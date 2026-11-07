// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.apache.log4j.AppenderSkeleton
import org.apache.log4j.LogManager
import org.apache.log4j.PatternLayout
import org.apache.log4j.spi.LoggingEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Window
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Errors that nothing caught (fork-only): written to Leaf's log with their stack traces, then handled as before. On
 * 2026-11-06 Compose's dialog showed "RootNodeOwner is already disposed", Leaf quit, and its log said nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UncaughtErrorsTest {
    private val logged = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private val log: ErrorLog = { message, error -> logged += message to error }

    /** What reaches log4j, as Leaf's file appender gets it, with the layout that `LogsRepository` gives it. */
    private val appender = RecordingAppender()
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeEach
    fun setUp() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        LogManager.getRootLogger().addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
        LogManager.getRootLogger().removeAppender(appender)
    }

    @Test
    fun `an error in the window is logged, then Compose's handler gets it`() {
        val window = mockk<Window>()
        val order = mutableListOf<String>()
        val handled = mutableListOf<Throwable>()
        val windowsAsked = mutableListOf<Window>()
        val next = WindowExceptionHandlerFactory { asked ->
            windowsAsked += asked
            WindowExceptionHandler { error ->
                order += "next"
                handled += error
            }
        }
        val factory = LoggingWindowExceptionHandlerFactory(next) { message, error ->
            order += "log"
            log(message, error)
        }
        val error = IllegalStateException("RootNodeOwner is already disposed")

        val handler = factory.exceptionHandler(window)
        handler.onException(error)

        assertEquals(listOf(window), windowsAsked)
        assertEquals(listOf("log", "next"), order)
        assertEquals(listOf("Uncaught error in the window" to error), logged.toList())
        assertEquals(listOf<Throwable>(error), handled)
    }

    @Test
    fun `an error in the window still reaches Compose's handler when logging fails`() {
        val handled = mutableListOf<Throwable>()
        val next = WindowExceptionHandlerFactory { WindowExceptionHandler { handled += it } }
        val factory = LoggingWindowExceptionHandlerFactory(next) { _, _ -> throw IllegalStateException("log failed") }
        val error = IllegalStateException("in the window")

        factory.exceptionHandler(mockk()).onException(error)

        assertEquals(listOf<Throwable>(error), handled)
    }

    @Test
    fun `the window's error reaches the log file with its stack trace`() {
        val next = WindowExceptionHandlerFactory { WindowExceptionHandler { } }
        val error = IllegalStateException("RootNodeOwner is already disposed")

        LoggingWindowExceptionHandlerFactory(next).exceptionHandler(mockk()).onException(error)

        val written = appender.written()
        assertTrue(written.contains("ERROR"), written)
        assertTrue(written.contains("UncaughtErrors - Uncaught error in the window"), written)
        assertTrue(written.contains("java.lang.IllegalStateException: RootNodeOwner is already disposed"), written)
        assertTrue(written.contains("\tat dev.app.leaf.UncaughtErrorsTest"), written)
    }

    @Test
    fun `an error on a thread is logged, then the handler before gets it`() {
        val order = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { _, _ -> order += "previous" }
        val handler = LoggingUncaughtExceptionHandler(previous) { message, error ->
            order += "log"
            log(message, error)
        }
        val thread = Thread({}, "leaf-worker")
        val error = IllegalStateException("on a thread")

        handler.uncaughtException(thread, error)

        assertEquals(listOf("log", "previous"), order)
        assertEquals(listOf("Uncaught error in thread \"leaf-worker\"" to error), logged.toList())
    }

    @Test
    fun `without a handler before, the error is printed as the JVM prints it`() {
        val handler = LoggingUncaughtExceptionHandler(next = null, log = log)
        val error = IllegalStateException("on a thread")
        val printed = ByteArrayOutputStream()
        val err = System.err

        System.setErr(PrintStream(printed, true))
        try {
            handler.uncaughtException(Thread({}, "leaf-worker"), error)
        } finally {
            System.setErr(err)
        }

        val text = printed.toString()
        assertTrue(text.startsWith("Exception in thread \"leaf-worker\" java.lang.IllegalStateException: on a thread"), text)
        assertTrue(text.contains("\tat "), text)
        assertEquals(1, logged.size)
    }

    @Test
    fun `a thread's error still reaches the handler before when logging fails`() {
        val handled = mutableListOf<Throwable>()
        val previous = Thread.UncaughtExceptionHandler { _, error -> handled += error }
        val handler = LoggingUncaughtExceptionHandler(previous) { _, _ -> throw IllegalStateException("log failed") }
        val error = IllegalStateException("on a thread")

        handler.uncaughtException(Thread({}, "leaf-worker"), error)

        assertEquals(listOf<Throwable>(error), handled)
    }

    @Test
    fun `once installed, an error that escapes a thread is logged and the handler before still gets it`() {
        val handledBefore = CopyOnWriteArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> handledBefore += error }
        val error = IllegalStateException("escaped a thread")

        logUncaughtErrors()
        val thread = Thread({ throw error }, "leaf-escaping")
        thread.start()
        thread.join(5_000)

        assertEquals(listOf<Throwable>(error), handledBefore.toList())
        val event = appender.events.single { it.renderedMessage.contains("leaf-escaping") }
        assertEquals("UncaughtErrors - Uncaught error in thread \"leaf-escaping\"", event.renderedMessage)
        assertSame(error, event.throwableInformation?.throwable)
    }

    @Test
    fun `once installed, an error that escapes a coroutine is logged`() {
        val handledBefore = CopyOnWriteArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> handledBefore += error }
        val error = IllegalStateException("escaped a coroutine")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        logUncaughtErrors()
        try {
            scope.launch { throw error }
            awaitUntil(5, TimeUnit.SECONDS) { handledBefore.isNotEmpty() }
        } finally {
            scope.cancel()
        }

        assertEquals(listOf<Throwable>(error), handledBefore.toList())
        assertTrue(appender.events.any { it.throwableInformation?.throwable === error })
    }

    private fun awaitUntil(timeout: Long, unit: TimeUnit, condition: () -> Boolean) {
        val end = System.nanoTime() + unit.toNanos(timeout)

        while (!condition()) {
            check(System.nanoTime() < end) { "Nothing happened within $timeout $unit" }
            Thread.sleep(10)
        }
    }

    private class RecordingAppender : AppenderSkeleton() {
        val events = CopyOnWriteArrayList<LoggingEvent>()
        private val layout = PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n")

        override fun append(event: LoggingEvent) {
            events += event
        }

        /** The events as `LogsRepository`'s layout writes them, with the stack traces the appender adds. */
        fun written(): String = buildString {
            for (event in events) {
                append(layout.format(event))
                event.throwableStrRep?.forEach { append(it).append('\n') }
            }
        }

        override fun close() = Unit

        override fun requiresLayout() = false
    }
}
