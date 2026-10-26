// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.conflicts.ColumnShares
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Color
import dev.app.leaf.domain.conflicts.resolving
import dev.app.leaf.domain.conflicts.ChangedWords
import dev.app.leaf.domain.conflicts.editStart
import dev.app.leaf.domain.conflicts.editStartSyntax
import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import dev.app.leaf.data.highlighting.TextMateHighlighter
import dev.app.leaf.domain.conflicts.typing
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsObjectDownloadError
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.CommitRelation
import dev.app.leaf.domain.models.CommitSummary
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictPreview
import dev.app.leaf.domain.models.KeptChanges
import dev.app.leaf.domain.models.LfsObject
import dev.app.leaf.domain.models.PreviewImage
import dev.app.leaf.domain.models.PreviewLine
import dev.app.leaf.domain.models.PreviewLineType
import dev.app.leaf.domain.models.RenamedFile
import dev.app.leaf.domain.models.SubmodulePreview
import dev.app.leaf.domain.models.VersionPreview
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictResolverLayout
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TextLimit
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.TypedLines
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.styleColorsFor
import dev.app.leaf.theme.styled
import dev.app.leaf.viewmodels.ChoicePick
import dev.app.leaf.viewmodels.ConflictResolverState
import dev.app.leaf.viewmodels.LfsDownload
import dev.app.leaf.viewmodels.ResolverFile
import dev.app.leaf.viewmodels.ResolverFileEntry
import dev.app.leaf.viewmodels.ResolverHandOff
import dev.app.leaf.viewmodels.ResolverLeave
import dev.app.leaf.viewmodels.ResolverPrompt
import dev.app.leaf.viewmodels.ResolverEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.RawText
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** The resolver's view, rendered offscreen from a state, without its view model. */
class ConflictResolverTest {
    private val sides = ConflictSides(
        ConflictOperation.MERGE,
        ours = ConflictCommit("main", "1111111", "Main"),
        theirs = ConflictCommit("feature/retry", "2222222", "Retry"),
    )

    // ours added an import; both changed the read, differently; theirs added a function
    private val merge = TextMerge(
        base = text("package demo", "", "fun load(): Config {", "    val text = read(path)", "    return parse(text)", "}"),
        ours = text(
            "package demo", "", "import java.nio.file.Path", "", "fun load(): Config {",
            "    val text = Path.of(path).readText()", "    return parse(text)", "}",
        ),
        theirs = text(
            "package demo", "", "fun load(): Config {", "    val text = retry(3) { read(path) }",
            "    return parse(text)", "}", "", "fun <T> retry(times: Int, block: () -> T): T = block()",
        ),
        chunks = listOf(
            Chunk.Unchanged(lines(0, 2), lines(0, 2), lines(0, 2)),
            Chunk.Change(lines(2, 2), lines(2, 4), lines(2, 2), ChangedBy.OURS),
            Chunk.Unchanged(lines(2, 3), lines(4, 5), lines(2, 3)),
            Chunk.Conflict(lines(3, 4), lines(5, 6), lines(3, 4)),
            Chunk.Unchanged(lines(4, 6), lines(6, 8), lines(4, 6)),
            Chunk.Change(lines(6, 6), lines(8, 8), lines(6, 8), ChangedBy.THEIRS),
        ),
    )

    private val entry = StatusEntry(
        "src/ConfigLoader.kt",
        StatusType.CONFLICTING,
        conflict = FileConflict(ConflictVersion.FILE, ConflictVersion.FILE),
    )

    /** The merge's versions in their colours, as the view model colours them (step 6 of the syntax plan). */
    private val syntax: HighlightedVersions by lazy {
        runBlocking {
            val highlight = HighlightTextUseCase(TextMateHighlighter())
            listOf(merge.base, merge.ours, merge.theirs).fold(HighlightedVersions()) { versions, text ->
                versions.with(text, entry.filePath, highlight(entry.filePath, text))
            }
        }
    }

    @Test
    fun `the conflict counter is a chip in the style's warning strip, not the conflict amber`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (style in AccentStyle.entries) {
                for ((theme, base) in listOf(Theme.Light to lightTheme, Theme.Dark to darkTheme)) {
                    val strip = styleColorsFor(style, base.styled(style)).warningStrip
                    val scene = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
                        AppTheme(selectedTheme = theme, style = BuiltInStyles.ofLook(style)) {
                            ConflictResolverContent(state(), ResolverLists(), actions())
                        }
                    }

                    try {
                        scene.render()
                        val bitmap = Bitmap.makeFromImage(scene.render())
                        val counter = scene.nodes().first { node ->
                            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "Conflict 1 of 1" }
                        }.boundsInRoot
                        // Where the chip's padding is, either side of the text's start
                        val row = ((counter.left - 6).toInt()..(counter.left + 2).toInt())
                            .map { x -> bitmap.getColor(x, counter.center.y.toInt()) }

                        fun Int.isClose(color: Color) = listOf(16, 8, 0).all { shift ->
                            abs(((this shr shift) and 0xFF) - ((color.toArgb() shr shift) and 0xFF)) <= 2
                        }

                        assertTrue(row.any { it.isClose(strip.background) }, "$style $theme: the chip's fill")

                        if (style != AccentStyle.CLASSIC) {
                            assertTrue(row.none { it.isClose(base.conflictingFile) }, "$style $theme: the conflict amber")
                        }
                    } finally {
                        scene.close()
                    }
                }
            }
        }

    @Test
    fun `the columns name each side, and the toolbar counts the conflicts`(): Unit = runBlocking(Dispatchers.Swing) {
        val texts = render(state()).texts

        assertTrue("Ours: main" in texts, "$texts")
        assertTrue("Theirs: feature/retry" in texts, "$texts")
        assertTrue("Conflict 1 of 1" in texts, "$texts")
        assertTrue("0 resolved · 2 merged cleanly" in texts, "$texts")
        assertTrue("Conflict 1 · open: ▶ takes ours, ◀ takes theirs" in texts, "$texts")
    }

    @Test
    fun `the words that each side changed are marked inside their lines, in a stronger shade of the line's colour`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val words = ChangedWords(merge)
            val oursLine = merge.ours.getString(5)
            val theirsLine = merge.theirs.getString(3)

            for (layout in ConflictResolverLayout.entries) {
                val marks = marksShown(state().copy(layout = layout))

                // Both sides of the open conflict, in the conflict's colour: the result shows neither
                assertEquals(listOf(words.of(merge.ours, 5)), marks[oursLine]?.map { it.ranges }, "$layout")
                assertEquals(listOf(words.of(merge.theirs, 3)), marks[theirsLine]?.map { it.ranges }, "$layout")
                assertTrue(words.of(merge.ours, 5).isNotEmpty() && words.of(merge.theirs, 3).isNotEmpty())
                assertEquals(0.45f, marks.getValue(oursLine).single().color.alpha, 0.01f, "$layout")

                // Lines added whole get none: the import and theirs' function
                assertEquals(listOf<IntRange>(), marks["import java.nio.file.Path"]?.flatMap { it.ranges }.orEmpty())
                assertEquals(listOf<IntRange>(), marks["fun <T> retry(times: Int, block: () -> T): T = block()"]?.flatMap { it.ranges }.orEmpty())
            }

            // The text after the last mark is drawn too
            val counted = TextMerge(
                base = text("val n = 1 // items"),
                ours = text("val n = 2 // items"),
                theirs = text("val n = 1 // items"),
                chunks = listOf(Chunk.Change(lines(0, 1), lines(0, 1), lines(0, 1), ChangedBy.OURS)),
            )

            assertEquals(listOf(listOf(8..8), listOf(8..8)), marksShown(state(counted))["val n = 2 // items"]?.map { it.ranges })

            // Theirs taken: the result has theirs' line and its marks, in the colour of a resolved conflict
            for (layout in ConflictResolverLayout.entries) {
                val resolved = marksShown(state(merge.resolving(3, ConflictResolution.Theirs)).copy(layout = layout))
                val colours = resolved.getValue(theirsLine).map { it.color }

                // Theirs' column in the conflict's colour, the result in a resolved conflict's
                assertEquals(2, colours.distinct().size, "$layout: $colours")
                assertEquals(
                    listOf(words.of(merge.theirs, 3), words.of(merge.theirs, 3)),
                    resolved.getValue(theirsLine).map { it.ranges },
                    "$layout",
                )
            }
        }

    @Test
    fun `the gutters' buttons don't stack, and each makes its row no taller than a button`(): Unit = runBlocking(Dispatchers.Swing) {
        val next = "    return parse(text)"
        val open = render(state())
        val openTop = open.textBounds.getValue(next).first().top

        // Theirs taken: + in ours' gutter, and × in theirs', where theirs' arrow was. They used to stack in ours'
        val theirs = render(state(merge.resolving(3, ConflictResolution.Theirs)))
        val add = theirs.bounds.getValue("Add ours after theirs: main").single()
        val reopen = theirs.bounds.getValue("Open this conflict again").single()

        assertEquals(open.bounds.getValue("Take ours: main").single(), add)
        assertEquals(open.bounds.getValue("Take theirs: feature/retry").single(), reopen)
        assertEquals(openTop, theirs.textBounds.getValue(next).first().top, 0.5f)

        // The base taken, in a conflict of two lines: ours' arrow on its first row, and × on its second
        val twoLines = TextMerge(
            base = text("a", "b1", "b2", "c"),
            ours = text("a", "o1", "o2", "c"),
            theirs = text("a", "t1", "t2", "c"),
            chunks = listOf(
                Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
                Chunk.Conflict(lines(1, 3), lines(1, 3), lines(1, 3)),
                Chunk.Unchanged(lines(3, 4), lines(3, 4), lines(3, 4)),
            ),
        )
        val openTwoLines = render(state(twoLines))
        val lineHeight = openTwoLines.textBounds.getValue("a").first().height
        val base = render(state(twoLines.resolving(1, ConflictResolution.Base)))
        val arrow = base.bounds.getValue("Take ours: main").single()
        val back = base.bounds.getValue("Open this conflict again").single()

        assertTrue(back.top >= arrow.bottom && abs(back.left - arrow.left) < 1f, "$arrow, $back")
        // The second row is as tall as a button, as the first already is: a few pixels over its line, where a
        // second button on the first row added a whole button
        val grown = base.textBounds.getValue("c").first().top - openTwoLines.textBounds.getValue("c").first().top

        assertTrue(grown <= arrow.height + 2f - lineHeight + 0.5f, "Grew $grown, a line is $lineHeight, a button $arrow")
    }

    @Test
    fun `the arrows take a side, and a change can be undone`(): Unit = runBlocking(Dispatchers.Swing) {
        val taken = mutableListOf<Pair<Int, ConflictSide>>()
        val applied = mutableListOf<Pair<Int, Boolean>>()
        val columns = PickActions(
            onTake = { chunk, side -> taken += chunk to side },
            onResolve = { _, _ -> },
            onSetApplied = { chunk, value -> applied += chunk to value },
        )

        render(state(), actions(columns), clickDescription = "Take ours: main")
        render(state(), actions(columns), clickDescription = "Take theirs: feature/retry")
        render(state(), actions(columns), clickDescription = "Undo this change: the result gets the base's lines")

        assertEquals(listOf(3 to ConflictSide.OURS, 3 to ConflictSide.THEIRS), taken)
        // The first change on the left is ours'
        assertEquals(1 to false, applied.first())
    }

    @Test
    fun `once ours is taken, theirs' arrow adds after it and the conflict can be opened again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val resolved = state(merge.copy(chunks = merge.chunks.mapIndexed { index, chunk ->
                if (index == 3) (chunk as Chunk.Conflict).copy(resolution = ConflictResolution.Ours) else chunk
            }))
            val rendered = render(resolved)

            assertTrue("Add theirs after ours: feature/retry" in rendered.descriptions, "${rendered.descriptions}")
            assertTrue("Open this conflict again" in rendered.descriptions, "${rendered.descriptions}")
            assertTrue("Take ours: main" !in rendered.descriptions)
            assertTrue("1 resolved · 2 merged cleanly" in rendered.texts)
        }

    @Test
    fun `Mark resolved does nothing while a conflict is open`(): Unit = runBlocking(Dispatchers.Swing) {
        var marked = 0
        val actions = actions(onMarkResolved = { marked++ })

        assertTrue("Mark resolved" !in render(state(), actions).clickable)

        val resolved = state(merge.copy(chunks = merge.chunks.mapIndexed { index, chunk ->
            if (index == 3) (chunk as Chunk.Conflict).copy(resolution = ConflictResolution.Theirs) else chunk
        }))
        render(resolved, actions, clickText = "Mark resolved")
        assertEquals(1, marked)
    }

    @Test
    fun `a file without markers is offered to be marked resolved as it is, or resolved again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var asItIs = 0
            var again = 0
            val state = state(workingFile = WorkingFileState.NO_MARKERS)

            render(state, actions(onMarkResolvedAsItIs = { asItIs++ }), clickText = "Mark resolved as it is")
            render(state, actions(onResolveAgain = { again++ }), clickText = "Resolve again")

            assertEquals(1 to 1, asItIs to again)
            assertTrue("No conflict markers left" in render(state).texts)
        }

    @Test
    fun `a file whose markers don't match the merge says so above the columns`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val texts = render(state(workingFile = WorkingFileState.DIFFERENT, conflictsInFile = 2)).texts

            assertTrue(texts.any { it.startsWith("The file's conflict markers don't match the merge: 2 conflicts") }, "$texts")
        }

    @Test
    fun `a binary conflict has a card per side, and a click on a card picks it`(): Unit = runBlocking(Dispatchers.Swing) {
        val picked = mutableListOf<ConflictSide>()
        val state = choice(
            ConflictContent.Binary,
            ConflictPreview(VersionPreview.File(9), VersionPreview.File(48 * 1024), VersionPreview.File(51 * 1024)),
        )

        val texts = render(state, actions(onPick = { picked += it }), clickText = "Ours: main").texts
        render(state, actions(onPick = { picked += it }), clickText = "Theirs: feature/retry")

        assertEquals(listOf(ConflictSide.OURS, ConflictSide.THEIRS), picked)
        assertTrue("A binary file, changed on both sides. git can't merge it line by line." in texts, "$texts")
        assertTrue("Binary · 48 KB" in texts && "Binary · 51 KB" in texts, "$texts")
        assertTrue("Open in the default app" in texts, "$texts")
        assertTrue(texts.any { it.startsWith("Click a card, or press ← or →, to pick a side.") }, "$texts")
        // Nothing picked yet
        assertTrue(texts.none { it.startsWith("✓") }, "$texts")
    }

    @Test
    fun `without a preview, the cards still name the sides and can be picked, and say nothing they don't know`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val picked = mutableListOf<ConflictSide>()

            val texts = render(choice(ConflictContent.Binary, preview = null), actions(onPick = { picked += it }), clickText = "Ours: main").texts

            assertEquals(listOf(ConflictSide.OURS), picked)
            assertTrue("A binary file, changed on both sides. git can't merge it line by line." in texts, "$texts")
            assertTrue(texts.none { it.startsWith("The file is gone") || it == "Deleted" }, "$texts")

            // The index still says which side deleted it
            val deleted = render(
                choice(ConflictContent.Deleted, preview = null) {
                    it.copy(theirs = null, conflict = FileConflict(ConflictVersion.FILE, null))
                }
            ).texts

            assertTrue("The file is gone in feature/retry." in deleted && "Deleted" in deleted, "$deleted")
        }

    @Test
    fun `a submodule that one side deleted says so`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = choice(
            ConflictContent.Submodule,
            ConflictPreview("1".repeat(40).let { VersionPreview.Commit(it, null) }, VersionPreview.Commit("2".repeat(40), null), null),
        ) { it.copy(theirs = null, conflict = FileConflict(ConflictVersion.SUBMODULE, null)) }

        val texts = render(state).texts

        assertTrue("theirs (feature/retry) deleted this file, and ours (main) changed it." in texts, "$texts")
        assertTrue("The file is gone in feature/retry." in texts && "Commit 2222222" in texts, "$texts")
    }

    @Test
    fun `Mark resolved waits for a pick, then stages it`(): Unit = runBlocking(Dispatchers.Swing) {
        val preview = ConflictPreview(null, VersionPreview.File(9), VersionPreview.File(9))
        var marked = 0

        // Nothing to mark before a pick: no Mark resolved, on the cards or in the toolbar
        val unpicked = render(choice(ConflictContent.Binary, preview)).texts

        assertTrue("Mark resolved" !in unpicked && unpicked.none { it.startsWith("Mark resolved (") }, "$unpicked")

        // The card picked has it, next to the pick, and the toolbar doesn't
        val picked = choice(ConflictContent.Binary, preview, pick = ConflictSide.THEIRS)
        val texts = render(picked, actions(onMarkResolved = { marked++ }), clickText = "Mark resolved (${acceptShortcut()})").texts

        assertEquals(1, marked)
        assertTrue("✓ Use theirs (feature/retry)" in texts, "$texts")
        assertTrue("Mark resolved" !in texts, "$texts")
        // The text's Save and the columns' switches have nothing to do here
        assertTrue("Save" !in texts && "Wrap lines" !in texts, "$texts")
    }

    @Test
    fun `once the file is resolved, the card picked says what was done, and the hint goes`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val preview = ConflictPreview(null, VersionPreview.File(9), VersionPreview.File(9))
            val resolved = choice(ConflictContent.Binary, preview, pick = ConflictSide.THEIRS)
                .copy(handOff = ResolverHandOff(from = entry.filePath, resolvedHere = true, next = "docs/guide.md"))

            var opened = 0
            val texts = render(resolved, actions(onNextFile = { opened++ }), clickText = "Next file (${acceptShortcut()})").texts

            // One Next file, on the card, where Mark resolved was: the toolbar and the strip have none
            assertEquals(1, opened)
            assertEquals(1, texts.count { it.startsWith("Next file") }, "$texts")
            assertTrue(
                "${entry.filePath} is resolved and staged. ${acceptShortcut()} opens docs/guide.md." in texts,
                "$texts",
            )
            assertTrue("✓ Use theirs (feature/retry)" in texts, "$texts")
            assertTrue(texts.none { it.startsWith("Mark resolved (") || it.startsWith("Click a card") }, "$texts")
            assertTrue("Mark resolved" !in render(resolved).clickable)
            // The cards are dimmed and take no picks
            assertTrue(render(resolved).clickable.none { it.startsWith("Ours: main") || it.startsWith("Theirs: feature/retry") })
        }

    @Test
    fun `a resolved text file has Next file where Mark resolved was`(): Unit = runBlocking(Dispatchers.Swing) {
        var opened = 0
        val resolved = state().copy(handOff = ResolverHandOff(from = entry.filePath, resolvedHere = true, next = "docs/guide.md"))

        val rendered = render(resolved, actions(onNextFile = { opened++ }), clickText = "Next file")

        assertEquals(1, opened)
        assertEquals(1, rendered.texts.count { it.startsWith("Next file") }, "${rendered.texts}")
        assertTrue("Mark resolved" !in rendered.clickable && "Save" !in rendered.clickable, "${rendered.clickable}")
        // At the right end, as Mark resolved was, before the Focus icon
        assertTrue(rendered.textBounds.getValue("Next file").single().left > 1200, "${rendered.textBounds["Next file"]}")
    }

    @Test
    fun `a file that theirs deleted shows ours' changes, and what deleting does`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val kept = KeptChanges(
                added = 1,
                removed = 1,
                lines = listOf(
                    PreviewLine(PreviewLineType.HUNK, "@@ -1 +1 @@"),
                    PreviewLine(PreviewLineType.REMOVED, "base"),
                    PreviewLine(PreviewLineType.ADDED, "main's kept"),
                ),
                more = 2,
            )
            val state = choice(
                ConflictContent.Deleted,
                ConflictPreview(VersionPreview.File(5), VersionPreview.File(12), null, kept, workingFileEdited = true),
                pick = ConflictSide.THEIRS,
                showBase = true,
            ) { it.copy(theirs = null, conflict = FileConflict(ConflictVersion.FILE, null)) }

            val texts = render(state).texts

            assertTrue("theirs (feature/retry) deleted this file, and ours (main) changed it." in texts, "$texts")
            assertTrue("Changed · +1 −1" in texts && "Deleted" in texts, "$texts")
            assertTrue("- base" in texts && "+ main's kept" in texts && "2 more lines" in texts, "$texts")
            assertTrue("The file is gone in feature/retry." in texts, "$texts")
            assertTrue(texts.any { it.startsWith("The working file was edited after the merge stopped.") }, "$texts")
            assertTrue("✓ Delete the file, as theirs (feature/retry) did" in texts, "$texts")
            // The diff shows the base: no card for it, and no Base in the toolbar
            assertTrue("Base" !in texts, "$texts")

            val keeping = render(state.copy(file = (state.file as ResolverFile.Loaded).copy(pick = ChoicePick.OURS)))

            assertTrue("✓ Keep ours (main)" in keeping.texts, "${keeping.texts}")

            val unchanged = state.file.file.preview!!.copy(kept = kept.copy(0, 0, emptyList(), 0))
            val same = render(
                choice(ConflictContent.Deleted, unchanged) {
                    it.copy(theirs = null, conflict = FileConflict(ConflictVersion.FILE, null))
                }
            ).texts

            assertTrue("The same as the base" in same && "Changed · +0 −0" in same, "$same")
        }

    @Test
    fun `Base adds the base's card between the sides`(): Unit = runBlocking(Dispatchers.Swing) {
        val preview = ConflictPreview(VersionPreview.File(7 * 1024), VersionPreview.File(9), VersionPreview.File(9))

        val hidden = render(choice(ConflictContent.Binary, preview)).texts
        val shown = render(choice(ConflictContent.Binary, preview, showBase = true))

        // The toolbar's toggle, then the card's title
        assertEquals(1, hidden.count { it == "Base" }, "$hidden")
        assertEquals(2, shown.texts.count { it == "Base" }, "${shown.texts}")
        assertTrue("Binary · 7 KB" in shown.texts, "${shown.texts}")

        val (ours, base, theirs) = listOf("Ours: main", "Binary · 7 KB", "Theirs: feature/retry")
            .map { shown.textBounds.getValue(it).first().left }

        assertTrue(ours < base && base < theirs, "$ours $base $theirs")
    }

    @Test
    fun `a submodule's cards name its commits, and say how they relate`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = choice(
            ConflictContent.Submodule,
            ConflictPreview(
                base = null,
                ours = VersionPreview.Commit("2".repeat(40), CommitSummary("Bump the tokenizer", "Ana Petrova", 0)),
                theirs = VersionPreview.Commit("3".repeat(40), summary = null),
                submodule = SubmodulePreview(true, CommitRelation.OURS_CONTAINS_THEIRS, checkedOut = "c".repeat(40)),
            ),
        )

        val texts = render(state).texts

        assertTrue("Commit 2222222" in texts && "Commit 3333333" in texts, "$texts")
        assertTrue("Bump the tokenizer" in texts && texts.any { it.startsWith("Ana Petrova · ") }, "$texts")
        assertTrue("Not in the submodule's repository" in texts, "$texts")
        assertTrue("2222222 already contains 3333333." in texts, "$texts")
        assertTrue(
            "The submodule has ccccccc checked out. Its checkout doesn't change either way." in texts,
            "$texts",
        )

        fun notes(submodule: SubmodulePreview) =
            render(choice(ConflictContent.Submodule, (state.file as ResolverFile.Loaded).file.preview!!.copy(submodule = submodule))).texts

        assertTrue("3333333 already contains 2222222." in notes(SubmodulePreview(true, CommitRelation.THEIRS_CONTAINS_OURS, null)))
        assertTrue("Neither commit contains the other." in notes(SubmodulePreview(true, CommitRelation.DIVERGED, null)))

        val uninitialized = notes(SubmodulePreview(initialized = false, relation = null, checkedOut = null))

        assertTrue("The submodule isn't initialized, so its commits can't be named." in uninitialized, "$uninitialized")
        assertTrue(uninitialized.none { "contains" in it || "checked out" in it }, "$uninitialized")
    }

    @Test
    fun `text too large to merge shows each side's size and lines`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = choice(
            ConflictContent.TooLarge(size = 14_260_000, limit = TextLimit.Size(10L * 1024 * 1024)),
            ConflictPreview(null, VersionPreview.File(14_260_000, lines = 260_001), VersionPreview.File(900, lines = 12)),
        )

        val texts = render(state).texts

        assertTrue("Text larger than 10 MB on a side, too large for the resolver." in texts, "$texts")
        assertTrue("Text · 13.6 MB" in texts && "260,001 lines" in texts && "12 lines" in texts, "$texts")
        assertTrue("Open in the default app" !in texts, "$texts")
    }

    @Test
    fun `text that took too long to compare says so, with each side's size and lines`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val state = choice(
                ConflictContent.TooLarge(size = 5_400_000, limit = TextLimit.Time(2.seconds)),
                ConflictPreview(null, VersionPreview.File(5_400_000, lines = 116_946), VersionPreview.File(5_300_000, lines = 116_946)),
            )

            val texts = render(state).texts

            assertTrue("Too many changes to compare here: comparing the versions took over 2 seconds." in texts, "$texts")
            assertTrue(texts.none { it.startsWith("Text larger than") }, "$texts")
            assertTrue(texts.count { it == "Text · 5.1 MB" } == 2 && texts.count { it == "116,946 lines" } == 2, "$texts")
        }

    @Test
    fun `Git LFS cards show each object, and offer to open the one downloaded`(): Unit = runBlocking(Dispatchers.Swing) {
        val ours = LfsObject("a".repeat(64), 4 * 1024)
        val theirs = LfsObject("b".repeat(64), 5 * 1024)
        val state = choice(
            ConflictContent.Lfs(ours, theirs),
            ConflictPreview(
                base = null,
                ours = VersionPreview.Lfs(ours, downloaded = true),
                theirs = VersionPreview.Lfs(theirs, downloaded = false),
            ),
        )

        val texts = render(state).texts

        assertTrue("LFS · 4 KB" in texts && "LFS · 5 KB" in texts, "$texts")
        assertTrue("Object aaaaaaaaaaaa…" in texts && "Object bbbbbbbbbbbb…" in texts, "$texts")
        assertEquals(1, texts.count { it == "Open in the default app" }, "$texts")
        assertTrue("Not downloaded yet" in texts && "Download 5 KB" in texts, "$texts")
    }

    @Test
    fun `an LFS object that isn't downloaded has Download on its card, which doesn't pick the card`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val downloads = mutableListOf<LfsObject>()
            val picked = mutableListOf<ConflictSide>()

            val actions = actions(onPick = { picked += it }, onDownloadLfs = { downloads += it })

            val texts = render(lfsChoice(), actions, clickText = "Download 5 KB").texts

            assertEquals(listOf(lfsTheirs), downloads)
            assertEquals(emptyList<ConflictSide>(), picked)
            assertEquals(1, texts.count { it == "Download 5 KB" }, "$texts")
        }

    @Test
    fun `the base's object has Download on the base's card too`(): Unit = runBlocking(Dispatchers.Swing) {
        val downloads = mutableListOf<LfsObject>()
        val state = lfsChoice(showBase = true)

        render(state, actions(onDownloadLfs = { downloads += it }), clickText = "Download 3 KB")

        assertEquals(listOf(lfsBase), downloads)
    }

    @Test
    fun `a download shows how much arrived, and Cancel stops it`(): Unit = runBlocking(Dispatchers.Swing) {
        val cancelled = mutableListOf<LfsObject>()
        val state = lfsChoice().copy(lfsDownloads = mapOf(lfsTheirs.oid to LfsDownload.Running(received = 2 * 1024)))

        val scene = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
            AppTheme {
                ConflictResolverContent(state, ResolverLists(), actions(onCancelLfsDownload = { cancelled += it }))
            }
        }

        scene.closing {
            it.render()

            assertTrue("Downloading… 2 KB of 5 KB" in it.texts(), "${it.texts()}")
            assertTrue("Download 5 KB" !in it.texts() && "Not downloaded yet" !in it.texts(), "${it.texts()}")
            val progress = it.nodes().mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current
            }

            assertEquals(listOf(0.4f), progress)

            it.clickOwnText("Cancel")
        }

        assertEquals(listOf(lfsTheirs), cancelled)
    }

    @Test
    fun `a download that failed says why on the card, and Try again downloads again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val downloads = mutableListOf<LfsObject>()
            val refused = LfsObjectDownloadError(LfsError.ObjectRefused(404, "Object does not exist"))
            val failed = LfsDownload.Failed(refused)
            val state = lfsChoice().copy(lfsDownloads = mapOf(lfsTheirs.oid to failed))

            val texts = render(state, actions(onDownloadLfs = { downloads += it }), clickText = "Try again").texts

            assertTrue(
                "Couldn't download the LFS object: the LFS server answered 404 Object does not exist." in texts,
                "$texts",
            )
            assertEquals(listOf(lfsTheirs), downloads)
        }

    @Test
    fun `a downloaded LFS image shows on its card, with its size in pixels`(): Unit = runBlocking(Dispatchers.Swing) {
        val png = ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()
        val state = lfsChoice(theirs = VersionPreview.Lfs(lfsTheirs, downloaded = true, image = PreviewImage(png)))

        scene(ResolverLists()) { state }.closing { scene ->
            // Decoded off the UI thread
            repeat(100) {
                if (scene.texts().none { it == "PNG · 4 × 2 · 5 KB" }) {
                    scene.frames(1)
                    delay(10)
                }
            }

            assertTrue("PNG · 4 × 2 · 5 KB" in scene.texts(), "${scene.texts()}")
            assertEquals(2, scene.texts().count { it == "Open in the default app" }, "${scene.texts()}")
            assertTrue(scene.texts().none { it.startsWith("Download") }, "${scene.texts()}")
        }
    }

    @Test
    fun `a link's cards say where each side points`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = choice(
            ConflictContent.SymbolicLink("main-target", "feature-target"),
            ConflictPreview(
                base = null,
                ours = VersionPreview.Link("main-target", resolves = true),
                theirs = VersionPreview.Link("feature-target", resolves = false),
            ),
        )

        val texts = render(state).texts

        assertTrue("→ main-target" in texts && "Points to something that exists" in texts, "$texts")
        assertTrue("→ feature-target" in texts && "Points to nothing" in texts, "$texts")
    }

    @Test
    fun `an image shows, with its size in pixels, and opens in the default app`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", it) }
                .toByteArray()
            val opened = mutableListOf<ConflictStage>()
            val state = choice(
                ConflictContent.Binary,
                ConflictPreview(
                    base = null,
                    ours = VersionPreview.File(png.size.toLong(), image = PreviewImage(png)),
                    theirs = VersionPreview.File(5, image = PreviewImage("not a png".toByteArray())),
                ),
            ) { it.copy(path = "assets/logo.png") }

            scene(ResolverLists(), actions = actions(onOpenVersion = { opened += it })) { state }.closing { scene ->
                // Decoded off the UI thread, each image on its own
                repeat(100) {
                    val texts = scene.texts()

                    if (texts.none { it == "PNG · 2 × 3 · ${png.size} B" } || "Can't show this image" !in texts) {
                        scene.frames(1)
                        delay(10)
                    }
                }

                assertTrue("PNG · 2 × 3 · ${png.size} B" in scene.texts(), "${scene.texts()}")
                assertTrue("Can't show this image" in scene.texts(), "${scene.texts()}")

                scene.clickOwnText("Open in the default app")
            }

            assertEquals(listOf(state.ours()), opened)
        }

    @Test
    fun `a conflict at other paths keeps the file there, or leaves it out`(): Unit = runBlocking(Dispatchers.Swing) {
        val picked = mutableListOf<ConflictSide>()
        val state = choice(
            ConflictContent.OtherPaths,
            ConflictPreview(null, null, VersionPreview.File(5)),
        ) { it.copy(path = "d~feature", base = null, ours = null, conflict = FileConflict(null, ConflictVersion.FILE)) }

        val texts = render(state, actions(onPick = { picked += it }), clickText = "Theirs: feature/retry").texts

        assertEquals(listOf(ConflictSide.THEIRS), picked)
        assertTrue(texts.any { it.startsWith("git recorded this conflict at more than one path") }, "$texts")
        assertTrue("Only theirs (feature/retry) has a file at this path." in texts, "$texts")
        assertTrue("main has no file at this path." in texts && "File · 5 B" in texts, "$texts")

        fun label(side: ConflictSide) =
            render(state.copy(file = (state.file as ResolverFile.Loaded).copy(pick = ChoicePick.of(side)))).texts

        assertTrue("✓ Leave it out of this path, as ours (main) did" in label(ConflictSide.OURS))
        assertTrue("✓ Keep it here, as theirs (feature/retry) has it" in label(ConflictSide.THEIRS))
    }

    @Test
    fun `a file renamed on both sides has a card per new name, and one for both`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val renamed = RenamedFile("notes/a.txt", "notes/b.txt", "notes/c.txt")
            val both = mutableListOf<Unit>()
            val picked = mutableListOf<ConflictSide>()

            fun state(conflicts: Boolean = false, theirsCommit: String? = "3".repeat(40), pick: ChoicePick? = null) =
                choice(
                    ConflictContent.Renamed(renamed, conflicts, theirsCommit),
                    ConflictPreview(VersionPreview.File(6), VersionPreview.File(9), VersionPreview.File(9)),
                ) { it.copy(path = renamed.original) }.let { state ->
                    state.copy(file = (state.file as ResolverFile.Loaded).copy(pick = pick))
                }

            val texts = render(state(), actions(onPickBothNames = { both += Unit }), clickText = "Keep both names").texts
            render(state(), actions(onPick = { picked += it }), clickText = "Theirs: feature/retry")

            assertEquals(1, both.size)
            assertEquals(listOf(ConflictSide.THEIRS), picked)
            assertTrue(
                "ours (main) renamed notes/a.txt to notes/b.txt, and theirs (feature/retry) renamed it to notes/c.txt." in texts,
                "$texts",
            )
            assertTrue(texts.any { it.startsWith("git merged both sides' changes") }, "$texts")
            assertTrue("notes/b.txt" in texts && "notes/c.txt" in texts && texts.count { it == "Renamed" } == 2, "$texts")
            assertTrue("notes/b.txt and notes/c.txt, with the same content" in texts, "$texts")

            assertTrue("✓ Name it notes/c.txt" in render(state(pick = ChoicePick.THEIRS)).texts)
            assertTrue("✓ Keep both names" in render(state(pick = ChoicePick.BOTH)).texts)

            var marked = 0
            val markResolved = "Mark resolved (${acceptShortcut()})"
            val conflicts = render(state(conflicts = true, pick = ChoicePick.OURS), actions(onMarkResolved = { marked++ }), clickText = markResolved)

            assertTrue(conflicts.texts.any { it.startsWith("Both sides' changes to the content conflict too. After Mark resolved") })
            assertEquals(1, marked)

            // Without theirs' commit, the conflicts can't go to the three panes: Mark resolved waits, and a click on its
            // text is the card's
            val unknownState = state(conflicts = true, theirsCommit = null, pick = ChoicePick.OURS)
            val unknown = render(unknownState, actions(onMarkResolved = { marked++ }), clickText = markResolved)

            assertTrue(unknown.texts.any { it.startsWith("Both sides' changes to the content conflict too, and this operation") })
            assertEquals(1, marked)
            assertTrue("Mark resolved" !in unknown.clickable)
        }

    @Test
    fun `the list shows a renamed file once, under its old name with its new names`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val files = listOf(
                ResolverFileEntry(StatusEntry("notes/a.txt", StatusType.CONFLICTING), false, listOf("notes/b.txt", "notes/c.txt")),
                ResolverFileEntry(entry, resolvedHere = false),
            )

            val texts = render(state().copy(files = files)).texts

            assertTrue("a.txt → b.txt, c.txt" in texts, "$texts")
        }

    @Test
    fun `a change's undo is in the gutter next to the side that made it`(): Unit = runBlocking(Dispatchers.Swing) {
        val undo = "Undo this change: the result gets the base's lines"
        // Ours' import comes first, theirs' function last
        val (ours, theirs) = render(state()).bounds.getValue(undo).sortedBy { it.top }

        assertTrue(ours.left < 700f && theirs.left > 700f, "ours' at ${ours.left}, theirs' at ${theirs.left}")
    }

    @Test
    fun `leaving a file with unsaved picks asks to save, discard or cancel`(): Unit = runBlocking(Dispatchers.Swing) {
        val answers = mutableListOf<Boolean?>()
        val state = state().copy(prompt = ResolverPrompt.UnsavedChanges(ResolverLeave.Close))

        for (button in listOf("Save", "Discard", "Cancel")) {
            render(state, actions(onAnswerUnsaved = { answers += it }), clickText = button, inPrompt = true)
        }

        assertEquals(listOf(true, false, null), answers)
    }

    @Test
    fun `the headers' tooltip says what ours and theirs are in the operation`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var text = ""
            val scene = ImageComposeScene(width = 600, height = 100, density = Density(1f)) {
                AppTheme {
                    text = sides.copy(operation = ConflictOperation.REBASE).explanation()
                    Text(text)
                }
            }

            try {
                scene.render()
            } finally {
                scene.close()
            }

            assertEquals(
                "In a rebase, ours is what the rebase builds on, main (onto), with the commits replayed so far, and " +
                    "theirs is the commit being replayed, 2222222 \"Retry\" from feature/retry.",
                text,
            )
        }

    @Test
    fun `the stacked layout has the sides on top, named, and the result below`(): Unit = runBlocking(Dispatchers.Swing) {
        val rendered = render(state().copy(layout = ConflictResolverLayout.STACKED))
        val texts = rendered.texts

        assertTrue("Ours: main" in texts, "$texts")
        assertTrue("Theirs: feature/retry" in texts, "$texts")
        assertTrue("Conflict 1 · open: take ours or theirs above, or right-click for more" in texts, "$texts")
        assertEquals(2, texts.count { it == "Take ↓" }, "$texts")
        // Ours' import and theirs' function
        assertEquals(2, texts.count { it == "Applied" }, "$texts")
        assertTrue("Conflict 1 of 1" in texts, "$texts")
        assertTrue(texts.any { it.startsWith("Take ↓ · Add ↓ after the other side") }, "$texts")

        val ours = rendered.textBounds.getValue("Ours: main").single()
        val theirs = rendered.textBounds.getValue("Theirs: feature/retry").single()
        val result = rendered.textBounds.getValue("Result").single()

        assertTrue(ours.top == theirs.top && ours.left < theirs.left, "ours at $ours, theirs at $theirs")
        // The result below the top pair, across the full width
        assertTrue(result.top > ours.bottom + 100 && result.left <= ours.left + 1, "result at $result, ours at $ours")
    }

    @Test
    fun `the stacked chips take a side, add the other after it, and take it out again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val calls = mutableListOf<String>()
            val picks = PickActions(
                onTake = { chunk, side -> calls += "take $chunk $side" },
                onResolve = { _, _ -> },
                onSetApplied = { chunk, applied -> calls += "applied $chunk $applied" },
                onDrop = { chunk, side -> calls += "drop $chunk $side" },
            )
            val open = state().copy(layout = ConflictResolverLayout.STACKED)
            val oursTaken = state(merge.copy(chunks = merge.chunks.mapIndexed { index, chunk ->
                if (index == 3) (chunk as Chunk.Conflict).copy(resolution = ConflictResolution.Ours) else chunk
            })).copy(layout = ConflictResolverLayout.STACKED)

            // Ours' chips come first
            render(open, actions(picks), clickText = "Take ↓")
            render(oursTaken, actions(picks), clickText = "Taken")
            render(oursTaken, actions(picks), clickText = "Add ↓")
            render(open, actions(picks), clickText = "Applied")

            assertEquals(listOf("take 3 OURS", "drop 3 OURS", "take 3 THEIRS", "applied 1 false"), calls)
            assertTrue("Take ↓" !in render(oursTaken).texts)
        }

    @Test
    fun `the toolbar switches the layout`(): Unit = runBlocking(Dispatchers.Swing) {
        val layouts = mutableListOf<ConflictResolverLayout>()
        val actions = actions(onSetLayout = { layouts += it })

        render(state(), actions, clickDescription = "Stacked: ours and theirs on top, the result below")
        render(state().copy(layout = ConflictResolverLayout.STACKED), actions, clickDescription = "Columns: ours, the result and theirs side by side")
        // The layout shown isn't picked again
        render(state(), actions, clickDescription = "Columns: ours, the result and theirs side by side")

        assertEquals(listOf(ConflictResolverLayout.STACKED, ConflictResolverLayout.COLUMNS), layouts)
    }

    @Test
    fun `switching the layout keeps the place in the file, and the top pair follows the result`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // Ours replaced line 60 with five lines, theirs with one
            val base = (0 until 100).map { "line $it" }
            val ours = base.take(60) + (1..5).map { "ours $it" } + base.drop(61)
            val theirs = base.take(60) + "theirs" + base.drop(61)
            val long = TextMerge(
                text(*base.toTypedArray()),
                text(*ours.toTypedArray()),
                text(*theirs.toTypedArray()),
                listOf(
                    Chunk.Unchanged(lines(0, 60), lines(0, 60), lines(0, 60)),
                    Chunk.Conflict(lines(60, 61), lines(60, 65), lines(60, 61)),
                    Chunk.Unchanged(lines(61, 100), lines(65, 104), lines(61, 100)),
                ),
            )
            val lists = ResolverLists()
            var layout by mutableStateOf(ConflictResolverLayout.COLUMNS)

            scene(lists) { state(long).copy(layout = layout) }.closing { scene ->
                // Opens at the conflict, with three rows above it
                scene.frames()
                assertEquals(57, lists.columns.firstVisibleItemIndex)

                layout = ConflictResolverLayout.STACKED
                scene.frames()
                assertEquals(57, lists.result.firstVisibleItemIndex)
                assertEquals(57, lists.sides.firstVisibleItemIndex)

                // The result's row 70 is the ninth line after the conflict, which is five rows tall on top. The offset
                // within the line goes with it
                lists.result.requestScrollToItem(70, 5)
                scene.frames()
                assertEquals(74 to 5, lists.sides.firstVisibleItemIndex to lists.sides.firstVisibleItemScrollOffset)

                layout = ConflictResolverLayout.COLUMNS
                scene.frames()
                assertEquals(74 to 5, lists.columns.firstVisibleItemIndex to lists.columns.firstVisibleItemScrollOffset)
            }
        }

    @Test
    fun `moving to a conflict shows it on top too, past lines that only the top pair has`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // Ours deleted lines 40 to 59; then both changed line 61
            val base = (0 until 100).map { "line $it" }
            val ours = base.take(40) + base[60] + "ours" + base.drop(62)
            val theirs = base.take(61) + "theirs" + base.drop(62)
            val long = TextMerge(
                text(*base.toTypedArray()),
                text(*ours.toTypedArray()),
                text(*theirs.toTypedArray()),
                listOf(
                    Chunk.Unchanged(lines(0, 40), lines(0, 40), lines(0, 40)),
                    Chunk.Change(lines(40, 60), lines(40, 40), lines(40, 60), ChangedBy.OURS),
                    Chunk.Unchanged(lines(60, 61), lines(40, 41), lines(60, 61)),
                    Chunk.Conflict(lines(61, 62), lines(41, 42), lines(61, 62)),
                    Chunk.Unchanged(lines(62, 100), lines(42, 80), lines(62, 100)),
                ),
            )
            val lists = ResolverLists()
            val scrollToChunk = MutableSharedFlow<Int>(extraBufferCapacity = 1)

            scene(lists, scrollToChunk) { state(long).copy(layout = ConflictResolverLayout.STACKED) }.closing { scene ->
                scene.frames()
                lists.result.requestScrollToItem(0)
                scene.frames()
                assertEquals(0, lists.sides.firstVisibleItemIndex)

                scrollToChunk.tryEmit(3)
                scene.frames(count = 60)

                // The result's conflict is its row 42, and the top pair's 61: both with three rows above
                assertEquals(39, lists.result.firstVisibleItemIndex)
                assertEquals(58, lists.sides.firstVisibleItemIndex)
            }
        }

    @Test
    fun `with Wrap lines off, a long line stays on one row and every column scrolls sideways together`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val long = "    val message = " + "\"word\" + ".repeat(60) + "\"end\""
            // Enough lines after the conflict for the list to scroll up and down
            val after = (1..80).map { "line $it" }.toTypedArray()
            val merge = TextMerge(
                text("short", long, "base", *after),
                text("short", long, "ours", *after),
                text("short", long, "theirs", *after),
                listOf(
                    Chunk.Unchanged(lines(0, 2), lines(0, 2), lines(0, 2)),
                    Chunk.Conflict(lines(2, 3), lines(2, 3), lines(2, 3)),
                    Chunk.Unchanged(lines(3, 83), lines(3, 83), lines(3, 83)),
                ),
            )
            val lists = ResolverLists()
            var wrap by mutableStateOf(false)

            scene(lists) { state(merge).copy(wrapLines = wrap) }.closing { scene ->
                scene.frames()

                val short = scene.placesOf("short")
                val unwrapped = scene.placesOf(long)

                // In ours, the result and theirs, one line high, and wider than a column
                assertEquals(3, unwrapped.size, "$unwrapped")
                assertTrue(unwrapped.all { it.height == short.first().height && it.width > 600f }, "$unwrapped, $short")
                assertTrue(lists.sideways.max > 0f)
                // Its row is one line high too: the next row starts a line further down
                val lineHeight = unwrapped.first().top - short.first().top
                assertEquals(unwrapped.first().top + lineHeight, scene.placesOf("ours").first().top)

                // A sideways swipe of two steps over the result moves every column's lines by 2 × 48 dp
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(2f, 0f))
                scene.frames()

                assertEquals(96f, lists.sideways.offset)
                assertEquals(unwrapped.map { it.left - 96f }, scene.placesOf(long).map { it.left })
                assertEquals(short.map { it.left - 96f }, scene.placesOf("short").map { it.left })

                // Up and down stays the list's
                fun listPlace() = lists.columns.firstVisibleItemIndex to lists.columns.firstVisibleItemScrollOffset
                val listTop = listPlace()
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(0f, 1f))
                scene.frames()

                assertEquals(96f, lists.sideways.offset)
                assertTrue(listPlace() != listTop, "${listPlace()}")

                lists.columns.requestScrollToItem(0)
                scene.frames()

                // A swipe that is mostly sideways goes sideways only: the list doesn't drift up or down
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(-1f, 0.5f))
                scene.frames()

                assertEquals(48f, lists.sideways.offset)
                assertEquals(listTop, listPlace())

                // Shift and the wheel go sideways, and the list stays where it is
                val shift = PointerKeyboardModifiers(isShiftPressed = true)
                scene.sendPointerEvent(
                    PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(0f, 1f), keyboardModifiers = shift,
                )
                scene.frames()

                assertEquals(96f, lists.sideways.offset)
                assertEquals(listTop, listPlace())

                // Back past the start stops at it
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 150f), scrollDelta = Offset(-10f, 0f))
                scene.frames()

                assertEquals(0f, lists.sideways.offset)

                wrap = true
                scene.frames()

                val wrapped = scene.placesOf(long)
                assertTrue(wrapped.size == 3 && wrapped.all { it.height > 2 * short.first().height }, "$wrapped")
            }
        }

    @Test
    fun `the toolbar's Wrap lines calls its action`(): Unit = runBlocking(Dispatchers.Swing) {
        var toggled = 0

        render(state(), actions(onToggleWrapLines = { toggled++ }), clickText = "Wrap lines")

        assertEquals(1, toggled)
    }

    @Test
    fun `dragging a gutter moves the edge between its columns, and a double-click shares the width out again`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val changed = mutableListOf<ColumnShares>()
            val lists = ResolverLists()

            scene(lists, actions = actions(onColumnSharesChanged = { changed += it })) { state() }.closing { scene ->
                scene.frames()

                // The undo button of ours' import is in the left gutter; the headers are 30 dp under the toolbar's 40
                val undo = "Undo this change: the result gets the base's lines"
                val gutter = scene.nodes().first { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(undo) == true
                }
                val header = Offset(gutter.boundsInRoot.center.x, 55f)

                scene.drag(from = header, to = header + Offset(100f, 0f))

                val dragged = changed.single()

                // A third, and 100 of the 1,095 dp that the gutters and the file list leave
                assertEquals(1f / 3 + 100f / 1095, dragged.ours, 0.002f)
                assertEquals(1f / 3, dragged.theirs, 0.001f)

                // A double-click where the drag left the gutter shares the width out evenly again
                scene.doubleClick(header + Offset(100f, 0f))
                assertEquals(ColumnShares(), changed.last())

                // From a row's gutter too, the other way, beside the 26 dp buttons in the 40 dp gutter, which keep
                // their clicks
                val beside = Offset(header.x - 17f, 80f)

                scene.drag(from = beside, to = beside - Offset(100f, 0f))
                assertEquals(1f / 3 - 100f / 1095, changed.last().ours, 0.002f)

                // Theirs' gutter, with theirs' undo button, moves the edge between the result and theirs
                val theirsGutter = scene.nodes()
                    .filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(undo) == true }
                    .maxBy { it.boundsInRoot.left }
                val theirsHeader = Offset(theirsGutter.boundsInRoot.center.x, 55f)

                scene.drag(from = theirsHeader, to = theirsHeader - Offset(100f, 0f))

                assertEquals(1f / 3 - 100f / 1095, changed.last().ours, 0.002f)
                assertEquals(1f / 3 + 100f / 1095, changed.last().theirs, 0.002f)
            }
        }

    @Test
    fun `dragging the file list's edge resizes it`(): Unit = runBlocking(Dispatchers.Swing) {
        val widths = mutableListOf<Float>()

        scene(ResolverLists(), actions = actions(onFileListWidthChanged = { widths += it })) { state() }.closing { scene ->
            scene.frames()
            // The list is 220 dp wide, and its edge 5
            scene.drag(from = Offset(222f, 300f), to = Offset(282f, 300f))

            assertTrue(widths.single() in 255f..281f, "$widths")
        }
    }

    @Test
    fun `dragging the stacked layout's handle gives the top pair more of the height`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val splits = mutableListOf<Float>()

            scene(ResolverLists(), actions = actions(onStackedSplitChanged = { splits += it })) {
                state().copy(layout = ConflictResolverLayout.STACKED)
            }.closing { scene ->
                scene.frames()
                // Under the 40 dp toolbar, 45% of the 526 dp that the toolbar, the legend and the handle leave, then
                // the middle of the 8 dp handle
                val handle = Offset(800f, 40f + 0.45f * 526f + 4f)

                scene.drag(from = handle, to = handle + Offset(0f, 60f))

                assertTrue(splits.single() in 0.5f..0.57f, "$splits")
            }
        }

    @Test
    fun `in Focus, the file list hides, and the file name in the toolbar switches files`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val selected = mutableListOf<String>()
            val other = StatusEntry("docs/guide.md", StatusType.CONFLICTING)
            val done = StatusEntry("README.md", StatusType.CONFLICTING)
            val focused = state().let { state ->
                state.copy(
                    focused = true,
                    files = state.files + ResolverFileEntry(other, resolvedHere = false) +
                        ResolverFileEntry(done, resolvedHere = true),
                )
            }

            assertTrue("CONFLICTED FILES" in render(state()).texts)

            scene(ResolverLists(), actions = actions(onSelectFile = { selected += it })) { focused }.closing { scene ->
                scene.frames()

                assertTrue(scene.placesOf("CONFLICTED FILES").isEmpty())

                scene.clickText(entry.filePath)
                scene.frames()
                // The menu lists every file; one resolved here can't be opened again
                assertTrue(scene.placesOf("docs/guide.md").isNotEmpty())
                val resolved = scene.nodes().first { node ->
                    node.config.getOrNull(SemanticsActions.OnClick) != null &&
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "README.md" }
                }
                assertTrue(resolved.config.contains(SemanticsProperties.Disabled))
                scene.clickText("docs/guide.md")
                scene.frames()

                assertEquals(listOf("docs/guide.md"), selected)
            }
        }

    @Test
    fun `the toolbar's Focus button turns Focus on and off`(): Unit = runBlocking(Dispatchers.Swing) {
        var toggled = 0
        val actions = actions(onToggleFocus = { toggled++ })

        scene(ResolverLists(), actions = actions) { state() }.closing { scene ->
            scene.frames()
            scene.clickDescription { it.startsWith("Focus: hide the tabs") }
        }
        scene(ResolverLists(), actions = actions) { state().copy(focused = true) }.closing { scene ->
            scene.frames()
            scene.clickDescription { it.startsWith("Leave Focus") && it.endsWith("or Esc)") }
        }

        assertEquals(2, toggled)
    }

    @Test
    fun `a resolved file stays, read-only but scrolling, with a strip that opens the next file`(): Unit =
        runBlocking(Dispatchers.Swing) {
            // Enough lines after the conflict for the list to scroll
            val after = (1..80).map { "line $it" }.toTypedArray()
            val long = TextMerge(
                text("a", "base", *after),
                text("a", "ours", *after),
                text("a", "theirs", *after),
                listOf(
                    Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
                    Chunk.Conflict(lines(1, 2), lines(1, 2), lines(1, 2)),
                    Chunk.Unchanged(lines(2, 82), lines(2, 82), lines(2, 82)),
                ),
            )
            val guide = StatusEntry("docs/guide.md", StatusType.CONFLICTING)
            val handOff = ResolverHandOff(from = entry.filePath, resolvedHere = true, next = "docs/guide.md")
            val resolved = state(long).copy(
                files = listOf(ResolverFileEntry(guide, resolvedHere = false), ResolverFileEntry(entry, resolvedHere = true)),
                handOff = handOff,
            )
            var next = 0
            val taken = mutableListOf<Int>()
            val picks = PickActions(onTake = { chunk, _ -> taken += chunk }, { _, _ -> }, { _, _ -> })
            val lists = ResolverLists()
            val shortcut = if (currentOs == OS.MAC) "⌘⏎" else "Ctrl+Enter"

            scene(lists, actions = actions(picks = picks, onNextFile = { next++ })) { resolved }.closing { scene ->
                // Dimmed: the first frame comes before the animation, and the file then stands out much less from the
                // background
                scene.frames(count = 1)
                val full = scene.contrastInFile()
                scene.frames(count = 30)
                val dimmed = scene.contrastInFile()
                assertTrue(dimmed < 0.6 * full, "$dimmed of $full")

                assertTrue(scene.placesOf("src/ConfigLoader.kt is resolved and staged. $shortcut opens docs/guide.md.").isNotEmpty())
                assertTrue(scene.placesOf("Resolved").isNotEmpty())
                // Nothing left to save or resolve
                assertTrue(scene.placesOf("Save").isEmpty())
                assertTrue(scene.placesOf("Mark resolved").isEmpty())
                assertTrue(scene.placesOf("External merge tool…").isEmpty())

                // The arrows take no clicks, and the list still scrolls
                val arrow = scene.nodes().first { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("Take ours") }
                }
                scene.press(arrow.boundsInRoot.center)
                scene.release(arrow.boundsInRoot.center)
                scene.frames()
                assertEquals(emptyList<Int>(), taken)

                val top = lists.columns.firstVisibleItemIndex
                scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 300f), scrollDelta = Offset(0f, 3f))
                scene.frames()
                assertTrue(lists.columns.firstVisibleItemIndex > top, "${lists.columns.firstVisibleItemIndex}")

                scene.clickText("Next file")
                assertEquals(1, next)

            }

            // Resolved outside the resolver
            assertTrue(
                render(resolved.copy(handOff = handOff.copy(resolvedHere = false))).texts
                    .any { it.startsWith("src/ConfigLoader.kt has no conflicts any more.") }
            )

            // Once another file is shown, it's about nothing on screen
            val other = render(resolved.copy(handOff = handOff.copy(from = "README.md"))).texts
            assertTrue(other.none { "is resolved and staged." in it }, "$other")
            assertTrue("Mark resolved" in other, "$other")
        }

    @Test
    fun `in Focus, the toolbar counts the files, those resolved here first`(): Unit = runBlocking(Dispatchers.Swing) {
        val files = listOf(
            ResolverFileEntry(entry, resolvedHere = false),
            ResolverFileEntry(StatusEntry("docs/guide.md", StatusType.CONFLICTING), resolvedHere = false),
            ResolverFileEntry(StatusEntry("README.md", StatusType.CONFLICTING), resolvedHere = true),
        )

        assertTrue("2 of 3 files" in render(state().copy(files = files, focused = true)).texts)
        // Once the file shown is resolved, it's the last of those resolved here
        val resolved = state().copy(
            files = listOf(files[1], files[2], ResolverFileEntry(entry, resolvedHere = true)),
            focused = true,
            handOff = ResolverHandOff(from = entry.filePath, resolvedHere = true, next = "docs/guide.md"),
        )
        assertTrue("2 of 3 files" in render(resolved).texts)
        assertTrue("1 of 2 files" in render(resolved.copy(files = listOf(files[1], ResolverFileEntry(entry, true)))).texts)
        // The file list shows them outside Focus, and one file needs no count
        assertTrue(render(state().copy(files = files)).texts.none { it.endsWith(" files") })
        assertTrue(render(state().copy(focused = true)).texts.none { it.endsWith(" files") })
    }

    @Test
    fun `while the next file loads, the last one stays, takes no clicks, and fades into the next`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val taken = mutableListOf<Pair<Int, ConflictSide>>()
            val picks = PickActions(onTake = { chunk, side -> taken += chunk to side }, { _, _ -> }, { _, _ -> })
            var current by mutableStateOf(state())

            scene(ResolverLists(), actions = actions(picks = picks)) { current }.closing { scene ->
                scene.frames()

                fun clickTakeOurs() {
                    val arrow = scene.nodes().first { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                            .any { it.startsWith("Take ours") }
                    }
                    val at = arrow.boundsInRoot.center
                    scene.press(at)
                    scene.release(at)
                    scene.frames(count = 2)
                }

                clickTakeOurs()
                assertEquals(1, taken.size)

                assertTrue(scene.coloursInFile() > 1)

                current = current.copy(file = ResolverFile.Loading("docs/guide.md"))
                scene.frames(count = 2)

                // No "Loading…" flash: the last file stays, under a cover that takes the pointer
                assertTrue(scene.placesOf("Loading…").isEmpty())
                assertTrue(scene.placesOf("Ours: main").isNotEmpty())
                clickTakeOurs()
                assertEquals(1, taken.size)

                // It fades out
                scene.frames()
                assertEquals(1, scene.coloursInFile())

                // A load that takes long enough to notice says so
                delay(600)
                scene.frames()
                assertTrue(scene.placesOf("Loading…").isNotEmpty())

                val guide = (state().file as ResolverFile.Loaded).let { loaded ->
                    loaded.copy(file = loaded.file.copy(path = "docs/guide.md"))
                }
                current = current.copy(file = guide)
                scene.frames(count = 30)

                assertTrue(scene.placesOf("Loading…").isEmpty())
                assertTrue(scene.coloursInFile() > 1)
                clickTakeOurs()
                assertEquals(2, taken.size)
            }
        }

    @Test
    fun `Focus's shortcut is Shift and F with Cmd on macOS, and with Ctrl elsewhere`() {
        val mac = currentOs == OS.MAC

        fun press(cmd: Boolean = false, ctrl: Boolean = false, shift: Boolean = true, alt: Boolean = false, key: Key = Key.F) =
            isFocusShortcut(key, cmd = cmd, ctrl = ctrl, shift = shift, alt = alt)

        assertEquals(mac, press(cmd = true))
        assertEquals(!mac, press(ctrl = true))
        assertEquals(false, press(cmd = mac, ctrl = !mac, shift = false))
        assertEquals(false, press(cmd = mac, ctrl = !mac, alt = true))
        assertEquals(false, press(cmd = mac, ctrl = !mac, key = Key.G))
        assertEquals(false, press(cmd = true, ctrl = true))
    }

    @Test
    fun `a double-click on the result edits its part, at the line clicked`(): Unit = runBlocking(Dispatchers.Swing) {
        val edits = mutableListOf<Pair<Int, Int?>>()
        val picks = PickActions({ _, _ -> }, { _, _ -> }, { _, _ -> }, onEdit = { chunk, line -> edits += chunk to line })

        scene(ResolverLists(), actions = actions(picks)) { state() }.closing { scene ->
            scene.frames()
            // Ours, the result and theirs have the line: the result's is in the middle
            val closing = scene.placesOf("}").sortedBy { it.left }[1]

            scene.doubleClick(closing.center)

            // The second line of the part after the conflict
            assertEquals(listOf(4 to 1), edits)
        }
    }

    @Test
    fun `the pointer over the result shows Edit, which edits its part`(): Unit = runBlocking(Dispatchers.Swing) {
        val edits = mutableListOf<Pair<Int, Int?>>()
        val picks = PickActions({ _, _ -> }, { _, _ -> }, { _, _ -> }, onEdit = { chunk, line -> edits += chunk to line })

        scene(ResolverLists(), actions = actions(picks)) { state() }.closing { scene ->
            scene.frames()
            assertTrue(scene.placesOf("Edit").isEmpty())

            val line = scene.placesOf("    return parse(text)").sortedBy { it.left }[1]

            hover(scene, line.center)
            assertEquals(1, scene.placesOf("Edit").size)

            scene.clickText("Edit")
            assertEquals(listOf(4 to 0), edits)
        }
    }

    @Test
    fun `while a part is edited, a text field takes its rows, from the base's line for an open conflict`(): Unit =
        runBlocking(Dispatchers.Swing) {
            scene(ResolverLists()) { state().copy(edit = editing(chunk = 3)) }.closing { scene ->
                scene.frames()

                val field = scene.field()

                assertEquals("    val text = read(path)", field.config[SemanticsProperties.EditableText].text)
                assertEquals(true, field.config.getOrNull(SemanticsProperties.Focused))
                // The placeholder gives way to the field; the sides' lines stay next to it
                assertTrue(scene.placesOf("Conflict 1 · open: ▶ takes ours, ◀ takes theirs").isEmpty())
                assertEquals(1, scene.placesOf("    val text = Path.of(path).readText()").size)
                assertEquals(1, scene.placesOf("    val text = retry(3) { read(path) }").size)
                // Numbered as the result: an open conflict's placeholder has no number, and the field's line is 6
                assertEquals(render(state()).texts.count { it == " 6" } + 1, scene.placesOf(" 6").size)

                // Keywords keep the rows' colour while editing
                val layouts = mutableListOf<TextLayoutResult>()
                checkNotNull(field.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
                val shown = layouts.single().layoutInput.text

                assertTrue(
                    shown.spanStyles.any { shown.text.substring(it.start, it.end) == "val" && it.item.color.isSpecified },
                    "${shown.spanStyles}",
                )
            }
        }

    @Test
    fun `the field grows with its lines, and the sides' fillers keep the rows after it lined up`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var edit by mutableStateOf(editing(chunk = 3))

            scene(ResolverLists()) { state().copy(edit = edit) }.closing { scene ->
                scene.frames()
                val oneLine = scene.placesOf("    return parse(text)").map { it.top }

                edit = editing(chunk = 3, text = "a\nb\nc\nd")
                scene.frames()
                val fourLines = scene.placesOf("    return parse(text)").map { it.top }
                val lineHeight = scene.placesOf("}").first().height

                // Ours, the result and theirs, still on one row, three lines lower
                assertEquals(3, fourLines.size)
                assertEquals(1, fourLines.distinct().size, "$fourLines")
                assertEquals(oneLine.first() + 3 * lineHeight, fourLines.first(), 2f)

                // The field's line numbers, in the result's column, are next to its lines
                val resultColumn = scene.placesOf("    return parse(text)").sortedBy { it.left }[1].left
                val numbers = (6..9).map { number ->
                    scene.placesOf(" $number").first { abs(it.right - resultColumn) < 60f }.top
                }

                assertEquals((0..3).map { numbers.first() + it * lineHeight }, numbers)

                // A side taller than the field: the field's column gets the filler
                edit = editing(chunk = 1, text = "import java.nio.file.Paths")
                scene.frames()
                val after = scene.placesOf("fun load(): Config {").map { it.top }

                assertEquals(3, after.size)
                assertEquals(1, after.distinct().size, "$after")
            }
        }

    @Test
    fun `a side with more lines than the field makes the part as tall as its lines`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val tall = TextMerge(
                text("a", "x", "z"),
                text("a", "o1", "o2", "o3", "z"),
                text("a", "t", "z"),
                listOf(
                    Chunk.Unchanged(lines(0, 1), lines(0, 1), lines(0, 1)),
                    Chunk.Conflict(lines(1, 2), lines(1, 4), lines(1, 2)),
                    Chunk.Unchanged(lines(2, 3), lines(4, 5), lines(2, 3)),
                ),
            )

            scene(ResolverLists()) { state(tall).copy(edit = editing(chunk = 1, merge = tall, text = "e")) }.closing { scene ->
                scene.frames()
                val ours = listOf("o1", "o2", "o3").map { scene.placesOf(it).single() }
                val lineHeight = ours.first().height
                val after = scene.placesOf("z")

                // Ours' lines, one under the other, then the rows after the part
                assertTrue(lineHeight > 0f)
                assertEquals(ours.map { it.top }, (0..2).map { ours.first().top + it * lineHeight })
                assertEquals(ours.map { lineHeight }, ours.map { it.height })
                assertEquals(3, after.size)
                assertTrue(after.all { it.top >= ours.last().bottom - 1f }, "$ours, $after")
            }
        }

    @Test
    fun `Mark resolved can be clicked while the lines typed resolve the last open conflict`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var resolved = 0
            val marking = actions(onMarkResolved = { resolved++ })

            // The text as editing started: the conflict stays open
            assertTrue("Mark resolved" !in render(state().copy(edit = editing(chunk = 3)), marking).clickable)

            render(state().copy(edit = editing(chunk = 3, text = "    val text = both")), marking, clickText = "Mark resolved")
            assertEquals(1, resolved)
        }

    @Test
    fun `typing goes to the view model, and a press outside the field finishes editing`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val typed = mutableListOf<String>()
            var finished = 0
            val edits = EditActions(onType = { typed += it.text }, onFinish = { finished++ })

            scene(ResolverLists(), actions = actions(edits = edits)) { state().copy(edit = editing(chunk = 3)) }.closing { scene ->
                scene.frames()
                val field = scene.field()

                checkNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString("    val text = both"))
                assertEquals(listOf("    val text = both"), typed)

                // In the field: still editing
                scene.press(field.boundsInRoot.center)
                scene.release(field.boundsInRoot.center)
                scene.frames()
                assertEquals(0, finished)

                // Anywhere else, the toolbar included
                scene.press(Offset(700f, 20f))
                scene.release(Offset(700f, 20f))
                scene.frames()
                assertEquals(1, finished)
            }
        }

    @Test
    fun `a field that the list scrolls out of sight stays, still editing, as LazyColumn keeps a focused item`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val after = (1..120).map { "line $it" }.toTypedArray()
            val long = TextMerge(
                text("base", *after),
                text("ours", *after),
                text("theirs", *after),
                listOf(
                    Chunk.Conflict(lines(0, 1), lines(0, 1), lines(0, 1)),
                    Chunk.Unchanged(lines(1, 121), lines(1, 121), lines(1, 121)),
                ),
            )
            var finished = 0
            val lists = ResolverLists()
            val edits = EditActions(onType = {}, onFinish = { finished++ })

            scene(lists, actions = actions(edits = edits)) {
                state(long).copy(edit = editing(chunk = 0, merge = long, text = "typed"))
            }.closing { scene ->
                scene.frames()
                assertEquals(0, finished)

                lists.columns.requestScrollToItem(100)
                scene.frames()

                // Typing brings it back into view
                assertEquals(0, finished)
                assertEquals(true, scene.field().config.getOrNull(SemanticsProperties.Focused))
            }
        }

    @Test
    fun `while editing, the legend says how editing ends`(): Unit = runBlocking(Dispatchers.Swing) {
        val undo = if (currentOs == OS.MAC) "⌘Z" else "Ctrl+Z"

        assertTrue(
            "Editing · Esc or a click outside finishes · $undo undoes typing · ▶ ◀ put a side in place of the text" in
                render(state().copy(edit = editing(chunk = 3))).texts,
        )
        assertTrue(render(state()).texts.any { it.startsWith("▶ ◀ take · + add after") })
    }

    @Test
    fun `an edited part is marked, and the gutter puts the original lines, the change or the base back`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val putBack = mutableListOf<Int>()
            val applied = mutableListOf<Pair<Int, Boolean>>()
            val picks = PickActions(
                { _, _ -> },
                { _, _ -> },
                onSetApplied = { chunk, value -> applied += chunk to value },
                onPutBack = { putBack += it },
            )
            val typed = merge
                .typing(1, TypedLines(text("import java.nio.file.Paths", "")))
                .typing(3, TypedLines(text("    val text = typed")))
                .typing(4, TypedLines(text("    return parse(text)!!", "}")))

            val rendered = render(state(typed), actions(picks), clickDescription = "Put the original lines back in place of yours")
            render(state(typed), actions(picks), clickDescription = "Put this change's lines in place of yours")
            render(state(typed), actions(picks), clickDescription = "Put the base's lines in place of yours")

            val mark = "You edited these lines. Undo, or the buttons next to them, put other lines in their place."

            assertEquals(3, rendered.descriptions.count { it == mark }, "${rendered.descriptions}")
            assertTrue("1 resolved · 2 merged cleanly" in rendered.texts, "${rendered.texts}")
            assertEquals(listOf(4), putBack)
            assertEquals(listOf(1 to true, 1 to false), applied)
        }

    @Test
    fun `the toolbar's Undo and Redo work while there is something to undo or redo`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var undone = 0
            val undo = "Undo the last pick or edit (${if (currentOs == OS.MAC) "⌘Z" else "Ctrl+Z"})"
            val redo = "Redo (${if (currentOs == OS.MAC) "⇧⌘Z" else "Ctrl+Shift+Z"})"

            render(state().copy(canUndo = true), actions(onUndo = { undone++ }), clickDescription = undo)
            assertEquals(1, undone)

            scene(ResolverLists()) { state().copy(canUndo = true) }.closing { scene ->
                scene.frames()
                val described = scene.nodes().filter { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == undo || it == redo }
                }

                assertEquals(
                    listOf(false, true),
                    described.map { SemanticsProperties.Disabled in it.config },
                )
            }
        }

    @Test
    fun `in the stacked layout, the field takes the result's full width`(): Unit = runBlocking(Dispatchers.Swing) {
        scene(ResolverLists()) {
            state().copy(layout = ConflictResolverLayout.STACKED, edit = editing(chunk = 3))
        }.closing { scene ->
            scene.frames()
            val field = scene.field()

            assertEquals("    val text = read(path)", field.config[SemanticsProperties.EditableText].text)
            assertTrue(field.boundsInRoot.width > 1000f, "${field.boundsInRoot}")
            // The chips on top still take a side, in place of the text
            assertTrue(scene.placesOf("Take ↓").size == 2)
        }
    }

    @Test
    fun `editing a part that starts above the list's top keeps the line at the top, and so does finishing`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val numbered = (0 until 150).map { "line $it" }.toTypedArray()
            val long = TextMerge(
                text(*numbered, "base"),
                text(*numbered, "ours"),
                text(*numbered, "theirs"),
                listOf(
                    Chunk.Unchanged(lines(0, 150), lines(0, 150), lines(0, 150)),
                    Chunk.Conflict(lines(150, 151), lines(150, 151), lines(150, 151)),
                ),
            )
            val lists = ResolverLists()
            var edit by mutableStateOf<ResolverEdit?>(null)

            scene(lists) { state(long).copy(edit = edit) }.closing { scene ->
                scene.frames()
                lists.columns.requestScrollToItem(80, 5)
                scene.frames()
                val top = scene.placesOf("line 80").first().top

                edit = editing(chunk = 0, merge = long, line = 85)
                scene.frames(count = 30)

                // The field is one item now, scrolled to where line 80 was
                assertEquals(1, lists.columns.layoutInfo.visibleItemsInfo.count { (it.key as Long) shr 32 == 0L })
                val lineHeight = scene.placesOf("line 80").first().height
                assertEquals(80 * lineHeight + 5, lists.columns.firstVisibleItemScrollOffset.toFloat(), lineHeight / 2)

                edit = null
                scene.frames(count = 30)

                assertEquals(80, lists.columns.firstVisibleItemIndex)
                assertEquals(top, scene.placesOf("line 80").first().top, 2f)
            }
        }

    @Test
    fun `a resolved file shows no Edit, and a double-click edits nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        val edits = mutableListOf<Pair<Int, Int?>>()
        val picks = PickActions({ _, _ -> }, { _, _ -> }, { _, _ -> }, onEdit = { chunk, line -> edits += chunk to line })
        val resolved = state().copy(handOff = ResolverHandOff(from = entry.filePath, resolvedHere = true, next = "x"))

        scene(ResolverLists(), actions = actions(picks)) { resolved }.closing { scene ->
            scene.frames(count = 30)
            val line = scene.placesOf("    return parse(text)").sortedBy { it.left }[1]

            hover(scene, line.center)
            assertTrue(scene.placesOf("Edit").isEmpty())

            scene.doubleClick(line.center)
            assertEquals(emptyList<Pair<Int, Int?>>(), edits)
        }
    }

    @Test
    fun `tabs show as four spaces in the field, and the caret maps back to the text`() {
        val transformed = TabsAsSpaces.filter(AnnotatedString("\tx\t"))

        assertEquals("    x    ", transformed.text.text)
        assertEquals(4, transformed.offsetMapping.originalToTransformed(1))
        assertEquals(9, transformed.offsetMapping.originalToTransformed(3))
        assertEquals(0, transformed.offsetMapping.transformedToOriginal(2))
        assertEquals(1, transformed.offsetMapping.transformedToOriginal(4))
        assertEquals(3, transformed.offsetMapping.transformedToOriginal(9))
        assertEquals(5, TabsAsSpaces.offset("\tx\t", 2))
    }

    @Test
    fun `the rows of a part being edited make one item, keyed as its anchor row`() {
        val items = listItems(listOf(0, 1, 1, 1, 2), { it }, editChunk = 1, anchorRow = 2)

        assertEquals(3, items.size)
        assertEquals(listOf(rowKey(0, 0), rowKey(1, 2), rowKey(2, 0)), items.map { it.key })
        assertEquals(listOf(1, 1, 1), (items[1] as ListItem.Editor).rows)
    }

    private fun state(
        merge: TextMerge = this.merge,
        workingFile: WorkingFileState = WorkingFileState.MATCHES,
        conflictsInFile: Int = 1,
    ) = ConflictResolverState(
        files = listOf(ResolverFileEntry(entry, resolvedHere = false)),
        file = ResolverFile.Loaded(
            file = ConflictedFile(
                path = entry.filePath,
                base = ConflictStage("1".repeat(40), 0b1000000110100100),
                ours = ConflictStage("2".repeat(40), 0b1000000110100100),
                theirs = ConflictStage("3".repeat(40), 0b1000000110100100),
                conflict = FileConflict(ConflictVersion.FILE, ConflictVersion.FILE),
                sides = sides,
                content = ConflictContent.Text(this.merge, WorkingFileCheck(workingFile, conflictsInFile, "id")),
            ),
            merge = merge,
            saved = this.merge,
        ),
        syntax = syntax,
    )

    private fun actions(
        picks: PickActions = PickActions({ _, _ -> }, { _, _ -> }, { _, _ -> }),
        onMarkResolved: () -> Unit = {},
        onMarkResolvedAsItIs: () -> Unit = {},
        onResolveAgain: () -> Unit = {},
        onPick: (ConflictSide) -> Unit = {},
        onPickBothNames: () -> Unit = {},
        onOpenVersion: (ConflictStage) -> Unit = {},
        onDownloadLfs: (LfsObject) -> Unit = {},
        onCancelLfsDownload: (LfsObject) -> Unit = {},
        onAnswerUnsaved: (Boolean?) -> Unit = {},
        onSetLayout: (ConflictResolverLayout) -> Unit = {},
        onStackedSplitChanged: (Float) -> Unit = {},
        onColumnSharesChanged: (ColumnShares) -> Unit = {},
        onFileListWidthChanged: (Float) -> Unit = {},
        onToggleWrapLines: () -> Unit = {},
        onToggleFocus: () -> Unit = {},
        onSelectFile: (String) -> Unit = {},
        onNextFile: () -> Unit = {},
        onUndo: () -> Unit = {},
        edits: EditActions = EditActions(onType = {}, onFinish = {}),
    ) = ResolverActions(
        onClose = {},
        onSelectFile = onSelectFile,
        onPreviousConflict = {},
        onNextConflict = {},
        onToggleBase = {},
        onSetLayout = onSetLayout,
        onStackedSplitChanged = onStackedSplitChanged,
        onColumnSharesChanged = onColumnSharesChanged,
        onFileListWidthChanged = onFileListWidthChanged,
        onToggleWrapLines = onToggleWrapLines,
        onToggleFocus = onToggleFocus,
        onSave = {},
        onMarkResolved = onMarkResolved,
        onOpenInExternalTool = {},
        onMarkResolvedAsItIs = onMarkResolvedAsItIs,
        onResolveAgain = onResolveAgain,
        onAnswerUnsaved = onAnswerUnsaved,
        onReload = {},
        onDismissPrompt = {},
        onNextFile = onNextFile,
        picks = picks,
        onUndo = onUndo,
        edits = edits,
        onPick = onPick,
        onPickBothNames = onPickBothNames,
        onOpenVersion = onOpenVersion,
        onDownloadLfs = onDownloadLfs,
        onCancelLfsDownload = onCancelLfsDownload,
    )

    private val lfsOurs = LfsObject("a".repeat(64), 4 * 1024)
    private val lfsTheirs = LfsObject("b".repeat(64), 5 * 1024)
    private val lfsBase = LfsObject("c".repeat(64), 3 * 1024)

    /** An LFS image whose ours is downloaded, and [theirs] and the base aren't. */
    private fun lfsChoice(
        theirs: VersionPreview.Lfs = VersionPreview.Lfs(lfsTheirs, downloaded = false),
        showBase: Boolean = false,
    ) = choice(
        ConflictContent.Lfs(lfsOurs, lfsTheirs),
        ConflictPreview(
            base = VersionPreview.Lfs(lfsBase, downloaded = false),
            ours = VersionPreview.Lfs(lfsOurs, downloaded = true),
            theirs = theirs,
        ),
        showBase = showBase,
    ) { it.copy(path = "assets/logo.png") }

    /** [state]'s file as a conflict that three panes can't show, with [preview], [pick] and the base [showBase]. */
    private fun choice(
        content: ConflictContent,
        preview: ConflictPreview?,
        pick: ConflictSide? = null,
        showBase: Boolean = false,
        change: (ConflictedFile) -> ConflictedFile = { it },
    ): ConflictResolverState = state().let { state ->
        val file = change((state.file as ResolverFile.Loaded).file.copy(content = content, preview = preview))

        state.copy(
            file = ResolverFile.Loaded(file, merge = null, saved = null, pick = pick?.let { ChoicePick.of(it) }),
            showBase = showBase,
        )
    }

    private fun ConflictResolverState.ours() = checkNotNull((file as ResolverFile.Loaded).file.ours)

    /** A line's marks as drawn: the ranges with a background, and its colour. */
    private data class ShownMarks(val ranges: List<IntRange>, val color: Color)

    /** The marks of every line drawn for [state], by line: one entry per time the line is drawn, marked or not. */
    private fun marksShown(state: ConflictResolverState): Map<String, List<ShownMarks>> {
        val scene = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
            AppTheme {
                ConflictResolverContent(state, ResolverLists(), actions())
            }
        }

        try {
            scene.render()

            return scene.nodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { text ->
                    val marked = text.spanStyles.filter { it.item.background.isSpecified && it.item.background.alpha > 0f }

                    text.text to ShownMarks(marked.map { it.start until it.end }, marked.firstOrNull()?.item?.background ?: Color.Transparent)
                }
                .groupBy({ it.first }, { it.second })
        } finally {
            scene.close()
        }
    }

    /** Every text in the scene. */
    private fun ImageComposeScene.texts(): List<String> =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** Clicks the clickable node that has [text] itself, not in a child: a link inside a clickable card. */
    private fun ImageComposeScene.clickOwnText(text: String) = click { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
    }

    /** Editing [chunk] of [merge], with [text] in the field, or what editing starts from. */
    private fun editing(chunk: Int, merge: TextMerge = this.merge, text: String? = null, line: Int? = null): ResolverEdit {
        val start = merge.editStart(chunk)
        val value = text ?: start.text

        return ResolverEdit(chunk, TextFieldValue(value, TextRange(value.length)), start, line, merge.editStartSyntax(chunk, syntax))
    }

    /** The text field of the part being edited. */
    private fun ImageComposeScene.field() = nodes().first { it.config.getOrNull(SemanticsProperties.EditableText) != null }

    /** Moves the pointer to [at], as the mouse does over the view. */
    private fun hover(scene: ImageComposeScene, at: Offset) {
        eventTime += 16
        scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = eventTime)
        scene.frames()
    }

    /**
     * What the view shows: its [texts], its icons' [descriptions], the texts that can be clicked, and where each
     * description is.
     */
    private class Rendered(
        val texts: List<String>,
        val descriptions: List<String>,
        val clickable: List<String>,
        val bounds: Map<String, List<Rect>>,
        val textBounds: Map<String, List<Rect>>,
    )

    /** The view of [state] in a 1,400 by 600 window, with [lists] that the test reads, for [frames]. */
    private fun scene(
        lists: ResolverLists,
        scrollToChunk: MutableSharedFlow<Int> = MutableSharedFlow(),
        actions: ResolverActions = actions(),
        state: () -> ConflictResolverState,
    ) = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
        AppTheme {
            ConflictResolverContent(state(), lists, actions, scrollToChunk = scrollToChunk)
        }
    }

    /** The scene's semantics nodes, every one. */
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return nodes
    }

    /** Clicks the first clickable node that has [text], or a child with it. */
    private fun ImageComposeScene.clickText(text: String) = click { node ->
        (listOf(node) + node.children).any { child ->
            child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }
    }

    /** Clicks the first clickable node whose description, or a child's, matches. */
    private fun ImageComposeScene.clickDescription(matches: (String) -> Boolean) = click { node ->
        (listOf(node) + node.children).any { child ->
            child.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any(matches)
        }
    }

    private fun ImageComposeScene.click(matches: (SemanticsNode) -> Boolean) {
        val node = nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && matches(it) }

        checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
    }

    /** Where the nodes with [text] are laid out, unclipped: their position and size. */
    private fun ImageComposeScene.placesOf(text: String): List<Rect> = nodes()
        .filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
        .map { Rect(it.positionInRoot, it.size.toSize()) }

    /**
     * How many colours the file's part of the view has, right of the file list and under the toolbar, every 4 px: one
     * while it's faded out.
     */
    private fun ImageComposeScene.coloursInFile(): Int {
        val bitmap = Bitmap.makeFromImage(render(frameTime))
        val colours = mutableSetOf<Int>()

        for (x in 400 until 1380 step 4) {
            for (y in 100 until 560 step 4) {
                colours += bitmap.getColor(x, y)
            }
        }

        return colours.size
    }

    /**
     * How far the file's part of the view stands out from its background: the pixels' average distance from the most
     * common colour, every 4 px, over the same part as [coloursInFile].
     */
    private fun ImageComposeScene.contrastInFile(): Double {
        val bitmap = Bitmap.makeFromImage(render(frameTime))
        val pixels = (400 until 1380 step 4).flatMap { x -> (100 until 560 step 4).map { y -> bitmap.getColor(x, y) } }
        val background = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key

        fun distance(colour: Int) = (0..2).sumOf { channel ->
            abs((colour shr (8 * channel) and 0xFF) - (background shr (8 * channel) and 0xFF))
        }

        return pixels.sumOf { distance(it) }.toDouble() / pixels.size
    }

    private var eventTime = 0L

    /** Presses the primary button at [from], moves to [to] in steps, and lets go. */
    private fun ImageComposeScene.drag(from: Offset, to: Offset, steps: Int = 10) {
        press(from)
        for (step in 1..steps) {
            eventTime += 16
            val at = from + (to - from) * (step / steps.toFloat())
            sendPointerEvent(PointerEventType.Move, at, timeMillis = eventTime, buttons = PointerButtons(isPrimaryPressed = true))
            frames(count = 1)
        }
        release(to)
        frames()
    }

    /** Two clicks at [at], 100 ms apart. */
    private fun ImageComposeScene.doubleClick(at: Offset) {
        repeat(2) {
            eventTime += 100
            press(at)
            release(at)
            frames(count = 2)
        }
        frames(count = 30)
    }

    private fun ImageComposeScene.press(at: Offset) {
        eventTime += 10
        val pressed = PointerButtons(isPrimaryPressed = true)
        sendPointerEvent(PointerEventType.Press, at, timeMillis = eventTime, buttons = pressed, button = PointerButton.Primary)
    }

    private fun ImageComposeScene.release(at: Offset) {
        eventTime += 10
        val none = PointerButtons()
        sendPointerEvent(PointerEventType.Release, at, timeMillis = eventTime, buttons = none, button = PointerButton.Primary)
    }

    private inline fun ImageComposeScene.closing(block: (ImageComposeScene) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private var frameTime = 0L

    /** Renders [count] frames, 16 ms apart, so that the effects scroll and the animations end. */
    private fun ImageComposeScene.frames(count: Int = 10) {
        repeat(count) {
            frameTime += 16_000_000L
            render(frameTime)
        }
    }

    /**
     * Renders the view in a 1,400 by 600 window, and clicks what has the text [clickText] or the description
     * [clickDescription]. The prompt comes after the toolbar, so with [inPrompt] it's the last match, else the first.
     * With `LEAF_RENDER_DIR` set, it also saves the image there.
     */
    private fun render(
        state: ConflictResolverState,
        actions: ResolverActions = actions(),
        clickText: String? = null,
        clickDescription: String? = null,
        inPrompt: Boolean = false,
    ): Rendered {
        val scene = ImageComposeScene(width = 1400, height = 600, density = Density(1f)) {
            AppTheme {
                ConflictResolverContent(state, ResolverLists(), actions)
            }
        }

        try {
            val image = scene.render()

            System.getenv("LEAF_RENDER_DIR")?.let { dir ->
                val name = (clickText ?: clickDescription ?: "view").replace(Regex("[^A-Za-z0-9]+"), "-")
                File(dir, "resolver-$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }

            val nodes = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }
            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            fun clickable(matches: (SemanticsNode) -> Boolean) = nodes.filter { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null && matches(node)
            }

            fun SemanticsNode.hasText(text: String) =
                config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }

            val target = when {
                // A node with the text itself first: a button inside a clickable card is the card's child
                clickText != null -> (
                    clickable { node -> node.hasText(clickText) }.ifEmpty {
                        clickable { node -> node.children.any { it.hasText(clickText) } }
                    }
                ).let { if (inPrompt) it.last() else it.first() }

                clickDescription != null -> clickable { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(clickDescription) ||
                        node.children.any {
                            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(clickDescription)
                        }
                }.first()

                else -> null
            }

            target?.let { checkNotNull(it.config[SemanticsActions.OnClick].action).invoke() }

            fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

            return Rendered(
                texts = nodes.flatMap { it.texts() },
                descriptions = nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() },
                clickable = clickable { true }.flatMap { node -> node.texts() + node.children.flatMap { it.texts() } },
                bounds = nodes
                    .flatMap { node ->
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().map { it to node.boundsInRoot }
                    }
                    .groupBy({ it.first }, { it.second }),
                textBounds = nodes
                    .flatMap { node -> node.texts().map { it to node.boundsInRoot } }
                    .groupBy({ it.first }, { it.second }),
            )
        } finally {
            scene.close()
        }
    }
}

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)
