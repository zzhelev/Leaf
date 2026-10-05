package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.interfaces.IGetCommitFromHashGitAction
import org.eclipse.jgit.lib.ObjectId
import javax.inject.Inject

class GetCommitFromHashGitAction @Inject constructor(
    private val commitMapper: JGitCommitMapper,
    private val jgit: JGit,
) : IGetCommitFromHashGitAction {
    override suspend operator fun invoke(repositoryPath: String, commitHash: String) =
        jgit.provide(repositoryPath) { git ->
            git
                .repository
                .parseCommit(ObjectId.fromString(commitHash))
                ?.let { commit -> commitMapper.toDomain(commit) }
        }
}