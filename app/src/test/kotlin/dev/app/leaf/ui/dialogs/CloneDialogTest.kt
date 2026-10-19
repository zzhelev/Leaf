// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RemoteOperationError
import dev.app.leaf.domain.interfaces.ICloneRepositoryGitAction
import dev.app.leaf.domain.interfaces.IListRemoteRefsGitAction
import dev.app.leaf.domain.models.CloneState
import dev.app.leaf.domain.models.RemoteRefs
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.system.OpenFilePickerUseCase
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.CloneViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

private const val BRANCH_TITLE = "Branch or tag (optional)"
private const val ONLY_THIS_BRANCH = "Only this branch"
private const val NOTE = "Fetches only this branch. A later fetch brings only this branch too."
private const val CHOOSE = "Choose…"
private const val ASKING = "Asking example.com for its branches and tags…"

private val REFS = RemoteRefs(
    defaultBranch = "main",
    branches = listOf("docs", "feature/clone-branch", "main", "release/1.2", "release/1.10"),
    tags = listOf("v0.9", "v1.0"),
)
private val REF_NAMES = (REFS.branches + REFS.tags).toSet()

/** Records each request for the list of "Choose…", and answers when the test says so. */
private class AskedRefs : IListRemoteRefsGitAction {
    val asked = AtomicInteger()
    val cancelled = AtomicInteger()
    private val requests = LinkedBlockingQueue<Pair<String, CompletableDeferred<Either<RemoteRefs, GitError>>>>()

    override suspend fun invoke(url: String): Either<RemoteRefs, GitError> {
        val answer = CompletableDeferred<Either<RemoteRefs, GitError>>()
        asked.incrementAndGet()
        requests.put(url to answer)

        try {
            return answer.await()
        } catch (e: CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
    }

    /** The URL of the next request, and what answers it. */
    fun nextRequest() = checkNotNull(requests.poll(5, TimeUnit.SECONDS)) { "Nothing was asked" }
}

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

    @Test
    fun `Choose is a button, and nothing asks the remote before it is clicked`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            assertTrue(dialog.canClick(CHOOSE), "${dialog.texts()}")

            // Typing, a changed URL and the dialog opening are not a reason to ask
            dialog.fill()
            dialog.type(BRANCH, "feat")
            dialog.type(URL, "https://example.com/other.git")
            dialog.type(BRANCH, "feature/x")
            dialog.type(BRANCH, "")

            assertEquals(0, list.asked.get())
            assertTrue(dialog.texts().none { it.startsWith("Asking") }, "${dialog.texts()}")
        }
    }

    @Test
    fun `a click asks the URL once and says who it asks, and Cancel stops it`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            dialog.fill()
            dialog.choose()

            assertEquals("https://example.com/r.git", list.nextRequest().first)
            dialog.waitForText { ASKING in it }

            // The dialog has a Cancel too: the one in the list is the pointer's, as the list is a layer of its own
            dialog.clickWithPointer("Cancel")

            assertTrue(dialog.texts().none { it == ASKING }, "${dialog.texts()}")
            waitUntil { list.cancelled.get() == 1 }
            assertEquals(0, dialog.closes)
            assertEquals(1, list.asked.get())
        }
    }

    @Test
    fun `without a URL it says so and asks nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            dialog.click(CHOOSE)

            assertTrue("Invalid empty URL" in dialog.texts(), "${dialog.texts()}")
            assertTrue(dialog.texts().none { it.startsWith("Asking") })
            assertEquals(0, list.asked.get())
        }
    }

    @Test
    fun `the list has the default branch first, then the branches and the tags, and counts them`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val list = AskedRefs()

            DialogScene(list = list).use { dialog ->
                dialog.fill()
                dialog.choose()
                dialog.answer(list, Either.Ok(REFS))

                assertEquals(
                    listOf("main", "docs", "feature/clone-branch", "release/1.2", "release/1.10", "v0.9", "v1.0"),
                    dialog.listed(),
                )
                assertTrue("BRANCHES" in dialog.texts() && "TAGS" in dialog.texts(), "${dialog.texts()}")
                assertTrue("default" in dialog.texts(), "${dialog.texts()}")
                assertTrue("7 of 7 match" in dialog.texts(), "${dialog.texts()}")
            }
        }

    @Test
    fun `typing in the field filters the list without asking again`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            dialog.fill()
            dialog.choose()
            dialog.answer(list, Either.Ok(REFS))

            dialog.type(BRANCH, "REL")

            assertEquals(listOf("release/1.2", "release/1.10"), dialog.listed())
            assertTrue("2 of 7 match" in dialog.texts(), "${dialog.texts()}")

            dialog.type(BRANCH, "zzz")

            assertEquals(emptyList<String>(), dialog.listed())
            assertTrue("No branch or tag matches." in dialog.texts(), "${dialog.texts()}")

            dialog.type(BRANCH, "")

            assertEquals(7, dialog.listed().size)
            assertEquals(1, list.asked.get())
        }
    }

    @Test
    fun `a click on a row puts it in the field, closes the list, and the clone takes it`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val list = AskedRefs()

            DialogScene(list = list).use { dialog ->
                dialog.fill()
                dialog.choose()
                dialog.answer(list, Either.Ok(REFS))

                // A real press on the row: the list is a layer of its own, and a press elsewhere closes it
                dialog.clickWithPointer("feature/clone-branch")

                assertEquals("feature/clone-branch", dialog.fieldText(BRANCH))
                assertEquals(emptyList<String>(), dialog.listed())
                assertTrue(dialog.canClick(ONLY_THIS_BRANCH))

                dialog.click("Clone")

                assertEquals(Call(File(tempDir, "r"), "feature/clone-branch", singleBranch = false), dialog.nextCall())
            }
        }

    @Test
    fun `the arrows move through the rows and Enter picks the one moved to`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            dialog.fill()
            dialog.type(BRANCH, "e")
            dialog.choose()
            dialog.answer(list, Either.Ok(REFS))

            // "e" is in these three and not in "docs"
            assertEquals(listOf("feature/clone-branch", "release/1.2", "release/1.10"), dialog.listed())

            // What was typed may be the whole name, so Enter alone picks nothing
            dialog.press(Key.Enter)

            assertEquals("e", dialog.fieldText(BRANCH))
            assertEquals(3, dialog.listed().size)

            dialog.press(Key.DirectionDown)
            dialog.press(Key.DirectionDown)

            assertTrue(dialog.isSelected("release/1.2"), "${dialog.texts()}")

            dialog.press(Key.DirectionUp)

            assertTrue(dialog.isSelected("feature/clone-branch"), "${dialog.texts()}")

            // The filter changed, so the highlight starts over, and Enter picks nothing again
            dialog.type(BRANCH, "rel")

            assertEquals(listOf("release/1.2", "release/1.10"), dialog.listed())
            assertFalse(dialog.isSelected("release/1.2") || dialog.isSelected("release/1.10"), "${dialog.texts()}")

            dialog.press(Key.Enter)

            assertEquals("rel", dialog.fieldText(BRANCH))
            assertEquals(2, dialog.listed().size)

            dialog.press(Key.DirectionDown)
            dialog.press(Key.DirectionDown)
            dialog.press(Key.Enter)

            assertEquals("release/1.10", dialog.fieldText(BRANCH))
            assertEquals(emptyList<String>(), dialog.listed())
        }
    }

    @Test
    fun `Esc closes the list first and the dialog next`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            dialog.fill()
            dialog.choose()
            dialog.answer(list, Either.Ok(REFS))

            dialog.press(Key.Escape)

            assertEquals(emptyList<String>(), dialog.listed())
            assertEquals(0, dialog.closes)

            dialog.press(Key.Escape)

            assertEquals(1, dialog.closes)
        }
    }

    @Test
    fun `Esc while the list waits stops the request`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            dialog.fill()
            dialog.choose()
            dialog.waitForText { ASKING in it }

            dialog.press(Key.Escape)

            assertEquals(0, dialog.closes)
            assertTrue(dialog.texts().none { it == ASKING }, "${dialog.texts()}")
            waitUntil { list.cancelled.get() == 1 }
        }
    }

    @Test
    fun `a press elsewhere in the dialog closes the list, a press on the field does not`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val list = AskedRefs()

            DialogScene(list = list).use { dialog ->
                dialog.fill()
                dialog.choose()
                dialog.answer(list, Either.Ok(REFS))

                dialog.pressAt(dialog.centerOf(BRANCH_TITLE).copy(x = 300f).let { it.copy(y = it.y + 19f) })

                assertEquals(7, dialog.listed().size)

                dialog.pressAt(dialog.centerOf("Clone a new repository"))

                assertEquals(emptyList<String>(), dialog.listed())
                assertEquals(0, dialog.closes)
            }
        }

    @Test
    fun `a failure is in the list with Try again, which asks once more`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()

        DialogScene(list = list).use { dialog ->
            dialog.fill()
            dialog.choose()
            dialog.answer(list, Either.Err(RemoteOperationError.ConnectionFailed("fatal: Could not resolve host: example.com")))

            assertTrue(dialog.texts().any { it.contains("Could not resolve host: example.com") }, "${dialog.texts()}")
            assertTrue(dialog.canClick("Try again"))

            dialog.clickWithPointer("Try again")

            assertEquals(2, list.asked.get())
            dialog.waitForText { ASKING in it }

            dialog.answer(list, Either.Ok(REFS))

            assertEquals(7, dialog.listed().size)
        }
    }

    @Test
    fun `closing the dialog while the list waits stops the request`(): Unit = runBlocking(Dispatchers.Swing) {
        val list = AskedRefs()
        val dialog = DialogScene(list = list)

        try {
            dialog.fill()
            dialog.choose()
            dialog.waitForText { ASKING in it }

            // Only the dialog goes: the request stops because the dialog closed the list, not because the view model went
            dialog.closeScene()

            waitUntil { list.cancelled.get() == 1 }
        } finally {
            dialog.close()
        }
    }

    /** Waits for [condition], as the view model works from another thread. */
    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(50)
        }
        fail<Unit>("It didn't happen in 5 seconds")
    }

    private data class Call(val directory: File, val branch: String?, val singleBranch: Boolean)

    private inner class DialogScene(
        result: CloneState = CloneState.Cloning("Starting...", 0, 0),
        private val list: IListRemoteRefsGitAction = mockk(),
    ) : AutoCloseable {
        var closes = 0
        private var closed = false
        private var sceneClosed = false
        private var eventTime = 0L

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
            listRemoteRefsGitAction = list,
            openFilePickerUseCase = OpenFilePickerUseCase(ShellManager()),
            appSettings = AppSettingsService(
                mockk<AppSettingsRepository> { every { cloneDefaultDirectory } returns flowOf(null) }
            ),
        )

        private val scene = ImageComposeScene(width = 800, height = 1000, density = Density(1f)) {
            AppTheme {
                CloneDialog(cloneViewModel = viewModel, onClose = { closes++ }, onOpenRepository = {})
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

        /** The clickable that has [text], the one nearest the top of the dialog when several do (the popup's Cancel). */
        private fun topmostClickable(text: String): SemanticsNode =
            clickable(text).minBy { it.boundsInWindow.top }

        /** Clicks the button or row [text] of the list, which is a layer of its own, as a user does: with the pointer. */
        fun clickWithPointer(text: String) {
            val at = topmostClickable(text).boundsInWindow.center

            pressAt(at)
        }

        fun pressAt(at: Offset) {
            eventTime += 10
            scene.sendPointerEvent(
                PointerEventType.Press, at, timeMillis = eventTime,
                buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
            )
            eventTime += 10
            scene.sendPointerEvent(
                PointerEventType.Release, at, timeMillis = eventTime,
                buttons = PointerButtons(), button = PointerButton.Primary,
            )
            scene.render()
            scene.render()
        }

        /** Where [text] is in the dialog, for a press. */
        fun centerOf(text: String): Offset =
            nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text } }
                .boundsInWindow.center

        @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
        fun press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
            scene.render()
            scene.render()
        }

        /** The branches and tags that the list shows, in the order it shows them. */
        fun listed(): List<String> = texts().filter { it in REF_NAMES }

        fun isSelected(text: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsProperties.Selected) == true &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        /** Answers the request that "Choose…" made, and waits for the list to show it. */
        suspend fun answer(list: AskedRefs, result: Either<RemoteRefs, GitError>) {
            list.nextRequest().second.complete(result)
            waitForText { texts -> texts.none { it == ASKING } }
        }

        /** Clicks "Choose…" and waits for its request, which is answered by [answer]. */
        fun choose() = click(CHOOSE)

        /**
         * Takes the dialog off the screen and leaves the view model, which outlives it in the app (it is the tab's):
         * what the dialog started must stop by itself.
         */
        fun closeScene() {
            if (sceneClosed) return
            sceneClosed = true
            scene.close()
        }

        override fun close() {
            if (closed) return
            closed = true
            closeScene()
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
