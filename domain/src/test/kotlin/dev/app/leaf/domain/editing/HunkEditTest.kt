// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editing

import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Editing a hunk's new side in place: which lines, and the file that typing gives (fork-only). */
class HunkEditTest {
    /** A hunk as the diff makes one: [lines] of the two versions, each a type and its two numbers, from 0. */
    private fun hunk(old: String, new: String, vararg lines: Triple<LineType, Int, Int>): Hunk {
        val oldText = RawText(old.toByteArray(Charsets.ISO_8859_1))
        val newText = RawText(new.toByteArray(Charsets.ISO_8859_1))

        return Hunk(
            header = "@@",
            lines = lines.map { (type, oldLine, newLine) ->
                val text = if (type == LineType.ADDED) newText.getString(newLine) else oldText.getString(oldLine)
                Line(text, oldLine, newLine, type)
            },
            oldText = oldText,
            newText = newText,
        )
    }

    private fun context(old: Int, new: Int) = Triple(LineType.CONTEXT, old, new)
    private fun added(old: Int, new: Int) = Triple(LineType.ADDED, old, new)
    private fun removed(old: Int, new: Int) = Triple(LineType.REMOVED, old, new)

    /** A file of four lines whose second line changed, as an Unstaged diff shows it. */
    private val old = "one\ntwo\nthree\nfour\n"
    private val new = "one\nTWO\nthree\nfour\n"
    private val changedSecond = hunk(old, new, context(0, 0), removed(1, 1), added(2, 1), context(2, 2), context(3, 3))

    private fun String.bytes() = toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `an edit starts from the new side's lines, context included, and knows the old side's`() {
        val edit = hunkEditOf("notes.txt", changedSecond)!!

        assertEquals(0, edit.start)
        assertEquals("one\nTWO\nthree\nfour", edit.text)
        assertEquals(listOf("one", "two", "three", "four"), edit.oldLines)
        assertEquals(Charsets.UTF_8, edit.charset)
    }

    @Test
    fun `typed lines take the new side's place, and the rest of the file stays`() {
        val file = "zero\n$new".bytes()
        val hunk = hunk("zero\n$old", "zero\n$new", context(1, 1), removed(2, 2), added(3, 2), context(3, 3))
        val edit = hunkEditOf("notes.txt", hunk)!!

        val edited = editedFile(file, edit, "one\nTwo, and a half\nthree")!!

        assertEquals("zero\none\nTwo, and a half\nthree\nfour\n", String(edited.bytes))
        assertEquals(2, edited.firstLine)
        assertEquals(3, edited.lineCount)
    }

    @Test
    fun `a file with CRLF keeps them, though the diff read LF`() {
        // core.autocrlf: the diff reads the file as git would store it, with LF
        val edit = hunkEditOf("notes.txt", changedSecond)!!
        val file = new.replace("\n", "\r\n").bytes()

        val edited = editedFile(file, edit, "one\nTWO\nthree and more\nfour")!!

        assertEquals("one\r\nTWO\r\nthree and more\r\nfour\r\n", String(edited.bytes))
    }

    @Test
    fun `a file whose diff has CRLF too keeps them`() {
        val crlfOld = old.replace("\n", "\r\n")
        val crlfNew = new.replace("\n", "\r\n")
        val hunk = hunk(crlfOld, crlfNew, context(0, 0), removed(1, 1), added(2, 1), context(2, 2), context(3, 3))
        val edit = hunkEditOf("notes.txt", hunk)!!

        assertEquals("one\nTWO\nthree\nfour", edit.text, "the field has no \\r")
        assertEquals("one\r\ntwo\r\nthree\r\nfour\r\n", String(editedFile(crlfNew.bytes(), edit, "one\ntwo\nthree\nfour")!!.bytes))
    }

    @Test
    fun `lines in Latin-1 stay Latin-1, byte for byte`() {
        val latin = "café\nnaïve\n"
        val hunk = hunk("cafe\nnaive\n", latin, removed(0, 0), removed(1, 0), added(2, 0), added(2, 1))
        val edit = hunkEditOf("notes.txt", hunk)!!

        assertEquals(Charsets.ISO_8859_1, edit.charset)
        assertEquals("café\nnaïve", edit.text)
        assertFalse(edit.canWrite("✓"), "no room for a check mark in Latin-1")

        val edited = editedFile(latin.bytes(), edit, "café\nnaïve, still")!!
        assertArrayEquals("café\nnaïve, still\n".bytes(), edited.bytes)
    }

    @Test
    fun `a file that ends without a line ending still does`() {
        val oldText = "one\ntwo"
        val newText = "one\nTWO"
        val hunk = hunk(oldText, newText, context(0, 0), removed(1, 1), added(2, 1))
        val edit = hunkEditOf("notes.txt", hunk)!!

        assertEquals("one\nTWO and three", String(editedFile(newText.bytes(), edit, "one\nTWO and three")!!.bytes))
        assertEquals("one\nTWO\nthree", String(editedFile(newText.bytes(), edit, "one\nTWO\nthree")!!.bytes))
    }

    @Test
    fun `a file that changed after the diff was read isn't written`() {
        val edit = hunkEditOf("notes.txt", changedSecond)!!

        assertNull(editedFile("one\nTWO, from an agent\nthree\nfour\n".bytes(), edit, "x"), "a line differs")
        assertNull(editedFile("one\nTWO\n".bytes(), edit, "x"), "the file is shorter")
    }

    @Test
    fun `deleting every line takes the lines out of the file`() {
        val file = "zero\n$new".bytes()
        val hunk = hunk("zero\n$old", "zero\n$new", context(1, 1), removed(2, 2), added(3, 2), context(3, 3))
        val edit = hunkEditOf("notes.txt", hunk)!!

        val edited = editedFile(file, edit, "")!!

        assertEquals("zero\nfour\n", String(edited.bytes))
        assertEquals(0, edited.lineCount)
    }

    @Test
    fun `a hunk with no new side inserts the typed lines where its lines went`() {
        // The whole file deleted: one removed line, and nothing on the new side
        val hunk = hunk("gone\n", "", removed(0, 0))
        val edit = hunkEditOf("notes.txt", hunk)!!

        assertEquals("", edit.text)
        assertEquals("back\n", String(editedFile(ByteArray(0), edit, "back")!!.bytes))
    }

    @Test
    fun `lines typed after a last line without a line ending give it one`() {
        // A hunk whose new side is the end of a file that has no line ending, as when the view shows the complete file
        val hunk = hunk("one\ntwo", "one\ntwo", context(0, 0), context(1, 1))
        val edit = hunkEditOf("notes.txt", hunk)!!.let { HunkEdit(it.path, 2, emptyList(), emptyList(), it.charset) }

        assertEquals("one\ntwo\nthree", String(editedFile("one\ntwo".bytes(), edit, "three")!!.bytes))
    }

    @Test
    fun `typed lines are marked where they differ from the old side`() {
        val edit = hunkEditOf("notes.txt", changedSecond)!!

        assertEquals(setOf(1), changedTypedLines(edit, edit.text), "the line the diff shows as added")
        assertEquals(setOf(1, 2), changedTypedLines(edit, "one\nTWO\nnew line\nthree\nfour"))
        assertEquals(emptySet<Int>(), changedTypedLines(edit, "one\ntwo\nthree\nfour"), "typed back as it was")
        assertTrue(edit.isUnchanged("one\r\nTWO\r\nthree\r\nfour"), "pasted CRLF is the same text")
    }

    @Test
    fun `a hunk longer than the field can take isn't edited`() {
        val text = (0..MAX_HUNK_EDIT_LINES).joinToString("") { "$it\n" }
        val lines = (0..MAX_HUNK_EDIT_LINES).map { context(it, it) }.toTypedArray()

        assertNull(hunkEditOf("long.txt", hunk(text, text, *lines)))
    }
}
