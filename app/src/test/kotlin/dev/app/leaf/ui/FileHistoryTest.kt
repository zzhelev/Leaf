// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetCommitDiffEntriesGitAction
import dev.app.leaf.domain.errors.RestoreFileError
import dev.app.leaf.domain.interfaces.IGetFileCommitsAction
import dev.app.leaf.domain.interfaces.IGetFileDiffBetweenCommitsGitAction
import dev.app.leaf.domain.interfaces.IGetWorktreePathGitAction
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffTextViewType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.usecases.GetCommitDiffEntriesUseCase
import dev.app.leaf.domain.usecases.GetDiffUseCase
import dev.app.leaf.domain.usecases.GetFileCommitsUseCase
import dev.app.leaf.domain.usecases.GetFileDiffBetweenCommitsUseCase
import dev.app.leaf.domain.usecases.GetFileRestorePlanUseCase
import dev.app.leaf.domain.usecases.GetWorktreeUseCase
import dev.app.leaf.domain.usecases.RestoreFileVersionUseCase
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.FileVersions
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
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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

    /** The commits that "Restore this version of the file" was chosen for. */
    private val restoreRequests = mutableListOf<Commit>()

    /** Whether the repository has a working tree, which the menu's restore item needs. */
    private var hasWorkTree = true

    private var offerRestore = true

    /** How many times the view model asked for the working tree. */
    private val workTreeAsked = AtomicInteger()

    private val clipboard = RecordingClipboard()

    private var scene: ImageComposeScene? = null
    private var frameTime = 0L

    /** The tab of [renderFileHistory]'s view model. */
    private val tabScope = TabCoroutineScope()

    @AfterEach
    fun closeScene() {
        scene?.close()
        scene = null
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
        assertTrue(stripTexts().contains("0303030"), stripTexts().toString())
        assertTrue(stripTexts().any { it.startsWith("· Zhelyazko Zhelev · ") }, stripTexts().toString())
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

    @Test
    fun `a right-click on a row opens the menu of that row, whichever commit is selected`() {
        render()

        rightClick(listRow("Commit 05").center)

        assertEquals(
            listOf("Restore this version of the file", "Copy commit hash", "Copy commit message"),
            menuItems(),
        )
        assertEquals(commits[0], selected, "the right-click doesn't select the row")

        click(menuItem("Restore this version of the file").center)

        assertEquals(listOf(commits[4]), restoreRequests)
    }

    @Test
    fun `the menu copies the whole hash and the whole message of its row`() {
        val body = commit(2).copy(message = "Fix the parser\n\nIt took the last line twice.\n")
        val withBody = commits.map { if (it.hash == body.hash) body else it }
        render(withBody)

        rightClick(listRow("Fix the parser").center)
        click(menuItem("Copy commit hash").center)
        assertEventually { clipboard.text == body.hash }

        rightClick(listRow("Fix the parser").center)
        click(menuItem("Copy commit message").center)
        assertEventually { clipboard.text == "Fix the parser\n\nIt took the last line twice." }
    }

    @Test
    fun `a repository without a working tree has no restore item, only the copies`() {
        offerRestore = false
        render()

        rightClick(listRow("Commit 02").center)

        assertEquals(listOf("Copy commit hash", "Copy commit message"), menuItems())
    }

    @Test
    fun `the file history offers the restore when the repository has a working tree`() {
        val viewModel = renderFileHistory()

        rightClick(listRow("Commit 02").center)

        assertEventually { "Restore this version of the file" in menuItems() }
        viewModel.onClear()
    }

    @Test
    fun `the file history offers no restore in a repository without a working tree`() {
        hasWorkTree = false
        val viewModel = renderFileHistory()
        assertEventually { workTreeAsked.get() > 0 }
        Thread.sleep(200)
        settle()

        rightClick(listRow("Commit 02").center)

        assertEquals(listOf("Copy commit hash", "Copy commit message"), menuItems())
        viewModel.onClear()
    }

    @Test
    fun `a click on the hash in the strip copies it, and says so`() {
        selected = commits[2]
        render()

        click(stripNode("0303030").boundsInWindow.center)

        assertEventually { clipboard.text == commits[2].hash }
        assertEventually { "Copied" in stripTexts() }
    }

    @Test
    fun `a commit's message for a tooltip has no line past 72 columns, and is cut after 20 lines`() {
        val long = "word ".repeat(40).trim()

        assertTrue(long.forTooltip().lines().all { it.length <= 72 }, long.forTooltip())
        assertEquals("one\ntwo", "one\r\ntwo\n".forTooltip())
        assertEquals((1..20).joinToString("\n") { "line $it" } + "\n…", (1..25).joinToString("\n") { "line $it" }.forTooltip())
    }

    // Two versions, compared (docs/fork/compare.md, 7.3)

    @Test
    fun `a row's Compare with selected compares two versions, with badges on their rows and a strip that names them`() {
        val viewModel = renderFileHistory()

        // Not on the selected row
        rightClick(listRow("Commit 01").center)
        assertFalse(nodes().any { node -> node.texts().any { it.startsWith("Compare with selected") } })
        press(Key.Escape)

        rightClick(listRow("Commit 05").center)
        assertTrue("⌘-click" in nodes().flatMap { it.texts() } || "Ctrl-click" in nodes().flatMap { it.texts() })
        click(menuItem("Compare with selected (0101010)").center)

        assertEventually { viewModel.versions.value != null }
        assertEquals(commits[4], viewModel.versions.value!!.a)
        assertEquals(commits[0], viewModel.versions.value!!.b)

        // Both rows show as selected, with their letters
        assertTrue("A" in listRowTexts("Commit 05"), listRowTexts("Commit 05").toString())
        assertTrue("B" in listRowTexts("Commit 01"), listRowTexts("Commit 01").toString())
        assertFalse("A" in listRowTexts("Commit 03") || "B" in listRowTexts("Commit 03"))
        assertEquals(rowColor("Commit 05"), rowColor("Commit 01"))
        assertNotEquals(rowColor("Commit 03"), rowColor("Commit 01"))

        assertEventually { "The file is the same in both versions." in stripTexts() }
        assertTrue(listOf("A", "0505050", "Commit 05", "→", "B", "0101010", "Commit 01", "· 4 commits apart").all { it in stripTexts() }, stripTexts().toString())
        // The one commit's strip is gone
        assertFalse("1 of 40" in stripTexts())

        // No Compare with selected while two are compared
        rightClick(listRow("Commit 04").center)
        assertFalse(nodes().any { node -> node.texts().any { it.startsWith("Compare with selected") } })
        press(Key.Escape)

        viewModel.onClear()
    }

    @Test
    fun `swap turns the two around, and close and Esc go back to the commit selected before`() {
        val viewModel = renderFileHistory()
        rightClick(listRow("Commit 05").center)
        click(menuItem("Compare with selected (0101010)").center)
        assertEventually { viewModel.versions.value != null }

        click(button("Swap A and B").center)
        assertEventually { viewModel.versions.value?.a == commits[0] }
        assertTrue("A" in listRowTexts("Commit 01"))

        click(button("Back to one commit (Esc)").center)
        assertEventually { viewModel.versions.value == null }
        assertEquals(commits[0], viewModel.selectedCommit.value)
        assertTrue("1 of 40" in stripTexts(), stripTexts().toString())

        rightClick(listRow("Commit 05").center)
        click(menuItem("Compare with selected (0101010)").center)
        assertEventually { viewModel.versions.value != null }

        press(Key.Escape)
        assertEventually { viewModel.versions.value == null }
        assertEquals(commits[0], viewModel.selectedCommit.value)

        viewModel.onClear()
    }

    @Test
    fun `renders two versions in both themes, to look at`() {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return
        folder.mkdirs()
        val versions = FileVersions(a = commits[4], b = commits[0], before = commits[0])

        for (theme in listOf(Theme.Dark, Theme.Light)) {
            scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
                AppTheme(theme) {
                    HistoryContentLoaded(
                        historyState = HistoryState.Loaded("src/App.kt", commits),
                        viewDiffResult = ViewDiffResult.DiffNotFound(null),
                        scrollState = LazyListState(),
                        selectedCommit = commits[0],
                        onCommitSelected = {},
                        onSelectAdjacentCommit = {},
                        versionsActions = FileVersionsActions(versions, onPick = {}, onSwap = {}, onEnd = {}),
                    )
                }
            }
            settle()
            File(folder, "file-history-versions-${theme.name.lowercase()}.png")
                .writeBytes(scene!!.render(frameTime).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene!!.close()
            scene = null
        }
    }

    private fun listRowTexts(message: String): List<String> = mergedNodes()
        .first { it.boundsInWindow.left < LIST_WIDTH && message in it.texts() }
        .texts()

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
                every { diffWhitespace } returns MutableStateFlow(null)
                every { diffShowWhitespace } returns MutableStateFlow(null)
            }
        )
        val getWorktreePath = mockk<IGetWorktreePathGitAction> {
            coEvery { this@mockk(any()) } coAnswers {
                workTreeAsked.incrementAndGet()
                if (hasWorkTree) Either.Ok("/repo") else Either.Err(RestoreFileError.NoWorkTree)
            }
        }
        // Every two versions have the same file, so the view says so where the diff would be
        val getFileDiffBetween = mockk<IGetFileDiffBetweenCommitsGitAction> {
            coEvery { this@mockk(any(), any(), any(), any()) } returns Either.Ok(null)
        }
        val viewModel = HistoryViewModel(
            GetCommitDiffEntriesUseCase(getCommitDiffEntries, mockk(), useCaseExecutor),
            mockk(),
            settings,
            GetFileCommitsUseCase(getFileCommits, useCaseExecutor),
            GetDiffUseCase(mockk(), mockk(), repositoryDataRepository),
            GetWorktreeUseCase(getWorktreePath, useCaseExecutor),
            GetFileRestorePlanUseCase(mockk(), useCaseExecutor),
            RestoreFileVersionUseCase(useCaseExecutor, mockk()),
            InMemoryRepositoryStateRepository(),
            GetFileDiffBetweenCommitsUseCase(getFileDiffBetween, useCaseExecutor),
        )

        viewModel.fileHistory("src/App.kt")
        runBlocking { withTimeout(5.seconds) { viewModel.historyState.first { it is HistoryState.Loaded } } }

        scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            AppTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    FileHistory(
                        historyViewModel = viewModel,
                        onClose = {},
                        onRestoreVersion = { restoreRequests += it },
                    )
                }
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

    private fun render(shown: List<Commit> = commits) {
        scene = ImageComposeScene(width = WIDTH, height = HEIGHT) {
            AppTheme {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                HistoryContentLoaded(
                    historyState = HistoryState.Loaded("src/App.kt", shown),
                    viewDiffResult = null,
                    scrollState = LazyListState(),
                    selectedCommit = selected,
                    onCommitSelected = { selected = it },
                    onSelectAdjacentCommit = { offset ->
                        steps += offset
                        adjacentCommit(commits, selected, offset)?.let { selected = it }
                    },
                    onRestoreVersion = if (offerRestore) { commit -> restoreRequests += commit } else null,
                )
                }
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

    private fun rightClick(position: Offset) {
        val scene = scene!!

        // A menu ignores a right-click that comes within 20 ms of the last one, in any test
        Thread.sleep(50)
        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isSecondaryPressed = true),
            button = PointerButton.Secondary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, buttons = PointerButtons(), button = PointerButton.Secondary)
        settle()
    }

    /** The labels of the menu that a right-click opened, which is a popup of its own, over the list. */
    private fun menuItems(): List<String> = nodes()
        .flatMap { it.texts() }
        .filter { it in setOf("Restore this version of the file", "Copy commit hash", "Copy commit message") }

    private fun menuItem(label: String): Rect = nodes()
        .first { label in it.texts() }
        .boundsInWindow

    /** Waits for what a coroutine of the scene does, which runs on its own thread, rendering meanwhile. */
    private fun assertEventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L

        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(20)
            settle()
        }

        assertTrue(condition(), "it didn't happen in 5 s")
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

/** A clipboard that keeps the text put on it, as the system's isn't there in a test. */
@OptIn(ExperimentalComposeUiApi::class)
private class RecordingClipboard : Clipboard {
    @Volatile
    var text: String? = null

    override val nativeClipboard: NativeClipboard get() = error("Not used")

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
