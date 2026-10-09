package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IUnstageHunkGitAction
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.TaskType
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class UnstageHunkUseCase @Inject constructor(
    private val unstageHunkGitAction: IUnstageHunkGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(diffEntry: DiffEntry, hunk: Hunk) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.UnstageHunk,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // So that the diff shows the file as it is when git refuses lines that changed since
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            unstageHunkGitAction(repositoryPath, diffEntry, hunk)
        }
    }
}
