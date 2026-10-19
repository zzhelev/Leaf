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
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val files = listOf(
    StatusEntry("src/main/kotlin/dev/app/parser/Lexer.kt", StatusType.MODIFIED),
    StatusEntry("src/main/kotlin/dev/app/parser/Parser.kt", StatusType.MODIFIED),
    StatusEntry("src/main/kotlin/dev/app/parser/Tokens.kt", StatusType.ADDED),
    StatusEntry("src/main/kotlin/dev/app/ui/View.kt", StatusType.MODIFIED),
    StatusEntry("README.md", StatusType.MODIFIED),
)

/** The Stage all and Unstage all button's label and click while a search narrows the list (fork-only). */
class StageAllHeaderTest {
    private val actions = mutableListOf<StatusAction>()
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `without a search it says Stage all`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, state(null))

        assertTrue("Stage all" in texts(), texts().toString())
    }

    @Test
    fun `a search that hides files says how many it stages, and clicking sends the action`(): Unit =
        runBlocking(Dispatchers.Swing) {
            render(EntryType.UNSTAGED, state("parser"))

            assertTrue("Stage 3 matches" in texts(), texts().toString())
            assertFalse("Stage all" in texts())

            click("Stage 3 matches")

            assertEquals(listOf<StatusAction>(StatusAction.AllEntriesAction(EntryType.UNSTAGED)), actions)
        }

    @Test
    fun `one match is singular`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, state("view"))

        assertTrue("Stage 1 match" in texts(), texts().toString())
    }

    @Test
    fun `a search that matches everything still says Stage all`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, state("."))

        assertTrue("Stage all" in texts(), texts().toString())
    }

    @Test
    fun `no match shows a greyed button that does nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, state("zzz"))

        assertTrue("Stage 0 matches" in texts(), texts().toString())

        click("Stage 0 matches")

        assertEquals(emptyList<StatusAction>(), actions)
    }

    @Test
    fun `Staged says Unstage 3 matches`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.STAGED, state("parser", staged = true))

        assertTrue("Unstage 3 matches" in texts(), texts().toString())
    }

    @Test
    fun `two selected files say selected, whatever the search`(): Unit = runBlocking(Dispatchers.Swing) {
        render(EntryType.UNSTAGED, state("parser"), selected = files.take(2))

        assertTrue("Stage selected" in texts(), texts().toString())
        assertFalse(texts().any { "matches" in it })
    }

    private fun state(search: String?, staged: Boolean = false) = StatusState(
        isLoading = false,
        staged = if (staged) files else emptyList(),
        unstaged = if (staged) emptyList() else files,
        showSearchUnstaged = search != null,
        searchFilterUnstaged = TextFieldValue(search.orEmpty()),
        showSearchStaged = search != null,
        searchFilterStaged = TextFieldValue(search.orEmpty()),
    )

    private fun render(entryType: EntryType, state: StatusState, selected: List<StatusEntry> = emptyList()) {
        scene = ImageComposeScene(width = 400, height = 400) {
            AppTheme {
                StatusChangesList(
                    entryType = entryType,
                    statusState = state,
                    showSearch = false,
                    searchFilter = TextFieldValue(""),
                    listState = rememberLazyListState(),
                    selectedEntries = selected.map { dev.app.leaf.domain.models.DiffType.UncommittedDiff(it, entryType) },
                    onSearchFilterToggled = {},
                    onSearchFocused = {},
                    onBlameFile = {},
                    onHistoryFile = {},
                    onDiscardFolderChanges = { _, _ -> },
                    onIgnore = {},
                    onConfirmAction = { _, onConfirm -> onConfirm() },
                    onAction = { actions += it },
                )
            }
        }.also { it.render() }
    }

    private fun click(text: String) {
        val scene = scene!!
        val node = nodes().first { text in it.texts() }
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
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { it.texts() }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
}
