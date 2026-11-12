// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsState
import dev.app.leaf.domain.credentials.CredentialsStateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val KEY = "/home/me/.ssh/id_ed25519"
private const val PASSPHRASE_PROMPT = "Enter passphrase for key '$KEY': "

/** How Leaf answers git's and ssh's prompts, and git's credential helper requests, with its dialogs and caches. */
class AskpassAnswersTest {
    private val credentialsStateManager = CredentialsStateManager()
    private val cache = CredentialsCacheRepository()

    /** Settings' "Cache credentials in memory", which a test may turn off while the "app" runs. */
    private var cachesCredentials = true

    private fun newCommand(passphraseKeyPath: String? = null) =
        AskpassAnswers(credentialsStateManager, cache, { cachesCredentials }, passphraseKeyPath)

    @Test
    fun `git's two HTTPS prompts take one dialog`(): Unit = runBlocking {
        val answers = newCommand()

        val (given, dialogs) = credentialsStateManager.answeringDialogs({ httpCredentialsAccepted("bob", "pw") }) {
            listOf(
                answers.answer(AskpassRequest.Prompt("Username for 'https://example.com': ")),
                answers.answer(AskpassRequest.Prompt("Password for 'https://bob@example.com': ")),
            )
        }

        assertEquals(listOf("bob", "pw"), given)
        assertEquals(listOf(CredentialsRequest.HttpCredentialsRequest(user = null, askPassword = true)), dialogs)
    }

    @Test
    fun `a password prompt for a known user asks only for the password`(): Unit = runBlocking {
        val (given, dialogs) = credentialsStateManager.answeringDialogs({ httpCredentialsAccepted("ignored", "pw") }) {
            newCommand().answer(AskpassRequest.Prompt("Password for 'https://bob@example.com': "))
        }

        assertEquals("pw", given)
        assertEquals(listOf(CredentialsRequest.HttpCredentialsRequest(user = "bob", askPassword = true)), dialogs)
    }

    @Test
    fun `a closed dialog refuses the prompt`(): Unit = runBlocking {
        val answers = newCommand()

        val (given, _) = credentialsStateManager.answeringDialogs({ credentialsDenied() }) {
            answers.answer(AskpassRequest.Prompt("Username for 'https://example.com': "))
        }

        assertNull(given)
        assertTrue(answers.refused)
    }

    @Test
    fun `the dialog of a cancelled command closes, and the next one is shown`(): Unit = runBlocking {
        val asking = launch { newCommand().answer(AskpassRequest.Prompt("Username for 'https://example.com': ")) }
        credentialsStateManager.credentialsState.first { it is CredentialsRequest }

        asking.cancel()
        asking.join()

        assertEquals(CredentialsState.None, credentialsStateManager.credentialsState.value)

        val (given, _) = credentialsStateManager.answeringDialogs({ httpCredentialsAccepted("bob", "pw") }) {
            newCommand().answer(AskpassRequest.Prompt("Username for 'https://example.com': "))
        }
        assertEquals("bob", given)
    }

    @Test
    fun `a trusted host key is answered with yes`(): Unit = runBlocking {
        val prompt = "The authenticity of host 'example.com (1.2.3.4)' can't be established.\n" +
            "ED25519 key fingerprint is SHA256:abc.\nAre you sure you want to continue connecting (yes/no/[fingerprint])? "

        val (given, dialogs) = credentialsStateManager.answeringDialogs({ sshHostKeyTrusted() }, { credentialsDenied() }) {
            listOf(newCommand().answer(AskpassRequest.Prompt(prompt)), newCommand().answer(AskpassRequest.Prompt(prompt)))
        }

        assertEquals(listOf("yes", null), given)
        assertEquals(CredentialsRequest.SshHostKeyRequest("example.com", "SHA256:abc"), dialogs.first())
    }

    @Test
    fun `a passphrase is kept once the command authenticated, and given without asking next time`(): Unit = runBlocking {
        val first = newCommand()

        val (given, dialogs) = credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("phrase") }) {
            first.answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT))
        }
        first.commit()

        assertEquals("phrase", given)
        assertEquals(listOf(CredentialsRequest.SshCredentialsRequest(isRetry = false, password = "")), dialogs)

        val (givenAgain, dialogsAgain) = credentialsStateManager.answeringDialogs {
            newCommand().answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT))
        }

        assertEquals("phrase", givenAgain)
        assertEquals(emptyList<CredentialsRequest>(), dialogsAgain)
    }

    @Test
    fun `a passphrase isn't kept when the command didn't authenticate`(): Unit = runBlocking {
        credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("wrong") }) {
            newCommand().answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT))
        }

        assertNull(cache.getCachedSshCredentials("ssh-key:$KEY"))
    }

    @Test
    fun `a kept passphrase that ssh asks for again is dropped, and the user is asked`(): Unit = runBlocking {
        cache.cacheSshCredentials("ssh-key:$KEY", "old")
        val answers = newCommand()

        val (given, dialogs) = credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("new") }) {
            listOf(
                answers.answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT)),
                answers.answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT)),
            )
        }

        assertEquals(listOf("old", "new"), given)
        assertEquals(listOf(CredentialsRequest.SshCredentialsRequest(isRetry = true, password = "")), dialogs)
        assertNull(cache.getCachedSshCredentials("ssh-key:$KEY"))

        answers.commit()
        assertEquals("new", cache.getCachedSshCredentials("ssh-key:$KEY")?.password)
    }

    @Test
    fun `ssh-keygen's passphrase prompt without a key is about the key it signs with, and kept for it`(): Unit =
        runBlocking {
            val signing = newCommand(passphraseKeyPath = KEY)

            val (given, dialogs) = credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("phrase") }) {
                signing.answer(AskpassRequest.Prompt("Enter passphrase: "))
            }
            signing.commit()

            assertEquals("phrase", given)
            assertEquals(listOf(CredentialsRequest.SshCredentialsRequest(isRetry = false, password = "")), dialogs)
            assertEquals("phrase", cache.getCachedSshCredentials("ssh-key:$KEY")?.password)
        }

    @Test
    fun `a passphrase prompt for an unknown key asks each time, and keeps nothing`(): Unit = runBlocking {
        val answers = newCommand()

        val (given, dialogs) = credentialsStateManager.answeringDialogs(
            { sshCredentialsAccepted("wrong") },
            { sshCredentialsAccepted("phrase") },
        ) {
            listOf(
                answers.answer(AskpassRequest.Prompt("Enter passphrase: ")),
                answers.answer(AskpassRequest.Prompt("Enter passphrase: ")),
            )
        }
        answers.commit()

        assertEquals(listOf("wrong", "phrase"), given)
        assertEquals(
            listOf(
                CredentialsRequest.SshCredentialsRequest(isRetry = false, password = ""),
                CredentialsRequest.SshCredentialsRequest(isRetry = true, password = ""),
            ),
            dialogs,
        )

        val (_, dialogsAgain) = credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("phrase") }) {
            newCommand().answer(AskpassRequest.Prompt("Enter passphrase: "))
        }

        assertEquals(1, dialogsAgain.size)
    }

    @Test
    fun `with caching off, a passphrase is asked for each command, and nothing is kept`(): Unit = runBlocking {
        cachesCredentials = false

        val (given, dialogs) = credentialsStateManager.answeringDialogs(
            { sshCredentialsAccepted("phrase") },
            { sshCredentialsAccepted("phrase") },
        ) {
            List(2) {
                val answers = newCommand()
                answers.answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT)).also { answers.commit() }
            }
        }

        assertEquals(listOf("phrase", "phrase"), given)
        assertEquals(List(2) { CredentialsRequest.SshCredentialsRequest(isRetry = false, password = "") }, dialogs)
        assertNull(cache.getCachedSshCredentials("ssh-key:$KEY"))
    }

    @Test
    fun `with caching off, ssh-keygen's passphrase isn't kept for the key it signs with`(): Unit = runBlocking {
        cachesCredentials = false
        val signing = newCommand(passphraseKeyPath = KEY)

        credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("phrase") }) {
            signing.answer(AskpassRequest.Prompt("Enter passphrase: "))
        }
        signing.commit()

        assertNull(cache.getCachedSshCredentials("ssh-key:$KEY"))
    }

    @Test
    fun `turned off while Leaf runs, a passphrase kept before isn't given, and the user is asked`(): Unit =
        runBlocking {
            cache.cacheSshCredentials("ssh-key:$KEY", "kept")
            cachesCredentials = false

            val (given, dialogs) = credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("typed") }) {
                newCommand().answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT))
            }

            assertEquals("typed", given)
            assertEquals(listOf(CredentialsRequest.SshCredentialsRequest(isRetry = false, password = "")), dialogs)
        }

    @Test
    fun `turned off before the command authenticated, the typed passphrase isn't kept`(): Unit = runBlocking {
        val answers = newCommand()

        credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("phrase") }) {
            answers.answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT))
        }
        cachesCredentials = false
        answers.commit()

        assertNull(cache.getCachedSshCredentials("ssh-key:$KEY"))
    }

    @Test
    fun `turned on again, a passphrase is kept from the next command on`(): Unit = runBlocking {
        cachesCredentials = false
        val first = newCommand()
        credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("phrase") }) {
            first.answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT))
        }
        first.commit()

        cachesCredentials = true
        val second = newCommand()
        credentialsStateManager.answeringDialogs({ sshCredentialsAccepted("phrase") }) {
            second.answer(AskpassRequest.Prompt(PASSPHRASE_PROMPT))
        }
        second.commit()

        assertEquals("phrase", cache.getCachedSshCredentials("ssh-key:$KEY")?.password)
    }

    @Test
    fun `other prompts and confirmations get generic dialogs`(): Unit = runBlocking {
        val answers = newCommand()

        val (given, dialogs) = credentialsStateManager.answeringDialogs({ promptAnswered("pin") }, { confirmed() }) {
            listOf(
                answers.answer(AskpassRequest.Prompt("Enter PIN for ED25519-SK key /k: ")),
                answers.answer(AskpassRequest.Confirm("Allow use of key /k?")),
            )
        }

        assertEquals(listOf("pin", ""), given)
        assertEquals(
            listOf(
                CredentialsRequest.PromptRequest("Enter PIN for ED25519-SK key /k: ", secret = true),
                CredentialsRequest.ConfirmRequest("Allow use of key /k?"),
            ),
            dialogs,
        )
    }

    @Test
    fun `the credential helper gives what git stored for the same URL and user only`(): Unit = runBlocking {
        val answers = newCommand()
        answers.answer(credential("store", "protocol=https\nhost=example.com\nusername=bob\npassword=p=w\n"))

        assertEquals("username=bob\npassword=p=w\n", answers.answer(credential("get", "protocol=https\nhost=example.com\n")))
        assertEquals("", answers.answer(credential("get", "protocol=https\nhost=example.com:8443\n")))
        assertEquals("", answers.answer(credential("get", "protocol=https\nhost=example.com\npath=team/repo.git\n")))
        assertEquals("", answers.answer(credential("get", "protocol=https\nhost=example.com\nusername=alice\n")))
    }

    @Test
    fun `erase removes the credentials only while they are the rejected ones`(): Unit = runBlocking {
        val answers = newCommand()
        answers.answer(credential("store", "protocol=https\nhost=example.com\nusername=bob\npassword=new\n"))

        answers.answer(credential("erase", "protocol=https\nhost=example.com\nusername=bob\npassword=old\n"))
        assertEquals("username=bob\npassword=new\n", answers.answer(credential("get", "protocol=https\nhost=example.com\n")))

        answers.answer(credential("erase", "protocol=https\nhost=example.com\nusername=bob\npassword=new\n"))
        assertEquals("", answers.answer(credential("get", "protocol=https\nhost=example.com\n")))
    }

    @Test
    fun `with caching off, the credential helper gives and stores nothing`(): Unit = runBlocking {
        val answers = newCommand()
        answers.answer(credential("store", "protocol=https\nhost=example.com\nusername=bob\npassword=kept\n"))

        // Turned off after git started with Leaf's helper
        cachesCredentials = false

        assertEquals("", answers.answer(credential("get", "protocol=https\nhost=example.com\n")))
        answers.answer(credential("store", "protocol=https\nhost=example.org\nusername=bob\npassword=new\n"))
        assertNull(cache.getCachedHttpCredentials("git-credential:https://example.org", isLfs = false))
    }

    private fun credential(operation: String, input: String) = AskpassRequest.Credential(operation, "$input\n")
}
