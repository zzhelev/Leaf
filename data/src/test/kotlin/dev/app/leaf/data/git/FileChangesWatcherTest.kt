// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/** The Rust library that the app build puts in the app's resources. */
private fun builtLeafLibrary(): File? = File("../app/src/main/resources", System.mapLibraryName("leaf_rs"))
    .takeIf { it.isFile }

class FileChangesWatcherTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the watch loop leaves the collecting thread free, and still reports changes`(): Unit = runBlocking {
        val library = builtLeafLibrary()
        assumeTrue(library != null, "Needs the Rust library, built by any app build (./gradlew :app:rustTasks)")
        System.setProperty("uniffi.component.leaf_rs.libraryOverride", checkNotNull(library).absolutePath)

        val watcher = FileChangesWatcher()
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

            // FSEvents, inotify and the loop's batching all take their time
            withTimeout(15.seconds) {
                do {
                    val event = events.receive()
                } while (event !is WatcherEvent.ChangesDetected || event.changes.none { it.path.endsWith("changed.txt") })
            }

            // Cancelling the collector stops the loop, which checks every 500 ms
            withTimeout(5.seconds) { collecting.cancelAndJoin() }
        } finally {
            watcher.close()
            tabThread.close()
        }
    }
}
