package dev.app.leaf.di.modules

import dev.app.leaf.common.TabScope
import dev.app.leaf.data.repositories.InMemoryRepositoryDataRepository
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dagger.Binds
import dagger.Module

@Module
interface TabRepositoriesModule {
    @TabScope
    @Binds
    fun statusRepository(repository: InMemoryRepositoryDataRepository): RepositoryDataRepository

    @TabScope
    @Binds
    fun repositoryStateRepository(repository: InMemoryRepositoryStateRepository): RepositoryStateRepository
}