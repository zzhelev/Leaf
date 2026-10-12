// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeChunk
import org.eclipse.jgit.merge.MergeChunk.ConflictState

private const val BASE = 0
private const val OURS = 1
private const val THEIRS = 2

/** The comparator of JGit's content merges (`ResolveMerger`): lines are equal when their bytes are, line ending too. */
private val COMPARATOR = RawTextComparator.DEFAULT

/**
 * The diff algorithm that JGit's merges use, read as `ResolveMerger.getMergeAlgorithm` reads it: `diff.algorithm`,
 * through the reader that `main.kt` installs (`GitCompatibleConfigGetter`, which takes git's `default` and `minimal` as
 * myers and `patience` as histogram), and histogram when it's not set. A value that git refuses too fails Leaf's
 * merges, but not the resolver: it gets histogram.
 */
internal fun mergeDiffAlgorithm(config: Config): DiffAlgorithm {
    val algorithm = try {
        config.getEnum(
            ConfigConstants.CONFIG_DIFF_SECTION,
            null,
            ConfigConstants.CONFIG_KEY_ALGORITHM,
            DiffAlgorithm.SupportedAlgorithm.HISTOGRAM,
        )
    } catch (_: IllegalArgumentException) {
        DiffAlgorithm.SupportedAlgorithm.HISTOGRAM
    }

    return DiffAlgorithm.getAlgorithm(algorithm)
}

/**
 * Merges [ours] and [theirs] from [base] with JGit's `MergeAlgorithm`, the content merge of Leaf's merges, rebases,
 * cherry-picks and reverts, so the conflicts are the ones that such a merge writes into the file.
 *
 * JGit's chunks say where their lines are in one version only: a change that one side made carries that side's lines,
 * not the base's lines it replaced, and a conflict's base lines are cut down by the lines that ours and theirs have
 * alike at its start and end (`[1,-1)` when both added the file). So the merge's parts are found again from the two
 * diffs that it walks (base to ours, base to theirs), as it walks them:
 * - lines that neither diff changes;
 * - an edit of one side that no edit of the other touches: that side's change;
 * - edits of both sides that overlap or touch, joined as long as they reach the next one: a conflict, with the lines
 *   that both sides have alike at its start and end, which JGit merges as ours. They become changes by both, or
 *   unchanged lines when the base has them too. Without a conflict, both made the same change.
 *
 * Each part takes JGit's chunks for it, which must fit, and becomes chunks with their lines in the three versions.
 * Throws [IllegalStateException] when JGit's chunks don't fit, which would be a bug: the columns wouldn't line up.
 */
internal fun textMerge(base: RawText, ours: RawText, theirs: RawText, algorithm: DiffAlgorithm): TextMerge {
    val mergeChunks = MergeAlgorithm(algorithm).merge(COMPARATOR, base, ours, theirs).toList()

    val chunks = if (ours.size() == 0 || theirs.size() == 0) {
        // JGit merges an empty side without diffs: the other side's change, or a conflict over the whole file
        wholeFileChunks(base, ours, theirs, mergeChunks)
    } else {
        val parts = mergeParts(
            oursEdits = algorithm.diff(COMPARATOR, base, ours),
            theirsEdits = algorithm.diff(COMPARATOR, base, theirs),
            baseSize = base.size(),
        )

        ChunkMatcher(base, ours, mergeChunks).chunksOf(parts)
    }

    return TextMerge(base, ours, theirs, chunks.withAdjacentChangesJoined()).also { it.checkCoversEveryLine() }
}

private enum class PartKind {
    UNCHANGED,
    OURS,
    THEIRS,

    /** Edits of both sides, which overlap or touch. */
    BOTH,
}

/** A part of the merge, with its lines in each version. */
private data class MergePart(val kind: PartKind, val base: LineRange, val ours: LineRange, val theirs: LineRange)

/** The end of a diff, as JGit's merge marks it. */
private val END_EDIT = Edit(Int.MAX_VALUE, Int.MAX_VALUE)

/**
 * The parts of the merge, as JGit's `MergeAlgorithm.merge` walks the edits of the two diffs: the same comparisons, in
 * the same order, so the parts are the ones that its chunks come from.
 */
private fun mergeParts(oursEdits: EditList, theirsEdits: EditList, baseSize: Int): List<MergePart> {
    val parts = mutableListOf<MergePart>()
    val oursIterator = oursEdits.iterator()
    val theirsIterator = theirsEdits.iterator()

    fun Iterator<Edit>.nextEdit() = if (hasNext()) next() else END_EDIT

    var ours = oursIterator.nextEdit()
    var theirs = theirsIterator.nextEdit()

    // The first base line not walked yet, and how far its line is from it in ours and in theirs
    var current = 0
    var oursShift = 0
    var theirsShift = 0

    fun unchangedUntil(end: Int) {
        if (end > current) {
            parts += MergePart(
                PartKind.UNCHANGED,
                base = LineRange(current, end),
                ours = LineRange(current + oursShift, end + oursShift),
                theirs = LineRange(current + theirsShift, end + theirsShift),
            )
        }
    }

    while (ours !== END_EDIT || theirs !== END_EDIT) {
        when {
            ours.endA < theirs.beginA -> {
                unchangedUntil(ours.beginA)
                parts += MergePart(
                    PartKind.OURS,
                    base = LineRange(ours.beginA, ours.endA),
                    ours = LineRange(ours.beginB, ours.endB),
                    theirs = LineRange(ours.beginA + theirsShift, ours.endA + theirsShift),
                )
                current = ours.endA
                oursShift = ours.endB - ours.endA
                ours = oursIterator.nextEdit()
            }

            theirs.endA < ours.beginA -> {
                unchangedUntil(theirs.beginA)
                parts += MergePart(
                    PartKind.THEIRS,
                    base = LineRange(theirs.beginA, theirs.endA),
                    ours = LineRange(theirs.beginA + oursShift, theirs.endA + oursShift),
                    theirs = LineRange(theirs.beginB, theirs.endB),
                )
                current = theirs.endA
                theirsShift = theirs.endB - theirs.endA
                theirs = theirsIterator.nextEdit()
            }

            else -> {
                val start = minOf(ours.beginA, theirs.beginA)
                unchangedUntil(start)

                // Each side's lines from the base's start of the part: the side whose edit starts later has the base's
                // lines until then
                val oursStart = ours.beginB - (ours.beginA - start)
                val theirsStart = theirs.beginB - (theirs.beginA - start)

                // Join the next edits of either side while they reach into the part
                var nextOurs = oursIterator.nextEdit()
                var nextTheirs = theirsIterator.nextEdit()

                while (true) {
                    if (ours.endA >= nextTheirs.beginA) {
                        theirs = nextTheirs
                        nextTheirs = theirsIterator.nextEdit()
                    } else if (theirs.endA >= nextOurs.beginA) {
                        ours = nextOurs
                        nextOurs = oursIterator.nextEdit()
                    } else {
                        break
                    }
                }

                val end = maxOf(ours.endA, theirs.endA)
                val oursEnd = ours.endB + (end - ours.endA)
                val theirsEnd = theirs.endB + (end - theirs.endA)

                parts += MergePart(
                    PartKind.BOTH,
                    base = LineRange(start, end),
                    ours = LineRange(oursStart, oursEnd),
                    theirs = LineRange(theirsStart, theirsEnd),
                )

                current = end
                oursShift = oursEnd - end
                theirsShift = theirsEnd - end
                ours = nextOurs
                theirs = nextTheirs
            }
        }
    }

    unchangedUntil(baseSize)

    return parts
}

/** Matches JGit's chunks, in order, to the merge's parts. */
private class ChunkMatcher(
    private val base: RawText,
    private val ours: RawText,
    private val mergeChunks: List<MergeChunk>,
) {
    private var next = 0

    fun chunksOf(parts: List<MergePart>): List<Chunk> {
        val chunks = parts.flatMap { part ->
            when (part.kind) {
                PartKind.UNCHANGED -> {
                    take(BASE, ConflictState.NO_CONFLICT, part.base)
                    listOf(Chunk.Unchanged(part.base, part.ours, part.theirs))
                }

                PartKind.OURS -> {
                    take(OURS, ConflictState.NO_CONFLICT, part.ours)
                    listOf(Chunk.Change(part.base, part.ours, part.theirs, ChangedBy.OURS))
                }

                PartKind.THEIRS -> {
                    take(THEIRS, ConflictState.NO_CONFLICT, part.theirs)
                    listOf(Chunk.Change(part.base, part.ours, part.theirs, ChangedBy.THEIRS))
                }

                PartKind.BOTH -> bothChanged(part)
            }
        }

        check(next == mergeChunks.size) { "JGit's chunks from ${describe(next)} are left over" }

        return chunks
    }

    private fun bothChanged(part: MergePart): List<Chunk> {
        // The lines that both sides have alike at the start, if any
        val alikeAtStart = mergeChunks.getOrNull(next)?.takeIf {
            it.sequenceIndex == OURS && it.conflictState == ConflictState.NO_CONFLICT &&
                it.begin == part.ours.start && it.size in 1..minOf(part.ours.size, part.theirs.size)
        }?.also { next++ }
        val startSize = alikeAtStart?.size ?: 0

        if (mergeChunks.getOrNull(next)?.conflictState != ConflictState.FIRST_CONFLICTING_RANGE) {
            // Both made the same change: JGit merges it as ours. Lines that both deleted have no chunk.
            check(startSize == part.ours.size && part.ours.size == part.theirs.size) {
                "Edits of both sides at ${part.base} without a conflict, before ${describe(next)}"
            }

            return listOf(Chunk.Change(part.base, part.ours, part.theirs, ChangedBy.BOTH))
        }

        val conflictOurs = take(OURS, ConflictState.FIRST_CONFLICTING_RANGE, range = null).toRange()
        val conflictBase = take(BASE, ConflictState.BASE_CONFLICTING_RANGE, range = null)
        val conflictTheirs = take(THEIRS, ConflictState.NEXT_CONFLICTING_RANGE, range = null).toRange()
        val endSize = part.ours.end - conflictOurs.end

        check(
            conflictOurs.start == part.ours.start + startSize &&
                conflictTheirs.start == part.theirs.start + startSize &&
                endSize == part.theirs.end - conflictTheirs.end
        ) { "A conflict that doesn't fit the edits at ${part.base}, at ${describe(next - 3)}" }

        if (endSize > 0) {
            // The lines that both sides have alike at the end
            take(OURS, ConflictState.NO_CONFLICT, LineRange(conflictOurs.end, part.ours.end))
        }

        // JGit's base lines can start after they end, or outside the part: an empty or short base
        val conflictBaseStart = conflictBase.start.coerceIn(part.base.start, part.base.end)
        val conflictBaseEnd = conflictBase.end.coerceIn(conflictBaseStart, part.base.end)

        return listOfNotNull(
            alike(
                base = LineRange(part.base.start, conflictBaseStart),
                ours = LineRange(part.ours.start, conflictOurs.start),
                theirs = LineRange(part.theirs.start, conflictTheirs.start),
            ),
            Chunk.Conflict(LineRange(conflictBaseStart, conflictBaseEnd), conflictOurs, conflictTheirs),
            alike(
                base = LineRange(conflictBaseEnd, part.base.end),
                ours = LineRange(conflictOurs.end, part.ours.end),
                theirs = LineRange(conflictTheirs.end, part.theirs.end),
            ),
        )
    }

    /** Lines that ours and theirs have alike next to a conflict: unchanged when the base has them too. */
    private fun alike(base: LineRange, ours: LineRange, theirs: LineRange): Chunk? = when {
        base.isEmpty && ours.isEmpty -> null
        sameLines(this.base, base, this.ours, ours) -> Chunk.Unchanged(base, ours, theirs)
        else -> Chunk.Change(base, ours, theirs, ChangedBy.BOTH)
    }

    /**
     * Takes JGit's next chunk, which must be of [sequence], in [state] and, when given, of [range]. JGit's base range
     * of a conflict can be inverted, so it's returned as it is, without its order checked.
     */
    private fun take(sequence: Int, state: ConflictState, range: LineRange?): LineRangeOrInverted {
        val chunk = mergeChunks.getOrNull(next)

        check(
            chunk != null && chunk.sequenceIndex == sequence && chunk.conflictState == state &&
                (range == null || (chunk.begin == range.start && chunk.end == range.end))
        ) { "Expected $state of sequence $sequence ${range ?: ""} at ${describe(next)}" }

        next++

        return LineRangeOrInverted(chunk.begin, chunk.end)
    }

    private fun describe(index: Int) = mergeChunks.drop(index).take(5).joinToString(prefix = "[", postfix = "]") {
        "${it.conflictState} ${it.sequenceIndex} [${it.begin},${it.end})"
    }
}

/** A chunk's lines as JGit gives them, before they're known to be in order. */
private data class LineRangeOrInverted(val start: Int, val end: Int)

private fun LineRangeOrInverted.toRange() = LineRange(start, end)

private val MergeChunk.size get() = end - begin

/** The chunks of a merge where ours or theirs is empty, which JGit merges without diffs. */
private fun wholeFileChunks(base: RawText, ours: RawText, theirs: RawText, mergeChunks: List<MergeChunk>): List<Chunk> {
    val baseLines = LineRange(0, base.size())
    val oursLines = LineRange(0, ours.size())
    val theirsLines = LineRange(0, theirs.size())

    if (mergeChunks.any { it.conflictState == ConflictState.FIRST_CONFLICTING_RANGE }) {
        return listOf(Chunk.Conflict(baseLines, oursLines, theirsLines))
    }

    val oursChanged = !sameLines(base, baseLines, ours, oursLines)
    val theirsChanged = !sameLines(base, baseLines, theirs, theirsLines)
    val by = when {
        oursChanged && theirsChanged -> ChangedBy.BOTH
        oursChanged -> ChangedBy.OURS
        theirsChanged -> ChangedBy.THEIRS
        // All three are empty
        else -> return emptyList()
    }

    return listOf(Chunk.Change(baseLines, oursLines, theirsLines, by))
}

/** Joins changes of one side that touch, as one side's diff can split a change into a deletion and an insertion. */
private fun List<Chunk>.withAdjacentChangesJoined(): List<Chunk> = fold(mutableListOf()) { chunks, chunk ->
    val previous = chunks.lastOrNull()

    if (previous is Chunk.Change && chunk is Chunk.Change && previous.by == chunk.by) {
        chunks[chunks.lastIndex] = previous.copy(
            base = LineRange(previous.base.start, chunk.base.end),
            ours = LineRange(previous.ours.start, chunk.ours.end),
            theirs = LineRange(previous.theirs.start, chunk.theirs.end),
        )
    } else {
        chunks += chunk
    }

    chunks
}

private fun sameLines(a: RawText, aRange: LineRange, b: RawText, bRange: LineRange) =
    aRange.size == bRange.size &&
        (0 until aRange.size).all { COMPARATOR.equals(a, aRange.start + it, b, bRange.start + it) }

private fun TextMerge.checkCoversEveryLine() {
    fun check(version: String, size: Int, range: (Chunk) -> LineRange) {
        var line = 0

        for (chunk in chunks) {
            val chunkRange = range(chunk)
            check(chunkRange.start == line) { "The $version lines of $chunk don't start at $line" }
            line = chunkRange.end
        }

        check(line == size) { "The chunks end at $version line $line of $size" }
    }

    check("base", base.size()) { it.base }
    check("ours", ours.size()) { it.ours }
    check("theirs", theirs.size()) { it.theirs }
}
