// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val parserLexer = StatusEntry("src/parser/Lexer.kt", StatusType.MODIFIED)
private val parserNew = StatusEntry("src/parser/Parser.kt", StatusType.ADDED)
private val view = StatusEntry("src/ui/View.kt", StatusType.MODIFIED)
private val other = StatusEntry("src/ui/Other.kt", StatusType.REMOVED)
private val files = listOf(parserLexer, parserNew, view, other)

private fun state(
    search: String? = null,
    searchOpen: Boolean = true,
    staged: List<StatusEntry> = emptyList(),
    unstaged: List<StatusEntry> = files,
) = StatusState(
    isLoading = false,
    staged = staged,
    unstaged = unstaged,
    showSearchUnstaged = searchOpen,
    searchFilterUnstaged = TextFieldValue(search.orEmpty()),
    showSearchStaged = searchOpen,
    searchFilterStaged = TextFieldValue(search.orEmpty()),
)

/** What the Stage all and Unstage all buttons act on while a search narrows the list (fork-only). */
class BulkTargetTest {
    @Test
    fun `without a search it is every file`() {
        assertEquals(BulkTarget.All, state().bulkTarget(EntryType.UNSTAGED, emptyList()))
        assertEquals(null, BulkTarget.All.entriesToApply)
    }

    @Test
    fun `a search that matches some files gives those and not the hidden ones`() {
        val target = state("PARSER").bulkTarget(EntryType.UNSTAGED, emptyList())

        assertEquals(BulkTarget.Matches(listOf(parserLexer, parserNew)), target)
        assertEquals(listOf(parserLexer, parserNew), target.entriesToApply)
    }

    @Test
    fun `a search that matches every file is every file`() {
        assertEquals(BulkTarget.All, state("src/").bulkTarget(EntryType.UNSTAGED, emptyList()))
    }

    @Test
    fun `a search that matches nothing does nothing, rather than everything`() {
        val target = state("nothing like it").bulkTarget(EntryType.UNSTAGED, emptyList())

        assertEquals(BulkTarget.NoMatches, target)
        assertTrue(target.isNoOp)
    }

    @Test
    fun `text left in a closed search box or a blank one narrows nothing`() {
        assertEquals(BulkTarget.All, state("parser", searchOpen = false).bulkTarget(EntryType.UNSTAGED, emptyList()))
        assertEquals(BulkTarget.All, state("  ").bulkTarget(EntryType.UNSTAGED, emptyList()))
    }

    @Test
    fun `two or more selected files win over the search, hidden ones included`() {
        val selection = listOf(parserLexer, other)

        assertEquals(BulkTarget.Selected(selection), state("parser").bulkTarget(EntryType.UNSTAGED, selection))
        assertEquals(BulkTarget.Selected(selection), state().bulkTarget(EntryType.UNSTAGED, selection))
    }

    @Test
    fun `one selected file is no selection`() {
        assertEquals(BulkTarget.All, state().bulkTarget(EntryType.UNSTAGED, listOf(view)))
        assertEquals(
            BulkTarget.Matches(listOf(parserLexer, parserNew)),
            state("parser").bulkTarget(EntryType.UNSTAGED, listOf(view)),
        )
    }

    @Test
    fun `Staged follows its own list and search`() {
        val state = state("ui", staged = files, unstaged = emptyList())

        assertEquals(BulkTarget.Matches(listOf(view, other)), state.bulkTarget(EntryType.STAGED, emptyList()))
        assertEquals(BulkTarget.NoMatches, state.bulkTarget(EntryType.UNSTAGED, emptyList()), "Unstaged is empty")
    }

    @Test
    fun `a search in one pane doesn't narrow the other`() {
        val both = StatusState(
            isLoading = false,
            staged = files,
            unstaged = files,
            showSearchUnstaged = true,
            searchFilterUnstaged = TextFieldValue("parser"),
        )

        assertEquals(BulkTarget.All, both.bulkTarget(EntryType.STAGED, emptyList()))
    }
}
