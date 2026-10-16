package dev.app.leaf.data.git.repository

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.interfaces.IPersistCommitMessageGitAction
import dev.app.leaf.domain.models.CommitMessageSource
import dev.app.leaf.domain.models.commitMessageSource
import javax.inject.Inject

class PersistCommitMessageGitAction @Inject constructor(
    private val jgit: JGit,
    private val repositoryStateMapper: RepositoryStateMapper,
) : IPersistCommitMessageGitAction {
    override suspend operator fun invoke(repositoryPath: String, message: String?) =
        jgit.provide(repositoryPath) { git ->
            // Fork-only: where the commit field reads it in this state. Upstream saved it in MERGE_MSG while merging,
            // rebasing or reverting, and nowhere while cherry-picking, while the field showed MERGE_MSG only while
            // merging.
            val state = repositoryStateMapper.toDomain(git.repository.repositoryState)

            when (commitMessageSource(state, squashMergeInProgress = false)) {
                CommitMessageSource.MERGE -> git.repository.writeMergeCommitMsg(message)
                CommitMessageSource.REBASE -> git.repository.writeRebaseEditMessage(message)
                // Leaf's own file, not git's COMMIT_EDITMSG
                CommitMessageSource.NORMAL -> git.repository.writeCommitDraft(message)
                // PersistSquashMessageUseCase writes SQUASH_MSG
                CommitMessageSource.SQUASH -> Unit
            }
        }
}
