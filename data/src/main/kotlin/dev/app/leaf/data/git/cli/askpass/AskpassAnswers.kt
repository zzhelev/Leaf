// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.repositories.CredentialsRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

private const val SSH_KEY_CACHE_PREFIX = "ssh-key:"
private const val GIT_CREDENTIAL_CACHE_PREFIX = "git-credential:"

/**
 * Answers the askpass helper for one git command: prompts with Leaf's dialogs, and the credential helper requests with
 * Leaf's in-memory cache. One instance per command, as it keeps what the user typed between git's prompts.
 *
 * - HTTPS: git asks for the user name and the password one at a time. Leaf asks for both in one dialog when git asks
 *   for the user name, and gives the password when git asks for it next. When git already knows the user name, only
 *   the password is asked for. git itself sends what the server took to every credential helper, Leaf's cache last.
 * - SSH passphrases are kept per key file for the session, as Leaf does with JGit. A passphrase that ssh asks for again
 *   was wrong, so it's dropped, and the user is asked again. A typed passphrase is kept only once the command
 *   [authenticated][commit].
 * - The SSH host key question uses the same dialog as Leaf's own SSH implementation, and other prompts a generic one.
 */
class AskpassAnswers(
    private val credentialsStateManager: CredentialsStateManager,
    private val credentialsRepository: CredentialsRepository,
) {
    /** The password typed together with a user name, for git's next prompt, which names that user. */
    private var typedPassword: TypedPassword? = null

    /** The passphrase given last for each key file during this command. */
    private val passphrasesGiven = mutableMapOf<String, String>()
    private val passphrasesFromCache = mutableSetOf<String>()

    // git and ssh wait for each answer, but the programs they start (git-lfs) could ask at the same time
    private val mutex = Mutex()

    /** Whether the user closed a dialog, after which git and ssh stop. */
    @Volatile
    var refused = false
        private set

    suspend fun answer(request: AskpassRequest): String? = mutex.withLock {
        when (request) {
            is AskpassRequest.Prompt -> answerPrompt(parseAskpassPrompt(request.text))
            is AskpassRequest.Confirm -> refusedAsNull { credentialsStateManager.requestConfirmation(request.text) }
                ?.let { "" }

            is AskpassRequest.Credential -> answerCredentialHelper(request.operation, request.input)
        }
    }

    /** Keeps the passphrases that the user typed, once the command got past authentication with them. */
    suspend fun commit() = mutex.withLock {
        for ((keyPath, passphrase) in passphrasesGiven) {
            if (keyPath !in passphrasesFromCache) {
                credentialsRepository.cacheSshCredentials(SSH_KEY_CACHE_PREFIX + keyPath, passphrase)
            }
        }
    }

    private suspend fun answerPrompt(prompt: AskpassPrompt): String? = when (prompt) {
        is AskpassPrompt.HttpUsername -> refusedAsNull {
            val credentials = credentialsStateManager.requestHttpCredentials(user = null, password = null)
            typedPassword = TypedPassword(prompt.url.withUser(credentials.user), credentials.password)
            credentials.user
        }

        is AskpassPrompt.HttpPassword -> {
            val typed = typedPassword
            typedPassword = null

            if (typed != null && typed.url == prompt.url) {
                typed.password
            } else {
                refusedAsNull { credentialsStateManager.requestHttpCredentials(prompt.user, password = null).password }
            }
        }

        is AskpassPrompt.SshHostKey -> refusedAsNull {
            credentialsStateManager.requestSshHostKeyTrust(prompt.host, prompt.fingerprint)
            "yes"
        }

        is AskpassPrompt.SshPassphrase -> answerPassphrase(prompt.keyPath)

        is AskpassPrompt.Other -> refusedAsNull {
            credentialsStateManager.requestPromptAnswer(prompt.text, prompt.secret)
        }
    }

    private suspend fun answerPassphrase(keyPath: String): String? {
        val cacheKey = SSH_KEY_CACHE_PREFIX + keyPath
        val isRetry = keyPath in passphrasesGiven

        if (isRetry) {
            if (passphrasesFromCache.remove(keyPath)) {
                credentialsRepository.removeCachedSshCredentials(cacheKey)
            }
        } else {
            val cached = credentialsRepository.getCachedSshCredentials(cacheKey)

            if (cached != null) {
                passphrasesGiven[keyPath] = cached.password
                passphrasesFromCache.add(keyPath)

                return cached.password
            }
        }

        val passphrase = refusedAsNull {
            credentialsStateManager.requestSshCredentials(isRetry, password = null).password
        } ?: return null

        passphrasesGiven[keyPath] = passphrase

        return passphrase
    }

    /**
     * Leaf's in-memory cache as git's credential helper, which git adds last to the user's helpers. It answers `get`
     * like git's `cache` helper: with the credentials stored for the protocol, host and path (and user name, when git
     * sends one).
     */
    private suspend fun answerCredentialHelper(operation: String, input: String): String {
        val credential = input.lineSequence()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        val protocol = credential["protocol"] ?: return ""
        val host = credential["host"] ?: return ""
        val path = credential["path"]?.let { "/$it" }.orEmpty()
        val user = credential["username"]
        val password = credential["password"]

        val cacheKey = "$GIT_CREDENTIAL_CACHE_PREFIX$protocol://$host$path"
        val cached = credentialsRepository.getCachedHttpCredentials(cacheKey, isLfs = false)
            ?.takeIf { user == null || it.user == user }

        when (operation) {
            "get" -> if (cached != null) {
                return "username=${cached.user}\npassword=${cached.password}\n"
            }

            "store" -> if (user != null && password != null) {
                credentialsRepository.cacheHttpCredentials(cacheKey, user, password, isLfs = false)
            }

            "erase" -> if (cached != null && (password == null || cached.password == password)) {
                credentialsRepository.removeCachedHttpCredentials(cached)
            }
        }

        return ""
    }

    /**
     * Returns null when the user closes a dialog, which [CredentialsStateManager] reports with a
     * [CancellationException]. A real cancellation, of the command itself, is passed on.
     */
    private suspend fun <T> refusedAsNull(ask: suspend () -> T): T? = try {
        ask()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        refused = true
        null
    }

    private class TypedPassword(val url: String, val password: String)
}

/** Puts [user] in a URL as git describes it in prompts: `https://example.com` → `https://bob@example.com`. */
private fun String.withUser(user: String): String {
    val protocol = substringBefore("://", missingDelimiterValue = "")

    return if (protocol.isEmpty()) this else "$protocol://$user@${substringAfter("://")}"
}
