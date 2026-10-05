package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ICreateBranchGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class CreateBranchUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    val createBranchGitAction: ICreateBranchGitAction,
) {
    operator fun invoke(branchName: String, target: Commit?) {
        // TODO Should be "execute" and handle the result in the UI
        useCaseExecutor.executeLaunch(
            taskType = TaskType.CreateBranch,
            refreshEvenIfFailed = true, // TODO Previously this was a conditional lambda: refreshEvenIfCrashesInteractive = { it is CheckoutConflictException },
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            createBranchGitAction(repositoryPath, branchName, target)
        }
    }
}