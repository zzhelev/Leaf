// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.data.git.worktrees.readTextOrNull
import dev.app.leaf.data.git.worktrees.worktreesUsing
import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.eclipse.jgit.lib.Repository
import java.io.File
import kotlin.time.Duration

/** What git prints when `update-ref` finds the ref elsewhere than the old value it was given. */
private const val MOVED_MESSAGE = "but expected"

/**
 * Where git runs for a local branch that is rewritten (fork-only): in the worktree that has it checked out, as a
 * terminal there would, so that its HEAD's reflog says that HEAD moved, or in the tab's when none has. [gitArgs] name
 * the git dir when the worktree's folder is gone, or the repository is bare.
 */
internal class BranchPlace(
    val workingDirectory: File,
    val gitArgs: List<String>,
)

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
        placeOf(user.gitDir, File(user.path))
    } else {
        placeOf(repository.directory, if (repository.isBare) null else repository.workTree)
    }
}

private fun placeOf(gitDir: File, workTree: File?): BranchPlace {
    return if (workTree != null && workTree.isDirectory) {
        BranchPlace(workTree, emptyList())
    } else {
        BranchPlace(gitDir, listOf("--git-dir=${gitDir.absolutePath}"))
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
