// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.LineRange
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.RewriteKind
import dev.app.leaf.repositoryopen.RewriteNotice
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.RewriteFixtures.BRANCH
import dev.app.leaf.ui.RewriteFixtures.add
import dev.app.leaf.ui.RewriteFixtures.cache
import dev.app.leaf.ui.RewriteFixtures.wip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The strip after a branch's commits were rewritten (fork-only, step A3 of `docs/fork/squash-and-rebase.md`): what it
 * says for each kind and after Undo, and its buttons; and what the notice takes from the rewrite.
 */
class RewriteStripTest {
    private fun result(kind: RewriteKind) = BranchRewriteResult(
        branch = BRANCH,
        kind = kind,
        oldTip = cache.hash,
        newTip = "9".repeat(40),
        rewritten = emptyList(),
    )

    private fun notice(range: LineRange, kind: RewriteKind, currentBranch: String? = BRANCH) =
        RewriteNotice.of(result(kind), range, currentBranch)

    @Test
    fun `a squash of pushed commits says so, and offers to push with force`(): Unit = runBlocking(Dispatchers.Swing) {
        val range = LineRange(RewriteFixtures.line(pushed = setOf(add.hash, wip.hash, cache.hash)), listOf(wip, cache))

        StripScene(notice(range, RewriteKind.SQUASH)).use { strip ->
            assertEquals(
                listOf(
                    "Squashed 2 commits into one on claude/parser-cache.",
                    "origin/claude/parser-cache still has the old commits: push with force to replace them.",
                ),
                strip.texts().filterNot { it in BUTTONS },
            )

            strip.click("Push with force…")
            strip.click("Undo")
            strip.clickClose()

            assertEquals(listOf("push", "undo", "dismiss"), strip.calls)
        }
    }

    @Test
    fun `a squash onto the latest main says on top of which`(): Unit = runBlocking(Dispatchers.Swing) {
        val range = LineRange(RewriteFixtures.line(), listOf(add, wip, cache))
        val onto = OntoBase("refs/heads/main", "5".repeat(40))
        val notice = RewriteNotice.of(result(RewriteKind.SQUASH).copy(onto = onto), range, BRANCH)

        StripScene(notice).use { strip ->
            assertEquals(
                listOf("Squashed 3 commits into one on claude/parser-cache, on top of main."),
                strip.texts().filterNot { it in BUTTONS },
            )
        }
    }

    @Test
    fun `a fixup names the commit fixed up, and nothing pushed means no push`(): Unit = runBlocking(Dispatchers.Swing) {
        StripScene(notice(LineRange(RewriteFixtures.line(), listOf(wip, cache)), RewriteKind.FIXUP)).use { strip ->
            assertEquals(listOf("Fixed up c0ffee1 into its parent on claude/parser-cache."), strip.texts().filterNot { it in BUTTONS })
            assertFalse(strip.isClickable("Push with force…"))
            assertTrue(strip.isClickable("Undo"))
        }
    }

    @Test
    fun `a reword names the commit, and another branch than the tab's has no push`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val range = LineRange(RewriteFixtures.line(pushed = setOf(add.hash, wip.hash, cache.hash)), listOf(wip))

            StripScene(notice(range, RewriteKind.REWORD, currentBranch = "refs/heads/main")).use { strip ->
                assertEquals(
                    listOf(
                        "Changed the message of b1c2d3e on claude/parser-cache.",
                        "origin/claude/parser-cache still has the old commits: push with force to replace them.",
                    ),
                    strip.texts().filterNot { it in BUTTONS },
                )
                assertFalse(strip.isClickable("Push with force…"))
            }
        }

    @Test
    fun `after Undo it says where the branch is back, with only the close button`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val range = LineRange(RewriteFixtures.line(pushed = setOf(add.hash, wip.hash, cache.hash)), listOf(wip, cache))

            StripScene(notice(range, RewriteKind.SQUASH).copy(isUndone = true)).use { strip ->
                assertEquals(listOf("Undone: claude/parser-cache is back at c0ffee1."), strip.texts().filterNot { it in BUTTONS })
                assertFalse(strip.isClickable("Undo"))
                assertFalse(strip.isClickable("Push with force…"))

                strip.clickClose()
                assertEquals(listOf("dismiss"), strip.calls)
            }
        }

    @Test
    fun `the notice counts the commits, and names the upstream only when the rewrite changed pushed ones`() {
        val unpushed = LineRange(RewriteFixtures.line(pushed = setOf(add.hash)), listOf(wip, cache))
        val noUpstream = LineRange(RewriteFixtures.line(upstream = null), listOf(add, wip, cache))

        assertEquals(2, notice(unpushed, RewriteKind.SQUASH).joined)
        assertNull(notice(unpushed, RewriteKind.SQUASH).pushedTo)
        assertFalse(notice(unpushed, RewriteKind.SQUASH).canPush)
        assertNull(notice(noUpstream, RewriteKind.SQUASH).pushedTo)
        assertEquals("b1c2d3e", notice(unpushed, RewriteKind.SQUASH).commit)
        assertEquals("c0ffee1", notice(unpushed, RewriteKind.FIXUP).commit)
    }

    private class StripScene(notice: RewriteNotice) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 1100, height = 80, density = Density(1f)) {
            AppTheme {
                RewriteStrip(
                    notice = notice,
                    onPushWithForce = { calls += "push" },
                    onUndo = { calls += "undo" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        fun isClickable(text: String) = clickables(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(clickables(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        fun clickClose() {
            val close = nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Close")
            }

            checkNotNull(close.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun clickables(text: String) = nodes().filter { node ->
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

    private companion object {
        val BUTTONS = setOf("Push with force…", "Undo")
    }
}
