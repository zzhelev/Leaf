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
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.ui.UiDataState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class StatusStateTest {
    private val status = Status(
        staged = listOf(StatusEntry("src/a.txt", StatusType.MODIFIED, EntryType.STAGED)),
        unstaged = listOf(
            StatusEntry("src/b.txt", StatusType.MODIFIED),
            StatusEntry("c.txt", StatusType.ADDED),
        ),
    )

    private val commitMessage = MutableStateFlow(TextFieldValue(""))
    private val showSearchUnstaged = MutableStateFlow(false)
    private val searchFilterUnstaged = MutableStateFlow(TextFieldValue(""))

    private fun statusStates(): Flow<StatusState> = combineStatusState(
        status = flowOf(UiDataState(isLoading = false, data = status, error = null)),
        showSearchStaged = MutableStateFlow(false),
        searchFilterStaged = MutableStateFlow(TextFieldValue("")),
        showSearchUnstaged = showSearchUnstaged,
        searchFilterUnstaged = searchFilterUnstaged,
        viewState = flowOf(FilesViewState()),
        stagedCollapsedFolders = flowOf(CollapsedFolders()),
        unstagedCollapsedFolders = flowOf(CollapsedFolders()),
        swapUncommittedChanges = flowOf(false),
        isAmend = flowOf(false),
        isAmendRebaseInteractive = flowOf(false),
        committerDataRequestState = flowOf(CommitterDataRequestState.None),
        rebaseInteractiveState = flowOf(RebaseInteractiveState.None),
        selectedUnstagedDiffEntries = flowOf(emptyList()),
        selectedStagedDiffEntries = flowOf(emptyList()),
        commitMessage = commitMessage,
        previousCommitMessage = flowOf(null),
        repositoryState = flowOf(UiDataState(isLoading = false, data = RepositoryState.SAFE, error = null)),
        repositoryPath = flowOf("/repo/.git"),
        sectionSizes = flowOf(StatusSectionSizes()),
    )

    @Test
    fun `typing in the commit message keeps the file lists that were built`(): Unit = runBlocking {
        val states = statusStates().produceIn(this)
        val before = states.receive()

        commitMessage.value = TextFieldValue("Fix the")
        val after = states.receive()
        states.cancel()

        assertEquals("Fix the", after.commitMessage.text)
        assertSame(before.staged, after.staged)
        assertSame(before.unstaged, after.unstaged)
        assertSame(before.stagedRows, after.stagedRows)
        assertSame(before.unstagedRows, after.unstagedRows)
    }

    @Test
    fun `a search still filters its list, and the state shows what was typed`(): Unit = runBlocking {
        val states = statusStates().produceIn(this)
        val before = states.receive()

        showSearchUnstaged.value = true
        states.receive()
        searchFilterUnstaged.value = TextFieldValue("c.txt")
        val searching = states.receive()
        states.cancel()

        assertEquals(listOf("src/b.txt", "c.txt"), before.unstagedRows.filePaths())
        assertNotSame(before.unstagedRows, searching.unstagedRows)
        assertEquals(listOf("c.txt"), searching.unstagedRows.filePaths())
        assertEquals("c.txt", searching.searchFilterUnstaged.text)
    }

    private fun List<FileRow<StatusEntry>>.filePaths(): List<String> =
        filterIsInstance<FileRow.File<StatusEntry>>().map { it.file.item.filePath }
}
