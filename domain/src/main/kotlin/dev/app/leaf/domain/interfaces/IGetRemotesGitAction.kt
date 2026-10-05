package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Remote
import dev.app.leaf.domain.models.RemoteInfo
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IGetRemotesGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<List<Remote>, GitError>
}