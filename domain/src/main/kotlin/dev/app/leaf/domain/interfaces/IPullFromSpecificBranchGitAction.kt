package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch

interface IPullFromSpecificBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, remoteBranch: Branch, pullWithRebase: Boolean): Either<PullHasConflicts, GitError>
}