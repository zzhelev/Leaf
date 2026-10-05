package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDeleteSubmoduleGitAction
import dev.app.leaf.domain.interfaces.IUpdateSubmoduleGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class UpdateSubmoduleUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val updateSubmoduleGitAction: IUpdateSubmoduleGitAction,
) {
    operator fun invoke(path: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.UpdateSubmodule,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            updateSubmoduleGitAction(repositoryPath, path)
        }
    }
}