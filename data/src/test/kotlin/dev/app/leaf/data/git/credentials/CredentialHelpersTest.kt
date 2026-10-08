// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.credentials

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.exceptions.CommandExecutionFailed
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val REMOTE_URL = "https://example.invalid/team/project.git"

/** Which credential helpers Leaf runs, in which order and with what, compared with what the git CLI does. */
@DisabledOnOs(OS.WINDOWS)
class CredentialHelpersTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()

    /** Holds `leaf-helper`, which the helpers run, and what it records. */
    private val tools by lazy { File(tempDir, "tools").apply { mkdirs() } }

    /** The global config file, which stands for `~/.gitconfig`. */
    private val globalConfig by lazy { File(tempDir, "global.gitconfig").apply { createNewFile() } }

    /** What the login shell adds: its PATH has [tools], and git reads [globalConfig] and no system config. */
    private val shellVariables by lazy {
        mapOf(
            "PATH" to "${tools.absolutePath}:${System.getenv("PATH")}",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to globalConfig.absolutePath,
            "HOME" to tempDir.absolutePath,
            "XDG_CONFIG_HOME" to File(tempDir, "xdg-config").absolutePath,
        )
    }

    private lateinit var git: Git

    private val repository: Repository get() = git.repository

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        git = Git.init().setDirectory(File(tempDir, "repository")).call()

        // Each helper is `!leaf-helper <name>`. It records its input and that it ran, and answers `get` with
        // `<name>.answer`, if there is one.
        File(tools, "leaf-helper").writeExecutable(
            """
            |#!/bin/sh
            |dir="${'$'}(dirname "${'$'}0")"
            |cat > "${'$'}dir/${'$'}1.${'$'}2.input"
            |echo "${'$'}1 ${'$'}2" >> "${'$'}dir/runs"
            |[ "${'$'}2" = get ] && [ -f "${'$'}dir/${'$'}1.answer" ] && cat "${'$'}dir/${'$'}1.answer"
            |exit 0
            |""".trimMargin()
        )
    }

    @AfterEach
    fun tearDown() {
        git.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the helpers, their order, the user name and useHttpPath are the ones git uses`() {
        val included = File(tempDir, "included.gitconfig")
        included.writeText("[credential]\n\thelper = !leaf-helper included\n")
        globalConfig.writeText(
            """
            |[credential]
            |	helper = !leaf-helper global
            |[credential "https://example.invalid"]
            |	useHttpPath = true
            |	helper = !leaf-helper global-example
            |[credential]
            |	username = global-user
            |[includeIf "gitdir:${repository.workTree.canonicalPath}/"]
            |	path = ${included.absolutePath}
            |""".trimMargin()
        )
        // After the global config, as git reads it
        File(repository.directory, "config").appendText(
            """
            |[credential "https://*.invalid"]
            |	helper = !leaf-helper local-wildcard
            |[credential]
            |	useHttpPath = false
            |[credential "https://other.invalid"]
            |	useHttpPath = yes
            |	helper =
            |	helper = !leaf-helper local-other
            |[credential "https://example.invalid:8443"]
            |	helper =
            |	helper = !leaf-helper local-port
            |	username = port-user
            |""".trimMargin()
        )

        // The helpers that git runs, as none of them answers
        val cases = listOf(
            Triple(repository, REMOTE_URL, listOf("global", "global-example", "included", "local-wildcard")),
            Triple(repository, "https://example.invalid:8443/team/project.git", listOf("local-port")),
            Triple(repository, "https://alice@other.invalid/team/project.git", listOf("local-other")),
            // Cloning: only the global config, without the file that includeIf names for the repository
            Triple(null, REMOTE_URL, listOf("global", "global-example")),
        )

        assertAll(
            cases.map { (repository, url, expectedHelpers) ->
                Executable {
                    val gitRun = runGitCredentialFill(repository, url)
                    val settings = find(repository, url)
                    val helpers = settings.helpers.map { it.removePrefix("!leaf-helper ") }

                    assertEquals(expectedHelpers, gitRun.helpers, "the helpers git runs for $url")
                    assertEquals(gitRun.helpers, helpers, "the helpers for $url")
                    assertEquals(
                        gitRun.firstInput,
                        credentialHelperInput(URIish(url), settings.useHttpPath, HelperCredential(settings.username)),
                        "what the first helper reads for $url",
                    )
                }
            }
        )
    }

    @Test
    fun `without git, JGit's config gives the general helpers, then those of the URLs from the least specific`() {
        // Git would run example and wildcard, and apply the last useHttpPath: JGit doesn't keep the order of the file
        File(repository.directory, "config").appendText(
            """
            |[credential]
            |	helper = !leaf-helper general
            |	username = general-user
            |[credential "https://example.invalid"]
            |	helper =
            |	helper = !leaf-helper example
            |	useHttpPath = true
            |[credential "https://*.invalid"]
            |	helper = !leaf-helper wildcard
            |	username = wildcard-user
            |[credential]
            |	useHttpPath = false
            |""".trimMargin()
        )

        val settings = find(repository, REMOTE_URL, gitCli = testGitCli(shellVariables, "/nonexistent/git"))

        assertEquals(CredentialSettings(listOf("!leaf-helper example"), true, "wildcard-user"), settings)
    }

    @Test
    fun `helpers are asked in turn until one completes the user name and the password, as git asks them`() {
        File(tools, "first.answer").writeText("username=first-user\n")
        File(tools, "second.answer").writeText("password=second=password\n")
        File(tools, "third.answer").writeText("username=third-user\npassword=third-password\n")
        globalConfig.writeText(
            "[credential]\n" + listOf("first", "second", "third").joinToString("") { "\thelper = !leaf-helper $it\n" }
        )

        val gitRun = runGitCredentialFill(repository, REMOTE_URL)
        val secondGitInput = File(tools, "second.get.input").readText()
        File(tools, "runs").delete()

        val answer = credentialHelpers().get(find(repository, REMOTE_URL), URIish(REMOTE_URL))

        assertEquals(HelperAnswer.Credentials("first-user", "second=password"), answer)
        assertEquals(gitRun.answer, answer)
        assertEquals(listOf("first", "second"), runs())
        assertEquals(gitRun.helpers, runs())
        assertEquals(secondGitInput, File(tools, "second.get.input").readText())
    }

    @Test
    fun `a helper can answer with the password alone when the URL has a user name`() {
        File(tools, "keychain.answer").writeText("password=keychain-password\n")
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper keychain\n\tusername = config-user\n")
        val url = "https://alice@example.invalid/team/project.git"

        val answer = credentialHelpers().get(find(repository, url), URIish(url))

        assertEquals(HelperAnswer.Credentials("alice", "keychain-password"), answer)
        assertEquals(
            "protocol=https\nhost=example.invalid\nusername=alice\n",
            File(tools, "keychain.get.input").readText(),
        )
        assertEquals(runGitCredentialFill(repository, url).answer, answer)
    }

    @Test
    fun `a refresh token and an expiry reach the next helpers, and every helper once approved, as git sends them`() {
        File(tools, "first.answer").writeText(
            "username=first-user\noauth_refresh_token=refresh\npassword_expiry_utc=4102444800\n"
        )
        File(tools, "second.answer").writeText("password=second-password\n")
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper first\n\thelper = !leaf-helper second\n")
        val gitRun = runGitCredentialFill(repository, REMOTE_URL)
        val secondGitInput = File(tools, "second.get.input").readText()
        val gitStores = runGitCredentialApprove(repository, gitRun.output)
        val helpers = credentialHelpers()
        val settings = find(repository, REMOTE_URL)

        val answer = helpers.get(settings, URIish(REMOTE_URL))
        helpers.approve(settings, URIish(REMOTE_URL), (answer as HelperAnswer.Credentials).credential)

        assertEquals(
            HelperAnswer.Credentials(HelperCredential("first-user", "second-password", "refresh", 4102444800)),
            answer,
        )
        assertEquals(gitRun.answer, answer)
        assertEquals(secondGitInput, File(tools, "second.get.input").readText())
        // Both helpers, the ones that gave the credentials too, get all of them
        val expectedStore = "protocol=https\nhost=example.invalid\nusername=first-user\npassword=second-password\n" +
                "oauth_refresh_token=refresh\npassword_expiry_utc=4102444800\n"
        assertEquals(mapOf("first" to expectedStore, "second" to expectedStore), gitStores)
        assertEquals(gitStores, storeInputs())
    }

    @Test
    fun `a password that has expired is dropped, and the next helpers are asked, as git does`() {
        File(tools, "first.answer").writeText(
            "username=first-user\npassword=old-password\noauth_refresh_token=refresh\npassword_expiry_utc=1000\n"
        )
        File(tools, "second.answer").writeText("password=new-password\n")
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper first\n\thelper = !leaf-helper second\n")
        val gitRun = runGitCredentialFill(repository, REMOTE_URL)
        val secondGitInput = File(tools, "second.get.input").readText()

        val answer = credentialHelpers().get(find(repository, REMOTE_URL), URIish(REMOTE_URL))

        assertEquals(HelperAnswer.Credentials(HelperCredential("first-user", "new-password", "refresh")), answer)
        assertEquals(gitRun.answer, answer)
        // The user name and the refresh token stay, without the password or its expiry
        assertEquals(
            "protocol=https\nhost=example.invalid\nusername=first-user\noauth_refresh_token=refresh\n",
            secondGitInput,
        )
        assertEquals(secondGitInput, File(tools, "second.get.input").readText())

        // With no other helper, only the user name and the refresh token are known
        File(tools, "second.answer").delete()
        assertEquals(
            HelperAnswer.NotStored(HelperCredential("first-user", oauthRefreshToken = "refresh")),
            credentialHelpers().get(find(repository, REMOTE_URL), URIish(REMOTE_URL)),
        )
    }

    @Test
    fun `credentials are not approved without a user name and a password, or once expired, as git doesn't`() {
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper first\n")
        val settings = find(repository, REMOTE_URL)
        val helpers = credentialHelpers()
        val uri = URIish(REMOTE_URL)

        val gitStores = runGitCredentialApprove(
            repository,
            "protocol=https\nhost=example.invalid\nusername=user\npassword=pass\npassword_expiry_utc=1000\n",
        )
        helpers.approve(settings, uri, HelperCredential("user", "pass", passwordExpiryUtc = 1000))
        helpers.approve(settings, uri, HelperCredential("user"))
        helpers.approve(settings, uri, HelperCredential(password = "pass"))

        assertEquals(emptyMap<String, String>(), gitStores)
        assertEquals(emptyList<String>(), runs())

        // A password that expires later is stored
        helpers.approve(settings, uri, HelperCredential("user", "pass", passwordExpiryUtc = 4102444800))
        assertEquals(listOf("first"), runs())
    }

    @Test
    fun `password_expiry_utc is read as git reads it`() {
        File(tools, "first.answer").writeText("username=user\npassword=pass\n")
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper first\n")
        val values = listOf("4102444800", " 4102444800", "+4102444800", "4102444800abc", "0", "abc", "", "4102444800.5")

        assertAll(
            values.map { value ->
                Executable {
                    File(tools, "first.answer").writeText("username=user\npassword=pass\npassword_expiry_utc=$value\n")
                    val gitExpiry = runGitCredentialFill(repository, REMOTE_URL).answer?.credential?.passwordExpiryUtc

                    assertEquals(gitExpiry, gitExpiry(value), "password_expiry_utc=$value")
                }
            }
        )
    }

    @Test
    fun `a helper that says quit stops the search`() {
        File(tools, "first.answer").writeText("username=first-user\nquit=1\n")
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper first\n\thelper = !leaf-helper second\n")

        val answer = credentialHelpers().get(find(repository, REMOTE_URL), URIish(REMOTE_URL))

        assertEquals(HelperAnswer.Failed, answer)
        assertEquals(listOf("first"), runs())
    }

    @Test
    fun `a helper that can't be started is skipped, as in git`() {
        File(tools, "second.answer").writeText("username=second-user\npassword=second-password\n")
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper broken\n\thelper = !leaf-helper second\n")
        val shellManager = object : IShellManager by ShellManager() {
            override fun runCommandProcess(
                command: List<String>,
                directory: File?,
                environment: Map<String, String>,
            ): Process {
                if (command.any { "broken" in it }) {
                    throw CommandExecutionFailed("No such file or directory", IOException())
                }

                return ShellManager().runCommandProcess(command, directory, environment)
            }
        }

        val answer = credentialHelpers(shellManager = shellManager)
            .get(find(repository, REMOTE_URL), URIish(REMOTE_URL))

        assertEquals(HelperAnswer.Credentials("second-user", "second-password"), answer)
    }

    @Test
    fun `credentials are stored with every helper, and erased from every helper`() {
        globalConfig.writeText("[credential]\n\thelper = !leaf-helper first\n\thelper = !leaf-helper second\n")
        val settings = find(repository, REMOTE_URL)
        val helpers = credentialHelpers()
        val expectedInput = "protocol=https\nhost=example.invalid\nusername=user\npassword=pass=word\n"

        helpers.approve(settings, URIish(REMOTE_URL), HelperCredential("user", "pass=word"))
        helpers.erase(settings, URIish(REMOTE_URL), HelperCredential("user", "pass=word"))

        val runs = File(tools, "runs").readLines()

        // Each waits for its helpers
        assertEquals(listOf("first store", "second store"), runs.take(2).sorted())
        assertEquals(listOf("first erase", "second erase"), runs.drop(2).sorted())
        listOf("first", "second").forEach { name ->
            assertEquals(expectedInput, File(tools, "$name.store.input").readText(), name)
            assertEquals(expectedInput, File(tools, "$name.erase.input").readText(), name)
        }
    }

    /** What [CredentialHelpers.find] gives for [url] in [repository], or without one. */
    private fun find(
        repository: Repository?,
        url: String,
        gitCli: GitCli = testGitCli(shellVariables),
    ): CredentialSettings = runBlocking { credentialHelpers(gitCli).find(repository, URIish(url)) }

    private fun credentialHelpers(
        gitCli: GitCli = testGitCli(shellVariables),
        shellManager: IShellManager = ShellManager(),
    ) =
        CredentialHelpers(
            shellManager = shellManager,
            gitCredentialsManagerProvider = NoCredentialsManager,
            loginShellEnvironment = LoginShellEnvironment { shellVariables },
            gitCli = gitCli,
        )

    /** The helpers that have run since the runs were last cleared, by name. */
    private fun runs(): List<String> = File(tools, "runs").takeIf { it.exists() }?.readLines().orEmpty()
        .map { it.substringBefore(" ") }

    /**
     * Runs `git credential fill` for [url] in [repository]'s working tree, or outside any repository, and returns the
     * helpers it ran, what the first one read, and the credentials git ended with. Git can't ask, so it fails when no
     * helper completes them.
     */
    private fun runGitCredentialFill(repository: Repository?, url: String): GitRun {
        File(tools, "runs").delete()
        tools.listFiles { file -> file.name.endsWith(".input") }?.forEach { it.delete() }
        val directory = repository?.workTree ?: File(tempDir, "no-repository").apply { mkdirs() }

        val process = ProcessBuilder("git", "credential", "fill")
            .directory(directory)
            .apply {
                environment().putAll(shellVariables)
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_CEILING_DIRECTORIES"] = tempDir.absolutePath
                environment().remove("GIT_ASKPASS")
                environment().remove("SSH_ASKPASS")
            }
            .start()

        process.outputStream.bufferedWriter().use { it.write("url=$url\n") }
        val output = process.inputStream.bufferedReader().readText()
        process.errorStream.readAllBytes()
        process.waitFor(30, TimeUnit.SECONDS)

        val helpers = runs()
        val values = output.lines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        val answer = values["password"]?.let { password ->
            HelperAnswer.Credentials(
                HelperCredential(
                    username = values.getValue("username"),
                    password = password,
                    oauthRefreshToken = values["oauth_refresh_token"],
                    passwordExpiryUtc = values["password_expiry_utc"]?.toLong(),
                )
            )
        }

        return GitRun(
            helpers = helpers,
            firstInput = helpers.firstOrNull()?.let { File(tools, "$it.get.input").readText() },
            answer = answer,
            output = output,
        )
    }

    /**
     * Runs `git credential approve` with [input], as git does once the server took the credentials, in [repository]'s
     * working tree, and returns what each helper was given to store, by name. It clears the runs and the stores
     * afterwards, for Leaf's turn.
     */
    private fun runGitCredentialApprove(repository: Repository, input: String): Map<String, String> {
        File(tools, "runs").delete()

        val process = ProcessBuilder("git", "credential", "approve")
            .directory(repository.workTree)
            .apply {
                environment().putAll(shellVariables)
                environment()["GIT_CEILING_DIRECTORIES"] = tempDir.absolutePath
            }
            .start()

        process.outputStream.bufferedWriter().use { it.write(input) }
        process.inputStream.readAllBytes()
        process.errorStream.readAllBytes()
        process.waitFor(30, TimeUnit.SECONDS)

        val stores = storeInputs()
        File(tools, "runs").delete()
        tools.listFiles { file -> file.name.endsWith(".store.input") }?.forEach { it.delete() }

        return stores
    }

    /** What each helper was given to store, by name. */
    private fun storeInputs(): Map<String, String> = tools.listFiles { file -> file.name.endsWith(".store.input") }
        .orEmpty()
        .associate { it.name.removeSuffix(".store.input") to it.readText() }

    private class GitRun(
        val helpers: List<String>,
        val firstInput: String?,
        val answer: HelperAnswer.Credentials?,
        val output: String,
    )

    private object NoCredentialsManager : IGitCredentialsManagerProvider {
        override fun loadPath(): String? = null
    }
}
