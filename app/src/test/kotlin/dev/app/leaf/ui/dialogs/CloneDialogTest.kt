// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.interfaces.ICloneRepositoryGitAction
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.CloneViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val BRANCH_TITLE = "Branch or tag (optional)"
private const val ONLY_THIS_BRANCH = "Only this branch"
private const val NOTE = "Fetches only this branch. A later fetch brings only this branch too."

/** The Clone dialog's Branch field and "Only this branch" (fork-only), run against a view model with a recording clone. */
class CloneDialogTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `asks for a branch or tag, and only this branch waits for one`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene().use { dialog ->
            val texts = dialog.texts()

            assertTrue(BRANCH_TITLE in texts, "$texts")
            assertTrue(ONLY_THIS_BRANCH in texts, "$texts")
            assertFalse(dialog.canClick(ONLY_THIS_BRANCH))
            assertFalse(NOTE in texts, "$texts")

            dialog.type(BRANCH, "feature/x")

            assertTrue(dialog.canClick(ONLY_THIS_BRANCH))
            assertEquals(ToggleableState.Off, dialog.checkState(ONLY_THIS_BRANCH))
            assertFalse(NOTE in dialog.texts())
        }
    }

    @Test
    fun `a branch alone is cloned with every branch`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene().use { dialog ->
            dialog.fill()
            dialog.type(BRANCH, " feature/x ")

            dialog.click("Clone")

            assertEquals(Call(File(tempDir, "r"), "feature/x", singleBranch = false), dialog.nextCall())
        }
    }

    @Test
    fun `only this branch is sent, with the note that says what it does`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene().use { dialog ->
            dialog.fill()
            dialog.type(BRANCH, "feature/x")

            dialog.click(ONLY_THIS_BRANCH)

            assertEquals(ToggleableState.On, dialog.checkState(ONLY_THIS_BRANCH))
            assertTrue(NOTE in dialog.texts(), "${dialog.texts()}")

            dialog.click("Clone")

            assertEquals(Call(File(tempDir, "r"), "feature/x", singleBranch = true), dialog.nextCall())
        }
    }

    @Test
    fun `no branch clones the default branch, whatever the box was`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene().use { dialog ->
            dialog.fill()
            dialog.type(BRANCH, "feature/x")
            dialog.click(ONLY_THIS_BRANCH)

            // Clearing the field takes the box away: it can't be checked without a branch
            dialog.type(BRANCH, "  ")

            assertFalse(dialog.canClick(ONLY_THIS_BRANCH))
            assertFalse(NOTE in dialog.texts(), "${dialog.texts()}")

            dialog.click("Clone")

            assertEquals(Call(File(tempDir, "r"), null, singleBranch = false), dialog.nextCall())
        }
    }

    @Test
    fun `a branch that the remote lacks is explained in the dialog`(): Unit = runBlocking(Dispatchers.Swing) {
        val missing = RemoteOperationError.BranchNotFound("nope", "fatal: Remote branch nope not found in upstream origin")

        DialogScene(CloneState.Fail(missing)).use { dialog ->
            dialog.fill()
            dialog.type(BRANCH, "nope")

            dialog.click("Clone")
            dialog.nextCall()
            dialog.waitForText { texts -> texts.any { it.contains("The repository has no branch or tag named \"nope\".") } }

            val error = dialog.texts().single { it.contains("no branch or tag named") }
            assertTrue(error.contains("fatal: Remote branch nope not found in upstream origin"), error)
            // The dialog is back on its fields, with what was typed
            assertTrue(dialog.canClick("Clone"))
            assertEquals("nope", dialog.fieldText(BRANCH))
        }
    }

    private data class Call(val directory: File, val branch: String?, val singleBranch: Boolean)

    private inner class DialogScene(result: CloneState = CloneState.Cloning("Starting...", 0, 0)) : AutoCloseable {
        private val calls = LinkedBlockingQueue<Call>()

        private val action = object : ICloneRepositoryGitAction {
            override fun invoke(
                directory: File,
                url: String,
                cloneSubmodules: Boolean,
                branch: String?,
                singleBranch: Boolean,
            ): Flow<CloneState> {
                calls.put(Call(directory, branch, singleBranch))

                return flowOf(result)
            }
        }

        private val viewModel = CloneViewModel(
            cloneRepositoryGitAction = action,
            openFilePickerUseCase = OpenFilePickerUseCase(ShellManager()),
            appSettings = AppSettingsService(
                mockk<AppSettingsRepository> { every { cloneDefaultDirectory } returns flowOf(null) }
            ),
        )

        private val scene = ImageComposeScene(width = 800, height = 1000, density = Density(1f)) {
            AppTheme {
                CloneDialog(cloneViewModel = viewModel, onClose = {}, onOpenRepository = {})
            }
        }

        init {
            scene.render()
            scene.render()
        }

        fun nextCall(): Call = checkNotNull(calls.poll(5, TimeUnit.SECONDS)) { "No clone was started" }

        /** The URL, directory and folder that a clone needs. */
        fun fill() {
            type(URL, "https://example.com/r.git")
            type(DIRECTORY, tempDir.path)
            type(FOLDER, "r")
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** Whether [text] is a button or a clickable row. */
        fun canClick(text: String): Boolean = clickable(text).isNotEmpty()

        fun click(text: String) {
            checkNotNull(clickable(text).single().config[SemanticsActions.OnClick].action).invoke()
            scene.render()
            scene.render()
        }

        /** The state of the check box in the clickable row [text], a node of its own inside the row. */
        fun checkState(text: String): ToggleableState? {
            fun find(node: SemanticsNode): ToggleableState? =
                node.config.getOrNull(SemanticsProperties.ToggleableState) ?: node.children.firstNotNullOfOrNull(::find)

            return find(clickable(text).single())
        }

        fun fieldText(field: Int): String = checkNotNull(editables()[field].config.getOrNull(SemanticsProperties.EditableText)).text

        fun type(field: Int, text: String) {
            checkNotNull(editables()[field].config[SemanticsActions.SetText].action).invoke(AnnotatedString(text))
            scene.render()
            scene.render()
        }

        /** Renders until [condition] holds, as the view model reports from another thread. */
        suspend fun waitForText(condition: (List<String>) -> Boolean) {
            repeat(100) {
                scene.render()
                if (condition(texts())) return
                delay(50)
            }
            fail<Unit>("The dialog didn't show it: ${texts()}")
        }

        private fun editables(): List<SemanticsNode> =
            nodes().filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }

        private fun clickable(text: String): List<SemanticsNode> = nodes().filter { node ->
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

        override fun close() {
            scene.close()
            viewModel.onClear()
        }
    }

    private companion object {
        const val URL = 0
        const val DIRECTORY = 1
        const val FOLDER = 2
        const val BRANCH = 3
    }
}
