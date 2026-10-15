// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.domain.models.FileChanged
import dev.app.leaf.domain.models.FileType
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** FSEvents, inotify and the watch's batching all take their time. */
private val CHANGE_TIMEOUT = 10.seconds

class FileChangesWatcherTest {
    @TempDir
    lateinit var dir: File

    private val watchers = mutableListOf<FileChangesWatcher>()

    @AfterEach
    fun closeWatchers() {
        watchers.forEach { it.close() }
    }

    @Test
    fun `the watch loop leaves the collecting thread free, and still reports changes`(): Unit = runBlocking {
        val watcher = watcher()
        // Stands in for the tab's dispatcher, which has as few threads as the machine has cores
        val tabThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

        try {
            watcher.addPathToWatch(dir.absolutePath, false)
            val events = Channel<WatcherEvent>(Channel.UNLIMITED)

            val collecting = launch(tabThread) {
                watcher.observeEvents().collect { events.send(it) }
            }

            // Queued after the collector, so it only runs if the watch loop isn't holding the thread
            val otherWorkRan = CompletableDeferred<Unit>()
            val otherWork = launch(tabThread) { otherWorkRan.complete(Unit) }

            val ran = withTimeoutOrNull(5.seconds) { otherWorkRan.await() }

            // Before asserting: with the loop on the tab's thread, cancelling is the only way to free it
            if (ran == null) {
                collecting.cancelAndJoin()
                otherWork.join()
            }

            assertNotNull(ran, "The tab's other work never ran while the watcher looped")

            File(dir, "changed.txt").writeText("changed")

            events.awaitChange(File(dir, "changed.txt"))

            // Cancelling the collector stops the loop, which checks every 500 ms
            withTimeout(5.seconds) { collecting.cancelAndJoin() }
        } finally {
            tabThread.close()
        }
    }

    @Test
    fun `reports the changes made together in one batch`(): Unit = runBlocking {
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)
        val watch = watch(watcher)

        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")

        val event = watch.events.awaitChange(File(dir, "a.txt"))

        assertTrue(event.changes.any { it.path == File(dir, "b.txt").path }, "${event.changes}")
        watch.stop()
    }

    @Test
    fun `reports changes that keep coming at most every half a second`(): Unit = runBlocking {
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)
        val watch = watch(watcher)
        val file = File(dir, "busy.txt")

        val start = TimeSource.Monotonic.markNow()
        var count = 0
        while (start.elapsedNow() < 3.seconds) {
            file.writeText("${count++}")
            delay(50)
        }
        File(dir, "marker.txt").writeText("marker")
        val events = watch.events.awaitChanges(File(dir, "marker.txt"))

        // About one per 500 ms, and one more for what came last
        assertTrue(events.size in 2..10, "${events.size} events for $count changes")
        watch.stop()
    }

    @Test
    fun `stops reporting the changes in a folder removed from the watch`(): Unit = runBlocking {
        val subfolder = File(dir, "subfolder").apply { mkdir() }
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)
        watcher.addPathToWatch(subfolder.path, false)
        val watch = watch(watcher)

        File(subfolder, "watched.txt").writeText("1")
        watch.events.awaitChange(File(subfolder, "watched.txt"))

        watcher.removePathFromWatch(subfolder.path)
        File(subfolder, "unwatched.txt").writeText("2")
        File(dir, "marker.txt").writeText("marker")
        val events = watch.events.awaitChanges(File(dir, "marker.txt"))

        assertFalse(events.flatMap { it.changes }.any { it.path.endsWith("unwatched.txt") }, "$events")
        watch.stop()
    }

    @Test
    fun `reports a folder created in a watched folder as a folder, and a watched folder deleted as one too`(): Unit =
        runBlocking {
            val watcher = watcher()
            watcher.addPathToWatch(dir.absolutePath, false)
            val watch = watch(watcher)
            val folder = File(dir, "folder")

            folder.mkdir()
            val created = watch.events.awaitChange(folder).changes.single { it.path == folder.path }
            assertEquals(FileType.DIRECTORY, created.fileType)

            watcher.addPathToWatch(folder.path, false)
            folder.delete()
            val deleted = watch.events.awaitChange(folder).changes.single { it.path == folder.path }
            assertEquals(FileType.DIRECTORY, deleted.fileType)

            watch.stop()
        }

    @Test
    fun `doesn't report the changes in the subfolders of a folder watched on its own`(): Unit = runBlocking {
        val subfolder = File(dir, "subfolder").apply { mkdir() }
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)
        val watch = watch(watcher)

        File(subfolder, "inner.txt").writeText("inner")
        File(dir, "marker.txt").writeText("marker")

        val events = watch.events.awaitChanges(File(dir, "marker.txt"))

        assertFalse(events.flatMap { it.changes }.any { it.path.endsWith("inner.txt") }, "$events")
        watch.stop()
    }

    @Test
    fun `doesn't report a subfolder whose entries changed`(): Unit = runBlocking {
        // As .git in a working tree's root, which changes with every git command
        val gitDir = File(dir, ".git").apply { mkdir() }
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)
        val watch = watch(watcher)

        File(gitDir, "index.lock").writeText("lock")
        // The root's next change, when JBR lists it again and finds .git's new time
        File(dir, "first.txt").writeText("first")
        val events = watch.events.awaitChanges(File(dir, "first.txt"))

        assertFalse(events.flatMap { it.changes }.any { it.path == gitDir.path }, "$events")
        watch.stop()
    }

    @ParameterizedTest(name = "with FILE_TREE where the JDK has it: {0}")
    @ValueSource(booleans = [true, false])
    fun `watches everything in a recursive folder, the folders created after it too`(watchesTrees: Boolean): Unit =
        runBlocking {
            val refs = File(dir, "refs")
            val heads = File(refs, "heads").apply { mkdirs() }
            val origin = File(refs, "remotes/origin").apply { mkdirs() }
            val watcher = watcher(watchesTrees)
            watcher.addPathToWatch(refs.path, true)
            val watch = watch(watcher)

            File(heads, "main").writeText("1")
            watch.events.awaitChange(File(heads, "main"))

            File(origin, "main").writeText("1")
            watch.events.awaitChange(File(origin, "main"))

            val feature = File(heads, "feature")
            feature.mkdir()
            watch.events.awaitChange(feature)

            File(feature, "x").writeText("2")
            watch.events.awaitChange(File(feature, "x"))

            watch.stop()
        }

    @Test
    fun `a recursive folder added again on its own stays watched with its subfolders`(): Unit = runBlocking {
        val worktrees = File(dir, "worktrees")
        val agent = File(worktrees, "agent").apply { mkdirs() }
        val watcher = watcher()
        watcher.addPathToWatch(worktrees.path, true)
        // As the refresh does once git creates the folder: it's new, so it's watched like any new folder
        watcher.addPathToWatch(worktrees.path, false)
        val watch = watch(watcher)

        File(agent, "index").writeText("1")

        watch.events.awaitChange(File(agent, "index"))
        watch.stop()
    }

    @Test
    fun `reports lost changes when the system drops events`(): Unit = runBlocking {
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)

        // The JDK keeps 512 events per folder until they're taken, then drops the rest
        repeat(600) { File(dir, "file$it").writeText("$it") }
        delay(1.seconds)

        val watch = watch(watcher)
        val event = withTimeout(CHANGE_TIMEOUT) { watch.events.receive() }

        assertEquals(WatcherEvent.ChangesLost, event)
        watch.stop()
    }

    @Test
    fun `stops soon after it's cancelled, and closes`(): Unit = runBlocking {
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)
        val watch = watch(watcher)
        delay(100)

        val start = TimeSource.Monotonic.markNow()
        withTimeout(5.seconds) { watch.job.cancelAndJoin() }

        assertTrue(start.elapsedNow() < 1.seconds, "took ${start.elapsedNow()}")
        // Closed: nothing to watch with any more, and no error either
        watcher.addPathToWatch(dir.absolutePath, false)
        withTimeout(1.seconds) { watcher.observeEvents().collect {} }
    }

    @Test
    fun `closing it ends the watch at once`(): Unit = runBlocking {
        val watcher = watcher()
        watcher.addPathToWatch(dir.absolutePath, false)
        val watch = watch(watcher)
        delay(100)

        watcher.close()

        withTimeout(300.milliseconds) { watch.job.join() }
    }

    @Test
    fun `a file created and deleted before the batch is reported is left out, and one deleted and created isn't`() {
        val batch = ChangesBatch()

        batch.add("/repo/temporary", ENTRY_CREATE, isFolder = false)
        batch.add("/repo/temporary", ENTRY_MODIFY, isFolder = false)
        batch.add("/repo/temporary", ENTRY_DELETE, isFolder = false)
        batch.add("/repo/replaced", ENTRY_DELETE, isFolder = false)
        batch.add("/repo/replaced", ENTRY_CREATE, isFolder = false)

        assertEquals(
            WatcherEvent.ChangesDetected(listOf(FileChanged("/repo/replaced", FileType.FILE))),
            batch.take(),
        )
        assertEquals(null, batch.take())
    }

    @Test
    fun `a path that a folder had in the batch is reported as a folder`() {
        val batch = ChangesBatch()

        batch.add("/repo/folder", ENTRY_DELETE, isFolder = true)
        batch.add("/repo/folder", ENTRY_MODIFY, isFolder = false)

        assertEquals(
            WatcherEvent.ChangesDetected(listOf(FileChanged("/repo/folder", FileType.DIRECTORY))),
            batch.take(),
        )
    }

    @Test
    fun `lost changes are reported in place of the changes known, once`() {
        val batch = ChangesBatch()

        batch.add("/repo/file", ENTRY_MODIFY, isFolder = false)
        batch.changesLost = true

        assertEquals(WatcherEvent.ChangesLost, batch.take())
        assertEquals(null, batch.take())
    }

    private fun watcher(watchesTrees: Boolean = true) = FileChangesWatcher(watchesTrees).also { watchers += it }

    private class Watch(val events: Channel<WatcherEvent>, val job: Job) {
        /** Cancels the watch, which a broken watcher could otherwise leave running for good. */
        suspend fun stop() = withTimeout(5.seconds) { job.cancelAndJoin() }
    }

    private suspend fun CoroutineScope.watch(watcher: FileChangesWatcher): Watch {
        val events = Channel<WatcherEvent>(Channel.UNLIMITED)
        // Off the test's thread, so that a watch that blocks its collector fails the test rather than hanging it
        val job = launch(Dispatchers.Default) { watcher.observeEvents().collect { events.send(it) } }

        // FSEvents reports what changes after its stream started, a moment after the registration
        delay(300)

        return Watch(events, job)
    }

    /** The events up to the first that has a change of [file], which is the last. */
    private suspend fun Channel<WatcherEvent>.awaitChanges(file: File): List<WatcherEvent.ChangesDetected> {
        val events = mutableListOf<WatcherEvent.ChangesDetected>()

        withTimeout(CHANGE_TIMEOUT) {
            do {
                val event = receive()
                if (event is WatcherEvent.ChangesDetected) events += event
            } while (event !is WatcherEvent.ChangesDetected || event.changes.none { it.path == file.path })
        }

        return events
    }

    /** The first event that has a change of [file]. */
    private suspend fun Channel<WatcherEvent>.awaitChange(file: File): WatcherEvent.ChangesDetected =
        awaitChanges(file).last()
}
