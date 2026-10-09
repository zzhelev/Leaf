package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IContinueRebaseGitAction
import dev.app.leaf.domain.interfaces.IGetRebaseInteractiveStateGitAction
import dev.app.leaf.domain.interfaces.IGetRepositoryStateGitAction
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class ContinueRebaseUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val continueRebaseGitAction: IContinueRebaseGitAction,
    private val doCommitUseCase: DoCommitUseCase,
) {
    operator fun invoke(
        message: String,
        isAmendRebaseInteractive: Boolean,
        repositoryState: RepositoryState,
        rebaseInteractiveState: RebaseInteractiveState,
        onIdentityRequest: suspend () -> Identity?,
    ) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ContinueRebase,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
        ) { repositoryPath ->
            if (
                repositoryState == RepositoryState.REBASING_INTERACTIVE &&
                rebaseInteractiveState is RebaseInteractiveState.ProcessingCommits &&
                rebaseInteractiveState.isCurrentStepAmenable &&
                isAmendRebaseInteractive
            ) {
                val amendCommitId = rebaseInteractiveState.commitToAmendId

                if (!amendCommitId.isNullOrBlank()) {
                    // Fork-only: amends within this task, and doesn't continue when the amend fails
                    doCommitUseCase.commit(repositoryPath, message, true, onIdentityRequest()).bind()
                }
            }

            continueRebaseGitAction(repositoryPath)
        }
    }
}