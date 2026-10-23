package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.branches.DeleteBranchGitAction
import dev.app.leaf.domain.errors.RemoteBranchChangedError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDeleteRemoteBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.isRejected
import dev.app.leaf.domain.models.statusMessage
import org.eclipse.jgit.transport.RefLeaseSpec
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
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

        val changedOnRemote = handleTransportGitAction(repositoryPath) {
            val pushResults = git
                .push()
                .setTransportConfigCallback {
                    handleTransport(it)
                }
                .setRefSpecs(refSpec)
                // Fork-only: deleted only while the remote has the commit that Leaf showed, as with git
                .setRefLeaseSpecs(RefLeaseSpec(branchName, ref.hash))
                .setRemote(remoteName)
                .call()

            val results = pushResults.map { pushResult ->
                pushResult.remoteUpdates.filter { remoteRefUpdate ->
                    remoteRefUpdate.status.isRejected
                }
            }.flatten()

            if (results.any { it.status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED }) {
                // What the server has now, which deleting it anyway takes as its lease
                return@handleTransportGitAction pushResults.firstNotNullOfOrNull {
                    it.getAdvertisedRef(branchName)?.objectId?.name
                } ?: ""
            }

            if (results.isNotEmpty()) {
                val error = StringBuilder()

                results.forEach { result ->
                    error.append(result.statusMessage)
                    error.append("\n")
                }

                throw Exception(error.toString())
            }

            null
        }.bind() // A remote that refused keeps its branch, so the remote-tracking branch stays too (fork-only)

        if (changedOnRemote != null) {
            raiseError(
                RemoteBranchChangedError("$remoteName/${ref.simpleName}", ref.name, changedOnRemote.ifBlank { null }, ref.hash)
            )
        }

        // Like git branch -d -r, which skips the merge check for remote-tracking branches
        deleteBranchGitAction(repositoryPath, ref, force = true).bind() /// TODO Handle error?

    }
}