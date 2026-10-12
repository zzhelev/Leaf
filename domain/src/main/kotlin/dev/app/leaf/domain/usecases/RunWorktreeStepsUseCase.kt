// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.ICopyWorktreeFilesGitAction
import dev.app.leaf.domain.interfaces.IGetUnmergedFilesGitAction
import dev.app.leaf.domain.interfaces.IOpenRepositoryGitAction
import dev.app.leaf.domain.interfaces.IPersistSquashMessageGitAction
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.worktrees.WorktreeStep
import kotlinx.coroutines.Job
import javax.inject.Inject

/** git's exit code when a merge stops at conflicts, and also when it refuses a squash merge. */
private const val MERGE_STOPPED_EXIT_CODE = 1

/**
 * Runs the steps of a worktree action (fork-only), the ones its dialog showed, in order, as a task: creating,
 * removing, discarding, pruning, locking or unlocking worktrees, or merging one's branch into the base. It stops at the
 * first step that fails, whose error names the command, except a merge that stopped at conflicts: the steps go on, and
 * the task's toast says that it stopped at them. Then it refreshes [dataToRefresh], even after a failure, as the steps
 * before it ran.
 */
class RunWorktreeStepsUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val runGitCommandGitAction: IRunGitCommandGitAction,
    private val copyWorktreeFilesGitAction: ICopyWorktreeFilesGitAction,
    private val getUnmergedFilesGitAction: IGetUnmergedFilesGitAction,
    private val openRepositoryGitAction: IOpenRepositoryGitAction,
    private val persistSquashMessageGitAction: IPersistSquashMessageGitAction,
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
        stoppedAtConflicts = { stoppedAtConflicts -> stoppedAtConflicts },
    ) { gitDirAtStart ->
        runSteps(steps, gitDirAtStart)
    }

    /**
     * Runs [steps] inside a task that already runs, whose git dir was [gitDirAtStart], such as creating a worktree.
     * Returns whether a merge among them stopped at conflicts.
     */
    suspend fun runSteps(steps: List<WorktreeStep>, gitDirAtStart: String): Either<Boolean, AppError> = either {
        var stoppedAtConflicts = false

        for (step in steps) {
            when (step) {
                is WorktreeStep.Git -> {
                    val result = runGitCommandGitAction(step.folder, step.args)
                    val mergeConflicts = step.mayStopAtConflicts && result is Either.Err &&
                        hasStoppedAtConflicts(step.folder, result.error)

                    if (mergeConflicts) {
                        stoppedAtConflicts = true
                    } else {
                        result.bind()
                    }
                }

                is WorktreeStep.SwitchTab -> {
                    val currentGitDir = repositoryDataRepository.repositoryPath ?: gitDirAtStart
                    switchWorktreeUseCase.switch(currentGitDir, step.folder).bind()
                }

                is WorktreeStep.CopyFiles -> copyWorktreeFilesGitAction(step.from, step.to, step.files).bind()

                is WorktreeStep.WriteSquashMessage -> {
                    val gitDir = openRepositoryGitAction(step.folder).bind()
                    persistSquashMessageGitAction(gitDir, step.message).bind()
                }
            }
        }

        Either.Ok(stoppedAtConflicts)
    }

    /**
     * Whether a merge in [folder] that failed with [error] stopped at conflicts: git exits with 1, and the index has
     * them. git exits with 1 too when it refuses a squash merge, as local changes are in the way, but leaves none, and
     * with 128 when the index already had conflicts.
     */
    private suspend fun hasStoppedAtConflicts(folder: String, error: GitError): Boolean {
        if (error !is GitCliError.CommandFailed || error.exitCode != MERGE_STOPPED_EXIT_CODE) {
            return false
        }

        return getUnmergedFilesGitAction(folder).okOrNull()?.isNotEmpty() == true
    }
}
