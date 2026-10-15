// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator

/**
 * The most words, the base's and a side's together, that a chunk's lines are compared by. Past it, its lines get no
 * marks: the rows compare them as they're drawn, and the time grows with the square of the words that differ. On an
 * M4 Pro, 3,200 words that all differ took 28 ms, and 1,920 took 11 (`conflict-resolver.md`, section 11).
 */
const val MAX_COMPARED_WORDS = 2_000

/**
 * The words of [side]'s [sideLines] that the base's [baseLines] don't have, by line of [side] (`conflict-resolver.md`,
 * section 11): what a change, or a side of a conflict, changed inside its lines. A mark is a range of a line's
 * characters, as `RawText.getString` gives the line.
 *
 * Words are runs of letters, digits and `_`, runs of spaces and tabs, and each other character alone. Words alike
 * between two changes that are no longer than either change are part of them, as in diff-match-patch's semantic
 * cleanup, which the diff pane uses: `1.3.0` against `1.2.3` is one mark, not `3` and `0`. A line that's changed whole
 * gets none, as its colour says it, which the diff pane does too. Nor does a chunk past [MAX_COMPARED_WORDS].
 */
fun changedWords(base: RawText, baseLines: LineRange, side: RawText, sideLines: LineRange): Map<Int, List<IntRange>> {
    // Lines added or deleted whole
    if (baseLines.isEmpty || sideLines.isEmpty) {
        return emptyMap()
    }

    val baseWords = words(base, baseLines, MAX_COMPARED_WORDS) ?: return emptyMap()
    val sideWords = words(side, sideLines, MAX_COMPARED_WORDS - baseWords.size) ?: return emptyMap()
    val changed = BooleanArray(sideWords.size)
    val edits = HistogramDiff().diff(WordComparator, Words(baseWords), Words(sideWords))

    for (edit in withShortEqualitiesJoined(edits, baseWords, sideWords)) {
        for (index in edit.beginB until edit.endB) {
            changed[index] = true
        }
    }

    return sideWords.indices
        .groupBy { sideWords[it].line }
        .mapValues { (_, indices) -> marks(indices.map { sideWords[it] to changed[it] }) }
        .filterValues { it.isNotEmpty() }
}

/**
 * Each line's [changedWords] in [merge], worked out a chunk at a time, the first time that one of its lines is asked
 * for. Picks and lines typed keep the chunks' lines, so one serves a file for as long as it's shown. Not thread-safe:
 * the rows ask for marks as they're drawn.
 */
class ChangedWords(private val merge: TextMerge) {
    private val oursStarts = merge.chunks.map { it.ours.start }
    private val theirsStarts = merge.chunks.map { it.theirs.start }
    private val computed = HashMap<Pair<ConflictSide, Int>, Map<Int, List<IntRange>>>()

    /**
     * The marks of [line] of [text], which ours or theirs has in a change or a conflict, or none: [text] is the base's,
     * lines typed, or a filler's.
     */
    fun of(text: RawText?, line: Int): List<IntRange> {
        val side = when {
            text == null -> return emptyList()
            text === merge.ours -> ConflictSide.OURS
            text === merge.theirs -> ConflictSide.THEIRS
            else -> return emptyList()
        }
        val index = chunkWith(side, line) ?: return emptyList()
        val chunk = merge.chunks[index]

        if (chunk is Chunk.Unchanged) {
            return emptyList()
        }

        val lines = computed.getOrPut(side to index) {
            changedWords(merge.base, chunk.base, text, if (side == ConflictSide.OURS) chunk.ours else chunk.theirs)
        }

        return lines[line].orEmpty()
    }

    /**
     * The chunk that has [line] of [side]: the last one that starts at it or before, as the chunks cover each version's
     * lines in order. One without lines there starts where the next one does, so it's never the last.
     */
    private fun chunkWith(side: ConflictSide, line: Int): Int? {
        val starts = if (side == ConflictSide.OURS) oursStarts else theirsStarts
        var low = 0
        var high = starts.lastIndex
        var index = -1

        while (low <= high) {
            val middle = (low + high) ushr 1

            if (starts[middle] <= line) {
                index = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        val chunk = merge.chunks.getOrNull(index) ?: return null
        val lines = if (side == ConflictSide.OURS) chunk.ours else chunk.theirs

        return index.takeIf { line < lines.end }
    }
}

/** A word of [line]: its characters from [start] to [end], or the line's end when it's [LINE_END]. */
private class Word(val line: Int, val start: Int, val end: Int, val text: String) {
    val isSpace get() = text.isNotEmpty() && text[0].isWhitespace()
}

private const val LINE_END = "\n"

/** [lines]' words, each line's ended by [LINE_END], or null when there are more than [limit]. */
private fun words(text: RawText, lines: LineRange, limit: Int): List<Word>? {
    val words = mutableListOf<Word>()

    for (line in lines.start until lines.end) {
        val string = text.getString(line).removeSuffix("\r")
        var start = 0

        while (start < string.length) {
            val first = string[start]
            var end = start + 1

            if (isWordCharacter(first)) {
                while (end < string.length && isWordCharacter(string[end])) end++
            } else if (first == ' ' || first == '\t') {
                while (end < string.length && (string[end] == ' ' || string[end] == '\t')) end++
            }

            words += Word(line, start, end, string.substring(start, end))
            start = end
        }

        words += Word(line, string.length, string.length, LINE_END)

        if (words.size > limit) {
            return null
        }
    }

    return words
}

private fun isWordCharacter(char: Char) = char.isLetterOrDigit() || char == '_'

/**
 * [edits] with the words alike between two of them made part of them, when they're on one line and no longer, in
 * characters, than the words either edit takes out or puts in (diff-match-patch's `diff_cleanupSemantic`).
 */
private fun withShortEqualitiesJoined(edits: List<Edit>, baseWords: List<Word>, sideWords: List<Word>): List<Edit> {
    fun length(words: List<Word>, from: Int, to: Int) =
        (from until to).sumOf { words[it].text.takeUnless { text -> text == LINE_END }?.length ?: 0 }

    fun Edit.length() = maxOf(length(baseWords, beginA, endA), length(sideWords, beginB, endB))

    val joined = edits.toMutableList()
    var index = 0

    while (index < joined.size - 1) {
        val edit = joined[index]
        val next = joined[index + 1]
        val onOneLine = (edit.endB until next.beginB).none { sideWords[it].text == LINE_END }
        val alikeLength = length(sideWords, edit.endB, next.beginB)

        if (onOneLine && alikeLength <= edit.length() && alikeLength <= next.length()) {
            joined[index] = Edit(edit.beginA, next.endA, edit.beginB, next.endB)
            joined.removeAt(index + 1)
            // The longer edit may now take in the words alike before it
            index = (index - 1).coerceAtLeast(0)
        } else {
            index++
        }
    }

    return joined
}

/** A line's marks from its words in order, each with whether it changed. None when every word but spaces changed. */
private fun marks(words: List<Pair<Word, Boolean>>): List<IntRange> {
    val inLine = words.filter { (word) -> word.text != LINE_END }

    if (inLine.filterNot { (word) -> word.isSpace }.all { (_, changed) -> changed }) {
        return emptyList()
    }

    val marks = mutableListOf<IntRange>()
    var index = 0

    while (index < inLine.size) {
        if (!inLine[index].second) {
            index++
            continue
        }

        val start = inLine[index].first.start

        while (index < inLine.size && inLine[index].second) {
            index++
        }

        marks += start until inLine[index - 1].first.end
    }

    return marks
}

private class Words(val words: List<Word>) : Sequence() {
    override fun size() = words.size
}

private object WordComparator : SequenceComparator<Words>() {
    override fun equals(a: Words, ai: Int, b: Words, bi: Int) = a.words[ai].text == b.words[bi].text

    override fun hash(seq: Words, ptr: Int) = seq.words[ptr].text.hashCode()
}
