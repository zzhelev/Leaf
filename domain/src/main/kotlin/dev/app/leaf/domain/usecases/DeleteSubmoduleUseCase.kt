package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IDeleteSubmoduleGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class DeleteSubmoduleUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val deleteSubmoduleGitAction: IDeleteSubmoduleGitAction,
) {
    operator fun invoke(path: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.DeleteSubmodule,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            deleteSubmoduleGitAction(repositoryPath, path)
        }
    }
}