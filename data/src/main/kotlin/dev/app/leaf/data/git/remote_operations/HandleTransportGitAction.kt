package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.credentials.CredentialsCache
import dev.app.leaf.data.git.credentials.CredentialsHandler
import dev.app.leaf.data.git.credentials.HttpCredentialsFactory
import dev.app.leaf.data.git.credentials.reportAcceptedCredentials
import dev.app.leaf.domain.credentials.*
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import javax.inject.Inject

class HandleTransportGitAction @Inject constructor(
    private val noSshSessionFactory: NoSshSessionFactory,
    private val httpCredentialsProvider: HttpCredentialsFactory,
    private val jgit: JGit,
) {
    suspend operator fun <R> invoke(repositoryPath: String?, block: suspend CredentialsHandler.() -> R) =
        jgit.provideOptional(repositoryPath) { git ->
            var cache: CredentialsCache? = null
            val credentialsHandler = object : CredentialsHandler {
                override fun handleTransport(transport: Transport?) {
                    cache = when (transport) {
                        is SshTransport -> {
                            // Leaf reaches SSH remotes only with the git CLI, so this fails before it connects
                            transport.sshSessionFactory = noSshSessionFactory

                            null
                        }

                        is HttpTransport -> {

                            val httpCredentials = httpCredentialsProvider.create(git)
                            transport.credentialsProvider = httpCredentials
                            // Like git, which stores credentials once a request succeeds with them
                            if (transport is TransportHttp) {
                                transport.reportAcceptedCredentials(httpCredentials::credentialsAccepted)
                            }
                            httpCredentials
                        }

                        else -> {
                            null
                        }
                    }
                }
            }

            val result = credentialsHandler.block()
            cache?.cacheCredentialsIfNeeded()

            result
        }
}