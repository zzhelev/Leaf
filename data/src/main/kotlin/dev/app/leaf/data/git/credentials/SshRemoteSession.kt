package dev.app.leaf.data.git.credentials

import dev.app.leaf.HostKeyState
import dev.app.leaf.Session
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.credentials.SshProcess
import dev.app.leaf.domain.exceptions.SshException
import dev.app.leaf.domain.extensions.throwIfSshMessage
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.URIish
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException


private const val NOT_EXPLICIT_PORT = -1

class SshRemoteSession(
    private val credentialsStateManager: CredentialsStateManager,
    /** Replaces the user's known_hosts file, for tests. */
    private val knownHostsFile: String?,
) : RemoteSession {
    @Inject
    constructor(credentialsStateManager: CredentialsStateManager) : this(credentialsStateManager, null)

    private lateinit var session: Session
    private lateinit var process: SshProcess
    override fun exec(commandName: String, timeout: Int): Process {
        println("Running command $commandName")

        process = SshProcess()

        process.setup(session, commandName)

        return process
    }

    override fun disconnect() {
        process.closeChannel()
        session.disconnect()
        session.close()
    }

    fun setup(uri: URIish, sshCredentialsProvider: CredentialsProvider) {
        val session = Session()
            ?: throw SshException("Could not obtain the session, this is likely a bug. Please file a report.")

        val port = if (uri.port == NOT_EXPLICIT_PORT) {
            null
        } else
            uri.port

        session.setup(uri.host, uri.user ?: "", port, knownHostsFile).throwIfSshMessage()

        try {
            verifyHostKey(session, uri)
        } catch (e: Exception) {
            session.disconnect()
            session.close()
            throw e
        }

        var result = session.publicKeyAuth("")

        if (result == 2) {//AuthStatus.DENIED) {
            val passwordCredentialItem = CredentialItem.Password()
            sshCredentialsProvider.get(uri, passwordCredentialItem)

            val password = passwordCredentialItem.value.joinToString("")

            result = session.publicKeyAuth(password)

            if (result != 1) {//AuthStatus.SUCCESS) {
                result = session.passwordAuth(password)
            }
        }

        if (result != 1) {//AuthStatus.SUCCESS)
            throw Exception("Something went wrong with authentication. Code $result")
        }

        this.session = session
    }

    /**
     * Checks the server's key against the known_hosts files before Leaf authenticates, as ssh does with its default
     * `StrictHostKeyChecking ask`: a known key connects, the user decides about an unknown one, which is then added to
     * known_hosts, and a key that differs from the known one is refused.
     *
     * Failures are [TransportException]s, which JGit passes on: it reports anything else from the session as "remote
     * hung up unexpectedly".
     */
    private fun verifyHostKey(session: Session, uri: URIish) {
        val check = session.checkHostKey()
        val host = if (uri.port == NOT_EXPLICIT_PORT) uri.host else "[${uri.host}]:${uri.port}"

        when (check.state) {
            HostKeyState.KNOWN -> {}
            HostKeyState.UNKNOWN -> {
                try {
                    runBlocking { credentialsStateManager.requestSshHostKeyTrust(host, check.fingerprint) }
                } catch (e: CancellationException) {
                    throw TransportException(
                        uri,
                        "Host key verification failed: the host key of $host wasn't trusted.",
                    )
                }

                val error = session.acceptHostKey()
                if (error.isNotEmpty()) {
                    throw TransportException(uri, error)
                }
            }

            HostKeyState.CHANGED -> throw TransportException(
                uri,
                "The host key of $host has changed, so Leaf didn't connect. Someone may be intercepting the " +
                    "connection, or the server's key may have been replaced. Its key is now ${check.fingerprint}. " +
                    "If you know that the key changed, remove the old one from your known_hosts file (for example " +
                    "with ssh-keygen -R), then try again."
            )

            HostKeyState.OTHER_TYPE -> throw TransportException(
                uri,
                "$host sent a different type of host key than the one in your known_hosts file, so Leaf didn't " +
                    "connect. Someone may be intercepting the connection. Its key is ${check.fingerprint}. If you " +
                    "know that the key changed, remove the old one from your known_hosts file (for example with " +
                    "ssh-keygen -R), then try again."
            )

            HostKeyState.FAILED -> throw TransportException(
                uri,
                "Could not check the host key of $host: ${check.error}",
            )
        }
    }
}
