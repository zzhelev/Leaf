package dev.app.leaf.data.git.stash

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.author.toPersonIdent
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.StashChangesError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IStashChangesGitAction
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

class StashChangesGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitIdentities: GitIdentities,
    private val sparseCheckout: SparseCheckoutGit,
) : IStashChangesGitAction {
    override suspend operator fun invoke(repositoryPath: String, message: String?) = jgit.provide(repositoryPath) { git ->
        // Fork-only: JGit's stash resets the working tree with a hard reset, which writes the files that a sparse
        // checkout leaves out. git's message for a typed one is "On <branch>: <message>".
        if (sparseCheckout.applies(git.repository)) {
            val stashBefore = stashTip(git.repository)
            val arguments = listOfNotNull("stash", "push", "--include-untracked", message?.let { "--message=$it" })

            sparseCheckout.run(git.repository, *arguments.toTypedArray()).bind()

            // git says "No local changes to save", and exits with 0
            if (stashTip(git.repository) == stashBefore) {
                raiseError(StashChangesError.NoDataToStash)
            }

            return@provide
        }

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

    /** The commit that the stash list starts with, or null when there's none, or git can't tell. */
    private suspend fun stashTip(repository: Repository): String? =
        (sparseCheckout.read(repository, "rev-parse", "--quiet", "--verify", "refs/stash") as? Either.Ok)?.value?.trim()
}
