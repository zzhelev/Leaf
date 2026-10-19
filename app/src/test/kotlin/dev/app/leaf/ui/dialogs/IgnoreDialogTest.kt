// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.IgnoreError
import dev.app.leaf.domain.ignore.IgnoreContext
import dev.app.leaf.domain.ignore.IgnoreFile
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.ignore.IgnoreRequest
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val ADD = "Add"

/** What the ignore dialog shows and does, from its state (fork-only). */
class IgnoreDialogTest {
    private val file = IgnoreRequest.Files(listOf("build/out.log"))

    @Test
    fun `offers the file, its folder, its extension and a pattern, and writes the first by default`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(file, context()).use { dialog ->
                val texts = dialog.texts()

                for (expected in listOf(
                    "Ignore", "build/out.log", "This file", "Everything in build/", "All *.log files",
                    "Custom pattern", ".gitignore", ".git/info/exclude", "Lines to add to .gitignore", "/build/out.log",
                )) {
                    assertTrue(expected in texts, "$expected in $texts")
                }

                assertTrue(dialog.isEnabled(ADD))

                dialog.click(ADD)

                assertEquals(listOf("add", "dismiss"), dialog.calls)
            }
        }

    @Test
    fun `the lines follow the choice and the place`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(file, context(excludePath = "/repo/.git/info/exclude")).use { dialog ->
            dialog.click("Everything in build/")
            assertTrue("/build/" in dialog.texts(), dialog.texts().toString())

            dialog.click("All *.log files")
            assertTrue("*.log" in dialog.texts(), dialog.texts().toString())

            dialog.click(".git/info/exclude")
            assertTrue("Lines to add to /repo/.git/info/exclude" in dialog.texts(), dialog.texts().toString())
            assertEquals(IgnorePlace.EXCLUDE, dialog.state.place)

            dialog.click(".gitignore")
            assertEquals(IgnorePlace.GITIGNORE, dialog.state.place)
        }
    }

    @Test
    fun `the global list is the third place, with the path of its file`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(file, context()).use { dialog ->
            assertTrue("Global ignore list" in dialog.texts(), dialog.texts().toString())
            assertTrue("On this computer, for every repository." in dialog.texts(), dialog.texts().toString())

            dialog.click("Global ignore list")

            assertEquals(IgnorePlace.GLOBAL, dialog.state.place)
            assertTrue("Lines to add to /dotfiles/global-ignore" in dialog.texts(), dialog.texts().toString())
            assertTrue("/build/out.log" in dialog.texts(), dialog.texts().toString())
            assertTrue(dialog.isEnabled(ADD))
        }
    }

    @Test
    fun `the path of the global list shows the home folder as a tilde`(): Unit = runBlocking(Dispatchers.Swing) {
        val home = System.getProperty("user.home")

        DialogScene(file, context(globalPath = "$home/.gitignore_global")).use { dialog ->
            dialog.click("Global ignore list")

            assertTrue("Lines to add to ~/.gitignore_global" in dialog.texts(), dialog.texts().toString())
        }
    }

    @Test
    fun `a global list that the repository's config overrides says why it can't be used`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val overridden = context(globalProblem = IgnoreError.GlobalListOverridden("/elsewhere/ignore"))

            DialogScene(file, overridden).use { dialog ->
                dialog.click("Global ignore list")

                val texts = dialog.texts()

                assertTrue(texts.any { it.contains("sets core.excludesFile to /elsewhere/ignore") }, texts.toString())
                assertFalse(dialog.isEnabled(ADD))

                dialog.click(".gitignore")

                assertTrue(dialog.isEnabled(ADD))
            }
        }

    @Test
    fun `starts at the place used last`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(file, context(remembered = IgnorePlace.EXCLUDE, excludePath = "/repo/.git/info/exclude")).use { dialog ->
            assertTrue("Lines to add to /repo/.git/info/exclude" in dialog.texts(), dialog.texts().toString())
        }
    }

    @Test
    fun `a rule the file has already is said so, and nothing is added`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(file, context(gitignoreLines = listOf("node_modules", "/build/out.log"))).use { dialog ->
            assertTrue(".gitignore already has this." in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.isEnabled(ADD))
        }
    }

    @Test
    fun `says how many of the lines are there already, and shows only the others`(): Unit = runBlocking(Dispatchers.Swing) {
        val files = IgnoreRequest.Files(listOf("a.log", "b.log"))

        DialogScene(files, context(gitignoreLines = listOf("/a.log"))).use { dialog ->
            val texts = dialog.texts()

            assertTrue("/b.log" in texts, texts.toString())
            assertTrue("1 of these lines is already there." in texts, texts.toString())
            assertTrue(dialog.isEnabled(ADD))
        }
    }

    @Test
    fun `a pattern field starts from the selected file, and what is typed is the line written`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(file, context()).use { dialog ->
                dialog.click("Custom pattern")

                assertEquals("/build/out.log", dialog.fieldText())

                dialog.type("/build/*.log\n\n# note")

                assertTrue("/build/*.log\n# note" in dialog.texts(), dialog.texts().toString())
                assertEquals(listOf("/build/*.log", "# note"), dialog.state.rules)
                assertTrue(dialog.isEnabled(ADD))

                dialog.type("  \n")

                assertTrue("Type a pattern to add." in dialog.texts(), dialog.texts().toString())
                assertFalse(dialog.isEnabled(ADD))
            }
        }

    @Test
    fun `several files offer their folders and extensions once each`(): Unit = runBlocking(Dispatchers.Swing) {
        val files = IgnoreRequest.Files(listOf("a/x.log", "a/y.tmp", "c/z.log", "w.log"))

        DialogScene(files, context()).use { dialog ->
            val texts = dialog.texts()

            for (expected in listOf(
                "4 untracked files", "These 4 files", "Everything in 2 folders: a/, c/",
                "All files that match *.log, *.tmp",
            )) {
                assertTrue(expected in texts, "$expected in $texts")
            }

            dialog.click("Everything in 2 folders: a/, c/")

            assertEquals(listOf("/a/", "/c/"), dialog.state.rules)
        }
    }

    @Test
    fun `a folder row offers the folder`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(IgnoreRequest.Folder("build/gen"), context()).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Folder build/gen" in texts, texts.toString())
            assertTrue("Everything in build/gen/" in texts, texts.toString())
            assertTrue("/build/gen/" in texts, texts.toString())
            assertFalse("This file" in texts)
        }
    }

    @Test
    fun `a file name with a line break is left out, and said so`(): Unit = runBlocking(Dispatchers.Swing) {
        val files = IgnoreRequest.Files(listOf("ok.txt", "bad\nname.txt"))

        DialogScene(files, context()).use { dialog ->
            val texts = dialog.texts()

            assertTrue(texts.any { it.startsWith("1 file has a line break in its name") }, texts.toString())
            assertEquals(listOf("/ok.txt"), dialog.state.rules)
        }
    }

    @Test
    fun `a file that can't be written is shown instead of the lines, and the dialog can't add`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val broken = context(
                gitignoreProblem = IgnoreError.FileNotWritable("/repo/.gitignore", IgnoreError.Reason.SYMBOLIC_LINK),
            )

            DialogScene(file, broken).use { dialog ->
                val texts = dialog.texts()

                assertTrue(texts.any { it.contains("/repo/.gitignore is a symbolic link") }, texts.toString())
                assertFalse(texts.any { it.startsWith("Lines to add") }, texts.toString())
                assertFalse(dialog.isEnabled(ADD))

                // The other place is still fine
                dialog.click(".git/info/exclude")

                assertTrue(dialog.isEnabled(ADD))
            }
        }

    @Test
    fun `says that the files are being read, and shows an error that stops it`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(file, null).use { dialog ->
            assertTrue("Reading the ignore files…" in dialog.texts(), dialog.texts().toString())
            assertFalse(dialog.isEnabled(ADD))
        }

        DialogScene(file, null, error = GenericError("The repository couldn't be read")).use { dialog ->
            assertTrue(dialog.texts().any { it.contains("The repository couldn't be read") })
            assertFalse("Reading the ignore files…" in dialog.texts())
            assertFalse(dialog.isEnabled(ADD))
        }
    }

    private fun context(
        remembered: IgnorePlace = IgnorePlace.GITIGNORE,
        gitignoreLines: List<String> = emptyList(),
        gitignoreProblem: IgnoreError? = null,
        excludePath: String = "/repo/.git/info/exclude",
        globalPath: String = "/dotfiles/global-ignore",
        globalLines: List<String> = listOf("*~"),
        globalProblem: IgnoreError? = null,
    ) = IgnoreContext(
        remembered,
        mapOf(
            IgnorePlace.GITIGNORE to IgnoreFile("/repo/.gitignore", gitignoreLines, gitignoreProblem),
            IgnorePlace.EXCLUDE to IgnoreFile(excludePath, listOf("# comment")),
            IgnorePlace.GLOBAL to IgnoreFile(globalPath, globalLines, globalProblem),
        ),
    )

    private inner class DialogScene(
        request: IgnoreRequest,
        context: IgnoreContext?,
        error: GenericError? = null,
    ) : AutoCloseable {
        val calls = mutableListOf<String>()

        var state by mutableStateOf(
            initialIgnoreState(request).copy(context = context, place = context?.remembered, error = error)
        )

        private val scene = ImageComposeScene(width = 800, height = 1000, density = Density(1f)) {
            AppTheme {
                IgnoreDialogContent(
                    request = request,
                    state = state,
                    onChoice = { state = state.copy(choice = it) },
                    onCustomText = { state = state.copy(customText = it) },
                    onPlace = { state = state.copy(place = it) },
                    onAdd = { calls += "add"; state.canAdd },
                    onDismiss = { calls += "dismiss" },
                )
            }
        }

        init {
            // The second frame runs the dialog's focus request
            scene.render()
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** Whether the button [text] takes clicks: `PrimaryButton` isn't clickable at all while it's disabled. */
        fun isEnabled(text: String): Boolean = buttons(text).isNotEmpty()

        /** Clicks the clickable element whose text is [text]. */
        fun click(text: String) {
            checkNotNull(buttons(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
            scene.render()
        }

        /** What the pattern field holds. */
        fun fieldText(): String = checkNotNull(editable().config.getOrNull(SemanticsProperties.EditableText)).text

        /** Replaces what the pattern field holds. */
        fun type(text: String) {
            checkNotNull(editable().config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            scene.render()
            scene.render()
        }

        private fun editable(): SemanticsNode =
            nodes().single { it.config.getOrNull(SemanticsProperties.EditableText) != null }

        private fun buttons(text: String): List<SemanticsNode> = nodes().filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        /** Every node of the merged tree, where a button's text is part of the button. */
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
