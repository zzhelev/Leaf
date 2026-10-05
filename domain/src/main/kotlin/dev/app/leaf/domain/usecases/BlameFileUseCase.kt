package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RepositoryPathNotSetError
import dev.app.leaf.domain.interfaces.IBlameFileGitAction
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import org.eclipse.jgit.blame.BlameResult
import javax.inject.Inject

class BlameFileUseCase @Inject constructor(
    private val repositoryDataRepository: RepositoryDataRepository,
    private val blameFileGitAction: IBlameFileGitAction,
) {
    suspend operator fun invoke(filePath: String): Either<BlameResult, GitError> {
        val repositoryPath = repositoryDataRepository.repositoryPath ?: return Either.Err(RepositoryPathNotSetError)
        return blameFileGitAction(repositoryPath, filePath)
    }
}