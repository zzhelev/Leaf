package dev.app.leaf.data.git.lfs

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.interfaces.IVerifyUploadLfsObjectGitAction
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.network.NetworkConstants
import dev.app.leaf.domain.repositories.LfsRepository
import org.eclipse.jgit.lfs.lib.AnyLongObjectId
import javax.inject.Inject


class VerifyUploadLfsObjectGitAction @Inject constructor(
    private val lfsRepository: LfsRepository,
    private val provideLfsCredentialsGitAction: ProvideLfsCredentialsGitAction,
) : IVerifyUploadLfsObjectGitAction {
    override suspend operator fun invoke(
        lfsServerUrl: String,
        lfsObject: LfsObject,
        oid: AnyLongObjectId,
    ): Either<Unit, LfsError> {
        val verifyUrl = lfsObject.actions?.verify?.href

        if (verifyUrl != null) {
            val verifyHeaders = lfsObject.actions?.verify?.header.orEmpty()
            return if (verifyHeaders.containsKey(NetworkConstants.AUTH_HEADER)) {
                lfsRepository.verify(
                    verifyUrl,
                    oid.name(),
                    lfsObject.size,
                    verifyHeaders,
                    null,
                    null,
                )
            } else {
                provideLfsCredentialsGitAction(
                    url = lfsServerUrl,
                ) { user, password ->
                    lfsRepository.verify(
                        verifyUrl,
                        oid.name(),
                        lfsObject.size,
                        verifyHeaders,
                        user,
                        password,
                    )
                }
            }
        }

        return Either.Ok(Unit)
    }
}