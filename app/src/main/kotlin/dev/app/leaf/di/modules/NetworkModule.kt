package dev.app.leaf.di.modules

import dagger.Provides
import dev.app.leaf.data.network.createHttpClient
import io.ktor.client.*

@dagger.Module
class NetworkModule {
    @Provides
    fun provideKtorHttpClient(): HttpClient = createHttpClient()
}
