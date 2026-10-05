package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

typealias IsMultiStep = Boolean

interface IRebaseBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, branch: Branch): Either<IsMultiStep, GitError>
}