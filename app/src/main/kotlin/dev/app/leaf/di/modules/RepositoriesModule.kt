package dev.app.leaf.di.modules

import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.InMemoryNotificationsRepository
import dev.app.leaf.data.repositories.JvmSystemProxyRepository
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.data.repositories.configuration.DataStoreAppSettingsRepository
import dev.app.leaf.domain.repositories.CredentialsRepository
import dev.app.leaf.domain.repositories.LfsRepository
import dev.app.leaf.domain.repositories.NotificationsRepository
import dev.app.leaf.domain.repositories.SystemProxyRepository
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
interface RepositoriesModule {
    @Binds
    fun notificationsRepository(repository: InMemoryNotificationsRepository): NotificationsRepository

    @Binds
    fun systemProxyRepository(repository: JvmSystemProxyRepository): SystemProxyRepository

    @Singleton
    @Binds
    fun appSettingsRepository(repository: DataStoreAppSettingsRepository): AppSettingsRepository

    @Singleton
    @Binds
    fun credentialsRepository(repository: CredentialsCacheRepository): CredentialsRepository

    @Binds
    fun lfsRepository(repository: NetworkLfsRepository): LfsRepository
}