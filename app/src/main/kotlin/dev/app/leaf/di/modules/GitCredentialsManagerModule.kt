package dev.app.leaf.di.modules

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.credentials.external.IGitCredentialsManagerProvider
import dev.app.leaf.domain.credentials.external.NixGitCredentialsManagerProvider
import dev.app.leaf.domain.credentials.external.WindowsGitCredentialsManagerProvider
import dagger.Module
import dagger.Provides
import javax.inject.Provider

@Module
class GitCredentialsManagerModule {
    @Provides
    fun providesGitCredentialsManagerProvider(
        windowsGitCredentialsManagerProvider: Provider<WindowsGitCredentialsManagerProvider>,
        nixGitCredentialsManagerProvider: Provider<NixGitCredentialsManagerProvider>,
    ): IGitCredentialsManagerProvider {
        return when (currentOs) {
            OS.LINUX, OS.MAC ->  nixGitCredentialsManagerProvider.get() // TODO Test this on MacOs
            OS.WINDOWS -> windowsGitCredentialsManagerProvider.get()
            OS.UNKNOWN -> throw IllegalStateException("Unknown OS")
        }
    }
}