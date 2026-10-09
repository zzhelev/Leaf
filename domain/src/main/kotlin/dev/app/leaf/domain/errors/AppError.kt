package dev.app.leaf.domain.errors

import dev.app.leaf.domain.models.WorktreeBranchUse

sealed interface AppError

sealed interface GitError : AppError


data class GenericError(val message: String, val exception: Exception? = null) : GitError

sealed interface CreateBranchError : GitError {
    data class BranchAlreadyExists(val name: String): CreateBranchError
    data class NameNotAllowed(val name: String): CreateBranchError
}

sealed interface CheckoutBranchError : GitError {
    /**
     * Checking out [remoteBranch] was to fast-forward the local branch [localBranch] to it, but they have diverged
     * since the user chose to: [localBranch] has commits that [remoteBranch] doesn't have. Nothing changed.
     */
    data class CannotFastForward(val localBranch: String, val remoteBranch: String) : CheckoutBranchError

    /**
     * The local branch [branch] can't be checked out, or fast-forwarded, because the worktree at [worktreePath], which
     * isn't the one the tab shows, uses it ([use]). git refuses this too ("already used by worktree at"). Nothing
     * changed.
     */
    data class BranchUsedByWorktree(
        val branch: String,
        val worktreePath: String,
        val use: WorktreeBranchUse,
    ) : CheckoutBranchError
}

/**
 * A branch or tag deletion refused without force. [commitsOnlyOnRef] commits are on no other branch, tag or HEAD, so
 * deleting the ref with force leaves them on none.
 */
sealed interface DeleteRefError : GitError {
    val commitsOnlyOnRef: Int

    /** The branch isn't merged into HEAD, as `git branch -d` checks. */
    data class BranchNotMerged(val branchName: String, override val commitsOnlyOnRef: Int) : DeleteRefError

    /** The tag is the only ref left on some commits. */
    data class TagHasOwnCommits(val tagName: String, override val commitsOnlyOnRef: Int) : DeleteRefError
}

/** A branch deletion refused even with force, unlike [DeleteRefError]. */
sealed interface DeleteBranchError : GitError {
    /**
     * The local branch [branch] can't be deleted because the worktree at [worktreePath], possibly the one the tab shows,
     * uses it ([use]). git refuses this even with `-D` ("cannot delete branch used by worktree at"). Nothing changed.
     */
    data class BranchUsedByWorktree(
        val branch: String,
        val worktreePath: String,
        val use: WorktreeBranchUse,
    ) : DeleteBranchError
}

sealed interface RenameBranchError : GitError {
    /**
     * The local branch [branch] can't be renamed because the worktree at [worktreePath] is rebasing it, as git refuses
     * ("is being rebased at"). Nothing changed.
     */
    data class BranchRebasedInWorktree(val branch: String, val worktreePath: String) : RenameBranchError

    /**
     * The local branch [branch] can't be renamed because the worktree at [worktreePath] is bisecting from it, as git
     * refuses ("is being bisected at"). Nothing changed.
     */
    data class BranchBisectedInWorktree(val branch: String, val worktreePath: String) : RenameBranchError

    /**
     * [oldBranch] was renamed to [newBranch], but the HEAD of the worktree at [worktreePath], which had [oldBranch]
     * checked out, couldn't be moved to [newBranch], as something else was writing it. That worktree is on
     * [oldBranch], which no longer exists, as after git's "branch renamed to, but HEAD is not updated".
     */
    data class WorktreeHeadNotMoved(
        val oldBranch: String,
        val newBranch: String,
        val worktreePath: String,
    ) : RenameBranchError
}

/**
 * Repository path for current tab is not set
 */
data object RepositoryPathNotSetError : GitError

/**
 * Errors reading information from a repository (such as branches, status, etc.)
 */
data class RepositoryReadError(val message: String) : GitError

data class HookRejectionError(val message: String): GitError

sealed interface StashChangesError: GitError {
    data object NoDataToStash: StashChangesError
}

sealed interface FSWatchError: AppError {

}

sealed interface OpenRepoError: AppError {
    data object DirectoryNotFoundError : OpenRepoError
    data object PathIsNotDirectory : OpenRepoError
    data object RepositoryNotFoundInPath : OpenRepoError
    data class RepositoryLoadFailed(val error: String) : OpenRepoError
}
