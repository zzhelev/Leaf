// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.operations.GitCliOperations
import dev.app.leaf.data.git.repository.IsUnchangedSinceGitAction
import dev.app.leaf.data.git.stash.CreateSnapshotStashGitAction
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.PullHasConflicts
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.PullType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import javax.inject.Inject

/** The value of `pull.ff` that allows only fast-forwards. */
private const val FAST_FORWARD_ONLY = "only"

/**
 * Pulls with `git pull` (fork-only): git fetches, then merges or rebases as in a terminal, with its merge engine (ort,
 * which follows renames), its merge message (`Merge branch 'main' of <url>`), every hook that git runs, the fork point
 * of a rebased upstream, and git-lfs's filter for LFS files. It runs through [GitCliOperations.run], with
 * `GIT_EDITOR=:`.
 *
 * Merging or rebasing is Leaf's choice ([PullType]), passed as `--rebase` or `--no-rebase`, so the user's
 * `pull.rebase` and `branch.<name>.rebase` don't apply, as with JGit. `pull.ff=only` refuses a merge, as it did with
 * JGit: an explicit `--no-rebase` would merge anyway, and Leaf's Pull button passes one too.
 *
 * `merge.autoStash` and `rebase.autoStash` apply. A merge's waits in `MERGE_AUTOSTASH` while the merge stops at
 * conflicts, and Leaf's commit and Abort put it back ([dev.app.leaf.data.git.cli.operations.Autostashes]).
 *
 * Leaf's backup of local changes ("Automatically stash uncommitted changes before merge") is taken before a merge
 * pull, and deleted after one that completed, or that failed without changing anything, as Merge does.
 *
 * What is pulled is what JGit's `PullCommand` pulls ([pullSource]): with an upstream, git pulls it, and fetches the
 * whole remote; otherwise Leaf names the remote and the branch.
 */
class GitPullGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
    private val operations: GitCliOperations,
    private val checkHasUncommittedChangesGitAction: CheckHasUncommittedChangesGitAction,
    private val createSnapshotStashGitAction: CreateSnapshotStashGitAction,
    private val deleteStashGitAction: DeleteStashGitAction,
    private val isUnchangedSinceGitAction: IsUnchangedSinceGitAction,
) {
    suspend operator fun invoke(
        repositoryPath: String,
        pullType: PullType,
        mergeAutoStash: Boolean,
        remoteBranch: Branch?,
        automaticStashDescription: String,
    ): Either<PullHasConflicts, GitError> = either {
        val source = jgit.provide(repositoryPath) { git -> pullSource(git.repository, remoteBranch) }.bind()
        val rebase = pullType == PullType.REBASE

        val args = buildList {
            add("pull")
            add("--progress")

            if (rebase) {
                add("--rebase")
            } else {
                add("--no-rebase")
                add("--no-edit")

                if (allowsOnlyFastForwards(source.directory)) {
                    add("--ff-only")
                }
            }

            if (!source.isUpstream) {
                add(source.remote)
                add(source.ref)
            }
        }

        val backupStash = if (mergeAutoStash && !rebase) {
            backUpLocalChanges(repositoryPath, automaticStashDescription).bind()
        } else {
            null
        }

        // git exits with 1 for a fetch that fails and for local changes in the way too, so only unmerged paths tell
        // conflicts
        val result = operations.run(repositoryPath, args) { output ->
            remoteOperationError(output.exitCode, output.stderr)
        }

        if (backupStash != null) {
            val keep = when (result) {
                is Either.Ok -> result.value
                is Either.Err -> !changedNothing(repositoryPath, backupStash)
            }

            if (!keep) {
                deleteStashGitAction(repositoryPath, backupStash)
            }
        }

        result
    }

    /** Whether `pull.ff` is `only`, as git reads it, `includeIf` too. */
    private suspend fun allowsOnlyFastForwards(directory: File): Boolean {
        val output = gitCli.execute(directory, listOf("config", "--get", "pull.ff")).okOrNull() ?: return false

        return output.exitCode == 0 && output.stdout.trim() == FAST_FORWARD_ONLY
    }

    /** A snapshot of the uncommitted changes, untracked files too, which changes nothing in the working tree. */
    private suspend fun backUpLocalChanges(repositoryPath: String, message: String): Either<Commit?, GitError> =
        either {
            if (checkHasUncommittedChangesGitAction(repositoryPath).bind()) {
                createSnapshotStashGitAction(repositoryPath, message, includeUntracked = true)
            } else {
                Either.Ok(null)
            }
        }

    /**
     * Whether the pull left HEAD at the commit that [backupStash] was taken on, its first parent, and no merge, as
     * `MergeBranchUseCase` checks. A cancelled pull may have stopped halfway through the working tree, so its backup
     * stays.
     */
    private suspend fun changedNothing(repositoryPath: String, backupStash: Commit): Boolean {
        val head = backupStash.parentsHashes.firstOrNull() ?: return false

        if (!currentCoroutineContext().isActive) {
            return false
        }

        return isUnchangedSinceGitAction(repositoryPath, head) == Either.Ok(true)
    }
}
