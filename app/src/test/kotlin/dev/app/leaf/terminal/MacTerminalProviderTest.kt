// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.terminal

import dev.app.leaf.domain.IShellManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Terminal button on macOS (Gitnuro#208): what [MacTerminalProvider] asks `open` to do. The shell is a fake that
 * records the command, so no window opens.
 */
class MacTerminalProviderTest {
    private class RecordingShellManager : IShellManager {
        val runs = mutableListOf<Pair<List<String>, String>>()

        override fun runCommandInPath(command: List<String>, path: String) {
            runs += command to path
        }

        override fun runCommand(command: List<String>): String? = error("not used")

        override fun runCommandWithoutResult(command: List<String>): Boolean = error("not used")

        override fun runCommandProcess(
            command: List<String>,
            directory: File?,
            environment: Map<String, String>,
        ): Process = error("not used")
    }

    private val shell = RecordingShellManager()
    private val provider = MacTerminalProvider(shell)

    private fun start(path: String, repositoryPath: String = "/tmp/repo"): Pair<List<String>, String> {
        provider.startTerminal(TerminalEmulator("CUSTOM_TERMINAL", path), repositoryPath)

        return shell.runs.single()
    }

    @Test
    fun `the built-in Terminal opens the folder in its running instance`(): Unit {
        val emulator = provider.getTerminalEmulators().single()

        provider.startTerminal(emulator, "/tmp/repo")

        // No `-n`, which starts another Terminal on every click, and no `--args`, which hands the path over as an
        // argument that Terminal opens its own window for
        assertEquals(listOf("open", "-a", "Terminal", "/tmp/repo") to "/tmp/repo", shell.runs.single())
    }

    @Test
    fun `a path with spaces and non-ASCII characters is one argument, as it is`(): Unit {
        val repository = "/tmp/leaf-terminal-208/space ünï repo 日本"

        provider.startTerminal(provider.getTerminalEmulators().single(), repository)

        assertEquals(listOf("open", "-a", "Terminal", repository) to repository, shell.runs.single())
    }

    @Test
    fun `a custom path to Terminal or iTerm2 opens the folder the same way`(): Unit {
        val customs = listOf(
            "Terminal",
            "terminal",
            "/System/Applications/Utilities/Terminal.app",
            "iTerm",
            "iTerm2",
            "iTerm.app",
            "/Applications/iTerm.app",
            "/Applications/iTerm.app/",
            "/Users/someone/Applications/iTerm2.app",
        )

        for (custom in customs) {
            shell.runs.clear()

            assertEquals(
                listOf("open", "-a", custom, "/tmp/repo") to "/tmp/repo",
                start(custom),
                "custom path $custom",
            )
        }
    }

    @Test
    fun `a custom emulator that was not checked keeps the command it had`(): Unit {
        val customs = listOf(
            "Ghostty",
            "/Applications/Ghostty.app",
            "/Applications/kitty.app",
            "WezTerm",
            "/opt/homebrew/bin/alacritty",
            "/Applications/Terminal Plus.app",
            "/Applications/iTerm2-nightly.app",
        )

        for (custom in customs) {
            shell.runs.clear()

            assertEquals(
                listOf("open", "-a", custom, "-n", "--args", "/tmp/repo") to "/tmp/repo",
                start(custom),
                "custom path $custom",
            )
        }
    }
}
