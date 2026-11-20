// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.cherrypick.BranchCherryPick
import dev.app.leaf.domain.cherrypick.CherryPickCandidate
import dev.app.leaf.domain.cherrypick.CherryPickLeftOut
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.WorktreeStatus
import dev.app.leaf.domain.log.ParentLink
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.repositoryopen.CommitEditorState
import dev.app.leaf.repositoryopen.ParentJump
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.commiteditor.CommitEditor
import dev.app.leaf.ui.dialogs.CherryPickBranchDialogContent
import dev.app.leaf.ui.dialogs.CherryPickBranchState
import dev.app.leaf.viewmodels.HistoryState
import dev.app.leaf.domain.models.ViewDiffResult
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private val ada = Identity("Ada Lovelace", "ada@example.com")
private val github = Identity("GitHub", "noreply@github.com")

private const val DAY = 24 * 3600L

/** 2025-03-04 12:30 UTC. */
private const val WRITTEN = 1_741_091_400L

private fun commit(
    hash: String,
    subject: String,
    authorDate: Long,
    date: Long,
    committer: Identity = ada,
    parent: String? = null,
) = Commit(
    hash = hash.padEnd(40, '0'),
    message = subject,
    committer = committer,
    author = ada,
    date = date,
    parentsHashes = listOfNotNull(parent?.padEnd(40, '0')),
    authorDate = authorDate,
)

/** Newest first: rebased a week after it was written, merged on GitHub, and committed as written. */
private val rebased = commit("c0ffee1", "Cache the parsed files", WRITTEN + 2 * DAY, WRITTEN + 9 * DAY, parent = "b1c2d3e")
private val merged = commit("b1c2d3e", "Merge the lexer", WRITTEN + DAY, WRITTEN + DAY, github, parent = "a4880d1")
private val plain = commit("a4880d1", "Add the parser", WRITTEN, WRITTEN, parent = "e3c6632")

/**
 * File history and the commit editor show each commit's author date, as the log's Author date column and the commit
 * details do (fork-only). Set `LEAF_RENDER_DIR` for PNGs of both in both themes, and pass `--rerun`.
 */
class AuthorDateRowsTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    @Test
    fun `file history's rows and strip show the author date`() {
        var authored = emptyList<String>()
        var committed = ""
        var stripDate = ""

        render(900, 400) {
            authored = listOf(rebased, merged, plain).map { it.authorDate.toSmartSystemString() }
            committed = rebased.date.toSmartSystemString()
            stripDate = rebased.authorDate.toSmartSystemString(showTime = true)
            FileHistory()
        }

        val texts = texts()

        for (date in authored) assertTrue(date in texts, "$date in $texts")
        assertFalse(committed in texts, "The commit date isn't shown: $texts")
        assertTrue(texts.any { it.endsWith(stripDate) && it.contains("Ada Lovelace") }, texts.toString())
    }

    @Test
    fun `the commit editor's rows show the author date`() {
        var authored = emptyList<String>()
        var committed = ""

        render(1100, 400) {
            authored = listOf(rebased, merged, plain).map { it.authorDate.toSmartSystemString() }
            committed = rebased.date.toSmartSystemString()
            Editor()
        }

        val texts = texts()

        for (date in authored) assertTrue(date in texts, "$date in $texts")
        assertFalse(committed in texts, "The commit date isn't shown: $texts")
    }

    @Test
    fun `the cherry-pick dialog's rows show the author date`() {
        var authored = emptyList<String>()
        var committed = ""

        render(900, 900) {
            authored = listOf(plain, rebased).map { it.authorDate.toSmartSystemString() }
            committed = rebased.date.toSmartSystemString()
            CherryPick()
        }

        val texts = texts()

        for (date in authored) assertTrue(date in texts, "$date in $texts")
        assertFalse(committed in texts, "The commit date isn't shown: $texts")
    }

    @Test
    fun `the tooltip has the whole author date, and when and by whom it was committed when that differs`() {
        val tooltips = mutableMapOf<Commit, String>()
        var rebasedDates = emptyList<String>()
        var mergedDates = emptyList<String>()
        var plainDate = ""

        render(400, 100) {
            for (commit in listOf(rebased, merged, plain)) tooltips[commit] = authorDateTooltip(commit)
            rebasedDates = listOf(rebased.authorDate, rebased.date).map { it.toSmartSystemString(false, showTime = true) }
            mergedDates = listOf(merged.authorDate, merged.date).map { it.toSmartSystemString(false, showTime = true) }
            plainDate = plain.authorDate.toSmartSystemString(false, showTime = true)
        }

        assertEquals("Authored ${rebasedDates[0]}\nCommitted ${rebasedDates[1]}", tooltips[rebased])
        assertEquals(
            "Authored ${mergedDates[0]}\nCommitted by GitHub <noreply@github.com>, ${mergedDates[1]}",
            tooltips[merged],
        )
        assertEquals(plainDate, tooltips[plain])
    }

    @Test
    fun `a parent's tooltip has its author date, and when and by whom it was committed when that differs`() {
        val tooltips = mutableMapOf<Commit, String>()
        val dates = mutableMapOf<Commit, List<String>>()

        render(400, 100) {
            for (commit in listOf(rebased, merged, plain)) {
                tooltips[commit] = parentTooltip(ParentLink(commit.hash, 1, commit), false, ParentJump.None)
                dates[commit] = listOf(commit.authorDate, commit.date)
                    .map { it.toSmartSystemString(false, showTime = true) }
            }
        }

        assertEquals(
            "Cache the parsed files\nAda Lovelace · ${dates[rebased]!![0]}\n" +
                "Committed ${dates[rebased]!![1]}\n${rebased.hash}",
            tooltips[rebased],
        )
        assertEquals(
            "Merge the lexer\nAda Lovelace · ${dates[merged]!![0]}\n" +
                "Committed by GitHub <noreply@github.com>, ${dates[merged]!![1]}\n${merged.hash}",
            tooltips[merged],
        )
        assertEquals("Add the parser\nAda Lovelace · ${dates[plain]!![0]}\n${plain.hash}", tooltips[plain])
    }

    @Test
    fun `renders file history and the commit editor in both themes`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            render(900, 300, theme) { FileHistory() }
            save(File(folder, "author-date-file-history-${theme.name.lowercase()}.png"))

            render(1100, 260, theme) { Editor() }
            save(File(folder, "author-date-commit-editor-${theme.name.lowercase()}.png"))
        }
    }

    @Composable
    private fun FileHistory() {
        HistoryContentLoaded(
            historyState = HistoryState.Loaded("src/Parser.kt", listOf(rebased, merged, plain)),
            viewDiffResult = ViewDiffResult.DiffNotFound(null),
            scrollState = LazyListState(),
            selectedCommit = rebased,
            onCommitSelected = {},
            onSelectAdjacentCommit = {},
        )
    }

    @Composable
    private fun Editor() {
        val line = BranchLine(
            branch = RewriteFixtures.BRANCH,
            base = RewriteFixtures.MAIN,
            commits = listOf(rebased, merged, plain),
            upstream = null,
            pushed = emptySet(),
        )

        CommitEditor(
            CommitEditorState(plan = RebasePlan.of(line, listOf(plain, merged, rebased)), selected = rebased.hash),
            onAction = {},
            modifier = Modifier.width(1100.dp).fillMaxHeight(),
        )
    }

    /** The two commits that a cherry-pick copies, oldest first, and the merge between them, which it leaves out. */
    @Composable
    private fun CherryPick() {
        val branch = Branch("f".repeat(40), RewriteFixtures.BRANCH, isLocal = true)
        val pick = BranchCherryPick(
            branch = branch,
            currentBranch = "refs/heads/main",
            workingTree = "/repo",
            candidates = listOf(
                CherryPickCandidate(plain),
                CherryPickCandidate(merged, CherryPickLeftOut.MERGE),
                CherryPickCandidate(rebased),
            ),
            commitCount = 3,
            repositoryState = RepositoryState.SAFE,
            isSequenceInProgress = false,
            status = WorktreeStatus(0, 0, 0, 0, null, null),
        )

        CherryPickBranchDialogContent(
            branch = branch,
            state = CherryPickBranchState(pick = pick, selected = setOf(plain.hash, rebased.hash), asOneCommit = false),
            onToggle = {},
            onAsOneCommitChanged = {},
            onConfirm = {},
            onDismiss = {},
        )
    }

    private fun render(width: Int, height: Int, theme: Theme = Theme.Dark, content: @Composable () -> Unit) {
        closeScene()
        scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            AppTheme(theme) { content() }
        }
        repeat(20) { scene!!.render(it * 16_000_000L) }
    }

    private fun save(file: File) {
        file.writeBytes(scene!!.render(400_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        closeScene()
    }

    private fun texts(): List<String> = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        .sortedWith(compareBy<SemanticsNode>({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
}
