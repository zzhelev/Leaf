package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IPullBranchGitAction
import dev.app.leaf.domain.models.*
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PullBranchUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val pullBranchGitAction: IPullBranchGitAction,
    private val appSettingsService: AppSettingsService,
) {
    operator fun invoke(
        pullType: PullType,
        remoteBranch: Branch? = null,
        automaticStashDescription: String,
    ) = useCaseExecutor.executeLaunch(
        taskType = TaskType.Pull,
        dataToRefresh = arrayOf(DataToRefresh.ALL),
    ) { repositoryPath ->
        val autoStashOnMerge = appSettingsService.autoStashOnMerge.first()

        val pullTypeWithSettings = if (pullType == PullType.DEFAULT) {
            val isPullWithRebase = appSettingsService.pullWithRebase.first()

            if (isPullWithRebase) {
                PullType.REBASE
            } else {
                PullType.MERGE
            }
        } else {
            pullType
        }

        val result = pullBranchGitAction(repositoryPath, pullTypeWithSettings, autoStashOnMerge, remoteBranch, automaticStashDescription)

        if (result is Either.Ok) {
            if (result.value) {
                warningNotification("Pull produced conflicts, fix them to continue")
            } else {
                positiveNotification("Pull completed")
            }
        }

        result
    }
}