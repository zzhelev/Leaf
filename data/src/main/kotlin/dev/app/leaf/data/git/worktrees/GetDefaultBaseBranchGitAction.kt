// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetDefaultBaseBranchGitAction
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

private const val ORIGIN_HEAD = "${Constants.R_REMOTES}${Constants.DEFAULT_REMOTE_NAME}/${Constants.HEAD}"

class GetDefaultBaseBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetDefaultBaseBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<String?, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            // origin/HEAD names the remote's default branch, such as refs/remotes/origin/develop
            val remoteDefault = repository.exactRef(ORIGIN_HEAD)
                ?.takeIf { it.isSymbolic }
                ?.target
                ?.name
                ?.removePrefix("${Constants.R_REMOTES}${Constants.DEFAULT_REMOTE_NAME}/")

            listOfNotNull(remoteDefault, "main", Constants.MASTER)
                .map { Constants.R_HEADS + it }
                .firstOrNull { repository.exactRef(it) != null }
        }
}
