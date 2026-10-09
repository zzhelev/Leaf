// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.domain.errors.CheckoutBranchError
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.models.WorktreeBranchUse
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException

/** A worktree that uses a branch. [path] is its folder, as git's messages name it. */
internal data class WorktreeUsingBranch(val path: String, val use: WorktreeBranchUse)

/**
 * Refuses to check out the local branch [branchName] (`refs/heads/x`) when another worktree uses it, as `git checkout`
 * and `git switch` do. The branch that [repository] has checked out is never refused, even if another worktree has it
 * too: checking it out changes nothing.
 */
internal fun EitherContext<GitError>.refuseIfUsedByOtherWorktree(repository: Repository, branchName: String) {
    if (repository.fullBranch == branchName) return

    val worktree = repository.findOtherWorktreeUsing(branchName) ?: return

    raiseError(
        CheckoutBranchError.BranchUsedByWorktree(
            branch = Repository.shortenRefName(branchName),
            worktreePath = worktree.path,
            use = worktree.use,
        )
    )
}

/**
 * Finds a worktree other than this repository's that uses the branch [branchName] (`refs/heads/x`), as git's
 * `die_if_checked_out` does: its HEAD is the branch, or its HEAD is detached while it rebases the branch or bisects
 * from it. Null when none does.
 *
 * JGit knows nothing of other worktrees, so this reads their git dirs as git does: the main worktree's unless the
 * repository is bare, then each one in `<common git dir>/worktrees/`, including those whose folder was deleted.
 *
 * Worktrees whose refs are in a reftable aren't found: their HEAD file is only a stub. JGit 7.7 can't read their HEAD
 * either, as it reads every worktree's HEAD from the shared reftable.
 */
internal fun Repository.findOtherWorktreeUsing(branchName: String): WorktreeUsingBranch? {
    val currentGitDir = directory.canonicalFile

    return worktreeGitDirs()
        .filter { (gitDir, _) -> gitDir != currentGitDir }
        .firstNotNullOfOrNull { (gitDir, path) ->
            worktreeUse(gitDir, branchName)?.let { use -> WorktreeUsingBranch(path, use) }
        }
}

/** Each worktree's git dir and folder, main worktree first, like git's `get_worktrees`. */
private fun Repository.worktreeGitDirs(): List<Pair<File, String>> {
    val commonDir = commonDirectory.canonicalFile
    val isBare = config.getBoolean(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_BARE, false)

    // `get_main_worktree`: the common git dir without its `.git`
    val mainPath = if (commonDir.name == Constants.DOT_GIT) commonDir.parent else commonDir.path
    val main = if (isBare) null else commonDir to mainPath

    val linked = File(commonDir, "worktrees").listFiles { file -> file.isDirectory }.orEmpty()
        .sortedBy { it.name }
        .mapNotNull { gitDir -> linkedWorktreePath(gitDir)?.let { path -> gitDir to path } }

    return listOfNotNull(main) + linked
}

/**
 * The folder of the linked worktree whose git dir is [gitDir], as git's `get_linked_worktree` reads it: the `gitdir`
 * file has the path of the worktree's `.git` file, relative to [gitDir] with `worktree.useRelativePaths`. Null without
 * that file, as git then skips the worktree.
 */
private fun linkedWorktreePath(gitDir: File): String? {
    val dotGitPath = File(gitDir, "gitdir").readTextOrNull()?.trimEnd()?.ifEmpty { null } ?: return null
    val path = dotGitPath.removeSuffix("/${Constants.DOT_GIT}")

    return if (File(path).isAbsolute) path else File(gitDir, path).canonicalPath
}

/** How the worktree whose git dir is [gitDir] uses [branchName], as git's `is_shared_symref` tells. */
private fun worktreeUse(gitDir: File, branchName: String): WorktreeBranchUse? {
    val head = symbolicHead(gitDir)

    if (head != null) {
        return if (head == branchName) WorktreeBranchUse.CheckedOut else null
    }

    // HEAD is detached, so the branch may be one that the worktree rebases or bisects (`wt_status_check_rebase` and
    // `wt_status_check_bisect`)
    val rebasedBranch = when {
        // `git am` also uses rebase-apply
        File(gitDir, "rebase-apply").exists() ->
            if (File(gitDir, "rebase-apply/applying").exists()) null else stateBranch(gitDir, "rebase-apply/head-name")

        File(gitDir, "rebase-merge").exists() -> stateBranch(gitDir, "rebase-merge/head-name")
        else -> null
    }

    val bisectedBranch = if (File(gitDir, "BISECT_LOG").exists()) stateBranch(gitDir, "BISECT_START") else null

    return when {
        rebasedBranch != null && isSameBranch(rebasedBranch, branchName) -> WorktreeBranchUse.Rebasing
        bisectedBranch != null && isSameBranch(bisectedBranch, branchName) -> WorktreeBranchUse.Bisecting
        else -> null
    }
}

/**
 * The branch that the HEAD file in [gitDir] points to (`ref: refs/heads/x`), or null when HEAD is detached or can't be
 * read. With refs in a reftable, it points to `refs/heads/.invalid`, which can't be a branch.
 */
private fun symbolicHead(gitDir: File): String? {
    val head = File(gitDir, Constants.HEAD).readTextOrNull()?.trimEnd() ?: return null

    return if (head.startsWith("ref:")) head.removePrefix("ref:").trim() else null
}

/** A branch name that git keeps in a state file of [gitDir], such as `rebase-merge/head-name` (git's `get_branch`). */
private fun stateBranch(gitDir: File, fileName: String) = File(gitDir, fileName).readTextOrNull()?.trimEnd('\n')

/** State files have `refs/heads/x` (rebases) or `x` (`BISECT_START`), and git compares them without `refs/heads/`. */
private fun isSameBranch(stateBranch: String, branchName: String) =
    stateBranch.removePrefix(Constants.R_HEADS) == branchName.removePrefix(Constants.R_HEADS)

private fun File.readTextOrNull(): String? = try {
    readText()
} catch (ex: IOException) {
    null
}
