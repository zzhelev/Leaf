package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IInitializeSubmoduleGitAction
import dev.app.leaf.domain.interfaces.IUpdateSubmoduleGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class InitializeSubmoduleUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val initializeSubmoduleGitAction: IInitializeSubmoduleGitAction,
    private val updateSubmoduleGitAction: IUpdateSubmoduleGitAction,
) {
    operator fun invoke(path: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.InitSubmodule,
            dataToRefresh = arrayOf(DataToRefresh.SUBMODULES),
        ) { repositoryPath ->
            initializeSubmoduleGitAction(repositoryPath, path).bind()
            updateSubmoduleGitAction(repositoryPath, path)
        }
    }
}