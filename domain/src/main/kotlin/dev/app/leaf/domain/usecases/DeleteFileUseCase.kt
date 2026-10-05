package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IDeleteFileGitAction
import dev.app.leaf.domain.interfaces.IDiscardEntriesGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.TaskType
import java.io.File
import javax.inject.Inject

class DeleteFileUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val deleteFileGitAction: IDeleteFileGitAction,
) {
    operator fun invoke(filePath: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.DiscardFile,
            dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.LOG),
        ) { repositoryPath ->
            deleteFileGitAction(repositoryPath, filePath)
        }
    }
}
