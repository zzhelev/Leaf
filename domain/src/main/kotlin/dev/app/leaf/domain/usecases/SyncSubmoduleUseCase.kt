package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.ISyncSubmoduleGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class SyncSubmoduleUseCase @Inject constructor(
    private val syncSubmoduleGitAction: ISyncSubmoduleGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(submodulePath: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.SyncSubmodule,
            dataToRefresh = arrayOf(DataToRefresh.SUBMODULES),
        ) { repository ->
            syncSubmoduleGitAction(repository, submodulePath)
        }
    }
}