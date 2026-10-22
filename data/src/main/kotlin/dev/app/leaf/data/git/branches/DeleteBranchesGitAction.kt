// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.worktrees.WorktreeHead
import dev.app.leaf.data.git.worktrees.worktreeHeads
import dev.app.leaf.domain.branches.BranchDeletionOutcome
import dev.app.leaf.domain.branches.BranchKept
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.branches.BranchesDeletion
import dev.app.leaf.domain.interfaces.IDeleteBranchesGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val TAG = "DeleteBranchesGitAction"

/**
 * Deletes several local branches (fork-only, upstream issue 116) as JGit's `DeleteBranchCommand` deletes one: a forced
 * `RefUpdate`, which also removes the branch's reflog, and then its `branch.<name>` section of the config. What differs:
 *  - each branch is deleted only if it still points to the commit that the dialog showed. The update carries that
 *    commit as the expected old value, so a branch that moves between the check and the delete is refused by the lock,
 *    and the dialog's choice never deletes a commit it didn't show;
 *  - a branch that a worktree uses stays, as in [DeleteBranchGitAction], the repository's own worktree included;
 *  - a branch that stays never stops the others, and the config is written once, for the branches that went.
 */
class DeleteBranchesGitAction @Inject constructor(private val jgit: JGit) : IDeleteBranchesGitAction {
    override suspend operator fun invoke(repositoryPath: String, branches: List<BranchToDelete>) =
        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                val repository = git.repository
                val heads = repository.worktreeHeads()
                val currentGitDir = repository.directory.canonicalFile

                val outcomes = branches.distinctBy { it.name }.map { branch ->
                    ensureActive()
                    deleteBranch(repository, heads, currentGitDir, branch)
                }

                removeConfigSections(repository, outcomes.filterIsInstance<BranchDeletionOutcome.Deleted>())

                BranchesDeletion(outcomes)
            }
        }

    private fun deleteBranch(
        repository: Repository,
        heads: List<WorktreeHead>,
        currentGitDir: File,
        branch: BranchToDelete,
    ): BranchDeletionOutcome {
        fun kept(reason: BranchKept) = BranchDeletionOutcome.Kept(branch, reason)

        if (!branch.name.startsWith(Constants.R_HEADS)) {
            return kept(BranchKept.Failed("${branch.name} is not a local branch."))
        }

        val ref = repository.exactRef(branch.name) ?: return kept(BranchKept.NoLongerExists)

        val worktree = heads.firstNotNullOfOrNull { head ->
            head.useOf(branch.name)?.let { use -> BranchKept.UsedByWorktree(head.path, use, head.gitDir == currentGitDir) }
        }

        if (worktree != null) {
            return kept(worktree)
        }

        val tip = ObjectId.fromString(branch.tip)

        if (ref.objectId != tip) {
            return kept(BranchKept.Moved(ref.objectId.name))
        }

        return try {
            val update = repository.updateRef(branch.name)

            update.setExpectedOldObjectId(tip)
            update.setRefLogMessage("branch deleted", false)
            update.isForceUpdate = true

            when (val result = update.delete()) {
                RefUpdate.Result.FORCED, RefUpdate.Result.FAST_FORWARD, RefUpdate.Result.NO_CHANGE ->
                    BranchDeletionOutcome.Deleted(branch)

                // The lock was refused: the ref changed after it was read above, or something else holds it
                RefUpdate.Result.LOCK_FAILURE -> {
                    val now = repository.exactRef(branch.name)

                    kept(
                        when {
                            now == null -> BranchKept.NoLongerExists
                            now.objectId != tip -> BranchKept.Moved(now.objectId.name)
                            else -> BranchKept.Failed("The branch is locked: ${result.name}")
                        }
                    )
                }

                else -> kept(BranchKept.Failed("git refused to delete the branch: ${result.name}"))
            }
        } catch (e: IOException) {
            kept(BranchKept.Failed(e.message ?: e.toString()))
        }
    }

    /** Takes `branch.<name>` out of the config for each deleted branch, as `git branch -D` does. */
    private fun removeConfigSections(repository: Repository, deleted: List<BranchDeletionOutcome.Deleted>) {
        if (deleted.isEmpty()) return

        try {
            val config = repository.config

            for (outcome in deleted) {
                config.removeSection("branch", outcome.branch.simpleName)
            }

            config.save()
        } catch (e: IOException) {
            // The branches are gone, which is what matters: a stale section does no harm
            printError(TAG, "Could not remove the deleted branches' config: ${e.message}")
        }
    }
}
