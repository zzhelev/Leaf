// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.remote_operations.mergeHasConflicts
import dev.app.leaf.data.git.remote_operations.PullWouldOverwriteException
import dev.app.leaf.data.git.remote_operations.rebaseHasConflicts
import dev.app.leaf.data.git.remote_operations.useBuiltinLfs
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.stash.SnapshotStashCreateCommand
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.PullHasConflicts
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.PullType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand.ConflictStyle
import org.eclipse.jgit.api.MergeCommand.FastForwardMode
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import javax.inject.Inject

private const val LOCAL_REMOTE = "."

/**
 * Pulls like [dev.app.leaf.data.git.remote_operations.PullBranchGitAction], but fetches with `git fetch`: JGit then
 * merges or rebases the fetched commit, as JGit's `PullCommand` does after its own fetch. So Leaf's automatic stash,
 * its conflict handling and its built-in LFS stay, and git never opens an editor for the merge message. When git-lfs
 * is installed, it downloads the commit's LFS objects first ([GitLfsFetch]), so the built-in LFS only reads them.
 *
 * What is pulled is what JGit's `PullCommand` (and git) pulls: [Branch] when one is given, or else the branch's
 * upstream (`branch.<name>.remote` and `branch.<name>.merge`), or else the branch of the same name on `origin`.
 */
class GitCliPullBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
    private val gitLfsFetch: GitLfsFetch,
    private val checkHasUncommittedChangesGitAction: CheckHasUncommittedChangesGitAction,
    private val deleteStashGitAction: DeleteStashGitAction,
    private val commitMapper: JGitCommitMapper,
) {
    suspend operator fun invoke(
        repositoryPath: String,
        pullType: PullType,
        mergeAutoStash: Boolean,
        remoteBranch: Branch?,
        automaticStashDescription: String,
    ): Either<PullHasConflicts, GitError> = either {
        val source = jgit.provide(repositoryPath) { git -> pullSource(git.repository, remoteBranch) }.bind()

        val fetched = if (source.remote == LOCAL_REMOTE) {
            // Like git and JGit, a branch whose upstream is a local branch pulls without fetching
            val commit = jgit.provide(repositoryPath) { git -> git.repository.resolve(source.ref) }.bind()
                ?: raiseError(GenericError("${Repository.shortenRefName(source.ref)} doesn't exist."))

            FetchHeadEntry(commit.name, forMerge = true, "branch '${Repository.shortenRefName(source.ref)}'")
        } else {
            fetch(source)
        }

        if (source.remote != LOCAL_REMOTE) {
            // Before anything changes, so that a failed download leaves the branch as it was
            jgit.provide(repositoryPath) { git ->
                gitLfsFetch(git.repository, source.remote, ObjectId.fromString(fetched.objectId)).bind()
            }.bind()
        }

        val hasConflicts = jgit.provide(repositoryPath) { git ->
            useBuiltinLfs(git.repository) {
                var backupStash: Commit? = null

                if (mergeAutoStash && pullType != PullType.REBASE) {
                    val hasUncommittedChanges = checkHasUncommittedChangesGitAction(repositoryPath).bind()

                    if (hasUncommittedChanges) {
                        backupStash = SnapshotStashCreateCommand(
                            repository = git.repository,
                            workingDirectoryMessage = automaticStashDescription,
                            includeUntracked = true,
                        ).call()?.let { commitMapper.toDomain(it) }
                    }
                }

                val hasConflicts = try {
                    integrate(git, ObjectId.fromString(fetched.objectId), fetched.description, pullType)
                } catch (e: PullWouldOverwriteException) {
                    // The merge changed nothing, so the backup of the local changes isn't needed
                    backupStash?.let { deleteStashGitAction(git.repository.directory.absolutePath, it) }
                    throw e
                }

                if (!hasConflicts && backupStash != null) {
                    deleteStashGitAction(git.repository.directory.absolutePath, backupStash)
                }

                hasConflicts
            }
        }.bind()

        Either.Ok(hasConflicts)
    }

    /** Fetches what is pulled, and returns its line of `FETCH_HEAD`. */
    private suspend fun EitherContext<GitError>.fetch(source: PullSource): FetchHeadEntry {
        // Fetching the tracked remote updates all its remote-tracking branches, as `git pull` does, and marks the
        // upstream for merge. Anything else is fetched by name, which marks it for merge.
        val args = if (source.isUpstream) {
            listOf("fetch", "--progress", source.remote)
        } else {
            listOf("fetch", "--progress", source.remote, source.ref)
        }

        val output = remoteCommand.run(source.directory, args).bind()

        if (output.exitCode != 0) {
            raiseError(remoteOperationError(output.exitCode, output.stderr))
        }

        val fetchHead = File(source.gitDir, "FETCH_HEAD").takeIf { it.isFile }?.readText().orEmpty()

        return parseFetchHead(fetchHead).firstOrNull { it.forMerge } ?: raiseError(
            GenericError("${source.remote} has no branch ${Repository.shortenRefName(source.ref)} to pull.")
        )
    }

    /** Merges or rebases [commit], as JGit's `PullCommand` does with the commit it fetched. */
    private fun integrate(git: Git, commit: ObjectId, upstreamName: String, pullType: PullType): PullHasConflicts {
        val repository = git.repository

        if (repository.exactRef(Constants.HEAD)?.objectId == null) {
            checkOutIntoUnbornBranch(repository, commit)
            return false
        }

        val conflictStyle = repository.config.getEnum(
            ConfigConstants.CONFIG_MERGE_SECTION,
            null,
            ConfigConstants.CONFIG_KEY_CONFLICTSTYLE,
            ConflictStyle.MERGE,
        )

        return if (pullType == PullType.REBASE) {
            val result = git.rebase()
                .setUpstream(commit)
                .setUpstreamName(upstreamName)
                .setOperation(RebaseCommand.Operation.BEGIN)
                .setStrategy(MergeStrategy.RECURSIVE)
                .setConflictStyle(conflictStyle)
                .setPreserveMerges(false)
                .call()

            rebaseHasConflicts(result)
        } else {
            // pull.ff, as JGit's PullCommand reads it. Without it, the merge reads merge.ff itself.
            val fastForward = repository.config.getEnum(
                FastForwardMode.Merge.values(),
                ConfigConstants.CONFIG_PULL_SECTION,
                null,
                ConfigConstants.CONFIG_KEY_FF,
                null,
            )?.let { FastForwardMode.valueOf(it) }

            val result = try {
                git.merge()
                    .include(upstreamName, commit)
                    .setStrategy(MergeStrategy.RECURSIVE)
                    .setConflictStyle(conflictStyle)
                    .setFastForward(fastForward)
                    .call()
            } catch (e: CheckoutConflictException) {
                // A fast-forward throws where a merge returns FAILED
                throw PullWouldOverwriteException(e.conflictingPaths)
            }

            mergeHasConflicts(result)
        }
    }

    /** A branch without commits takes the pulled commit, as with JGit's `PullCommand` and git. */
    private fun checkOutIntoUnbornBranch(repository: Repository, commit: ObjectId) {
        RevWalk(repository).use { walk ->
            val tree = walk.parseCommit(commit).tree

            DirCacheCheckout(repository, repository.lockDirCache(), tree).apply {
                setFailOnConflict(true)
                checkout()
            }
        }

        val head = repository.exactRef(Constants.HEAD)
        val update = repository.updateRef(head.target.name).apply {
            setNewObjectId(commit)
            setExpectedOldObjectId(null)
            setRefLogMessage("initial pull", false)
        }

        if (update.update() != RefUpdate.Result.NEW) {
            throw Exception("Could not update ${head.target.name} to the pulled commit")
        }
    }

    private class PullSource(
        val remote: String,
        /** The full name of the ref to pull from the remote, such as `refs/heads/main`. */
        val ref: String,
        /** Whether it's the branch's configured upstream. */
        val isUpstream: Boolean,
        val directory: File,
        val gitDir: File,
    )

    private fun pullSource(repository: Repository, remoteBranch: Branch?): PullSource {
        if (repository.repositoryState != RepositoryState.SAFE) {
            throw Exception("Can't pull while this is in progress: ${repository.repositoryState.description}")
        }

        val directory = repository.commandDirectory()
        val gitDir = repository.directory

        if (remoteBranch != null) {
            return PullSource(
                remote = remoteBranch.remoteName,
                ref = Constants.R_HEADS + remoteBranch.simpleName,
                isUpstream = false,
                directory = directory,
                gitDir = gitDir,
            )
        }

        val branch = repository.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }?.removePrefix(Constants.R_HEADS)
            ?: throw Exception("HEAD isn't on a branch, so there is no branch to pull into.")

        val config = repository.config
        val merge = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE)
        val remote = config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE)

        return PullSource(
            remote = remote ?: Constants.DEFAULT_REMOTE_NAME,
            ref = (merge ?: branch).let { if (it.startsWith(Constants.R_REFS)) it else Constants.R_HEADS + it },
            isUpstream = merge != null && remote != null,
            directory = directory,
            gitDir = gitDir,
        )
    }
}
