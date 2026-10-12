// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IPersistSquashMessageGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PersistSquashMessageGitAction @Inject constructor(
    private val jgit: JGit,
) : IPersistSquashMessageGitAction {
    override suspend operator fun invoke(repositoryPath: String, message: String?): Either<Unit, GitError> =
        jgit.provide(repositoryPath) { git ->
            // The worktree's own git dir, where git looks for SQUASH_MSG, also in a linked worktree
            withContext(Dispatchers.IO) {
                git.repository.writeSquashCommitMsg(message)
            }
        }
}
