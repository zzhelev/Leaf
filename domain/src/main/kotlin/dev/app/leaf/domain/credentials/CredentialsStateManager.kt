package dev.app.leaf.domain.credentials

import androidx.compose.runtime.Immutable
import dev.app.leaf.domain.models.CredentialsType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

// TODO Being a singleton, we may have problems if multiple tabs request credentials at the same time
@Singleton
class CredentialsStateManager @Inject constructor() {
    private val mutex = Mutex()
    val credentialsState: StateFlow<CredentialsState>
        field = MutableStateFlow<CredentialsState>(CredentialsState.None)

    /**
     * Asks the user for the credentials of an HTTPS remote, or only for the part that git doesn't know yet, as git
     * does, and answers with what git knew in place of what the dialog sent. At most one is known:
     * - the [user] name, for the URL, from `credential.username` or from a credential helper: the user is asked only
     *   for the password;
     * - the [password] that a credential helper gave without a user name: the user is asked only for the user name.
     *   It stays out of [credentialsState].
     */
    suspend fun requestHttpCredentials(user: String?, password: String?): CredentialsAccepted.HttpCredentialsAccepted {
        val accepted = requestAwaitingCredentials<CredentialsAccepted.HttpCredentialsAccepted>(
            CredentialsRequest.HttpCredentialsRequest(user, askPassword = password == null)
        )

        return CredentialsAccepted.HttpCredentialsAccepted(user ?: accepted.user, password ?: accepted.password)
    }

    suspend fun requestSshCredentials(isRetry: Boolean, password: String?): CredentialsAccepted.SshCredentialsAccepted {
        return requestAwaitingCredentials(CredentialsRequest.SshCredentialsRequest(isRetry, password.orEmpty()))
    }

    /**
     * Asks whether to trust the SSH server [host], whose key isn't in known_hosts, by the key's [fingerprint]. Returns
     * once the user trusts it, and throws a [CancellationException] if they don't, like the other requests.
     */
    suspend fun requestSshHostKeyTrust(host: String, fingerprint: String) {
        requestAwaitingCredentials<CredentialsAccepted.SshHostKeyTrusted>(
            CredentialsRequest.SshHostKeyRequest(host, fingerprint)
        )
    }

    /** Asks the user for the credentials of an LFS server, like [requestHttpCredentials]. */
    suspend fun requestLfsCredentials(user: String?, password: String?): CredentialsAccepted.LfsCredentialsAccepted {
        val accepted = requestAwaitingCredentials<CredentialsAccepted.LfsCredentialsAccepted>(
            CredentialsRequest.LfsCredentialsRequest(user, askPassword = password == null)
        )

        return CredentialsAccepted.LfsCredentialsAccepted(user ?: accepted.user, password ?: accepted.password)
    }

    fun credentialsDenied() {
        credentialsState.value = CredentialsState.CredentialsDenied
    }

    fun httpCredentialsAccepted(user: String, password: String) {
        credentialsState.value = CredentialsAccepted.HttpCredentialsAccepted(user, password)
    }

    fun sshCredentialsAccepted(password: String) {
        credentialsState.value = CredentialsAccepted.SshCredentialsAccepted(password)
    }

    fun lfsCredentialsAccepted(user: String, password: String) {
        credentialsState.value = CredentialsAccepted.LfsCredentialsAccepted(user, password)
    }

    fun sshHostKeyTrusted() {
        credentialsState.value = CredentialsAccepted.SshHostKeyTrusted
    }

    private suspend inline fun <reified T : CredentialsAccepted> requestAwaitingCredentials(credentialsRequest: CredentialsRequest): T {
        mutex.withLock {
            assert(this.credentialsState.value is CredentialsState.None)

            credentialsState.value = credentialsRequest

            val credentialsResult = this.credentialsState
                .first { it !is CredentialsRequest }

            credentialsState.value = CredentialsState.None

            return when (credentialsResult) {
                is T -> credentialsResult
                is CredentialsState.CredentialsDenied -> throw CancellationException("Credentials denied")
                else -> throw IllegalStateException("Unexpected credentials result")
            }
        }
    }
}

sealed interface CredentialsState {
    data object None : CredentialsState
    data object CredentialsDenied : CredentialsState
}

sealed interface CredentialsAccepted : CredentialsState {
    data class SshCredentialsAccepted(val password: String) : CredentialsAccepted
    data object SshHostKeyTrusted : CredentialsAccepted
    data class HttpCredentialsAccepted(val user: String, val password: String) : CredentialsAccepted
    data class LfsCredentialsAccepted(val user: String, val password: String) : CredentialsAccepted {
        companion object {
            fun fromCachedCredentials(credentials: CredentialsType.HttpCredentials): LfsCredentialsAccepted {
                return LfsCredentialsAccepted(credentials.user, credentials.password)
            }
        }
    }
}

sealed interface CredentialsRequest : CredentialsState {
    @Immutable
    data class SshCredentialsRequest(val isRetry: Boolean, val password: String) : CredentialsRequest

    /** An SSH server whose key isn't in known_hosts: [fingerprint] is the key's, as ssh shows it ("SHA256:..."). */
    @Immutable
    data class SshHostKeyRequest(val host: String, val fingerprint: String) : CredentialsRequest

    /**
     * [user] is the user name that git knows for the URL, if any: then only the password is asked for. Without
     * [askPassword], a credential helper gave the password but no user name, so only the user name is asked for.
     */
    @Immutable
    data class HttpCredentialsRequest(val user: String?, val askPassword: Boolean) : CredentialsRequest

    /** Like [HttpCredentialsRequest], for an LFS server. */
    @Immutable
    data class LfsCredentialsRequest(val user: String?, val askPassword: Boolean) : CredentialsRequest
}

