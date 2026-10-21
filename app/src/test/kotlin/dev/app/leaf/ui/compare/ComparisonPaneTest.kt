// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.compare

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.app.leaf.domain.compare.CompareSide
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Comparison
import dev.app.leaf.domain.models.ComparisonMode
import dev.app.leaf.domain.models.ComparisonMode.ALL
import dev.app.leaf.domain.models.ComparisonMode.SINCE_SPLIT
import dev.app.leaf.domain.models.ComparisonRelation
import dev.app.leaf.domain.models.ComparisonRelation.A_IS_ANCESTOR
import dev.app.leaf.domain.models.ComparisonRelation.B_IS_ANCESTOR
import dev.app.leaf.domain.models.ComparisonRelation.DIVERGED
import dev.app.leaf.domain.models.ComparisonRelation.SAME
import dev.app.leaf.domain.models.ComparisonRelation.UNRELATED
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.repositoryopen.ComparisonAction
import dev.app.leaf.repositoryopen.ComparisonState
import dev.app.leaf.repositoryopen.ComparisonTarget
import dev.app.leaf.repositoryopen.ReviewedWorktree
import dev.app.leaf.repositoryopen.toFileItems
import dev.app.leaf.theme.AppTheme
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/** The right pane's default width. */
private const val WIDTH = 330
private const val HEIGHT = 560

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(hash: String, subject: String) = Commit(
    hash = hash.padEnd(40, '0'),
    message = subject,
    committer = someone,
    author = someone,
    date = 1_760_000_000,
    parentsHashes = emptyList(),
)

private fun file(path: String, type: DiffEntry.ChangeType = DiffEntry.ChangeType.MODIFY) = object : DiffEntry() {
    init {
        changeType = type
        oldPath = if (type == ChangeType.ADD) DEV_NULL else path
        newPath = if (type == ChangeType.DELETE) DEV_NULL else path
    }
}

/**
 * The comparison pane (fork-only, `docs/fork/compare.md`, F3 and F7), rendered offscreen at the right pane's width
 * and clicked as a user would, in each state: diverged sides in both modes, one an ancestor of the other either way,
 * no common commit, the same commit, loading, failed, and the review of a worktree. Set `LEAF_RENDER_DIR` to a folder to
 * get a PNG of every state in both themes, and pass `--rerun`: Gradle doesn't see an environment variable change.
 */
class ComparisonPaneTest {
    private val main = CompareSide.Branch("refs/heads/main", "5d5537fe")
    private val feature = CompareSide.Branch("refs/heads/feat/clone-picker", "7d03f51a")

    private val featureCommits = listOf(
        commit("a41c9e2", "List remote refs with git ls-remote"),
        commit("7d03f51", "Add Choose… to the clone dialog"),
    )
    private val mainCommits = listOf(
        commit("acaf05d", "Docs: notes and changelog for the log's arrow keys"),
        commit("752fec1", "Make two Linux CI test failures pass"),
        commit("0267adf", "Set Leaf's version to 1.3.0"),
        commit("5d5537f", "Announced Leaf 1.3.0 in latest.json"),
    )
    private val featureFiles = listOf(
        file("app/src/main/kotlin/dev/app/leaf/ui/dialogs/CloneBranchChoices.kt", DiffEntry.ChangeType.ADD),
        file("app/src/main/kotlin/dev/app/leaf/ui/dialogs/CloneDialog.kt"),
        file("data/src/main/kotlin/dev/app/leaf/data/git/cli/remote/GitCliListRemoteRefsGitAction.kt", DiffEntry.ChangeType.ADD),
        file("domain/src/main/kotlin/dev/app/leaf/domain/models/RemoteRefs.kt", DiffEntry.ChangeType.ADD),
        file("app/src/main/composeResources/values/strings.xml"),
    )

    private var state by mutableStateOf<ComparisonState?>(null)
    private var theme = Theme.Dark
    private val actions = mutableListOf<ComparisonAction>()
    private val selectedCommits = mutableListOf<Commit>()
    private val clipboard = PaneClipboard()

    private var scene: ImageComposeScene? = null
    private var width = WIDTH
    private var frameTime = 0L

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    @Test
    fun `diverged sides show both, the switch, what the mode leaves out, both sides' commits and the files`() {
        state = state(DIVERGED, SINCE_SPLIT)
        render()

        assertEquals(listOf("A", "main", "5d5537f"), row("main").texts())
        assertEquals(listOf("B", "feat/clone-picker", "7d03f51"), row("feat/clone-picker").texts())
        assertTrue(tab("Since they split").isSelected())
        assertFalse(tab("All differences").isSelected())
        assertTrue(
            "What feat/clone-picker changed since it branched off main at e3c6632. main's 4 newer commits are left out." in texts(),
            texts().toString(),
        )
        assertTrue("Commits only in B" in texts())
        assertTrue("List remote refs with git ls-remote" in texts())
        // Commits only in A start closed
        assertTrue("Commits only in A" in texts())
        assertFalse("Set Leaf's version to 1.3.0" in texts())
        assertTrue("CloneDialog.kt" in texts(), texts().toString())
        assertWithinPane()
    }

    @Test
    fun `all differences says that A's newer commits show as undone`() {
        state = state(DIVERGED, ALL)
        render()

        assertTrue(tab("All differences").isSelected())
        assertTrue(
            "Every difference between the two snapshots. main's 4 newer commits show as if feat/clone-picker had undone them." in texts(),
            texts().toString(),
        )
    }

    @Test
    fun `the switch sends the other mode, and the shown one sends nothing`() {
        state = state(DIVERGED, SINCE_SPLIT)
        render()

        click(tab("Since they split").center())
        click(tab("All differences").center())

        assertEquals(listOf<ComparisonAction>(ComparisonAction.SetMode(ALL)), actions)
    }

    @Test
    fun `swap, the full view and end are the header's buttons`() {
        state = state(DIVERGED, SINCE_SPLIT)
        render()

        click(described("Swap A and B").center())
        click(described("Open the full view").center())
        click(described("End the comparison (Esc)").center())

        assertEquals(listOf(ComparisonAction.Swap, ComparisonAction.OpenFullView(), ComparisonAction.End), actions)
    }

    @Test
    fun `A's commits open and close with their section's header`() {
        state = state(DIVERGED, SINCE_SPLIT)
        render()

        click(node("Commits only in A").center())
        assertTrue("Set Leaf's version to 1.3.0" in texts())

        click(node("Commits only in A").center())
        assertFalse("Set Leaf's version to 1.3.0" in texts())
    }

    @Test
    fun `when A is B's ancestor there is one answer, a note, the commits from A to B and no switch`() {
        state = state(A_IS_ANCESTOR, SINCE_SPLIT, onlyInA = emptyList())
        render()

        assertFalse(texts().any { it == "Since they split" })
        assertTrue(
            "feat/clone-picker has 2 commits that main lacks, and main has none of its own, so both ways of comparing give the same files." in texts(),
            texts().toString(),
        )
        assertTrue("Commits from A to B" in texts())
        assertFalse("Commits only in A" in texts())
    }

    @Test
    fun `when B is A's ancestor, A's commits are open and show as undone`() {
        state = state(B_IS_ANCESTOR, ALL, onlyInB = emptyList())
        render()

        assertTrue(
            "main has 4 commits that feat/clone-picker lacks, and feat/clone-picker has none of its own. The files show them as if feat/clone-picker had undone them." in texts(),
            texts().toString(),
        )
        assertFalse("Commits only in B" in texts())
        assertTrue("Set Leaf's version to 1.3.0" in texts())
    }

    @Test
    fun `histories without a common commit say that every difference shows`() {
        state = state(UNRELATED, ALL)
        render()

        assertTrue("main and feat/clone-picker share no commit, so this shows every difference between them." in texts())
        assertFalse(texts().any { it == "All differences" })
    }

    @Test
    fun `the same commit has nothing to compare`() {
        val commit = CompareSide.Commit("e3c6632f")
        state = state(SAME, ALL, onlyInB = emptyList(), onlyInA = emptyList(), files = emptyList(), a = commit, b = commit.copy(tag = "refs/tags/v1"))
        render()

        assertTrue("e3c6632 and v1 are the same commit, so there is nothing to compare." in texts(), texts().toString())
        assertTrue("The two have the same files." in texts())
        // A side named by its commit doesn't repeat it
        assertEquals(listOf("A", "e3c6632"), row("e3c6632").texts())
    }

    @Test
    fun `loading says what it reads, and a read again keeps the last comparison`() {
        state = state(DIVERGED, SINCE_SPLIT).copy(isLoading = true, comparison = null, rows = emptyList())
        render()

        assertTrue("Reading the commits and files…" in texts())

        state = state(DIVERGED, SINCE_SPLIT).copy(isLoading = true)
        settle()

        assertFalse("Reading the commits and files…" in texts())
        assertTrue("List remote refs with git ls-remote" in texts())
    }

    @Test
    fun `a failed read shows the error in place, with Try again`() {
        state = state(DIVERGED, SINCE_SPLIT).copy(comparison = null, rows = emptyList(), error = GenericError("feat/clone-picker doesn't exist."))
        render()

        assertTrue("feat/clone-picker doesn't exist." in texts(), texts().toString())

        click(node("Try again").center())

        assertEquals(listOf<ComparisonAction>(ComparisonAction.TryAgain), actions)
    }

    @Test
    fun `the review keeps its line about the worktree's uncommitted changes, and what a branch that brings nothing says`() {
        val review = ReviewedWorktree("/dev/leaf-agent1", "leaf-agent1", uncommittedChanges = 2, isTabWorktree = false, canSwitchTo = true)
        state = state(B_IS_ANCESTOR, SINCE_SPLIT, onlyInB = emptyList(), files = emptyList()).copy(review = review)
        render()

        assertTrue("feat/clone-picker has no commits that main lacks, so a merge would bring nothing." in texts(), texts().toString())
        assertTrue("No changes: a merge would change no file." in texts())
        assertTrue("Plus 2 uncommitted changes in leaf-agent1, which a merge leaves out." in texts(), texts().toString())

        click(node("Switch to it").center())

        assertEquals(listOf<ComparisonAction>(ComparisonAction.ShowUncommittedChanges), actions)
    }

    @Test
    fun `a file opens the full view at it, and a commit's menu selects it in the log or copies its hash`() {
        state = state(DIVERGED, SINCE_SPLIT)
        render()

        click(node("CloneDialog.kt").center())

        assertEquals("app/src/main/kotlin/dev/app/leaf/ui/dialogs/CloneDialog.kt", (actions.single() as ComparisonAction.OpenFullView).entry!!.newPath)

        rightClick(node("List remote refs with git ls-remote").center())
        click(node("Select in the log").center())

        assertEquals(listOf(featureCommits[0]), selectedCommits)

        rightClick(node("List remote refs with git ls-remote").center())
        click(node("Copy commit hash").center())
        settle()

        assertEquals(featureCommits[0].hash, clipboard.text)
    }

    @Test
    fun `older commits than the listed ones are counted`() {
        val listed = featureCommits
        state = state(DIVERGED, SINCE_SPLIT).let { it.copy(comparison = it.comparison!!.copy(countOnlyInB = 1_002 + listed.size)) }
        render()

        assertTrue("1002 older commits aren't listed." in texts(), texts().toString())
    }

    @Test
    fun `in a narrow pane long names are cut and the buttons stay`() {
        width = 260
        state = state(DIVERGED, SINCE_SPLIT, b = CompareSide.Branch("refs/remotes/origin/agents/claude/very-long-branch-name-for-the-retry", "7d03f51a"))
        render()

        assertWithinPane()
        assertTrue(described("End the comparison (Esc)").boundsInWindow.right <= width)
        assertTrue(described("Swap A and B").boundsInWindow.right <= width)
    }

    @Test
    fun `renders every state in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()
        val review = ReviewedWorktree("/dev/leaf-agent1", "leaf-agent1", uncommittedChanges = 2, isTabWorktree = false, canSwitchTo = true)
        val cases = linkedMapOf(
            "diverged" to state(DIVERGED, SINCE_SPLIT),
            "diverged-all" to state(DIVERGED, ALL),
            "a-is-ancestor" to state(A_IS_ANCESTOR, SINCE_SPLIT, onlyInA = emptyList()),
            "b-is-ancestor" to state(B_IS_ANCESTOR, ALL, onlyInB = emptyList()),
            "unrelated" to state(UNRELATED, ALL),
            "loading" to state(DIVERGED, SINCE_SPLIT).copy(isLoading = true, comparison = null, rows = emptyList()),
            "failed" to state(DIVERGED, SINCE_SPLIT).copy(comparison = null, rows = emptyList(), error = GenericError("Couldn't download what the comparison needs: Could not resolve host: github.com")),
            "review" to state(DIVERGED, SINCE_SPLIT).copy(review = review),
        )

        for (themeUnderTest in listOf(Theme.Dark, Theme.Light)) {
            for ((name, value) in cases) {
                theme = themeUnderTest
                state = value
                render()
                val image = scene!!.render(frameTime)
                File(folder, "compare-pane-$name-${themeUnderTest.name.lowercase()}.png")
                    .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                closeScene()
            }
        }
    }

    private fun state(
        relation: ComparisonRelation,
        mode: ComparisonMode,
        onlyInB: List<Commit> = featureCommits,
        onlyInA: List<Commit> = mainCommits,
        files: List<DiffEntry> = featureFiles,
        a: CompareSide = main,
        b: CompareSide = feature,
    ): ComparisonState {
        val comparison = Comparison(
            a = a.revision,
            b = b.revision,
            mode = mode,
            relation = relation,
            mergeBase = if (relation == UNRELATED) null else "e3c6632f".padEnd(40, '0'),
            commitsOnlyInB = onlyInB,
            countOnlyInB = onlyInB.size,
            commitsOnlyInA = onlyInA,
            countOnlyInA = onlyInA.size,
            changes = files,
        )

        return ComparisonState(
            target = ComparisonTarget(a, b),
            mode = mode,
            isLoading = false,
            comparison = comparison,
            error = null,
            aCommit = a.commit,
            bCommit = b.commit,
            viewState = FilesViewState(),
            rows = buildFileRows(files = files.toFileItems(), state = FilesViewState(), isCollapsed = { false }),
            isFullView = false,
            review = null,
        )
    }

    private fun render() {
        frameTime = 0L

        scene = ImageComposeScene(width = width, height = HEIGHT) {
            AppTheme(theme) {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    state?.let { current ->
                        ComparisonPaneView(
                            state = current,
                            diffSelected = null,
                            listStates = remember(current.target) { ComparisonListStates() },
                            onAction = { actions += it },
                            onSelectCommit = { selectedCommits += it },
                        )
                    }
                }
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
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        settle()
    }

    private fun rightClick(position: Offset) {
        val scene = scene!!

        Thread.sleep(50)
        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isSecondaryPressed = true),
            button = PointerButton.Secondary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Secondary)
        settle()
    }

    /** The row whose merged texts include [text]: a side's line in the header. */
    private fun row(text: String): SemanticsNode = mergedNodes()
        .filter { text in it.texts() }
        .minByOrNull { it.texts().size }
        ?: error("No row with $text in ${mergedNodes().map { it.texts() }}")

    private fun node(text: String): SemanticsNode = nodes().firstOrNull { text in it.texts() }
        ?: error("No $text in ${texts()}")

    private fun tab(label: String): SemanticsNode = nodes().first { label in it.texts() }

    private fun described(description: String): SemanticsNode = nodes().firstOrNull {
        description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    } ?: error("Nothing described as $description")

    private fun SemanticsNode.isSelected() = config.getOrNull(SemanticsProperties.Selected) == true

    private fun SemanticsNode.center() = boundsInWindow.center

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun assertWithinPane() {
        for (node in nodes()) {
            if (node.texts().isEmpty()) continue

            assertTrue(node.boundsInWindow.right <= width + 0.5f, "${node.texts()} reaches ${node.boundsInWindow.right}, past the pane's $width")
        }
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun mergedNodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}

@OptIn(ExperimentalComposeUiApi::class)
private class PaneClipboard : Clipboard {
    @Volatile
    var text: String? = null

    override val nativeClipboard: NativeClipboard get() = error("Not used")

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
