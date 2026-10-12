// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.common.printError
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.interfaces.ICheckBranchNameGitAction
import dev.app.leaf.domain.interfaces.ICreateWorktreeIncludeGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreeIncludeGitAction
import dev.app.leaf.domain.interfaces.ISaveWorktreeCreationSettingsGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.WorktreeCreationSettings
import dev.app.leaf.domain.models.WorktreeInclude
import dev.app.leaf.domain.worktrees.WorktreeStep
import kotlinx.coroutines.Job
import javax.inject.Inject

private const val TAG = "CreateWorktreeUseCase"

/**
 * Creates a worktree (fork-only): runs the steps that its dialog showed, as a task, and saves how the repository makes
 * new worktrees (where they go, whether the tab switches to them) for the next one.
 */
class CreateWorktreeUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val saveWorktreeCreationSettingsGitAction: ISaveWorktreeCreationSettingsGitAction,
    private val runWorktreeStepsUseCase: RunWorktreeStepsUseCase,
) {
    operator fun invoke(steps: List<WorktreeStep>, settings: WorktreeCreationSettings): Job =
        useCaseExecutor.executeLaunch(
            taskType = TaskType.CreateWorktree,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            refreshEvenIfFailed = true,
        ) { gitDir ->
            saveWorktreeCreationSettingsGitAction(gitDir, settings).onErr {
                printError(TAG, "Couldn't save how new worktrees are made: $it")
            }

            runWorktreeStepsUseCase.runSteps(steps, gitDir)
        }
}

/** Reads the repository's `.worktreeinclude`, and the files it would copy into a new worktree (fork-only). */
class GetWorktreeIncludeUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getWorktreeIncludeGitAction: IGetWorktreeIncludeGitAction,
) {
    suspend operator fun invoke(mainWorktreePath: String): Either<WorktreeInclude?, AppError> =
        useCaseExecutor.execute { getWorktreeIncludeGitAction(mainWorktreePath) }
}

/** Creates a `.worktreeinclude` with comments and examples, unless there's one, and returns its path (fork-only). */
class CreateWorktreeIncludeUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val createWorktreeIncludeGitAction: ICreateWorktreeIncludeGitAction,
) {
    suspend operator fun invoke(mainWorktreePath: String): Either<String, AppError> =
        useCaseExecutor.execute { createWorktreeIncludeGitAction(mainWorktreePath) }
}

/** Whether git accepts a name for a new branch (fork-only). */
class CheckBranchNameUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val checkBranchNameGitAction: ICheckBranchNameGitAction,
) {
    suspend operator fun invoke(name: String): Either<Boolean, AppError> =
        useCaseExecutor.execute { repositoryPath -> checkBranchNameGitAction(repositoryPath, name) }
}
