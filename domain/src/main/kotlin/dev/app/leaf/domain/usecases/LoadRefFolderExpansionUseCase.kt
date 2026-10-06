// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RepositoryPathNotSetError
import dev.app.leaf.domain.interfaces.ILoadRefFolderExpansionGitAction
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.sorting.RefFolderExpansion
import javax.inject.Inject

class LoadRefFolderExpansionUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val loadRefFolderExpansionGitAction: ILoadRefFolderExpansionGitAction,
) {
    suspend operator fun invoke(): Either<RefFolderExpansion, GitError> {
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return Either.Err(RepositoryPathNotSetError)
        return loadRefFolderExpansionGitAction(repositoryPath)
    }
}
