package dev.app.leaf.lfs

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.lfs.GetLfsUrlSettingGitAction
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.extensions.isHttpOrHttps
import dev.app.leaf.domain.interfaces.IGetLfsUrlGitAction
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.models.Remote
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

private const val TAG = "LfsRepository"

class GetLfsUrlGitAction @Inject constructor(
    private val getRemotesGitAction: GetRemotesGitAction,
    private val getLfsUrlSettingGitAction: GetLfsUrlSettingGitAction,
) : IGetLfsUrlGitAction {
    /**
     * The LFS server for [operation] with the remote [remoteName], or with the remote that git-lfs picks: the current
     * branch's upstream remote, `remote.lfsdefault`, the only remote, or `origin`. The server is the one `lfs.url`
     * sets (after `lfs.pushurl` for an upload), or else the remote's `remote.<name>.lfsurl` (after its
     * `remote.<name>.lfspushurl`), or else the one at the remote's URL (its push URL for an upload). A remote without
     * any of them gets `origin`'s server, as with git-lfs 3.8.
     */
    override suspend operator fun invoke(
        repository: Repository,
        remoteName: String?,
        operation: OperationType,
    ): LfsServer? {
        val repositoryPath = repository.directory.absolutePath
        // TODO Proper error handling?
        val remotes = getRemotesGitAction(repositoryPath).okOrNull().orEmpty()
        // The remote that git-lfs picks when none is given (fork-only)
        val remote = remoteName?.removePrefix(Constants.R_REMOTES) ?: defaultRemote(repository, remotes)
        val remoteUrl = remotes.firstOrNull { it.name == remote }?.url(operation)

        // Git's config too, which git-lfs reads before .lfsconfig (fork-only)
        val lfsUrl = getLfsUrlSettingGitAction.lfsUrl(repository, operation)
            ?: remoteLfsUrl(repository, remote, remoteUrl, operation)
            // git-lfs takes origin's server for a remote without one (fork-only)
            ?: Constants.DEFAULT_REMOTE_NAME.takeIf { it != remote }?.let { origin ->
                remoteLfsUrl(repository, origin, remotes.firstOrNull { it.name == origin }?.url(operation), operation)
            }

        if (lfsUrl == null) {
            printError(TAG, "No LFS server for the remote $remote.")
            return null
        }

        // Credential helpers are asked about the remote's URL, when it has one
        return LfsServer(lfsUrl, remoteUrl)
    }

    /**
     * The remote that git-lfs 3.8 picks: the current branch's upstream remote, then `remote.lfsdefault`, then the
     * only remote, then `origin` (fork-only).
     */
    private suspend fun defaultRemote(repository: Repository, remotes: List<Remote>): String {
        // A detached HEAD, as in a rebase, has no branch, even at a branch's commit
        val currentBranch = repository.fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }
        // The branch, not its full ref, which the config has no upstream for
        val upstreamRemote = currentBranch?.let {
            getLfsUrlSettingGitAction.branchRemote(repository, Repository.shortenRefName(it))
        }

        return upstreamRemote
            ?: getLfsUrlSettingGitAction.lfsDefaultRemote(repository)
            ?: remotes.singleOrNull()?.name
            ?: Constants.DEFAULT_REMOTE_NAME
    }

    /**
     * The remote's own server: `remote.<name>.lfsurl` (after `remote.<name>.lfspushurl` for an upload), which
     * git-lfs reads after `lfs.url`, or else the one at [remoteUrl] (fork-only).
     */
    private suspend fun remoteLfsUrl(
        repository: Repository,
        remote: String,
        remoteUrl: String?,
        operation: OperationType,
    ): String? = getLfsUrlSettingGitAction.remoteLfsUrl(repository, remote, operation) ?: remoteUrl?.let(::lfsUrlAt)

    /** The remote's URL for [operation]: its push URL for an upload, or else its URL. */
    private fun Remote.url(operation: OperationType): String? =
        (if (operation == OperationType.UPLOAD) pushUri else fetchUri).takeIf { it.isNotEmpty() }

    /**
     * The LFS server at a remote's URL, as git-lfs finds it: `<url>.git/info/lfs`, or `<url>/info/lfs` when the URL
     * ends with `.git` (fork-only). git-lfs asks an SSH remote itself where its LFS server is (git-lfs-authenticate).
     */
    private fun lfsUrlAt(remoteUrl: String): String {
        if (!remoteUrl.isHttpOrHttps()) {
            return remoteUrl
        }

        val url = remoteUrl.removeSuffix("/")

        return if (url.endsWith(".git")) "$url/info/lfs" else "$url.git/info/lfs"
    }
}
