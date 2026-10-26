// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.usesLfs
import dev.app.leaf.data.git.config.GitLfsNoticeConfig
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IDismissGitLfsNoticeGitAction
import dev.app.leaf.domain.interfaces.IGetGitLfsNoticeGitAction
import dev.app.leaf.domain.interfaces.ISetUpGitLfsGitAction
import dev.app.leaf.domain.lfs.GitLfsNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/** git-lfs only writes config and hooks, but asks nothing, so a minute is plenty. */
private val SET_UP_TIMEOUT = 1.minutes

/**
 * Tells what git outside Leaf lacks for a repository's LFS files (fork-only), with [GitLfsSetup]'s rule, the one that
 * picks Leaf's filter for the commands that Leaf runs: Leaf's filter there means none for a terminal's or an agent's
 * git. A repository without LFS, or without a working tree, gets none.
 */
class GetGitLfsNoticeGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitLfsSetup: GitLfsSetup,
) : IGetGitLfsNoticeGitAction {
    override suspend fun invoke(repositoryPath: String): Either<GitLfsNotice?, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            if (repository.isBare || !withContext(Dispatchers.IO) { usesLfs(repository) }) {
                return@provide null
            }

            val workTree = repository.workTree
            val notice = when {
                gitLfsSetup.gitNamesGitLfs(workTree) ->
                    GitLfsNotice.MISSING.takeUnless { gitLfsSetup.gitLfsWorks(workTree) }
                gitLfsSetup.gitLfsWorks(workTree) -> GitLfsNotice.NOT_SET_UP
                else -> GitLfsNotice.NOT_INSTALLED
            }

            notice?.takeUnless { it == GitLfsNoticeConfig.dismissed(repository) }
        }
}

class DismissGitLfsNoticeGitAction @Inject constructor(
    private val jgit: JGit,
) : IDismissGitLfsNoticeGitAction {
    override suspend fun invoke(repositoryPath: String, notice: GitLfsNotice): Either<Unit, GitError> =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) { GitLfsNoticeConfig.dismiss(git.repository, notice) }
        }
}

/**
 * Runs `git lfs install` in the working tree (fork-only), as git-lfs's own instructions say: it gives the LFS filter
 * git-lfs's commands in the user's git config, and installs git-lfs's hooks in the repository, where `core.hooksPath`
 * or a linked worktree's common git dir says. A hook of another tool in the way stops the hooks, not the filter:
 * git-lfs then exits with 2, and its message, which says how to merge them, is the error.
 */
class SetUpGitLfsGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
    private val gitLfsSetup: GitLfsSetup,
) : ISetUpGitLfsGitAction {
    override suspend fun invoke(repositoryPath: String): Either<Unit, GitError> {
        val workTree = when (val result = jgit.provide(repositoryPath) { git -> git.repository.workTree }) {
            is Either.Err -> return result
            is Either.Ok -> result.value
        }

        val result = gitCli.run(workTree, listOf("lfs", "install"), SET_UP_TIMEOUT)

        // It works now, or the user installed it since the last check
        gitLfsSetup.forgetGitLfsCheck()

        return when (result) {
            is Either.Err -> result
            is Either.Ok -> Either.Ok(Unit)
        }
    }
}
