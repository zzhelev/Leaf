package dev.app.leaf.di.modules

import dev.app.leaf.data.git.FileChangesWatcher
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dagger.Binds
import dagger.Module

@Module
interface FileWatcherModule {
    @Binds
    fun bindFileWatcher(watcher: FileChangesWatcher) : IFileChangesWatcher
}