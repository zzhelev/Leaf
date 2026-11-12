package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.network.ProxySettings
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IUploadLfsObjectGitAction
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.network.NetworkConstants
import dev.app.leaf.domain.repositories.LfsRepository
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

class UploadLfsObjectGitAction @Inject constructor(
    private val lfsRepository: LfsRepository,
    private val provideLfsCredentialsGitAction: ProvideLfsCredentialsGitAction,
    private val proxySettings: ProxySettings,
) : IUploadLfsObjectGitAction {
    override suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        lfsObject: LfsObject,
        oid: AnyLongObjectId,
    ): Either<Unit, LfsError> {
        val uploadUrl = lfsObject.actions?.upload?.href ?: return Either.Ok(Unit)

        val store = repository.lfsStore()
        val mediaFile = store.find(oid) ?: store.mediaFile(oid)
        val uploadHeaders = lfsObject.actions?.upload?.header.orEmpty()
        val settings = when (val result = proxySettings.lfsRequestSettings(repository, uploadUrl)) {
            is Either.Ok -> result.value
            is Either.Err -> return result
        }

        return if (uploadHeaders.containsKey(NetworkConstants.AUTH_HEADER)) {
            lfsRepository.uploadObject(
                uploadUrl,
                oid.name(),
                mediaFile,
                lfsObject.size,
                uploadHeaders,
                null,
                null,
                settings,
            )
        } else {
            provideLfsCredentialsGitAction(repository, lfsServer) { user, password ->
                lfsRepository.uploadObject(
                    uploadUrl,
                    oid.name(),
                    mediaFile,
                    lfsObject.size,
                    uploadHeaders,
                    user,
                    password,
                    settings,
                )
            }
        }
    }
}