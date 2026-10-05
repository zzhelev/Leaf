package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.FSWatchError
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import org.eclipse.jgit.lib.Repository

interface IFileChangesWatcher {
    fun addPathToWatch(path: String, isRecursive: Boolean)
    fun removePathFromWatch(path: String)

    suspend fun observeEvents(): Flow<WatcherEvent>

    fun close()
}