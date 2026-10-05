package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IRenameBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, oldName: String, newName: String): Either<Branch, GitError>
}