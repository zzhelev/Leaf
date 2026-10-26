// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.data.highlighting.TextMateHighlighter
import dev.app.leaf.domain.highlighting.DiffHighlights
import dev.app.leaf.domain.DiffMatchPatch
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.MatchLine
import dev.app.leaf.domain.models.SplitHunk
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.SyntaxColors
import dev.app.leaf.theme.styles.resolveStyleSide
import dev.app.leaf.ui.HistoryContentLoaded
import dev.app.leaf.viewmodels.HistoryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The two versions of a Kotlin file. A block comment starts above the hunk, and the old version has a raw string. */
private val OLD = """
    /* A comment
       that goes on
       val notCode = 1
    */
    val name = "Leaf"
    fun count(x: Int): Int = x + 42
    val text = ""${'"'}
    fun inside()
    ""${'"'}
""".trimIndent() + "\n"

private val NEW = """
    /* A comment
       that goes on
       val notCode = 1
    */
    val name = "Leaf"
    fun count(x: Int): Int = x + 43
    val text = ""
    fun inside()
    ""
""".trimIndent() + "\n"

private fun line(text: String, old: Int, new: Int, type: LineType) = Line("$text\n", old, new, type)

private val contextComment = line("   val notCode = 1", 2, 2, LineType.CONTEXT)
private val contextName = line("val name = \"Leaf\"", 4, 4, LineType.CONTEXT)
private val removedCount = line("fun count(x: Int): Int = x + 42", 5, 5, LineType.REMOVED)
private val addedCount = line("fun count(x: Int): Int = x + 43", 6, 5, LineType.ADDED)
private val contextInside = line("fun inside()", 7, 7, LineType.CONTEXT)

/** A diff of [path] whose versions are [OLD] and [NEW]. */
private class Demo(path: String) {
    val entry = object : DiffEntry() {
        init {
            changeType = ChangeType.MODIFY
            oldPath = path
            newPath = path
        }
    }
    val oldText = RawText(OLD.toByteArray())
    val newText = RawText(NEW.toByteArray())
    val hunk = Hunk(
        "@@ -3,6 +3,6 @@",
        listOf(contextComment, line("*/", 3, 3, LineType.CONTEXT), contextName, removedCount, addedCount, contextInside),
        oldText,
        newText,
    )
    val diffType = DiffType.CommitDiff(entry)
    val unified = DiffResult.Text(entry, listOf(hunk))
    val split = DiffResult.TextSplit(
        entry,
        listOf(SplitHunk(hunk, listOf(contextComment to contextComment, contextName to contextName, removedCount to addedCount, contextInside to contextInside))),
    )

    /** Its versions coloured as the loader colours them, by the real highlighter. */
    fun highlights(): DiffHighlights = runBlocking {
        val useCase = HighlightTextUseCase(highlighter)
        DiffHighlights(useCase(entry.oldPath, oldText), useCase(entry.newPath, newText))
    }
}

private val highlighter = TextMateHighlighter()

/**
 * The diff pane's lines in their versions' colours (fork-only, `docs/fork/syntax-highlighting.md`, step 5), rendered
 * with the real highlighter, in both themes, unified and split.
 */
class DiffSyntaxTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(diff: DiffResult.TextDiff, diffType: DiffType, highlights: () -> DiffHighlights?, theme: Theme = Theme.Light) {
        scene?.close()
        scene = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
            AppTheme(selectedTheme = theme) {
                CompositionLocalProvider(LocalDiffHighlights provides highlights()) {
                    when (diff) {
                        is DiffResult.Text -> HunkUnifiedTextDiff(
                            diffType, rememberLazyListState(), diff, false, { _, _ -> }, { _, _ -> },
                            { _, _, _ -> }, { _, _ -> }, { _, _, _ -> },
                        )

                        is DiffResult.TextSplit -> HunkSplitTextDiff(
                            diffType, rememberLazyListState(), diff, false, { _, _ -> }, { _, _ -> },
                            { _, _ -> }, { _, _, _ -> }, { _, _, _ -> },
                        )
                    }
                }
            }
        }.also { it.render() }
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    /** The texts drawn for lines whose text is [text], in the order they're laid out: left to right in a split diff. */
    private fun drawn(text: String): List<Pair<AnnotatedString, Rect>> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().filter { it.text == text }.map { it to node.boundsInRoot }
    }.sortedBy { it.second.left }

    /** The colour that [drawn] draws the first [piece] in, or null when none. */
    private fun colourOf(drawn: AnnotatedString, piece: String): Color? {
        val at = drawn.text.indexOf(piece)
        check(at >= 0) { "No \"$piece\" in \"${drawn.text}\"" }
        return drawn.spanStyles.lastOrNull { it.start <= at && at < it.end && it.item.color != Color.Unspecified }?.item?.color
    }

    private fun syntaxColours(isLight: Boolean): SyntaxColors =
        resolveStyleSide(BuiltInStyles.DEFAULT, isLight).palette.toSyntaxColors()

    @Test
    fun `each category takes its colour, in both themes`(): Unit = runBlocking(Dispatchers.Swing) {
        val demo = Demo("src/Demo.kt")
        val highlights = demo.highlights()

        for ((theme, isLight) in listOf(Theme.Light to true, Theme.Dark to false)) {
            show(demo.unified, demo.diffType, { highlights }, theme)
            val colours = syntaxColours(isLight)

            val name = drawn("val name = \"Leaf\"").single().first
            assertEquals(colours.keyword, colourOf(name, "val"), "$theme")
            assertEquals(colours.string, colourOf(name, "\"Leaf\""), "$theme")
            assertEquals(null, colourOf(name, "name"), "$theme")

            val added = drawn("fun count(x: Int): Int = x + 43").single().first
            assertEquals(colours.keyword, colourOf(added, "fun"), "$theme")
            assertEquals(colours.function, colourOf(added, "count"), "$theme")
            assertEquals(colours.type, colourOf(added, "Int"), "$theme")
            assertEquals(colours.number, colourOf(added, "43"), "$theme")
            assertEquals(colours.colorOf(SyntaxCategory.NUMBER), colourOf(drawn("fun count(x: Int): Int = x + 42").single().first, "42"))
        }
    }

    @Test
    fun `a comment that starts above the hunk colours its lines in it`(): Unit = runBlocking(Dispatchers.Swing) {
        val demo = Demo("src/Demo.kt")
        val highlights = demo.highlights()
        show(demo.unified, demo.diffType, { highlights })

        val inComment = drawn("   val notCode = 1").single().first
        assertEquals(syntaxColours(isLight = true).comment, colourOf(inComment, "val"))
        assertEquals(syntaxColours(isLight = true).comment, colourOf(inComment, "1"))
    }

    @Test
    fun `a removed line takes the old version's colours, and the old side of a split diff too`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val demo = Demo("src/Demo.kt")
            val highlights = demo.highlights()
            val colours = syntaxColours(isLight = true)

            // In the old version, `fun inside()` is in a raw string; in the new one, it's code
            show(demo.unified, demo.diffType, { highlights })
            val unified = drawn("fun inside()").single().first
            assertEquals(colours.keyword, colourOf(unified, "fun"))
            assertEquals(colours.function, colourOf(unified, "inside"))

            show(demo.split, demo.diffType, { highlights })
            val (left, right) = drawn("fun inside()").map { it.first }
            assertEquals(colours.string, colourOf(left, "fun"))
            assertEquals(colours.string, colourOf(left, "inside"))
            assertEquals(colours.keyword, colourOf(right, "fun"))

            // A removed line is the old version's line whatever its text: here the one inside the old raw string
            val removedInside = line("fun inside()", 7, 7, LineType.REMOVED)
            val oneLine = DiffResult.Text(demo.entry, listOf(demo.hunk.copy(lines = listOf(removedInside))))
            show(oneLine, demo.diffType, { highlights })
            assertEquals(colours.string, colourOf(drawn("fun inside()").single().first, "fun"))
        }

    @Test
    fun `colours arrive after the text, and move nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        val demo = Demo("src/Demo.kt")
        val highlights = demo.highlights()
        var shown: DiffHighlights? by mutableStateOf(null)

        show(demo.unified, demo.diffType, { shown })
        val (plain, before) = drawn("fun count(x: Int): Int = x + 43").single()
        assertEquals(null, colourOf(plain, "fun"))

        shown = highlights
        scene!!.render()

        val (coloured, after) = drawn("fun count(x: Int): Int = x + 43").single()
        assertEquals(syntaxColours(isLight = true).keyword, colourOf(coloured, "fun"))
        assertEquals(before, after)
        assertEquals(plain.text, coloured.text)
    }

    @Test
    fun `a file that nothing names is drawn plain`(): Unit = runBlocking(Dispatchers.Swing) {
        val demo = Demo("LICENSE")
        val highlights = demo.highlights()
        assertEquals(null, highlights.old)
        assertEquals(null, highlights.new)

        show(demo.unified, demo.diffType, { highlights })

        for (text in listOf("val name = \"Leaf\"", "fun count(x: Int): Int = x + 43", "   val notCode = 1")) {
            val drawn = drawn(text).single().first
            assertTrue(drawn.spanStyles.none { it.item.color != Color.Unspecified }, "$text: ${drawn.spanStyles}")
        }
    }

    @Test
    fun `a line whose word diff is cut from another line is drawn plain`(): Unit = runBlocking(Dispatchers.Swing) {
        val demo = Demo("src/Demo.kt")
        val highlights = demo.highlights()
        // The word diff's pieces aren't this line's text, so its spans would colour other characters
        val pieces = MatchLine(listOf(DiffMatchPatch.Diff(DiffMatchPatch.Operation.INSERT, "val other = 0\n")))
        val paired = addedCount.copy(textDiffed = pieces)
        show(DiffResult.Text(demo.entry, listOf(demo.hunk.copy(lines = listOf(paired)))), demo.diffType, { highlights })

        val drawn = drawn("val other = 0").single().first
        assertTrue(drawn.spanStyles.none { it.item.color != Color.Unspecified }, "${drawn.spanStyles}")
    }

    @Test
    fun `file history draws its diff in the versions' colours`(): Unit = runBlocking(Dispatchers.Swing) {
        val demo = Demo("src/Demo.kt")
        val highlights = demo.highlights()
        val author = Identity("Maya Chen", "maya.chen@example.com")
        val commit = Commit("a".repeat(40), "Count", author, author, 0L, emptyList())
        scene?.close()
        scene = ImageComposeScene(width = 1200, height = 600, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                HistoryContentLoaded(
                    historyState = HistoryState.Loaded("src/Demo.kt", listOf(commit)),
                    viewDiffResult = ViewDiffResult.Loaded(demo.diffType, demo.unified),
                    scrollState = LazyListState(),
                    selectedCommit = commit,
                    onCommitSelected = {},
                    onSelectAdjacentCommit = {},
                    highlights = highlights,
                )
            }
        }.also { it.render() }

        val added = drawn("fun count(x: Int): Int = x + 43").single().first
        assertEquals(syntaxColours(isLight = true).keyword, colourOf(added, "fun"))
        assertEquals(syntaxColours(isLight = true).comment, colourOf(drawn("   val notCode = 1").single().first, "val"))
    }
}
