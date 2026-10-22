// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import androidx.compose.ui.text.input.TextFieldValue
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffSelected
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.SelectedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.junit.jupiter.api.AfterEach
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

private class Entry(type: ChangeType, oldPath: String, newPath: String) : DiffEntry() {
    init {
        this.changeType = type
        this.oldPath = oldPath
        this.newPath = newPath
    }
}

private fun modified(path: String): DiffEntry = Entry(ChangeType.MODIFY, path, path)

/**
 * An open diff following the selection (fork-only, upstream issues 343, 136 and 128): what it shows after each kind of
 * selection, what it leaves when the commit doesn't have the file, and when it doesn't follow at all. The extender's
 * sequence of states for a selected commit is played as it sends them: the previous commit's files first, marked, then the
 * commit's own.
 */
class DiffFollowControllerTest {
    // Unconfined: the controller's collector starts and runs on the thread that sets a flow, so the first state it reads is
    // the one that the test left, not the initial one read late by another thread
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val selectedItem = MutableStateFlow<SelectedItem>(SelectedItem.UncommittedChanges)
    private val commitChanges = MutableStateFlow<CommitChangesState?>(null)
    private val diffSelected = MutableStateFlow<DiffSelected?>(null)
    private val keepsLog = MutableStateFlow(true)

    private val controller = DiffFollowController(
        scope = scope,
        selectedItem = selectedItem,
        commitChanges = commitChanges,
        diffSelected = diffSelected,
        keepsLog = keepsLog,
        select = { diffSelected.value = it },
    )

    private val first = commit("c1")
    private val second = commit("c2")
    private val third = commit("c3")

    private var lastEntries = emptyList<DiffEntry>()

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun item(commit: Commit): SelectedItem = SelectedItem.CommitItem(commit, isStash = false, scrollToItem = false)

    private fun state(
        commit: Commit,
        entries: List<DiffEntry>,
        isLoading: Boolean = false,
        showsPreviousChanges: Boolean = false,
        error: dev.app.leaf.domain.errors.AppError? = null,
    ) = CommitChangesState(
        isLoading = isLoading,
        error = error,
        commit = commit,
        showSearch = false,
        searchFilter = TextFieldValue(""),
        changes = entries,
        showsPreviousChanges = showsPreviousChanges,
    )

    /** Selects the commit as the commit pane does: the previous commit's files, marked as such, then its own. */
    private fun select(commit: Commit, vararg entries: DiffEntry, item: SelectedItem = item(commit)) {
        selectedItem.value = item
        commitChanges.value = state(commit, lastEntries, showsPreviousChanges = true)
        commitChanges.value = state(commit, entries.toList())
        lastEntries = entries.toList()
    }

    /** What the commit's list does when a file is clicked. */
    private fun open(entry: DiffEntry) {
        val selection = DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(entry)))

        diffSelected.value = selection
        controller.pin(selection)
    }

    private fun shownEntry() = (diffSelected.value as? DiffSelected.CommitedChanges)?.items?.singleOrNull()?.diffEntry

    /** Waits for what the controller does, which it does on its own thread. */
    private fun eventually(message: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(3_000) {
                while (!condition()) delay(5)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out: $message")
        }
    }

    /** Lets the controller act on what it was sent, for a check that nothing happened. */
    private fun quiet() = runBlocking { delay(120) }

    @Test
    fun `the diff follows to the same file in the next commit`() {
        val inFirst = modified("src/Log.kt")
        val inSecond = modified("src/Log.kt")

        select(first, inFirst, modified("README.md"))
        open(inFirst)
        select(second, modified("README.md"), inSecond)

        eventually("the second commit's entry is shown") { shownEntry() === inSecond }
        assertNull(controller.missing.value)
    }

    @Test
    fun `a commit that does not change the file leaves the area up with the file's path`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, modified("README.md"))

        eventually("the placeholder") { controller.missing.value != null }
        assertNull(diffSelected.value)
        assertEquals(MissingFollowedFile("src/Log.kt", second, unreadable = false), controller.missing.value)
    }

    @Test
    fun `the next commit that changes the file shows its diff again`() {
        val inFirst = modified("src/Log.kt")
        val inThird = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, modified("README.md"))
        eventually("the placeholder") { controller.missing.value != null }

        select(third, inThird)

        eventually("the third commit's entry is shown") { shownEntry() === inThird }
        assertNull(controller.missing.value)
    }

    @Test
    fun `stepping back to a commit that has the file shows it too`() {
        val inFirst = modified("src/Log.kt")
        val inSecond = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, inSecond)
        eventually("the second commit's entry") { shownEntry() === inSecond }

        val again = modified("src/Log.kt")
        select(first, again)

        eventually("the first commit's entry again") { shownEntry() === again }
    }

    @Test
    fun `a commit that moved the file shows the move`() {
        val inFirst = modified("src/Old.kt")
        val move = Entry(ChangeType.RENAME, "src/Old.kt", "src/New.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, move)

        eventually("the move") { shownEntry() === move }
    }

    @Test
    fun `the previous commit's files, shown until the commit's own load, are not read as the commit's`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)

        // The commit pane's first state for the new commit: the old list, marked
        selectedItem.value = item(second)
        commitChanges.value = state(second, listOf(inFirst), showsPreviousChanges = true)
        quiet()

        assertSame(inFirst, shownEntry(), "the diff waits for the commit's own files")
        assertNull(controller.missing.value, "and doesn't claim that the file is missing")

        commitChanges.value = state(second, listOf(modified("README.md")))

        eventually("the placeholder, from the commit's own files") { controller.missing.value != null }
    }

    @Test
    fun `a state of another commit is not the selected commit's`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)

        // The selection moved, and the pane still holds the first commit's final state
        selectedItem.value = item(second)
        quiet()

        assertSame(inFirst, shownEntry())
        assertNull(controller.missing.value)
    }

    @Test
    fun `a commit whose files are loading is waited for`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        selectedItem.value = item(second)
        commitChanges.value = state(second, emptyList(), isLoading = true)
        quiet()

        assertSame(inFirst, shownEntry())
        assertNull(controller.missing.value)
    }

    @Test
    fun `stepping past a commit before its files arrive follows the one that is selected`() {
        val inFirst = modified("src/Log.kt")
        val inThird = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        selectedItem.value = item(second) // its files never arrive: the selection moved on
        select(third, inThird)

        eventually("the third commit's entry") { shownEntry() === inThird }
        assertNull(controller.missing.value)
    }

    @Test
    fun `the same commit's files loading again does not select the file again`() {
        val inFirst = modified("src/Log.kt")
        val inSecond = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, inSecond)
        eventually("the second commit's entry") { shownEntry() === inSecond }

        // A refresh gives the commit new entries. Selecting one would load the same diff twice.
        commitChanges.value = state(second, listOf(modified("src/Log.kt")))
        quiet()

        assertSame(inSecond, shownEntry())
    }

    @Test
    fun `reloading the files of the commit that a file was opened from does not select it again`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)

        // A refresh gives the commit new entries, and the commit pane sends them as its state
        commitChanges.value = state(first, listOf(modified("src/Log.kt")))
        quiet()

        assertSame(inFirst, shownEntry(), "the file that the user opened stays as it is")
        assertNull(controller.missing.value)
    }

    @Test
    fun `the same commit selected again as a branch or a tag keeps the diff`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(first, modified("src/Log.kt"), item = SelectedItem.TagItem(
            dev.app.leaf.domain.models.Tag(commitHash = first.hash, hash = first.hash, name = "refs/tags/v1"),
            first,
        ))
        quiet()

        assertSame(inFirst, shownEntry())
        assertNull(controller.missing.value)
    }

    @Test
    fun `a diff that the user did not open from the commit's list is not followed`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        diffSelected.value = DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(inFirst)))
        select(second, modified("README.md"))
        quiet()

        assertNull(controller.missing.value)
    }

    @Test
    fun `a closed diff is forgotten, and the next commit that has the file shows nothing`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        diffSelected.value = null
        controller.forget()
        select(second, modified("src/Log.kt"))
        quiet()

        assertNull(diffSelected.value)
        assertNull(controller.missing.value)
    }

    @Test
    fun `forgetting while the placeholder is up takes it away`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, modified("README.md"))
        eventually("the placeholder") { controller.missing.value != null }

        controller.forget()

        assertNull(controller.missing.value)
    }

    @Test
    fun `opening a file from the commit that stands in for the diff replaces the placeholder`() {
        val inFirst = modified("src/Log.kt")
        val other = modified("README.md")

        select(first, inFirst)
        open(inFirst)
        select(second, other)
        eventually("the placeholder") { controller.missing.value != null }

        open(other)

        assertNull(controller.missing.value)
        assertSame(other, shownEntry())

        // And it is the README that the next commit's diff follows
        val next = modified("README.md")
        select(third, next)
        eventually("the README's diff") { shownEntry() === next }
    }

    @Test
    fun `a selection of several files or none is not pinned`() {
        select(first, modified("a"), modified("b"))
        controller.pin(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(modified("a")), DiffType.CommitDiff(modified("b")))))
        controller.pin(DiffSelected.CommitedChanges(emptySet()))
        select(second, modified("c"))
        quiet()

        assertNull(controller.missing.value)
        assertNull(diffSelected.value)
    }

    @Test
    fun `a file opened while no commit is selected is not pinned`() {
        selectedItem.value = SelectedItem.UncommittedChanges
        controller.pin(DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(modified("src/Log.kt")))))
        select(first, modified("README.md"))
        quiet()

        assertNull(controller.missing.value)
    }

    @Test
    fun `the uncommitted changes close a commit's diff and the file is forgotten`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        selectedItem.value = SelectedItem.UncommittedChanges

        eventually("the diff closes") { diffSelected.value == null }

        select(second, modified("src/Log.kt"))
        quiet()

        assertNull(diffSelected.value, "the commit after them shows no diff")
        assertNull(controller.missing.value)
    }

    @Test
    fun `the uncommitted changes take the placeholder away`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, modified("README.md"))
        eventually("the placeholder") { controller.missing.value != null }

        selectedItem.value = SelectedItem.UncommittedChanges

        eventually("the placeholder goes") { controller.missing.value == null }
    }

    @Test
    fun `a diff of an uncommitted file closes when a commit is selected`() {
        selectedItem.value = SelectedItem.UncommittedChanges
        diffSelected.value = DiffSelected.UncommittedChanges(EntryType.UNSTAGED, emptySet())

        select(first, modified("src/Log.kt"))

        eventually("the uncommitted diff closes") { diffSelected.value == null }
    }

    @Test
    fun `a diff of an uncommitted file stays while the uncommitted changes stay selected`() {
        val selection = DiffSelected.UncommittedChanges(EntryType.UNSTAGED, emptySet())

        selectedItem.value = SelectedItem.UncommittedChanges
        diffSelected.value = selection
        commitChanges.value = null
        quiet()

        assertSame(selection, diffSelected.value)
    }

    @Test
    fun `a diff opened from the review closes with the review and does not follow commits`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)

        // The review opens: nothing is selected, and its file's diff is selected as the review does
        selectedItem.value = SelectedItem.None
        val reviewed = DiffSelected.CommitedChanges(setOf(DiffType.CommitDiff(modified("src/Other.kt"))))
        diffSelected.value = reviewed
        quiet()

        assertSame(reviewed, diffSelected.value, "the diff stays while the review shows")

        // The review closes and selects what was selected before
        select(first, inFirst)

        eventually("the review's diff closes") { diffSelected.value == null }
        assertNull(controller.missing.value)
    }

    @Test
    fun `the log hidden, a commit without the file closes the diff and leaves no placeholder`() {
        val inFirst = modified("src/Log.kt")

        keepsLog.value = false
        select(first, inFirst)
        open(inFirst)
        select(second, modified("README.md"))

        eventually("the diff closes") { diffSelected.value == null }
        assertNull(controller.missing.value)

        // Nothing is pinned any more
        select(third, modified("src/Log.kt"))
        quiet()
        assertNull(diffSelected.value)
    }

    @Test
    fun `the log hidden, a commit with the file still shows it`() {
        val inFirst = modified("src/Log.kt")
        val inSecond = modified("src/Log.kt")

        keepsLog.value = false
        select(first, inFirst)
        open(inFirst)
        select(second, inSecond)

        eventually("the second commit's entry") { shownEntry() === inSecond }
    }

    @Test
    fun `hiding the log takes the placeholder away, as nothing would be left to step with`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        select(second, modified("README.md"))
        eventually("the placeholder") { controller.missing.value != null }

        keepsLog.value = false

        eventually("the placeholder goes") { controller.missing.value == null }
    }

    @Test
    fun `a commit whose files could not be read says so, rather than that the file is not changed`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)
        selectedItem.value = item(second)
        commitChanges.value = state(second, emptyList(), error = GenericError("The commit could not be read"))

        eventually("the placeholder") { controller.missing.value != null }
        assertTrue(controller.missing.value!!.unreadable)
        assertEquals(second, controller.missing.value!!.commit)
    }

    @Test
    fun `a commit without the file, and the file back in the one after, many times over`() {
        val inFirst = modified("src/Log.kt")

        select(first, inFirst)
        open(inFirst)

        repeat(50) { n ->
            select(commit("without$n"), modified("README.md"))
            eventually("the placeholder at $n") { controller.missing.value?.commit?.hash == commit("without$n").hash }
            assertNull(diffSelected.value)

            val back = modified("src/Log.kt")
            select(commit("with$n"), back)
            eventually("the diff at $n") { shownEntry() === back }
            assertFalse(controller.missing.value != null)
        }

        assertNotNull(shownEntry())
    }
}
