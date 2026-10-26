// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.data.highlighting.TextMateHighlighter
import dev.app.leaf.domain.conflicts.textBefore
import dev.app.leaf.domain.conflicts.typing
import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.highlighting.decodedText
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.SyntaxColors
import dev.app.leaf.theme.styles.resolveStyleSide
import dev.app.leaf.viewmodels.ConflictResolverState
import dev.app.leaf.viewmodels.ResolverFile
import dev.app.leaf.viewmodels.ResolverFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val PATH = "src/ConfigLoader.kt"

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)

private val merge = TextMerge(
    base = text("/* Loads", "   the config. */", "fun load(): Config {", "    val text = read(path)", "    return parse(text)", "}"),
    ours = text(
        "/* Loads", "   the config. */", "fun load(): Config {", "    val text = Path.of(path).readText()",
        "    return parse(text)", "}",
    ),
    theirs = text(
        "/* Loads", "   the config. */", "fun load(): Config {", "    val text = retry(3) { read(path) }",
        "    return parse(text)", "}",
    ),
    chunks = listOf(
        Chunk.Unchanged(lines(0, 3), lines(0, 3), lines(0, 3)),
        Chunk.Conflict(lines(3, 4), lines(3, 4), lines(3, 4)),
        Chunk.Unchanged(lines(4, 6), lines(4, 6), lines(4, 6)),
    ),
)

private val highlight = HighlightTextUseCase(TextMateHighlighter())

/** The versions of [merge] in their colours, as the view model colours them. */
private val versions: HighlightedVersions = runBlocking {
    listOf(merge.base, merge.ours, merge.theirs).fold(HighlightedVersions()) { versions, text ->
        versions.with(text, PATH, highlight(PATH, text))
    }
}

/**
 * The conflict resolver's rows in their versions' colours (fork-only, step 6 of `docs/fork/syntax-highlighting.md`),
 * rendered with the real highlighter, in both layouts.
 */
class ResolverSyntaxColoursTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun close() {
        scene?.close()
    }

    private val colours: SyntaxColors = resolveStyleSide(BuiltInStyles.DEFAULT, isLight = false).palette.toSyntaxColors()

    private val actions = ResolverActions(
        onClose = {}, onSelectFile = {}, onPreviousConflict = {}, onNextConflict = {}, onToggleBase = {},
        onSetLayout = {}, onStackedSplitChanged = {}, onColumnSharesChanged = {}, onFileListWidthChanged = {},
        onToggleWrapLines = {}, onToggleFocus = {}, onSave = {}, onMarkResolved = {}, onOpenInExternalTool = {},
        onMarkResolvedAsItIs = {}, onResolveAgain = {}, onAnswerUnsaved = {}, onReload = {}, onDismissPrompt = {},
        onNextFile = {}, picks = PickActions({ _, _ -> }, { _, _ -> }, { _, _ -> }), onUndo = {},
        edits = EditActions(onType = {}, onFinish = {}),
    )

    private fun state(merge: TextMerge, syntax: HighlightedVersions, layout: ConflictResolverLayout) = ConflictResolverState(
        files = listOf(ResolverFileEntry(StatusEntry(PATH, StatusType.CONFLICTING), resolvedHere = false)),
        file = ResolverFile.Loaded(
            file = ConflictedFile(
                path = PATH,
                base = ConflictStage("1".repeat(40), 0b1000000110100100),
                ours = ConflictStage("2".repeat(40), 0b1000000110100100),
                theirs = ConflictStage("3".repeat(40), 0b1000000110100100),
                conflict = FileConflict(ConflictVersion.FILE, ConflictVersion.FILE),
                sides = null,
                content = ConflictContent.Text(merge, WorkingFileCheck(WorkingFileState.MATCHES, 1, "id")),
            ),
            merge = merge,
            saved = merge,
        ),
        layout = layout,
        syntax = syntax,
    )

    private fun show(state: ConflictResolverState) {
        scene?.close()
        scene = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
            AppTheme {
                ConflictResolverContent(state, ResolverLists(), actions)
            }
        }.also { scene -> repeat(10) { scene.render(it * 16_000_000L) } }
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return nodes
    }

    /** The texts drawn for lines whose text is [text]. */
    private fun drawn(text: String): List<AnnotatedString> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().filter { it.text == text } }

    /** The colour that [drawn] draws the first [piece] in, or null when none. */
    private fun colourOf(drawn: AnnotatedString, piece: String): Color? {
        val at = drawn.text.indexOf(piece)
        check(at >= 0) { "No \"$piece\" in \"${drawn.text}\"" }
        return drawn.spanStyles.lastOrNull { it.start <= at && at < it.end && it.item.color != Color.Unspecified }?.item?.color
    }

    @Test
    fun `the rows take their versions' colours, in both layouts`(): Unit = runBlocking(Dispatchers.Swing) {
        for (layout in ConflictResolverLayout.entries) {
            show(state(merge, versions, layout))

            // Ours, the result and theirs
            val loads = drawn("fun load(): Config {")
            assertEquals(3, loads.size, "$layout")
            for (load in loads) {
                assertEquals(colours.keyword, colourOf(load, "fun"), "$layout")
                assertEquals(colours.function, colourOf(load, "load"), "$layout")
                assertEquals(colours.type, colourOf(load, "Config"), "$layout")
            }

            // A comment that started on the line before: each version's own colouring
            for (comment in drawn("   the config. */")) {
                assertEquals(colours.comment, colourOf(comment, "config"), "$layout")
            }

            // The conflict's sides
            assertEquals(colours.number, colourOf(drawn("    val text = retry(3) { read(path) }").single(), "3"), "$layout")
            assertEquals(colours.keyword, colourOf(drawn("    val text = Path.of(path).readText()").single(), "val"), "$layout")
        }
    }

    @Test
    fun `lines typed take the colours of their own text, after the lines before them`(): Unit = runBlocking(Dispatchers.Swing) {
        // Typed in place of the last part's lines, and coloured after lines that open a comment
        val typed = TypedLines(text("    of the app */", "fun load(): Config {"))
        val edited = merge.typing(2, TypedLines(text("    return parse(text) // typed", "}")))
        val withTyped = merge.typing(2, typed)
        val coloured = runBlocking { highlight.inContext(PATH, "/* The config\n", typed.text.decodedText()) }
        val syntax = versions.with(typed.text, PATH, coloured)

        for (layout in ConflictResolverLayout.entries) {
            show(state(withTyped, syntax, layout))

            // In the result only: the sides keep the chunk's own lines
            val inComment = drawn("    of the app */").single()
            assertEquals(colours.comment, colourOf(inComment, "app"), "$layout")
        }

        // Lines typed that nothing coloured yet are plain, while the others keep their colours
        show(state(edited, versions, ConflictResolverLayout.COLUMNS))
        val plain = drawn("    return parse(text) // typed").single()
        assertTrue(plain.spanStyles.none { it.item.color != Color.Unspecified }, "${plain.spanStyles}")
        assertEquals(colours.keyword, colourOf(drawn("fun load(): Config {").first(), "fun"))
        assertEquals("/* Loads\n   the config. */\nfun load(): Config {\n    val text = Path.of(path).readText()\n", merge.textBefore(2))
    }

    @Test
    fun `without colours, the rows are plain`(): Unit = runBlocking(Dispatchers.Swing) {
        show(state(merge, HighlightedVersions(), ConflictResolverLayout.COLUMNS))

        for (load in drawn("fun load(): Config {")) {
            assertTrue(load.spanStyles.none { it.item.color != Color.Unspecified }, "${load.spanStyles}")
        }
    }
}
