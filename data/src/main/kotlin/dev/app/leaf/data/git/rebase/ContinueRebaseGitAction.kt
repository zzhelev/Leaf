package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IContinueRebaseGitAction
import org.eclipse.jgit.api.RebaseCommand
import javax.inject.Inject

class ContinueRebaseGitAction @Inject constructor(
    private val jgit: JGit,
) : IContinueRebaseGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        git.rebase()
            .setOperation(RebaseCommand.Operation.CONTINUE)
            .call()

        // TODO Throw error if call result is not continue?
        Unit
    }
}