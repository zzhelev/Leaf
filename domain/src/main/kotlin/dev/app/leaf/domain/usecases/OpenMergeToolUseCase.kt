// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IOpenMergeToolGitAction
import dev.app.leaf.domain.models.MergeToolTurn
import dev.app.leaf.domain.models.TaskType
import javax.inject.Inject

/**
 * Resolves conflicted files in the user's merge tool. The tab waits, behind the processing screen, until the tool
 * closes; its Cancel stops waiting.
 */
class OpenMergeToolUseCase @Inject constructor(
    private val openMergeToolGitAction: IOpenMergeToolGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    operator fun invoke(filePath: String) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.OpenMergeTool,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // The file may have changed even when the tool failed
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            openMergeToolGitAction(repositoryPath, filePath)
        }
    }

    /**
     * The banner's Resolve conflicts, with the external merge tool picked: [filePaths] one after another, as
     * `git mergetool` goes through every conflicted file in a terminal (see [resolveInTurn]).
     */
    fun inTurn(filePaths: List<String>) {
        useCaseExecutor.executeLaunch(
            taskType = TaskType.ResolveConflictsInMergeTool,
            dataToRefresh = arrayOf(DataToRefresh.STATUS),
            // The files before the one that failed are resolved
            refreshEvenIfFailed = true,
        ) { repositoryPath ->
            resolveInTurn(filePaths) { filePath, turn -> openMergeToolGitAction(repositoryPath, filePath, turn) }
        }
    }
}

/**
 * Resolves [filePaths] in turn with [open], and returns how many it resolved. A file that has no conflicts any more, or
 * that the tool can't take, is skipped, as the status that listed it was older. Any other failure stops it, as
 * `git mergetool` stops when it can't ask whether to go on: closing the tool without saving is how the user stops. A
 * file that the tool didn't resolve then says how many it resolved before it.
 */
internal suspend fun resolveInTurn(
    filePaths: List<String>,
    open: suspend (filePath: String, turn: MergeToolTurn) -> Either<Unit, GitError>,
): Either<Int, GitError> {
    var resolved = 0

    for ((index, filePath) in filePaths.withIndex()) {
        val result = open(filePath, MergeToolTurn(index + 1, filePaths.size))

        if (result is Either.Err) {
            when (val error = result.error) {
                is ConflictError.NotConflicted, is ConflictError.MergeToolRefused -> continue
                is ConflictError.NotResolved -> return Either.Err(error.copy(resolvedBefore = resolved))
                is ConflictError.MarkersLeft -> return Either.Err(error.copy(resolvedBefore = resolved))
                else -> return Either.Err(error)
            }
        }

        resolved++
    }

    return Either.Ok(resolved)
}
