package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit

interface ICreateBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, branchName: String, targetCommit: Commit?): Either<Unit, GitError>
}