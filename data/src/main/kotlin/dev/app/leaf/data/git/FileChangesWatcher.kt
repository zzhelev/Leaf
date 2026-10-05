package dev.app.leaf.data.git

import dev.app.leaf.FileChanged
import dev.app.leaf.FileWatcher
import dev.app.leaf.WatchDirectoryNotifier
import dev.app.leaf.common.TabScope
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import javax.inject.Inject

private const val TAG = "FileChangesWatcher"

@TabScope
class FileChangesWatcher @Inject constructor() : AutoCloseable, IFileChangesWatcher {
    private val fileWatcher = FileWatcher()
    private var shouldKeepLooping = true

    init {
        // TODO add error handling
        fileWatcher.init()
    }

    override fun addPathToWatch(path: String, isRecursive: Boolean) {
        fileWatcher.addWatch(path, isRecursive)
    }

    override fun removePathFromWatch(path: String) {
        fileWatcher.removeWatch(path)
    }

    override suspend fun observeEvents(): Flow<WatcherEvent> = callbackFlow {
        fileWatcher.watch(
            notifier = object : WatchDirectoryNotifier {
                override fun shouldKeepLooping(): Boolean = coroutineContext.isActive && shouldKeepLooping
                override fun detectedChange(paths: List<FileChanged>) {
                    trySendBlocking(WatcherEvent.ChangesDetected(paths))
                }

                override fun onError(code: Int) {
                    trySendBlocking(WatcherEvent.WatchInitError(code))
                }
            }
        )

        awaitClose { fileWatcher.close() }
    }


    override fun close() {
        shouldKeepLooping = false
        fileWatcher.close()
    }
}
