package dev.app.leaf.data.git.repository

import dev.app.leaf.data.extensions.isMerging
import dev.app.leaf.data.extensions.isReverting
import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IPersistCommitMessageGitAction
import org.eclipse.jgit.lib.RepositoryState
import javax.inject.Inject

class PersistCommitMessageGitAction @Inject constructor(
    private val jgit: JGit,
) : IPersistCommitMessageGitAction {
    override suspend operator fun invoke(repositoryPath: String, message: String?) =
        jgit.provide(repositoryPath) { git ->
            val state = git.repository.repositoryState
            if (state.isMerging || state.isRebasing || state.isReverting) {
                git.repository.writeMergeCommitMsg(message)
            } else if (state == RepositoryState.SAFE) {
                git.repository.writeCommitEditMsg(message)
            }
        }
}
