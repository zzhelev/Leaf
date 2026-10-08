package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IDeleteBranchGitAction
import dev.app.leaf.domain.models.Branch
import javax.inject.Inject

class DeleteBranchUseCase @Inject constructor(
    private val deleteBranchGitAction: IDeleteBranchGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    /** Returns the error to the caller, which is the delete dialog, so it can offer to delete with [force]. */
    suspend operator fun invoke(branch: Branch, force: Boolean): Either<Unit, AppError> {
        return useCaseExecutor.execute(
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            deleteBranchGitAction(repositoryPath, branch, force)
        }
    }
}