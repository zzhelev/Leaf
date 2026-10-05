// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.Duration

private const val DESTROY_GRACE_PERIOD_MS = 2_000L

sealed interface ProcessOutcome {
    data class Completed(val exitCode: Int, val stdout: String, val stderr: String) : ProcessOutcome
    data object TimedOut : ProcessOutcome
}

/**
 * Runs external processes from coroutines. On timeout or cancellation the process and its descendants are killed, so
 * no process outlives the coroutine that started it.
 */
class ProcessRunner @Inject constructor() {
    /**
     * @param environment variables to add to the inherited environment; a null value removes the variable.
     * @throws java.io.IOException if the process can't be started.
     */
    suspend fun run(
        command: List<String>,
        workingDirectory: File?,
        environment: Map<String, String?>,
        timeout: Duration,
    ): ProcessOutcome = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .apply {
                val processEnvironment = environment()

                for ((key, value) in environment) {
                    if (value == null) {
                        processEnvironment.remove(key)
                    } else {
                        processEnvironment[key] = value
                    }
                }
            }
            .start()

        // Nothing is ever written to stdin, close it so commands that read it don't wait forever
        process.outputStream.close()

        coroutineScope {
            // Both streams have to be drained while the process runs, otherwise it blocks once a pipe buffer is full
            val stdout = async { process.inputStream.readBytes().decodeToString() }
            val stderr = async { process.errorStream.readBytes().decodeToString() }

            try {
                val exitCode = withTimeoutOrNull(timeout) { process.onExit().await().exitValue() }

                if (exitCode == null) {
                    process.destroyTree()
                    ProcessOutcome.TimedOut
                } else {
                    ProcessOutcome.Completed(exitCode, stdout.await(), stderr.await())
                }
            } finally {
                // On cancellation the blocking stream reads only finish once the process is gone
                if (process.isAlive) {
                    process.destroyTree()
                }
            }
        }
    }

    /**
     * Asks the process and its descendants to terminate (git removes its lock files on SIGTERM), then kills whatever
     * is still alive after a grace period. A surviving descendant would keep the output pipes open.
     */
    private fun Process.destroyTree() {
        // Collected first, as descendants are reparented once their parent dies
        val descendants = descendants().toList()

        descendants.forEach { it.destroy() }
        destroy()

        if (!waitFor(DESTROY_GRACE_PERIOD_MS, TimeUnit.MILLISECONDS)) {
            destroyForcibly()
        }

        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}
