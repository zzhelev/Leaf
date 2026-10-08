package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.network.isSslVerify
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IVerifyUploadLfsObjectGitAction
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.network.NetworkConstants
import dev.app.leaf.domain.repositories.LfsRepository
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject


class VerifyUploadLfsObjectGitAction @Inject constructor(
    private val lfsRepository: LfsRepository,
    private val provideLfsCredentialsGitAction: ProvideLfsCredentialsGitAction,
) : IVerifyUploadLfsObjectGitAction {
    override suspend operator fun invoke(
        repository: Repository,
        lfsServer: LfsServer,
        lfsObject: LfsObject,
        oid: AnyLongObjectId,
    ): Either<Unit, LfsError> {
        val verifyUrl = lfsObject.actions?.verify?.href

        if (verifyUrl != null) {
            val verifyHeaders = lfsObject.actions?.verify?.header.orEmpty()
            val sslVerify = repository.config.isSslVerify(verifyUrl)
            return if (verifyHeaders.containsKey(NetworkConstants.AUTH_HEADER)) {
                lfsRepository.verify(
                    verifyUrl,
                    oid.name(),
                    lfsObject.size,
                    verifyHeaders,
                    null,
                    null,
                    sslVerify,
                )
            } else {
                provideLfsCredentialsGitAction(repository, lfsServer) { user, password ->
                    lfsRepository.verify(
                        verifyUrl,
                        oid.name(),
                        lfsObject.size,
                        verifyHeaders,
                        user,
                        password,
                        sslVerify,
                    )
                }
            }
        }

        return Either.Ok(Unit)
    }
}