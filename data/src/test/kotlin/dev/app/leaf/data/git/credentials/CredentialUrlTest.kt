// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PORT_URL = "https://example.invalid:8443/team/project.git"

/** What Leaf tells credential helpers, and which `credential.<url>` settings it applies, compared with the git CLI. */
@DisabledOnOs(OS.WINDOWS)
class CredentialUrlTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `helpers get the protocol, host and path that git gives them, for get and store`() {
        val urls = listOf(
            "https://example.invalid:8443/team/project.git/",
            "https://example.invalid/team/project.git",
            "http://example.invalid:80/",
            "https://example.invalid:443",
            "https://[::1]:8443/project.git",
            "https://example.invalid/~user/project.git",
            "https://example.invalid//team/project.git//",
            // Git leaves alone what comes before the first colon, and %00
            "https://example.invalid/a%20b:c%20d/x%41//",
            "https://example.invalid/%2Fa%2F/%2F",
            "https://example.invalid/x%00y+z",
            "https://example.invalid/%E2%82%AC/",
            "https://example.invalid/project.git?service=x",
            // Refused by both
            "https://example.invalid/team%0Ahost=other.invalid/project.git",
        )

        assertAll(
            urls.flatMap { url ->
                listOf(true, false).flatMap { useHttpPath ->
                    listOf(
                        Executable {
                            assertEquals(
                                gitHelperInput(url, useHttpPath),
                                credentialHelperInput(URIish(url), useHttpPath),
                                "get for $url, useHttpPath=$useHttpPath",
                            )
                        },
                        Executable {
                            assertEquals(
                                gitHelperInput(url, useHttpPath, "user", "pass=word"),
                                credentialHelperInput(URIish(url), useHttpPath, HelperCredential("user", "pass=word")),
                                "store for $url, useHttpPath=$useHttpPath",
                            )
                        },
                    )
                }
            }
        )
    }

    @Test
    fun `values with a carriage return or a newline are not sent`() {
        val url = URIish("https://example.invalid/team%0Dproject.git")

        // Git sends what comes before the path, then fails
        assertNull(credentialHelperInput(url, useHttpPath = true))
        // Git sends it without the path
        assertEquals("protocol=https\nhost=example.invalid\n", credentialHelperInput(url, useHttpPath = false))
        assertEquals(gitHelperInput(url.toString(), useHttpPath = false), credentialHelperInput(url, false))

        val portUrl = URIish(PORT_URL)
        assertNull(credentialHelperInput(portUrl, false, HelperCredential("user", "pass\nhost=other.invalid")))
        assertNull(credentialHelperInput(portUrl, false, HelperCredential("user\r", "password")))
    }

    @Test
    fun `credential settings for a URL apply to the same remotes as in git`() {
        val cases = listOf(
            PORT_URL to "https://example.invalid",
            PORT_URL to "https://example.invalid:8443",
            PORT_URL to "https://EXAMPLE.Invalid:8443/",
            PORT_URL to "HTTPS://example.invalid:08443",
            PORT_URL to "https://example.invalid:0",
            PORT_URL to "https://*.invalid:8443",
            PORT_URL to "https://*:8443",
            PORT_URL to "https://example.invalid:8443/team",
            PORT_URL to "https://example.invalid:8443/team/",
            PORT_URL to "https://example.invalid:8443/te",
            PORT_URL to "https://example.invalid:8443/team/project.git/",
            PORT_URL to "https://example.invalid:8443/team/project.git/more",
            PORT_URL to "https://alice@example.invalid:8443",
            "https://alice@example.invalid:8443/team" to "https://alice@example.invalid:8443",
            "https://alice@example.invalid:8443/team" to "https://bob@example.invalid:8443",
            "https://alice@example.invalid:8443/team" to "https://example.invalid:8443",
            "https://example.invalid/team" to "https://example.invalid:443",
            "https://example.invalid:443/team" to "https://example.invalid",
            "http://example.invalid:80/team" to "http://example.invalid",
            "http://example.invalid/team" to "https://example.invalid",
            "https://example.invalid/team%20a/project.git" to "https://example.invalid/team a",
            "https://example.invalid/team%20a/project.git" to "https://example.invalid/team%20a",
            "https://[::1]:8443/project.git" to "https://[::1]:8443",
            "https://[::1]:8443/project.git" to "https://[::1]",
            // Partial URLs
            PORT_URL to "example.invalid:8443",
            PORT_URL to "example.invalid",
            PORT_URL to "https://",
            PORT_URL to "http://",
            PORT_URL to "example.invalid:8443/team/project.git",
            PORT_URL to "example.invalid:8443/team",
            "https://alice@example.invalid:8443/team" to "alice@example.invalid:8443",
        )

        assertAll(
            cases.map { (url, key) ->
                Executable {
                    val config = Config().apply { setString("credential", key, "helper", "leaf-test") }

                    assertEquals(
                        gitAppliesHelper(url, key),
                        credentialConfigSubsections(config, URIish(url)) == listOf(key),
                        "credential.$key for $url",
                    )
                }
            }
        )
    }

    @Test
    fun `the most specific URL comes first, then partial URLs`() {
        val keys = listOf(
            "example.invalid:8443",
            "https://*.invalid:8443",
            "https://example.invalid:8443",
            "https://example.invalid:8443/team",
            "https://alice@example.invalid:8443/team",
            "https://other.invalid",
        )
        val config = Config().apply { keys.forEach { setString("credential", it, "helper", "leaf-test") } }

        assertEquals(
            listOf(
                "https://alice@example.invalid:8443/team",
                "https://example.invalid:8443/team",
                "https://example.invalid:8443",
                "https://*.invalid:8443",
                "example.invalid:8443",
            ),
            credentialConfigSubsections(config, URIish("https://alice@example.invalid:8443/team/project.git")),
        )
    }

    @Test
    fun `config entries are read as git config --list prints them`() {
        val configFile = File(tempDir, "list.gitconfig")
        configFile.writeText(
            """
            |[credential]
            |	helper = "!f() { echo a=b; }; f"
            |	helper =
            |	helper
            |	username = "line\nbreak"
            |[credential "https://Example.invalid:8443/team"]
            |	useHttpPath = yes
            |""".trimMargin()
        )
        val process = ProcessBuilder("git", "config", "--file", configFile.absolutePath, "--list", "-z")
            .directory(tempDir)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        assertEquals(
            listOf(
                ConfigEntry("credential.helper", "!f() { echo a=b; }; f"),
                ConfigEntry("credential.helper", ""),
                ConfigEntry("credential.helper", null),
                ConfigEntry("credential.username", "line\nbreak"),
                ConfigEntry("credential.https://Example.invalid:8443/team.usehttppath", "yes"),
            ),
            parseConfigList(output),
        )
    }

    @Test
    fun `credential settings without a value are skipped, where git stops with an error`() {
        val entries = listOf(
            ConfigEntry("credential.helper", "first"),
            ConfigEntry("credential.helper", null),
            ConfigEntry("credential.username", null),
            ConfigEntry("credential.useHttpPath", null),
        )

        assertEquals(CredentialSettings(listOf("first"), false, null), credentialSettings(entries, URIish(PORT_URL)))
    }

    /**
     * What git writes to a credential helper for [url]: for `get` with `git credential fill`, or for `store` with
     * `git credential approve` when there's a [password]. Null if git doesn't run the helper.
     */
    private fun gitHelperInput(
        url: String,
        useHttpPath: Boolean,
        username: String? = null,
        password: String? = null,
    ): String? {
        val inputFile = File(tempDir, "helper.input").apply { delete() }

        runGit(
            "-c", "credential.helper=!f() { cat > '${inputFile.absolutePath}'; }; f",
            "-c", "credential.useHttpPath=$useHttpPath",
            "credential", if (password == null) "fill" else "approve",
            input = "url=$url\n" + if (password == null) "" else "username=$username\npassword=$password\n",
        )

        return inputFile.takeIf { it.exists() }?.readText()
    }

    /** Whether git runs the helper set as `credential.<key>.helper` when it needs credentials for [url]. */
    private fun gitAppliesHelper(url: String, key: String): Boolean {
        val marker = File(tempDir, "helper.ran").apply { delete() }

        runGit(
            "-c", "credential.$key.helper=!f() { cat > /dev/null; touch '${marker.absolutePath}'; }; f",
            "credential", "fill",
            input = "url=$url\n",
        )

        return marker.exists()
    }

    /**
     * Runs git with [args] and [input] outside any repository, ignoring the developer's git config and never
     * prompting. Git fails when it has no credentials to fill, which doesn't matter here.
     */
    private fun runGit(vararg args: String, input: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(tempDir)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = File(tempDir, "empty.gitconfig").apply { createNewFile() }.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment().remove("GIT_ASKPASS")
                environment().remove("SSH_ASKPASS")
            }
            .start()

        process.outputStream.bufferedWriter().use { it.write(input) }
        process.inputStream.readAllBytes()
        process.waitFor()
    }
}
