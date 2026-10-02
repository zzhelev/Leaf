package com.jetpackduba.gitnuro.terminal

import com.jetpackduba.gitnuro.data.repositories.configuration.DataStoreAppSettingsRepository
import com.jetpackduba.gitnuro.domain.TabCoroutineScope
import com.jetpackduba.gitnuro.domain.errors.okOrNull
import com.jetpackduba.gitnuro.domain.usecases.GetWorktreeUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

// For flatpak: https://github.com/flathub/com.visualstudio.code#use-host-shell-in-the-integrated-terminal

class OpenRepositoryInTerminalGitAction @Inject constructor(
    private val terminalProvider: ITerminalProvider,
    private val settings: DataStoreAppSettingsRepository,
    private val getWorktreeUseCase: GetWorktreeUseCase,
    private val tabScope: TabCoroutineScope,
) {
    operator fun invoke() = tabScope.launch {
        val terminalEmulators = terminalProvider.getTerminalEmulators()
        // The repository path is the git dir, whose parent is not the working tree for linked worktrees
        val repositoryDir = getWorktreeUseCase().okOrNull() ?: return@launch

        val terminalPath = settings.terminalPath.firstOrNull()

        if (!terminalPath.isNullOrBlank()) {
            terminalProvider.startTerminal(TerminalEmulator("CUSTOM_TERMINAL", terminalPath), repositoryDir)
        } else {
            for (terminal in terminalEmulators) {
                val isTerminalEmulatorInstalled = terminalProvider.isTerminalInstalled(terminal)
                if (isTerminalEmulatorInstalled) {
                    terminalProvider.startTerminal(terminal, repositoryDir)
                    break
                }
            }
        }
    }
}
