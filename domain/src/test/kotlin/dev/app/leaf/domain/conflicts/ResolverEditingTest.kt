// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Editing a part of the resolver's result: the parts, what editing starts from, and how typed lines are written. */
class ResolverEditingTest {
    // base: a b c d      ours: a B c d      theirs: a b c x      (ours changed b, theirs changed d)
    private val changes = TextMerge(
        text("a", "b", "c", "d"),
        text("a", "B", "c", "d"),
        text("a", "b", "c", "x"),
        listOf(
            Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
            Chunk.Change(lines(1, 2), lines(1, 2), lines(1, 2), ChangedBy.OURS),
            Chunk.Unchanged(lines(2, 3), lines(2, 3), lines(2, 3)),
            Chunk.Change(lines(3, 4), lines(3, 4), lines(3, 4), ChangedBy.THEIRS),
        ),
    )

    // base: 1 2 3     ours: 1 O O 3     theirs: 1 T 3
    private val conflict = TextMerge(
        text("1", "2", "3"),
        text("1", "O", "O", "3"),
        text("1", "T", "3"),
        listOf(
            Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
            Chunk.Conflict(lines(1, 2), lines(1, 3), lines(1, 2)),
            Chunk.Unchanged(lines(2, 3), lines(3, 4), lines(2, 3)),
        ),
    )

    @Test
    fun `long runs of unchanged lines are cut into parts, and the rows stay the same`() {
        val numbers = (1..7).map { "$it" }.toTypedArray()
        val merge = TextMerge(
            text(*numbers, "x"),
            text(*numbers, "ours"),
            text(*numbers, "x"),
            listOf(
                Chunk.Unchanged(lines(0, 7), lines(0, 7), lines(0, 7)),
                Chunk.Change(lines(7, 8), lines(7, 8), lines(7, 8), ChangedBy.OURS),
            ),
        )
        val parts = merge.inParts(maxLines = 3)

        assertEquals(
            listOf(lines(0, 3), lines(3, 6), lines(6, 7), lines(7, 8)),
            parts.chunks.map { it.base },
        )
        assertEquals(parts.chunks.map { it.base }, parts.chunks.map { it.ours })
        assertEquals(merge.chunks.last(), parts.chunks.last())
        assertEquals(
            merge.columnRows(showBase = false).map { it.describe() },
            parts.columnRows(showBase = false).map { it.describe() },
        )
        // Nothing to cut: the same merge
        assertSame(merge, merge.inParts(maxLines = 7))

        // Lines typed in place of a run stay one part, as they don't follow its lines
        val typed = merge.typing(0, TypedLines(text("x", "y", "z", "w")))

        assertSame(typed, typed.inParts(maxLines = 3))
    }

    @Test
    fun `editing starts from the lines the result shows there, and an open conflict from the base's`() {
        assertEquals("B", changes.editStart(1).text)
        assertEquals("b", changes.applying(1, applied = false).editStart(1).text)
        assertEquals("c", changes.editStart(2).text)

        assertEquals("2", conflict.editStart(1).text)
        assertEquals("O\nO", conflict.resolving(1, ConflictResolution.Ours).editStart(1).text)
        assertEquals("T\nO\nO", conflict.resolving(1, ConflictResolution.TheirsThenOurs).editStart(1).text)
        assertEquals("", conflict.resolving(1, ConflictResolution.Edited(TypedLines(text()))).editStart(1).text)
    }

    @Test
    fun `typing replaces a part's lines in the result, and resolves a conflict`() {
        val typed = conflict.typing(1, TypedLines(text("x", "y")))

        assertEquals(listOf("1|1|1", "O|x|T", "O|y|_", "3|3|3"), typed.columnRows(showBase = false).map { it.describe() })
        assertEquals(ConflictResolution.Edited(TypedLines(text("x", "y"))), (typed.chunks[1] as Chunk.Conflict).resolution)
        assertEquals(0, typed.openConflicts)
        assertTrue(typed.isEdited(1))

        val row = typed.columnRows(showBase = false)[1].result

        assertEquals(CellKind.RESOLVED, row.kind)
        assertTrue(row.edited)
        assertEquals(listOf(1, 2, 3, 4), typed.columnRows(showBase = false).map { it.result.number })
    }

    @Test
    fun `lines typed in place of unchanged lines or a change are marked as edited, and numbered with the rest`() {
        val typed = changes.typing(2, TypedLines(text("c1", "c2"))).typing(3, TypedLines(text("X")))
        val rows = typed.columnRows(showBase = false)

        assertEquals(listOf("a|a|a", "B|B|b", "c|c1|c", "_|c2|_", "d|X|x"), rows.map { it.describe() })
        assertEquals(listOf(1, 2, 3, 4, 5), rows.map { it.result.number })
        assertEquals(
            listOf(false, false, true, true, true),
            rows.map { it.result.edited },
        )
        assertEquals(CellKind.EDITED, rows[2].result.kind)
        assertEquals(CellKind.EDITED, rows[4].result.kind)
        // The sides are as they were
        assertEquals(CellKind.UNCHANGED, rows[2].ours.kind)
        assertEquals(CellKind.CHANGED_BY_THEIRS, rows[4].theirs.kind)
    }

    @Test
    fun `deleting every line of a part leaves a mark in the stacked result`() {
        val typed = changes.typing(2, TypedLines(text()))

        assertEquals(listOf("a|a|a", "B|B|b", "c|_|c", "d|x|x"), typed.columnRows(showBase = false).map { it.describe() })

        val result = typed.resultRows(showBase = false).filter { it.chunk == 2 }

        assertEquals(1, result.size)
        assertNull(result.single().cell.text)
        assertTrue(result.single().cell.edited)
    }

    @Test
    fun `a change's buttons, and putting the lines back, replace the lines typed`() {
        val typed = changes.typing(1, TypedLines(text("typed")))

        assertEquals("a", typed.applying(1, applied = false).editStart(0).text)
        assertEquals("b", typed.applying(1, applied = false).editStart(1).text)
        assertEquals("B", typed.applying(1, applied = true).editStart(1).text)
        assertEquals(changes, typed.withoutTyping(1))
        assertFalse(typed.withoutTyping(1).isEdited(1))
        assertEquals(changes, changes.typing(2, TypedLines(text("C"))).withoutTyping(2))

        // A conflict keeps its lines: its arrows and menu replace them
        val typedConflict = conflict.typing(1, TypedLines(text("x")))

        assertSame(typedConflict, typedConflict.withoutTyping(1))
        assertEquals(ConflictResolution.Ours, (typedConflict.resolving(1, ConflictResolution.Ours).chunks[1] as Chunk.Conflict).resolution)
    }

    @Test
    fun `typed lines are equal when their bytes are`() {
        assertEquals(TypedLines(text("x", "y")), TypedLines(text("x", "y")))
        assertEquals(TypedLines(text("x")).hashCode(), TypedLines(text("x")).hashCode())
        assertNotEquals(TypedLines(text("x")), TypedLines(RawText("x\r\n".toByteArray())))
        assertEquals(changes.typing(2, TypedLines(text("q"))), changes.typing(2, TypedLines(text("q"))))
    }

    @Test
    fun `typed text is written with the part's line endings, and an empty text is no line`() {
        val lf = LineFormat(Charsets.UTF_8, "\n", endsWithLineEnding = true)

        assertEquals("x\ny\n", lf.typed("x\ny").string())
        assertEquals("x\n\n", lf.typed("x\n").string())
        assertEquals(0, lf.typed("").size)
        assertEquals(2, lf.typed("\n").size)
        // Pasted text can bring its own line endings
        assertEquals("x\ny\n", lf.typed("x\r\ny").string())

        val crlf = LineFormat(Charsets.UTF_8, "\r\n", endsWithLineEnding = true)

        assertEquals("x\r\ny\r\n", crlf.typed("x\ny").string())

        val atTheEnd = LineFormat(Charsets.UTF_8, "\n", endsWithLineEnding = false)

        assertEquals("x\ny", atTheEnd.typed("x\ny").string())
        assertTrue(atTheEnd.typed("x\ny").text.isMissingNewlineAtEnd)
    }

    @Test
    fun `editing keeps the file's line endings, and where the file ends without one`() {
        val crlf = merge(base = "a\r\nb\r\nc", ours = "a\r\nB\r\nc", theirs = "a\r\nb\r\nc")
        val start = crlf.editStart(1)

        assertEquals("B", start.text)
        assertEquals("\r\n", start.format.lineEnding)
        assertTrue(start.format.endsWithLineEnding)
        // The last line has no line ending
        val last = crlf.editStart(2)

        assertEquals("c", last.text)
        assertFalse(last.format.endsWithLineEnding)
        assertEquals("C1\r\nC2", last.format.typed("C1\nC2").string())
    }

    @Test
    fun `a part without lines takes the file's line ending and charset`() {
        // Ours deleted b
        val crlf = TextMerge(
            RawText("a\r\nb\r\n".toByteArray()),
            RawText("a\r\n".toByteArray()),
            RawText("a\r\nb\r\n".toByteArray()),
            listOf(
                Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
                Chunk.Change(lines(1, 2), lines(1, 1), lines(1, 2), ChangedBy.OURS),
            ),
        )
        val start = crlf.editStart(1)

        assertEquals("", start.text)
        assertEquals("\r\n", start.format.lineEnding)
        assertEquals(Charsets.UTF_8, start.format.charset)
    }

    @Test
    fun `lines that aren't UTF-8 are edited as ISO-8859-1, which keeps every byte`() {
        // "café" in ISO-8859-1, as an older Windows editor writes it
        val latin1 = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        val merge = TextMerge(
            RawText(latin1 + "\n".toByteArray()),
            RawText(latin1 + "\n".toByteArray()),
            RawText(latin1 + "\n".toByteArray()),
            listOf(Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1))),
        )
        val start = merge.editStart(0)

        assertEquals("café", start.text)
        assertEquals(Charsets.ISO_8859_1, start.format.charset)
        assertTrue((latin1 + "\n".toByteArray()).contentEquals(start.format.typed(start.text).text.rawContent))
        assertTrue(start.format.canWrite("crème"))
        assertFalse(start.format.canWrite("€"))

        val utf8 = merge(base = "café\n", ours = "café\n", theirs = "café\n")

        assertEquals("café", utf8.editStart(0).text)
        assertEquals(Charsets.UTF_8, utf8.editStart(0).format.charset)
        assertTrue(utf8.editStart(0).format.canWrite("€"))
    }

    @Test
    fun `editing a part and changing nothing gives its bytes back`() {
        val merges = listOf(
            changes,
            conflict.resolving(1, ConflictResolution.OursThenTheirs),
            merge(base = "a\r\nb\r\nc", ours = "a\r\nB\r\nc", theirs = "a\r\nb\r\nc"),
            merge(base = "x\n\n\ny", ours = "x\n\n\ny", theirs = "x\n\n\ny"),
        )

        for (merge in merges) {
            for (chunk in merge.chunks.indices) {
                val start = merge.editStart(chunk)

                // A single empty line starts as an empty text, which is no line: the view model keeps a part whose text
                // didn't change as it was
                if (start.text.isEmpty()) {
                    continue
                }
                val typed = start.format.typed(start.text)
                val original = merge.resultLines(merge.chunks[chunk], showBase = false).lines.flatMap { (text, line) ->
                    val raw = text.getRawString(line)
                    val bytes = ByteArray(raw.remaining()).also { raw.get(it) }.toList()
                    val hasLineEnding = line < text.size() - 1 || !text.isMissingNewlineAtEnd

                    if (hasLineEnding) bytes + '\n'.code.toByte() else bytes
                }

                assertEquals(original, typed.text.rawContent.toList(), "Chunk $chunk of $merge")
            }
        }
    }

    @Test
    fun `parts are numbered as the result, and the first changed part is found`() {
        assertEquals(listOf(1, 2, 3, 4), changes.chunks.indices.map { changes.firstResultLine(it) })
        // An open conflict has no lines in the result
        assertEquals(listOf(1, 2, 2), conflict.chunks.indices.map { conflict.firstResultLine(it) })
        assertEquals(listOf(1, 2, 4), conflict.resolving(1, ConflictResolution.Ours).let { m -> m.chunks.indices.map { m.firstResultLine(it) } })

        assertEquals(1, changes.firstChangedChunk(changes.typing(1, TypedLines(text("q")))))
        assertNull(changes.firstChangedChunk(changes))
    }
}

/** A merge of three versions with as many lines each, a chunk per line, and ours' lines that differ as changes. */
private fun merge(base: String, ours: String, theirs: String): TextMerge {
    val texts = listOf(base, ours, theirs).map { RawText(it.toByteArray()) }
    val size = texts.first().size()

    require(texts.all { it.size() == size }) { "The versions need as many lines" }

    val chunks = (0 until size).map { line ->
        val range = lines(line, line + 1)

        if (texts[1].getString(line) != texts[0].getString(line)) {
            Chunk.Change(range, range, range, ChangedBy.OURS)
        } else {
            Chunk.Unchanged(range, range, range)
        }
    }

    return TextMerge(texts[0], texts[1], texts[2], chunks)
}

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)

private fun TypedLines.string() = String(text.rawContent, Charsets.UTF_8)

private fun ColumnRow.describe() = listOf(ours, result, theirs).joinToString("|") { cell ->
    cell.text?.getString(cell.line) ?: "_"
}
