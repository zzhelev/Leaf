package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

interface IMergeBranchGitAction {
    /**
     * @return true if success has conflicts, false if success without conflicts
     */
    suspend operator fun invoke(
        repositoryPath: String,
        branch: Branch,
        fastForward: Boolean,
    ): Either<Boolean, GitError>
}