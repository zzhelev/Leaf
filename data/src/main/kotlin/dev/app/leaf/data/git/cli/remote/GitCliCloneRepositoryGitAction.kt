// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.CloneCheckoutError
import dev.app.leaf.domain.errors.CloneSubmodulesError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.LfsFilterError
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.models.TaskProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val TAG = "GitCliCloneRepository"

private const val SUBMODULES_STAGE = "Cloning submodules"

/**
 * git shows its checkout's progress ("Updating files", and "Filtering content" while a filter downloads LFS files)
 * only once it has taken 2 s, and git-lfs's filter shows none (git 2.54, git-lfs 3.8). With no delay, the clone's
 * dialog shows both from the start.
 */
private val CHECKOUT_PROGRESS = mapOf("GIT_PROGRESS_DELAY" to "0")

/**
 * Clones with `git clone`, which checks the files out itself: with git-lfs's filter where git-lfs is set up, which also
 * installs its hooks, and with Leaf's otherwise ([dev.app.leaf.data.git.lfs.filter.LfsFilter], through
 * [GitCliRemoteCommand]), so git-lfs isn't needed. With `cloneSubmodules`, `git submodule update --init --recursive`
 * then clones the submodules, as `git clone --recurse-submodules` does.
 *
 * With a `branch`, git checks out that branch or tag (`--branch`, a tag leaves HEAD detached), and with `singleBranch`
 * fetches only it (`--single-branch`), which also limits `remote.origin.fetch`. The submodules below follow HEAD. A
 * branch that the remote lacks is `RemoteOperationError.BranchNotFound`, and git has made no folder by then.
 *
 * A clone that fails or is cancelled is removed: the folder, or what's in it when it was an empty folder before. git
 * keeps a clone whose checkout failed, as when an LFS file couldn't be downloaded ([CloneCheckoutError]), and Leaf
 * removes it too. git refuses a folder with files in it, which is then left alone. Only a clone whose submodules failed
 * is kept, as with `git clone --recurse-submodules`, since the repository itself is complete.
 */
class GitCliCloneRepositoryGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
) {
    operator fun invoke(
        directory: File,
        url: String,
        cloneSubmodules: Boolean,
        branch: String? = null,
        singleBranch: Boolean = false,
    ): Flow<CloneState> = channelFlow {
        val destination = directory.absoluteFile
        val existed = destination.exists()
        // Leaf only removes what the clone created
        val removable = !existed || destination.list()?.isEmpty() == true
        var keep = false

        try {
            send(CloneState.Cloning("Starting...", 0, 0))

            val error = try {
                clone(destination, url, cloneSubmodules, branch, singleBranch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Such as the askpass server's socket failing. The dialog would wait forever for a state otherwise.
                printError(TAG, "Clone failed", e)
                GenericError(e.message.orEmpty(), e)
            }
            keep = error == null || error is CloneSubmodulesError

            send(if (error == null) CloneState.Completed(destination) else CloneState.Fail(error))
        } finally {
            // Also after a cancellation, and after a failed checkout, whose clone git keeps
            if (!keep && removable) {
                jgit.forgetRepositoriesIn(destination)
                removePartialClone(destination, existed)
            }
        }
    }

    private suspend fun ProducerScope<CloneState>.clone(
        destination: File,
        url: String,
        cloneSubmodules: Boolean,
        branch: String?,
        singleBranch: Boolean,
    ): GitError? {
        val clone = remoteCommand.run(
            workingDirectory = destination.parentFile ?: destination,
            args = cloneArguments(url, destination, branch, singleBranch),
            onProgress = { reportProgress(it, prefix = "") },
            environment = CHECKOUT_PROGRESS,
        )

        val output = when (clone) {
            is Either.Err -> return if (clone.error is LfsFilterError) CloneCheckoutError(clone.error) else clone.error
            is Either.Ok -> clone.value
        }

        if (output.exitCode != 0) {
            return if (checkoutFailed(output.stderr)) {
                CloneCheckoutError(remoteOperationError(output.exitCode, withoutRemovedCloneHints(output.stderr)))
            } else {
                remoteOperationError(output.exitCode, output.stderr)
            }
        }

        if (cloneSubmodules && File(destination, Constants.DOT_GIT_MODULES).isFile) {
            send(CloneState.Cloning(SUBMODULES_STAGE, 0, 0))

            val error = when (
                val update = remoteCommand.run(
                    workingDirectory = destination,
                    args = listOf("submodule", "update", "--init", "--recursive", "--progress"),
                    onProgress = { reportProgress(it, prefix = "$SUBMODULES_STAGE: ") },
                )
            ) {
                is Either.Err -> update.error
                is Either.Ok -> if (update.value.exitCode == 0) {
                    null
                } else {
                    remoteOperationError(update.value.exitCode, update.value.stderr)
                }
            }

            if (error != null) {
                return CloneSubmodulesError(destination.path, error)
            }
        }

        return null
    }

    private fun ProducerScope<CloneState>.reportProgress(progress: TaskProgress?, prefix: String) {
        val stage = progress?.stage ?: return

        trySend(CloneState.Cloning(prefix + stage, progress.percent ?: 0, 100))
    }

    /** Removes what the clone left: [destination], or what's in it when it [existed] before, empty. */
    private fun removePartialClone(destination: File, existed: Boolean) {
        val leftovers = if (existed) destination.listFiles().orEmpty().toList() else listOf(destination)

        for (leftover in leftovers) {
            try {
                // JGit's delete doesn't follow symbolic links, which the checkout may have created
                FileUtils.delete(leftover, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
            } catch (e: IOException) {
                printError(TAG, "Couldn't remove ${leftover.path} after the clone stopped", e)
            }
        }
    }
}

/**
 * `git clone`'s arguments. `--branch=<name>`, not `--branch <name>`, so that a name that starts with `-` is a value.
 * `--single-branch` without a branch clones the remote's default branch alone.
 */
internal fun cloneArguments(url: String, destination: File, branch: String?, singleBranch: Boolean): List<String> =
    buildList {
        addAll(listOf("clone", "--progress"))
        if (!branch.isNullOrBlank()) add("--branch=${branch.trim()}")
        if (singleBranch) add("--single-branch")
        addAll(listOf("--", url, destination.path))
    }

private const val CHECKOUT_FAILED = "Clone succeeded, but checkout failed."

/** Whether git's [stderr] says that it cloned, but couldn't check the files out (git 2.54, in English). */
internal fun checkoutFailed(stderr: String) = CHECKOUT_FAILED in stderr

/**
 * Lines of git's output that point into a clone that Leaf removes: git's own (`git status`, `git restore`, after
 * "Clone succeeded, but checkout failed."), and where git-lfs logged its errors.
 */
private val REMOVED_CLONE_HINTS = listOf(
    Regex("""^warning: ${Regex.escape(CHECKOUT_FAILED)}$"""),
    Regex("""^You can inspect what was checked out with 'git status'$"""),
    Regex("""^and retry with 'git restore --source=HEAD :/'$"""),
    Regex("""^Errors logged to '.*'\.$"""),
    Regex("""^Use `git lfs logs last` to view the log\.$"""),
)

/** git's [stderr] for a clone whose checkout failed, without [REMOVED_CLONE_HINTS]. */
internal fun withoutRemovedCloneHints(stderr: String): String = stderr
    .replace("\r\n", "\n")
    .split('\n')
    .filterNot { line -> REMOVED_CLONE_HINTS.any { it.matches(line.trimEnd()) } }
    .joinToString("\n")
