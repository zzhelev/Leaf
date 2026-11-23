// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editing

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Editing a working file whole: the text, the bytes a Save writes, markers, Put it back and occurrences (fork-only). */
class FileEditTest {
    private fun edit(content: String) = fileEditOf("Notes.kt", content.toByteArray(Charsets.ISO_8859_1))!!

    private fun saved(content: String, typed: String) = String(savedBytes(edit(content), typed), Charsets.ISO_8859_1)

    @Test
    fun `the text is the file's lines, without their line endings`() {
        assertEquals("one\ntwo", edit("one\ntwo\n").text)
        assertEquals("one\ntwo", edit("one\r\ntwo\r\n").text)
        assertEquals("one\ntwo", edit("one\ntwo").text)
        assertEquals("", edit("").text)
        assertTrue(edit("one\ntwo\n").isUnchanged("one\r\ntwo"), "pasted CRLF is the same text")
    }

    @Test
    fun `saving the text unchanged gives the file back byte for byte, whatever its line endings`() {
        for (content in listOf("one\ntwo\n", "one\r\ntwo\r\n", "one\r\ntwo\nthree", "a\n\n\nb\n", "", "only")) {
            assertEquals(content, saved(content, edit(content).text), content)
        }
    }

    @Test
    fun `lines that didn't change keep their own line endings, and typed lines take the file's`() {
        // A file with mixed line endings: the first line's is the file's
        assertEquals("one\r\nnew\r\ntwo\nthree\r\n", saved("one\r\ntwo\nthree\r\n", "one\nnew\ntwo\nthree"))
        assertEquals("ONE\r\ntwo\nthree\r\n", saved("one\r\ntwo\nthree\r\n", "ONE\ntwo\nthree"))
    }

    @Test
    fun `a file without a last line ending stays without one, unless lines come after its last line`() {
        assertEquals("one\nTWO", saved("one\ntwo", "one\nTWO"))
        assertEquals("one\ntwo\nthree", saved("one\ntwo", "one\ntwo\nthree"))
        assertEquals("one\ntwo\nthree\n", saved("one\ntwo\n", "one\ntwo\nthree"))
    }

    @Test
    fun `deleting every line empties the file`() {
        assertEquals("", saved("one\ntwo\n", ""))
    }

    @Test
    fun `a file in Latin-1 keeps its bytes`() {
        val content = "café\nnaïve\n"
        val edit = edit(content)

        assertEquals(Charsets.ISO_8859_1, edit.charset)
        assertFalse(edit.canWrite("✓"))
        assertArrayEquals(
            "café\nnaïve!\n".toByteArray(Charsets.ISO_8859_1),
            savedBytes(edit, "café\nnaïve!"),
        )
    }

    @Test
    fun `a file longer than the field can take isn't edited`() {
        val long = (0..MAX_FILE_EDIT_LINES).joinToString("") { "$it\n" }

        assertNull(fileEditOf("Long.kt", long.toByteArray()))
    }

    @Test
    fun `markers say which lines were added, changed and removed against the index`() {
        val index = "one\ntwo\nthree\nfour\nfive"
        val typed = "one\nTWO\nthree\nfive\nsix"

        assertEquals(
            listOf(
                ChangeMarker(ChangeKind.CHANGED, 1, 2, listOf("two")),
                ChangeMarker(ChangeKind.REMOVED, 3, 3, listOf("four")),
                ChangeMarker(ChangeKind.ADDED, 4, 5, emptyList()),
            ),
            changeMarkers(index, typed),
        )
        assertEquals(emptyList<ChangeMarker>(), changeMarkers(index, index))
        assertEquals(listOf(ChangeMarker(ChangeKind.ADDED, 0, 2, emptyList())), changeMarkers("", "a\nb"), "a new file")
        assertEquals(emptyList<ChangeMarker>(), changeMarkers(null, "a"), "nothing to compare")
    }

    @Test
    fun `putting a change back gives the index's lines in its place`() {
        val index = "one\ntwo\nthree\nfour\nfive"
        val typed = "one\nTWO\nthree\nfive\nsix"
        val (changed, removed, added) = changeMarkers(index, typed)

        assertEquals("one\ntwo\nthree\nfive\nsix", puttingBack(typed, changed))
        assertEquals("one\nTWO\nthree\nfour\nfive\nsix", puttingBack(typed, removed))
        assertEquals("one\nTWO\nthree\nfive", puttingBack(typed, added))
    }

    @Test
    fun `the name at the caret is found wherever it's a whole word`() {
        val text = "val backup = moveAside(file)\nbackup.delete()\nbackups.clear()\n// backup"
        val at = text.indexOf("backup") + 2

        assertEquals(listOf(4..9, 29..34, 64..69), occurrencesAt(text, at), "not in backups")
        assertEquals(occurrencesAt(text, at), occurrencesAt(text, text.indexOf("backup") + 6), "at the name's end too")
        assertEquals(emptyList<IntRange>(), occurrencesAt(text, text.indexOf("=")), "not on a name")
        assertEquals(emptyList<IntRange>(), occurrencesAt("a = a + a", 0), "one character")
        assertEquals(emptyList<IntRange>(), occurrencesAt("x = 42 + 42", 5), "a number")
    }
}
