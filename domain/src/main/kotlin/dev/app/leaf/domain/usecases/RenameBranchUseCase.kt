package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IRenameBranchGitAction
import dev.app.leaf.domain.interfaces.ISetTrackingBranchGitAction
import javax.inject.Inject

class RenameBranchUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val renameBranchGitAction: IRenameBranchGitAction,
    private val setTrackingBranchGitAction: ISetTrackingBranchGitAction,
) {
    suspend operator fun invoke(oldName: String, newName: String): Either<Unit, AppError> {
        return useCaseExecutor.execute(
            dataToRefresh = arrayOf(DataToRefresh.BRANCHES, DataToRefresh.LOG),
        ) { repositoryPath ->
            val branch = renameBranchGitAction(repositoryPath, oldName, newName).bind()

            setTrackingBranchGitAction(repositoryPath, branch, null, null)
        }
    }
}