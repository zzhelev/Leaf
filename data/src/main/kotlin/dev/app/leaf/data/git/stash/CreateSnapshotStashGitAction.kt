package dev.app.leaf.data.git.stash

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.ICreateSnapshotStashGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

class CreateSnapshotStashGitAction @Inject constructor(
    private val commitMapper: JGitCommitMapper,
    private val jgit: JGit,
) : ICreateSnapshotStashGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        message: String,
        includeUntracked: Boolean,
    ): Either<Commit?, GitError> = jgit.provide(repositoryPath) { git ->
        SnapshotStashCreateCommand(
            repository = git.repository,
            workingDirectoryMessage = message,
            includeUntracked = true
        )
            .call()
            ?.let { commitMapper.toDomain(it) }
    }
}