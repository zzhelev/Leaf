// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsServer
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val REMOTE_URL = "https://example.invalid/team/project.git"
private const val LFS_URL = "$REMOTE_URL/info/lfs"

/** The credentials that Leaf gives an LFS server over HTTPS: from the credential helper, Leaf's cache or the user. */
class ProvideLfsCredentialsGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val credentialsStateManager = CredentialsStateManager()
    private val credentialsCache = CredentialsCacheRepository()

    /** Holds the helper, on the login shell's PATH. */
    private val tools by lazy { File(tempDir, "tools").apply { mkdirs() } }

    /** What the login shell adds: its PATH includes [tools], and git ignores the developer's own config. */
    private val shellVariables by lazy {
        mapOf(
            "PATH" to "${tools.absolutePath}:${System.getenv("PATH")}",
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to File(tempDir, "empty.gitconfig").apply { createNewFile() }.absolutePath,
            "HOME" to tempDir.absolutePath,
        )
    }

    private lateinit var git: Git

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        git = Git.init().setDirectory(File(tempDir, "repository")).call()
    }

    @AfterEach
    fun restoreSystemReader() {
        git.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a server that needs no credentials gets none`() {
        setHelper("leaf-test")
        val server = FakeServer(accepts = null)

        val result = provideCredentials(server)

        assertEquals(Either.Ok("objects"), result)
        assertEquals(listOf<Answer?>(null), server.attempts)
        assertFalse(File(tools, "operations").exists())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `the helper gives the credentials, asked about the remote's URL as git-lfs asks`() {
        setHelper("leaf-test", useHttpPath = true)
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        val server = FakeServer(accepts = Answer("helper-user", "helper-password"))

        val result = provideCredentials(server)

        assertEquals(Either.Ok("objects"), result)
        assertEquals(listOf(null, Answer("helper-user", "helper-password")), server.attempts)
        assertEquals(listOf("get"), File(tools, "operations").readLines())
        // The remote's path, not the LFS server's .../info/lfs, so the helper finds what git stored
        assertEquals(
            "protocol=https\nhost=example.invalid\npath=team/project.git\n",
            File(tools, "get.input").readText(),
        )
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `rejected helper credentials are erased, and the ones the user types are stored once the server takes them`() {
        setHelper("leaf-test")
        createRecordingHelper(answer = Answer("helper-user", "old-password"))
        val server = FakeServer(accepts = Answer("helper-user", "new-password"))

        val result = provideCredentials(server, prompts = listOf(Answer("helper-user", "new-password")))

        assertEquals(Either.Ok("objects"), result)
        assertEquals(
            listOf(null, Answer("helper-user", "old-password"), Answer("helper-user", "new-password")),
            server.attempts,
        )
        awaitOperations(3)
        assertEquals(listOf("get", "erase", "store"), File(tools, "operations").readLines())
        assertEquals(
            "protocol=https\nhost=example.invalid\nusername=helper-user\npassword=old-password\n",
            File(tools, "erase.input").readText(),
        )
        assertEquals(
            "protocol=https\nhost=example.invalid\nusername=helper-user\npassword=new-password\n",
            File(tools, "store.input").readText(),
        )
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `typed credentials that the server rejects are neither stored nor erased with the helper`() {
        setHelper("leaf-test")
        createRecordingHelper(answer = null)
        val server = FakeServer(accepts = Answer("user", "right-password"))

        val result = provideCredentials(
            server,
            prompts = listOf(Answer("user", "wrong-password"), Answer("user", "right-password")),
        )

        assertEquals(Either.Ok("objects"), result)
        awaitOperations(2)
        assertEquals(listOf("get", "store"), File(tools, "operations").readLines())
        assertEquals(
            "protocol=https\nhost=example.invalid\nusername=user\npassword=right-password\n",
            File(tools, "store.input").readText(),
        )
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `with a helper, the in-memory cache is neither read nor changed`() {
        setHelper("leaf-test")
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        cacheInMemory(Answer("cached-user", "cached-password"))
        val server = FakeServer(accepts = Answer("helper-user", "helper-password"))

        provideCredentials(server)

        assertEquals(listOf(null, Answer("helper-user", "helper-password")), server.attempts)
        assertEquals(Answer("cached-user", "cached-password"), cachedInMemory())
    }

    @Test
    fun `without a helper, rejected cached credentials are dropped, and typed ones cached once accepted`() {
        cacheInMemory(Answer("user", "old-password"))
        val server = FakeServer(accepts = Answer("user", "new-password"))

        val result = provideCredentials(
            server,
            prompts = listOf(Answer("user", "wrong-password"), Answer("user", "new-password")),
        )

        assertEquals(Either.Ok("objects"), result)
        assertEquals(
            listOf(
                null,
                Answer("user", "old-password"),
                Answer("user", "wrong-password"),
                Answer("user", "new-password"),
            ),
            server.attempts,
        )
        assertEquals(Answer("user", "new-password"), cachedInMemory())
    }

    @Test
    fun `without a helper, cached credentials that the server takes are used without asking`() {
        cacheInMemory(Answer("user", "password"))
        val server = FakeServer(accepts = Answer("user", "password"))

        val result = provideCredentials(server)

        assertEquals(Either.Ok("objects"), result)
        assertEquals(listOf(null, Answer("user", "password")), server.attempts)
    }

    @Test
    fun `without a helper, credentials are not cached when the server fails for another reason`() {
        val server = FakeServer(accepts = Answer("user", "password"), failure = HttpStatusCode.InternalServerError)

        val result = provideCredentials(server, prompts = listOf(Answer("user", "password")))

        assertEquals(Either.Err(LfsError.HttpError(HttpStatusCode.InternalServerError)), result)
        assertNull(cachedInMemory())
    }

    @Test
    fun `without a helper, rejected cached credentials are dropped even when the user then cancels`() {
        cacheInMemory(Answer("user", "old-password"))
        val server = FakeServer(accepts = Answer("user", "new-password"))

        assertThrows<CancellationException> { provideCredentials(server, prompts = listOf(null)) }

        assertNull(cachedInMemory())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a URL that git refuses gets no credentials, and Leaf doesn't ask for them`() {
        setHelper("leaf-test", useHttpPath = true)
        createRecordingHelper(answer = Answer("helper-user", "helper-password"))
        val server = FakeServer(accepts = Answer("helper-user", "helper-password"))
        // The helper would read a second host, and give that server's credentials
        val lfsServer = LfsServer("https://example.invalid/team%0Ahost=other.invalid/project.git/info/lfs", null)

        val result = provideCredentials(server, lfsServer = lfsServer)

        assertEquals(Either.Err(LfsError.HttpError(HttpStatusCode.Unauthorized)), result)
        assertEquals(listOf<Answer?>(null), server.attempts)
        assertFalse(File(tools, "operations").exists())
    }

    @Test
    fun `helpers are asked about the remote's URL only when the LFS server has its scheme, host and port`() {
        fun credentialsUrl(lfsUrl: String, remoteUrl: String?) = lfsCredentialsUri(LfsServer(lfsUrl, remoteUrl))

        assertEquals(URIish(REMOTE_URL), credentialsUrl(LFS_URL, REMOTE_URL))
        val upperCaseHost = "https://EXAMPLE.invalid/team/project.git"
        assertEquals(URIish(upperCaseHost), credentialsUrl(LFS_URL, upperCaseHost))
        assertEquals(URIish(LFS_URL), credentialsUrl(LFS_URL, null))
        assertEquals(URIish(LFS_URL), credentialsUrl(LFS_URL, "http://example.invalid/team/project.git"))
        assertEquals(URIish(LFS_URL), credentialsUrl(LFS_URL, "https://example.invalid:8443/team/project.git"))
        assertEquals(URIish(LFS_URL), credentialsUrl(LFS_URL, "https://lfs.example.invalid/team/project.git"))
        assertEquals(URIish(LFS_URL), credentialsUrl(LFS_URL, "git@example.invalid:team/project.git"))
    }

    /** Sets [helper] as the repository's `credential.helper`. */
    private fun setHelper(helper: String, useHttpPath: Boolean = false) {
        git.repository.config.apply {
            setString("credential", null, "helper", helper)
            setBoolean("credential", null, "useHttpPath", useHttpPath)
            save()
        }
    }

    /**
     * Creates the helper `git-credential-leaf-test` in [tools]. It writes the input of each operation to
     * `<operation>.input`, and each operation to `operations` when it finishes. It answers `get` with [answer], if
     * there is one.
     */
    private fun createRecordingHelper(answer: Answer?) {
        val answerGet = answer?.let { "printf 'username=${it.user}\\npassword=${it.password}\\n'" } ?: ":"

        File(tools, "git-credential-leaf-test").writeExecutable(
            """
            |#!/bin/sh
            |dir="${'$'}(dirname "${'$'}0")"
            |operation="${'$'}1"
            |cat > "${'$'}dir/${'$'}operation.input"
            |echo "${'$'}operation" >> "${'$'}dir/operations"
            |[ "${'$'}operation" = get ] && $answerGet
            |exit 0
            |""".trimMargin()
        )
    }

    /**
     * Runs a request to [server] for [lfsServer] the way Leaf's LFS actions do, and returns its result. When Leaf asks
     * the user, the answers are [prompts], in order, where null cancels; asking more often fails the test.
     */
    private fun provideCredentials(
        server: FakeServer,
        prompts: List<Answer?> = emptyList(),
        lfsServer: LfsServer = LfsServer(LFS_URL, REMOTE_URL),
    ): Either<String, LfsError> = runBlocking {
        val action = ProvideLfsCredentialsGitAction(
            credentialsCacheRepository = credentialsCache,
            credentialsStateManager = credentialsStateManager,
            credentialHelpers = CredentialHelpers(
                shellManager = ShellManager(),
                gitCredentialsManagerProvider = NoCredentialsManager,
                loginShellEnvironment = LoginShellEnvironment { shellVariables },
            ),
        )

        val responder = launch(Dispatchers.Default) {
            for (answer in prompts) {
                credentialsStateManager.credentialsState.first { it == CredentialsRequest.LfsCredentialsRequest }

                if (answer != null) {
                    credentialsStateManager.lfsCredentialsAccepted(answer.user, answer.password)
                } else {
                    credentialsStateManager.credentialsDenied()
                }
            }
        }

        val result = withTimeout(10_000) {
            action(git.repository, lfsServer) { user, password -> server.request(user, password) }
        }

        // Every answer was asked for
        withTimeout(1_000) { responder.join() }

        result
    }

    /** Waits until the helper has finished [count] operations, as Leaf doesn't wait for `store`. */
    private fun awaitOperations(count: Int) = runBlocking {
        val operations = File(tools, "operations")

        withTimeout(10_000) {
            while (!operations.exists() || operations.readLines().size < count) {
                delay(20)
            }
        }
    }

    private fun cacheInMemory(credentials: Answer) = runBlocking {
        credentialsCache.cacheHttpCredentials(LFS_URL, credentials.user, credentials.password, isLfs = true)
    }

    private fun cachedInMemory(): Answer? = credentialsCache.getCachedHttpCredentials(LFS_URL, isLfs = true)
        ?.let { Answer(it.user, it.password) }

    private data class Answer(val user: String, val password: String)

    /**
     * An LFS server that takes the credentials [accepts], or none if it's null, and answers anything else with a 401,
     * or with [failure] for the credentials it takes. It records the credentials of each request.
     */
    private class FakeServer(private val accepts: Answer?, private val failure: HttpStatusCode? = null) {
        val attempts = mutableListOf<Answer?>()

        fun request(user: String?, password: String?): Either<String, LfsError> {
            val given = if (user != null && password != null) Answer(user, password) else null
            attempts += given

            return when {
                given != accepts -> Either.Err(LfsError.HttpError(HttpStatusCode.Unauthorized))
                failure != null -> Either.Err(LfsError.HttpError(failure))
                else -> Either.Ok("objects")
            }
        }
    }

    private object NoCredentialsManager : IGitCredentialsManagerProvider {
        override fun loadPath(): String? = null
    }
}
