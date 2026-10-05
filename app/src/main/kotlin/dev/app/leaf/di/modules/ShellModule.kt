package dev.app.leaf.di.modules

import dev.app.leaf.AppEnvInfo
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.FlatpakShellManager
import dev.app.leaf.domain.IShellManager
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.terminal.ITerminalProvider
import dev.app.leaf.terminal.LinuxTerminalProvider
import dev.app.leaf.terminal.MacTerminalProvider
import dev.app.leaf.terminal.WindowsTerminalProvider
import dagger.Module
import dagger.Provides
import javax.inject.Provider

@Module
class ShellModule {
    @Provides
    fun provideShellManager(
        appEnvInfo: AppEnvInfo,
        shellManager: Provider<ShellManager>,
        flatpakShellManager: Provider<FlatpakShellManager>,
    ): IShellManager {
        return if (appEnvInfo.isFlatpak)
            flatpakShellManager.get()
        else
            shellManager.get()
    }

    @Provides
    fun provideTerminalProvider(
        linuxTerminalProvider: Provider<LinuxTerminalProvider>,
        windowsTerminalProvider: Provider<WindowsTerminalProvider>,
        macTerminalProvider: Provider<MacTerminalProvider>,
    ): ITerminalProvider {
        return when (currentOs) {
            OS.LINUX -> linuxTerminalProvider.get()
            OS.WINDOWS -> windowsTerminalProvider.get()
            OS.MAC -> macTerminalProvider.get()
            OS.UNKNOWN -> throw NotImplementedError("Unknown operating system")
        }
    }
}