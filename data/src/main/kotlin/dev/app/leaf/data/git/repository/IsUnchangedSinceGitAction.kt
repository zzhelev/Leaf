// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IIsUnchangedSinceGitAction
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryState
import javax.inject.Inject

class IsUnchangedSinceGitAction @Inject constructor(
    private val jgit: JGit,
) : IIsUnchangedSinceGitAction {
    override suspend fun invoke(repositoryPath: String, commitHash: String): Either<Boolean, GitError> =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            repository.resolve(Constants.HEAD)?.name == commitHash &&
                repository.repositoryState == RepositoryState.SAFE
        }
}
