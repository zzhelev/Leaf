// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.allDiscard
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.ui.dialogs.ConfirmableAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val MORE_ACTIONS = "More actions"
private const val DISCARD_ALL = "Discard all changes…"
private const val NOTHING = "Nothing to discard"

private val unstaged = listOf(
    StatusEntry("a.kt", StatusType.MODIFIED),
    StatusEntry("src/b.kt", StatusType.MODIFIED),
    StatusEntry("src/gone.kt", StatusType.REMOVED),
    StatusEntry("src/new.kt", StatusType.ADDED),
    StatusEntry("src/clash.kt", StatusType.CONFLICTING),
    StatusEntry("libs/core", StatusType.MODIFIED, isSubmodule = true),
)

/** The "⋯" menu of the Unstaged header and the "Discard all changes…" in it (fork-only), driven like a user would. */
class DiscardAllMenuTest {
    private val confirmations = mutableListOf<Pair<ConfirmableAction, () -> Unit>>()
    private val actions = mutableListOf<StatusAction>()
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `the Unstaged header has a menu button and the Staged header doesn't`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, StatusState(isLoading = false, unstaged = unstaged))
        assertTrue(MORE_ACTIONS in descriptions())
        close()

        render(EntryType.STAGED, StatusState(isLoading = false, staged = unstaged))
        assertFalse(MORE_ACTIONS in descriptions())
    }

    @Test
    fun `the menu offers Discard all changes and nothing happens until it is picked`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render(EntryType.UNSTAGED, StatusState(isLoading = false, unstaged = unstaged))

            assertFalse(DISCARD_ALL in texts(), "The menu starts closed")

            clickDescription(MORE_ACTIONS)

            assertTrue(DISCARD_ALL in texts(), texts().toString())
            assertEquals(emptyList<ConfirmableAction>(), confirmations.map { it.first })
            assertEquals(emptyList<StatusAction>(), actions)
        }

    @Test
    fun `picking it asks first, with the files that the list shows`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, StatusState(isLoading = false, unstaged = unstaged))

        clickDescription(MORE_ACTIONS)
        clickText(DISCARD_ALL)

        val action = assertInstanceOf(ConfirmableAction.DiscardAll::class.java, confirmations.single().first)

        assertEquals(unstaged.allDiscard(), action.plan)
        assertEquals(null, action.search)
        assertFalse(action.deleteNewFiles.isChecked)
        assertEquals(emptyList<StatusAction>(), actions, "Nothing runs before the dialog confirms")
        assertFalse(DISCARD_ALL in texts(), "The menu closed")
    }

    @Test
    fun `confirming discards what the plan lists and keeps the new files`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, StatusState(isLoading = false, unstaged = unstaged))

        clickDescription(MORE_ACTIONS)
        clickText(DISCARD_ALL)
        confirmations.single().second()

        assertEquals(listOf<StatusAction>(StatusAction.DiscardAll(unstaged.allDiscard(), deleteNewFiles = false)), actions)
    }

    @Test
    fun `confirming with the box ticked deletes the new files too`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, StatusState(isLoading = false, unstaged = unstaged))

        clickDescription(MORE_ACTIONS)
        clickText(DISCARD_ALL)

        (confirmations.single().first as ConfirmableAction.DiscardAll).deleteNewFiles.isChecked = true
        confirmations.single().second()

        assertEquals(listOf<StatusAction>(StatusAction.DiscardAll(unstaged.allDiscard(), deleteNewFiles = true)), actions)
    }

    @Test
    fun `a search narrows it to the files that match`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = StatusState(
            isLoading = false,
            unstaged = unstaged,
            showSearchUnstaged = true,
            searchFilterUnstaged = TextFieldValue("SRC/"),
        )

        render(EntryType.UNSTAGED, state)

        clickDescription(MORE_ACTIONS)
        clickText(DISCARD_ALL)

        val action = confirmations.single().first as ConfirmableAction.DiscardAll
        val shown = unstaged.filter { it.filePath.startsWith("src/") }

        assertEquals(shown.allDiscard(), action.plan)
        assertEquals(listOf("src/b.kt", "src/gone.kt"), action.plan.restored.map { it.filePath })
        assertEquals("SRC/", action.search, "The dialog says what the search matches")

        confirmations.single().second()

        assertEquals(listOf<StatusAction>(StatusAction.DiscardAll(shown.allDiscard(), deleteNewFiles = false)), actions)
    }

    @Test
    fun `text left in a closed search box narrows nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = StatusState(
            isLoading = false,
            unstaged = unstaged,
            showSearchUnstaged = false,
            searchFilterUnstaged = TextFieldValue("src/"),
        )

        render(EntryType.UNSTAGED, state)

        clickDescription(MORE_ACTIONS)
        clickText(DISCARD_ALL)

        val action = confirmations.single().first as ConfirmableAction.DiscardAll

        assertEquals(unstaged.allDiscard(), action.plan)
        assertEquals(null, action.search)
    }

    @Test
    fun `a search that matches only new files still has work, to delete them`(): Unit = runBlocking(Dispatchers.Swing) {
        val state = StatusState(
            isLoading = false,
            unstaged = unstaged,
            showSearchUnstaged = true,
            searchFilterUnstaged = TextFieldValue("new"),
        )

        render(EntryType.UNSTAGED, state)

        clickDescription(MORE_ACTIONS)

        assertTrue(DISCARD_ALL in texts(), texts().toString())
    }

    @Test
    fun `with nothing to discard the menu says so`(): Unit = runBlocking(Dispatchers.Swing) {
        val conflictsOnly = listOf(
            StatusEntry("src/clash.kt", StatusType.CONFLICTING),
            StatusEntry("libs/core", StatusType.MODIFIED, isSubmodule = true),
        )

        render(EntryType.UNSTAGED, StatusState(isLoading = false, unstaged = conflictsOnly))

        clickDescription(MORE_ACTIONS)

        assertTrue(NOTHING in texts(), texts().toString())
        assertFalse(DISCARD_ALL in texts())
    }

    @Test
    fun `an empty list says so too, and a search with no match`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, StatusState(isLoading = false))

        clickDescription(MORE_ACTIONS)
        assertTrue(NOTHING in texts(), texts().toString())
        close()

        val noMatch = StatusState(
            isLoading = false,
            unstaged = unstaged,
            showSearchUnstaged = true,
            searchFilterUnstaged = TextFieldValue("no such file"),
        )

        render(EntryType.UNSTAGED, noMatch)

        clickDescription(MORE_ACTIONS)
        assertTrue(NOTHING in texts(), texts().toString())
    }

    private fun render(entryType: EntryType, state: StatusState) {
        scene = ImageComposeScene(width = 420, height = 400) {
            AppTheme {
                StatusChangesList(
                    entryType = entryType,
                    statusState = state,
                    showSearch = state.showSearchUnstaged,
                    searchFilter = state.searchFilterUnstaged,
                    listState = rememberLazyListState(),
                    selectedEntries = emptyList(),
                    onSearchFilterToggled = {},
                    onSearchFocused = {},
                    onBlameFile = {},
                    onHistoryFile = {},
                    onDiscardFolderChanges = { _, _ -> },
                    onIgnore = {},
                    onConfirmAction = { action, onConfirm -> confirmations += action to onConfirm },
                    onAction = { actions += it },
                )
            }
        }.also { it.render() }
    }

    private fun close() {
        scene?.close()
        scene = null
    }

    private fun clickText(text: String) = click(nodes().first { text in it.texts() })

    private fun clickDescription(description: String) =
        click(nodes().first { description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })

    private fun click(node: SemanticsNode) {
        val scene = scene!!
        val position = Offset(node.boundsInWindow.center.x, node.boundsInWindow.center.y)

        scene.sendPointerEvent(PointerEventType.Move, position)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
        scene.render()
        scene.render()
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun descriptions(): List<String> =
        nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
