// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.UnsupportedIndexError
import dev.app.leaf.domain.index.UnsupportedIndex
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.getErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val CONVERT = "Convert index…"

/**
 * The strip that says the worktree's index is in a format that Leaf can't read, with the commands that convert it, and
 * the same explanation in the error of an operation that failed on it.
 */
class UnsupportedIndexBannerTest {
    private val home = System.getProperty("user.home")
    private val workTree = "$home/dev/monorepo"

    @Test
    fun `a split index says what fails and how to convert it`(): Unit = runBlocking(Dispatchers.Swing) {
        Banner(UnsupportedIndex.Split(workTree, splitIndexConfigured = false)).use { banner ->
            assertEquals(
                "This repository's index is split, which Leaf can't read. Uncommitted changes, staging, commits and " +
                    "checkouts won't work until it's converted.  git update-index --no-split-index",
                banner.text(),
            )

            banner.click(CONVERT)

            assertEquals(listOf("convert"), banner.calls)
        }
    }

    @Test
    fun `with core splitIndex on, both commands show`(): Unit = runBlocking(Dispatchers.Swing) {
        Banner(UnsupportedIndex.Split(workTree, splitIndexConfigured = true)).use { banner ->
            assertTrue(
                banner.text().endsWith("git config core.splitIndex false && git update-index --no-split-index"),
                banner.text(),
            )
        }
    }

    @Test
    fun `a sparse index keeps the sparse checkout`(): Unit = runBlocking(Dispatchers.Swing) {
        Banner(UnsupportedIndex.Sparse(workTree)).use { banner ->
            assertEquals(
                "This repository's index is sparse, which Leaf can't read. Uncommitted changes, staging, commits and " +
                    "checkouts won't work until it's converted. Your sparse checkout stays as it is.  " +
                    "git sparse-checkout reapply --no-sparse-index",
                banner.text(),
            )
        }
    }

    @Test
    fun `an extension that git can't convert has no button`(): Unit = runBlocking(Dispatchers.Swing) {
        Banner(UnsupportedIndex.UnknownExtension(workTree, "abcd")).use { banner ->
            assertEquals(
                "This repository's index has an extension that Leaf can't read (abcd). Uncommitted changes, staging, " +
                    "commits and checkouts won't work, and git has no command that converts it.",
                banner.text(),
            )
            assertEquals(emptyList<SemanticsNode>(), banner.buttons(CONVERT))
        }
    }

    @Test
    fun `an operation that fails on the index explains it, with the commands for a terminal`(): Unit =
        runBlocking(Dispatchers.Swing) {
            var text = ""
            val error = UnsupportedIndexError(UnsupportedIndex.Split(workTree, splitIndexConfigured = true))

            val scene = ImageComposeScene(width = 400, height = 100, density = Density(1f)) {
                AppTheme { text = error.getErrorText() }
            }
            scene.render()
            scene.close()

            assertEquals(
                listOf(
                    "This repository's index is split, which Leaf can't read. Uncommitted changes, staging, commits " +
                        "and checkouts won't work until it's converted.",
                    "Convert it with Convert index… in the strip above the repository, or in a terminal:",
                    "cd ~/dev/monorepo\ngit config core.splitIndex false\ngit update-index --no-split-index",
                ).joinToString("\n\n"),
                text,
            )
        }

    private class Banner(index: UnsupportedIndex) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 2000, height = 100, density = Density(1f)) {
            AppTheme {
                UnsupportedIndexBanner(index = index, onConvert = { calls += "convert" })
            }
        }

        init {
            scene.render()
        }

        /** The strip's text, without the button's. */
        fun text(): String = nodes()
            .filter { it.config.getOrNull(SemanticsActions.OnClick) == null }
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString("") { it.text }

        fun click(text: String) {
            checkNotNull(buttons(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        fun buttons(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
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
