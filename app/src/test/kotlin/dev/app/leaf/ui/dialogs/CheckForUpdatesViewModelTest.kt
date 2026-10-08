// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import com.sun.net.httpserver.HttpServer
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.usecases.OpenPathInSystemUseCase
import dev.app.leaf.system.OpenUrlInBrowserUseCase
import dev.app.leaf.updates.Update
import dev.app.leaf.updates.UpdateCheck
import dev.app.leaf.updates.UpdatesRepository
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val CURRENT_VERSION_CODE = 28

private val NEWER_RELEASE = Update(
    appVersion = "1.29",
    appCode = CURRENT_VERSION_CODE + 1,
    downloadUrl = "https://github.com/zzhelev/Leaf/releases/tag/leaf-1.29",
)

class CheckForUpdatesViewModelTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val requests = AtomicInteger()

    /** The status of each answer in turn, 200 being the newer release. The last one repeats. */
    @Volatile
    private var statuses: List<Int> = listOf(200)

    /** The server answers once this is open. */
    @Volatile
    private var answering = CountDownLatch(1)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient = HttpClient(CIO)
    private lateinit var viewModel: CheckForUpdatesViewModel

    @BeforeEach
    fun setUp() {
        server.createContext("/latest.json") { exchange ->
            val status = statuses[minOf(requests.getAndIncrement(), statuses.lastIndex)]
            answering.await(5, TimeUnit.SECONDS)

            exchange.use {
                val body = """{"appVersion":"${NEWER_RELEASE.appVersion}","appCode":${NEWER_RELEASE.appCode},""" +
                    """"downloadUrl":"${NEWER_RELEASE.downloadUrl}"}"""
                val bytes = body.toByteArray()
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        answering.countDown()
        if (::viewModel.isInitialized) {
            viewModel.onClear()
        }
        scope.cancel()
        httpClient.close()
        server.stop(0)
    }

    @Test
    fun `the dialog checks when it opens, and says what it found`(): Unit = runBlocking {
        answering.countDown()
        viewModel = openDialog()

        assertEquals(UpdateCheck.Available(NEWER_RELEASE), awaitResult())
    }

    @Test
    fun `asking again while a check runs doesn't start another`(): Unit = runBlocking {
        viewModel = openDialog()
        awaitRequests(1)

        viewModel.check()
        viewModel.check()
        delay(200.milliseconds)
        assertNull(viewModel.result.value)
        answering.countDown()

        assertEquals(UpdateCheck.Available(NEWER_RELEASE), awaitResult())
        // Time for a second request to arrive, which the server only takes once it answered the first
        delay(300.milliseconds)
        assertEquals(1, requests.get())
    }

    @Test
    fun `trying again checks again`(): Unit = runBlocking {
        statuses = listOf(500, 200)
        answering.countDown()
        viewModel = openDialog()
        assertInstanceOf(UpdateCheck.Failed::class.java, awaitResult())
        answering = CountDownLatch(1)

        viewModel.check()
        assertNull(viewModel.result.value)
        answering.countDown()

        assertEquals(UpdateCheck.Available(NEWER_RELEASE), awaitResult())
        assertEquals(2, requests.get())
    }

    private fun openDialog() = CheckForUpdatesViewModel(
        updatesRepository = UpdatesRepository(
            httpClient = httpClient,
            versionCheckUrl = "http://127.0.0.1:${server.address.port}/latest.json",
            currentVersionCode = CURRENT_VERSION_CODE,
            checkInterval = 1.hours,
            scope = scope,
        ),
        // Never called here: it would open a browser
        openUrlInBrowserUseCase = OpenUrlInBrowserUseCase(OpenPathInSystemUseCase(ShellManager())),
    )

    private suspend fun awaitResult(): UpdateCheck = withTimeout(5.seconds) {
        viewModel.result.first { it != null }!!
    }

    private suspend fun awaitRequests(count: Int) = withTimeout(5.seconds) {
        while (requests.get() < count) {
            delay(10.milliseconds)
        }
    }
}
