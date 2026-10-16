package dev.app.leaf.data.git.stash

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.author.toPersonIdent
import dev.app.leaf.domain.errors.StashChangesError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IStashChangesGitAction
import javax.inject.Inject

class StashChangesGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitIdentities: GitIdentities,
) : IStashChangesGitAction {
    override suspend operator fun invoke(repositoryPath: String, message: String?) = jgit.provide(repositoryPath) { git ->
        val commit = git
            .stashCreate()
            .setIncludeUntracked(true)
            .apply {
                if (message != null)
                    // Fork-only: as typed, though JGit formats it as a MessageFormat pattern
                    setWorkingDirectoryMessage(messageFormatText(message))
            }
            // Fork-only: git's committer, which includeIf may set
            .apply { gitIdentities.of(git.repository.directory)?.committer?.let { setPerson(it.toPersonIdent()) } }
            .call()


        if (commit == null) {
            raiseError(StashChangesError.NoDataToStash)
        }
    }
}