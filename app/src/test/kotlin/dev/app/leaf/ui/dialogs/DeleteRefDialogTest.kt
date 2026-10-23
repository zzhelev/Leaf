// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.branches.BranchCopy
import dev.app.leaf.domain.branches.BranchDeletionInfo
import dev.app.leaf.domain.branches.BranchDeletionKind
import dev.app.leaf.domain.branches.BranchToDelete
import dev.app.leaf.domain.models.MergedIntoBase
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
 * The single "Delete branch" dialog's offer to delete the branch's merged copy on origin too (fork-only, follows
 * upstream issue 116), from its state. Set `LEAF_RENDER_DIR` to a folder to get a PNG of each state in both themes.
 */
class DeleteRefDialogTest {
    private val copy = BranchCopy(
        remote = "origin",
        info = BranchDeletionInfo(BranchToDelete("refs/remotes/origin/claude/eager-ride", "a".repeat(40)), null, MergedIntoBase.COMMITS, 0),
        kind = BranchDeletionKind.Merged(MergedIntoBase.COMMITS, 0),
        looksLongLived = false,
        goes = true,
    )

    @Test
    fun `a branch without a merged copy is deleted here only, as before`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(DeleteRefState()).use { dialog ->
            assertFalse(dialog.texts().any { it.startsWith("Also delete") }, dialog.texts().toString())

            dialog.click("Delete")
            assertEquals(listOf("delete"), dialog.calls)
        }
    }

    @Test
    fun `a merged copy is offered unchecked, and checking it asks for it`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(DeleteRefState(remoteCopy = copy)).use { dialog ->
            assertTrue("Also delete origin/claude/eager-ride on origin" in dialog.texts(), dialog.texts().toString())
            assertTrue(dialog.hasButton("Delete"))

            dialog.click("Also delete origin/claude/eager-ride on origin")
            assertEquals(listOf("copy true"), dialog.calls)
        }
    }

    @Test
    fun `asked for, the button says it deletes on origin too`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(DeleteRefState(remoteCopy = copy, deletesCopy = true)).use { dialog ->
            dialog.click("Delete here and on origin")
            assertEquals(listOf("delete"), dialog.calls)

            dialog.click("Also delete origin/claude/eager-ride on origin")
            assertEquals(listOf("delete", "copy false"), dialog.calls)
        }
    }

    @Test
    fun `a copy that isn't merged is offered too, marked`(): Unit = runBlocking(Dispatchers.Swing) {
        val unmerged = copy.copy(kind = BranchDeletionKind.OnOtherRefs)

        DialogScene(DeleteRefState(remoteCopy = unmerged)).use { dialog ->
            assertTrue("Also delete origin/claude/eager-ride on origin" in dialog.texts(), dialog.texts().toString())
            assertTrue("Not merged, a server can close its pull requests" in dialog.texts(), dialog.texts().toString())
        }

        DialogScene(DeleteRefState(remoteCopy = copy)).use { dialog ->
            assertFalse(dialog.texts().any { it.startsWith("Not merged") }, dialog.texts().toString())
        }
    }

    @Test
    fun `renders each state in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()
        val cases = linkedMapOf(
            "offered" to DeleteRefState(remoteCopy = copy),
            "asked" to DeleteRefState(remoteCopy = copy, deletesCopy = true),
            "unmerged" to DeleteRefState(remoteCopy = copy.copy(kind = BranchDeletionKind.OnOtherRefs)),
        )

        for (themeUnderTest in listOf(Theme.Dark, Theme.Light)) {
            for ((name, value) in cases) {
                DialogScene(value, themeUnderTest).use { dialog ->
                    File(folder, "delete-branch-copy-$name-${themeUnderTest.name.lowercase()}.png")
                        .writeBytes(dialog.scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                }
            }
        }
    }

    private inner class DialogScene(state: DeleteRefState, theme: Theme = Theme.Dark) : AutoCloseable {
        val calls = mutableListOf<String>()

        val scene = ImageComposeScene(width = 700, height = 500, density = Density(1f)) {
            AppTheme(theme) {
                DeleteRefDialogContent(
                    state = state,
                    title = "Delete branch",
                    subtitle = "Delete the branch claude/eager-ride?",
                    onDeletesCopyChange = { calls += "copy $it" },
                    onDelete = { calls += "delete" },
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

        fun hasButton(text: String): Boolean = clickables(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(clickables(text).first().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        private fun clickables(text: String): List<SemanticsNode> = nodes().filter { node ->
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
