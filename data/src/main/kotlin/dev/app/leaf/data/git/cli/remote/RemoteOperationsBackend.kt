// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.common.printLog
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.data.git.cli.askpass.AskpassHelper
import dev.app.leaf.data.git.remote_operations.CloneRepositoryGitAction
import dev.app.leaf.data.git.remote_operations.DeleteRemoteBranchGitAction
import dev.app.leaf.data.git.remote_operations.FetchAllRemotesGitAction
import dev.app.leaf.data.git.remote_operations.PullBranchGitAction
import dev.app.leaf.data.git.remote_operations.PushBranchGitAction
import dev.app.leaf.data.git.submodules.AddSubmoduleGitAction
import dev.app.leaf.data.git.submodules.UpdateSubmoduleGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.SshNeedsGitError
import dev.app.leaf.domain.interfaces.IAddSubmoduleGitAction
import dev.app.leaf.domain.interfaces.ICloneRepositoryGitAction
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchGitAction
import dev.app.leaf.domain.interfaces.IFetchAllRemotesGitAction
import dev.app.leaf.domain.interfaces.IPullBranchGitAction
import dev.app.leaf.domain.interfaces.IPushBranchGitAction
import dev.app.leaf.domain.interfaces.IUpdateSubmoduleGitAction
import dev.app.leaf.domain.interfaces.PullHasConflicts
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.models.PullType
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "RemoteOperationsBackend"

private val LFS_VERSION_TIMEOUT = 10.seconds

/** How `.gitattributes` names git-lfs's filter (`git lfs track`). */
private const val LFS_FILTER = "filter=lfs"

/**
 * Decides whether a remote operation runs with the git CLI or with JGit, Leaf's built-in implementation. The git CLI
 * is used unless:
 * - the "Use git for remote operations" setting is off;
 * - no usable git was found, or this build has no askpass helper;
 * - the push sends commits of a repository that uses LFS, but git wouldn't upload its LFS objects: the repository's
 *   `pre-push` hook doesn't run git-lfs, or git-lfs isn't installed. JGit uploads them itself.
 *
 * Fetch, pull, clone, and adding or updating a submodule use it the same way, as they upload nothing. JGit does a
 * clone's checkout either way. A pull runs `git pull`, unless git's checkout would need Leaf's built-in LFS
 * ([pullNeedsBuiltinLfs]): then it fetches with git and JGit merges.
 *
 * It never switches after git has started: a push could then run twice.
 *
 * JGit can't reach SSH remotes, as Leaf has no SSH of its own: there, JGit fails before it connects, with the reason
 * it ran ([whyNotGitCli]).
 */
class RemoteOperationsBackend @Inject constructor(
    private val appSettingsService: AppSettingsService,
    private val gitExecutableLocator: GitExecutableLocator,
    private val askpassHelper: AskpassHelper,
    private val gitCli: GitCli,
    private val jgit: JGit,
) {
    suspend fun useGitCli(repositoryPath: String, uploadsObjects: Boolean): Boolean {
        if (!useGitCli()) {
            return false
        }

        if (uploadsObjects && !gitUploadsLfsObjects(repositoryPath)) {
            printLog(TAG, "Using JGit, as git wouldn't upload this repository's LFS objects")
            return false
        }

        return true
    }

    /** For an operation that uploads nothing, without a repository: a clone. */
    suspend fun useGitCli(): Boolean = whyNotGitCli() == null

    /**
     * Why an operation that uploads nothing runs with JGit, or null when it runs with the git CLI. JGit's SSH asks it
     * ([dev.app.leaf.data.git.remote_operations.NoSshSessionFactory]), to tell the user what makes git run.
     */
    suspend fun whyNotGitCli(): SshNeedsGitError.Reason? {
        if (!appSettingsService.remoteOperationsWithGit.first()) {
            return SshNeedsGitError.Reason.SettingOff
        }

        if (gitExecutableLocator.locate(appSettingsService.gitExecutablePath.first()) is Either.Err) {
            printLog(TAG, "Using JGit, as no usable git was found")
            return SshNeedsGitError.Reason.GitNotFound
        }

        if (askpassHelper.path() == null) {
            printLog(TAG, "Using JGit, as the askpass helper is missing")
            return SshNeedsGitError.Reason.HelperMissing
        }

        return null
    }

    /**
     * Whether a pull of [remoteBranch], or of the branch's upstream, needs Leaf's built-in LFS client: the repository
     * uses LFS, or the branch pulled does as its remote-tracking branch has it, and git-lfs doesn't work. `git pull`
     * would then check out pointer files without a word, or fail with `filter.lfs.required` (which `git lfs install`
     * sets), a rebase halfway.
     */
    suspend fun pullNeedsBuiltinLfs(repositoryPath: String, remoteBranch: Branch?): Boolean {
        val lfs = jgit.provide(repositoryPath) { git ->
            val repository = git.repository
            val usesLfs = usesLfs(repository) || pulledCommitUsesLfs(repository, remoteBranch)

            LfsUse(usesLfs, prePushRunsLfs = false, repository.commandDirectory())
        }

        val use = when (lfs) {
            is Either.Err -> return false // The pull itself will report the problem
            is Either.Ok -> lfs.value
        }

        return use.usesLfs && !gitLfsWorks(use.directory)
    }

    private suspend fun gitUploadsLfsObjects(repositoryPath: String): Boolean {
        val lfs = jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            // git-lfs's hook runs "git lfs pre-push"; hook managers that call it are found the same way
            val prePushRunsLfs = repository.fs.findHook(repository, "pre-push")
                ?.takeIf { it.isFile }
                ?.readText()
                ?.let { it.contains("git lfs") || it.contains("git-lfs") } == true

            LfsUse(usesLfs(repository), prePushRunsLfs, repository.commandDirectory())
        }

        val use = when (lfs) {
            is Either.Err -> return true // The push itself will report the problem
            is Either.Ok -> lfs.value
        }

        if (!use.usesLfs) {
            return true
        }

        return use.prePushRunsLfs && gitLfsWorks(use.directory)
    }

    private suspend fun gitLfsWorks(directory: File): Boolean {
        return gitCli.run(directory, listOf("lfs", "version"), LFS_VERSION_TIMEOUT) is Either.Ok
    }

    private class LfsUse(val usesLfs: Boolean, val prePushRunsLfs: Boolean, val directory: File)
}

/** Whether [repository] uses LFS: it has LFS objects, or its root `.gitattributes` names the LFS filter. */
private fun usesLfs(repository: Repository): Boolean {
    val attributes = if (repository.isBare) null else File(repository.workTree, Constants.DOT_GIT_ATTRIBUTES)

    return File(repository.commonDirectory ?: repository.directory, "lfs").isDirectory ||
        attributes?.takeIf { it.isFile }?.readText()?.contains(LFS_FILTER) == true
}

/**
 * Whether the root `.gitattributes` of what a pull pulls names the LFS filter, as far as Leaf knows it before the
 * fetch: [remoteBranch]'s commit, or else the remote-tracking branch of the branch's upstream. A file that a partial
 * clone hasn't downloaded counts as no.
 */
private fun pulledCommitUsesLfs(repository: Repository, remoteBranch: Branch?): Boolean {
    val ref = remoteBranch?.name
        ?: repository.branch?.let { BranchConfig(repository.config, it).remoteTrackingBranch }
        ?: return false
    val commit = repository.resolve(ref) ?: return false

    return try {
        RevWalk(repository).use { walk ->
            val tree = walk.parseCommit(commit).tree

            TreeWalk.forPath(repository, Constants.DOT_GIT_ATTRIBUTES, tree)?.use { treeWalk ->
                repository.open(treeWalk.getObjectId(0)).bytes.decodeToString().contains(LFS_FILTER)
            } == true
        }
    } catch (e: MissingObjectException) {
        false
    }
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

/**
 * Fetches with the git CLI or with JGit, as [RemoteOperationsBackend] decides. A fetch uploads nothing, and git-lfs
 * doesn't take part in it.
 */
class SelectingFetchAllRemotesGitAction @Inject constructor(
    private val backend: RemoteOperationsBackend,
    private val gitCliFetchAllRemotesGitAction: GitCliFetchAllRemotesGitAction,
    private val jGitFetchAllRemotesGitAction: FetchAllRemotesGitAction,
) : IFetchAllRemotesGitAction {
    override suspend fun invoke(repositoryPath: String, specificRemote: Remote?): Either<Unit, GitError> =
        if (backend.useGitCli(repositoryPath, uploadsObjects = false)) {
            gitCliFetchAllRemotesGitAction(repositoryPath, specificRemote)
        } else {
            jGitFetchAllRemotesGitAction(repositoryPath, specificRemote)
        }
}

/**
 * Pulls as [RemoteOperationsBackend] decides: with `git pull` ([GitPullGitAction]); with git's fetch and JGit's merge
 * or rebase, with Leaf's built-in LFS, when git's checkout would need it
 * ([RemoteOperationsBackend.pullNeedsBuiltinLfs]); or with JGit alone.
 */
class SelectingPullBranchGitAction @Inject constructor(
    private val backend: RemoteOperationsBackend,
    private val gitPullGitAction: GitPullGitAction,
    private val gitCliPullBranchGitAction: GitCliPullBranchGitAction,
    private val jGitPullBranchGitAction: PullBranchGitAction,
) : IPullBranchGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        pullType: PullType,
        mergeAutoStash: Boolean,
        remoteBranch: Branch?,
        automaticStashDescription: String,
    ): Either<PullHasConflicts, GitError> = when {
        !backend.useGitCli(repositoryPath, uploadsObjects = false) ->
            jGitPullBranchGitAction(repositoryPath, pullType, mergeAutoStash, remoteBranch, automaticStashDescription)

        backend.pullNeedsBuiltinLfs(repositoryPath, remoteBranch) ->
            gitCliPullBranchGitAction(repositoryPath, pullType, mergeAutoStash, remoteBranch, automaticStashDescription)

        else ->
            gitPullGitAction(repositoryPath, pullType, mergeAutoStash, remoteBranch, automaticStashDescription)
    }
}

/**
 * Clones with the git CLI or with JGit, as [RemoteOperationsBackend] decides when the clone starts. Either way JGit
 * checks the files out, with its built-in LFS.
 */
class SelectingCloneRepositoryGitAction @Inject constructor(
    private val backend: RemoteOperationsBackend,
    private val gitCliCloneRepositoryGitAction: GitCliCloneRepositoryGitAction,
    private val jGitCloneRepositoryGitAction: CloneRepositoryGitAction,
) : ICloneRepositoryGitAction {
    override fun invoke(
        directory: File,
        url: String,
        cloneSubmodules: Boolean,
        branch: String?,
        singleBranch: Boolean,
    ): Flow<CloneState> = flow {
        val states = if (backend.useGitCli()) {
            gitCliCloneRepositoryGitAction(directory, url, cloneSubmodules, branch, singleBranch)
        } else {
            jGitCloneRepositoryGitAction(directory, url, cloneSubmodules, branch, singleBranch)
        }

        emitAll(states)
    }
}

/** Clones or updates a submodule with the git CLI or with JGit, as [RemoteOperationsBackend] decides. */
class SelectingUpdateSubmoduleGitAction @Inject constructor(
    private val backend: RemoteOperationsBackend,
    private val gitCliUpdateSubmoduleGitAction: GitCliUpdateSubmoduleGitAction,
    private val jGitUpdateSubmoduleGitAction: UpdateSubmoduleGitAction,
) : IUpdateSubmoduleGitAction {
    override suspend fun invoke(repositoryPath: String, path: String): Either<Unit, GitError> =
        if (backend.useGitCli(repositoryPath, uploadsObjects = false)) {
            gitCliUpdateSubmoduleGitAction(repositoryPath, path)
        } else {
            jGitUpdateSubmoduleGitAction(repositoryPath, path)
        }
}

/** Adds a submodule with the git CLI or with JGit, as [RemoteOperationsBackend] decides. */
class SelectingAddSubmoduleGitAction @Inject constructor(
    private val backend: RemoteOperationsBackend,
    private val gitCliAddSubmoduleGitAction: GitCliAddSubmoduleGitAction,
    private val jGitAddSubmoduleGitAction: AddSubmoduleGitAction,
) : IAddSubmoduleGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        name: String,
        path: String,
        uri: String,
    ): Either<Unit, GitError> = if (backend.useGitCli(repositoryPath, uploadsObjects = false)) {
        gitCliAddSubmoduleGitAction(repositoryPath, name, path, uri)
    } else {
        jGitAddSubmoduleGitAction(repositoryPath, name, path, uri)
    }
}
