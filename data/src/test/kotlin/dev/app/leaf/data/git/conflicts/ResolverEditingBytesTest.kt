// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.domain.conflicts.editStart
import dev.app.leaf.domain.conflicts.inParts
import dev.app.leaf.domain.conflicts.isEdited
import dev.app.leaf.domain.conflicts.typing
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** What Save writes once parts of the result were edited in the resolver. */
class ResolverEditingBytesTest {
    private val histogram = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
    private val style = ConflictMarkerStyle(withBase = false, ConflictLabels(ours = "OURS", base = "BASE", theirs = "THEIRS"))

    @Test
    fun `lines typed in place of unchanged lines, a change and a conflict are written with the file's line endings`() {
        val merge = textMerge(
            RawText("1\r\n2\r\n3\r\n4\r\n".toByteArray()),
            RawText("1\r\nours\r\n3\r\n4\r\nadded\r\n".toByteArray()),
            RawText("1\r\ntheirs\r\n3\r\n4\r\n".toByteArray()),
            histogram,
        )
        // 1 | conflict | 3 4 | ours' added line
        val unchanged = merge.chunks.indexOfLast { it is Chunk.Unchanged }
        val change = merge.chunks.indexOfFirst { it is Chunk.Change }
        val conflict = merge.chunks.indexOfFirst { it is Chunk.Conflict }

        fun TextMerge.typing(chunk: Int, text: String) = typing(chunk, editStart(chunk).format.typed(text))

        val edited = merge
            .typing(unchanged, "three")
            .typing(change, "added\nmore")
            .typing(conflict, "both")

        assertEquals(
            "1\r\nboth\r\nthree\r\nadded\r\nmore\r\n",
            edited.resultBytes(style).decodeToString(),
        )
        // Every line deleted
        assertEquals("1\r\nboth\r\n", edited.typing(unchanged, "").typing(change, "").resultBytes(style).decodeToString())
    }

    /**
     * Opening any part of a random merge, CRLF and ISO-8859-1 files included, and leaving its text as it was gives the
     * file back byte for byte, so editing changes only what the user typed.
     */
    @Test
    fun `editing a part without changing its text writes the same file`() {
        val random = Random(seed = 20261013)
        var parts = 0

        repeat(2_000) { case ->
            val crlf = random.nextBoolean()
            val latin1 = random.nextInt(3) == 0
            val base = randomLines(random, random.nextInt(0, 9))
            val versions = listOf(base, mutate(random, base), mutate(random, base))
            val texts = versions.map { RawText(it.toBytes(random, crlf, latin1)) }
            val merge = textMerge(texts[0], texts[1], texts[2], histogram)
                .inParts(maxLines = 2)
                .resolvingRandomly(random)
            val expected = merge.resultBytes(style)

            for (chunk in merge.chunks.indices) {
                val start = merge.editStart(chunk)

                // An empty text: the view model keeps the part as it was (ResolverEditingTest). An open conflict starts
                // from the base's lines, which typing puts in place of its markers
                if (start.text.isEmpty() || merge.chunks[chunk].isOpenConflict() || merge.joinsALastLine(chunk)) {
                    continue
                }

                parts++
                assertEquals(
                    expected.toString(Charsets.ISO_8859_1).escaped(),
                    merge.typing(chunk, start.format.typed(start.text)).resultBytes(style).toString(Charsets.ISO_8859_1).escaped(),
                    "case $case, chunk $chunk, crlf $crlf, latin1 $latin1, versions $versions",
                )
            }
        }

        assertTrue(parts > 4_000, "Only $parts parts edited")
    }

    @Test
    fun `lines saved where a change meets unchanged lines come back in the unchanged lines, which keeps the change`() {
        val merge = textMerge(
            RawText("a\nb\nc\nd\n".toByteArray()),
            RawText("a\nB\nc\nd\n".toByteArray()),
            RawText("a\nb\nc\nd\n".toByteArray()),
            histogram,
        )
        val change = merge.chunks.indexOfFirst { it is Chunk.Change }
        val unchanged = change + 1
        val saved = merge.typing(unchanged, merge.editStart(unchanged).format.typed("typed\nc\nd"))
        val resumed = checkNotNull(merge.resumedFrom(saved.resultBytes(style), style))

        assertEquals(saved.chunks, resumed.chunks)
    }

    /**
     * A file that the resolver saved opens as it was saved: random merges, with random picks and lines typed in random
     * parts, are written, then picked out of the bytes again. The state found writes the same bytes and has the same
     * conflicts open, and a state of picks alone comes back as those picks.
     */
    @Test
    fun `a saved state is found again in the bytes it wrote`() {
        val random = Random(seed = 20261014)
        var typedStates = 0

        repeat(2_000) { case ->
            val crlf = random.nextBoolean()
            val base = randomLines(random, random.nextInt(0, 9))
            val versions = listOf(base, mutate(random, base), mutate(random, base))
            val texts = versions.map { RawText(it.toBytes(random, crlf, latin1 = false)) }
            val fresh = textMerge(texts[0], texts[1], texts[2], histogram).inParts(maxLines = 2)
            val picked = fresh.resolvingRandomly(random)
            val typed = picked.chunks.indices.fold(picked) { merge, chunk ->
                if (random.nextInt(5) != 0 || merge.chunks[chunk].isOpenConflict()) {
                    merge
                } else {
                    val lines = randomLines(random, random.nextInt(0, 3)).joinToString("\n")

                    merge.typing(chunk, merge.editStart(chunk).format.typed(lines))
                }
            }

            for (saved in listOf(picked, typed)) {
                val bytes = saved.resultBytes(style)
                val resumed = fresh.resumedFrom(bytes, style)
                val description = "case $case, crlf $crlf, versions $versions"

                checkNotNull(resumed) { "Not found again: $description" }
                assertEquals(bytes.toString(Charsets.ISO_8859_1).escaped(), resumed.resultBytes(style).toString(Charsets.ISO_8859_1).escaped(), description)
                assertEquals(saved.openConflicts, resumed.openConflicts, description)

                // Picks come back as picks, not as lines typed. Two can give the same lines, as when a side has none
                if (saved === picked) {
                    assertTrue(resumed.chunks.indices.none { resumed.isEdited(it) }, description)
                }
            }

            if (typed != picked) typedStates++
        }

        assertTrue(typedStates > 500, "Only $typedStates states with lines typed")
    }
}

private val ALPHABET = listOf("a", "b", "c", "dé")

/** Lines from a small alphabet, so that the merges find many equal lines. */
private fun randomLines(random: Random, count: Int) = List(count) { ALPHABET.random(random) }

private fun mutate(random: Random, lines: List<String>): List<String> = buildList {
    for (line in lines) {
        when (random.nextInt(10)) {
            0 -> Unit
            1, 2 -> add(ALPHABET.random(random))
            3 -> {
                add(ALPHABET.random(random))
                add(line)
            }

            else -> add(line)
        }
    }

    if (random.nextInt(4) == 0) {
        add(ALPHABET.random(random))
    }
}

/** The lines as a file, sometimes without the last line ending. */
private fun List<String>.toBytes(random: Random, crlf: Boolean, latin1: Boolean): ByteArray {
    val ending = if (crlf) "\r\n" else "\n"
    val text = joinToString("") { it + ending }
    val trimmed = if (text.isNotEmpty() && random.nextInt(4) == 0) text.removeSuffix(ending) else text

    return trimmed.toByteArray(if (latin1) Charsets.ISO_8859_1 else Charsets.UTF_8)
}

/** Each conflict resolved one way or another, or left open, and some changes undone. */
private fun TextMerge.resolvingRandomly(random: Random) = copy(
    chunks = chunks.map { chunk ->
        when (chunk) {
            is Chunk.Conflict -> chunk.copy(
                resolution = listOf(
                    ConflictResolution.Unresolved,
                    ConflictResolution.Ours,
                    ConflictResolution.Theirs,
                    ConflictResolution.OursThenTheirs,
                    ConflictResolution.TheirsThenOurs,
                    ConflictResolution.Base,
                ).random(random),
            )

            is Chunk.Change -> chunk.copy(applied = random.nextInt(4) != 0)
            is Chunk.Unchanged -> chunk
        }
    },
)

private fun Chunk.isOpenConflict() = this is Chunk.Conflict && resolution == ConflictResolution.Unresolved

/** Line endings made visible, so that a difference in them shows in a failure. */
private fun String.escaped() = replace("\r", "\\r").replace("\n", "\\n\n")

/**
 * A conflict that writes a version's last line, which has no line ending, before the other side's lines. The merge gives
 * that line the markers' line ending, as git does, and typed lines get the part's, the first line ending in it.
 */
private fun TextMerge.joinsALastLine(chunk: Int): Boolean {
    val conflict = chunks[chunk] as? Chunk.Conflict ?: return false
    val (first, firstRange, secondRange) = when (conflict.resolution) {
        ConflictResolution.OursThenTheirs -> Triple(ours, conflict.ours, conflict.theirs)
        ConflictResolution.TheirsThenOurs -> Triple(theirs, conflict.theirs, conflict.ours)
        else -> return false
    }

    return !firstRange.isEmpty && !secondRange.isEmpty && firstRange.end == first.size() && first.isMissingNewlineAtEnd
}
