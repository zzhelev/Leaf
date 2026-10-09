package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICherryPickCommitGitAction
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.api.CherryPickResult.CherryPickStatus
import javax.inject.Inject

class CherryPickCommitGitAction @Inject constructor(
    private val jgit: JGit,
) : ICherryPickCommitGitAction {
    override suspend operator fun invoke(repositoryPath: String, commit: Commit) = jgit.provide(repositoryPath) { git ->
        val base =
            git.repository.resolve(commit.hash) ?: throw Exception("Commit ${commit.hash} not found")

        val result = git.cherryPick()
            .include(base)
            .call()

        // Like RevertCommitGitAction: JGit reports these in its result, not with an exception (fork-only)
        when (result.status) {
            CherryPickStatus.FAILED -> raiseError(
                GenericError(
                    "The cherry-pick didn't happen, as it would overwrite your changes to " +
                        "${result.failingPaths.orEmpty().keys.sorted().joinToString(", ")}. Commit or stash them, " +
                        "then cherry-pick again."
                )
            )

            CherryPickStatus.CONFLICTING -> raiseError(
                GenericError("Cherry-pick stopped with conflicts. Fix the conflicts and commit the desired changes.")
            )

            CherryPickStatus.OK, null -> {}
        }
    }
}
