// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.ICopyWorktreeFilesGitAction
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.worktrees.WorktreeStep
import kotlinx.coroutines.Job
import javax.inject.Inject

/**
 * Runs the steps of a worktree action (fork-only), the ones its dialog showed, in order, as a task: creating,
 * removing, discarding, pruning, locking or unlocking worktrees. It stops at the first step that fails, whose error names the
 * command. Then it refreshes [dataToRefresh], even after a failure, as the steps before it ran.
 */
class RunWorktreeStepsUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val runGitCommandGitAction: IRunGitCommandGitAction,
    private val copyWorktreeFilesGitAction: ICopyWorktreeFilesGitAction,
    private val switchWorktreeUseCase: SwitchWorktreeUseCase,
    private val repositoryDataRepository: RepositoryDataRepository,
) {
    operator fun invoke(
        taskType: TaskType,
        steps: List<WorktreeStep>,
        dataToRefresh: Array<DataToRefresh> = arrayOf(DataToRefresh.ALL),
    ): Job = useCaseExecutor.executeLaunch(
        taskType = taskType,
        dataToRefresh = dataToRefresh,
        refreshEvenIfFailed = true,
    ) { gitDirAtStart ->
        runSteps(steps, gitDirAtStart)
    }

    /** Runs [steps] inside a task that already runs, whose git dir was [gitDirAtStart], such as creating a worktree. */
    suspend fun runSteps(steps: List<WorktreeStep>, gitDirAtStart: String): Either<Unit, AppError> = either {
        for (step in steps) {
            when (step) {
                is WorktreeStep.Git -> runGitCommandGitAction(step.folder, step.args).bind()
                is WorktreeStep.SwitchTab -> {
                    val currentGitDir = repositoryDataRepository.repositoryPath ?: gitDirAtStart
                    switchWorktreeUseCase.switch(currentGitDir, step.folder).bind()
                }

                is WorktreeStep.CopyFiles -> copyWorktreeFilesGitAction(step.from, step.to, step.files).bind()
            }
        }

        Either.Ok(Unit)
    }
}
