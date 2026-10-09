package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IStageHunkGitAction
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.TaskType
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class StageHunkUseCase @Inject constructor(
    private val stageHunkGitAction: IStageHunkGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(diffEntry: DiffEntry, hunk: Hunk) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.StageHunk,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // So that the diff shows the file as it is when git refuses lines that changed since
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            stageHunkGitAction(repositoryPath, diffEntry, hunk)
        }
    }
}
