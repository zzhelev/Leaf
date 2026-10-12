// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.readGitOnlyConfigValues
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.merge.ContentMergeStrategy
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** The merge into chunks: JGit's merge, with each chunk's lines in the three versions. */
class TextMergeChunksTest {
    private val histogram = DiffAlgorithm.getAlgorithm(SupportedAlgorithm.HISTOGRAM)

    @Test
    fun `a change that one side made is a chunk with the lines it replaced in every version`() {
        val merge = merge(
            base = "1\n2\n3\n4\n5\n",
            ours = "1\nours 2\n3\n4\n5\n",
            theirs = "1\n2\n3\n4\ntheirs 5\ntheirs 6\n",
        )

        assertEquals(
            listOf(
                Chunk.Unchanged(base = lines(0, 1), ours = lines(0, 1), theirs = lines(0, 1)),
                Chunk.Change(base = lines(1, 2), ours = lines(1, 2), theirs = lines(1, 2), ChangedBy.OURS),
                Chunk.Unchanged(base = lines(2, 4), ours = lines(2, 4), theirs = lines(2, 4)),
                Chunk.Change(base = lines(4, 5), ours = lines(4, 5), theirs = lines(4, 6), ChangedBy.THEIRS),
            ),
            merge.chunks,
        )
    }

    @Test
    fun `lines that both sides changed alike next to a conflict are changes by both, and the conflict is the rest`() {
        // The probe in conflict-resolver.md 2.1: both changed lines 3 to 5, the same way at lines 3 and 5
        val merge = merge(
            base = "1\n2\n3\n4\n5\n6\n",
            ours = "1\n2\nX3\nours 4\nZ5\n6\n",
            theirs = "1\n2\nX3\ntheirs 4\nZ5\n6\n",
        )

        assertEquals(
            listOf(
                Chunk.Unchanged(base = lines(0, 2), ours = lines(0, 2), theirs = lines(0, 2)),
                Chunk.Change(base = lines(2, 3), ours = lines(2, 3), theirs = lines(2, 3), ChangedBy.BOTH),
                Chunk.Conflict(base = lines(3, 4), ours = lines(3, 4), theirs = lines(3, 4)),
                Chunk.Change(base = lines(4, 5), ours = lines(4, 5), theirs = lines(4, 5), ChangedBy.BOTH),
                Chunk.Unchanged(base = lines(5, 6), ours = lines(5, 6), theirs = lines(5, 6)),
            ),
            merge.chunks,
        )
    }

    @Test
    fun `a file that both sides added has an empty base, which JGit's range of 1 to -1 doesn't break`() {
        // JGit gives this conflict's base as [1,-1): a line alike at the start and one at the end, cut from nothing
        val merge = merge(
            base = "",
            ours = "package a\nours\nend\n",
            theirs = "package a\ntheirs\nend\n",
        )

        assertEquals(
            listOf(
                Chunk.Change(base = lines(0, 0), ours = lines(0, 1), theirs = lines(0, 1), ChangedBy.BOTH),
                Chunk.Conflict(base = lines(0, 0), ours = lines(1, 2), theirs = lines(1, 2)),
                Chunk.Change(base = lines(0, 0), ours = lines(2, 3), theirs = lines(2, 3), ChangedBy.BOTH),
            ),
            merge.chunks,
        )
    }

    @Test
    fun `lines that both sides have alike next to a conflict are unchanged when the base has them too`() {
        val merge = merge(base = "b\nb\nb\nb\n", ours = "b\n", theirs = "a\na\na\nb\n")

        assertEquals(
            listOf(
                Chunk.Conflict(base = lines(0, 3), ours = lines(0, 0), theirs = lines(0, 3)),
                Chunk.Unchanged(base = lines(3, 4), ours = lines(0, 1), theirs = lines(3, 4)),
            ),
            merge.chunks,
        )
    }

    @Test
    fun `a deletion and an insertion that touch, as Myers can give them, are one change`() {
        // Myers' edits from the base to ours: REPLACE(0-1,0-1), DELETE(3-4,3-3), INSERT(4-4,3-4)
        val merge = textMerge(
            RawText("a\nc\nb\nb\n".toByteArray()),
            RawText("b\nc\nb\na\n".toByteArray()),
            RawText("a\nc\nb\nb\n".toByteArray()),
            DiffAlgorithm.getAlgorithm(SupportedAlgorithm.MYERS),
        )

        assertEquals(
            listOf(
                Chunk.Change(base = lines(0, 1), ours = lines(0, 1), theirs = lines(0, 1), ChangedBy.OURS),
                Chunk.Unchanged(base = lines(1, 3), ours = lines(1, 3), theirs = lines(1, 3)),
                Chunk.Change(base = lines(3, 4), ours = lines(3, 4), theirs = lines(3, 4), ChangedBy.OURS),
            ),
            merge.chunks,
        )
    }

    @Test
    fun `lines that both sides deleted are a change by both with no lines on either side`() {
        val merge = merge(base = "1\n2\n3\n4\n", ours = "1\n4\n", theirs = "1\n4\n")

        assertEquals(
            listOf(
                Chunk.Unchanged(base = lines(0, 1), ours = lines(0, 1), theirs = lines(0, 1)),
                Chunk.Change(base = lines(1, 3), ours = lines(1, 1), theirs = lines(1, 1), ChangedBy.BOTH),
                Chunk.Unchanged(base = lines(3, 4), ours = lines(1, 2), theirs = lines(1, 2)),
            ),
            merge.chunks,
        )
    }

    @Test
    fun `an emptied file against a changed one is a conflict over the whole file`() {
        val merge = merge(base = "1\n2\n", ours = "", theirs = "1\n2\n3\n")

        assertEquals(listOf(Chunk.Conflict(base = lines(0, 2), ours = lines(0, 0), theirs = lines(0, 3))), merge.chunks)
    }

    @Test
    fun `an emptied file against an unchanged one is ours' change`() {
        val merge = merge(base = "1\n2\n", ours = "", theirs = "1\n2\n")

        assertEquals(
            listOf(Chunk.Change(base = lines(0, 2), ours = lines(0, 0), theirs = lines(0, 2), ChangedBy.OURS)),
            merge.chunks,
        )
    }

    @Test
    fun `the diff algorithm is the one that Leaf's merges read, and histogram when git refuses it too`() {
        fun algorithmFor(value: String?) = mergeDiffAlgorithm(
            Config().apply { if (value != null) setString("diff", null, "algorithm", value) }
        )::class

        val myers = DiffAlgorithm.getAlgorithm(SupportedAlgorithm.MYERS)::class

        // As main.kt reads every config
        readGitOnlyConfigValues()

        try {
            assertEquals(histogram::class, algorithmFor(null))
            assertEquals(myers, algorithmFor("myers"))
            assertEquals(myers, algorithmFor("Myers"))
            assertEquals(histogram::class, algorithmFor("histogram"))
            assertEquals(histogram::class, algorithmFor("patience"))
            assertEquals(myers, algorithmFor("minimal"))
            assertEquals(myers, algorithmFor("default"))
            assertEquals(histogram::class, algorithmFor("fast"))
        } finally {
            Config.setTypedConfigGetter(null)
        }
    }

    /**
     * Random three-way merges, checked against JGit's own merge and against the versions themselves:
     * - with every conflict open, the result is what JGit's formatter writes, in both conflict styles;
     * - taking ours in every conflict is JGit's merge that favours ours, and theirs likewise;
     * - keeping only ours' changes gives ours back, byte for byte, and the same for theirs and for the base. So each
     *   chunk has the right lines in each version.
     */
    @ParameterizedTest
    @EnumSource(SupportedAlgorithm::class)
    fun `random merges agree with JGit's and give each version back`(supportedAlgorithm: SupportedAlgorithm) {
        val algorithm = DiffAlgorithm.getAlgorithm(supportedAlgorithm)
        val random = Random(seed = 20261012)
        var conflicts = 0

        repeat(5_000) { case ->
            val base = randomText(random, lines = random.nextInt(0, 9))
            val ours = mutate(random, base)
            val theirs = mutate(random, base)
            val texts = listOf(base, ours, theirs).map { RawText(it.toByteArray()) }
            val description = "case $case: base ${base.quoted()}, ours ${ours.quoted()}, theirs ${theirs.quoted()}"

            val merge = try {
                textMerge(texts[0], texts[1], texts[2], algorithm)
            } catch (e: IllegalStateException) {
                throw AssertionError(description, e)
            }
            conflicts += merge.conflicts

            for (withBase in listOf(false, true)) {
                assertSameAsJGit(
                    jgitResult(texts, algorithm, ContentMergeStrategy.CONFLICT, withBase),
                    merge.resultBytes(labelled(withBase)).decodeToString(),
                    "$description, with base: $withBase",
                )
            }

            assertSameAsJGit(
                jgitResult(texts, algorithm, ContentMergeStrategy.OURS),
                merge.keeping(ChangedBy.OURS, ChangedBy.THEIRS, resolution = ConflictResolution.Ours).resultText(),
                "Ours, $description",
            )
            assertSameAsJGit(
                jgitResult(texts, algorithm, ContentMergeStrategy.THEIRS),
                merge.keeping(ChangedBy.OURS, ChangedBy.THEIRS, resolution = ConflictResolution.Theirs).resultText(),
                "Theirs, $description",
            )

            assertEquals(
                ours,
                merge.keeping(ChangedBy.OURS, resolution = ConflictResolution.Ours).resultText(),
                description,
            )
            assertEquals(
                theirs,
                merge.keeping(ChangedBy.THEIRS, resolution = ConflictResolution.Theirs).resultText(),
                description,
            )
            assertEquals(base, merge.keeping(resolution = ConflictResolution.Base).resultText(), description)
        }

        assertTrue(conflicts > 1_000, "Only $conflicts conflicts: the cases don't test conflicts much")
    }

    /**
     * JGit's formatter ends the file without a line ending when the last chunk it writes comes from a version whose
     * last line has none, even if that chunk ends before that line (`MergeFormatterPass.formatMerge`). The resolver
     * writes each line with its own line ending, as git does (`ConflictResultTest`).
     */
    private fun assertSameAsJGit(jgit: String, actual: String, message: String) {
        if (!jgit.endsWith("\n") && actual == jgit + "\n") {
            return
        }

        assertEquals(jgit, actual, message)
    }

    private fun merge(base: String, ours: String, theirs: String) =
        textMerge(RawText(base.toByteArray()), RawText(ours.toByteArray()), RawText(theirs.toByteArray()), histogram)
}

private fun lines(start: Int, end: Int) = LineRange(start, end)

private val ALPHABET = listOf("a", "b", "c", "d")

/** Lines from a small alphabet, so that the diffs find many equal lines; sometimes without the last line ending. */
private fun randomText(random: Random, lines: Int): String =
    List(lines) { ALPHABET.random(random) }.toText(random)

/** [text] with random lines deleted, replaced and inserted, and maybe a different last line ending. */
private fun mutate(random: Random, text: String): String {
    val lines = text.lines().dropLast(if (text.endsWith("\n") || text.isEmpty()) 1 else 0)
    val mutated = buildList {
        for (line in lines) {
            when (random.nextInt(20)) {
                0, 1 -> Unit
                2, 3, 4 -> add(ALPHABET.random(random))
                5, 6, 7 -> {
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

    return mutated.toText(random)
}

private fun List<String>.toText(random: Random): String {
    val text = joinToString("") { "$it\n" }

    return if (text.isNotEmpty() && random.nextInt(5) == 0) text.dropLast(1) else text
}

private fun String.quoted() = "\"" + replace("\n", "\\n") + "\""

private fun labelled(withBase: Boolean) =
    ConflictMarkerStyle(withBase, ConflictLabels(ours = "OURS", base = "BASE", theirs = "THEIRS"))

private fun TextMerge.resultText() = resultBytes(labelled(withBase = false)).decodeToString()

/** The merge with only [kept]'s changes applied (and changes by both), and [resolution] in every conflict. */
private fun TextMerge.keeping(vararg kept: ChangedBy, resolution: ConflictResolution) = copy(
    chunks = chunks.map { chunk ->
        when (chunk) {
            is Chunk.Change ->
                chunk.copy(applied = chunk.by in kept || (chunk.by == ChangedBy.BOTH && kept.isNotEmpty()))
            is Chunk.Conflict -> chunk.copy(resolution = resolution)
            is Chunk.Unchanged -> chunk
        }
    }
)

/** What JGit's merge writes, conflict markers included, with the labels of [labelled]. */
private fun jgitResult(
    texts: List<RawText>,
    algorithm: DiffAlgorithm,
    strategy: ContentMergeStrategy,
    withBase: Boolean = false,
): String {
    val result = MergeAlgorithm(algorithm)
        .apply { contentMergeStrategy = strategy }
        .merge(RawTextComparator.DEFAULT, texts[0], texts[1], texts[2])
    val names = listOf("BASE", "OURS", "THEIRS")
    val out = ByteArrayOutputStream()

    if (withBase) {
        MergeFormatter().formatMergeDiff3(out, result, names, Charsets.UTF_8)
    } else {
        MergeFormatter().formatMerge(out, result, names, Charsets.UTF_8)
    }

    return out.toString(Charsets.UTF_8)
}
