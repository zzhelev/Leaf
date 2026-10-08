// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.remote_operations.DeleteRemoteBranchGitAction
import dev.app.leaf.data.git.remote_operations.PushBranchGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchGitAction
import dev.app.leaf.domain.interfaces.IPushBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "RemoteOperationsBackend"

private val LFS_VERSION_TIMEOUT = 10.seconds

/**
 * Decides whether a remote operation runs with the git CLI or with JGit, Leaf's built-in implementation. The git CLI
 * is used unless:
 * - the "Use git for remote operations" setting is off;
 * - no usable git was found, or this build has no askpass helper;
 * - the push sends commits of a repository that uses LFS, but git wouldn't upload its LFS objects: the repository's
 *   `pre-push` hook doesn't run git-lfs, or git-lfs isn't installed. JGit uploads them itself.
 *
 * It never switches after git has started: a push could then run twice.
 */
class RemoteOperationsBackend @Inject constructor(
    private val appSettingsService: AppSettingsService,
    private val gitExecutableLocator: GitExecutableLocator,
    private val askpassHelper: AskpassHelper,
    private val gitCli: GitCli,
    private val jgit: JGit,
) {
    suspend fun useGitCli(repositoryPath: String, uploadsObjects: Boolean): Boolean {
        if (!appSettingsService.remoteOperationsWithGit.first()) {
            return false
        }

        if (gitExecutableLocator.locate(appSettingsService.gitExecutablePath.first()) is Either.Err) {
            printLog(TAG, "Using JGit, as no usable git was found")
            return false
        }

        if (askpassHelper.path() == null) {
            printLog(TAG, "Using JGit, as the askpass helper is missing")
            return false
        }

        if (uploadsObjects && !gitUploadsLfsObjects(repositoryPath)) {
            printLog(TAG, "Using JGit, as git wouldn't upload this repository's LFS objects")
            return false
        }

        return true
    }

    private suspend fun gitUploadsLfsObjects(repositoryPath: String): Boolean {
        val lfs = jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val attributes = if (repository.isBare) null else File(repository.workTree, ".gitattributes")

            val usesLfs = File(repository.commonDirectory ?: repository.directory, "lfs").isDirectory ||
                attributes?.takeIf { it.isFile }?.readText()?.contains("filter=lfs") == true

            // git-lfs's hook runs "git lfs pre-push"; hook managers that call it are found the same way
            val prePushRunsLfs = repository.fs.findHook(repository, "pre-push")
                ?.takeIf { it.isFile }
                ?.readText()
                ?.let { it.contains("git lfs") || it.contains("git-lfs") } == true

            LfsUse(usesLfs, prePushRunsLfs, repository.commandDirectory())
        }

        val use = when (lfs) {
            is Either.Err -> return true // The push itself will report the problem
            is Either.Ok -> lfs.value
        }

        if (!use.usesLfs) {
            return true
        }

        return use.prePushRunsLfs &&
            gitCli.run(use.directory, listOf("lfs", "version"), LFS_VERSION_TIMEOUT) is Either.Ok
    }

    private class LfsUse(val usesLfs: Boolean, val prePushRunsLfs: Boolean, val directory: File)
}

/** Pushes with the git CLI or with JGit, as [RemoteOperationsBackend] decides. */
class SelectingPushBranchGitAction @Inject constructor(
    private val backend: RemoteOperationsBackend,
    private val gitCliPushBranchGitAction: GitCliPushBranchGitAction,
    private val jGitPushBranchGitAction: PushBranchGitAction,
) : IPushBranchGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        force: Boolean,
        pushTags: Boolean,
        pushWithLease: Boolean,
        specificBranch: Branch?,
    ): Either<Unit, GitError> = if (backend.useGitCli(repositoryPath, uploadsObjects = true)) {
        gitCliPushBranchGitAction(repositoryPath, force, pushTags, pushWithLease, specificBranch)
    } else {
        jGitPushBranchGitAction(repositoryPath, force, pushTags, pushWithLease, specificBranch)
    }
}

/** Deletes a remote branch with the git CLI or with JGit, as [RemoteOperationsBackend] decides. */
class SelectingDeleteRemoteBranchGitAction @Inject constructor(
    private val backend: RemoteOperationsBackend,
    private val gitCliDeleteRemoteBranchGitAction: GitCliDeleteRemoteBranchGitAction,
    private val jGitDeleteRemoteBranchGitAction: DeleteRemoteBranchGitAction,
) : IDeleteRemoteBranchGitAction {
    override suspend fun invoke(repositoryPath: String, ref: Branch): Either<Unit, GitError> =
        if (backend.useGitCli(repositoryPath, uploadsObjects = false)) {
            gitCliDeleteRemoteBranchGitAction(repositoryPath, ref)
        } else {
            jGitDeleteRemoteBranchGitAction(repositoryPath, ref)
        }
}
