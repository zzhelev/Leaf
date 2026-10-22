// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.compare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.compare.CompareSide
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonRelation
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.repositoryopen.ComparisonAction
import dev.app.leaf.repositoryopen.ComparisonState
import dev.app.leaf.repositoryopen.ComparisonTarget
import dev.app.leaf.repositoryopen.ShownCommit
import dev.app.leaf.repositoryopen.toFileItems
import dev.app.leaf.theme.AppTheme
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val WIDTH = 1180
private const val HEIGHT = 640

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(hash: String, subject: String) = Commit(hash.padEnd(40, '0'), subject, someone, someone, 1_760_000_000, emptyList())

private fun file(path: String, type: DiffEntry.ChangeType = DiffEntry.ChangeType.MODIFY) = object : DiffEntry() {
    init {
        changeType = type
        oldPath = if (type == ChangeType.ADD) DEV_NULL else path
        newPath = if (type == ChangeType.DELETE) DEV_NULL else path
    }
}

/**
 * A comparison's full view (fork-only, `docs/fork/compare.md`, F4), rendered offscreen at a 13-inch laptop's window
 * with a stand-in for the diff pane, which shows the buttons and labels that the view gives it: the header, the lists,
 * the files' stepper and ↑ ↓, and the sides above the diff. Set `LEAF_RENDER_DIR` for PNGs in both themes, and pass
 * `--rerun`.
 */
class ComparisonFullViewTest {
    private val main = CompareSide.Branch("refs/heads/main", "5d5537fe")
    private val feature = CompareSide.Branch("refs/heads/feat/clone-picker", "7d03f51a")
    private val subjects = mapOf("5d5537fe" to "Announced Leaf 1.3.0 in latest.json", "7d03f51a" to "Add Choose… to the clone dialog")

    private val files = listOf(
        file("app/src/main/kotlin/dev/app/leaf/ui/dialogs/CloneBranchChoices.kt", DiffEntry.ChangeType.ADD),
        file("app/src/main/kotlin/dev/app/leaf/ui/dialogs/CloneDialog.kt"),
        file("domain/src/main/kotlin/dev/app/leaf/domain/models/RemoteRefs.kt", DiffEntry.ChangeType.ADD),
    )

    private var state by mutableStateOf(state())
    private var shown by mutableStateOf<DiffEntry?>(null)
    private var isSplit by mutableStateOf(true)
    private var theme = Theme.Dark
    private val actions = mutableListOf<ComparisonAction>()

    private var scene: ImageComposeScene? = null
    private var frameTime = 0L

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    @Test
    fun `the header names both sides with their subjects, and has the switch, swap, back and end`() {
        render()

        assertTrue("main" in texts() && "feat/clone-picker" in texts(), texts().toString())
        assertTrue("Announced Leaf 1.3.0 in latest.json" in texts())
        assertTrue("Add Choose… to the clone dialog" in texts())
        assertTrue("Since they split" in texts() && "All differences" in texts())
        assertTrue(
            "What feat/clone-picker changed since it branched off main at e3c6632. main's 1 newer commit is left out." in texts(),
            texts().toString(),
        )

        click(node("All differences").center())
        click(described("Swap A and B").center())
        click(described("Back to the log (Esc)").center())
        click(described("End the comparison").center())

        assertEquals(
            listOf(ComparisonAction.SetMode(ComparisonMode.ALL), ComparisonAction.Swap, ComparisonAction.LeaveFullView, ComparisonAction.End),
            actions,
        )
    }

    @Test
    fun `without a file shown it asks for one, and the lists are on the left`() {
        render()

        assertTrue("Select a file to see what changed in it." in texts())
        assertFalse(texts().any { it.startsWith("Diff of") })
        assertTrue("Commits only in B" in texts())
        assertTrue("CloneDialog.kt" in texts())
        assertTrue(node("CloneDialog.kt").boundsInWindow.right <= 330f)
    }

    @Test
    fun `a file clicked in the list shows its diff in the view`() {
        render()

        click(node("CloneDialog.kt").center())

        assertEquals(files[1], (actions.single() as ComparisonAction.SelectEntry).entry)
    }

    @Test
    fun `the stepper says where the shown file is, and steps to the files before and after it`() {
        shown = files[1]
        render()

        assertTrue("Diff of app/src/main/kotlin/dev/app/leaf/ui/dialogs/CloneDialog.kt" in texts())
        assertTrue("2 of 3" in texts(), texts().toString())

        click(described("Previous file (↑)").center())
        click(described("Next file (↓)").center())

        assertEquals(listOf(files[0], files[2]), actions.map { (it as ComparisonAction.SelectEntry).entry })
    }

    @Test
    fun `the stepper stops at the ends`() {
        shown = files[0]
        render()

        assertTrue(isDisabled("Previous file (↑)"))
        assertFalse(isDisabled("Next file (↓)"))
        click(described("Previous file (↑)").center())

        shown = files[2]
        settle()

        assertFalse(isDisabled("Previous file (↑)"))
        assertTrue(isDisabled("Next file (↓)"))
        click(described("Next file (↓)").center())

        assertEquals(emptyList<ComparisonAction>(), actions)
    }

    @Test
    fun `arrow keys step through the files`() {
        shown = files[1]
        render()

        press(Key.DirectionDown)
        press(Key.DirectionUp)

        assertEquals(listOf(files[2], files[0]), actions.map { (it as ComparisonAction.SelectEntry).entry })
    }

    @Test
    fun `a file of a comparison read again is found by its paths`() {
        shown = file("app/src/main/kotlin/dev/app/leaf/ui/dialogs/CloneDialog.kt")
        render()

        assertTrue("2 of 3" in texts(), texts().toString())
    }

    @Test
    fun `A and B name the two sides of a split diff, and both on one line for a unified one`() {
        shown = files[1]
        render()

        val a = node("main · 5d5537f")
        val b = node("feat/clone-picker · 7d03f51")

        assertTrue(a.boundsInWindow.left < b.boundsInWindow.left)
        // Each over its half of the diff, which starts after the 330 px column and its 1 px line: B's badge and name
        // right after the middle
        val middle = 331 + (WIDTH - 331) / 2f
        assertTrue(b.boundsInWindow.left in middle..middle + 60, "${b.boundsInWindow} after $middle")

        isSplit = false
        settle()

        assertTrue(node("feat/clone-picker · 7d03f51").boundsInWindow.left < 330 + (WIDTH - 330) / 2)
        assertTrue("→" in texts())
    }

    @Test
    fun `while a commit's own changes are listed, its parent and the commit name the diff's sides, and its row and strip show`() {
        val picked = commit("a41c9e2", "List remote refs with git ls-remote").copy(parentsHashes = listOf("e3c6632f".padEnd(40, '0')))
        val ownFiles = files.take(1)
        state = state().copy(
            shownCommit = ShownCommit(picked, position = 1, count = 2, isLoading = false, error = null),
            files = ownFiles,
            rows = buildFileRows(files = ownFiles.toFileItems(), state = FilesViewState(), isCollapsed = { false }),
        )
        shown = ownFiles[0]
        render()

        val parent = node("Parent · e3c6632")
        // Over the diff, not the commit's row in the list on the left
        val own = nodes().first { "a41c9e2" in it.texts() && it.boundsInWindow.left > 331 }

        assertTrue(parent.boundsInWindow.left > 331)
        assertTrue(parent.boundsInWindow.left < own.boundsInWindow.left)
        assertFalse(texts().any { it == "main · 5d5537f" }, texts().toString())
        assertTrue("Changes of" in texts())
        // The stepper counts the commit's files
        assertTrue("1 of 1" in texts(), texts().toString())

        isSplit = false
        settle()

        assertTrue("→" in texts())

        // A commit without a parent
        state = state.copy(shownCommit = state.shownCommit!!.copy(commit = picked.copy(parentsHashes = emptyList())))
        settle()

        assertTrue("No parent" in texts(), texts().toString())
    }

    @Test
    fun `with the working tree as B, its label names it without HEAD's commit, and there is no swap`() {
        val workingTree = CompareSide.WorkingTree("7d03f51a", branch = "refs/heads/feat/clone-picker")
        state = state().copy(target = ComparisonTarget(main, workingTree))
        shown = files[1]
        render()

        assertTrue("Working tree" in texts(), texts().toString())
        assertFalse(texts().any { it.startsWith("Working tree ·") }, texts().toString())
        assertFalse(nodes().any { "Swap A and B" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        // Its line says what it's on, with no commit's subject
        assertTrue("feat/clone-picker, uncommitted" in texts(), texts().toString())
        assertFalse(nodes().any { "Add Choose… to the clone dialog" in it.texts() && it.boundsInWindow.top < 46f }, "a subject in the header")
    }

    @Test
    fun `renders the full view in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (themeUnderTest in listOf(Theme.Dark, Theme.Light)) {
            for ((name, split) in listOf("split" to true, "unified" to false)) {
                theme = themeUnderTest
                shown = files[1]
                isSplit = split
                render()
                File(folder, "compare-full-view-$name-${themeUnderTest.name.lowercase()}.png")
                    .writeBytes(scene!!.render(frameTime).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                closeScene()
            }
        }
    }

    private fun state(): ComparisonState {
        val comparison = Comparison(
            a = main.revision,
            b = feature.revision,
            mode = ComparisonMode.SINCE_SPLIT,
            relation = ComparisonRelation.DIVERGED,
            mergeBase = "e3c6632f".padEnd(40, '0'),
            commitsOnlyInB = listOf(commit("a41c9e2", "List remote refs with git ls-remote"), commit("7d03f51", "Add Choose… to the clone dialog")),
            countOnlyInB = 2,
            commitsOnlyInA = listOf(commit("5d5537f", "Announced Leaf 1.3.0 in latest.json")),
            countOnlyInA = 1,
            changes = files,
        )

        return ComparisonState(
            target = ComparisonTarget(main, feature),
            mode = ComparisonMode.SINCE_SPLIT,
            isLoading = false,
            comparison = comparison,
            error = null,
            aCommit = main.commit,
            bCommit = feature.commit,
            viewState = FilesViewState(),
            rows = buildFileRows(files = files.toFileItems(), state = FilesViewState(), isCollapsed = { false }),
            isFullView = true,
            review = null,
        )
    }

    private fun render() {
        frameTime = 0L

        scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            AppTheme(theme) {
                ComparisonFullViewContent(
                    state = state,
                    diffSelected = shown?.let { DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(it))) },
                    listStates = remember { ComparisonListStates() },
                    columnWidth = 330.dp,
                    subjectOf = { subjects[it] },
                    onAction = { actions += it },
                    onSelectCommit = {},
                    diff = { headerActions, sideLabels ->
                        Column {
                            Row {
                                Text("Diff of ${shown?.newPath}")
                                headerActions()
                            }
                            sideLabels(isSplit)
                        }
                    },
                )
            }
        }
        settle()
    }

    private fun settle() {
        repeat(30) {
            scene!!.render(frameTime)
            frameTime += 16_000_000L
        }
    }

    private fun click(position: Offset) {
        val scene = scene!!

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(PointerEventType.Press, position, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        settle()
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
        settle()
    }

    private fun node(text: String): SemanticsNode = nodes().firstOrNull { text in it.texts() } ?: error("No $text in ${texts()}")

    private fun described(description: String): SemanticsNode = nodes().firstOrNull {
        description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    } ?: error("Nothing described as $description")

    /** Whether the button described so can't be clicked: its merged node, which has the button's semantics. */
    private fun isDisabled(description: String): Boolean = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(true) }
        .first { description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        .config.contains(SemanticsProperties.Disabled)

    private fun SemanticsNode.center() = boundsInWindow.center

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
