package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IPushBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PushBranchUseCase @Inject constructor(
    private val pushBranchGitAction: IPushBranchGitAction,
    private val appSettingsService: AppSettingsService,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(force: Boolean, pushTags: Boolean, targetRemoteBranch: Branch? = null) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.Push,
            dataToRefresh = arrayOf(DataToRefresh.LOG, DataToRefresh.REMOTES),
        ) { repositoryPath ->
            val pushWithLease = appSettingsService.pushWithLease.first()

            pushBranchGitAction(repositoryPath, force, pushTags, pushWithLease, targetRemoteBranch)
        }
    }
}