// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetCommitDiffEntriesGitAction
import dev.app.leaf.domain.interfaces.IGetFileCommitsAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.GetDiffUseCase
import dev.app.leaf.domain.usecases.GetFileCommitsUseCase
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.HistoryState
import dev.app.leaf.viewmodels.HistoryViewModel
import dev.app.leaf.viewmodels.adjacentCommit
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.inject.Provider
import kotlin.time.Duration.Companion.seconds

private const val WIDTH = 900
private const val HEIGHT = 400

/** The list's width in [HistoryContentLoaded]; the strip and the diff are right of it. */
private const val LIST_WIDTH = 300

/** File history's list of commits and the strip above the diff, rendered offscreen and clicked like a user would. */
class FileHistoryTest {
    private val commits = (1..40).map { commit(it) }

    private var selected by mutableStateOf<Commit?>(commits[0])
    private val steps = mutableListOf<Int>()

    private var scene: ImageComposeScene? = null
    private var frameTime = 0L

    /** The tab of [renderFileHistory]'s view model. */
    private val tabScope = TabCoroutineScope()

    @AfterEach
    fun closeScene() {
        scene?.close()
        tabScope.cancel()
    }

    @Test
    fun `highlights the selected commit's row`() {
        render()
        val selectedColor = rowColor("Commit 01")
        val otherColor = rowColor("Commit 02")
        assertNotEquals(selectedColor, otherColor)

        click(listRow("Commit 02").center)
        moveAway()

        assertEquals(commits[1], selected)
        assertEquals(selectedColor, rowColor("Commit 02"))
        assertEquals(otherColor, rowColor("Commit 01"))
    }

    @Test
    fun `names the selected commit above the diff, and where it is in the list`() {
        selected = commits[2]
        render()

        assertEquals(listOf("Commit 03"), stripTexts().filter { it.startsWith("Commit") })
        assertTrue(stripTexts().any { it.startsWith("0303030 · Zhelyazko Zhelev · ") }, stripTexts().toString())
        assertTrue(stripTexts().contains("3 of 40"), stripTexts().toString())
    }

    @Test
    fun `its buttons select the next newer and older commit, and stop at the newest`() {
        render()

        click(button("Newer commit").center)
        click(button("Older commit").center)
        click(button("Newer commit").center)

        assertEquals(listOf(1, -1), steps)
        assertEquals(commits[0], selected)
    }

    @Test
    fun `keeps the selected row in view while stepping down the list and back up`() {
        render()

        repeat(30) {
            click(button("Older commit").center)
            assertWholeRowInList(selected!!.message)
        }

        repeat(30) {
            click(button("Newer commit").center)
            assertWholeRowInList(selected!!.message)
        }

        assertEquals(commits[0], selected)
    }

    @Test
    fun `its position brings the selected row back into view`() {
        render()
        repeat(30) { click(button("Older commit").center) }

        val list = Offset(LIST_WIDTH / 2f, HEIGHT / 2f)
        scene!!.sendPointerEvent(PointerEventType.Move, list)
        scene!!.sendPointerEvent(PointerEventType.Scroll, list, scrollDelta = Offset(0f, -1_000f))
        settle()
        assertNull(listRowOrNull("Commit 31"), "the list was scrolled to its top")

        click(stripNode("31 of 40").boundsInWindow.center)

        assertWholeRowInList("Commit 31")
    }

    @Test
    fun `the arrow keys step through the commits, also after a click on a row or a button`() {
        val viewModel = renderFileHistory()
        assertEquals(commits[0], viewModel.selectedCommit.value, "it opened on the newest")

        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        assertEquals(commits[1], viewModel.selectedCommit.value)

        click(listRow("Commit 05").center)
        press(Key.DirectionDown)
        assertEquals(commits[5], viewModel.selectedCommit.value)

        click(button("Older commit").center)
        press(Key.DirectionDown)
        assertEquals(commits[7], viewModel.selectedCommit.value)
        assertWholeRowInList("Commit 08")
    }

    /** All of file history, with its view model around git actions that list [commits] and find no diffs. */
    private fun renderFileHistory(): HistoryViewModel {
        val repositoryDataRepository = mockk<RepositoryDataRepository> { every { repositoryPath } returns "/repo/.git" }
        val useCaseExecutor = UseCaseExecutor(
            repositoryDataRepository,
            InMemoryRepositoryStateRepository(),
            Provider { error("Nothing is refreshed") },
            tabScope,
        )
        val getFileCommits = mockk<IGetFileCommitsAction> { coEvery { this@mockk(any(), any()) } returns Either.Ok(commits) }
        val getCommitDiffEntries = mockk<IGetCommitDiffEntriesGitAction> {
            coEvery { this@mockk(any(), any()) } returns Either.Ok(emptyList())
        }
        val settings = AppSettingsService(
            mockk<AppSettingsRepository> {
                every { diffTextViewType } returns MutableStateFlow(DiffTextViewType.Unified)
                every { diffDisplayFullFile } returns MutableStateFlow(false)
            }
        )
        val viewModel = HistoryViewModel(
            GetCommitDiffEntriesUseCase(getCommitDiffEntries, mockk(), useCaseExecutor),
            mockk(),
            settings,
            GetFileCommitsUseCase(getFileCommits, useCaseExecutor),
            GetDiffUseCase(mockk(), mockk(), repositoryDataRepository),
        )

        viewModel.fileHistory("src/App.kt")
        runBlocking { withTimeout(5.seconds) { viewModel.historyState.first { it is HistoryState.Loaded } } }

        scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            AppTheme {
                FileHistory(historyViewModel = viewModel, onClose = {})
            }
        }
        settle()

        return viewModel
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun press(key: Key) {
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene!!.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
        settle()
    }

    private fun render() {
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            AppTheme {
                HistoryContentLoaded(
                    historyState = HistoryState.Loaded("src/App.kt", commits),
                    viewDiffResult = null,
                    scrollState = LazyListState(),
                    selectedCommit = selected,
                    onCommitSelected = { selected = it },
                    onSelectAdjacentCommit = { offset ->
                        steps += offset
                        adjacentCommit(commits, selected, offset)?.let { selected = it }
                    },
                )
            }
        }
        settle()
    }

    /** Renders frames a second apart in total, so that scrolling, which may animate, ends. */
    private fun settle() {
        repeat(60) {
            scene!!.render(frameTime)
            frameTime += 16_000_000L
        }
    }

    private fun click(position: Offset) {
        val scene = scene!!

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        settle()
    }

    /** Moves the pointer off the list, so that no row shows the hover. */
    private fun moveAway() {
        scene!!.sendPointerEvent(PointerEventType.Move, Offset(WIDTH - 10f, HEIGHT - 10f))
        settle()
    }

    /** The color at the left edge of a row, left of its avatar, where nothing but the row's background is drawn. */
    private fun rowColor(message: String): Int {
        val image = scene!!.render(frameTime)
        val bitmap = Bitmap.makeFromImage(image)

        return bitmap.getColor(4, listRow(message).center.y.toInt())
    }

    private fun assertWholeRowInList(message: String) {
        val row = listRow(message)

        assertTrue(row.top >= 0f && row.bottom <= HEIGHT, "$message at $row, in a list $HEIGHT px high")
    }

    /** A row in the list, whose node holds its texts, as clicking merges them. */
    private fun listRow(message: String): Rect =
        listRowOrNull(message) ?: error("No row for $message in ${mergedNodes().map { it.texts() }}")

    private fun listRowOrNull(message: String): Rect? = mergedNodes()
        .firstOrNull { it.boundsInWindow.left < LIST_WIDTH && message in it.texts() }
        ?.boundsInWindow

    private fun stripNode(text: String): SemanticsNode = nodes()
        .first { it.boundsInWindow.left >= LIST_WIDTH && text in it.texts() }

    private fun stripTexts(): List<String> = nodes()
        .filter { it.boundsInWindow.left >= LIST_WIDTH }
        .flatMap { it.texts() }

    private fun button(description: String): Rect = nodes()
        .first { description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        .boundsInWindow

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun mergedNodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun commit(number: Int): Commit {
        val digits = number.toString().padStart(2, '0')

        return Commit(
            hash = digits.repeat(20),
            message = "Commit $digits",
            committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
            author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
            date = 1_760_000_000,
            parentsHashes = emptyList(),
        )
    }
}
