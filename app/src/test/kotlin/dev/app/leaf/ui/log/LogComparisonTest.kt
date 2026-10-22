// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.repositoryopen.LogAction
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.compare.CompareLetter
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val WIDTH = 420
private const val HEIGHT = 40
private const val A = "a"
private const val B = "b"

/** The log while comparing (fork-only, `docs/fork/compare.md`, 3.3): its badges, and what its clicks do. */
class LogComparisonTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private val author = Identity("Maya Chen", "maya.chen@example.com")
    private val commit = Commit(A.repeat(40), "Parse the config once", author, author, 0L, emptyList())
    private val feature = Branch(commit.hash, "refs/heads/feature", true)

    @Test
    fun `a row is A or B by its commit, A when both are the same commit`() {
        val comparison = LogComparison(A, B, selectedName = null)

        assertEquals(CompareLetter.A, comparison.letterOf(A))
        assertEquals(CompareLetter.B, comparison.letterOf(B))
        assertNull(comparison.letterOf("c"))
        assertEquals(CompareLetter.A, LogComparison(A, A).letterOf(A))
        assertTrue(comparison.isComparing)

        val none = LogComparison(selectedName = "main")
        assertNull(none.letterOf(A))
        assertEquals(false, none.isComparing)
    }

    @Test
    fun `a compare click picks the row, a plain one selects it`() {
        assertEquals(LogAction.CommitPicked(commit, isStash = false), commitRowClick(commit, false, isCompareClick = true))
        assertEquals(LogAction.CommitPicked(commit, isStash = true), commitRowClick(commit, true, isCompareClick = true))
        assertEquals(LogAction.CommitSelected(commit, isStash = false), commitRowClick(commit, false, isCompareClick = false))
    }

    @Test
    fun `a compare click on the uncommitted changes picks the working tree, a plain click selects them`() {
        assertEquals(LogAction.UncommittedChangesPicked, uncommittedRowClick(isCompareClick = true))
        assertEquals(LogAction.UncommittedChangesSelected, uncommittedRowClick(isCompareClick = false))
    }

    @Test
    fun `a side's row has its badge before the chips, in both themes`() {
        for (theme in listOf(Theme.Dark, Theme.Light)) {
            for (letter in CompareLetter.entries) {
                val bitmap = render(theme, letter)
                val badge = node(letter.name)
                val chip = node("feature")

                assertTrue(
                    badge.boundsInWindow.right < chip.boundsInWindow.left,
                    "$theme $letter: the badge ${badge.boundsInWindow} before the chip ${chip.boundsInWindow}",
                )
                assertTrue(
                    badge.boundsInWindow.left < 24f,
                    "$theme $letter: the badge starts the message, at ${badge.boundsInWindow.left}",
                )

                // The badge's fill, just inside its left edge, at its middle (the outline is 1 px on light)
                val y = badge.boundsInWindow.center.y.toInt()
                val x = (badge.boundsInWindow.center.x - 5).toInt()
                assertEquals(letter.fill.toArgb(), bitmap.getColor(x, y), "$theme $letter's fill")
            }
        }
    }

    @Test
    fun `a row that isn't a side has no badge, and its chips start where they did`() {
        render(Theme.Dark, letter = null)
        val chipWithout = node("feature").boundsInWindow.left

        assertTrue(nodes().none { node -> texts(node).any { it == "A" || it == "B" } })

        // The badge, 8 dp in, its 16 dp and 6 dp after it, take the place of the chips' 16 dp start padding
        render(Theme.Dark, CompareLetter.A)
        assertEquals(chipWithout + 14f, node("feature").boundsInWindow.left)
        assertEquals(16f, node("A").boundsInWindow.center.x, 0.5f, "the badge is centred 16 dp in, clear of the divider")
    }

    private fun render(theme: Theme, letter: CompareLetter?): Bitmap {
        scene?.close()
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) {
            AppTheme(theme) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    CommitMessage(
                        graphCommit = GraphCommit(
                            commit = commit,
                            lane = 0,
                            passingLanes = emptyList(),
                            mergingLanes = emptyList(),
                            forkingOffLanes = emptyList(),
                            childCount = 0,
                        ),
                        columnsLayout = LogColumnsLayout(shown = emptyList(), dateShowsTime = false),
                        currentBranch = null,
                        isStash = false,
                        tags = emptyList(),
                        branches = listOf(feature),
                        nodeColor = MaterialTheme.colors.primary,
                        compareLetter = letter,
                        onCheckoutBranch = {},
                        onMergeBranch = {},
                        onDeleteBranch = {},
                        onDeleteRemoteBranch = {},
                        onRebaseBranch = {},
                        onCheckoutTag = {},
                        onDeleteTag = {},
                        onPushRemoteBranch = {},
                        onPullRemoteBranch = {},
                        onChangeDefaultUpstreamBranch = {},
                        onRenameBranch = {},
                        onCopyBranchNameToClipboard = {},
                    )
                }
            }
        }

        // Laid out once for the chips' sizes, then drawn
        scene!!.render(System.nanoTime())
        val bitmap = Bitmap.makeFromImage(scene!!.render(System.nanoTime()))

        System.getenv("LEAF_RENDER_DIR")?.let { dir ->
            val name = "log-row-${theme.name.lowercase()}-${letter?.name ?: "none"}.png"
            File(dir, name).writeBytes(scene!!.render(System.nanoTime()).encodeToData()!!.bytes)
        }

        return bitmap
    }

    private fun node(text: String): SemanticsNode = nodes().first { text in texts(it) }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
