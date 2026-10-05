package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetRebaseInteractiveTodoLinesGitAction
import dev.app.leaf.domain.models.RebaseLine
import javax.inject.Inject

class GetRebaseInteractiveTodoLinesUseCase @Inject constructor(
    private val getRebaseInteractiveTodoLinesGitAction: IGetRebaseInteractiveTodoLinesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(): Either<List<RebaseLine>, AppError> {
        return useCaseExecutor.execute(
        ) { repositoryPath ->
            getRebaseInteractiveTodoLinesGitAction(repositoryPath)
        }
    }
}