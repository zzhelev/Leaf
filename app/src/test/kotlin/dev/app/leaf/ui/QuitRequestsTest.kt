// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Quitting while conflict resolvers have changes that aren't saved (fork-only). */
class QuitRequestsTest {
    private val quitRequests = QuitRequests()
    private val events = mutableListOf<String>()

    /** A file whose save waits until the test answers it, through [saved] or [failed]. */
    private inner class File(val path: String) {
        private var callbacks: Pair<() -> Unit, () -> Unit>? = null

        val unsaved = UnsavedResolverFile(
            path = path,
            tabName = MutableStateFlow("Leaf"),
            worktreeName = MutableStateFlow(null),
            save = { onSaved, onFailed ->
                events += "save $path"
                callbacks = onSaved to onFailed
            },
            show = { events += "show $path" },
        )

        fun saved() = checkNotNull(callbacks).first()

        fun failed() = checkNotNull(callbacks).second()
    }

    private fun request(vararg files: File, name: String = "") = quitRequests.request(
        unsaved = files.map { it.unsaved },
        quit = { events += "quit$name" },
        cancelled = { events += "cancelled$name" },
    )

    @Test
    fun `without changes that aren't saved, Leaf quits at once`() {
        request()

        assertEquals(listOf("quit"), events)
        assertNull(quitRequests.prompt.value)
    }

    @Test
    fun `with changes that aren't saved, Leaf asks first`() {
        val app = File("src/App.kt")
        val readme = File("README.md")

        request(app, readme)

        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("src/App.kt", "README.md"), quitRequests.prompt.value?.files?.map { it.path })
    }

    @Test
    fun `Cancel stays, and saves nothing`() {
        request(File("a.txt"))
        quitRequests.answer(null)

        assertEquals(listOf("cancelled"), events)
        assertNull(quitRequests.prompt.value)
    }

    @Test
    fun `Discard and quit quits without saving`() {
        request(File("a.txt"))
        quitRequests.answer(false)

        assertEquals(listOf("quit"), events)
        assertNull(quitRequests.prompt.value)
    }

    @Test
    fun `Save and quit saves the files one after another, then quits`() {
        val a = File("a.txt")
        val b = File("b.txt")

        request(a, b)
        quitRequests.answer(true)

        // The dialog closes while the first file is written
        assertNull(quitRequests.prompt.value)
        assertEquals(listOf("save a.txt"), events)

        a.saved()
        assertEquals(listOf("save a.txt", "save b.txt"), events)

        b.saved()
        assertEquals(listOf("save a.txt", "save b.txt", "quit"), events)
    }

    @Test
    fun `a save that fails shows its tab and stays, without saving the others`() {
        val a = File("a.txt")
        val b = File("b.txt")

        request(a, b)
        quitRequests.answer(true)
        a.failed()

        assertEquals(listOf("save a.txt", "show a.txt", "cancelled"), events)
    }

    @Test
    fun `a file saved after its failure, with Keep mine, doesn't go on with the quit`() {
        val a = File("a.txt")
        val b = File("b.txt")

        request(a, b)
        quitRequests.answer(true)
        a.failed()

        // The strip's Keep mine saves again, and calls the same callback once it's written
        a.saved()

        assertEquals(listOf("save a.txt", "show a.txt", "cancelled"), events)
    }

    @Test
    fun `a quit asked for while the dialog is open is left to its answer`() {
        request(File("a.txt"), name = " (Cmd+Q)")
        request(File("a.txt"), name = " (close button)")

        quitRequests.answer(false)

        assertEquals(listOf("quit (Cmd+Q)"), events)
    }

    @Test
    fun `a quit asked for while the files are saved is left to the saves`() {
        val a = File("a.txt")

        request(a, name = " (Cmd+Q)")
        quitRequests.answer(true)
        request(a, name = " (close button)")

        assertNull(quitRequests.prompt.value)

        a.saved()

        assertEquals(listOf("save a.txt", "quit (Cmd+Q)"), events)
    }

    @Test
    fun `a second answer is ignored`() {
        val a = File("a.txt")

        request(a)
        quitRequests.answer(true)
        quitRequests.answer(true)
        quitRequests.answer(null)

        assertEquals(listOf("save a.txt"), events)
    }

    @Test
    fun `after staying, the next quit asks again`() {
        request(File("a.txt"))
        quitRequests.answer(null)
        request(File("a.txt"))

        assertNotNull(quitRequests.prompt.value)

        quitRequests.answer(false)

        assertEquals(listOf("cancelled", "quit"), events)
    }
}
