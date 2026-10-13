package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ISkipRebaseGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import javax.inject.Inject

class SkipRebaseGitAction @Inject constructor(
    private val jgit: JGit,
) : ISkipRebaseGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        val result = git.rebase()
            .setOperation(RebaseCommand.Operation.SKIP)
            .call()

        // Fork-only: tells a rebase that stopped at the next commit's conflicts from one that went on to its end
        result.status == RebaseResult.Status.STOPPED
    }
}