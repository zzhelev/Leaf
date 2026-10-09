package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IResetHunkGitAction
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.TaskType
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class ResetHunkUseCase @Inject constructor(
    private val resetHunkGitAction: IResetHunkGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(diffEntry: DiffEntry, hunk: Hunk) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.Unspecified,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // So that the diff shows the file as it is when git refuses lines that changed since
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            resetHunkGitAction(repositoryPath, diffEntry, hunk)
        }
    }
}
