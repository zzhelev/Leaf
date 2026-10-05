package dev.app.leaf.data.git.stash

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.StashChangesError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IStashChangesGitAction
import javax.inject.Inject

class StashChangesGitAction @Inject constructor(
    private val jgit: JGit,
) : IStashChangesGitAction {
    override suspend operator fun invoke(repositoryPath: String, message: String?) = jgit.provide(repositoryPath) { git ->
        val commit = git
            .stashCreate()
            .setIncludeUntracked(true)
            .apply {
                if (message != null)
                    setWorkingDirectoryMessage(message)
            }
            .call()


        if (commit == null) {
            raiseError(StashChangesError.NoDataToStash)
        }
    }
}