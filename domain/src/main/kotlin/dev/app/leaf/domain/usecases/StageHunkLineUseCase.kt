package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IStageHunkLineGitAction
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.TaskType
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class StageHunkLineUseCase @Inject constructor(
    private val stageHunkLineGitAction: IStageHunkLineGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(diffEntry: DiffEntry, hunk: Hunk, line: Line) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.StageLine,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // So that the diff shows the file as it is when git refuses lines that changed since
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            stageHunkLineGitAction(repositoryPath, diffEntry, hunk, line)
        }
    }
}
