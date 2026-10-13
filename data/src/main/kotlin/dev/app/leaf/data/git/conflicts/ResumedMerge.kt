// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The state of this merge that Save writes as [canonical], the working file in the form that git checks it in: what
 * was saved from the resolver, picked out part by part, so that opening the file again shows it (`conflict-resolver.md`,
 * section 8). Null when no state of the merge writes those bytes in a way the resolver can show, as when markers were
 * edited by hand.
 *
 * Picks first: a state made of picks alone (changes applied or not, conflicts open or resolved without typing) that
 * writes the file, found part by part ([pickedFrom]). Otherwise the file is compared, line by line, with what the
 * merge writes untouched (every conflict open, every change applied). A part whose lines differ takes the file's
 * lines: a change that has the base's lines isn't applied, a conflict that has a side's lines (or both, or the base's)
 * takes them, and anything else is lines typed. Lines inserted where two parts meet go to the first one, unless that
 * one is a pick and the second unchanged lines, which take them. The
 * conflicts still open anchor the comparison, each at its block of markers, and a block that no conflict writes stops
 * it. The state is kept only when it writes [canonical] again, byte for byte.
 */
internal fun TextMerge.resumedFrom(canonical: ByteArray, style: ConflictMarkerStyle): TextMerge? {
    val written = resultBytes(style)

    if (written.contentEquals(canonical)) {
        return this
    }

    val expected = RawText(written)
    val actual = RawText(canonical)

    // Every block of markers in the file is an open conflict's ([openConflictsIn]), so lines typed have none
    fun TextMerge.writesTheFile(): Boolean = resultBytes(style).contentEquals(canonical)

    pickedFrom(actual, style).firstOrNull { it.writesTheFile() }?.let { return it }

    val starts = chunkStarts(style)

    if (starts.last() != expected.size()) {
        return null
    }

    // The conflicts still open are where their markers are, as Save wrote them: they anchor the parts between them
    val anchors = openConflictsIn(actual, expected, starts, style) ?: return null
    val mappedStarts = IntArray(chunks.size + 1)
    var previous: Pair<Int, LineRange>? = null

    for (anchor in anchors + listOf<Pair<Int, LineRange>?>(null)) {
        // The parts between the previous anchor (or the start) and this one (or the end)
        val firstChunk = previous?.first?.plus(1) ?: 0
        val endChunk = anchor?.first ?: chunks.size
        val from = LineRange(starts[firstChunk], starts[endChunk])
        val to = LineRange(previous?.second?.end ?: 0, anchor?.second?.start ?: actual.size())

        if (to.end < to.start) {
            return null
        }

        val mapped = linesMapped(expected, from, actual, to) { line ->
            // Lines inserted where a pick meets unchanged lines go to the unchanged lines, which keeps the pick
            val after = starts.indexOf(line).takeIf { it >= 0 }?.let { chunks.getOrNull(it) }
            val before = starts.indexOf(line).takeIf { it > 0 }?.let { chunks[it - 1] }

            !(before !is Chunk.Unchanged && after is Chunk.Unchanged)
        }

        for (chunk in firstChunk..endChunk) {
            mappedStarts[chunk] = mapped(starts[chunk])
        }

        anchor?.let { (chunk, block) -> mappedStarts[chunk + 1] = block.end }
        previous = anchor
    }

    val lineEnding = actual.firstLineEnding() ?: "\n"
    val places = chunks.indices.map { index ->
        val from = LineRange(starts[index], starts[index + 1])
        val to = LineRange(mappedStarts[index], mappedStarts[index + 1])

        if (to.end < to.start) {
            return null
        }

        from to to
    }

    fun resumed(picks: Boolean) = copy(
        chunks = chunks.mapIndexed { index, chunk ->
            val (from, to) = places[index]

            when {
                sameLines(expected, from, actual, to) -> chunk
                picks -> pickOf(chunk, actual, to) ?: chunk.typing(actual, to, lineEnding)
                else -> chunk.typing(actual, to, lineEnding)
            }
        },
    )

    // A pick can write its lines with other line endings than the file has: then they're lines typed
    return resumed(picks = true).takeIf { it.writesTheFile() } ?: resumed(picks = false).takeIf { it.writesTheFile() }
}

/**
 * The conflicts open in [actual], each as its chunk and its lines from `<<<<<<<` to `>>>>>>>`, in order: each block
 * of markers is the block that the merge writes for the next conflict that has the same lines ([expected] has every
 * conflict open, the chunks starting at [starts]). Null when a block is no conflict's.
 */
private fun TextMerge.openConflictsIn(
    actual: RawText,
    expected: RawText,
    starts: List<Int>,
    style: ConflictMarkerStyle,
): List<Pair<Int, LineRange>>? {
    val opening = "<".repeat(style.size)
    val closing = ">".repeat(style.size)

    fun RawText.startsWith(line: Int, marker: String): Boolean {
        val text = getRawString(line).toByteArray().decodeToString().trimEnd('\r')

        return text == marker || text.startsWith("$marker ")
    }

    val anchors = mutableListOf<Pair<Int, LineRange>>()
    var nextChunk = 0
    var line = 0

    while (line < actual.size()) {
        if (!actual.startsWith(line, opening)) {
            line++
            continue
        }

        val end = (line + 1 until actual.size()).firstOrNull { actual.startsWith(it, closing) } ?: return null
        val block = LineRange(line, end + 1)
        val chunk = (nextChunk until chunks.size).firstOrNull { index ->
            chunks[index] is Chunk.Conflict && sameLines(expected, LineRange(starts[index], starts[index + 1]), actual, block)
        } ?: return null

        anchors += chunk to block
        nextChunk = chunk + 1
        line = block.end
    }

    return anchors
}

/**
 * Where each line of [from] in [expected] goes in [to] of [actual], as a diff of the two puts it: past what an edit
 * around it put there. Lines inserted where two parts meet go to the first when [insertionsGoBefore] says so for the
 * line where the second starts, and to the second otherwise; a part that starts inside an edit gets none of it.
 */
private fun linesMapped(
    expected: RawText,
    from: LineRange,
    actual: RawText,
    to: LineRange,
    insertionsGoBefore: (line: Int) -> Boolean,
): (Int) -> Int {
    val edits = HistogramDiff().diff(
        RawTextComparator.DEFAULT,
        RawText(expected.bytesOf(from)),
        RawText(actual.bytesOf(to)),
    )

    return { line ->
        val relative = line - from.start

        when (line) {
            from.start -> to.start
            from.end -> to.end
            else -> to.start + mappedLine(relative, edits, insertionsGoBefore(line))
        }
    }
}

private fun mappedLine(line: Int, edits: List<Edit>, insertionsGoBefore: Boolean): Int {
    var shift = 0

    for (edit in edits) {
        when {
            line == edit.beginA && edit.beginA == edit.endA -> return if (insertionsGoBefore) edit.endB else edit.beginB
            line <= edit.beginA -> return line + shift
            line < edit.endA -> return edit.endB
            else -> shift += (edit.endB - edit.beginB) - (edit.endA - edit.beginA)
        }
    }

    return line + shift
}

/**
 * The pick of [chunk] that gives [lines] of [file], compared without line endings: a change that isn't applied, or a
 * conflict's side, both sides in either order, or the base. Null for lines that no pick gives.
 */
private fun TextMerge.pickOf(chunk: Chunk, file: RawText, lines: LineRange): Chunk? {
    fun gives(vararg parts: Pair<RawText, LineRange>): Boolean {
        val given = parts.flatMap { (text, range) -> (range.start until range.end).map { text.getRawString(it) } }

        return given.size == lines.size && given.indices.all { given[it] == file.getRawString(lines.start + it) }
    }

    return when (chunk) {
        is Chunk.Unchanged -> null
        is Chunk.Change -> if (gives(base to chunk.base)) chunk.copy(applied = false) else null
        is Chunk.Conflict -> {
            val ours = ours to chunk.ours
            val theirs = theirs to chunk.theirs
            val resolution = when {
                gives(ours) -> ConflictResolution.Ours
                gives(theirs) -> ConflictResolution.Theirs
                gives(ours, theirs) -> ConflictResolution.OursThenTheirs
                gives(theirs, ours) -> ConflictResolution.TheirsThenOurs
                gives(base to chunk.base) -> ConflictResolution.Base
                else -> return null
            }

            chunk.copy(resolution = resolution)
        }
    }
}

/** The most states of picks alone with the same lines that are tried: they differ in their line endings at most. */
private const val MAX_PICKED_STATES = 64

/**
 * The states of picks alone whose lines are [file]'s, compared without line endings: each part's possible picks are
 * matched against the file in order, keeping every place in the file that the parts so far can end at, so that parts
 * next to each other are told apart wherever the lines allow it. Picks with the same lines can write other line
 * endings, so the states come one by one, for the caller to check their bytes.
 */
private fun TextMerge.pickedFrom(file: RawText, style: ConflictMarkerStyle): Sequence<TextMerge> {
    // For each part, where it can end in the file, and each pick that gets there with the place it started at
    val steps = mutableListOf<Map<Int, List<Pair<Int, Chunk>>>>()
    var places = setOf(0)

    for (chunk in chunks) {
        val step = LinkedHashMap<Int, MutableList<Pair<Int, Chunk>>>()

        for (place in places) {
            for (pick in picksOf(chunk)) {
                val lines = writtenLines(pick, style)
                val fits = place + lines.size <= file.size() &&
                    lines.indices.all { sameText(lines[it], file.getRawString(place + it)) }

                if (fits) {
                    step.getOrPut(place + lines.size) { mutableListOf() } += place to pick
                }
            }
        }

        if (step.isEmpty()) {
            return emptySequence()
        }

        steps += step
        places = step.keys
    }

    /** The picks of the parts up to [part], which end at [place], last part first. */
    fun paths(part: Int, place: Int): Sequence<List<Chunk>> = sequence {
        if (part < 0) {
            if (place == 0) yield(emptyList())
            return@sequence
        }

        for ((start, pick) in steps[part][place].orEmpty()) {
            for (path in paths(part - 1, start)) {
                yield(path + pick)
            }
        }
    }

    return paths(chunks.lastIndex, file.size()).take(MAX_PICKED_STATES).map { copy(chunks = it) }
}

/** What a part can be without typing: its lines, a change applied or not, a conflict open or resolved. */
private fun picksOf(chunk: Chunk): List<Chunk> = when (chunk) {
    is Chunk.Unchanged -> listOf(chunk.copy(edited = null))
    is Chunk.Change -> listOf(chunk.copy(applied = true, edited = null), chunk.copy(applied = false, edited = null))
    is Chunk.Conflict -> listOf(
        ConflictResolution.Unresolved,
        ConflictResolution.Ours,
        ConflictResolution.Theirs,
        ConflictResolution.OursThenTheirs,
        ConflictResolution.TheirsThenOurs,
        ConflictResolution.Base,
    ).map { chunk.copy(resolution = it) }
}

/** The lines that [chunk] writes, without their line endings: its version's lines, or a conflict's markers and sides. */
private fun TextMerge.writtenLines(chunk: Chunk, style: ConflictMarkerStyle): List<ByteBuffer> {
    fun lines(text: RawText, range: LineRange) = (range.start until range.end).map { text.getRawString(it) }
    fun marker(character: Char, label: String) =
        ByteBuffer.wrap(character.toString().repeat(style.size).let { if (label.isEmpty()) it else "$it $label" }.toByteArray())

    return when (chunk) {
        is Chunk.Unchanged -> lines(base, chunk.base)
        is Chunk.Change -> when {
            !chunk.applied -> lines(base, chunk.base)
            chunk.by == ChangedBy.THEIRS -> lines(theirs, chunk.theirs)
            else -> lines(ours, chunk.ours)
        }

        is Chunk.Conflict -> when (val resolution = chunk.resolution) {
            ConflictResolution.Unresolved -> buildList {
                add(marker('<', style.labels.ours))
                addAll(lines(ours, chunk.ours))

                if (style.withBase) {
                    add(marker('|', style.labels.base))
                    addAll(lines(base, chunk.base))
                }

                add(marker('=', ""))
                addAll(lines(theirs, chunk.theirs))
                add(marker('>', style.labels.theirs))
            }

            ConflictResolution.Ours -> lines(ours, chunk.ours)
            ConflictResolution.Theirs -> lines(theirs, chunk.theirs)
            ConflictResolution.OursThenTheirs -> lines(ours, chunk.ours) + lines(theirs, chunk.theirs)
            ConflictResolution.TheirsThenOurs -> lines(theirs, chunk.theirs) + lines(ours, chunk.ours)
            ConflictResolution.Base -> lines(base, chunk.base)
            is ConflictResolution.Edited -> lines(resolution.lines.text, LineRange(0, resolution.lines.size))
        }
    }
}

/** Whether two lines have the same text, a CR at their end left out. */
private fun sameText(a: ByteBuffer, b: ByteBuffer): Boolean = a.withoutCarriageReturn() == b.withoutCarriageReturn()

private fun ByteBuffer.withoutCarriageReturn(): ByteBuffer {
    val line = duplicate()

    if (line.hasRemaining() && line.get(line.limit() - 1) == '\r'.code.toByte()) {
        line.limit(line.limit() - 1)
    }

    return line
}

/** Where each chunk starts in what the merge writes, and where the last one ends, in lines. */
private fun TextMerge.chunkStarts(style: ConflictMarkerStyle): List<Int> {
    val starts = mutableListOf(0)

    for (chunk in chunks) {
        val lines = when (chunk) {
            is Chunk.Unchanged -> chunk.edited?.size ?: chunk.base.size
            is Chunk.Change -> when {
                chunk.edited != null -> chunk.edited!!.size
                !chunk.applied -> chunk.base.size
                chunk.by == ChangedBy.THEIRS -> chunk.theirs.size
                else -> chunk.ours.size
            }

            is Chunk.Conflict -> when (val resolution = chunk.resolution) {
                // <<<<<<<, ours, (|||||||, the base,) =======, theirs, >>>>>>>
                ConflictResolution.Unresolved ->
                    chunk.ours.size + chunk.theirs.size + 3 + if (style.withBase) chunk.base.size + 1 else 0

                ConflictResolution.Ours -> chunk.ours.size
                ConflictResolution.Theirs -> chunk.theirs.size
                ConflictResolution.OursThenTheirs, ConflictResolution.TheirsThenOurs -> chunk.ours.size + chunk.theirs.size
                ConflictResolution.Base -> chunk.base.size
                is ConflictResolution.Edited -> resolution.lines.size
            }
        }

        starts += starts.last() + lines
    }

    return starts
}

/** Whether [aRange] of [a] and [bRange] of [b] are the same lines, line endings included. */
private fun sameLines(a: RawText, aRange: LineRange, b: RawText, bRange: LineRange): Boolean {
    if (aRange.size != bRange.size) {
        return false
    }

    return (0 until aRange.size).all { offset ->
        val aLine = aRange.start + offset
        val bLine = bRange.start + offset

        a.getRawString(aLine) == b.getRawString(bLine) && a.hasLineEnding(aLine) == b.hasLineEnding(bLine)
    }
}

private fun RawText.hasLineEnding(line: Int) = line < size() - 1 || !isMissingNewlineAtEnd

/** The lines of [lines] of [text], each with its line ending as the file has it. */
private fun RawText.bytesOf(lines: LineRange): ByteArray {
    if (lines.isEmpty) {
        return ByteArray(0)
    }

    val out = ByteArrayOutputStream()

    for (line in lines.start until lines.end) {
        out.writeBytes(getRawString(line).toByteArray())

        if (hasLineEnding(line)) {
            out.write('\n'.code)
        }
    }

    return out.toByteArray()
}

private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

/** The file's first line ending, CRLF or LF. */
private fun RawText.firstLineEnding(): String? = (0 until size()).firstOrNull { hasLineEnding(it) }?.let { line ->
    val raw = getRawString(line)

    if (raw.hasRemaining() && raw.get(raw.limit() - 1) == '\r'.code.toByte()) "\r\n" else "\n"
}

/** This chunk with [lines] of [file] typed in the result. They get [lineEnding] when lines follow their last one. */
private fun Chunk.typing(file: RawText, lines: LineRange, lineEnding: String): Chunk {
    val bytes = file.bytesOf(lines)
    val typed = TypedLines(RawText(bytes), lineEnding)

    return when (this) {
        is Chunk.Unchanged -> copy(edited = typed)
        is Chunk.Change -> copy(edited = typed)
        is Chunk.Conflict -> copy(resolution = ConflictResolution.Edited(typed))
    }
}
