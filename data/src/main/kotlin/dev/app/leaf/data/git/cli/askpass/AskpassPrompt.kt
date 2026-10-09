// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

/** A prompt that git or ssh gave the askpass helper, recognized so that Leaf can show the right dialog. */
sealed interface AskpassPrompt {
    /**
     * git, for HTTPS: `Username for 'https://example.com': `. git-lfs writes `Username for "https://example.com"` when
     * it asks itself, which it does when no credential helper applies to the URL.
     */
    data class HttpUsername(val url: String) : AskpassPrompt

    /**
     * git, for HTTPS: `Password for 'https://bob@example.com': `, or git-lfs's `Password for "https://bob@..."`. [url]
     * is as git wrote it, user name included, and [user] is null when it has none.
     */
    data class HttpPassword(val url: String, val user: String?) : AskpassPrompt

    /** ssh, for a server whose host key isn't in known_hosts. It expects `yes`. */
    data class SshHostKey(val host: String, val fingerprint: String) : AskpassPrompt

    /**
     * ssh, for a key file protected with a passphrase: `Enter passphrase for key '/home/me/.ssh/id_ed25519': `.
     * ssh-keygen asks `Enter passphrase for "/home/me/.ssh/id_ed25519": ` since OpenSSH 10, and `Enter passphrase: `
     * before, without the key: then [keyPath] is null.
     */
    data class SshPassphrase(val keyPath: String?) : AskpassPrompt

    /** Anything else, such as a password for SSH password authentication or a security key's PIN. */
    data class Other(val text: String, val secret: Boolean) : AskpassPrompt
}

// git's prompts are in English, as Leaf runs it with LC_ALL=C. ssh doesn't translate its prompts.
private val HTTP_USERNAME = Regex("""^Username for (?:'(.+)': |"(.+)")$""")
private val HTTP_PASSWORD = Regex("""^Password for (?:'(.+)': |"(.+)")$""")
private val SSH_PASSPHRASE = Regex("""^Enter passphrase for key '(.+)': ?$""")
private val SSH_KEYGEN_PASSPHRASE = Regex("""^Enter passphrase(?: for "(.+)")?: ?$""")
private val SSH_HOST_KEY_HOST = Regex("""The authenticity of host '([^']+)' can't be established""")
// "ED25519 key fingerprint is: SHA256:..." since OpenSSH 10, "... is SHA256:...." before
private val SSH_HOST_KEY_FINGERPRINT = Regex("""key fingerprint is:? (\S+?)\.?$""", RegexOption.MULTILINE)
private const val SSH_HOST_KEY_QUESTION = "Are you sure you want to continue connecting (yes/no"

fun parseAskpassPrompt(text: String): AskpassPrompt {
    HTTP_USERNAME.matchEntire(text)?.let { return AskpassPrompt.HttpUsername(it.quotedUrl()) }

    HTTP_PASSWORD.matchEntire(text)?.let {
        val url = it.quotedUrl()
        return AskpassPrompt.HttpPassword(url, userOfDescribedUrl(url))
    }

    SSH_PASSPHRASE.matchEntire(text)?.let { return AskpassPrompt.SshPassphrase(it.groupValues[1]) }
    SSH_KEYGEN_PASSPHRASE.matchEntire(text)?.let {
        return AskpassPrompt.SshPassphrase(it.groupValues[1].ifEmpty { null })
    }

    if (text.contains(SSH_HOST_KEY_QUESTION)) {
        val host = SSH_HOST_KEY_HOST.find(text)?.groupValues?.get(1)
        val fingerprint = SSH_HOST_KEY_FINGERPRINT.find(text)?.groupValues?.get(1)

        if (host != null && fingerprint != null) {
            // ssh writes "host (address)", such as "github.com (140.82.121.4)"
            return AskpassPrompt.SshHostKey(host.substringBefore(" ("), fingerprint)
        }

        return AskpassPrompt.Other(text, secret = false)
    }

    return AskpassPrompt.Other(text, secret = !text.startsWith("Username"))
}

/** The URL in git's quotes or in git-lfs's. */
private fun MatchResult.quotedUrl() = groupValues[1].ifEmpty { groupValues[2] }

/**
 * The user name in a URL as git describes it in prompts (`credential_describe`): `protocol://user@host/path`. The user
 * name isn't encoded there, so it can contain `@` itself, but neither it nor the host contains `/`.
 */
private fun userOfDescribedUrl(url: String): String? {
    val authority = url.substringAfter("://").substringBefore('/')

    return authority.substringBeforeLast('@', missingDelimiterValue = "").ifEmpty { null }
}
