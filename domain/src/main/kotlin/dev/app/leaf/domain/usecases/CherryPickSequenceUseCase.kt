// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.CherryPickSequenceCommand
import dev.app.leaf.domain.interfaces.ICherryPickSequenceGitAction
import dev.app.leaf.domain.interfaces.IPersistCommitMessageGitAction
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

/**
 * Continues, skips or aborts a cherry-pick of several commits that git stopped (fork-only,
 * [dev.app.leaf.domain.cherrypick.CherryPickSequence]): Leaf's own commit would finish the stopped commit and leave the
 * rest in git's sequencer, and its abort would leave the commits already picked.
 */
class CherryPickSequenceUseCase @Inject constructor(
    private val useCaseExecutor: UseCaseExecutor,
    private val cherryPickSequenceGitAction: ICherryPickSequenceGitAction,
    private val persistCommitMessageGitAction: IPersistCommitMessageGitAction,
) {
    /**
     * `git cherry-pick --continue`. [message] is the commit field's, which goes to `MERGE_MSG` first when git commits
     * the stopped commit ([commitsStoppedCommit]): git takes its message from there, and cleans it up as `git commit`
     * does.
     */
    fun continueSequence(message: String, commitsStoppedCommit: Boolean) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ContinueCherryPick,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            refreshEvenIfFailed = true,
            stoppedAtConflicts = { stoppedAgain -> stoppedAgain },
        ) { repositoryPath ->
            if (commitsStoppedCommit) {
                persistCommitMessageGitAction(repositoryPath, message).bind()
            }

            cherryPickSequenceGitAction(repositoryPath, CherryPickSequenceCommand.CONTINUE)
        }
    }

    /** `git cherry-pick --skip`: leaves the stopped commit out, and picks the rest. */
    fun skip() {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.SkipCherryPick,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            refreshEvenIfFailed = true,
            stoppedAtConflicts = { stoppedAgain -> stoppedAgain },
        ) { repositoryPath ->
            cherryPickSequenceGitAction(repositoryPath, CherryPickSequenceCommand.SKIP)
        }
    }

    /** `git cherry-pick --abort`: the branch goes back where it was before the cherry-pick. */
    fun abort() {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.AbortCherryPick,
            dataToRefresh = arrayOf(DataToRefresh.ALL),
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            cherryPickSequenceGitAction(repositoryPath, CherryPickSequenceCommand.ABORT)
        }
    }
}
