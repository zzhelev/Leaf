package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.errors.onOk
import dev.app.leaf.domain.interfaces.IAbortRebaseGitAction
import dev.app.leaf.domain.interfaces.IPutBackMergeAutostashGitAction
import dev.app.leaf.domain.interfaces.IResetRepositoryStateGitAction
import dev.app.leaf.domain.models.AutostashPutBackAfter
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class ResetRepositoryStateUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val resetRepositoryStateGitAction: IResetRepositoryStateGitAction,
    private val putBackMergeAutostashGitAction: IPutBackMergeAutostashGitAction,
) {
    operator fun invoke() {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ResetRepoState,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            resetRepositoryStateGitAction(repositoryPath)
                .onOk {
                    // Fork-only: puts back what merge.autoStash set aside, as `git merge --abort` does
                    putBackMergeAutostashGitAction(repositoryPath, AutostashPutBackAfter.ABORT).onErr {
                        printError(TAG, "Failed to put back the merge's autostash: $it")
                    }
                }
        }
    }
}
