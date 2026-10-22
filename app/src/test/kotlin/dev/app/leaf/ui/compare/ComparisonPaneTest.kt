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
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
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
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.repositoryopen.ComparisonAction
import dev.app.leaf.repositoryopen.ComparisonState
import dev.app.leaf.repositoryopen.ComparisonTarget
import dev.app.leaf.repositoryopen.ReviewedWorktree
import dev.app.leaf.repositoryopen.ShownCommit
import dev.app.leaf.repositoryopen.SidePickerState
import dev.app.leaf.repositoryopen.SideToPick
import dev.app.leaf.domain.compare.refChoicesFor
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Tag
import dev.app.leaf.repositoryopen.toFileItems
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.compactLineHeight
import dev.app.leaf.theme.denseLineHeight
import dev.app.leaf.theme.spacedLineHeight
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
    private var linesHeightType = LinesHeightType.COMPACT
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
    fun `a commit's click asks for its own changes, and the commit shown is highlighted and named in a strip`() {
        state = state(DIVERGED, SINCE_SPLIT)
        render()

        click(node("List remote refs with git ls-remote").center())
        assertEquals(listOf<ComparisonAction>(ComparisonAction.ShowCommit(featureCommits[0])), actions)
        actions.clear()

        state = showing(featureCommits[0], position = 1, files = featureFiles.take(2))
        settle()

        assertTrue(row("List remote refs with git ls-remote").isSelected())
        assertFalse(row("Add Choose… to the clone dialog").isSelected())
        assertEquals(listOf("Changes of", "a41c9e2", "· 1 of 2"), row("Changes of").texts())
        // The whole strip fits the pane's default width
        assertFalse(node("Changes of").isCut())
        assertTrue(listOf("Files changed", "2") in texts().windowed(2), texts().toString())
        assertTrue("CloneDialog.kt" in texts())
        assertFalse("RemoteRefs.kt" in texts())
        assertWithinPane()

        // The oldest has no older one
        click(described("Older commit").center())
        click(described("Newer commit").center())
        click(node("All changes").center())
        // A second click on the commit shown
        click(node("List remote refs with git ls-remote").center())

        assertEquals(
            listOf(
                ComparisonAction.StepCommit(1),
                ComparisonAction.ShowAllChanges,
                ComparisonAction.ShowCommit(featureCommits[0]),
            ),
            actions,
        )
    }

    @Test
    fun `the newest commit has no newer one`() {
        state = showing(featureCommits[1], position = 2, files = featureFiles.take(1))
        render()

        click(described("Newer commit").center())
        click(described("Older commit").center())

        assertEquals(listOf<ComparisonAction>(ComparisonAction.StepCommit(-1)), actions)
    }

    @Test
    fun `a commit that changes no file says so, and one whose read failed shows the error with Try again`() {
        state = showing(featureCommits[0], position = 1, files = emptyList())
        render()

        assertTrue("This commit changes no file." in texts(), texts().toString())

        state = showing(featureCommits[0], position = 1, files = emptyList(), error = GenericError("Couldn't read a41c9e2."))
        settle()

        assertFalse("This commit changes no file." in texts())
        assertTrue("Couldn't read a41c9e2." in texts(), texts().toString())

        click(node("Try again").center())

        assertEquals(listOf<ComparisonAction>(ComparisonAction.TryAgain), actions)
    }

    @Test
    fun `while a commit's changes load, the list stays and no note says it's empty`() {
        state = showing(featureCommits[0], position = 1, files = emptyList(), isLoading = true)
        render()

        assertFalse("This commit changes no file." in texts())
        assertTrue("Changes of" in texts())
    }

    @Test
    fun `in a narrow pane the strip keeps its buttons`() {
        width = 260
        state = showing(featureCommits[0].copy(hash = "a41c9e2"), position = 12, count = 345, files = featureFiles)
        render()

        assertWithinPane()
        assertTrue(described("Newer commit").boundsInWindow.right <= width)
        assertTrue(node("All changes").boundsInWindow.right <= width)
        // "Changes of" gives way, and the commit's id stays whole
        assertTrue(node("Changes of").isCut())
        assertFalse(nodes().last { "a41c9e2" in it.texts() }.isCut())
    }

    // Any two refs (7.1)

    private val refs = listOf(Branch("5d5537fe", "refs/heads/main", isLocal = true), Branch("7d03f51a", "refs/heads/feat/clone-picker", isLocal = true))
    private val tags = listOf(Tag("e3c6632f", "t1", "refs/tags/v1.3.0"))

    private fun picking(side: SideToPick, text: String = "", resolved: Commit? = null, base: ComparisonState = state(DIVERGED, SINCE_SPLIT)) =
        base.copy(picker = SidePickerState(side, text, refChoicesFor(text, refs, emptyList(), tags, resolved)))

    @Test
    fun `a side's line opens its list, but not in the review`() {
        state = state(DIVERGED, SINCE_SPLIT)
        render()

        click(row("feat/clone-picker").center())
        click(row("main").center())

        assertEquals(
            listOf<ComparisonAction>(ComparisonAction.OpenSidePicker(SideToPick.B), ComparisonAction.OpenSidePicker(SideToPick.A)),
            actions,
        )
        actions.clear()

        state = state(DIVERGED, SINCE_SPLIT).copy(review = ReviewedWorktree("/dev/leaf-agent1", "leaf-agent1", 0, false, true))
        settle()
        click(row("feat/clone-picker").center())

        assertEquals(emptyList<ComparisonAction>(), actions)
    }

    @Test
    fun `with the list open, the side is a field and the refs hang under the header, by group, with how many match`() {
        state = picking(SideToPick.B)
        render()

        assertTrue("Branch, tag or commit" in texts(), texts().toString())
        // B's name gave way to the field
        assertFalse(mergedNodes().any { it.texts().take(2) == listOf("B", "feat/clone-picker") })
        assertTrue(listOf("BRANCHES", "feat/clone-picker", "main", "TAGS", "v1.3.0").all { it in texts() }, texts().toString())
        assertTrue("3 of 3 match. A hash or HEAD~2 works too." in texts(), texts().toString())

        // The list is under the header, as wide as it
        val list = node("BRANCHES").boundsInWindow
        val aLine = mergedNodes().first { it.texts().take(2) == listOf("A", "main") }.boundsInWindow
        assertTrue(list.top > aLine.bottom, "$list under $aLine")

        click(node("v1.3.0").center())

        assertEquals(listOf<ComparisonAction>(ComparisonAction.PickSide(CompareSide.Commit("e3c6632f", tag = "refs/tags/v1.3.0"))), actions)
    }

    @Test
    fun `the keys move through the list, and Enter picks, starting from the first row once something is typed`() {
        // Both branches have an a
        state = picking(SideToPick.A, text = "a")
        render()

        press(Key.Enter)
        press(Key.DirectionDown)
        press(Key.Enter)

        assertEquals(
            listOf<ComparisonAction>(
                ComparisonAction.PickSide(CompareSide.Branch("refs/heads/feat/clone-picker", "7d03f51a")),
                ComparisonAction.PickSide(CompareSide.Branch("refs/heads/main", "5d5537fe")),
            ),
            actions,
        )

        press(Key.Escape)
        assertEquals(ComparisonAction.CloseSidePicker, actions.last())
    }

    @Test
    fun `a revision that git resolves is a commit row with its subject, and nothing matching says what works`() {
        val commit = commit("a1e4f9a", "Docs: notes and changelog for the diff's View menu")
        state = picking(SideToPick.B, text = "a1e4f9", resolved = commit)
        render()

        assertTrue(listOf("COMMIT", "a1e4f9a", "Docs: notes and changelog for the diff's View menu").all { it in texts() }, texts().toString())
        assertTrue("0 of 3 match. A hash or HEAD~2 works too." in texts(), texts().toString())

        state = picking(SideToPick.B, text = "nothing")
        settle()

        assertTrue("Nothing matches. A hash or HEAD~2 works too." in texts(), texts().toString())
    }

    @Test
    fun `a comparison with A alone asks for B, with nothing to swap or show`() {
        val pending = state(DIVERGED, SINCE_SPLIT).copy(
            target = ComparisonTarget(main, main),
            comparison = null,
            rows = emptyList(),
            isPending = true,
        )
        state = picking(SideToPick.B, base = pending)
        render()

        assertTrue("Choose what to compare main with." in texts(), texts().toString())
        assertFalse(texts().contains("Reading the commits and files…"))
        assertFalse(nodes().any { "Swap A and B" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        assertTrue(described("End the comparison (Esc)").boundsInWindow.right <= width)
    }

    // The working tree (7.2)

    private val workingTree = CompareSide.WorkingTree("7d03f51a", branch = "refs/heads/feat/clone-picker")

    @Test
    fun `the working tree as B is named by what it's on, can't be swapped, and its commits go on with the uncommitted changes`() {
        state = state(DIVERGED, SINCE_SPLIT, b = workingTree)
        render()

        assertEquals(listOf("B", "Working tree", "feat/clone-picker, uncommitted"), row("Working tree").texts())
        assertFalse(nodes().any { "Swap A and B" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        assertTrue(described("Open the full view").boundsInWindow.right <= width)
        assertTrue("and the uncommitted changes" in texts(), texts().toString())
        assertTrue(
            "What feat/clone-picker and its uncommitted changes would bring into main. main's 4 newer commits are left out." in texts(),
            texts().toString(),
        )

        state = state(DIVERGED, ALL, b = workingTree)
        settle()

        assertTrue(
            "Every difference between main and the files on disk. main's 4 newer commits show as if undone." in texts(),
            texts().toString(),
        )
    }

    @Test
    fun `the commit rows and the uncommitted changes' line are as tall as Lists spacing makes the log's rows`() {
        val heights = mapOf(
            LinesHeightType.SPACED to spacedLineHeight,
            LinesHeightType.COMPACT to compactLineHeight,
            LinesHeightType.DENSE to denseLineHeight,
        )

        for (type in LinesHeightType.entries) {
            linesHeightType = type
            state = state(DIVERGED, SINCE_SPLIT, b = workingTree)
            render()
            val expected = heights.getValue(type).logCommitHeight.value

            val rows = featureCommits.map { row(it.shortMessage) }
            for (commitRow in rows) {
                assertEquals(expected, commitRow.boundsInWindow.height, 0.5f, "$type: ${commitRow.texts()}")
            }
            // The line after the last commit takes a row of its own, its text centred in it
            val line = node("and the uncommitted changes").boundsInWindow
            assertEquals(rows.last().boundsInWindow.bottom + expected / 2, line.center.y, 1f, "$type")
            closeScene()
        }
    }

    @Test
    fun `the working tree's notes, by how HEAD meets A`() {
        state = state(SAME, ALL, onlyInB = emptyList(), onlyInA = emptyList(), b = workingTree)
        render()
        assertTrue("The working tree is on main, so this shows its uncommitted changes." in texts(), texts().toString())

        state = state(A_IS_ANCESTOR, SINCE_SPLIT, onlyInA = emptyList(), b = workingTree)
        settle()
        assertTrue("feat/clone-picker has 2 commits that main lacks, then the uncommitted changes." in texts(), texts().toString())

        // A ahead of HEAD: since they split is the uncommitted changes alone, and the switch shows
        state = state(B_IS_ANCESTOR, SINCE_SPLIT, onlyInB = emptyList(), b = workingTree)
        settle()
        assertTrue(tab("Since they split").isSelected())
        assertTrue(
            "What feat/clone-picker and its uncommitted changes would bring into main. main's 4 newer commits are left out." in texts(),
            texts().toString(),
        )

        state = state(UNRELATED, ALL, b = workingTree)
        settle()
        assertTrue("main and the working tree share no commit, so this shows every difference between them." in texts(), texts().toString())
    }

    @Test
    fun `a new file that git doesn't track yet is tagged untracked`() {
        val untracked = file("domain/src/test/kotlin/dev/app/leaf/domain/RemoteRefsTest.kt", DiffEntry.ChangeType.ADD)
        val files = featureFiles + untracked
        state = state(DIVERGED, SINCE_SPLIT, files = files, b = workingTree).copy(
            rows = buildFileRows(files = files.toFileItems(untracked = setOf(untracked.newPath)), state = FilesViewState(), isCollapsed = { false }),
        )
        render()

        assertEquals(1, texts().count { it == "untracked" }, texts().toString())
        // On its own row
        assertTrue(mergedNodes().any { "RemoteRefsTest.kt" in it.texts() && "untracked" in it.texts() }, mergedNodes().map { it.texts() }.toString())
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
            "commit" to showing(featureCommits[0], position = 1, files = featureFiles.take(2)),
            "picker" to picking(SideToPick.B, text = "v1"),
            "working-tree" to state(DIVERGED, SINCE_SPLIT, b = workingTree, files = featureFiles).let { wt ->
                wt.copy(rows = buildFileRows(files = featureFiles.toFileItems(untracked = setOf(featureFiles[0].newPath)), state = FilesViewState(), isCollapsed = { false }))
            },
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

    /** Diverged sides, listing [commit]'s own changes, [files]. */
    private fun showing(
        commit: Commit,
        position: Int,
        files: List<DiffEntry>,
        count: Int = featureCommits.size,
        isLoading: Boolean = false,
        error: GenericError? = null,
    ): ComparisonState = state(DIVERGED, SINCE_SPLIT).copy(
        shownCommit = ShownCommit(commit, position, count, isLoading, error),
        files = files,
        rows = buildFileRows(files = files.toFileItems(), state = FilesViewState(), isCollapsed = { false }),
        isLoading = isLoading,
    )

    private fun render() {
        frameTime = 0L

        scene = ImageComposeScene(width = width, height = HEIGHT) {
            AppTheme(theme, linesHeightType) {
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

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
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

    /** Whether the text got less room than it needs on one line, so that it's cut. */
    private fun SemanticsNode.isCut(): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()

        return layout.multiParagraph.intrinsics.maxIntrinsicWidth > layout.size.width + 0.5f
    }

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
