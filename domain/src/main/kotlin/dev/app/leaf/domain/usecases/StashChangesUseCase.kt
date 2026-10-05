package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.extensions.nullIfEmpty
import dev.app.leaf.domain.interfaces.IStageUntrackedFileGitAction
import dev.app.leaf.domain.interfaces.IStashChangesGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

class StashChangesUseCase @Inject constructor(
    private val stageUntrackedFileGitAction: IStageUntrackedFileGitAction,
    private val stashChangesGitAction: IStashChangesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(message: String?) = useCaseExecutor.executeLaunch(
        taskType = TaskType.Stash,
        dataToRefresh = arrayOf(DataToRefresh.STATUS, DataToRefresh.STASHES, DataToRefresh.LOG),
    ) { repositoryPath ->
        stageUntrackedFileGitAction(repositoryPath).bind()

        stashChangesGitAction(repositoryPath, message?.nullIfEmpty)
    }
}