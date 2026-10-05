package dev.app.leaf.data.log

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.interfaces.IGetFileCommitsAction
import javax.inject.Inject

class GetFileCommitsAction @Inject constructor(
    private val commitMapper: JGitCommitMapper,
    private val jgit: JGit,
) : IGetFileCommitsAction {
    override suspend fun invoke(
        repositoryPath: String,
        filePath: String
    ) = jgit.provide(repositoryPath) { git ->
        git.log()
            .addPath(filePath)
            .call()
            .toList()
            .map { commitMapper.toDomain(it) }
    }
}