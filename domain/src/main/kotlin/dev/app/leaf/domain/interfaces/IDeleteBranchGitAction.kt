package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

interface IDeleteBranchGitAction {
    /**
     * Deletes [branch]. Without [force], a branch that isn't merged into HEAD is kept, and the result is
     * [dev.app.leaf.domain.errors.DeleteRefError.BranchNotMerged]. A branch that a worktree uses, the repository's
     * own included, is kept even with [force]: [dev.app.leaf.domain.errors.DeleteBranchError.BranchUsedByWorktree].
     */
    suspend operator fun invoke(repositoryPath: String, branch: Branch, force: Boolean): Either<Unit, GitError>
}