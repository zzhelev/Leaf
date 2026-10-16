// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.operations

import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.MergeBranchGitAction
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitCliOutput
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.data.git.log.CherryPickCommitGitAction
import dev.app.leaf.data.git.log.RevertCommitGitAction
import dev.app.leaf.data.git.rebase.RebaseBranchGitAction
import dev.app.leaf.data.git.worktrees.unmergedPaths
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.ICherryPickCommitGitAction
import dev.app.leaf.domain.interfaces.IMergeBranchGitAction
import dev.app.leaf.domain.interfaces.IRebaseBranchGitAction
import dev.app.leaf.domain.interfaces.IRevertCommitGitAction
import dev.app.leaf.domain.interfaces.IsMultiStep
import dev.app.leaf.domain.models.AutostashOperation
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.refArgument
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

private const val TAG = "GitCliOperations"

/** git's exit code when a merge, cherry-pick, revert or rebase stops at conflicts, and when some of them refuse. */
private const val STOPPED_EXIT_CODE = 1

/** git keeps the message it has, rather than opening the user's `GIT_EDITOR` or `core.editor`. */
private val NO_EDITOR = mapOf("GIT_EDITOR" to ":")

/**
 * Runs the git commands that merge, cherry-pick, revert and rebase (fork-only), as a terminal would: with git's merge
 * engine (ort, which follows renames), git's messages, every hook that git runs, and the identity and signing settings
 * that git reads. JGit runs them when git can't ([useGitCli]).
 *
 * They go through [GitCliRemoteCommand], as git may have to ask: it downloads what a partial clone lacks, git-lfs's
 * filter downloads LFS files, and ssh-keygen asks for the passphrase of a key that signs commits. So the processing
 * screen can cancel them too: git gets SIGTERM, and removes its lock files.
 */
class GitCliOperations @Inject constructor(
    private val gitCli: GitCli,
    private val remoteCommand: GitCliRemoteCommand,
    private val gitExecutableLocator: GitExecutableLocator,
    private val askpassHelper: AskpassHelper,
    private val appSettingsService: AppSettingsService,
    private val jgit: JGit,
    private val autostashes: Autostashes,
) {
    /** Whether git runs these operations: Leaf found a usable git, and has the askpass helper. */
    suspend fun useGitCli(): Boolean {
        if (gitExecutableLocator.locate(appSettingsService.gitExecutablePath.first()) is Either.Err) {
            printLog(TAG, "Using JGit, as no usable git was found")
            return false
        }

        if (askpassHelper.path() == null) {
            printLog(TAG, "Using JGit, as the askpass helper is missing")
            return false
        }

        return true
    }

    /**
     * Runs `git <args>` in the working tree of [repositoryPath], and returns whether it stopped at conflicts: git
     * exited with 1 and the index has unmerged paths, which only [mayStop] commands can leave. git also exits with 1
     * when it refuses, as for a rebase with local changes, or a merge of something that isn't a commit: that's a
     * failure, as is any other exit code but 0, such as 128 for a merge while conflicts are left. No time limit, as
     * git runs hooks.
     *
     * A merge or a rebase that ends, aborted too, puts back what `merge.autoStash` or `rebase.autoStash` set aside.
     * When that doesn't apply, git keeps it in the stash list as "autostash", exits with 0 nonetheless, and the tab
     * shows a notice ([Autostashes.noticeIfKept]).
     */
    suspend fun run(
        repositoryPath: String,
        args: List<String>,
        mayStop: Boolean = true,
    ): Either<Boolean, GitError> = either {
        val workTree = workTree(repositoryPath).bind()
        val autostashOperation = autostashOperation(args)
        val stashBefore = autostashOperation?.let { autostashes.newestStash(workTree) }
        val output = remoteCommand.run(workTree, args, environment = NO_EDITOR).bind()

        if (autostashOperation != null && output.exitCode == 0) {
            val after = if ("--abort" in args) AutostashPutBackAfter.ABORT else AutostashPutBackAfter.OPERATION
            autostashes.noticeIfKept(workTree, stashBefore, autostashOperation, after)
        }

        when {
            output.exitCode == 0 -> Either.Ok(false)
            mayStop && output.exitCode == STOPPED_EXIT_CODE && hasConflicts(workTree) -> Either.Ok(true)
            else -> Either.Err(
                GitCliError.CommandFailed("git " + args.joinToString(" "), output.exitCode, failureMessage(output))
            )
        }
    }

    /**
     * How git names the branch [ref] in [repositoryPath] ([refArgument]): its short name, unless git takes that for
     * something else, such as a tag of the same name.
     */
    suspend fun branchArgument(repositoryPath: String, ref: String): Either<String, GitError> = either {
        val workTree = workTree(repositoryPath).bind()
        val argument = refArgument(ref) { name ->
            gitCli.run(workTree, listOf("rev-parse", "--symbolic-full-name", name)).okOrNull()?.trim()
        }

        Either.Ok(argument)
    }

    private suspend fun workTree(repositoryPath: String): Either<File, GitError> {
        return jgit.provide(repositoryPath) { git -> git.repository.workTree }
    }

    private suspend fun hasConflicts(workTree: File): Boolean {
        val output = gitCli.run(workTree, listOf("ls-files", "--unmerged", "-z")).okOrNull() ?: return false

        return unmergedPaths(output).isNotEmpty()
    }
}

/** What git may set the user's changes aside for, as `git <args>` runs: a merge or a rebase. */
private fun autostashOperation(args: List<String>): AutostashOperation? = when (args.firstOrNull()) {
    "merge" -> AutostashOperation.MERGE
    "rebase" -> AutostashOperation.REBASE
    else -> null
}

/**
 * What git said about its failure: its errors, or else what it printed, as `git revert` of a commit already undone
 * says only "nothing to commit", on stdout.
 */
private fun failureMessage(output: GitCliOutput): String {
    return readableGitOutput(output.stderr).ifEmpty { output.stdout.trim() }
}

/** Merges with git when it can ([GitCliOperations.useGitCli]), and with JGit otherwise (fork-only). */
class SelectingMergeBranchGitAction @Inject constructor(
    private val operations: GitCliOperations,
    private val gitCliAction: GitCliMergeBranchGitAction,
    private val jgitAction: MergeBranchGitAction,
) : IMergeBranchGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        branch: Branch,
        fastForward: Boolean,
    ): Either<Boolean, GitError> = if (operations.useGitCli()) {
        gitCliAction(repositoryPath, branch, fastForward)
    } else {
        jgitAction(repositoryPath, branch, fastForward)
    }
}

/** Cherry-picks with git when it can ([GitCliOperations.useGitCli]), and with JGit otherwise (fork-only). */
class SelectingCherryPickCommitGitAction @Inject constructor(
    private val operations: GitCliOperations,
    private val gitCliAction: GitCliCherryPickCommitGitAction,
    private val jgitAction: CherryPickCommitGitAction,
) : ICherryPickCommitGitAction {
    override suspend fun invoke(repositoryPath: String, commit: Commit): Either<Unit, GitError> =
        if (operations.useGitCli()) {
            gitCliAction(repositoryPath, commit)
        } else {
            jgitAction(repositoryPath, commit)
        }
}

/** Reverts with git when it can ([GitCliOperations.useGitCli]), and with JGit otherwise (fork-only). */
class SelectingRevertCommitGitAction @Inject constructor(
    private val operations: GitCliOperations,
    private val gitCliAction: GitCliRevertCommitGitAction,
    private val jgitAction: RevertCommitGitAction,
) : IRevertCommitGitAction {
    override suspend fun invoke(repositoryPath: String, commit: Commit): Either<Unit, GitError> =
        if (operations.useGitCli()) {
            gitCliAction(repositoryPath, commit)
        } else {
            jgitAction(repositoryPath, commit)
        }
}

/**
 * Rebases with git when it can ([GitCliOperations.useGitCli]), and with JGit otherwise (fork-only). Continue, Skip
 * and Abort then go to the one that started it (`isRebaseStartedByGit`).
 */
class SelectingRebaseBranchGitAction @Inject constructor(
    private val operations: GitCliOperations,
    private val gitCliAction: GitCliRebaseBranchGitAction,
    private val jgitAction: RebaseBranchGitAction,
) : IRebaseBranchGitAction {
    override suspend fun invoke(repositoryPath: String, branch: Branch): Either<IsMultiStep, GitError> =
        if (operations.useGitCli()) {
            gitCliAction(repositoryPath, branch)
        } else {
            jgitAction(repositoryPath, branch)
        }
}
