// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import dev.app.leaf.domain.models.WatcherEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WorkingTreeWatchesTest {
    @TempDir
    lateinit var tempDir: File

    private val root get() = File(tempDir, "root")
    private val watcher = FakeWatcher { root }
    private val watches by lazy { WorkingTreeWatches(watcher, root, isLeftOut = { false }) }

    private fun folder(vararg parts: String) = parts.fold(root) { file, part -> File(file, part) }.apply { mkdirs() }

    private fun file(vararg parts: String) = parts.fold(root) { file, part -> File(file, part) }.apply {
        parentFile.mkdirs()
        writeText("content")
    }

    @Test
    fun `a working tree with nothing ignored is one registration`() {
        folder("src", "main")
        folder("docs")

        watches.watchAll(emptyList())

        assertEquals(listOf("./**"), watcher.watched)
    }

    @Test
    fun `a folder that becomes ignored inside a folder watched as a whole is left out`() {
        folder("src", "a")
        folder("src", "b")
        folder("src", "generated", "deep")
        folder("docs")
        watches.watchAll(emptyList())

        watches.onStatus(listOf("src/generated"))

        // The whole working tree was one registration, which can't leave a folder out
        assertEquals(listOf(".", "docs/**", "src", "src/a/**", "src/b/**"), watcher.watched)
    }

    @Test
    fun `a folder whose rule is removed is watched again, with one registration where it can`() {
        folder("src", "a")
        folder("src", "generated")
        folder("docs")
        watches.watchAll(listOf("src/generated"))
        assertEquals(listOf(".", "docs/**", "src", "src/a/**"), watcher.watched)

        watches.onStatus(emptyList())

        assertEquals(listOf("./**"), watcher.watched)
    }

    @Test
    fun `an ignored file changes nothing`() {
        folder("src")
        file("src", "debug.log")
        watches.watchAll(emptyList())
        val calls = watcher.calls.size

        watches.onStatus(listOf("src/debug.log"))
        // Then a file more, and one less
        file("src", "other.log")
        watches.onStatus(listOf("src/debug.log", "src/other.log"))
        watches.onStatus(emptyList())

        assertEquals(calls, watcher.calls.size)
        assertEquals(listOf("./**"), watcher.watched)
    }

    @Test
    fun `the same ignored folders plan nothing again`() {
        folder("src", "generated")
        folder("docs")
        watches.watchAll(listOf("src/generated"))
        val calls = watcher.calls.size

        watches.onStatus(listOf("src/generated"))
        watches.onStatus(listOf("src/generated"))

        assertEquals(calls, watcher.calls.size)
    }

    @Test
    fun `a folder that a build makes, in a folder watched as a whole, is left out once the status lists it`() {
        folder("src", "a")
        folder("docs")
        watches.watchAll(emptyList())
        assertEquals(listOf("./**"), watcher.watched)

        // The folder is created, and its folder is under a registration for everything: nothing to add
        folder("src", "target", "classes")
        val calls = watcher.calls.size
        watches.watchNew(File(root, "src/target"), emptyList())
        assertEquals(calls, watcher.calls.size)
        assertEquals(listOf("./**"), watcher.watched)

        watches.onStatus(listOf("src/target"))

        assertEquals(listOf(".", "docs/**", "src", "src/a/**"), watcher.watched)
    }

    @Test
    fun `a status that comes before the first plan is applied after it`() {
        folder("gen")
        folder("docs")

        // The first plan was made from a status read before this one
        watches.onStatus(listOf("gen"))
        assertEquals(emptyList<String>(), watcher.watched)

        watches.watchAll(emptyList())

        assertEquals(listOf(".", "docs/**"), watcher.watched)
    }

    @Test
    fun `a folder created next to ignored folders is watched, and a deleted one and the folders in it are not`() {
        folder("src", "generated")
        folder("src", "a")
        watches.watchAll(listOf("src/generated"))
        assertEquals(listOf(".", "src", "src/a/**"), watcher.watched)

        folder("src", "new", "deep")
        watches.watchNew(File(root, "src/new"), emptyList())
        assertEquals(listOf(".", "src", "src/a/**", "src/new/**"), watcher.watched)

        // Its parent's deletion is the only event, and the folders in it have no event of their own
        watches.unwatch(File(root, "src").path)

        assertEquals(listOf("."), watcher.watched)
    }

    @Test
    fun `lost changes watch a folder again that was deleted and made again`() {
        folder("src", "generated")
        folder("src", "a")
        watches.watchAll(listOf("src/generated"))

        // Its registration ended with the folder, which the watcher noticed
        watcher.lose(File(root, "src/a").path)
        assertEquals(listOf(".", "src"), watcher.watched)

        watches.watchAll(listOf("src/generated"))

        assertEquals(listOf(".", "src", "src/a/**"), watcher.watched)
    }

    @Test
    fun `the git folder and left out folders are never watched`() {
        folder(".git", "refs")
        folder("agent")
        folder("src")
        val left = WorkingTreeWatches(watcher, root, isLeftOut = { it.name == "agent" })

        left.watchAll(emptyList())

        assertEquals(listOf(".", "src/**"), watcher.watched)
    }
}

/**
 * A watcher that keeps what it's given as the app's does: a folder that is watched as a whole, or is under one, takes
 * nothing more, and removing a folder removes its own registration only.
 */
private class FakeWatcher(private val root: () -> File) : IFileChangesWatcher {
    private val registrations = LinkedHashMap<String, Boolean>()
    val calls = mutableListOf<String>()

    /** The registrations as paths in the working tree, `**` after those for everything under them, sorted. */
    val watched: List<String>
        get() = registrations
            .map { (path, isRecursive) ->
                File(path).relativeTo(root()).path.ifEmpty { "." } + if (isRecursive) "/**" else ""
            }
            .sorted()

    override fun addPathToWatch(path: String, isRecursive: Boolean) {
        calls += "add $path $isRecursive"

        // The folder itself too: it can't be watched alone again while it's watched as a whole
        val isCovered = registrations.any { (watched, wholly) ->
            wholly && File(path).toPath().startsWith(File(watched).toPath())
        }

        if (!isCovered) {
            registrations[path] = isRecursive
        }
    }

    override fun removePathFromWatch(path: String) {
        calls += "remove $path"
        registrations.remove(path)
    }

    /** The registration of [path] ended by itself, as when its folder is deleted. */
    fun lose(path: String) {
        registrations.remove(path)
    }

    override suspend fun observeEvents(): Flow<WatcherEvent> = emptyFlow()

    override fun close() = Unit
}
