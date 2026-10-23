// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.errors

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.RemoteBranchChangedError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TaskType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.repositories.CompletedTask
import dev.app.leaf.domain.repositories.FailureSeverity
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
 * A remote branch that someone pushed to since the fetch: the error dialog asks whether to delete it anyway, rather than
 * only saying that it wasn't (fork-only, follows upstream issue 116). Set `LEAF_RENDER_DIR` for PNGs in both themes.
 */
class ErrorDialogDeleteAnywayTest {
    private val now = "7c1d2e3".padEnd(40, '0')
    private val was = "9aca96d".padEnd(40, '0')
    private val changed = RemoteBranchChangedError("origin/claude/single", "refs/remotes/origin/claude/single", now, was)

    private fun failure(error: RemoteBranchChangedError) =
        CompletedTask.Failure(0L, TaskType.DeleteRemoteBranch, error, FailureSeverity.HIGH)

    @Test
    fun `it asks whether to delete the branch anyway, at the commit the server has now`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(failure(changed)).use { dialog ->
            assertTrue("Delete origin/claude/single anyway?" in dialog.texts(), dialog.texts().toString())
            assertTrue(
                dialog.texts().any {
                    it.startsWith("origin/claude/single wasn't deleted: someone pushed to it since you last fetched. On the server it's now 7c1d2e3, not 9aca96d.")
                },
                dialog.texts().toString(),
            )
            assertFalse(dialog.hasButton("OK"))

            dialog.click("Delete anyway")

            assertEquals(listOf("accept", "delete $now refs/remotes/origin/claude/single"), dialog.calls)
        }
    }

    @Test
    fun `Cancel leaves it`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(failure(changed)).use { dialog ->
            dialog.click("Cancel")

            assertEquals(listOf("accept"), dialog.calls)
        }
    }

    @Test
    fun `without the server's commit, it only says why, as before`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(failure(changed.copy(now = null))).use { dialog ->
            assertTrue("Deleting remote branch failed" in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.hasButton("Delete anyway"))
            assertTrue(dialog.hasButton("OK"))
        }
    }

    @Test
    fun `renders the question in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            DialogScene(failure(changed), theme).use { dialog ->
                File(folder, "error-delete-anyway-${theme.name.lowercase()}.png")
                    .writeBytes(dialog.scene.render(0L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }

    private inner class DialogScene(error: CompletedTask.Failure, theme: Theme = Theme.Dark) : AutoCloseable {
        val calls = mutableListOf<String>()

        val scene = ImageComposeScene(width = 760, height = 420, density = Density(1f)) {
            AppTheme(theme) {
                ErrorDialog(
                    error = error,
                    onAccept = { calls += "accept" },
                    onDeleteRemoteBranchAnyway = { branch: Branch -> calls += "delete ${branch.hash} ${branch.name}" },
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
