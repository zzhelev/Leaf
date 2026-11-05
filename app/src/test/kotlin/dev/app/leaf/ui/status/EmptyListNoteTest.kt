// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.domain.sorting.buildFileRows
import dev.app.leaf.domain.sorting.toFileItems
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")
private val HEAD = Commit("a1".padEnd(40, '0'), "Show the amended commit's files", someone, someone, 1_760_000_000, emptyList())

private val stagedFile = StatusEntry("app/Parser.kt", StatusType.MODIFIED, EntryType.STAGED)
private val unstagedFile = StatusEntry("app/Lexer.kt", StatusType.MODIFIED, EntryType.UNSTAGED)

/** A status that was read, with the rows the lists show: none for an entry list that a search narrowed to nothing. */
private fun status(
    staged: List<StatusEntry> = emptyList(),
    unstaged: List<StatusEntry> = emptyList(),
    stagedShown: List<StatusEntry> = staged,
    unstagedShown: List<StatusEntry> = unstaged,
    hasStatus: Boolean = true,
    amended: AmendedGroup? = null,
) = StatusState(
    isLoading = false,
    hasStatus = hasStatus,
    staged = staged,
    unstaged = unstaged,
    stagedRows = buildFileRows(stagedShown.toFileItems(), FilesViewState()) { false },
    unstagedRows = buildFileRows(unstagedShown.toFileItems(), FilesViewState()) { false },
    amended = amended,
)

/** What an empty Staged or Unstaged list says (docs/fork/window-chrome.md, section 6). */
class EmptyListNoteTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun close() {
        scene?.close()
    }

    @Test
    fun `both lists empty is a clean working tree, and Staged says nothing is staged`() {
        val clean = status()

        assertEquals(EmptyListNote.NOTHING_STAGED, clean.emptyListNote(EntryType.STAGED))
        assertEquals(EmptyListNote.WORKING_TREE_CLEAN, clean.emptyListNote(EntryType.UNSTAGED))
    }

    @Test
    fun `with files staged, an empty Unstaged isn't a clean working tree`() {
        val state = status(staged = listOf(stagedFile))

        assertNull(state.emptyListNote(EntryType.STAGED))
        assertEquals(EmptyListNote.NO_UNSTAGED_CHANGES, state.emptyListNote(EntryType.UNSTAGED))
    }

    @Test
    fun `a list that shows rows says nothing, and an empty Staged with unstaged files is just nothing staged`() {
        val state = status(unstaged = listOf(unstagedFile))

        assertEquals(EmptyListNote.NOTHING_STAGED, state.emptyListNote(EntryType.STAGED))
        assertNull(state.emptyListNote(EntryType.UNSTAGED))
    }

    @Test
    fun `a search that hides every file says no files match`() {
        val state = status(staged = listOf(stagedFile), unstaged = listOf(unstagedFile), stagedShown = emptyList(), unstagedShown = emptyList())

        assertEquals(EmptyListNote.NO_MATCHES, state.emptyListNote(EntryType.STAGED))
        assertEquals(EmptyListNote.NO_MATCHES, state.emptyListNote(EntryType.UNSTAGED))
    }

    @Test
    fun `nothing is said before a status has been read`() {
        val state = status(hasStatus = false)

        assertNull(state.emptyListNote(EntryType.STAGED))
        assertNull(state.emptyListNote(EntryType.UNSTAGED))
        assertFalse(StatusState().hasStatus, "a tab starts without one")
    }

    @Test
    fun `while Amend shows the last commit's files under Staged, Staged says nothing`() {
        val group = AmendedGroup(HEAD, count = 0, rows = emptyList(), isCollapsed = false, isLoading = true, error = null)

        assertNull(status(amended = group).emptyListNote(EntryType.STAGED))
        assertEquals(EmptyListNote.WORKING_TREE_CLEAN, status(amended = group).emptyListNote(EntryType.UNSTAGED))
    }

    @Test
    fun `the notes show in the lists, and a list with files has none`(): Unit = runBlocking(Dispatchers.Swing) {
        render(status(), EntryType.UNSTAGED)
        assertTrue("Working tree clean" in texts(), texts().toString())

        render(status(), EntryType.STAGED)
        assertTrue("Nothing staged" in texts(), texts().toString())

        render(status(staged = listOf(stagedFile)), EntryType.UNSTAGED)
        assertTrue("No unstaged changes" in texts(), texts().toString())
        assertFalse("Working tree clean" in texts())

        render(status(unstaged = listOf(unstagedFile)), EntryType.UNSTAGED)
        assertTrue("Lexer.kt" in texts(), texts().toString())
        assertFalse(texts().any { it in NOTES }, texts().toString())
    }

    @Test
    fun `renders the notes in both themes, to look at`(): Unit = runBlocking(Dispatchers.Swing) {
        val folder = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        folder.mkdirs()

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            for ((name, state, entryType) in listOf(
                Triple("clean", status(), EntryType.UNSTAGED),
                Triple("nothing-staged", status(), EntryType.STAGED),
                Triple("no-unstaged", status(staged = listOf(stagedFile)), EntryType.UNSTAGED),
            )) {
                render(state, entryType, theme)
                File(folder, "empty-$name-${theme.name.lowercase()}.png")
                    .writeBytes(scene!!.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }

    private fun render(state: StatusState, entryType: EntryType, theme: Theme = Theme.Light) {
        scene?.close()
        scene = ImageComposeScene(width = 330, height = 240) {
            AppTheme(theme) {
                StatusChangesList(
                    entryType = entryType,
                    statusState = state,
                    showSearch = false,
                    searchFilter = TextFieldValue(""),
                    listState = rememberLazyListState(),
                    selectedEntries = emptyList<DiffType.UncommittedDiff>(),
                    onSearchFilterToggled = {},
                    onSearchFocused = {},
                    onBlameFile = {},
                    onHistoryFile = {},
                    onDiscardFolderChanges = { _, _ -> },
                    onIgnore = {},
                    onConfirmAction = { _, onConfirm -> onConfirm() },
                    onAction = {},
                )
            }
        }.also { repeat(3) { _ -> it.render() } }
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    private companion object {
        val NOTES = listOf("Nothing staged", "Working tree clean", "No unstaged changes", "No files match")
    }
}
