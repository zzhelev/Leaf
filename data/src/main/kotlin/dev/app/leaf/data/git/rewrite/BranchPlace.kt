// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.data.git.worktrees.readTextOrNull
import dev.app.leaf.data.git.worktrees.worktreesUsing
import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.eclipse.jgit.lib.Repository
import java.io.File
import kotlin.time.Duration

private const val TAG = "BranchPlace"

/** What git prints when `update-ref` finds the ref elsewhere than the old value it was given. */
private const val MOVED_MESSAGE = "but expected"

/** What git prints for each file that `read-tree -m -u` won't change: uncommitted changes, or an untracked file. */
private val FILES_IN_THE_WAY = listOf(
    Regex("Entry '(.+)' not uptodate"),
    Regex("Untracked working tree file '(.+)' would be overwritten"),
    Regex("Entry '(.+)' would be overwritten"),
)

/**
 * Where git runs for a local branch that is rewritten (fork-only): in the worktree that has it checked out, as a
 * terminal there would, so that its HEAD's reflog says that HEAD moved, or in the tab's when none has. [gitArgs] name
 * the git dir when the worktree's folder is gone, or the repository is bare. [hasBranchCheckedOut] says that the
 * branch is checked out in [workingDirectory], whose files follow it. [gitDir] is that worktree's git dir, null in a
 * test's place.
 */
internal class BranchPlace(
    val workingDirectory: File,
    val gitArgs: List<String>,
    val hasBranchCheckedOut: Boolean = false,
    val gitDir: File? = null,
) {
    /** Whether git runs in a working tree, which a rebase needs: not when the folder is gone, or the repository is bare. */
    val hasWorkingTree: Boolean
        get() = gitArgs.isEmpty()
}

/**
 * The [BranchPlace] of [branch] (`refs/heads/x`). Refused while a worktree rebases the branch or bisects from it, or
 * the worktree that has it checked out is in the middle of a merge, cherry-pick, revert, rebase or `git am`: moving the
 * branch under them would leave them on commits that it no longer has.
 */
internal fun EitherContext<GitError>.branchPlace(repository: Repository, branch: String): BranchPlace {
    val branchName = Repository.shortenRefName(branch)
    val users = repository.worktreesUsing(branch)

    for (user in users) {
        val operation = when (user.use) {
            WorktreeBranchUse.Rebasing -> BranchOperation.REBASE
            WorktreeBranchUse.Bisecting -> BranchOperation.BISECT
            WorktreeBranchUse.CheckedOut -> branchOperationIn(user.gitDir)
        }

        if (operation != null) {
            raiseError(RewriteBranchError.BranchBusy(branchName, user.path, operation))
        }
    }

    // git can check a branch out in two worktrees (`--ignore-other-worktrees`): the tab's own goes first
    val ownGitDir = repository.directory.canonicalFile
    val user = users.firstOrNull { it.gitDir == ownGitDir } ?: users.firstOrNull()

    return if (user != null) {
        placeOf(user.gitDir, File(user.path), isCheckedOut = true)
    } else {
        placeOf(repository.directory, if (repository.isBare) null else repository.workTree, isCheckedOut = false)
    }
}

private fun placeOf(gitDir: File, workTree: File?, isCheckedOut: Boolean): BranchPlace {
    return if (workTree != null && workTree.isDirectory) {
        BranchPlace(workTree, emptyList(), hasBranchCheckedOut = isCheckedOut, gitDir = gitDir)
    } else {
        BranchPlace(gitDir, listOf("--git-dir=${gitDir.absolutePath}"), gitDir = gitDir)
    }
}

/** What the worktree whose git dir is [gitDir] is in the middle of, from the files git keeps there. Null for nothing. */
internal fun branchOperationIn(gitDir: File): BranchOperation? = when {
    File(gitDir, "MERGE_HEAD").exists() -> BranchOperation.MERGE
    File(gitDir, "CHERRY_PICK_HEAD").exists() -> BranchOperation.CHERRY_PICK
    File(gitDir, "REVERT_HEAD").exists() -> BranchOperation.REVERT
    File(gitDir, "rebase-merge").exists() -> BranchOperation.REBASE
    File(gitDir, "rebase-apply/applying").exists() -> BranchOperation.APPLY_PATCHES
    File(gitDir, "rebase-apply").exists() -> BranchOperation.REBASE
    // A cherry-pick or a revert of several commits that local changes stopped keeps only its sequencer
    File(gitDir, "sequencer").exists() -> sequencerOperation(gitDir)
    File(gitDir, "BISECT_LOG").exists() -> BranchOperation.BISECT
    else -> null
}

private fun sequencerOperation(gitDir: File): BranchOperation {
    val firstCommand = File(gitDir, "sequencer/todo").readTextOrNull()
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.substringBefore(' ')

    return if (firstCommand == "revert" || firstCommand == "r") BranchOperation.REVERT else BranchOperation.CHERRY_PICK
}

/**
 * Moves [branch] from [from] to [to] with one `git update-ref` that expects [from], so that a branch that moved since
 * is refused ([RewriteBranchError.BranchMoved]) and stays where it is. git also writes the reflog of the HEAD that
 * points to the branch, in the worktree where it runs ([BranchPlace]), as after `git rebase`. No time limit, as git runs
 * the `reference-transaction` hook.
 */
internal suspend fun EitherContext<GitError>.moveBranch(
    gitCli: GitCli,
    place: BranchPlace,
    branch: String,
    from: String,
    to: String,
    reflogMessage: String,
) {
    val args = place.gitArgs + listOf("update-ref", "-m", reflogMessage, branch, to, from)
    val output = gitCli.execute(place.workingDirectory, args, timeout = Duration.INFINITE).bind()

    when {
        output.exitCode == 0 -> Unit
        output.stderr.contains(MOVED_MESSAGE) ->
            raiseError(RewriteBranchError.BranchMoved(Repository.shortenRefName(branch)))

        else -> raiseError(
            GitCliError.CommandFailed(
                "git update-ref $branch",
                output.exitCode,
                readableGitOutput(output.stderr),
            )
        )
    }
}

/**
 * Moves [branch] from [from] to [to], whose trees differ, as [moveBranch] does, and where it's checked out its working
 * tree and index with it (a squash onto the latest base, and its Undo). `git read-tree -m -u <from> <to>` first, which
 * changes only the files that differ between the two and keeps every other change, staged ones staged. Refused,
 * changing nothing, when uncommitted changes, untracked or ignored files are in the way ([filesInTheWay], and git's own
 * refusal: [RewriteBranchError.ChangesInTheWay]).
 * When the branch moved meanwhile, the files go back to [from], and it's refused as [moveBranch] refuses it.
 */
internal suspend fun EitherContext<GitError>.moveBranchWithFiles(
    gitCli: GitCli,
    place: BranchPlace,
    branch: String,
    from: String,
    to: String,
    reflogMessage: String,
) {
    if (!place.hasBranchCheckedOut) {
        return moveBranch(gitCli, place, branch, from, to, reflogMessage)
    }

    val inTheWay = filesInTheWay(gitCli, place, from, to)

    if (inTheWay.isNotEmpty()) {
        raiseError(
            RewriteBranchError.ChangesInTheWay(Repository.shortenRefName(branch), place.workingDirectory.path, inTheWay, "")
        )
    }

    readTree(gitCli, place, branch, from, to)

    val moved = either<Unit, GitError> { Either.Ok(moveBranch(gitCli, place, branch, from, to, reflogMessage)) }

    if (moved is Either.Err) {
        either<Unit, GitError> { Either.Ok(readTree(gitCli, place, branch, to, from)) }
            .let { if (it is Either.Err) printError(TAG, "Couldn't put the files back at $from: ${it.error}") }

        raiseError(moved.error)
    }
}

/** `git read-tree -m -u [from] [to]` in [place], the two-tree merge that `git checkout` makes. */
private suspend fun EitherContext<GitError>.readTree(
    gitCli: GitCli,
    place: BranchPlace,
    branch: String,
    from: String,
    to: String,
) {
    val args = place.gitArgs + listOf("read-tree", "-m", "-u", from, to)
    val output = gitCli.execute(place.workingDirectory, args, timeout = Duration.INFINITE).bind()

    if (output.exitCode == 0) {
        return
    }

    val files = FILES_IN_THE_WAY.flatMap { pattern -> pattern.findAll(output.stderr).map { it.groupValues[1] }.toList() }
    val gitOutput = readableGitOutput(output.stderr)

    if (files.isNotEmpty()) {
        raiseError(
            RewriteBranchError.ChangesInTheWay(
                branch = Repository.shortenRefName(branch),
                worktreePath = place.workingDirectory.path,
                files = files.distinct(),
                gitOutput = gitOutput,
            )
        )
    }

    raiseError(GitCliError.CommandFailed("git read-tree -m -u", output.exitCode, gitOutput))
}
