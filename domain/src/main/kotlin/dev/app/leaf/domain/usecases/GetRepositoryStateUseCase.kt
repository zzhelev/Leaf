package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RepositoryPathNotSetError
import dev.app.leaf.domain.interfaces.IGetRepositoryStateGitAction
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import javax.inject.Inject

class GetRepositoryStateUseCase @Inject constructor(
    private val gitRepositoryStateGitAction: IGetRepositoryStateGitAction,
    private val repositoryDataRepository: RepositoryDataRepository,
) {
    suspend operator fun invoke(): Either<RepositoryState, GitError> {
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return Either.Err(RepositoryPathNotSetError)
        return gitRepositoryStateGitAction(repositoryPath)
    }
}