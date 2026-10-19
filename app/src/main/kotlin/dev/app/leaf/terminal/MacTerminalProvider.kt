package dev.app.leaf.terminal


import dev.app.leaf.domain.IShellManager
import java.io.File
import javax.inject.Inject

class MacTerminalProvider @Inject constructor(
    private val shellManager: IShellManager,
) : ITerminalProvider {
    override fun getTerminalEmulators(): List<TerminalEmulator> {
        return listOf(
            TerminalEmulator("MacOS Terminal", "Terminal")
        )
    }

    override fun isTerminalInstalled(terminalEmulator: TerminalEmulator): Boolean {
        return true // TODO Return true always until we support multiple terminals
    }

    override fun startTerminal(terminalEmulator: TerminalEmulator, repositoryPath: String) {
        shellManager.runCommandInPath(openCommand(terminalEmulator, repositoryPath), repositoryPath)
    }

    // Terminal and iTerm2 take the folder as a document, as when it is dropped on the app, and open a window in the
    // running instance. `-n` starts another instance on every click, and `--args` hands the path over as an
    // argument, which Terminal also opens a window for. Checked with both; an emulator that was not checked keeps the
    // old command.
    private fun openCommand(terminalEmulator: TerminalEmulator, repositoryPath: String): List<String> {
        val app = File(terminalEmulator.path).name.removeSuffix(".app").lowercase()

        return if (app in APPS_THAT_OPEN_FOLDERS) {
            listOf("open", "-a", terminalEmulator.path, repositoryPath)
        } else {
            listOf("open", "-a", terminalEmulator.path, "-n", "--args", repositoryPath)
        }
    }

    private companion object {
        val APPS_THAT_OPEN_FOLDERS = setOf("terminal", "iterm", "iterm2")
    }
}