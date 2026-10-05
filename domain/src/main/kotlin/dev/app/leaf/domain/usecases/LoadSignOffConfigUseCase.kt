package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RepositoryPathNotSetError
import dev.app.leaf.domain.interfaces.ILoadSignOffConfigGitAction
import dev.app.leaf.domain.models.SignOffConfig
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

class LoadSignOffConfigUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val loadSignOffConfigGitAction: ILoadSignOffConfigGitAction,
) {
    suspend operator fun invoke(): Either<SignOffConfig, GitError> {
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return Either.Err(RepositoryPathNotSetError)
        return loadSignOffConfigGitAction(repositoryPath)
    }
}