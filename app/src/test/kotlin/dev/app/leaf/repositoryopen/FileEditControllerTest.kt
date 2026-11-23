// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.editing.ChangeKind
import dev.app.leaf.domain.editing.FileEdit
import dev.app.leaf.domain.editing.FileForEdit
import dev.app.leaf.domain.editing.HunkEdit
import dev.app.leaf.domain.editing.savedBytes
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.HunkEditError
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ViewDiffResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val PATH = "src/Parser.kt"

/** The whole file's editor: opening, typing, Save, Back, Put it back and leaving (fork-only, B2). */
class FileEditControllerTest {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val shown = MutableStateFlow<ViewDiffResult?>(null)
    private val index = "one\ntwo\nthree\nfour"
    private var onDisk = "one\nTWO\nthree\nfour\n".toByteArray()
    private val writes = mutableListOf<String>()
    private var nextWriteFails = false
    private val editing = mutableListOf<Boolean>()

    private val controller = FileEditController(
        scope = scope,
        diffResult = shown,
        read = { CompletableDeferred(Either.Ok(FileForEdit(onDisk, index))) },
        write = { edit, typed ->
            writes += typed
            val result: Either<ByteArray, AppError> = if (nextWriteFails) {
                Either.Err(HunkEditError.FileChanged(edit.path))
            } else {
                Either.Ok(savedBytes(edit, typed).also { onDisk = it })
            }
            CompletableDeferred(result)
        },
        highlight = { _, _ -> null as HighlightedText? },
        onEditing = { editing += it },
    )

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private val diffType = DiffType.UncommittedDiff(StatusEntry(PATH, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED)

    private fun diffOf(path: String = PATH) = ViewDiffResult.Loaded(
        DiffType.UncommittedDiff(StatusEntry(path, StatusType.MODIFIED, EntryType.UNSTAGED), EntryType.UNSTAGED),
        DiffResult.Text(entry(path), emptyList()),
    )

    private fun entry(path: String): DiffEntry = object : DiffEntry() {
        init {
            changeType = ChangeType.MODIFY
            oldPath = path
            newPath = path
            oldId = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())
            newId = AbbreviatedObjectId.fromObjectId(ObjectId.zeroId())
        }
    }

    private suspend fun settle() {
        repeat(20) { yield() }
        Thread.sleep(40)
    }

    private suspend fun opened(caretLine: Int? = null, carried: Pair<HunkEdit, String>? = null): FileEditState {
        shown.value = diffOf()
        controller.open(diffType, caretLine, carried)
        withTimeout(2_000) { while (controller.state.value == null) settle() }
        return controller.state.value!!
    }

    private fun typed(text: String) = controller.type(TextFieldValue(text, TextRange(text.length)))

    @Test
    fun `opening reads the file, with its markers against the index and the caret on the line asked for`(): Unit =
        runBlocking {
            val state = opened(caretLine = 3)

            assertEquals("one\nTWO\nthree\nfour", state.value.text)
            assertEquals(TextRange(8), state.value.selection, "the start of line 3")
            assertEquals(listOf(ChangeKind.CHANGED to 1), state.markers.map { it.kind to it.start })
            assertFalse(state.hasUnsavedChanges)
            assertEquals(listOf(true), editing)
        }

    @Test
    fun `only an Unstaged file, modified or new, opens`() {
        assertTrue(canEditWholeFile(diffType))
        assertTrue(canEditWholeFile(diffType.copy(statusEntry = StatusEntry(PATH, StatusType.ADDED, EntryType.UNSTAGED))))
        assertFalse(canEditWholeFile(DiffType.UncommittedDiff(StatusEntry(PATH, StatusType.MODIFIED, EntryType.STAGED), EntryType.STAGED)))
        assertFalse(canEditWholeFile(diffType.copy(statusEntry = StatusEntry(PATH, StatusType.CONFLICTING, EntryType.UNSTAGED))))
        assertFalse(canEditWholeFile(diffType.copy(statusEntry = StatusEntry(PATH, StatusType.REMOVED, EntryType.UNSTAGED))))
        assertFalse(canEditWholeFile(null))
    }

    @Test
    fun `typing updates the markers, and Save writes and stays`(): Unit = runBlocking {
        opened()
        typed("one\nTWO\nthree\nfour\nfive")
        withTimeout(2_000) { while (controller.state.value!!.markers.size < 2) settle() }
        assertEquals(listOf(ChangeKind.CHANGED, ChangeKind.ADDED), controller.state.value!!.markers.map { it.kind })
        assertTrue(controller.state.value!!.hasUnsavedChanges)

        controller.save()
        settle()

        val state = assertNotNull(controller.state.value).let { controller.state.value!! }
        assertEquals(listOf("one\nTWO\nthree\nfour\nfive"), writes)
        assertFalse(state.hasUnsavedChanges, "the file is what was typed now")
        assertEquals("one\nTWO\nthree\nfour\nfive", state.value.text)
    }

    @Test
    fun `a Save that fails keeps what was typed`(): Unit = runBlocking {
        opened()
        typed("one\nmine")
        nextWriteFails = true

        controller.save()
        settle()

        val state = controller.state.value!!
        assertTrue(state.hasUnsavedChanges)
        assertFalse(state.isSaving)
    }

    @Test
    fun `Back closes without lines unsaved, asks with them, and Back again keeps editing`(): Unit = runBlocking {
        opened()
        controller.back()
        assertNull(controller.state.value)

        opened()
        typed("one\nmine")
        controller.back()
        assertEquals(HunkEditLeave(then = null, isEscape = true), controller.state.value!!.leave)

        controller.back()
        assertNull(controller.state.value!!.leave)

        controller.back()
        controller.discardAndLeave()
        assertNull(controller.state.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `the shortcut saves and closes`(): Unit = runBlocking {
        opened()
        typed("one\nmine")

        controller.saveAndClose()
        withTimeout(2_000) { while (controller.state.value != null) settle() }

        assertEquals(listOf("one\nmine"), writes)
    }

    @Test
    fun `Put it back gives the index's lines, unsaved, and an open marker closes first on Back`(): Unit = runBlocking {
        val changed = opened().markers.single()

        controller.showMarker(changed)
        assertEquals(changed, controller.state.value!!.openMarker)
        controller.back()
        assertNull(controller.state.value!!.openMarker, "Back closes the marker first")
        assertNotNull(controller.state.value)

        controller.putBack(changed)
        assertEquals("one\ntwo\nthree\nfour", controller.state.value!!.value.text)
        assertTrue(controller.state.value!!.hasUnsavedChanges)
    }

    @Test
    fun `moving on closes without lines unsaved, and asks with them until the file is back`(): Unit = runBlocking {
        opened()
        shown.value = diffOf("src/Other.kt")
        settle()
        assertNull(controller.state.value)

        opened()
        typed("one\nmine")
        shown.value = diffOf("src/Other.kt")
        settle()
        assertEquals(HunkEditLeave(then = null), controller.state.value!!.leave)

        shown.value = diffOf()
        settle()
        assertNull(controller.state.value!!.leave)
    }

    @Test
    fun `leaving and quitting save or ask, as a hunk edit does`(): Unit = runBlocking {
        var left = 0

        opened()
        typed("one\nmine")
        controller.leaveThen { left++ }
        assertEquals(0, left)
        controller.saveAndLeave()
        withTimeout(2_000) { while (left < 1) settle() }
        assertNull(controller.state.value)

        opened()
        typed("one\nagain")
        assertEquals(PATH, controller.unsavedFile)
        var saved = false
        controller.saveBeforeQuit(onSaved = { saved = true }, onFailed = {})
        withTimeout(2_000) { while (!saved) settle() }
        assertEquals(listOf("one\nmine", "one\nagain"), writes)
    }

    @Test
    fun `a hunk's lines typed are carried over, unsaved`(): Unit = runBlocking {
        val hunk = HunkEdit(PATH, start = 1, lines = listOf("TWO".toByteArray()), oldLines = listOf("two"), charset = Charsets.UTF_8)

        val state = opened(carried = hunk to "TWO, typed in the hunk")

        assertEquals("one\nTWO, typed in the hunk\nthree\nfour", state.value.text)
        assertTrue(state.hasUnsavedChanges)
        assertTrue(writes.isEmpty(), "nothing saved")
    }

    @Suppress("unused")
    private fun FileEdit.textOf() = text
}
