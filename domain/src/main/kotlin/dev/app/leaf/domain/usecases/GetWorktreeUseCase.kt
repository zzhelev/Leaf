package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetWorktreePathGitAction
import javax.inject.Inject

class GetWorktreeUseCase @Inject constructor(
    private val getWorktreePathGitAction: IGetWorktreePathGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): Either<String, AppError> {
        return useCaseExecutor.execute(
        ) { repositoryPath ->
            getWorktreePathGitAction(repositoryPath)
        }
    }
}