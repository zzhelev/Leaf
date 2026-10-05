package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.credentials.SshCredentialsProvider
import dev.app.leaf.data.git.credentials.SshRemoteSession
import dev.app.leaf.domain.extensions.readUntilValue
import dev.app.leaf.domain.interfaces.IAuthenticateLfsServerWithSshGitAction
import dev.app.leaf.domain.lfs.LfsSshAuthenticateResult
import dev.app.leaf.domain.models.OperationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.eclipse.jgit.lfs.errors.LfsException
import org.eclipse.jgit.transport.URIish
import javax.inject.Inject
import javax.inject.Provider


class AuthenticateLfsServerWithSshGitAction @Inject constructor(
    private val sshRemoteSessionProvider: Provider<SshRemoteSession>,
    private val sshCredentialsProvider: Provider<SshCredentialsProvider>,
) : IAuthenticateLfsServerWithSshGitAction {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend operator fun invoke(
        lfsServerUrl: String,
        operationType: OperationType,
    ): LfsSshAuthenticateResult = withContext(Dispatchers.IO) {
        val operation = when (operationType) {
            OperationType.UPLOAD -> "upload"
            OperationType.DOWNLOAD -> "download"
        }

        val sshRemoteSession = sshRemoteSessionProvider.get()
        val uri = URIish(lfsServerUrl)

        sshRemoteSession.setup(uri, sshCredentialsProvider.get())
        val process = sshRemoteSession.exec("git-lfs-authenticate ${uri.path} $operation", 0 /*no timeout*/)

        val inputString = String(process.inputStream.readUntilValue(-1))
        val errorString = String(process.errorStream.readUntilValue(-1))

        if (errorString.isNotBlank()) {
            throw LfsException(errorString)
        } else if (inputString.isBlank()) {
            throw LfsException("SSH LFS Authentication failed, server returned invalid empty data.")
        }

        json.decodeFromString<LfsSshAuthenticateResult>(inputString)
    }
}