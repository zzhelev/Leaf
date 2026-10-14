// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IResolveRenamedFileGitAction
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.RenamedFile
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

/**
 * Resolves a file that each side renamed differently (`conflict-resolver.md`, section 9) with the git commands that git
 * itself suggests: `git rm` for the old name and a new name not kept, `git add` for a name kept. A name kept whose
 * working file has conflict markers, as git leaves it when both sides' changes to the content conflict, gets the three
 * stages of a content conflict instead (`git update-index --index-info`), and its working file is written again with
 * the resolver's markers, unless it was edited since.
 */
class ResolveRenamedFileGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) : IResolveRenamedFileGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        file: RenamedFile,
        keep: Set<ConflictSide>,
    ): Either<Unit, GitError> = either {
        require(keep.isNotEmpty()) { "A name to keep" }

        val plan = jgit.provide(repositoryPath) { git -> plan(git.repository, file, keep) }.bind()
        val dropped = listOf(file.original) + (ConflictSide.entries - keep).map { file.path(it) }

        gitCli.run(plan.workTree, listOf("--literal-pathspecs", "rm", "--quiet", "--") + dropped).bind()

        for ((side, path) in plan.missing) {
            gitCli.run(plan.workTree, listOf("--literal-pathspecs", "checkout", side.checkoutOption, "--", path)).bind()
        }

        if (plan.staged.isNotEmpty()) {
            gitCli.run(plan.workTree, listOf("--literal-pathspecs", "add", "--") + plan.staged).bind()
        }

        if (plan.conflicts.isNotEmpty()) {
            val info = plan.conflicts.joinToString("") { conflict ->
                // Mode 0 takes the path's entry out first, as update-index wants before stages
                "0 ${ObjectId.zeroId().name}\t${conflict.path}\n" +
                    conflict.stages.withIndex().joinToString("") { (i, id) ->
                        "${Integer.toOctalString(conflict.mode)} ${id.name} ${i + 1}\t${conflict.path}\n"
                    }
            }

            gitCli.execute(plan.workTree, listOf("update-index", "--index-info"), input = info.toByteArray())
                .bind()
                .let { output ->
                    if (output.exitCode != 0) {
                        raiseError(GitCliError.CommandFailed("git update-index --index-info", output.exitCode, output.stderr.trim()))
                    }
                }

            jgit.provide(repositoryPath) { git ->
                for (conflict in plan.conflicts) {
                    writeMarkersAgain(git.repository, conflict)
                }
            }.bind()
        }

        Either.Ok(Unit)
    }
}

private class RenamePlan(
    val workTree: File,
    /** Names kept whose working file has no markers: `git add`. */
    val staged: List<String>,
    /** Names kept without a working file: checked out from their side first. */
    val missing: List<Pair<ConflictSide, String>>,
    val conflicts: List<ContentConflict>,
)

/** A name kept that becomes a conflict of its content: [stages] are the base's, ours' and theirs' blobs. */
private class ContentConflict(val path: String, val mode: Int, val stages: List<ObjectId>, val gitsMerge: ObjectId)

/**
 * What resolving [file] takes, read before anything changes: it must still be the same three conflicts, as another
 * operation may have stopped there since.
 */
private fun EitherContext<GitError>.plan(repository: Repository, file: RenamedFile, keep: Set<ConflictSide>): RenamePlan {
    val index = repository.readDirCache()

    if (file !in renamedFiles(repository, index)) {
        raiseError(ConflictError.StagesChanged(file.original))
    }

    val workTree = repository.workTree
    val kept = keep.sorted().map { side -> side to file.path(side) }
    val (withMarkers, clean) = kept.partition { (_, path) -> hasConflictMarkers(File(workTree, path)) }
    val missing = clean.filterNot { (_, path) -> Files.exists(File(workTree, path).toPath(), LinkOption.NOFOLLOW_LINKS) }

    fun unknown(): Nothing = raiseError(ConflictError.RenamedContentUnknown(file.original))

    val conflicts = if (withMarkers.isEmpty()) {
        emptyList()
    } else {
        val head = repository.resolve(Constants.HEAD) ?: unknown()
        val theirs = theirsCommit(repository) ?: unknown()
        val stages = listOf(
            index.stagesOf(file.original).single().objectId,
            repository.blobAt(head, file.ours) ?: unknown(),
            repository.blobAt(theirs, file.theirs) ?: unknown(),
        )

        withMarkers.map { (_, path) ->
            val entry = index.stagesOf(path).single()

            ContentConflict(path, entry.rawMode, stages, gitsMerge = entry.objectId)
        }
    }

    return RenamePlan(workTree, clean.map { it.second }, missing, conflicts)
}

/**
 * Writes [conflict]'s working file again with the resolver's markers, from its new stages, when it's still what git's
 * merge wrote. git's markers there are a character longer than the `conflict-marker-size` attribute says, so neither
 * git rerere nor the resolver would see them as markers.
 */
private fun writeMarkersAgain(repository: Repository, conflict: ContentConflict) {
    val target = File(repository.workTree, conflict.path).toPath()
    val attributes = conflictFileAttributes(repository, conflict.path)

    if (attributes.filtered || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        return
    }

    if (blobId(checkedIn(Files.readAllBytes(target), attributes.checkinLineEndings)) != conflict.gitsMerge.name) {
        return
    }

    val loaded = loadConflictedFile(repository, conflict.path) ?: return
    val text = loaded.content as? ConflictContent.Text ?: return

    writeMergeResult(repository, loaded, text.merge)
}
