// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.errors.onErr
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.models.RECENT_CHANGE_MS
import dev.app.leaf.domain.models.WorktreeCleanup
import dev.app.leaf.domain.worktrees.candidates
import javax.inject.Inject

private const val TAG = "GetWorktreeCleanupUseCase"

/**
 * Reads what "Clean up merged worktrees" offers (fork-only, Phase 4.3b), for its dialog: the worktrees, read again as
 * the tab's list may be stale, and which of the ones it offers changed in the last few minutes, where an agent may
 * still be working.
 */
class GetWorktreeCleanupUseCase @Inject constructor(
    private val getWorktreesInfoUseCase: GetWorktreesInfoUseCase,
    private val getWorktreeActivityGitAction: IGetWorktreeActivityGitAction,
) {
    suspend operator fun invoke(): Either<WorktreeCleanup, AppError> {
        val list = when (val result = getWorktreesInfoUseCase()) {
            is Either.Err -> return result
            is Either.Ok -> result.value
        }

        val notBefore = System.currentTimeMillis() - RECENT_CHANGE_MS
        val recentChanges = WorktreeCleanup(list).candidates
            .filter { !it.isMissing && !it.isInUse }
            .mapNotNull { candidate ->
                val path = candidate.worktree.path
                // The whole read, not the git dir's time alone, as the dialog says how long ago it changed
                val lastChange = getWorktreeActivityGitAction(path)
                    .onErr { printError(TAG, "Could not read when $path last changed: $it") }
                    .okOrNull()

                lastChange?.takeIf { it >= notBefore }?.let { path to it }
            }
            .toMap()

        return Either.Ok(WorktreeCleanup(list, recentChanges))
    }
}
