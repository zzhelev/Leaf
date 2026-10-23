// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.repositoryopen.SelectedCommits
import dev.app.leaf.theme.AppTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The default width of the right pane. */
private const val WIDTH = 330
private const val HEIGHT = 300

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(hash: String, message: String) =
    Commit(hash.padEnd(40, '0'), message, someone, someone, 1_760_000_000, emptyList())

/**
 * The right pane while commits are selected in the log with Shift (fork-only, step A2 of
 * `docs/fork/squash-and-rebase.md`), rendered at the pane's default width. Set `LEAF_RENDER_DIR` to a folder to get a
 * PNG in both themes, and pass `--rerun`: Gradle doesn't see an environment variable change.
 */
class SelectedCommitsPaneTest {
    private val selected = SelectedCommits(
        listOf(
            commit("d4e5f6a", "Test the cache\n"),
            commit("c3d4e5f", "fixup! Add parser cache\n"),
            commit("b2c3d4e", "Evict the oldest entries of the parser cache past five hundred files\n\nBody."),
            commit("a1b2c3d", "Add parser cache\n\nKeeps the parsed files by path."),
        ),
    )

    private var cleared = 0
    private var scene: ImageComposeScene? = null
    private var frameTime = 0L

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
    }

    @Test
    fun `it says how many are selected, and lists each one's subject and short hash, newest first`() {
        render()

        assertTrue("4 commits selected" in texts(), texts().toString())

        val rows = mergedNodes().filter { node -> node.texts().any { it.length == 7 && it in hashes() } }

        assertEquals(hashes(), rows.map { it.texts().last() })
        assertEquals("Test the cache", rows.first().texts().first())
        // A subject without its body
        assertEquals("Add parser cache", rows.last().texts().first())
        assertTrue(rows.zipWithNext().all { (above, below) -> above.boundsInWindow.top < below.boundsInWindow.top })
    }

    @Test
    fun `everything stays inside the pane, a long subject cut`() {
        render()

        for (node in nodes()) {
            if (node.texts().isEmpty()) continue

            assertTrue(node.boundsInWindow.right <= WIDTH + 0.5f, "${node.texts()} reaches ${node.boundsInWindow.right}")
        }
    }

    @Test
    fun `the close button clears the selection`() {
        render()

        val close = nodes().first {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Clear the selection (Esc)") == true
        }
        click(close.boundsInWindow.center)

        assertEquals(1, cleared)
    }

    @Test
    fun `renders in both themes`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            render(theme)
            val image = scene!!.render(frameTime)
            File(folder, "selected-commits-${theme.name.lowercase()}.png")
                .writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            closeScene()
        }
    }

    private fun hashes() = selected.commits.map { it.shortHash }

    private fun render(theme: Theme = Theme.Dark) {
        frameTime = 0L
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            AppTheme(theme) {
                SelectedCommitsPane(selected, onClear = { cleared++ })
            }
        }
        settle()
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

    private fun settle() {
        repeat(20) {
            scene!!.render(frameTime)
            frameTime += 16_000_000L
        }
    }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun mergedNodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
