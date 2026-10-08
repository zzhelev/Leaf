package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.DeleteBranchGitAction
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.isRejected
import dev.app.leaf.domain.models.statusMessage
import org.eclipse.jgit.transport.RefSpec
import javax.inject.Inject

class DeleteRemoteBranchGitAction @Inject constructor(
    private val handleTransportGitAction: HandleTransportGitAction,
    private val deleteBranchGitAction: DeleteBranchGitAction,
    private val jgit: JGit,
) : IDeleteRemoteBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, ref: Branch) = jgit.provide(repositoryPath) { git ->
        val branchSplit = ref.name.split("/").toMutableList()
        val remoteName = branchSplit[2] // Remote name
        repeat(3) {
            branchSplit.removeAt(0)
        }

        val branchName = "refs/heads/${branchSplit.joinToString("/")}"

        val refSpec = RefSpec()
            .setSource(null)
            .setDestination(branchName)

        handleTransportGitAction(repositoryPath) {
            val pushResults = git
                .push()
                .setTransportConfigCallback {
                    handleTransport(it)
                }
                .setRefSpecs(refSpec)
                .setRemote(remoteName)
                .call()

            val results = pushResults.map { pushResult ->
                pushResult.remoteUpdates.filter { remoteRefUpdate ->
                    remoteRefUpdate.status.isRejected
                }
            }.flatten()

            if (results.isNotEmpty()) {
                val error = StringBuilder()

                results.forEach { result ->
                    error.append(result.statusMessage)
                    error.append("\n")
                }

                throw Exception(error.toString())
            }
        }

        // Like git branch -d -r, which skips the merge check for remote-tracking branches
        deleteBranchGitAction(repositoryPath, ref, force = true).bind() /// TODO Handle error?

    }
}