// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Conflict markers in the working file, found as `git rerere` finds them (rerere.c). */
class ConflictMarkerScanTest {
    @Test
    fun `a file without markers has no conflicts`() {
        assertEquals(ConflictMarkerScan(0, pairsUp = true), scan("fun load() {\n    parse()\n}\n"))
    }

    @Test
    fun `each conflict counts once, in merge and in diff3 style`() {
        val text = """
            |a
            |<<<<<<< HEAD
            |ours
            |=======
            |theirs
            |>>>>>>> feature
            |b
            |<<<<<<< HEAD
            |ours
            ||||||| parent of a1b2c3d (Fix parser)
            |base
            |=======
            |theirs
            |>>>>>>> a1b2c3d (Fix parser)
            |
        """.trimMargin()

        assertEquals(ConflictMarkerScan(2, pairsUp = true), scan(text))
    }

    @Test
    fun `a conflict inside another counts as one`() {
        val text = """
            |<<<<<<< HEAD
            |<<<<<<< HEAD
            |inner ours
            |=======
            |inner theirs
            |>>>>>>> other
            |=======
            |theirs
            |>>>>>>> feature
            |
        """.trimMargin()

        assertEquals(ConflictMarkerScan(1, pairsUp = true), scan(text))
        assertEquals(
            ConflictMarkerScan(1, pairsUp = true),
            scan("<<<<<<< HEAD\nours\n=======\n<<<<<<< HEAD\na\n=======\nb\n>>>>>>> x\n>>>>>>> feature\n"),
        )
    }

    @Test
    fun `markers can end with CRLF, and the bars and equals with a tab or nothing`() {
        assertEquals(
            ConflictMarkerScan(1, pairsUp = true),
            scan("<<<<<<< HEAD\r\nours\r\n|||||||\r\nbase\r\n=======\t\r\ntheirs\r\n>>>>>>> feature\r\n"),
        )
    }

    @Test
    fun `lines that only look like markers are text`() {
        // No label after <<<<<<<, a longer marker, markers further in, and ======= or >>>>>>> outside a conflict
        val text = """
            |<<<<<<<
            |<<<<<<<< HEAD
            | <<<<<<< HEAD
            |=======
            |>>>>>>> feature
            |
        """.trimMargin()

        assertEquals(ConflictMarkerScan(0, pairsUp = true), scan(text))
    }

    @Test
    fun `markers out of order or a conflict left open don't pair up`() {
        assertEquals(ConflictMarkerScan(0, pairsUp = false), scan("<<<<<<< HEAD\nours\n>>>>>>> feature\n"))
        assertEquals(ConflictMarkerScan(0, pairsUp = false), scan("<<<<<<< HEAD\n=======\n|||||||\n>>>>>>> x\n"))
        assertEquals(ConflictMarkerScan(0, pairsUp = false), scan("<<<<<<< HEAD\n=======\n=======\n>>>>>>> x\n"))
        assertEquals(
            ConflictMarkerScan(0, pairsUp = false),
            scan("<<<<<<< HEAD\na\n=======\nb\n|||||||\nc\n=======\nd\n>>>>>>> x\n"),
        )
        assertEquals(ConflictMarkerScan(0, pairsUp = false), scan("<<<<<<< HEAD\nours\n=======\ntheirs\n"))
        // Counted up to there
        assertEquals(
            ConflictMarkerScan(1, pairsUp = false),
            scan("<<<<<<< HEAD\na\n=======\nb\n>>>>>>> x\n<<<<<<< HEAD\nc\n"),
        )
    }

    @Test
    fun `a marker on the last line without a line ending isn't one, as for rerere`() {
        assertEquals(ConflictMarkerScan(0, pairsUp = false), scan("<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>>"))
        assertEquals(ConflictMarkerScan(1, pairsUp = true), scan("<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> x"))
    }

    @Test
    fun `markers have the length that conflict-marker-size asks for`() {
        val long = "<<<<<<<<<< HEAD\nours\n==========\ntheirs\n>>>>>>>>>> feature\n"
        val short = "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> feature\n"

        assertEquals(ConflictMarkerScan(1, pairsUp = true), scan(long, markerSize = 10))
        assertEquals(ConflictMarkerScan(0, pairsUp = true), scan(long))
        assertEquals(ConflictMarkerScan(0, pairsUp = true), scan(short, markerSize = 10))
    }

    @Test
    fun `bytes that aren't UTF-8 don't matter`() {
        val text = "café\n<<<<<<< HEAD\nüber\n=======\nnaïve\n>>>>>>> feature\n"

        assertEquals(ConflictMarkerScan(1, pairsUp = true), scan(text.toByteArray(Charsets.ISO_8859_1)))
    }

    private fun scan(text: String, markerSize: Int = DEFAULT_CONFLICT_MARKER_SIZE) =
        scan(text.toByteArray(), markerSize)

    private fun scan(text: ByteArray, markerSize: Int = DEFAULT_CONFLICT_MARKER_SIZE) =
        scanConflictMarkers(RawText(text), markerSize)
}
