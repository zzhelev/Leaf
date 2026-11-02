// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.sparse

import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.first
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileBasedConfig
import java.io.File
import javax.inject.Inject

private const val TAG = "SparseCheckoutGit"

/** The file where git keeps a worktree's own config: `git sparse-checkout` writes `core.sparseCheckout` there. */
private const val WORKTREE_CONFIG = "config.worktree"

/**
 * Whether git keeps this repository's working tree sparse (fork-only): `core.sparseCheckout` is on, in the config that
 * JGit reads or in the worktree's own file, which JGit doesn't read (`git sparse-checkout set` turns it on there, and
 * `extensions.worktreeConfig` with it). The files outside the patterns have git's skip-worktree bit in the index and
 * are not on disk.
 *
 * `git sparse-checkout disable` turns the setting off and clears the bits, so a repository that was sparse once isn't
 * one any more. A skip-worktree bit that `git update-index --skip-worktree` set without it is not a sparse checkout:
 * the files are on disk, and git itself refuses what would overwrite them.
 */
fun Repository.usesSparseCheckout(): Boolean {
    if (isBare) {
        return false
    }

    if (config.isSparseCheckoutOn()) {
        return true
    }

    val worktreeConfig = FileBasedConfig(File(directory, WORKTREE_CONFIG), fs)

    if (!worktreeConfig.file.isFile) {
        return false
    }

    return try {
        worktreeConfig.load()
        worktreeConfig.isSparseCheckoutOn()
    } catch (e: Exception) {
        // A file that git can't read fails every git command too, which says so
        printError(TAG, "Couldn't read $WORKTREE_CONFIG of $directory: ${e.message}")
        false
    }
}

private fun Config.isSparseCheckoutOn() =
    getBoolean(ConfigConstants.CONFIG_CORE_SECTION, null, "sparseCheckout", false)

/**
 * Runs git where a sparse checkout needs it (fork-only). JGit doesn't know sparse checkouts: its status leaves out the
 * files that git skipped, but whatever makes it write the working tree or rebuild the index (a checkout, a hard or mixed
 * reset, a stash, `git add` and `git reset` of a folder) writes the skipped files to disk, or stages their deletion, and
 * clears their skip-worktree bit. Switching branches would quietly widen the user's sparse set, upstream issue 336.
 * git does what it's asked and keeps the sparse set as it is.
 *
 * The actions that can do this ask [applies], and run git through [run] when it says yes. It says no where JGit's work
 * is as good as git's: a repository that isn't sparse, or where Leaf finds no usable git, which is JGit's work as
 * before. The commands go through [GitCliRemoteCommand.runLocal], as the worktree steps do: git writes and stages LFS
 * files through Leaf's filter, with Leaf's dialogs, and the processing screen shows progress and Cancel.
 */
class SparseCheckoutGit @Inject constructor(
    private val gitCli: GitCli,
    private val remoteCommand: GitCliRemoteCommand,
    private val gitExecutableLocator: GitExecutableLocator,
    private val appSettingsService: AppSettingsService,
) {
    /** Whether git should do what [repository]'s JGit would: its working tree is sparse, and git is there to run. */
    suspend fun applies(repository: Repository): Boolean {
        if (!repository.usesSparseCheckout()) {
            return false
        }

        if (gitExecutableLocator.locate(appSettingsService.gitExecutablePath.first()) is Either.Err) {
            printLog(TAG, "Using JGit in a sparse checkout, as no usable git was found")
            return false
        }

        return true
    }

    /** Runs `git <args>` in [repository]'s working tree, and returns its stdout when git exits with 0. */
    suspend fun run(repository: Repository, vararg args: String): Either<String, GitError> {
        return remoteCommand.runLocal(repository.workTree, args.toList())
    }

    /** Like [run], for a command that only reads, such as `git rev-parse`: it gets no filters, dialogs or progress. */
    suspend fun read(repository: Repository, vararg args: String): Either<String, GitError> {
        return gitCli.run(repository.workTree, args.toList())
    }
}
