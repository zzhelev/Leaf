package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.interfaces.IGetRepositoryStateGitAction
import javax.inject.Inject

class GetRepositoryStateGitAction @Inject constructor(
    private val jgit: JGit,
    private val repositoryStateMapper: RepositoryStateMapper,
) : IGetRepositoryStateGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        repositoryStateMapper.toDomain(git.repository.repositoryState)
    }
}