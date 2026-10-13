// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.worktrees.unmergedPaths
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IAbortRebaseGitAction
import dev.app.leaf.domain.interfaces.IContinueRebaseGitAction
import dev.app.leaf.domain.interfaces.ISkipRebaseGitAction
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration

/** Where a rebase keeps its state in the git dir: git's merge backend and JGit's rebase both use it. */
private const val REBASE_MERGE = "rebase-merge"

/** Where git's apply backend (`rebase.backend=apply`) keeps a rebase's state. JGit can't continue it. */
private const val REBASE_APPLY = "rebase-apply"

/** A file that JGit's rebase writes in [REBASE_MERGE], and git's doesn't (2.54 checked). */
private const val JGIT_ONTO_NAME = "onto_name"

/** git's exit code when `git rebase --continue` or `--skip` stops at a later commit's conflicts. */
private const val STOPPED_EXIT_CODE = 1

/**
 * Whether the rebase in [gitDir] was started by git rather than by JGit (fork-only): from a terminal, by an agent, or
 * by "Rebase onto" in a worktree's menu. Then git has to go on with it, and the other way round:
 * - JGit's continue commits git's `message` file as it is, with git's `# Conflicts:` lines, and its continue, skip
 *   and abort leave `MERGE_MSG` (the stopped commit's message, which a commit from a terminal would take) and
 *   `REBASE_HEAD` behind. JGit doesn't know git's other todo commands either (`exec`, `update-ref`, `label`);
 * - git refuses to continue JGit's ("could not commit staged changes"), so Leaf's own rebases, interactive ones
 *   included, stay with JGit.
 */
internal fun isRebaseStartedByGit(gitDir: File): Boolean {
    if (File(gitDir, REBASE_APPLY).isDirectory) {
        return true
    }

    val rebaseMerge = File(gitDir, REBASE_MERGE)

    return rebaseMerge.isDirectory && !File(rebaseMerge, JGIT_ONTO_NAME).exists()
}

/** Runs `git rebase --continue`, `--skip` or `--abort` for a rebase that git started (fork-only). */
class GitCliRebase @Inject constructor(
    private val gitCli: GitCli,
    private val gitExecutableLocator: GitExecutableLocator,
    private val appSettingsService: AppSettingsService,
    private val jgit: JGit,
) {
    /** Whether git goes on with the rebase in [repositoryPath]: git started it, and Leaf has a usable git. */
    suspend fun runsRebaseIn(repositoryPath: String): Boolean {
        return isRebaseStartedByGit(File(repositoryPath)) &&
            gitExecutableLocator.locate(appSettingsService.gitExecutablePath.first()) is Either.Ok
    }

    /**
     * Runs `git rebase <option>` in the working tree of [repositoryPath], and returns whether it stopped at a later
     * commit's conflicts: git exits with 1 and the index has them, which only [mayStop] options can do. git keeps the
     * stopped commit's message as it is (`GIT_EDITOR=:`), without its `# Conflicts:` lines. No time limit, as git
     * runs hooks.
     */
    suspend fun run(repositoryPath: String, option: String, mayStop: Boolean): Either<Boolean, GitError> = either {
        val workTree = jgit.provide(repositoryPath) { git -> git.repository.workTree }.bind()
        val args = listOf("rebase", option)
        val output = gitCli.execute(
            workingDirectory = workTree,
            args = args,
            timeout = Duration.INFINITE,
            environment = mapOf("GIT_EDITOR" to ":"),
        ).bind()

        when {
            output.exitCode == 0 -> Either.Ok(false)
            mayStop && output.exitCode == STOPPED_EXIT_CODE && hasConflicts(workTree) -> Either.Ok(true)
            else -> {
                val message = output.stderr.trim().ifEmpty { output.stdout.trim() }

                Either.Err(GitCliError.CommandFailed("git " + args.joinToString(" "), output.exitCode, message))
            }
        }
    }

    private suspend fun hasConflicts(workTree: File): Boolean {
        val output = gitCli.run(workTree, listOf("ls-files", "--unmerged", "-z")).okOrNull() ?: return false

        return unmergedPaths(output).isNotEmpty()
    }
}

/** Continues a rebase with git when git started it, and with JGit otherwise (fork-only). */
class SelectingContinueRebaseGitAction @Inject constructor(
    private val gitCliRebase: GitCliRebase,
    private val jgitAction: ContinueRebaseGitAction,
) : IContinueRebaseGitAction {
    override suspend fun invoke(repositoryPath: String): Either<Boolean, GitError> {
        return if (gitCliRebase.runsRebaseIn(repositoryPath)) {
            gitCliRebase.run(repositoryPath, "--continue", mayStop = true)
        } else {
            jgitAction(repositoryPath)
        }
    }
}

/** Skips a rebase's commit with git when git started the rebase, and with JGit otherwise (fork-only). */
class SelectingSkipRebaseGitAction @Inject constructor(
    private val gitCliRebase: GitCliRebase,
    private val jgitAction: SkipRebaseGitAction,
) : ISkipRebaseGitAction {
    override suspend fun invoke(repositoryPath: String): Either<Boolean, GitError> {
        return if (gitCliRebase.runsRebaseIn(repositoryPath)) {
            gitCliRebase.run(repositoryPath, "--skip", mayStop = true)
        } else {
            jgitAction(repositoryPath)
        }
    }
}

/** Aborts a rebase with git when git started it, and with JGit otherwise (fork-only). */
class SelectingAbortRebaseGitAction @Inject constructor(
    private val gitCliRebase: GitCliRebase,
    private val jgitAction: AbortRebaseGitAction,
) : IAbortRebaseGitAction {
    override suspend fun invoke(repositoryPath: String): Either<Unit, GitError> {
        return if (gitCliRebase.runsRebaseIn(repositoryPath)) {
            gitCliRebase.run(repositoryPath, "--abort", mayStop = false).mapOk { }
        } else {
            jgitAction(repositoryPath)
        }
    }
}
