package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IContinueRebaseGitAction
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import javax.inject.Inject

class ContinueRebaseGitAction @Inject constructor(
    private val jgit: JGit,
) : IContinueRebaseGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        val result = git.rebase()
            .setOperation(RebaseCommand.Operation.CONTINUE)
            .call()

        // Fork-only: tells a rebase that stopped at the next commit's conflicts from one that went on to its end
        result.status == RebaseResult.Status.STOPPED
    }
}