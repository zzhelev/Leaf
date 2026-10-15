package dev.app.leaf.data.git

import com.sun.nio.file.ExtendedWatchEventModifier
import dev.app.leaf.common.printDebug
import dev.app.leaf.common.printError
import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.models.FileChanged
import dev.app.leaf.domain.models.FileType
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "FileChangesWatcher"

/**
 * How long the watch waits for more changes before it reports the ones it has, and how often it reports changes that
 * keep coming. Also how soon it notices that it was cancelled.
 */
private const val BATCH_TIME_MS = 500L

private val WATCHED_KINDS = arrayOf(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)

/**
 * Watches folders for one watch of a tab's worktree. Not tab-scoped (fork-only): closing it closes its watch service,
 * so a tab that switches to another worktree watches it with a new one.
 *
 * Fork-only: the JDK's [WatchService] took the place of the Rust watcher (notify). JetBrains' JDK, which runs Leaf,
 * watches with FSEvents on macOS (`MacOSXWatchService`), and other JDKs poll there. Linux uses inotify, Windows
 * ReadDirectoryChangesW.
 *
 * @param watchesTrees whether a recursive folder is watched with one registration
 * ([ExtendedWatchEventModifier.FILE_TREE]) where the JDK can, as on macOS and Windows. Elsewhere, as on Linux, and
 * always when false (for tests), each folder in it has a registration of its own, and the watch adds one for each
 * folder created there.
 */
class FileChangesWatcher internal constructor(private val watchesTrees: Boolean) : AutoCloseable, IFileChangesWatcher {
    @Inject
    constructor() : this(watchesTrees = true)

    private val watchService: WatchService? = try {
        FileSystems.getDefault().newWatchService()
    } catch (e: IOException) {
        // Such as Linux's limit of inotify instances
        printError(TAG, "Couldn't start watching files: ${e.message}", e)
        null
    }

    /** The key of each folder watched. */
    private val keys = ConcurrentHashMap<Path, WatchKey>()

    /** The keys of the folders in a recursive folder that has a registration per folder, whose new folders it adds. */
    private val treeFolderKeys: MutableSet<WatchKey> = ConcurrentHashMap.newKeySet()

    /** The recursive folders that have one registration for everything under them. */
    private val fileTrees: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    /**
     * Fork-only: the watch blocks its thread for as long as it runs, so it runs on a thread of its own. On the tab's
     * dispatcher (`Dispatchers.Default`, one thread per core) each loaded tab held one, and as many tabs as cores
     * stalled everything else there. A view of `Dispatchers.IO` doesn't count against IO's 64 threads, so the watchers
     * don't take those either.
     */
    private val watchDispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun addPathToWatch(path: String, isRecursive: Boolean) {
        val service = watchService ?: return
        val folder = Path.of(path)

        try {
            when {
                // Registering it again would watch it without its subfolders
                fileTrees.any { folder.startsWith(it) } -> Unit
                !isRecursive -> register(service, folder, isInTree = false)
                watchesTrees && registerTree(service, folder) -> Unit
                else -> registerFolders(service, folder)
            }
        } catch (e: NoSuchFileException) {
            // Such as the modules folder of a repository without submodules
            printDebug(TAG, "Not watching $path, which doesn't exist")
        } catch (e: IOException) {
            // Such as Linux's limit of inotify watches
            printError(TAG, "Couldn't watch $path: ${e.message}", e)
        } catch (e: ClosedWatchServiceException) {
            // The watch has ended
        }
    }

    override fun removePathFromWatch(path: String) {
        val folder = Path.of(path)
        val key = keys.remove(folder) ?: return
        key.cancel()
        fileTrees.remove(folder)

        // The folders that the watch added under it
        if (treeFolderKeys.remove(key)) {
            keys.entries.removeIf { (otherFolder, otherKey) ->
                val isUnder = otherFolder.startsWith(folder) && otherKey in treeFolderKeys
                if (isUnder) {
                    otherKey.cancel()
                    treeFolderKeys.remove(otherKey)
                }
                isUnder
            }
        }
    }

    override suspend fun observeEvents(): Flow<WatcherEvent> = flow {
        val service = watchService

        if (service == null) {
            emit(WatcherEvent.WatchInitError("Couldn't start watching files"))
            return@flow
        }

        printDebug(TAG, "Watching with ${service.javaClass.name}")

        val batch = ChangesBatch()
        var lastReport = System.nanoTime()

        try {
            while (currentCoroutineContext().isActive) {
                val key = try {
                    service.poll(BATCH_TIME_MS, TimeUnit.MILLISECONDS)
                } catch (e: ClosedWatchServiceException) {
                    break
                }

                if (key != null) {
                    takeEvents(service, key, batch)
                }

                // Once changes stop for a moment, or at most every BATCH_TIME_MS while they keep coming
                val now = System.nanoTime()
                if (key == null || now - lastReport >= TimeUnit.MILLISECONDS.toNanos(BATCH_TIME_MS)) {
                    batch.take()?.let { emit(it) }
                    lastReport = now
                }
            }
        } finally {
            close()
        }
    }.flowOn(watchDispatcher)

    override fun close() {
        try {
            watchService?.close()
        } catch (e: IOException) {
            printError(TAG, "Couldn't stop watching files: ${e.message}", e)
        }
    }

    private fun register(
        service: WatchService,
        folder: Path,
        isInTree: Boolean,
        vararg modifiers: WatchEvent.Modifier,
    ) {
        // The same folder gets the same key, which keeps adding new folders once it was in a tree
        val key = folder.register(service, WATCHED_KINDS, *modifiers)
        keys[folder] = key

        if (isInTree) {
            treeFolderKeys.add(key)
        }
    }

    /** Watches [folder] and everything under it with one registration, where the JDK can. */
    private fun registerTree(service: WatchService, folder: Path): Boolean = try {
        register(service, folder, isInTree = false, ExtendedWatchEventModifier.FILE_TREE)
        fileTrees.add(folder)
        true
    } catch (e: UnsupportedOperationException) {
        false
    }

    /** Watches [root] and every folder under it, each with a registration of its own. Doesn't follow links. */
    private fun registerFolders(service: WatchService, root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = try {
                register(service, dir, isInTree = true)
                FileVisitResult.CONTINUE
            } catch (e: NoSuchFileException) {
                // Deleted meanwhile
                FileVisitResult.SKIP_SUBTREE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (file == root) throw exc
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun takeEvents(service: WatchService, key: WatchKey, batch: ChangesBatch) {
        val folder = key.watchable() as Path
        val addsNewFolders = key in treeFolderKeys

        for (event in key.pollEvents()) {
            val kind = event.kind()

            if (kind == OVERFLOW) {
                batch.changesLost = true

                // Folders created there whose events were lost
                if (addsNewFolders) {
                    addFolders(service, folder)
                }

                continue
            }

            val path = folder.resolve(event.context() as Path)

            // A folder's time changes with its entries, which JBR reports when it lists the folder that has it, as
            // Windows does. What changed in it comes from its own registration, if it has one: `.git`'s would refresh
            // everything at the next change in the working tree's root.
            if (kind == ENTRY_MODIFY && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                continue
            }

            val isFolder = when (kind) {
                ENTRY_CREATE -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                ENTRY_DELETE -> keys.containsKey(path)
                else -> false
            }

            batch.add(path.toString(), kind, isFolder)

            if (kind == ENTRY_CREATE && isFolder && addsNewFolders) {
                addFolders(service, path)
            }
        }

        if (!key.reset()) {
            keys.remove(folder, key)
            treeFolderKeys.remove(key)
            fileTrees.remove(folder)

            // The folder itself was deleted, which its parent may not report: it may not be watched
            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                batch.add(folder.toString(), ENTRY_DELETE, isFolder = true)
            }
        }
    }

    /** Watches a folder created in a tree, and the folders already in it. Its files written meanwhile go unreported. */
    private fun addFolders(service: WatchService, folder: Path) {
        try {
            registerFolders(service, folder)
        } catch (e: NoSuchFileException) {
            // Deleted meanwhile
        } catch (e: IOException) {
            printError(TAG, "Couldn't watch $folder: ${e.message}", e)
        } catch (e: ClosedWatchServiceException) {
            // The watch has ended
        }
    }
}

/** The changes that the watch reports together. */
internal class ChangesBatch {
    private val changes = LinkedHashMap<String, PathChanges>()

    /** The system dropped events: what changed isn't known. */
    var changesLost = false

    fun add(path: String, kind: WatchEvent.Kind<*>, isFolder: Boolean) {
        val pathChanges = changes.getOrPut(path) { PathChanges() }
        pathChanges.kinds += kind
        pathChanges.isFolder = pathChanges.isFolder || isFolder
    }

    /** What to report, or null when nothing changed. Empties the batch. */
    fun take(): WatcherEvent? {
        val event = when {
            changesLost -> WatcherEvent.ChangesLost
            else -> changes
                .filterValues { !it.isTemporary }
                .map { (path, pathChanges) ->
                    FileChanged(path, if (pathChanges.isFolder) FileType.DIRECTORY else FileType.FILE)
                }
                .takeIf { it.isNotEmpty() }
                ?.let { WatcherEvent.ChangesDetected(it) }
        }

        changes.clear()
        changesLost = false

        return event
    }
}

private class PathChanges {
    val kinds = mutableListOf<WatchEvent.Kind<*>>()
    var isFolder = false

    /**
     * Created, then deleted before the batch was reported, such as a temporary file. Deleted, then created again is a
     * change.
     */
    val isTemporary: Boolean
        get() {
            val created = kinds.indexOf(ENTRY_CREATE)
            val deleted = kinds.indexOf(ENTRY_DELETE)

            return created != -1 && deleted != -1 && created < deleted
        }
}
