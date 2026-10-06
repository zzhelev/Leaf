// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RepositoryPathNotSetError
import dev.app.leaf.domain.interfaces.ISaveRefFolderExpansionGitAction
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.sorting.RefFolderExpansion
import javax.inject.Inject

class SaveRefFolderExpansionUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val saveRefFolderExpansionGitAction: ISaveRefFolderExpansionGitAction,
) {
    suspend operator fun invoke(expansion: RefFolderExpansion): Either<Unit, GitError> {
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return Either.Err(RepositoryPathNotSetError)
        return saveRefFolderExpansionGitAction(repositoryPath, expansion)
    }
}
