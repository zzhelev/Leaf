package dev.app.leaf.lfs

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.lfs.GetLfsUrlSettingGitAction
import dev.app.leaf.data.git.remotes.GetRemotesGitAction
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.extensions.isHttpOrHttps
import dev.app.leaf.domain.interfaces.IGetCurrentBranchGitAction
import dev.app.leaf.domain.interfaces.IGetLfsUrlGitAction
import dev.app.leaf.domain.interfaces.IGetRemotesGitAction
import dev.app.leaf.domain.interfaces.IGetTrackingBranchGitAction
import dev.app.leaf.domain.lfs.LfsServer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

private const val TAG = "LfsRepository"

class GetLfsUrlGitAction @Inject constructor(
    private val getTrackingBranchGitAction: GetTrackingBranchGitAction,
    private val getCurrentBranchGitAction: GetCurrentBranchGitAction,
    private val getRemotesGitAction: GetRemotesGitAction,
    private val getLfsUrlSettingGitAction: GetLfsUrlSettingGitAction,
) : IGetLfsUrlGitAction {
    /**
     * The LFS server for the remote [remoteName], or for the current branch's remote: the one `lfs.url` sets, or
     * else the remote's `remote.<name>.lfsurl`, each in git's config or else in `.lfsconfig`, or else the one at the
     * remote's URL.
     */
    override suspend operator fun invoke(repository: Repository, remoteName: String?): LfsServer? {
        val git = Git(repository)
        // TODO This only gets the URL from considering happy path config
        // Git's config too, which git-lfs reads before .lfsconfig (fork-only)
        val lfsConfigUrl = getLfsUrlSettingGitAction.lfsUrl(repository)

        return getLfsServer(git, remoteName, lfsConfigUrl)
    }

    private suspend fun getLfsServer(git: Git, remoteName: String?, lfsConfigUrl: String?): LfsServer? {
        val repositoryPath = git.repository.directory.absolutePath
        // Without a remote, credential helpers are asked about the LFS server's own URL
        val withoutRemote = lfsConfigUrl?.let { LfsServer(it, remoteUrl = null) }

        val remotePath = if (remoteName != null) {
            remoteName
        } else {
            val currentBranch = getCurrentBranchGitAction(repositoryPath).okOrNull() // TODO Proper error handling?

            if (currentBranch == null) {
                if (withoutRemote == null) {
                    printError(TAG, "Current branch is null and couldn't obtain tracking branch remote.")
                }
                return withoutRemote
            }

            // The branch, not its full ref, which the config has no upstream for (fork-only)
            val trackingBranch =
                getTrackingBranchGitAction(repositoryPath, currentBranch).okOrNull() // TODO Proper error handling?
            val currentBranchTracedRemote = trackingBranch?.remote

            if (currentBranchTracedRemote != null) {
                Constants.R_REMOTES + currentBranchTracedRemote
            } else {
                val remotes = getRemotesGitAction(repositoryPath).okOrNull().orEmpty()

                return if (remotes.count() == 1) {
                    val remoteUrl = remotes[0].fetchUri
                    val remote = remoteUrl.removeSuffix("/")
                        .let {
                            if (it.endsWith(".git")) {
                                it
                            } else {
                                "$it.git"
                            }
                        }
                    // git-lfs asks an SSH remote itself where its LFS server is (git-lfs-authenticate)
                    val remoteLfsUrl = if (remoteUrl.isHttpOrHttps()) "$remote/info/lfs" else remoteUrl
                    // remote.<name>.lfsurl, which git-lfs reads after lfs.url (fork-only)
                    val configuredUrl =
                        lfsConfigUrl ?: getLfsUrlSettingGitAction.remoteLfsUrl(git.repository, remotes[0].name)
                    LfsServer(configuredUrl ?: remoteLfsUrl, remoteUrl)
                } else {
                    if (withoutRemote == null) {
                        printError(TAG, "Remote name is null and couldn't obtain tracking branch remote.")
                    }
                    withoutRemote
                }
            }
        }

        val remote = git
            .remoteList()
            .call()
            .firstOrNull { Constants.R_REMOTES + it.name == remotePath }
            ?: return withoutRemote
        val remoteUrl = remote.urIs.firstOrNull() ?: return withoutRemote

        val lfsUrl = lfsConfigUrl
            // remote.<name>.lfsurl, which git-lfs reads after lfs.url (fork-only)
            ?: getLfsUrlSettingGitAction.remoteLfsUrl(git.repository, remote.name)
            ?: when {
                remoteUrl.toString().isHttpOrHttps() -> "$remoteUrl/info/lfs"
                else -> remoteUrl.toString()
            }

        return LfsServer(lfsUrl, remoteUrl.toString())
    }
}
