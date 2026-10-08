// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

import com.sun.net.httpserver.HttpServer
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val CURRENT_VERSION_CODE = 28

class UpdatesRepositoryTest {
    private sealed interface Answer {
        /** Closes the connection without answering. */
        data object Drop : Answer

        data class Respond(val status: Int, val body: String) : Answer
    }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val requests = AtomicInteger()

    /** What the server answers to each request in turn. The last answer repeats. */
    @Volatile
    private var answers: List<Answer> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = HttpClient(CIO)

    @BeforeEach
    fun setUp() {
        server.createContext("/latest.json") { exchange ->
            val index = requests.getAndIncrement()

            when (val answer = answers[minOf(index, answers.lastIndex)]) {
                Answer.Drop -> exchange.close()
                is Answer.Respond -> exchange.use {
                    val body = answer.body.toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(answer.status, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        httpClient.close()
        server.stop(0)
    }

    @Test
    fun `a newer release is the update`(): Unit = runBlocking {
        answers = listOf(release(appCode = CURRENT_VERSION_CODE + 1))
        val repository = repository()

        assertEquals(update(CURRENT_VERSION_CODE + 1), awaitUpdate(repository))
    }

    @Test
    fun `a release that isn't newer is no update`(): Unit = runBlocking {
        answers = listOf(release(appCode = CURRENT_VERSION_CODE))
        val repository = repository()
        scope.launch { repository.update.collect {} }

        awaitRequests(3)

        assertNull(repository.update.value)
    }

    @Test
    fun `checks go on after a failed one`(): Unit = runBlocking {
        answers = listOf(
            Answer.Drop,
            Answer.Respond(500, "Internal Server Error"),
            Answer.Respond(404, "404: Not Found"),
            // An error page that happens to hold a release isn't read.
            Answer.Respond(503, releaseJson(appCode = CURRENT_VERSION_CODE + 2)),
            Answer.Respond(200, "<html>not json</html>"),
            release(appCode = CURRENT_VERSION_CODE + 1),
        )
        val repository = repository()

        assertEquals(update(CURRENT_VERSION_CODE + 1), awaitUpdate(repository))
    }

    @Test
    fun `a failed check keeps the update found before`(): Unit = runBlocking {
        answers = listOf(
            release(appCode = CURRENT_VERSION_CODE + 1),
            Answer.Respond(500, "Internal Server Error"),
        )
        val repository = repository()
        awaitUpdate(repository)

        awaitRequests(4)

        assertEquals(update(CURRENT_VERSION_CODE + 1), repository.update.value)
    }

    @Test
    fun `a later release replaces the update`(): Unit = runBlocking {
        answers = listOf(
            release(appCode = CURRENT_VERSION_CODE + 1),
            release(appCode = CURRENT_VERSION_CODE + 2),
        )
        val repository = repository()

        withTimeout(5.seconds) {
            repository.update.first { it == update(CURRENT_VERSION_CODE + 2) }
        }
    }

    @Test
    fun `every tab shares one check`(): Unit = runBlocking {
        answers = listOf(release(appCode = CURRENT_VERSION_CODE + 1))
        val repository = repository(checkInterval = 1.hours)

        // Each tab's view models collect the update, as the Welcome page and an open repository do.
        repeat(6) {
            scope.launch { repository.update.collect {} }
        }
        awaitUpdate(repository)
        delay(300.milliseconds)

        assertEquals(1, requests.get())
    }

    @Test
    fun `a check by hand finds a newer release, which every tab then shows`(): Unit = runBlocking {
        answers = listOf(
            release(appCode = CURRENT_VERSION_CODE),
            release(appCode = CURRENT_VERSION_CODE + 1),
        )
        val repository = repository(checkInterval = 1.hours)
        scope.launch { repository.update.collect {} }
        awaitRequests(1)

        assertEquals(UpdateCheck.Available(update(CURRENT_VERSION_CODE + 1)), repository.checkNow())
        assertEquals(update(CURRENT_VERSION_CODE + 1), awaitUpdate(repository))
        assertEquals(2, requests.get())
    }

    @Test
    fun `an update found by hand shows when the first tab looks`(): Unit = runBlocking {
        answers = listOf(
            release(appCode = CURRENT_VERSION_CODE + 1),
            release(appCode = CURRENT_VERSION_CODE),
        )
        val repository = repository(checkInterval = 1.hours)

        assertEquals(UpdateCheck.Available(update(CURRENT_VERSION_CODE + 1)), repository.checkNow())
        assertEquals(update(CURRENT_VERSION_CODE + 1), awaitUpdate(repository))
    }

    @Test
    fun `a check by hand says when this build is the latest`(): Unit = runBlocking {
        answers = listOf(release(appCode = CURRENT_VERSION_CODE))
        val repository = repository(checkInterval = 1.hours)

        assertEquals(UpdateCheck.UpToDate, repository.checkNow())
        assertNull(repository.update.value)
    }

    @Test
    fun `a check by hand says why it failed`(): Unit = runBlocking {
        answers = listOf(
            Answer.Respond(404, "404: Not Found"),
            // An error page that happens to hold a release isn't read.
            Answer.Respond(503, releaseJson(appCode = CURRENT_VERSION_CODE + 1)),
            Answer.Respond(200, "<html>not json</html>"),
            Answer.Drop,
        )
        val repository = repository(checkInterval = 1.hours)

        assertEquals(UpdateCheck.Failed("$versionCheckUrl answered 404 Not Found"), repository.checkNow())
        assertEquals(UpdateCheck.Failed("$versionCheckUrl answered 503 Service Unavailable"), repository.checkNow())
        assertEquals(UpdateCheck.Failed("$versionCheckUrl didn't answer with a release"), repository.checkNow())
        assertCouldNotReach(repository.checkNow())
    }

    @Test
    fun `a check by hand says when the server can't be reached`(): Unit = runBlocking {
        // A port that nothing listens on any more
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val repository = repository(versionCheckUrl = "http://127.0.0.1:$port/latest.json")

        assertCouldNotReach(repository.checkNow())
    }

    @Test
    fun `a check by hand says when the host isn't found`(): Unit = runBlocking {
        // .invalid never resolves (RFC 6761), like any host with no network
        val repository = repository(versionCheckUrl = "https://leaf-update-check.invalid/latest.json")

        assertEquals(
            UpdateCheck.Failed("Couldn't find the address of leaf-update-check.invalid"),
            repository.checkNow(),
        )
    }

    @Test
    fun `a failed check by hand keeps the update found before`(): Unit = runBlocking {
        answers = listOf(
            release(appCode = CURRENT_VERSION_CODE + 1),
            Answer.Respond(500, "Internal Server Error"),
        )
        val repository = repository(checkInterval = 1.hours)
        awaitUpdate(repository)

        assertEquals(UpdateCheck.Failed("$versionCheckUrl answered 500 Internal Server Error"), repository.checkNow())
        assertEquals(update(CURRENT_VERSION_CODE + 1), repository.update.value)
    }

    private val versionCheckUrl get() = "http://127.0.0.1:${server.address.port}/latest.json"

    private fun repository(
        checkInterval: Duration = 20.milliseconds,
        versionCheckUrl: String = this.versionCheckUrl,
    ) = UpdatesRepository(
        httpClient = httpClient,
        versionCheckUrl = versionCheckUrl,
        currentVersionCode = CURRENT_VERSION_CODE,
        checkInterval = checkInterval,
        scope = scope,
    )

    private fun assertCouldNotReach(check: UpdateCheck) {
        assertInstanceOf(UpdateCheck.Failed::class.java, check)
        val reason = (check as UpdateCheck.Failed).reason
        assertTrue(reason.startsWith("Couldn't reach 127.0.0.1: ") && reason.length > 26, reason)
    }

    private suspend fun awaitUpdate(repository: UpdatesRepository): Update? = withTimeout(5.seconds) {
        repository.update.first { it != null }
    }

    private suspend fun awaitRequests(count: Int) = withTimeout(5.seconds) {
        while (requests.get() < count) {
            delay(10.milliseconds)
        }
    }

    private fun update(appCode: Int) = Update(
        appVersion = "1.$appCode",
        appCode = appCode,
        downloadUrl = "https://github.com/zzhelev/Leaf/releases/tag/leaf-1.$appCode",
    )

    private fun release(appCode: Int): Answer = Answer.Respond(200, releaseJson(appCode))

    private fun releaseJson(appCode: Int): String {
        val update = update(appCode)

        return """{"appVersion":"${update.appVersion}","appCode":${update.appCode},"downloadUrl":"${update.downloadUrl}"}"""
    }
}
