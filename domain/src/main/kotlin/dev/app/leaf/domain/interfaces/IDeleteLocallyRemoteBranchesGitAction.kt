package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import org.eclipse.jgit.api.Git

interface IDeleteLocallyRemoteBranchesGitAction {
    suspend operator fun invoke(repositoryPath: String, branches: List<String>): Either<List<String>, GitError>
}