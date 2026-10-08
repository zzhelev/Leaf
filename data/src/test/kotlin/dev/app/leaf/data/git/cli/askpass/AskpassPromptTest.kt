// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The prompts as git 2.54 and OpenSSH 10.3 (and older OpenSSH) wrote them to the askpass program. */
class AskpassPromptTest {
    @Test
    fun `git's user name prompt names the URL`() {
        assertEquals(
            AskpassPrompt.HttpUsername("https://example.com"),
            parseAskpassPrompt("Username for 'https://example.com': "),
        )
    }

    @Test
    fun `git's password prompt names the URL with the user`() {
        assertEquals(
            AskpassPrompt.HttpPassword("https://bob@example.com", "bob"),
            parseAskpassPrompt("Password for 'https://bob@example.com': "),
        )
    }

    @Test
    fun `a user name with an at sign is kept whole`() {
        assertEquals(
            AskpassPrompt.HttpPassword("https://bob@corp.example@example.com:8443/team/repo.git", "bob@corp.example"),
            parseAskpassPrompt("Password for 'https://bob@corp.example@example.com:8443/team/repo.git': "),
        )
    }

    @Test
    fun `a password prompt without a user has none`() {
        assertEquals(
            AskpassPrompt.HttpPassword("https://example.com/a@b", null),
            parseAskpassPrompt("Password for 'https://example.com/a@b': "),
        )
    }

    @Test
    fun `ssh's host key question gives the host and the fingerprint`() {
        val prompt = "The authenticity of host '[127.0.0.1]:22222 ([127.0.0.1]:22222)' can't be established.\n" +
            "ED25519 key fingerprint is: SHA256:Wn0Hx1Hk2y+0bq7nYxkWgDdN1Kl7Cw9i0oG3uU2Rr3E\n" +
            "This key is not known by any other names.\n" +
            "Are you sure you want to continue connecting (yes/no/[fingerprint])? "

        assertEquals(
            AskpassPrompt.SshHostKey("[127.0.0.1]:22222", "SHA256:Wn0Hx1Hk2y+0bq7nYxkWgDdN1Kl7Cw9i0oG3uU2Rr3E"),
            parseAskpassPrompt(prompt),
        )
    }

    @Test
    fun `the host key question of OpenSSH before 10 is understood too`() {
        val prompt = "The authenticity of host 'github.com (140.82.121.4)' can't be established.\n" +
            "ED25519 key fingerprint is SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU.\n" +
            "Are you sure you want to continue connecting (yes/no/[fingerprint])? "

        assertEquals(
            AskpassPrompt.SshHostKey("github.com", "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU"),
            parseAskpassPrompt(prompt),
        )
    }

    @Test
    fun `a host key question without a fingerprint is shown as it is`() {
        val prompt = "The authenticity of host 'example.com' can't be established.\n" +
            "Are you sure you want to continue connecting (yes/no/[fingerprint])? "

        assertEquals(AskpassPrompt.Other(prompt, secret = false), parseAskpassPrompt(prompt))
    }

    @Test
    fun `ssh's passphrase prompt names the key file`() {
        assertEquals(
            AskpassPrompt.SshPassphrase("/home/me/.ssh/id_ed25519"),
            parseAskpassPrompt("Enter passphrase for key '/home/me/.ssh/id_ed25519': "),
        )
    }

    @Test
    fun `other prompts are secret, unless they ask for a user name`() {
        assertEquals(
            AskpassPrompt.Other("git@example.com's password: ", secret = true),
            parseAskpassPrompt("git@example.com's password: "),
        )
        assertEquals(
            AskpassPrompt.Other("Username: ", secret = false),
            parseAskpassPrompt("Username: "),
        )
    }
}
