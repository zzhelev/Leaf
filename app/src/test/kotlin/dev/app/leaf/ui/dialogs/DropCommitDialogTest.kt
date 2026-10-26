// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanPlace
import dev.app.leaf.domain.rewrite.PlanStop
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.repositoryopen.PlanFile
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.RewriteFixtures
import dev.app.leaf.ui.RewriteFixtures.cache
import dev.app.leaf.ui.RewriteFixtures.wip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "Drop commit…" (fork-only, choice 4a of `docs/fork/commit-editor.md`): the commit and its files, Drop disabled while
 * the commits after it are checked, "Drop commit" for a plan that doesn't stop and "Drop and rebase" with where it
 * stops for one that does, and the box for the tab's uncommitted changes.
 */
class DropCommitDialogTest {
    private val plan = RebasePlan.of(RewriteFixtures.line(), listOf(wip, cache)).withAction(wip.hash, PlanAction.DROP)
    private val here = PlanPlace(worktreeName = "leaf", isTabs = true)

    @Test
    fun `it names the commit and its files, and Drop waits for the check`(): Unit = runBlocking(Dispatchers.Swing) {
        val gate = CompletableDeferred<DropCheck>()

        DialogScene(check = { gate.await() }).use { dialog ->
            assertTrue("Drop b1c2d3e?" in dialog.texts(), dialog.texts().toString())
            assertTrue("wip" in dialog.texts())
            assertTrue("NOTES.md" in dialog.texts(), dialog.texts().toString())
            assertTrue("Checking the commits after it…" in dialog.texts())
            assertFalse(dialog.isEnabled("Drop commit"))

            gate.complete(DropCheck(PlanCheck(emptyList(), null), here))
            dialog.settle()

            assertTrue(dialog.isEnabled("Drop commit"))
            dialog.click("Drop commit")

            assertEquals(listOf("confirm stops=false autostash=false", "dismiss"), dialog.calls)
        }
    }

    @Test
    fun `a drop that stops says where, and asks to set the tab's changes aside first`(): Unit = runBlocking(Dispatchers.Swing) {
        val check = DropCheck(
            PlanCheck(emptyList(), PlanStop.Conflict(cache.hash, listOf("src/Cache.kt"))),
            here.copy(uncommitted = 1),
        )

        DialogScene(check = { check }).use { dialog ->
            assertTrue("Stops at c0ffee1 “Cache the parsed files”: Cache.kt conflicts." in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.isEnabled("Drop and rebase"))

            dialog.click("Set my 1 uncommitted change aside, and put it back after the rebase")
            dialog.click("Drop and rebase")

            assertEquals(listOf("confirm stops=true autostash=true", "dismiss"), dialog.calls)
        }
    }

    private inner class DialogScene(check: suspend () -> DropCheck) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 900, height = 700, density = Density(1f)) {
            AppTheme {
                DropCommitDialog(
                    plan = plan,
                    check = check,
                    filesOf = { listOf(PlanFile("NOTES.md", DiffEntry.ChangeType.MODIFY)) },
                    onConfirm = { checked, autostash -> calls += "confirm stops=${checked.stops} autostash=$autostash" },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }
        private var time = 0L

        init {
            settle()
        }

        fun settle() {
            repeat(10) {
                scene.render(time)
                time += 16_000_000L
            }

            runBlocking { delay(50) }
            scene.render(time)
        }

        fun texts(): List<String> = nodes(false).flatMap { it.texts() }

        fun isEnabled(text: String) = nodes(true).any { node ->
            text in node.texts() && node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Disabled) == null
        }

        fun click(text: String) {
            val node = nodes(true).first { text in it.texts() && it.config.getOrNull(SemanticsActions.OnClick) != null }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            settle()
        }

        private fun nodes(merged: Boolean): List<SemanticsNode> = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(merged) }

        private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

        override fun close() = scene.close()
    }
}
