// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The bytes that Save writes, compared with `git merge-file`: the same merge from the same three versions, for clean
 * merges and for conflicts whose lines git and JGit find alike.
 */
class ConflictResultTest {
    @TempDir
    lateinit var tempDir: File

    private val histogram = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
    private val labels = ConflictLabels(ours = "HEAD", base = "parent of a1b2c3d (Fix parser)", theirs = "feature")

    @Test
    fun `a clean merge is git's`() {
        assertSameAsGit(
            base = "fun load() {\n    val x = parse()\n    log(x)\n}\n\nfun save() {\n    write()\n}\n",
            ours = "fun load() {\n    val x = parse(strict = true)\n    log(x)\n}\n\nfun save() {\n    write()\n}\n",
            theirs = "fun load() {\n    val x = parse()\n    log(x)\n}\n\nfun save() {\n    flush()\n    write()\n}\n",
        )
    }

    @Test
    fun `CRLF line endings stay, in clean merges and in conflict markers`() {
        assertSameAsGit(base = "a\r\nb\r\nc\r\n", ours = "a\r\nours\r\nc\r\n", theirs = "a\r\nb\r\nc\r\nd\r\n")
        assertSameAsGit(base = "a\r\nb\r\nc\r\n", ours = "a\r\nours\r\nc\r\n", theirs = "a\r\ntheirs\r\nc\r\n")
    }

    @Test
    fun `markers of a file that both sides added with CRLF end with LF, as git can't tell from an empty base`() {
        val result = assertSameAsGit(base = "", ours = "ours\r\n", theirs = "theirs\r\n")

        assertEquals("<<<<<<< HEAD\nours\r\n=======\ntheirs\r\n>>>>>>> feature\n", result.decodeToString())
    }

    @Test
    fun `a file in Latin-1 keeps its bytes`() {
        val base = "café\nnaïve\nüber\n".toByteArray(Charsets.ISO_8859_1)
        val ours = "café crème\nnaïve\nüber\n".toByteArray(Charsets.ISO_8859_1)
        val theirs = "café\nnaïve\nüber alles\n".toByteArray(Charsets.ISO_8859_1)
        val conflicting = "café au lait\nnaïve\nüber\n".toByteArray(Charsets.ISO_8859_1)

        assertSameAsGit(base, ours, theirs)
        assertSameAsGit(base, ours, conflicting)
    }

    @Test
    fun `a missing line ending at the end stays missing, and a line without one gets one before a marker`() {
        // Clean: the last line comes from ours, without a line ending
        assertSameAsGit(base = "a\nb\nc", ours = "a\nb\nours", theirs = "theirs\nb\nc")
        // Conflict at the end: git adds the line ending before "=======" and after ">>>>>>>"
        val conflict = assertSameAsGit(base = "a\nb", ours = "a\nours", theirs = "a\ntheirs")

        assertEquals("a\n<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> feature\n", conflict.decodeToString())
    }

    @Test
    fun `a line keeps its line ending when the file's last line, without one, was deleted by both`() {
        // JGit's formatter writes "x\na" here: its last chunk is the base's, whose last line has no line ending
        val result = assertSameAsGit(base = "x\na\nb", ours = "x\na\n", theirs = "x\na\n")

        assertEquals("x\na\n", result.decodeToString())
        assertEquals("x\na", jgitResult(base = "x\na\nb", ours = "x\na\n", theirs = "x\na\n"))
    }

    @Test
    fun `diff3 style writes the base's lines with their label`() {
        assertSameAsGit(
            base = "1\n2\n3\n",
            ours = "1\nours\n3\n",
            theirs = "1\ntheirs\n3\n",
            withBase = true,
        )
    }

    @Test
    fun `markers have the length that conflict-marker-size asks for`() {
        val result =
            assertSameAsGit(base = "1\n2\n3\n", ours = "1\nours\n3\n", theirs = "1\ntheirs\n3\n", markerSize = 10)

        assertTrue(result.decodeToString().contains("\n<<<<<<<<<< HEAD\n"))
    }

    @Test
    fun `ours then theirs is git's union merge, a missing line ending included`() {
        for ((base, ours, theirs) in listOf(
            Triple("1\n2\n3\n", "1\nours\n3\n", "1\ntheirs\n3\n"),
            Triple("a\r\nb", "a\r\nours", "a\r\ntheirs"),
        )) {
            val merge = merge(base.toByteArray(), ours.toByteArray(), theirs.toByteArray())

            assertEquals(
                gitMergeFile(base.toByteArray(), ours.toByteArray(), theirs.toByteArray(), "--union").decodeToString(),
                merge.resolvingAll(ConflictResolution.OursThenTheirs).resultBytes(style()).decodeToString(),
            )
        }
    }

    @Test
    fun `each resolution writes its lines`() {
        val merge = merge("1\n2\n3\n".toByteArray(), "1\nours\n3\n".toByteArray(), "1\ntheirs\n3\n".toByteArray())

        fun result(resolution: ConflictResolution) =
            merge.resolvingAll(resolution).resultBytes(style()).decodeToString()

        assertEquals("1\nours\n3\n", result(ConflictResolution.Ours))
        assertEquals("1\ntheirs\n3\n", result(ConflictResolution.Theirs))
        assertEquals("1\nours\ntheirs\n3\n", result(ConflictResolution.OursThenTheirs))
        assertEquals("1\ntheirs\nours\n3\n", result(ConflictResolution.TheirsThenOurs))
        assertEquals("1\n2\n3\n", result(ConflictResolution.Base))
        assertEquals(
            "1\ntyped\r\nlines\n3\n",
            result(ConflictResolution.Edited(RawText("typed\r\nlines".toByteArray()))),
        )
    }

    @Test
    fun `a change that isn't applied writes the base's lines`() {
        val merge = merge("1\n2\n3\n".toByteArray(), "1\nours\n3\n".toByteArray(), "1\n2\n3\ntheirs\n".toByteArray())
        val unapplied =
            merge.copy(chunks = merge.chunks.map { if (it is Chunk.Change) it.copy(applied = false) else it })

        assertEquals("1\nours\n3\ntheirs\n", merge.resultBytes(style()).decodeToString())
        assertEquals("1\n2\n3\n", unapplied.resultBytes(style()).decodeToString())
    }

    @Test
    fun `diff3 and zdiff3 ask for the base's lines`() {
        fun withBase(style: String?) = conflictStyleWithBase(
            Config().apply { if (style != null) setString("merge", null, "conflictStyle", style) }
        )

        assertFalse(withBase(null))
        assertFalse(withBase("merge"))
        assertTrue(withBase("diff3"))
        assertTrue(withBase("zdiff3"))
    }

    private fun assertSameAsGit(
        base: String,
        ours: String,
        theirs: String,
        withBase: Boolean = false,
        markerSize: Int = DEFAULT_CONFLICT_MARKER_SIZE,
    ) = assertSameAsGit(base.toByteArray(), ours.toByteArray(), theirs.toByteArray(), withBase, markerSize)

    /** Checks that the result with every conflict open is `git merge-file`'s, and returns it. */
    private fun assertSameAsGit(
        base: ByteArray,
        ours: ByteArray,
        theirs: ByteArray,
        withBase: Boolean = false,
        markerSize: Int = DEFAULT_CONFLICT_MARKER_SIZE,
    ): ByteArray {
        val options = buildList {
            if (withBase) add("--diff3")
            add("--marker-size=$markerSize")
        }
        val expected = gitMergeFile(base, ours, theirs, *options.toTypedArray())
        val actual = merge(base, ours, theirs).resultBytes(style(withBase, markerSize))

        // Compared as Latin-1, which shows every byte
        assertEquals(expected.toString(Charsets.ISO_8859_1), actual.toString(Charsets.ISO_8859_1))

        return actual
    }

    private fun style(withBase: Boolean = false, markerSize: Int = DEFAULT_CONFLICT_MARKER_SIZE) =
        ConflictMarkerStyle(withBase, labels, markerSize)

    private fun merge(base: ByteArray, ours: ByteArray, theirs: ByteArray) =
        textMerge(RawText(base), RawText(ours), RawText(theirs), histogram)

    /** What `git merge-file -p` writes, with the labels of [labels] and git's config left out. */
    private fun gitMergeFile(base: ByteArray, ours: ByteArray, theirs: ByteArray, vararg options: String): ByteArray {
        val files = listOf("ours" to ours, "base" to base, "theirs" to theirs).map { (name, content) ->
            File(tempDir, name).apply { writeBytes(content) }
        }

        val process = ProcessBuilder(
            listOf(
                "git", "merge-file", "-p", "--diff-algorithm=histogram",
                "-L", labels.ours, "-L", labels.base, "-L", labels.theirs,
                *options,
            ) + files.map { it.absolutePath }
        )
            .directory(tempDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = File(tempDir, "no-config").absolutePath
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()

        val output = process.inputStream.readBytes()

        // The exit code is the number of conflicts; a negative one is an error
        check(process.waitFor() in 0..127) { "git merge-file failed" }

        return output
    }

    private fun jgitResult(base: String, ours: String, theirs: String): String {
        val result = MergeAlgorithm(histogram).merge(
            RawTextComparator.DEFAULT,
            RawText(base.toByteArray()),
            RawText(ours.toByteArray()),
            RawText(theirs.toByteArray()),
        )

        return ByteArrayOutputStream()
            .also { MergeFormatter().formatMerge(it, result, listOf("BASE", "OURS", "THEIRS"), Charsets.UTF_8) }
            .toString(Charsets.UTF_8)
    }
}
