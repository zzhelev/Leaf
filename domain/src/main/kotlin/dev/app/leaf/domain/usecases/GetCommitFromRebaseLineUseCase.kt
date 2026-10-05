package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetCommitFromRebaseLineGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

class GetCommitFromRebaseLineUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getCommitFromRebaseGitAction: IGetCommitFromRebaseLineGitAction,
) {
    suspend operator fun invoke(commitShortHash: String, shortMessage: String): Either<Commit?, AppError> {
        return useCaseExecutor.execute { repositoryPath ->
            getCommitFromRebaseGitAction(repositoryPath, commitShortHash, shortMessage)
        }
    }
}