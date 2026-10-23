// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IGetOntoBaseCheckGitAction
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.OntoBaseCheck
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

/** The most of the base's new commits that the dialog counts. */
private const val MAX_COUNTED_COMMITS = 10_000

/** What `git merge-tree` gives for the base and the branch: their merge's tree, or the files that conflict. */
internal sealed interface BaseMerge {
    data class Tree(val id: String) : BaseMerge
    data class Conflicts(val files: List<String>) : BaseMerge
}

/**
 * `git merge-tree --write-tree` of [onto] and [tip] in [place] (git 2.38 and later): exit 0 gives the merge's tree,
 * exit 1 the files that conflict, as `--name-only` lists them after it. The tree goes into the object database,
 * unreferenced, as git's own merges leave objects. Null when git can't (an older git refuses the option).
 */
internal suspend fun EitherContext<GitError>.mergeWithBase(
    gitCli: GitCli,
    place: BranchPlace,
    onto: String,
    tip: String,
): BaseMerge? {
    val args = place.gitArgs + listOf("merge-tree", "--write-tree", "--name-only", "--no-messages", onto, tip)
    val output = gitCli.execute(place.workingDirectory, args).bind()
    val lines = output.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }

    return when (output.exitCode) {
        0 -> lines.firstOrNull()?.let { BaseMerge.Tree(it) }
        1 -> BaseMerge.Conflicts(lines.drop(1).distinct())
        else -> null
    }
}

/**
 * The tree of [branch] squashed onto [onto], or refused when the merge conflicts ([RewriteBranchError.ConflictsWithBase])
 * or git can't merge this way.
 */
internal suspend fun EitherContext<GitError>.ontoBaseTree(
    gitCli: GitCli,
    place: BranchPlace,
    onto: OntoBase,
    branch: String,
    tip: String,
): String = when (val merge = mergeWithBase(gitCli, place, onto.commit, tip)) {
    is BaseMerge.Tree -> merge.id
    is BaseMerge.Conflicts -> raiseError(
        RewriteBranchError.ConflictsWithBase(
            branch = Repository.shortenRefName(branch),
            base = Repository.shortenRefName(onto.base),
            files = merge.files,
        )
    )

    null -> raiseError(
        GitCliError.CommandFailed("git merge-tree --write-tree", -1, "This git can't merge without a working tree.")
    )
}

/**
 * The files of [place]'s working tree, where the branch is checked out, that moving it from [from] to the tree [to]
 * would meet: tracked files that the move changes and that have uncommitted changes, staged or not, and anything on disk
 * where [to] adds a file or a folder, untracked or ignored. git's `read-tree -m -u` refuses the first two, and would
 * overwrite an ignored file, which git counts as expendable: Leaf counts it as in the way, as it may be a local `.env`.
 */
internal suspend fun EitherContext<GitError>.filesInTheWay(
    gitCli: GitCli,
    place: BranchPlace,
    from: String,
    to: String,
): List<String> {
    val diff = gitCli.execute(
        place.workingDirectory,
        place.gitArgs + listOf("diff-tree", "-r", "-z", "--no-renames", "--name-status", from, to),
    ).bind()

    if (diff.exitCode != 0) {
        raiseError(GitCliError.CommandFailed("git diff-tree", diff.exitCode, readableGitOutput(diff.stderr)))
    }

    val fields = diff.stdout.split('\u0000').filter { it.isNotEmpty() }
    val changed = fields.chunked(2).filter { it.size == 2 }.map { (status, path) -> status to path }

    if (changed.isEmpty()) {
        return emptyList()
    }

    val status = gitCli.execute(
        place.workingDirectory,
        place.gitArgs + listOf("status", "--porcelain=v1", "-z", "--untracked-files=no", "--no-renames"),
    ).bind()

    if (status.exitCode != 0) {
        raiseError(GitCliError.CommandFailed("git status", status.exitCode, readableGitOutput(status.stderr)))
    }

    val changedPaths = changed.map { it.second }.toSet()
    val tracked = status.stdout.split('\u0000').filter { it.length > 3 }.map { it.substring(3) }
    val inTheWay = sortedSetOf<String>()

    tracked.filterTo(inTheWay) { it in changedPaths }

    for ((kind, path) in changed) {
        if (kind == "A") {
            inTheWay.addAll(onDiskWhereAdded(place.workingDirectory, path))
        }
    }

    return inTheWay.toList()
}

/** What is on disk at [path], which a move adds, or at a folder of it that would have to be a folder. */
private fun onDiskWhereAdded(workTree: File, path: String): List<String> {
    val parts = path.split('/')

    for (end in 1 until parts.size) {
        val folder = parts.take(end).joinToString("/")
        val file = File(workTree, folder).toPath()

        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            return listOf(folder)
        }
    }

    return if (Files.exists(File(workTree, path).toPath(), LinkOption.NOFOLLOW_LINKS)) listOf(path) else emptyList()
}

/**
 * What putting a branch on the latest base would do (fork-only, `docs/fork/squash-and-rebase.md`, A4): the base's
 * commits that the branch lacks, `git merge-tree` of the two, and where the branch is checked out, the files in the way.
 * Null when there's no base, the branch has the base's tip, or git can't merge this way.
 */
class GetOntoBaseCheckGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) : IGetOntoBaseCheckGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        line: BranchLine,
    ): Either<OntoBaseCheck?, GitError> = jgit.provide(repositoryPath) { git ->
        check(git.repository, line)
    }

    private suspend fun EitherContext<GitError>.check(repository: Repository, line: BranchLine): OntoBaseCheck? {
        val base = line.base ?: return null
        val baseTip = repository.exactRef(base)?.objectId ?: return null
        val tip = repository.exactRef(line.branch)?.objectId ?: return null

        val newCommits = RevWalk(repository).use { walk ->
            val baseCommit = walk.parseCommit(baseTip)
            val tipCommit = walk.parseCommit(tip)

            if (walk.isMergedInto(baseCommit, tipCommit)) {
                return null
            }

            walk.reset()
            walk.markStart(baseCommit)
            walk.markUninteresting(tipCommit)
            walk.asSequence().take(MAX_COUNTED_COMMITS).count()
        }

        val onto = OntoBase(base, baseTip.name)
        val place = branchPlace(repository, line.branch)

        return when (val merge = mergeWithBase(gitCli, place, baseTip.name, tip.name)) {
            null -> null
            is BaseMerge.Conflicts -> OntoBaseCheck.Conflicts(onto, newCommits, merge.files)
            is BaseMerge.Tree -> {
                val inTheWay = if (place.hasBranchCheckedOut) {
                    filesInTheWay(gitCli, place, tip.name, merge.id)
                } else {
                    emptyList()
                }

                if (inTheWay.isEmpty()) {
                    OntoBaseCheck.Clean(onto, newCommits)
                } else {
                    OntoBaseCheck.ChangesInTheWay(onto, newCommits, place.workingDirectory.path, inTheWay)
                }
            }
        }
    }
}
