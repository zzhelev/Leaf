// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.cli.GitCliOutput
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RestoreFileError
import dev.app.leaf.domain.interfaces.IRestoreFileVersionGitAction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Where, in a worktree's git dir, a working file waits while git writes its version. */
internal const val RESTORE_BACKUP_FOLDER = "leaf-restore"

/**
 * Writes a file as a commit had it into the working tree (fork-only): `git restore --source=<commit> --worktree --
 * <path>`, with `--literal-pathspecs` so that a name such as `[ab].txt` isn't a pattern. The index is left alone, so
 * what was staged stays staged and nothing new is.
 *
 * It runs through [GitCliRemoteCommand], as git may have to download the blob (a partial clone) or the LFS object, and
 * ask for credentials, which Leaf's dialogs answer. The processing screen shows git's progress and can cancel it.
 *
 * The working file waits aside while git runs ([WorkingFileBackup]), and goes back when git fails or is cancelled: git
 * empties or deletes it before it has the version. Without a usable git, it fails and changes nothing.
 */
class RestoreFileVersionGitAction @Inject constructor(
    private val planner: FileRestorePlanner,
    private val remoteCommand: GitCliRemoteCommand,
) : IRestoreFileVersionGitAction {
    override suspend fun invoke(repositoryPath: String, commit: String, filePath: String): Either<Unit, GitError> {
        val planned = when (val result = planner.plan(repositoryPath, commit, filePath)) {
            is Either.Err -> return result
            is Either.Ok -> result.value
        }

        planned.plan.refusal?.let { return Either.Err(it) }

        val workTree = planned.workTree
        val backup = try {
            WorkingFileBackup.moveAside(File(workTree, filePath), File(repositoryPath, RESTORE_BACKUP_FOLDER))
        } catch (e: IOException) {
            return Either.Err(GenericError("Couldn't move \"$filePath\" out of the way: ${e.message}", e))
        }

        val result = try {
            restore(workTree, commit, filePath)
        } catch (e: Throwable) {
            // Cancelled, or crashed: the file goes back before that goes on
            withContext(NonCancellable) { backup.putBack() }
            throw e
        }

        val keptAt = withContext(NonCancellable) {
            if (result is Either.Ok) {
                backup.discard()
                null
            } else {
                backup.putBack()
            }
        }

        val error = (result as? Either.Err)?.error

        return if (error is RestoreFileError.GitFailed && keptAt != null) {
            Either.Err(error.copy(keptAt = keptAt))
        } else {
            result
        }
    }

    private suspend fun restore(workTree: File, commit: String, filePath: String): Either<Unit, GitError> {
        val args = listOf("--literal-pathspecs", "restore", "--source=$commit", "--worktree", "--", filePath)
        val output = when (val result = remoteCommand.run(workTree, args)) {
            is Either.Err -> return result
            is Either.Ok -> result.value
        }

        return if (output.exitCode == 0) Either.Ok(Unit) else Either.Err(failure(output, commit, filePath))
    }

    private fun failure(output: GitCliOutput, commit: String, filePath: String): RestoreFileError {
        val message = readableGitOutput(output.stderr).ifEmpty { output.stdout.trim() }

        // Looked at before, so only a commit that changed under the dialog gets here
        return if ("did not match any file(s) known to git" in message) {
            RestoreFileError.NotInCommit(filePath, commit)
        } else {
            RestoreFileError.GitFailed(filePath, message)
        }
    }
}
