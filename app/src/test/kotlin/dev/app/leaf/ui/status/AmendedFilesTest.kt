// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.RebaseInteractiveState
import dev.app.leaf.domain.models.RepositoryState
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusSectionSizes
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.CollapsedFolders
import dev.app.leaf.domain.sorting.FileRow
import dev.app.leaf.domain.sorting.FilesViewMode
import dev.app.leaf.domain.sorting.FilesViewState
import dev.app.leaf.ui.UiDataState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(name: String) = Commit(
    hash = name.padEnd(40, '0'),
    message = "Subject of $name",
    committer = someone,
    author = someone,
    date = 1_760_000_000,
    parentsHashes = emptyList(),
)

private class Entry(type: ChangeType, oldPath: String, newPath: String, score: Int = 0) : DiffEntry() {
    init {
        changeType = type
        this.oldPath = oldPath
        this.newPath = newPath
        this.score = score
    }
}

private fun modified(path: String): DiffEntry = Entry(ChangeType.MODIFY, path, path)
private fun added(path: String): DiffEntry = Entry(ChangeType.ADD, DiffEntry.DEV_NULL, path)
private fun deleted(path: String): DiffEntry = Entry(ChangeType.DELETE, path, DiffEntry.DEV_NULL)
private fun renamed(from: String, to: String): DiffEntry = Entry(ChangeType.RENAME, from, to, score = 90)

private val LAST_COMMIT = listOf(
    modified("app/src/main/kotlin/dev/app/leaf/ui/status/StatusState.kt"),
    modified("app/src/main/kotlin/dev/app/leaf/ui/status/StatusPane.kt"),
    added("domain/src/main/kotlin/dev/app/leaf/domain/amend/AmendedFiles.kt"),
    deleted("README.old"),
)

/**
 * The group of the amended commit's files under Staged (fork-only, upstream issue 260): when a commit is amended, and
 * how the rows follow Staged's search, view and sort.
 */
class AmendedFilesTest {
    private val head = commit("a1")

    private fun files(changes: List<DiffEntry> = LAST_COMMIT, isLoading: Boolean = false) =
        AmendedFiles(head, changes, isLoading = isLoading)

    private fun group(
        files: AmendedFiles? = files(),
        viewState: FilesViewState = FilesViewState(),
        search: String? = null,
        isCollapsed: Boolean = false,
        folders: CollapsedFolders = CollapsedFolders(),
    ) = amendedGroup(files, viewState, search, isCollapsed, folders)

    private fun List<FileRow<DiffEntry>>.paths() = mapNotNull { (it as? FileRow.File)?.file?.path }

    // Which commit is amended

    @Test
    fun `Amend on, outside an operation, amends the last commit`() {
        assertEquals(head, amendedCommit(RepositoryState.SAFE, RebaseInteractiveState.None, true, false, head))
    }

    @Test
    fun `Amend off amends nothing`() {
        assertNull(amendedCommit(RepositoryState.SAFE, RebaseInteractiveState.None, false, false, head))
        assertNull(amendedCommit(RepositoryState.SAFE, RebaseInteractiveState.None, false, true, head))
    }

    @Test
    fun `no head, no commit to amend`() {
        assertNull(amendedCommit(RepositoryState.SAFE, RebaseInteractiveState.None, true, false, null))
    }

    @Test
    fun `a merge, cherry-pick or revert has no Amend, whatever the switch says`() {
        for (state in listOf(
            RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED, RepositoryState.CHERRY_PICKING,
            RepositoryState.CHERRY_PICKING_RESOLVED, RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED,
        )) {
            assertNull(amendedCommit(state, RebaseInteractiveState.None, true, true, head), state.name)
        }
    }

    @Test
    fun `a rebase stopped to edit a commit amends it, with its own switch`() {
        val stop = RebaseInteractiveState.ProcessingCommits(commitToAmendId = head.hash)

        assertEquals(head, amendedCommit(RepositoryState.REBASING_INTERACTIVE, stop, false, true, head))
        // The other switch is for outside a rebase
        assertNull(amendedCommit(RepositoryState.REBASING_INTERACTIVE, stop, true, false, head))
        assertNull(amendedCommit(RepositoryState.REBASING_INTERACTIVE, stop, false, false, head))
    }

    @Test
    fun `a rebase stopped anywhere else, where the field is read-only, amends nothing`() {
        // ProcessingCommits is true at every stop of an interactive rebase, so its switch is on at a conflict too
        val conflictStop = RebaseInteractiveState.ProcessingCommits(commitToAmendId = null)
        val blankStop = RebaseInteractiveState.ProcessingCommits(commitToAmendId = "")

        assertNull(amendedCommit(RepositoryState.REBASING_INTERACTIVE, conflictStop, false, true, head))
        assertNull(amendedCommit(RepositoryState.REBASING_INTERACTIVE, blankStop, false, true, head))
        assertNull(amendedCommit(RepositoryState.REBASING_MERGE, RebaseInteractiveState.None, true, true, head))
    }

    // The rows

    @Test
    fun `no group while nothing is amended`() {
        assertNull(group(files = null))
    }

    @Test
    fun `the rows are the commit's files, sorted as Staged sorts, with the count`() {
        val group = group()!!

        assertEquals(4, group.count)
        assertEquals(head, group.commit)
        // File name, ascending: Staged's default
        assertEquals(
            listOf("AmendedFiles.kt", "README.old", "StatusPane.kt", "StatusState.kt"),
            group.rows.mapNotNull { (it as? FileRow.File)?.file?.fileName },
        )
        assertEquals(4, group.rows.paths().size)
    }

    @Test
    fun `the sort and view of Staged apply`() {
        val byPath = group(viewState = FilesViewState(viewMode = FilesViewMode.FlatList, ascending = false))!!

        assertEquals("StatusState.kt", (byPath.rows.first() as FileRow.File).file.fileName)

        val tree = group(viewState = FilesViewState(viewMode = FilesViewMode.FolderTree))!!

        assertTrue(tree.rows.any { it is FileRow.Folder })
        assertEquals(4, tree.count)
    }

    @Test
    fun `a rename is one row, with where it came from`() {
        val group = group(files = files(listOf(renamed("src/Lex.kt", "src/parse/Lexer.kt"))))!!

        val row = group.rows.single() as FileRow.File

        assertEquals("src/parse/Lexer.kt", row.file.path)
        assertNotNull(row.file.renamedFrom)
    }

    @Test
    fun `folders close apart from Staged's`() {
        val open = group(viewState = FilesViewState(viewMode = FilesViewMode.FolderTree))!!
        val folder = open.rows.filterIsInstance<FileRow.Folder>().first()

        val closed = group(
            viewState = FilesViewState(viewMode = FilesViewMode.FolderTree),
            folders = CollapsedFolders(collapsed = setOf(folder.path)),
        )!!

        assertTrue(closed.rows.size < open.rows.size)
        assertEquals(4, closed.count)
    }

    @Test
    fun `a closed group keeps its count and its rows for the header to hide`() {
        val closed = group(isCollapsed = true)!!

        assertTrue(closed.isCollapsed)
        assertEquals(4, closed.count)
    }

    // Staged's search

    @Test
    fun `a search narrows the group to its matches, and the count follows`() {
        val group = group(search = "status")!!

        assertEquals(2, group.count)
        assertEquals(
            listOf("StatusPane.kt", "StatusState.kt"),
            group.rows.mapNotNull { (it as? FileRow.File)?.file?.fileName },
        )
    }

    @Test
    fun `a search finds a renamed file by the name it had`() {
        val group = group(files = files(listOf(renamed("src/Lex.kt", "src/parse/Lexer.kt"))), search = "src/lex.kt")!!

        assertEquals(1, group.count)
    }

    @Test
    fun `a search that matches nothing leaves the group out, as Staged shows no row`() {
        assertNull(group(search = "zzz"))
    }

    @Test
    fun `a search that matches everything shows every file`() {
        val group = group(search = ".")!!

        assertEquals(4, group.count)
        assertEquals(4, group.rows.paths().size)
    }

    // The header's count

    @Test
    fun `the count shows once the files are known`() {
        assertTrue(group()!!.showsCount)
        // The first load: nothing is known, so no "0"
        assertFalse(group(files = files(emptyList(), isLoading = true))!!.showsCount)
        // Another commit's files stay while the next one's load
        assertTrue(group(files = files(isLoading = true))!!.showsCount)
        // A commit that changes nothing, as an amend of an empty commit
        assertTrue(group(files = files(emptyList()))!!.showsCount)
        assertFalse(
            group(files = AmendedFiles(head, error = dev.app.leaf.domain.errors.GenericError("no objects")))!!.showsCount,
        )
    }

    // The diff of a file of the group

    @Test
    fun `a diff opened from the group stays while the group has the file and the uncommitted changes are selected`() {
        val shown = LAST_COMMIT[0]

        assertFalse(isAmendedFileDiffStale(shown, LAST_COMMIT, LAST_COMMIT, onUncommittedChanges = true))
    }

    @Test
    fun `a diff opened from the group closes when the group goes`() {
        val shown = LAST_COMMIT[0]

        // Amend turned off, or the commit made
        assertTrue(isAmendedFileDiffStale(shown, LAST_COMMIT, emptyList(), onUncommittedChanges = true))
    }

    @Test
    fun `a diff opened from the group closes when another commit's files replace it`() {
        val shown = LAST_COMMIT[0]
        val otherCommit = listOf(modified(shown.newPath), modified("src/other.kt"))

        // The same path, read again for another commit, is another entry
        assertTrue(isAmendedFileDiffStale(shown, LAST_COMMIT, otherCommit, onUncommittedChanges = true))
    }

    @Test
    fun `a diff opened from the group closes when a commit or anything else is selected`() {
        val shown = LAST_COMMIT[0]

        assertTrue(isAmendedFileDiffStale(shown, LAST_COMMIT, LAST_COMMIT, onUncommittedChanges = false))
    }

    @Test
    fun `a diff opened from anywhere else is left alone`() {
        val fromTheCommitPane = modified("src/elsewhere.kt")

        assertFalse(isAmendedFileDiffStale(fromTheCommitPane, LAST_COMMIT, emptyList(), onUncommittedChanges = false))
        assertFalse(isAmendedFileDiffStale(null, LAST_COMMIT, emptyList(), onUncommittedChanges = false))
        assertFalse(isAmendedFileDiffStale(LAST_COMMIT[0], emptyList(), emptyList(), onUncommittedChanges = false))
    }

    // In the state

    private val status = Status(
        staged = listOf(StatusEntry("src/a.txt", StatusType.MODIFIED, EntryType.STAGED)),
        unstaged = listOf(StatusEntry("src/b.txt", StatusType.MODIFIED)),
    )

    private fun states(
        amendedFiles: Flow<AmendedFiles?>,
        amendedCollapsed: Flow<Boolean> = flowOf(false),
        searchStaged: String = "",
        viewState: FilesViewState = FilesViewState(),
    ): Flow<StatusState> = combineStatusState(
        status = flowOf(UiDataState(isLoading = false, data = status, error = null)),
        showSearchStaged = MutableStateFlow(searchStaged.isNotEmpty()),
        searchFilterStaged = MutableStateFlow(TextFieldValue(searchStaged)),
        showSearchUnstaged = MutableStateFlow(false),
        searchFilterUnstaged = MutableStateFlow(TextFieldValue("")),
        viewState = flowOf(viewState),
        stagedCollapsedFolders = flowOf(CollapsedFolders()),
        unstagedCollapsedFolders = flowOf(CollapsedFolders()),
        swapUncommittedChanges = flowOf(false),
        isAmend = flowOf(true),
        isAmendRebaseInteractive = flowOf(false),
        committerDataRequestState = flowOf(CommitterDataRequestState.None),
        rebaseInteractiveState = flowOf(RebaseInteractiveState.None),
        selectedUnstagedDiffEntries = flowOf(emptyList()),
        selectedStagedDiffEntries = flowOf(emptyList()),
        commitMessage = flowOf(TextFieldValue("")),
        previousCommitMessage = flowOf("Subject of a1"),
        repositoryState = flowOf(UiDataState(isLoading = false, data = RepositoryState.SAFE, error = null)),
        repositoryPath = flowOf("/repo/.git"),
        sectionSizes = flowOf(StatusSectionSizes()),
        amendedFiles = amendedFiles,
        amendedCollapsed = amendedCollapsed,
    )

    @Test
    fun `the state has the group next to Staged's rows, and not among them`(): Unit = runBlocking {
        val state = states(flowOf(files())).first()

        assertEquals(4, state.amended?.count)
        assertEquals(listOf("src/a.txt"), state.staged.map { it.filePath })
        assertEquals(1, state.stagedRows.size)
    }

    @Test
    fun `without Amend the state has no group`(): Unit = runBlocking {
        assertNull(states(flowOf(null)).first().amended)
    }

    @Test
    fun `Stage all and Unstage all see only Staged's own files`(): Unit = runBlocking {
        val withGroup = states(flowOf(files())).first()
        val without = states(flowOf(null)).first()

        for (entryType in EntryType.entries) {
            assertEquals(without.entriesShown(entryType), withGroup.entriesShown(entryType))
            assertEquals(without.bulkTarget(entryType, emptyList()), withGroup.bulkTarget(entryType, emptyList()))
        }

        assertEquals(1, withGroup.entriesShown(EntryType.STAGED).size)
        assertEquals(2, withGroup.changedFilesCount)
        assertEquals(without.changedFilesCount, withGroup.changedFilesCount)
        assertEquals(without.hasStagedFiles, withGroup.hasStagedFiles)
    }

    @Test
    fun `an amend with nothing staged still lists the commit's files, and staged stays empty`(): Unit = runBlocking {
        val none = Status()
        val state = combineStatusState(
            status = flowOf(UiDataState(isLoading = false, data = none, error = null)),
            showSearchStaged = MutableStateFlow(false),
            searchFilterStaged = MutableStateFlow(TextFieldValue("")),
            showSearchUnstaged = MutableStateFlow(false),
            searchFilterUnstaged = MutableStateFlow(TextFieldValue("")),
            viewState = flowOf(FilesViewState()),
            stagedCollapsedFolders = flowOf(CollapsedFolders()),
            unstagedCollapsedFolders = flowOf(CollapsedFolders()),
            swapUncommittedChanges = flowOf(false),
            isAmend = flowOf(true),
            isAmendRebaseInteractive = flowOf(false),
            committerDataRequestState = flowOf(CommitterDataRequestState.None),
            rebaseInteractiveState = flowOf(RebaseInteractiveState.None),
            selectedUnstagedDiffEntries = flowOf(emptyList()),
            selectedStagedDiffEntries = flowOf(emptyList()),
            commitMessage = flowOf(TextFieldValue("Subject of a1")),
            previousCommitMessage = flowOf("Subject of a1"),
            repositoryState = flowOf(UiDataState(isLoading = false, data = RepositoryState.SAFE, error = null)),
            repositoryPath = flowOf("/repo/.git"),
            sectionSizes = flowOf(StatusSectionSizes()),
            amendedFiles = flowOf(files()),
        ).first()

        assertFalse(state.hasStagedFiles)
        assertTrue(state.stagedRows.isEmpty())
        assertEquals(4, state.amended?.count)
        // Amend works with nothing staged, as before
        assertTrue(state.canAmend)
    }

    @Test
    fun `the state's group follows Staged's search`(): Unit = runBlocking {
        val state = states(flowOf(files()), searchStaged = "status").first()

        assertEquals(2, state.amended?.count)
        assertNull(states(flowOf(files()), searchStaged = "zzz").first().amended)
    }

    @Test
    fun `the state's group is closed when asked`(): Unit = runBlocking {
        assertTrue(states(flowOf(files()), amendedCollapsed = flowOf(true)).first().amended!!.isCollapsed)
        assertFalse(states(flowOf(files())).first().amended!!.isCollapsed)
    }

    @Test
    fun `the group is the same object while only the commit message changes`(): Unit = runBlocking {
        val message = MutableStateFlow(TextFieldValue(""))
        val flows = combineStatusState(
            status = flowOf(UiDataState(isLoading = false, data = status, error = null)),
            showSearchStaged = MutableStateFlow(false),
            searchFilterStaged = MutableStateFlow(TextFieldValue("")),
            showSearchUnstaged = MutableStateFlow(false),
            searchFilterUnstaged = MutableStateFlow(TextFieldValue("")),
            viewState = flowOf(FilesViewState()),
            stagedCollapsedFolders = flowOf(CollapsedFolders()),
            unstagedCollapsedFolders = flowOf(CollapsedFolders()),
            swapUncommittedChanges = flowOf(false),
            isAmend = flowOf(true),
            isAmendRebaseInteractive = flowOf(false),
            committerDataRequestState = flowOf(CommitterDataRequestState.None),
            rebaseInteractiveState = flowOf(RebaseInteractiveState.None),
            selectedUnstagedDiffEntries = flowOf(emptyList()),
            selectedStagedDiffEntries = flowOf(emptyList()),
            commitMessage = message,
            previousCommitMessage = flowOf("Subject of a1"),
            repositoryState = flowOf(UiDataState(isLoading = false, data = RepositoryState.SAFE, error = null)),
            repositoryPath = flowOf("/repo/.git"),
            sectionSizes = flowOf(StatusSectionSizes()),
            amendedFiles = flowOf(files()),
        )

        val states = flows.produceIn(this)
        val before = states.receive()

        message.value = TextFieldValue("Typed")
        val after = states.receive()
        states.cancel()

        assertEquals("Typed", after.commitMessage.text)
        assertSame(before.amended, after.amended)
    }
}
