package com.jetpackduba.gitnuro.data.git.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {
    @TempDir
    lateinit var tempDir: File

    private val processRunner = ProcessRunner()

    @Test
    fun `captures the exit code, stdout and stderr`() {
        val outcome = sh("echo out; echo err >&2; exit 3")

        assertEquals(ProcessOutcome.Completed(3, "out\n", "err\n"), outcome)
    }

    @Test
    fun `runs in the working directory`() {
        val outcome = sh("pwd -P") as ProcessOutcome.Completed

        assertEquals(tempDir.canonicalPath, outcome.stdout.trim())
    }

    @Test
    fun `adds and removes environment variables`() {
        val outcome = sh(
            "echo \"\$GITNURO_TEST_VARIABLE|\${HOME-removed}\"",
            environment = mapOf("GITNURO_TEST_VARIABLE" to "added", "HOME" to null),
        )

        assertEquals(ProcessOutcome.Completed(0, "added|removed\n", ""), outcome)
    }

    @Test
    fun `closes stdin so commands reading it do not wait forever`() {
        assertEquals(ProcessOutcome.Completed(0, "", ""), sh("cat"))
    }

    @Test
    fun `drains large output on both streams`() {
        val outcome = sh("head -c 1000000 /dev/zero | tr '\\0' a; head -c 1000000 /dev/zero | tr '\\0' b >&2")

        outcome as ProcessOutcome.Completed
        assertEquals(1_000_000, outcome.stdout.length)
        assertEquals(1_000_000, outcome.stderr.length)
    }

    @Test
    fun `kills the process and its children on timeout`() {
        val childPidFile = File(tempDir, "child.pid")
        val startNanos = System.nanoTime()

        val outcome = sh("sleep 30 & echo \$! > '${childPidFile.absolutePath}'; wait", timeout = 500.milliseconds)

        assertEquals(ProcessOutcome.TimedOut, outcome)
        assertTrue(System.nanoTime() - startNanos < TimeUnit.SECONDS.toNanos(10), "Timeout took too long")
        assertProcessExits(childPidFile.readText().trim().toLong())
    }

    @Test
    fun `kills the process when the coroutine is cancelled`(): Unit = runBlocking {
        val pidFile = File(tempDir, "process.pid")
        val command = listOf("/bin/sh", "-c", "echo \$\$ > '${pidFile.absolutePath}'; sleep 30")

        val job = launch(Dispatchers.Default) {
            processRunner.run(command, tempDir, emptyMap(), 60.seconds)
        }

        withTimeout(5.seconds) {
            while (!pidFile.exists() || pidFile.readText().isBlank()) delay(20)
        }

        withTimeout(10.seconds) { job.cancelAndJoin() }

        assertProcessExits(pidFile.readText().trim().toLong())
    }

    @Test
    fun `throws when the process can't be started`() {
        assertThrows(IOException::class.java) {
            runBlocking {
                processRunner.run(listOf(File(tempDir, "missing").absolutePath), tempDir, emptyMap(), 5.seconds)
            }
        }
    }

    private fun sh(
        script: String,
        timeout: Duration = 10.seconds,
        environment: Map<String, String?> = emptyMap(),
    ): ProcessOutcome = runBlocking {
        processRunner.run(listOf("/bin/sh", "-c", script), tempDir, environment, timeout)
    }

    private fun assertProcessExits(pid: Long) {
        // Throws a TimeoutException if the process is still alive
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
    }
}
