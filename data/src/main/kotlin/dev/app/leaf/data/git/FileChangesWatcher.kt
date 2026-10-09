package dev.app.leaf.data.git

import dev.app.leaf.FileChanged
import dev.app.leaf.FileWatcher
import dev.app.leaf.WatchDirectoryNotifier
import dev.app.leaf.common.TabScope
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject

private const val TAG = "FileChangesWatcher"

@TabScope
class FileChangesWatcher @Inject constructor() : AutoCloseable, IFileChangesWatcher {
    private val fileWatcher = FileWatcher()
    private var shouldKeepLooping = true

    /**
     * Fork-only: [FileWatcher.watch] blocks its thread for as long as the tab is open, so it runs on a thread of its
     * own. On the tab's dispatcher (`Dispatchers.Default`, one thread per core) each loaded tab held one, and as many
     * tabs as cores stalled everything else there. A view of `Dispatchers.IO` doesn't count against IO's 64 threads,
     * so the watchers don't take those either.
     */
    private val watchDispatcher = Dispatchers.IO.limitedParallelism(1)

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
    }.flowOn(watchDispatcher)


    override fun close() {
        shouldKeepLooping = false
        fileWatcher.close()
    }
}
