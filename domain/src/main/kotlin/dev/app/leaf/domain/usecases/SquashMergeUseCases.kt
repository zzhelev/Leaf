// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.interfaces.IGetWorktreePathGitAction
import dev.app.leaf.domain.interfaces.IPersistSquashMessageGitAction
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction
import dev.app.leaf.domain.models.TaskType
import kotlinx.coroutines.Job
import javax.inject.Inject

/** Saves the commit field's message to `SQUASH_MSG` while a squash merge waits to be committed (fork-only). */
class PersistSquashMessageUseCase @Inject constructor(
    private val persistSquashMessageGitAction: IPersistSquashMessageGitAction,
) {
    /**
     * Saves [message] in the git dir [repositoryPath], the worktree's that the message was written in, as the message
     * is saved a second after typing, when the tab may show another worktree. A blank message keeps the one that's
     * there: removing it would end the squash merge as Leaf and git see it.
     */
    suspend operator fun invoke(repositoryPath: String, message: String) {
        if (message.isBlank()) {
            return
        }

        persistSquashMessageGitAction(repositoryPath, message)
            .onErr {
                printError(TAG, "Failed to persist the squash message: $it")
            }
    }
}

/**
 * Undoes a squash merge that waits to be committed (fork-only), conflicts included: `git reset --merge`, which also
 * removes `SQUASH_MSG`. A squash merge leaves no state that `git merge --abort` could undo. Changes that the user made
 * since are kept, unless they're in a file that the squash merge changed: then git refuses, and changes nothing.
 */
class AbortSquashMergeUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val getWorktreePathGitAction: IGetWorktreePathGitAction,
    private val runGitCommandGitAction: IRunGitCommandGitAction,
) {
    operator fun invoke(): Job = useCaseExecutor.executeLaunch(
        taskType = TaskType.AbortSquashMerge,
        dataToRefresh = arrayOf(DataToRefresh.ALL),
        refreshEvenIfFailed = true,
    ) { repositoryPath ->
        val workTree = getWorktreePathGitAction(repositoryPath).bind()

        runGitCommandGitAction(workTree, listOf("reset", "--merge"))
    }
}
