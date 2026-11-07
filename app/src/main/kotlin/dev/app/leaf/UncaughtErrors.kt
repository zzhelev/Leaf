// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import dev.app.leaf.common.printError
import java.awt.Window

private const val TAG = "UncaughtErrors"

/** Writes an error that nothing caught, with its stack trace, to Leaf's log. */
internal typealias ErrorLog = (message: String, error: Throwable) -> Unit

private val leafLog: ErrorLog = { message, error -> printError(TAG, message, error) }

/**
 * Writes the errors that nothing caught, on any thread, to Leaf's log with their stack traces, then hands them to the
 * handler that was there before. A packaged Leaf has no console, so the JVM's own handler printed them nowhere: an
 * exception that escaped a coroutine was lost.
 */
fun logUncaughtErrors() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler(LoggingUncaughtExceptionHandler(previous))
}

internal class LoggingUncaughtExceptionHandler(
    private val next: Thread.UncaughtExceptionHandler?,
    private val log: ErrorLog = leafLog,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        logSafely(log, "Uncaught error in thread \"${thread.name}\"", error)

        if (next != null) {
            next.uncaughtException(thread, error)
        } else {
            // What the JVM prints without a handler (ThreadGroup.uncaughtException)
            System.err.print("Exception in thread \"${thread.name}\" ")
            error.printStackTrace(System.err)
        }
    }
}

/**
 * Compose's handler for an error in a window's composition, layout, drawing or input, which writes it to Leaf's log
 * first. Compose's own shows only the error's message in a dialog, then closes the window, which quits Leaf, and the
 * stack trace was lost: on 2026-11-06 Leaf quit after "RootNodeOwner is already disposed" with nothing in its log.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class LoggingWindowExceptionHandlerFactory(
    private val next: WindowExceptionHandlerFactory = DefaultWindowExceptionHandlerFactory,
    private val log: ErrorLog = leafLog,
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: Window): WindowExceptionHandler {
        val nextHandler = next.exceptionHandler(window)

        return WindowExceptionHandler { error ->
            logSafely(log, "Uncaught error in the window", error)
            nextHandler.onException(error)
        }
    }
}

/** Logging that fails mustn't keep the error from its handler. */
private fun logSafely(log: ErrorLog, message: String, error: Throwable) {
    runCatching { log(message, error) }
}
