package dev.app.leaf.di

import dev.app.leaf.App
import dev.app.leaf.data.di.DatastoreModule
import dev.app.leaf.di.modules.*
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        ShellModule::class,
        NetworkModule::class,
        GitCredentialsManagerModule::class,
        RepositoriesModule::class,
        DatastoreModule::class,
        GitCliModule::class,
    ]
)
interface AppComponent {
    fun app(): App
    fun tabComponentFactory(): TabComponent.Factory
}
