// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.branches.GetTrackingBranchGitAction
import dev.app.leaf.data.git.cli.askpass.answeringDialogs
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.credentials.CredentialsRequest
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.models.PullType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.concurrent.thread

private const val USER = "leaf"
private const val PASSWORD = "s3cret"

/**
 * Pushes, fetches and pulls over HTTP with the git CLI, to `git http-backend`, which a small server runs as CGI behind
 * Basic authentication. git asks for the credentials through the askpass helper, and Leaf answers with its dialogs and its
 * in-memory cache. Skipped when the askpass helper isn't built.
 */
@DisabledOnOs(OS.WINDOWS)
class GitCliHttpsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var globalConfig: File
    private lateinit var helper: File
    private lateinit var server: HttpServer
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        helper = built!!
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)

        val root = File(tempDir, "served").apply { mkdirs() }
        git.run(root, "init", "--bare", "repo.git")
        server = startGitHttpServer(root)

        work = git.initRepository(File(tempDir, "work"))
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) {
            server.stop(0)
        }
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `git's prompts take one dialog, and the cache answers the next push`(): Unit = runBlocking {
        val remote = remoteWithUrl("http://127.0.0.1:${server.address.port}/repo.git")

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs({ httpCredentialsAccepted(USER, PASSWORD) }) {
            push(remote)
        }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(listOf(CredentialsRequest.HttpCredentialsRequest(user = null, askPassword = true)), dialogs)

        commit("second")
        val (again, dialogsAgain) = remote.credentialsStateManager.answeringDialogs { push(remote) }

        assertEquals(Either.Ok(Unit), again)
        assertEquals(emptyList<CredentialsRequest>(), dialogsAgain)
    }

    @Test
    fun `a user name in the URL is only asked for the password`(): Unit = runBlocking {
        val remote = remoteWithUrl("http://$USER@127.0.0.1:${server.address.port}/repo.git")

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs({ httpCredentialsAccepted("ignored", PASSWORD) }) {
            push(remote)
        }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(listOf(CredentialsRequest.HttpCredentialsRequest(user = USER, askPassword = true)), dialogs)
    }

    @Test
    fun `wrong credentials fail the push and aren't cached`(): Unit = runBlocking {
        val remote = remoteWithUrl("http://127.0.0.1:${server.address.port}/repo.git")

        val (result, _) = remote.credentialsStateManager.answeringDialogs({ httpCredentialsAccepted(USER, "wrong") }) {
            push(remote)
        }

        assertInstanceOf(RemoteOperationError.AuthenticationFailed::class.java, (result as Either.Err).error)

        val (_, dialogs) = remote.credentialsStateManager.answeringDialogs({ httpCredentialsAccepted(USER, PASSWORD) }) {
            push(remote)
        }
        assertEquals(1, dialogs.size)
    }

    @Test
    fun `a closed dialog stops the push`(): Unit = runBlocking {
        val remote = remoteWithUrl("http://127.0.0.1:${server.address.port}/repo.git")

        val (result, _) = remote.credentialsStateManager.answeringDialogs({ credentialsDenied() }) { push(remote) }

        assertInstanceOf(RemoteOperationError.PromptRefused::class.java, (result as Either.Err).error)
    }

    @Test
    fun `without the cache setting, every push asks`(): Unit = runBlocking {
        val remote = remoteWithUrl("http://127.0.0.1:${server.address.port}/repo.git", cacheCredentials = false)
        val answer: CredentialsStateManager.(CredentialsRequest) -> Unit = { httpCredentialsAccepted(USER, PASSWORD) }

        remote.credentialsStateManager.answeringDialogs(answer) { push(remote) }
        commit("second")
        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs(answer) { push(remote) }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(1, dialogs.size)
    }

    @Test
    fun `fetch asks through the same dialog, and pull uses the credentials it cached`(): Unit = runBlocking {
        val pusher = remoteWithUrl("http://127.0.0.1:${server.address.port}/repo.git")
        pusher.credentialsStateManager.answeringDialogs({ httpCredentialsAccepted(USER, PASSWORD) }) { push(pusher) }

        // A new session of Leaf, with nothing cached
        val remote = TestRemoteCommand(helper, globalConfig)
        val (fetched, fetchDialogs) = remote.credentialsStateManager.answeringDialogs(
            { httpCredentialsAccepted(USER, PASSWORD) },
        ) {
            GitCliFetchAllRemotesGitAction(jgit, remote.command)(gitDir, specificRemote = null)
        }

        assertEquals(Either.Ok(Unit), fetched)
        assertEquals(listOf(CredentialsRequest.HttpCredentialsRequest(user = null, askPassword = true)), fetchDialogs)

        val (pulled, pullDialogs) = remote.credentialsStateManager.answeringDialogs {
            GitCliPullBranchGitAction(
                jgit = jgit,
                remoteCommand = remote.command,
                checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
                deleteStashGitAction = DeleteStashGitAction(jgit),
                commitMapper = JGitCommitMapper(JGitIdentityMapper()),
            )(gitDir, PullType.MERGE, mergeAutoStash = true, remoteBranch = null, "automatic stash")
        }

        assertEquals(Either.Ok(false), pulled)
        assertEquals(emptyList<CredentialsRequest>(), pullDialogs)
    }

    @Test
    fun `a fetch whose dialog is closed reports nothing, as with JGit`(): Unit = runBlocking {
        val remote = remoteWithUrl("http://127.0.0.1:${server.address.port}/repo.git")

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs({ credentialsDenied() }) {
            GitCliFetchAllRemotesGitAction(jgit, remote.command)(gitDir, specificRemote = null)
        }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(1, dialogs.size)
    }

    private fun remoteWithUrl(url: String, cacheCredentials: Boolean = true): TestRemoteCommand {
        git.run(work, "remote", "add", "origin", url)

        return TestRemoteCommand(helper, globalConfig, cacheCredentials)
    }

    private suspend fun push(remote: TestRemoteCommand): Either<Unit, GitError> =
        GitCliPushBranchGitAction(jgit, GetTrackingBranchGitAction(jgit), remote.command)(
            gitDir,
            force = false,
            pushTags = false,
            pushWithLease = true,
            specificBranch = null,
        )

    private fun commit(message: String) {
        File(work, "file.txt").appendText("$message\n")
        git.run(work, "add", ".")
        git.run(work, "commit", "-m", message)
    }

    /** Serves the repositories in [root] with `git http-backend`, to requests with the right Basic credentials. */
    private fun startGitHttpServer(root: File): HttpServer {
        val expected = "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        server.createContext("/") { exchange ->
            exchange.use {
                if (exchange.requestHeaders.getFirst("Authorization") != expected) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"leaf\"")
                    exchange.sendResponseHeaders(401, -1)
                } else {
                    runHttpBackend(exchange, root)
                }
            }
        }
        server.start()

        return server
    }

    private fun runHttpBackend(exchange: HttpExchange, root: File) {
        val process = ProcessBuilder("git", "http-backend")
            .apply {
                val environment = environment()
                environment["GIT_PROJECT_ROOT"] = root.absolutePath
                environment["GIT_HTTP_EXPORT_ALL"] = "1"
                environment["GIT_CONFIG_GLOBAL"] = globalConfig.absolutePath
                environment["GIT_CONFIG_NOSYSTEM"] = "1"
                environment["REQUEST_METHOD"] = exchange.requestMethod
                environment["PATH_INFO"] = exchange.requestURI.path
                environment["QUERY_STRING"] = exchange.requestURI.rawQuery.orEmpty()
                environment["CONTENT_TYPE"] = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                environment["REMOTE_USER"] = USER
                environment["REMOTE_ADDR"] = "127.0.0.1"
                exchange.requestHeaders.getFirst("Git-Protocol")?.let { environment["GIT_PROTOCOL"] = it }
                exchange.requestHeaders.getFirst("Content-Encoding")?.let { environment["HTTP_CONTENT_ENCODING"] = it }
            }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val input = thread {
            process.outputStream.use { exchange.requestBody.copyTo(it) }
        }

        val output = process.inputStream.readBytes()
        input.join()
        process.waitFor()

        // CGI: headers, a blank line, then the body. http-backend ends its lines with \r\n.
        val separator = output.indexOfSequence("\r\n\r\n".toByteArray())
        check(separator >= 0) { "http-backend wrote no headers: ${output.decodeToString()}" }
        val headers = output.copyOfRange(0, separator).decodeToString().lines().filter { it.isNotBlank() }
        val body = output.copyOfRange(separator + 4, output.size)

        var status = 200
        for (header in headers) {
            val name = header.substringBefore(':').trim()
            val value = header.substringAfter(':').trim()

            if (name.equals("Status", ignoreCase = true)) {
                status = value.substringBefore(' ').toInt()
            } else {
                exchange.responseHeaders.add(name, value)
            }
        }

        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) {
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int {
        outer@ for (start in 0..size - sequence.size) {
            for (offset in sequence.indices) {
                if (this[start + offset] != sequence[offset]) continue@outer
            }
            return start
        }
        return -1
    }
}
