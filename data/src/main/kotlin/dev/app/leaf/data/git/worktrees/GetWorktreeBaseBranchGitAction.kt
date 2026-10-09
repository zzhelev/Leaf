// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.config.WorktreeBaseBranchConfig
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetWorktreeBaseBranchGitAction
import dev.app.leaf.domain.models.WorktreeBaseBranch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

private const val ORIGIN_HEAD = "${Constants.R_REMOTES}${Constants.DEFAULT_REMOTE_NAME}/${Constants.HEAD}"

class GetWorktreeBaseBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetWorktreeBaseBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<WorktreeBaseBranch, GitError> =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                val repository = git.repository
                val chosen = WorktreeBaseBranchConfig.load(repository)

                WorktreeBaseBranch(
                    automatic = automaticBaseBranch(repository),
                    chosen = chosen,
                    chosenExists = chosen != null && repository.exactRef(chosen) != null,
                )
            }
        }
}

/** The branch `origin/HEAD` points to, then `main`, then `master`, whichever exists first as a local branch. */
private fun automaticBaseBranch(repository: Repository): String? {
    // origin/HEAD names the remote's default branch, such as refs/remotes/origin/develop
    val remoteDefault = repository.exactRef(ORIGIN_HEAD)
        ?.takeIf { it.isSymbolic }
        ?.target
        ?.name
        ?.removePrefix("${Constants.R_REMOTES}${Constants.DEFAULT_REMOTE_NAME}/")

    return listOfNotNull(remoteDefault, "main", Constants.MASTER)
        .map { Constants.R_HEADS + it }
        .firstOrNull { repository.exactRef(it) != null }
}
