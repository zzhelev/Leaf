package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.ICheckHasUncommittedChangesGitAction
import dev.app.leaf.domain.interfaces.ICreateSnapshotStashGitAction
import dev.app.leaf.domain.interfaces.IDeleteStashGitAction
import dev.app.leaf.domain.interfaces.IIsUnchangedSinceGitAction
import dev.app.leaf.domain.interfaces.IMergeBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import javax.inject.Inject

class MergeBranchUseCase @Inject constructor(
    private val mergeBranchGitAction: IMergeBranchGitAction,
    private val appSettingsService: AppSettingsService,
    private val checkHasUncommittedChangesGitAction: ICheckHasUncommittedChangesGitAction,
    private val useCaseExecutor: UseCaseExecutor,
    private val deleteStashGitAction: IDeleteStashGitAction,
    private val createSnapshotStashGitAction: ICreateSnapshotStashGitAction,
    private val isUnchangedSinceGitAction: IIsUnchangedSinceGitAction,
) {

    operator fun invoke(branch: Branch, automaticStashDescription: String) {
        useCaseExecutor.executeLaunch(
            TaskType.MergeBranch,
            refreshEvenIfFailed = true,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            stoppedAtConflicts = { hasConflicts -> hasConflicts },
        ) { repositoryPath ->
            val mergeAutoStash = appSettingsService.autoStashOnMerge.first()
            val fastForwardMerge = appSettingsService.fastForwardMerge.first()
            var backupStash: Commit? = null

            if (mergeAutoStash) {
                val hasUncommitedChanges = checkHasUncommittedChangesGitAction(repositoryPath).bind()
                if (hasUncommitedChanges) {
                    backupStash = createSnapshotStashGitAction(
                        repositoryPath,
                        message = automaticStashDescription,
                        includeUntracked = true
                    ).bind()
                }
            }

            val result = mergeBranchGitAction(repositoryPath, branch, fastForwardMerge)

            if (result is Either.Ok) {
                val hasConflicts = result.value

                if (!hasConflicts && backupStash != null) {
                    deleteStashGitAction(repositoryPath, backupStash)
                }
            } else if (backupStash != null && changedNothing(repositoryPath, backupStash)) {
                // Fork-only: git refused (local changes in the way, not a commit, conflicts left), so there's nothing
                // to restore. Each refusal used to leave a stash.
                deleteStashGitAction(repositoryPath, backupStash)
            }

            result
        }
    }

    /**
     * Whether the merge left HEAD at the commit that [backupStash] was taken on, its first parent, and no merge. A
     * cancelled merge may have stopped halfway through the working tree, so its backup stays.
     */
    private suspend fun changedNothing(repositoryPath: String, backupStash: Commit): Boolean {
        val head = backupStash.parentsHashes.firstOrNull() ?: return false

        if (!currentCoroutineContext().isActive) {
            return false
        }

        return isUnchangedSinceGitAction(repositoryPath, head) == Either.Ok(true)
    }
}