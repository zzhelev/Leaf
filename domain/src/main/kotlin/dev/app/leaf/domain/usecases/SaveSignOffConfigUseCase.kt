package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RepositoryPathNotSetError
import dev.app.leaf.domain.interfaces.ILoadSignOffConfigGitAction
import dev.app.leaf.domain.interfaces.ISaveLocalRepositoryConfigGitAction
import dev.app.leaf.domain.models.SignOffConfig
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

class SaveSignOffConfigUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val saveLocalRepositoryConfigGitAction: ISaveLocalRepositoryConfigGitAction,
) {
    suspend operator fun invoke(signOffConfig: SignOffConfig): Either<Unit, GitError> {
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return Either.Err(RepositoryPathNotSetError)
        return saveLocalRepositoryConfigGitAction(repositoryPath, signOffConfig)
    }
}