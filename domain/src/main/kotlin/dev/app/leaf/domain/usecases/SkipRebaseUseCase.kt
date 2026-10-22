package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.onOk
import dev.app.leaf.domain.rebase.SkippedRebaseCommit
import dev.app.leaf.domain.rebase.SkippedRebaseCommits
import dev.app.leaf.domain.interfaces.IAbortRebaseGitAction
import dev.app.leaf.domain.interfaces.ISkipRebaseGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class SkipRebaseUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val skipRebaseGitAction: ISkipRebaseGitAction,
    private val skippedRebaseCommits: SkippedRebaseCommits,
) {
    /**
     * Fork-only: [skipped] is the commit left out with its changes, which the tab lists once the rebase has ended
     * ([ReportSkippedRebaseCommitsUseCase]). Null for a commit that came out empty, which loses nothing.
     */
    operator fun invoke(skipped: SkippedRebaseCommit? = null) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.SkipRebase,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            stoppedAtConflicts = { stoppedAgain -> stoppedAgain },
        ) { repositoryPath ->
            skipRebaseGitAction(repositoryPath).onOk {
                if (skipped != null) {
                    skippedRebaseCommits.add(repositoryPath, skipped)
                }
            }
        }
    }
}