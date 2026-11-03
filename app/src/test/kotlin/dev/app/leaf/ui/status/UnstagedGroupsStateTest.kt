// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.CollapsedGroups
import dev.app.leaf.domain.sorting.FileGroup
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.ui.UiDataState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val NEW_TEST = StatusEntry("src/test/ParserTest.kt", StatusType.ADDED)
private val NEW_MAIN = StatusEntry("src/main/Lexer.kt", StatusType.ADDED)
private val NOTES = StatusEntry("notes.md", StatusType.ADDED)
private val PARSER = StatusEntry("src/main/Parser.kt", StatusType.MODIFIED)
private val GONE = StatusEntry("src/main/Old.kt", StatusType.REMOVED)
private val CLASH = StatusEntry("src/main/Clash.kt", StatusType.CONFLICTING)

/**
 * Unstaged's groups as [combineStatusState] builds them (fork-only, upstream issue 345): the setting, a search, what is
 * closed, Staged left as it is, and the files that a group's Stage and a group's folder stand for.
 */
class UnstagedGroupsStateTest {
    private val unstaged = listOf(NEW_TEST, PARSER, NEW_MAIN, GONE, NOTES, CLASH)
    private val staged = listOf(
        StatusEntry("src/main/Staged.kt", StatusType.ADDED, EntryType.STAGED),
        StatusEntry("README.md", StatusType.MODIFIED, EntryType.STAGED),
    )

    private fun state(
        viewState: FilesViewState = FilesViewState(groupUntracked = true),
        search: String? = null,
        groups: CollapsedGroups = CollapsedGroups(),
    ): StatusState = runBlocking {
        combineStatusState(
            status = flowOf(UiDataState(isLoading = false, data = Status(staged, unstaged), error = null)),
            showSearchStaged = MutableStateFlow(false),
            searchFilterStaged = MutableStateFlow(TextFieldValue("")),
            showSearchUnstaged = MutableStateFlow(search != null),
            searchFilterUnstaged = MutableStateFlow(TextFieldValue(search.orEmpty())),
            viewState = flowOf(viewState),
            stagedCollapsedFolders = flowOf(CollapsedFolders()),
            unstagedCollapsedFolders = flowOf(CollapsedFolders()),
            swapUncommittedChanges = flowOf(false),
            isAmend = flowOf(false),
            isAmendRebaseInteractive = flowOf(false),
            committerDataRequestState = flowOf(CommitterDataRequestState.None),
            rebaseInteractiveState = flowOf(RebaseInteractiveState.None),
            selectedUnstagedDiffEntries = flowOf(emptyList()),
            selectedStagedDiffEntries = flowOf(emptyList()),
            commitMessage = flowOf(TextFieldValue("")),
            previousCommitMessage = flowOf(null),
            repositoryState = flowOf(UiDataState(isLoading = false, data = RepositoryState.SAFE, error = null)),
            repositoryPath = flowOf("/repo/.git"),
            sectionSizes = flowOf(StatusSectionSizes()),
            unstagedCollapsedGroups = flowOf(groups),
        ).first()
    }

    private fun List<FileRow<StatusEntry>>.labels() = map { row ->
        when (row) {
            is FileRow.Group -> "== ${row.group} ${row.fileCount}" + if (row.isExpanded) "" else " +"
            is FileRow.Folder -> "[${row.path}]"
            is FileRow.File -> row.file.item.filePath
        }
    }

    @Test
    fun `the setting puts Unstaged in groups and leaves Staged as it is`() {
        val off = state(FilesViewState())
        val on = state()

        assertEquals(
            listOf(
                "src/main/Clash.kt", "src/main/Lexer.kt", "notes.md", "src/main/Old.kt", "src/main/Parser.kt",
                "src/test/ParserTest.kt",
            ),
            off.unstagedRows.labels(),
        )
        assertEquals(
            listOf(
                "== Conflicts 1", "src/main/Clash.kt",
                "== Changes 2", "src/main/Old.kt", "src/main/Parser.kt",
                "== Untracked 3", "src/main/Lexer.kt", "notes.md", "src/test/ParserTest.kt",
            ),
            on.unstagedRows.labels(),
        )
        // Staged's new file is staged, not untracked: no groups there
        assertEquals(off.stagedRows, on.stagedRows)
        assertEquals(listOf("README.md", "src/main/Staged.kt"), on.stagedRows.labels())
    }

    @Test
    fun `a search filters each group, drops a group without matches, and counts the matches`() {
        assertEquals(
            listOf("== Changes 1", "src/main/Parser.kt", "== Untracked 1", "src/test/ParserTest.kt"),
            state(search = "parser").unstagedRows.labels(),
        )
    }

    @Test
    fun `a closed group stays a header, and a search opens what was closed before it`() {
        val closed = CollapsedGroups().groupToggled(FileGroup.Untracked, isSearching = false)

        assertEquals("== Untracked 3 +", state(groups = closed).unstagedRows.labels().last())
        assertEquals("src/test/ParserTest.kt", state(search = "parser", groups = closed).unstagedRows.labels().last())
    }

    @Test
    fun `a group's Stage stands for its files, closed ones and a search's matches`() {
        val closed = CollapsedGroups()
            .groupToggled(FileGroup.Untracked, isSearching = false)
            .folderToggled(FileGroup.Untracked, "src", isSearching = false)

        assertEquals(listOf(NEW_TEST, NEW_MAIN, NOTES), state(groups = closed).unstagedEntriesShownIn(FileGroup.Untracked))
        assertEquals(listOf(PARSER, GONE), state().unstagedEntriesShownIn(FileGroup.Changes))
        assertEquals(listOf(NEW_TEST), state(search = "parser").unstagedEntriesShownIn(FileGroup.Untracked))
        assertEquals(emptyList<StatusEntry>(), state(search = "parser").unstagedEntriesShownIn(FileGroup.Conflicts))
    }

    @Test
    fun `a folder of a group stands for that group's files only, and without a group for all of them`() {
        val tree = state(FilesViewState(viewMode = FilesViewMode.FolderTree, groupUntracked = true))

        assertEquals(listOf(NEW_MAIN), tree.entriesShownUnder("src/main", EntryType.UNSTAGED, FileGroup.Untracked))
        assertEquals(listOf(PARSER, GONE), tree.entriesShownUnder("src/main", EntryType.UNSTAGED, FileGroup.Changes))
        assertEquals(
            listOf(PARSER, NEW_MAIN, GONE, CLASH),
            tree.entriesShownUnder("src/main", EntryType.UNSTAGED),
        )
        // In the tree src/main is in all three groups, with a row in each
        assertEquals(
            listOf("[src/main]", "[src/main]", "[src]", "[src/main]", "[src/test]"),
            tree.unstagedRows.filterIsInstance<FileRow.Folder>().map { "[${it.path}]" },
        )
    }

    @Test
    fun `Stage all and Discard all act on both groups, as before`() {
        val on = state()

        assertEquals(BulkTarget.All, on.bulkTarget(EntryType.UNSTAGED, emptyList()))
        assertEquals(unstaged, on.entriesShown(EntryType.UNSTAGED))
        assertEquals(
            BulkTarget.Matches(listOf(NEW_TEST, PARSER)),
            state(search = "parser").bulkTarget(EntryType.UNSTAGED, emptyList()),
        )
    }
}
