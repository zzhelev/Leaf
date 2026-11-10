// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A click on a blamed line's author and subject selects the commit that the line comes from (fork-only). */
class BlameSelectsCommitTest {
    @TempDir
    lateinit var dir: File

    private var scene: ImageComposeScene? = null
    private val selected = mutableListOf<String>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun commit(git: Git, content: String, message: String): String {
        File(dir, "notes.txt").writeText(content)
        git.add().addFilepattern("notes.txt").call()
        val ident = PersonIdent("Maya Chen", "maya.chen@example.com")

        return git.commit().setMessage(message).setSign(false).setAuthor(ident).setCommitter(ident).call().name
    }

    private fun show(blame: BlameResult) {
        scene = ImageComposeScene(width = 1200, height = 400, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                Blame("notes.txt", blame, onSelectCommit = { selected += it }, onClose = {})
            }
        }.also { it.render() }
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return nodes
    }

    /** The clickable column that shows a line's author and the [subject] of its commit. */
    private fun authorColumn(subject: String): SemanticsNode = nodes()
        .filter { it.config.contains(SemanticsActions.OnClick) }
        .single { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == subject } }

    private fun click(node: SemanticsNode) {
        node.config[SemanticsActions.OnClick].action!!.invoke()
    }

    @Test
    fun `each line's column selects its own commit, by its id`(): Unit = runBlocking(Dispatchers.Swing) {
        Git.init().setDirectory(dir).call().use { git ->
            val first = commit(git, "one\n", "Start the notes")
            val second = commit(git, "one\ntwo\n", "Add a second line")

            show(git.blame().setFilePath("notes.txt").call()!!)

            click(authorColumn("Add a second line"))
            click(authorColumn("Start the notes"))

            assertEquals(listOf(second, first), selected)
        }
    }

    @Test
    fun `a line that no commit has yet can't be clicked`(): Unit = runBlocking(Dispatchers.Swing) {
        Git.init().setDirectory(dir).call().use { git ->
            commit(git, "one\n", "Start the notes")
            File(dir, "notes.txt").writeText("one\nnot committed\n")

            show(git.blame().setFilePath("notes.txt").call()!!)

            val uncommitted = authorColumn("Uncommitted change")
            assertTrue(uncommitted.config.contains(SemanticsProperties.Disabled), "the uncommitted line's column is disabled")
            assertFalse(authorColumn("Start the notes").config.contains(SemanticsProperties.Disabled))
        }
    }
}
