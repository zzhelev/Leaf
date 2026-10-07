package dev.app.leaf.di.modules

import dev.app.leaf.data.services.GitProviderService
import dev.app.leaf.domain.services.IGitProviderService
import dagger.Binds
import dagger.Module

@Module
interface ServicesModule {
    @Binds
    fun bindIGitProviderService(service: GitProviderService): IGitProviderService

}