package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.PullType
import org.eclipse.jgit.api.Git

interface IPullBranchGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        pullType: PullType,
        mergeAutoStash: Boolean,
        remoteBranch: Branch?,
        automaticStashDescription: String,
    ): Either<PullHasConflicts, GitError>
}