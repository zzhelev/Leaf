// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.editing.EditedFile
import dev.app.leaf.domain.editing.HunkEdit
import dev.app.leaf.domain.editing.MAX_HUNK_EDIT_LINES
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.HunkEditError
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
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
import org.eclipse.jgit.diff.RawText
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

/** Editing a hunk in place: starting, typing, Done, Cancel, Esc and leaving (fork-only, `docs/fork/inline-editing.md`). */
class HunkEditControllerTest {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val shown = MutableStateFlow<ViewDiffResult?>(null)
    private val writes = mutableListOf<Pair<HunkEdit, String>>()
    private var nextResult: Either<EditedFile, AppError> = Either.Ok(EditedFile(ByteArray(0), 1, 1))
    private val editing = mutableListOf<Boolean>()

    private val controller = HunkEditController(
        scope = scope,
        diffResult = shown,
        write = { edit, typed ->
            writes += edit to typed
            CompletableDeferred(nextResult)
        },
        syntaxOf = { _, _ -> null },
        onEditing = { editing += it },
    )

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private val old = "one\ntwo\nthree\n"
    private val new = "one\nTWO\nthree\n"
    private val hunk = Hunk(
        header = "@@ -1,3 +1,3 @@",
        lines = listOf(
            Line("one", 0, 0, LineType.CONTEXT),
            Line("two", 1, 1, LineType.REMOVED),
            Line("TWO", 2, 1, LineType.ADDED),
            Line("three", 2, 2, LineType.CONTEXT),
        ),
        oldText = RawText(old.toByteArray()),
        newText = RawText(new.toByteArray()),
    )

    private fun diffOf(path: String = PATH, entryType: EntryType = EntryType.UNSTAGED, status: StatusType = StatusType.MODIFIED) =
        ViewDiffResult.Loaded(
            DiffType.UncommittedDiff(StatusEntry(path, status, entryType), entryType),
            DiffResult.Text(diffEntry(path), listOf(hunk)),
        )

    private fun diffEntry(path: String): DiffEntry = object : DiffEntry() {
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
        Thread.sleep(30)
    }

    private fun startEditing(): HunkEditState {
        val diff = diffOf()
        shown.value = diff
        controller.start(diff, hunk)
        return controller.state.value!!
    }

    private fun typed(text: String) = controller.type(TextFieldValue(text, TextRange(text.length)))

    @Test
    fun `starting takes the new side's lines, with the caret on the line asked for`() {
        val diff = diffOf()
        shown.value = diff
        controller.start(diff, hunk, caretLine = 2)

        val state = controller.state.value!!
        assertEquals("one\nTWO\nthree", state.value.text)
        assertEquals(TextRange(4), state.value.selection, "the start of line 2")
        assertEquals(setOf(1), state.changedLines)
        assertSame(diff, state.diff)
        assertEquals(listOf(true), editing, "Esc's views get the edit")
    }

    @Test
    fun `one hunk at a time`() {
        val first = startEditing()
        controller.start(diffOf(), hunk)

        assertSame(first, controller.state.value)
    }

    @Test
    fun `only an Unstaged diff of a changed or new file, not ignoring whitespace, can be edited`() {
        assertTrue(canEditHunk(diffOf().diffType, hunk))
        assertTrue(canEditHunk(diffOf(status = StatusType.ADDED).diffType, hunk), "a new file")
        assertFalse(canEditHunk(diffOf(entryType = EntryType.STAGED).diffType, hunk), "Staged is the index")
        assertFalse(canEditHunk(diffOf(status = StatusType.CONFLICTING).diffType, hunk), "the resolver edits those")
        assertFalse(canEditHunk(diffOf(status = StatusType.REMOVED).diffType, hunk), "nothing to edit")
        assertFalse(canEditHunk(diffOf().diffType, hunk.copy(ignoresWhitespace = true)), "its context isn't the file's")

        val long = (0..MAX_HUNK_EDIT_LINES).joinToString("") { "$it\n" }
        val longHunk = Hunk(
            "@@", (0..MAX_HUNK_EDIT_LINES).map { Line("$it", it, it, LineType.CONTEXT) },
            RawText(long.toByteArray()), RawText(long.toByteArray()),
        )
        assertFalse(canEditHunk(diffOf().diffType, longHunk), "too long for the field")
    }

    @Test
    fun `typing marks the lines that differ, and refuses what the file's charset can't hold`() {
        startEditing()

        typed("one\nTWO\nnew\nthree")
        assertEquals(setOf(1, 2), controller.state.value!!.changedLines)

        val latin = Hunk(
            hunk.header, hunk.lines, hunk.oldText,
            RawText("one\nTWÖ\nthree\n".toByteArray(Charsets.ISO_8859_1)),
        )
        controller.cancel()
        controller.start(diffOf(), latin)
        typed("one\nTWÖ ✓\nthree")

        assertEquals("one\nTWÖ\nthree", controller.state.value!!.value.text, "no check mark in Latin-1")
    }

    @Test
    fun `Done writes what was typed and ends`(): Unit = runBlocking {
        startEditing()
        typed("one\nTWO, and more\nthree")

        controller.done()
        settle()

        assertEquals(listOf("one\nTWO, and more\nthree"), writes.map { it.second })
        assertNull(controller.state.value)
        assertEquals(false, editing.last())
    }

    @Test
    fun `Done with nothing typed writes nothing`(): Unit = runBlocking {
        startEditing()

        controller.done()
        settle()

        assertTrue(writes.isEmpty())
        assertNull(controller.state.value)
    }

    @Test
    fun `a write that fails keeps the field open with what was typed`(): Unit = runBlocking {
        startEditing()
        typed("one\nmine\nthree")
        nextResult = Either.Err(HunkEditError.FileChanged(PATH))

        controller.done()
        settle()

        val state = requireNotNull(controller.state.value)
        assertEquals("one\nmine\nthree", state.value.text)
        assertFalse(state.isSaving)
    }

    @Test
    fun `Cancel leaves the file as it was`() {
        startEditing()
        typed("one\nmine\nthree")

        controller.cancel()

        assertNull(controller.state.value)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `Esc ends an edit with nothing typed, and asks about lines typed`() {
        startEditing()
        controller.escape()
        assertNull(controller.state.value, "nothing typed")

        startEditing()
        typed("one\nmine\nthree")
        controller.escape()
        assertEquals(HunkEditLeave(then = null, isEscape = true), controller.state.value!!.leave)

        controller.escape()
        assertNull(controller.state.value!!.leave, "Esc again keeps editing")
        assertEquals(true, editing.last(), "and Esc's views keep the edit")
    }

    @Test
    fun `moving on ends an edit with nothing typed, and asks about lines typed until the file is back`(): Unit =
        runBlocking {
            startEditing()
            shown.value = diffOf(path = "src/Other.kt")
            settle()
            assertNull(controller.state.value, "nothing typed")

            startEditing()
            typed("one\nmine\nthree")
            shown.value = diffOf(path = "src/Other.kt")
            settle()
            assertEquals(HunkEditLeave(then = null), controller.state.value!!.leave)

            shown.value = ViewDiffResult.Loading(diffOf().diffType)
            settle()
            assertNotNull(controller.state.value!!.leave, "loading says nothing yet")

            shown.value = diffOf()
            settle()
            assertNull(controller.state.value!!.leave, "back on the file: the question goes")
            assertEquals("one\nmine\nthree", controller.state.value!!.value.text)
        }

    @Test
    fun `leaving runs at once without lines typed, and after Save or Discard with them, not after Keep editing`(): Unit =
        runBlocking {
            var left = 0

            startEditing()
            controller.leaveThen { left++ }
            assertEquals(1, left)
            assertNull(controller.state.value)

            startEditing()
            typed("one\nmine\nthree")
            controller.leaveThen { left++ }
            assertEquals(1, left, "asks first")
            controller.keepEditing()
            assertEquals(1, left, "Keep editing doesn't leave")

            controller.leaveThen { left++ }
            controller.discardAndLeave()
            assertEquals(2, left)
            assertTrue(writes.isEmpty())

            startEditing()
            typed("one\nmine\nthree")
            controller.leaveThen { left++ }
            controller.saveAndLeave()
            withTimeout(2_000) { while (left < 3) settle() }
            assertEquals(listOf("one\nmine\nthree"), writes.map { it.second })
        }

    @Test
    fun `quitting saves lines typed, and stops when they can't be written`(): Unit = runBlocking {
        var saved = 0
        var failed = 0

        startEditing()
        assertNull(controller.unsavedFile)
        typed("one\nmine\nthree")
        assertEquals(PATH, controller.unsavedFile)
        assertTrue(controller.hasUnsavedChanges)

        nextResult = Either.Err(HunkEditError.FileChanged(PATH))
        controller.saveBeforeQuit(onSaved = { saved++ }, onFailed = { failed++ })
        settle()
        assertEquals(0 to 1, saved to failed)
        assertNotNull(controller.state.value, "still there to look at")

        nextResult = Either.Ok(EditedFile(ByteArray(0), 1, 3))
        controller.saveBeforeQuit(onSaved = { saved++ }, onFailed = { failed++ })
        settle()
        assertEquals(1 to 1, saved to failed)
        assertNull(controller.state.value)
    }

    private fun assertSame(expected: Any?, actual: Any?) = org.junit.jupiter.api.Assertions.assertSame(expected, actual)
}
