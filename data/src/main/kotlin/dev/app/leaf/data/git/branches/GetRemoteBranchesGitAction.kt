package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetRemoteBranchesGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.ListBranchCommand
import javax.inject.Inject

class GetRemoteBranchesGitAction @Inject constructor(
    private val jGitBranchMapper: JGitBranchMapper,
    private val jgit: JGit,
) : IGetRemoteBranchesGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<List<Branch>, GitError> {
        return jgit.provide(repositoryPath) { git ->
            git
                .branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call()
                .mapNotNull { jGitBranchMapper.toDomain(it) }
        }
    }
}