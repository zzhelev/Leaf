// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IWriteConflictResultGitAction
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.TextMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.util.io.EolStreamTypeUtil
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

private const val ABBREVIATED_LENGTH = 7

/**
 * Writes the resolver's result into the working file (`conflict-resolver.md`, 2.3), as git's merge writes a file
 * with conflicts:
 * - the conflicts still open get markers in `merge.conflictStyle`'s style, of the `conflict-marker-size` attribute's
 *   length, labelled as git labels them (`conflictLabels`);
 * - line endings are converted as a checkout converts them (`core.autocrlf`, `core.eol`, the `text` and `eol`
 *   attributes): the versions come from the index, which may have LF where the working tree has CRLF;
 * - the file is written in place, so its mode stays. A file that isn't there any more is created, executable when the
 *   side it comes from is.
 *
 * It refuses, and writes nothing, when the index's versions of the file changed since it was loaded (resolved or
 * aborted elsewhere, or another operation stopped there), when something other than a file is at its path, and when
 * the working file changed since, unless `overwriteChanges`.
 */
class WriteConflictResultGitAction @Inject constructor(
    private val jgit: JGit,
) : IWriteConflictResultGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        file: ConflictedFile,
        merge: TextMerge,
        overwriteChanges: Boolean,
    ): Either<Unit, GitError> = withContext(Dispatchers.IO) {
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val loaded = requireNotNull(file.content as? ConflictContent.Text) {
                "Only a text file's merge is written, not ${file.content}"
            }

            val entries = repository.readDirCache().stagesOf(file.path)

            if (entries.none { it.stage != DirCacheEntry.STAGE_0 }) {
                raiseError(ConflictError.NotConflicted(listOf(file.path)))
            }

            fun stage(stage: Int) =
                entries.firstOrNull { it.stage == stage }?.let { ConflictStage(it.objectId.name, it.rawMode) }

            if (stage(DirCacheEntry.STAGE_1) != file.base ||
                stage(DirCacheEntry.STAGE_2) != file.ours ||
                stage(DirCacheEntry.STAGE_3) != file.theirs
            ) {
                raiseError(ConflictError.StagesChanged(file.path))
            }

            val target = File(repository.workTree, file.path)
            val targetPath = target.toPath()
            val exists = Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)

            if (exists && !Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                raiseError(ConflictError.NotAFile(file.path))
            }

            val currentId = if (exists) blobId(target.readBytes()) else null

            if (!overwriteChanges && currentId != loaded.workingFile.contentId) {
                raiseError(ConflictError.WorkingFileChanged(file.path))
            }

            val attributes = conflictFileAttributes(repository, file.path)
            val style = ConflictMarkerStyle(
                withBase = conflictStyleWithBase(repository.config),
                labels = conflictLabels(repository, file.sides),
                size = attributes.markerSize,
            )
            val bytes = merge.resultBytes(style)

            target.parentFile.mkdirs()

            // Truncated and written in place, which keeps the file's mode
            FileOutputStream(target).use { out ->
                EolStreamTypeUtil.wrapOutputStream(out, attributes.checkoutLineEndings).use { it.write(bytes) }
            }

            if (!exists && listOfNotNull(file.ours, file.theirs).any { it.mode == FileMode.EXECUTABLE_FILE.bits }) {
                repository.fs.setExecute(target, true)
            }
        }
    }
}

/**
 * What follows the markers, as git writes them (checked against git 2.54):
 * - ours is always `HEAD`;
 * - a merge: theirs is the branch merged, and the base the merge base's abbreviated id (`merged common ancestors` when
 *   there are several);
 * - a rebase or a cherry-pick: theirs is `<id> (<subject>)` of the commit replayed, and the base `parent of …`;
 * - a revert: the other way round, as it applies the commit's parent;
 * - a stash that didn't apply cleanly: git stash's `Updated upstream`, `Stash base` and `Stashed changes`.
 */
internal fun conflictLabels(repository: Repository, sides: ConflictSides?): ConflictLabels {
    val theirs = sides?.theirs
    val ours = Constants.HEAD

    return when (sides?.operation) {
        ConflictOperation.MERGE -> ConflictLabels(
            ours = ours,
            base = mergeBaseLabel(repository),
            theirs = theirs?.let { it.branch ?: it.hash } ?: "theirs",
        )

        ConflictOperation.REBASE, ConflictOperation.CHERRY_PICK -> ConflictLabels(
            ours = ours,
            base = theirs?.let { "parent of ${it.label()}" } ?: "base",
            theirs = theirs?.label() ?: "theirs",
        )

        ConflictOperation.REVERT -> ConflictLabels(
            ours = ours,
            base = theirs?.label() ?: "base",
            theirs = theirs?.let { "parent of ${it.label()}" } ?: "theirs",
        )

        ConflictOperation.OTHER, null -> ConflictLabels(
            ours = "Updated upstream",
            base = "Stash base",
            theirs = "Stashed changes",
        )
    }
}

private fun ConflictCommit.label() = "$hash ($subject)"

/** The merge base of HEAD and `MERGE_HEAD`, abbreviated, as git labels the base of a merge's conflicts. */
private fun mergeBaseLabel(repository: Repository): String {
    val head = repository.resolve(Constants.HEAD)
    val mergeHead = repository.readMergeHeads()?.singleOrNull()

    if (head == null || mergeHead == null) {
        return "base"
    }

    val bases = RevWalk(repository).use { walk ->
        walk.revFilter = RevFilter.MERGE_BASE
        walk.markStart(walk.parseCommit(head))
        walk.markStart(walk.parseCommit(mergeHead))
        walk.take(2).map { ObjectId.toString(it) }
    }

    return bases.singleOrNull()?.take(ABBREVIATED_LENGTH) ?: "merged common ancestors"
}
