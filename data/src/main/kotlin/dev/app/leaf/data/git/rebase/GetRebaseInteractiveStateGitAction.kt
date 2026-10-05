package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.RebaseConstants
import dev.app.leaf.domain.interfaces.IGetRebaseInteractiveStateGitAction
import dev.app.leaf.domain.models.RebaseInteractiveState
import java.io.File
import javax.inject.Inject

class GetRebaseInteractiveStateGitAction @Inject constructor(
    private val getRebaseAmendCommitIdGitAction: GetRebaseAmendCommitIdGitAction,
    private val jgit: JGit,
) : IGetRebaseInteractiveStateGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        // TODO Delete this action
        TODO()
    }
}