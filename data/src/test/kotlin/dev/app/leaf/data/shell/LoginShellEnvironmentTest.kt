// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.shell

import dev.app.leaf.common.OS
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.writeExecutable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.junit.jupiter.api.condition.OS as JUnitOS

class LoginShellEnvironmentTest {
    @TempDir
    lateinit var tempDir: File

    private val processRunner = ProcessRunner()

    /**
     * A stand-in for the user's shell: runs [startup] like a startup file would, then the command it gets as its last
     * argument. It also saves its arguments, one per line, in `arguments.txt`.
     */
    private fun fakeShell(startup: String = ""): File = File(tempDir, "fake-shell").writeExecutable(
        """
        |#!/bin/sh
        |printf '%s\n' "$@" > "${File(tempDir, "arguments.txt").absolutePath}"
        |$startup
        |for command; do :; done
        |exec /bin/sh -c "${'$'}command"
        |""".trimMargin()
    )

    /** What a launch from the Finder or a desktop launcher inherits: no TERM, a minimal PATH. */
    private fun desktopEnvironment(shell: File) = mapOf(
        "HOME" to tempDir.absolutePath,
        "PATH" to "/usr/bin:/bin:/usr/sbin:/sbin",
        "SHELL" to shell.absolutePath,
    )

    private fun resolve(
        inherited: Map<String, String>,
        os: OS = OS.MAC,
        timeout: Duration = 10.seconds,
    ): Map<String, String> = runBlocking {
        resolveLoginShellEnvironment(os, inherited, processRunner, timeout)
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `adds what the shell's startup files set`() {
        val shell = fakeShell(
            """
            |echo "A startup file printing to stdout"
            |export PATH="/opt/leaf-tools/bin:${'$'}PATH"
            |export LEAF_FROM_STARTUP_FILE=yes
            """.trimMargin()
        )

        val added = resolve(desktopEnvironment(shell))

        assertTrue(added.getValue("PATH").startsWith("/opt/leaf-tools/bin:"), added["PATH"])
        assertEquals("yes", added["LEAF_FROM_STARTUP_FILE"])
        assertFalse("PWD" in added || "SHLVL" in added || RESOLVING_VARIABLE in added, "${added.keys}")
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `runs an interactive login shell and tells it that Leaf is asking`() {
        val seenByStartup = File(tempDir, "seen-by-startup.txt")
        val shell = fakeShell("echo \"${'$'}$RESOLVING_VARIABLE\" > \"${seenByStartup.absolutePath}\"")

        resolve(desktopEnvironment(shell))

        assertEquals(listOf("-i", "-l", "-c"), File(tempDir, "arguments.txt").readLines().take(3))
        assertEquals("1", seenByStartup.readText().trim())
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `does not run the shell when started from a terminal`() {
        val shell = fakeShell()

        val added = resolve(desktopEnvironment(shell) + ("TERM" to "xterm-256color"))

        assertEquals(emptyMap<String, String>(), added)
        assertFalse(File(tempDir, "arguments.txt").exists())
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `does not run the shell on Windows`() {
        val shell = fakeShell()

        val added = resolve(desktopEnvironment(shell), os = OS.WINDOWS)

        assertEquals(emptyMap<String, String>(), added)
        assertFalse(File(tempDir, "arguments.txt").exists())
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `keeps the inherited environment when the shell fails`() {
        val shell = fakeShell("echo 'Something broke' >&2; exit 3")

        assertEquals(emptyMap<String, String>(), resolve(desktopEnvironment(shell)))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `keeps the inherited environment when the shell takes too long`() {
        val shell = fakeShell("sleep 30")
        val start = TimeSource.Monotonic.markNow()

        val added = resolve(desktopEnvironment(shell), timeout = 1.seconds)

        assertEquals(emptyMap<String, String>(), added)
        assertTrue(start.elapsedNow() < 10.seconds, "took ${start.elapsedNow()}")
    }

    @Test
    fun `keeps the inherited environment when the shell can't be started`() {
        val inherited = desktopEnvironment(File(tempDir, "no-such-shell"))

        assertEquals(emptyMap<String, String>(), resolve(inherited))
    }

    @Test
    @DisabledOnOs(JUnitOS.WINDOWS)
    fun `reads the environment of a real shell`() {
        val inherited = desktopEnvironment(File("/bin/sh")) + ("HOME" to "/not/the/home")

        val added = resolve(inherited)

        // The shell inherits this JVM's variables, and HOME differs from the inherited map's
        assertEquals(System.getenv("HOME"), added["HOME"])
        assertTrue("PATH" in added, "${added.keys}")
    }

    @Test
    fun `parses the environment between the markers`() {
        val output = "\u001B]1337;ShellIntegration\u0007MARKER" +
                "PATH=/opt/homebrew/bin:/usr/bin\u0000" +
                "MULTILINE=first\nsecond\u0000" +
                "WITH_EQUALS=a=b\u0000" +
                "EMPTY=\u0000" +
                "not a variable\u0000" +
                "MARKER logout noise"

        val variables = parseEnvironmentOutput(output, "MARKER")

        assertEquals(
            mapOf(
                "PATH" to "/opt/homebrew/bin:/usr/bin",
                "MULTILINE" to "first\nsecond",
                "WITH_EQUALS" to "a=b",
                "EMPTY" to "",
            ),
            variables,
        )
    }

    @Test
    fun `reports output without both markers`() {
        assertNull(parseEnvironmentOutput("PATH=/usr/bin\u0000", "MARKER"))
        assertNull(parseEnvironmentOutput("MARKERPATH=/usr/bin\u0000", "MARKER"))
    }

    @Test
    fun `adds only new and changed variables that describe the user's setup`() {
        val inherited = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/Users/leaf")
        val shellVariables = mapOf(
            "PATH" to "/opt/homebrew/bin:/usr/bin:/bin",
            "HOME" to "/Users/leaf",
            "NVM_DIR" to "/Users/leaf/.nvm",
            "PWD" to "/Users/leaf",
            "OLDPWD" to "/",
            "SHLVL" to "1",
            "_" to "/usr/bin/env",
            "TERM" to "dumb",
            "GIT_DIR" to "/somewhere/.git",
            "GIT_INDEX_FILE" to "/somewhere/index",
        )

        assertEquals(
            mapOf("PATH" to "/opt/homebrew/bin:/usr/bin:/bin", "NVM_DIR" to "/Users/leaf/.nvm"),
            environmentToAdd(inherited, shellVariables),
        )
    }

    @Test
    fun `uses the arguments each shell accepts`() {
        assertEquals(listOf("-i", "-l", "-c"), loginShellArguments("/bin/zsh"))
        assertEquals(listOf("-i", "-l", "-c"), loginShellArguments("/opt/homebrew/bin/fish"))
        assertEquals(listOf("-i", "-c"), loginShellArguments("/bin/tcsh"))
        assertEquals(listOf("-i", "-c"), loginShellArguments("/bin/csh"))
    }

    @Test
    fun `runs the shell once for every caller`(): Unit = runBlocking {
        val runs = AtomicInteger()
        val environment = LoginShellEnvironment {
            runs.incrementAndGet()
            mapOf("PATH" to "/opt/homebrew/bin")
        }

        environment.prewarm()
        val results = (1..5).map { async { environment.variables() } }.awaitAll() + environment.variablesBlocking()

        assertEquals(1, runs.get())
        assertTrue(results.all { it == mapOf("PATH" to "/opt/homebrew/bin") })
    }

    @Test
    fun `an unexpected failure leaves the environment unchanged`(): Unit = runBlocking {
        val environment = LoginShellEnvironment { error("Unexpected") }

        assertEquals(emptyMap<String, String>(), environment.variables())
        assertEquals(emptyMap<String, String>(), environment.variablesBlocking())
    }
}
