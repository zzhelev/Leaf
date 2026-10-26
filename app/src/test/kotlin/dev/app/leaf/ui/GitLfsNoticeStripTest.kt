// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.lfs.GitLfsNotice
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The strip that says git outside Leaf lacks git-lfs (fork-only): its text and button for each notice, what they do,
 * and where the button sits. Set `LEAF_RENDER_DIR` for PNGs in both themes.
 */
class GitLfsNoticeStripTest {
    @Test
    fun `without git-lfs, it says what git outside Leaf does, and offers to install it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Strip(GitLfsNotice.NOT_INSTALLED).use { strip ->
                assertEquals(
                    "git-lfs isn't installed. Leaf handles this repository's LFS files, but git in a terminal or an " +
                        "agent would commit them whole.",
                    strip.message(),
                )
                assertFalse(strip.hasButton("Set up git-lfs"))

                strip.click("Install git-lfs…")

                assertEquals(listOf("install"), strip.calls)
            }
        }

    @Test
    fun `with git-lfs gone from where git's config names it, git fails on the files`(): Unit =
        runBlocking(Dispatchers.Swing) {
            Strip(GitLfsNotice.MISSING).use { strip ->
                assertEquals(
                    "git-lfs isn't installed. Leaf handles this repository's LFS files, but git in a terminal or an " +
                        "agent fails on them.",
                    strip.message(),
                )

                strip.click("Install git-lfs…")

                assertEquals(listOf("install"), strip.calls)
            }
        }

    @Test
    fun `with git-lfs installed but not set up, it offers to set it up`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(GitLfsNotice.NOT_SET_UP).use { strip ->
            assertEquals(
                "git-lfs isn't set up. Leaf handles this repository's LFS files, but git in a terminal or an agent " +
                    "would commit them whole.",
                strip.message(),
            )
            assertFalse(strip.hasButton("Install git-lfs…"))

            strip.click("Set up git-lfs")

            assertEquals(listOf("set up"), strip.calls)
        }
    }

    @Test
    fun `× hides it for the repository`(): Unit = runBlocking(Dispatchers.Swing) {
        for (notice in GitLfsNotice.entries) {
            Strip(notice).use { strip ->
                strip.click("Don't show again for this repository")

                assertEquals(listOf("dismiss"), strip.calls)
            }
        }
    }

    @Test
    fun `the button follows the text, and × is at the right edge`(): Unit = runBlocking(Dispatchers.Swing) {
        Strip(GitLfsNotice.NOT_SET_UP, width = 1600).use { strip ->
            val text = strip.bounds { it.textOf() == strip.message() && !it.isClickable() }
            val button = strip.bounds { it.isClickable() && it.textOf() == "Set up git-lfs" }
            val close = strip.bounds {
                it.isClickable() && it.contentDescription() == "Don't show again for this repository"
            }

            // A wide window leaves room after the text, which stays after the button rather than between them
            assertTrue(button.left - text.right < 24f) { "button at ${button.left}, text ends at ${text.right}" }
            assertTrue(close.left - button.right > 200f)
            assertEquals(1600f - 8f, close.right, 1f)
        }
    }

    @Test
    fun `renders each notice in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            for (notice in GitLfsNotice.entries) {
                for (width in listOf(1200, 700)) {
                    Strip(notice, width = width, theme = theme).use { strip ->
                        File(folder, "git-lfs-notice-${notice.key}-$width-${theme.name.lowercase()}.png")
                            .writeBytes(strip.scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    }
                }
            }
        }
    }

    private class Strip(notice: GitLfsNotice, width: Int = 1000, theme: Theme = Theme.Dark) : AutoCloseable {
        val calls = mutableListOf<String>()

        val scene = ImageComposeScene(width = width, height = 52, density = Density(1f)) {
            AppTheme(theme) {
                GitLfsNoticeStrip(
                    notice = notice,
                    onInstall = { calls += "install" },
                    onSetUp = { calls += "set up" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }

        init {
            scene.render()
        }

        /** The strip's text, which isn't a button's. */
        fun message(): String = nodes()
            .filter { !it.isClickable() }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .single()

        fun hasButton(text: String): Boolean = clickables(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(clickables(text).first().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        fun bounds(predicate: (SemanticsNode) -> Boolean): Rect = nodes().first(predicate).boundsInRoot

        private fun clickables(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.isClickable() && (node.textOf() == text || node.contentDescription() == text)
        }

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }
}

private fun SemanticsNode.isClickable() = config.getOrNull(SemanticsActions.OnClick) != null

private fun SemanticsNode.textOf(): String? = config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

private fun SemanticsNode.contentDescription(): String? =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")
