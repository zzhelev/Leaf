package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IResumeRebaseInteractiveGitAction
import dev.app.leaf.domain.models.TaskType
import org.eclipse.jgit.api.RebaseCommand
import javax.inject.Inject

class ResumeRebaseInteractiveUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val resumeRebaseInteractiveGitAction: IResumeRebaseInteractiveGitAction,
) {
    operator fun invoke(interactiveHandler: RebaseCommand.InteractiveHandler) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.RebaseInteractive,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            resumeRebaseInteractiveGitAction(repositoryPath, interactiveHandler)
        }
    }
}