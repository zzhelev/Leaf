package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.network.isSslVerify
import dev.app.leaf.domain.interfaces.IDownloadLfsObjectGitAction
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.network.NetworkConstants
import dev.app.leaf.domain.repositories.LfsRepository
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

class DownloadLfsObjectGitAction @Inject constructor(
    private val lfsRepository: LfsRepository,
    private val provideLfsCredentialsGitAction: ProvideLfsCredentialsGitAction,
) : IDownloadLfsObjectGitAction {
    override suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        lfsObject: LfsObject,
        oid: AnyLongObjectId,
    ) {
        val lfs = Lfs(repository)
        val downloadUrl = lfsObject.actions?.download?.href ?: return
        val headers = lfsObject.actions?.download?.header.orEmpty()
        val sslVerify = repository.config.isSslVerify(downloadUrl)

        if (headers.containsKey(NetworkConstants.AUTH_HEADER)) {
            lfsRepository.downloadObject(
                downloadUrl = downloadUrl,
                outPath = lfs.getMediaFile(oid),
                headers = headers,
                username = null,
                password = null,
                sslVerify = sslVerify,
            )
        } else {
            provideLfsCredentialsGitAction(repository, lfsServer) { user, password ->
                lfsRepository.downloadObject(
                    downloadUrl = downloadUrl,
                    outPath = lfs.getMediaFile(oid),
                    headers = headers,
                    username = user,
                    password = password,
                    sslVerify = sslVerify,
                )
            }
        }
    }
}