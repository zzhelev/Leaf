// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.LogColumn
import dev.app.leaf.domain.models.LogColumnsSettings
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.extensions.toSystemTimeString
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.CommitFooter
import dev.app.leaf.ui.components.sort.SortMenuItem
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import androidx.compose.ui.geometry.Rect
import java.io.File

private val ada = Identity("Ada Lovelace", "ada@example.com")
private val github = Identity("GitHub", "noreply@github.com")

/** 2025-03-04 12:30 UTC. */
private const val WRITTEN = 1_741_091_400L

/** A week later. */
private const val REBASED = WRITTEN + 7 * 24 * 3600L

private fun commit(committer: Identity = ada, date: Long = REBASED, authorDate: Long = WRITTEN) = Commit(
    hash = "b7e1a90".padEnd(40, '0'),
    message = "Fix the log",
    committer = committer,
    author = ada,
    date = date,
    parentsHashes = emptyList(),
    authorDate = authorDate,
)

private fun GraphCommit(commit: Commit) = GraphCommit(commit, 0, emptyList(), emptyList(), emptyList(), 0)

/**
 * The log's Commit date and Author date columns, their menu, and the commit details' footer, which shows the author
 * date beside the author and a line for the commit when it differs (fork-only). Rendered offscreen; set
 * `LEAF_RENDER_DIR` for PNGs of the footer and the two columns in both themes, and pass `--rerun`.
 */
class CommitDatesTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    @Test
    fun `the two date columns show their own dates, the author date where the commit was written`() {
        var expected = emptyList<String>()

        render {
            expected = listOf(WRITTEN.toSmartSystemString(), REBASED.toSmartSystemString())
            Cells(commit(), shown(LogColumn.AuthorDate, LogColumn.CommitDate), showTime = false)
        }

        assertEquals(expected, texts())
        assertTrue(expected[0] != expected[1], "The dates differ: $expected")
    }

    @Test
    fun `showing the time puts each date's own time in its column`() {
        var expected = emptyList<String>()

        render {
            expected = listOf(
                WRITTEN.toSmartSystemString(),
                WRITTEN.toSystemTimeString(),
                REBASED.toSmartSystemString(),
                REBASED.toSystemTimeString(),
            )
            Cells(commit(), shown(LogColumn.AuthorDate, LogColumn.CommitDate), showTime = true)
        }

        assertEquals(expected, texts())
    }

    @Test
    fun `the headers name both dates`() {
        render {
            LogColumnHeaders(shown(LogColumn.AuthorDate, LogColumn.CommitDate).columns.filter { it.isVisible }, { _, _ -> }, {})
        }

        assertEquals(listOf("Author date", "Commit date"), texts())
    }

    @Test
    fun `the menu lists the author date after the author, and the commit date after the committer`() {
        val labels = menu(LogColumnsSettings()).filterIsInstance<SortMenuItem.Option>().map { it.label }

        assertEquals(
            listOf("Author", "Author date", "Committer", "Commit date", "Commit", "Show time", "Reset columns"),
            labels,
        )
    }

    @Test
    fun `the menu offers the time while either date is shown`() {
        val authorDateOnly = LogColumnsSettings().toggled(LogColumn.CommitDate).toggled(LogColumn.AuthorDate)
        val noDate = LogColumnsSettings().toggled(LogColumn.CommitDate)

        assertTrue(menu(authorDateOnly).any { it is SortMenuItem.Option && it.label == "Show time" })
        assertFalse(menu(noDate).any { it is SortMenuItem.Option && it.label == "Show time" })
    }

    @Test
    fun `the footer shows the author date beside the author, and when it was committed under it`() {
        var authored = ""
        var committed = ""

        render {
            authored = WRITTEN.toSmartSystemString(allowRelative = true, showTime = true)
            committed = REBASED.toSmartSystemString(allowRelative = true, showTime = true)
            Footer(commit())
        }

        // Name, then hash and author date, then the committed line and its date; "A" is the avatar's initial
        assertEquals(listOf("Ada Lovelace", "b7e1a90", authored, "Committed", committed), texts() - "A")
    }

    @Test
    fun `the footer names another committer`() {
        var committed = ""

        render {
            committed = REBASED.toSmartSystemString(allowRelative = true, showTime = true)
            Footer(commit(committer = github))
        }

        assertEquals(listOf("Committed by GitHub", committed), texts().takeLast(2))
    }

    @Test
    fun `the footer names another committer at the same minute too`() {
        render { Footer(commit(committer = github, date = WRITTEN)) }

        assertTrue("Committed by GitHub" in texts(), texts().toString())
    }

    @Test
    fun `the footer has no committed line when the author committed it in the same minute`() {
        render { Footer(commit(date = WRITTEN + 30)) }

        assertFalse(texts().any { it.startsWith("Committed") }, texts().toString())
    }

    @Test
    fun `the committed date ends where the author date does, on the same line as its label when they fit`() {
        render { Footer(commit()) }

        val dates = nodes().filter { it.text.contains("2025") }
        val label = nodes().single { it.text == "Committed" }

        assertEquals(2, dates.size, dates.toString())
        assertEquals(dates[0].bounds.right, dates[1].bounds.right, 0.5f)
        assertTrue(dates[1].bounds.right <= 330f + 0.5f, "${dates[1].bounds} within the pane")
        assertEquals(label.bounds.center.y, dates[1].bounds.center.y, 1f, "$label beside ${dates[1]}")
    }

    @Test
    fun `renders the footer and the columns`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            render(theme) {
                Column {
                    Footer(commit())
                    Footer(commit(committer = github))
                    Cells(commit(), shown(LogColumn.AuthorDate, LogColumn.CommitDate), showTime = true)
                }
            }
            File(folder, "commit-dates-${theme.name.lowercase()}.png")
                .writeBytes(scene!!.render(0).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            closeScene()
        }
    }

    @Test
    fun `a long committer name gives way to the date`() {
        var committed = ""

        render {
            committed = REBASED.toSmartSystemString(allowRelative = true, showTime = true)
            Footer(commit(committer = Identity("A committer whose name is far too long to fit", "x@example.com")))
        }

        val authored = nodes().first { it.text.contains("Mar 4") }
        val date = nodes().last { it.text == committed }
        val label = nodes().last { it.text.startsWith("Committed by") }

        assertTrue(label.bounds.bottom <= date.bounds.top, "$date is under $label")
        assertEquals(authored.bounds.right, date.bounds.right, 0.5f, "$date ends where $authored does")
    }

    private fun shown(vararg columns: LogColumn): LogColumnsSettings =
        LogColumnsSettings(
            columns = LogColumnsSettings.DEFAULT_COLUMNS.map { it.copy(isVisible = it.column in columns) },
        ).withDateShowingTime(true)

    @Composable
    private fun Cells(commit: Commit, settings: LogColumnsSettings, showTime: Boolean) {
        Row {
            LogColumnCells(
                GraphCommit(commit),
                LogColumnsLayout(settings.columns.filter { it.isVisible }, dateShowsTime = showTime),
            )
        }
    }

    @Composable
    private fun Footer(commit: Commit) {
        Column(Modifier.width(330.dp)) {
            CommitFooter(
                shortHash = commit.shortHash,
                hash = commit.hash,
                authorDate = commit.authorDate,
                author = commit.author,
                committer = commit.committer,
                committerDate = commit.date,
            )
        }
    }

    private fun menu(settings: LogColumnsSettings): List<SortMenuItem> {
        var items = emptyList<SortMenuItem>()

        render { items = logColumnsMenuItems(settings, leftOut = emptySet(), onAction = {}) }

        return items
    }

    private fun render(theme: Theme = Theme.Dark, content: @Composable () -> Unit) {
        closeScene()
        scene = ImageComposeScene(width = 520, height = 240) {
            AppTheme(theme) { content() }
        }
        repeat(5) { scene!!.render(it * 16_000_000L) }
    }

    private data class TextNode(val text: String, val bounds: Rect)

    private fun nodes(): List<TextNode> = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
        .sortedWith(compareBy<SemanticsNode>({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        .mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.let { text ->
                TextNode(text.joinToString("") { it.text }, node.boundsInRoot)
            }
        }

    private fun texts(): List<String> = nodes().map { it.text }
}
