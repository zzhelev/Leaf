// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ChangedBy
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictContent
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictResolution
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictStage
import dev.app.leaf.domain.models.ConflictVersion
import dev.app.leaf.domain.models.ConflictedFile
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.LineRange
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.TextMerge
import dev.app.leaf.domain.models.TextMerge.Chunk
import dev.app.leaf.domain.models.WorkingFileCheck
import dev.app.leaf.domain.models.WorkingFileState
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.ConflictResolverState
import dev.app.leaf.viewmodels.ResolverFile
import dev.app.leaf.viewmodels.ResolverFileEntry
import dev.app.leaf.viewmodels.ResolverLeave
import dev.app.leaf.viewmodels.ResolverPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.RawText
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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
    fun `the arrows take a side, and a change can be undone`(): Unit = runBlocking(Dispatchers.Swing) {
        val taken = mutableListOf<Pair<Int, ConflictSide>>()
        val applied = mutableListOf<Pair<Int, Boolean>>()
        val columns = ColumnActions(
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
    fun `a binary conflict offers each side, named`(): Unit = runBlocking(Dispatchers.Swing) {
        val used = mutableListOf<ConflictSide>()
        val state = state().let { state ->
            val file = (state.file as ResolverFile.Loaded).file.copy(content = ConflictContent.Binary)
            state.copy(file = ResolverFile.Loaded(file, merge = null, saved = null))
        }

        render(state, actions(onUseSide = { _, side, _ -> used += side }), clickText = "Use ours (main)")
        render(state, actions(onUseSide = { _, side, _ -> used += side }), clickText = "Use theirs (feature/retry)")

        assertEquals(listOf(ConflictSide.OURS, ConflictSide.THEIRS), used)
    }

    @Test
    fun `a file that theirs deleted offers ours' version, or deleting it`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = state().let { state ->
            val file = (state.file as ResolverFile.Loaded).file.copy(
                theirs = null,
                conflict = FileConflict(ConflictVersion.FILE, null),
                content = ConflictContent.Deleted,
            )
            state.copy(file = ResolverFile.Loaded(file, merge = null, saved = null))
        }
        val texts = render(state).texts

        assertTrue("Use ours (main)" in texts, "$texts")
        assertTrue("Delete the file, as theirs (feature/retry) did" in texts, "$texts")
        assertTrue(texts.any { it.startsWith("theirs (feature/retry) deleted this file, and ours (main) changed it") }, "$texts")
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
    )

    private fun actions(
        columns: ColumnActions = ColumnActions({ _, _ -> }, { _, _ -> }, { _, _ -> }),
        onMarkResolved: () -> Unit = {},
        onMarkResolvedAsItIs: () -> Unit = {},
        onResolveAgain: () -> Unit = {},
        onUseSide: (StatusEntry, ConflictSide, ConflictSides?) -> Unit = { _, _, _ -> },
        onAnswerUnsaved: (Boolean?) -> Unit = {},
    ) = ResolverActions(
        onClose = {},
        onSelectFile = {},
        onPreviousConflict = {},
        onNextConflict = {},
        onToggleBase = {},
        onSave = {},
        onMarkResolved = onMarkResolved,
        onOpenInExternalTool = {},
        onMarkResolvedAsItIs = onMarkResolvedAsItIs,
        onResolveAgain = onResolveAgain,
        onUseSide = onUseSide,
        onAnswerUnsaved = onAnswerUnsaved,
        onReload = {},
        onDismissPrompt = {},
        columns = columns,
    )

    /**
     * What the view shows: its [texts], its icons' [descriptions], the texts that can be clicked, and where each
     * description is.
     */
    private class Rendered(
        val texts: List<String>,
        val descriptions: List<String>,
        val clickable: List<String>,
        val bounds: Map<String, List<Rect>>,
    )

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
                ConflictResolverContent(state, LazyListState(), actions)
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

            val target = when {
                clickText != null -> clickable { node ->
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == clickText } ||
                        node.children.any { child ->
                            child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == clickText }
                        }
                }.let { if (inPrompt) it.last() else it.first() }

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
            )
        } finally {
            scene.close()
        }
    }
}

private fun text(vararg lines: String) = RawText(lines.joinToString("") { "$it\n" }.toByteArray())

private fun lines(start: Int, end: Int) = LineRange(start, end)
