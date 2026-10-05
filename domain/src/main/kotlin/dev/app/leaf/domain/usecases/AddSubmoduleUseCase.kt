package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IAddSubmoduleGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class AddSubmoduleUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val addSubmoduleGitAction: IAddSubmoduleGitAction,
) {
    operator fun invoke(name: String, path: String, uri: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.AddSubmodule,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            addSubmoduleGitAction(repositoryPath, name, path, uri)
        }
    }
}