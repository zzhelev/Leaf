package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetBranchesGitAction
import dev.app.leaf.domain.models.Branch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import javax.inject.Inject

class GetBranchesGitAction @Inject constructor(
    private val jGitBranchMapper: JGitBranchMapper,
    private val jgit: JGit,
) : IGetBranchesGitAction {
    // TODO after refactor remove this overload
    override suspend operator fun invoke(git: Git): Either<List<Branch>, AppError> {
        return invoke(git.repository.directory.absolutePath)
    }

    override suspend operator fun invoke(repository: String): Either<List<Branch>, AppError> {
        return jgit.provide(repository) { git ->
            git
                .branchList()
                .call()
                .mapNotNull { jGitBranchMapper.toDomain(it) }
        }
    }
}