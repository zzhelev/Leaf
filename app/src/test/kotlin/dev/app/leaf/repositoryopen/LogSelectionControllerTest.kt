// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.log.LogSelection
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.SelectedItem
import dev.app.leaf.domain.repositories.CloseableView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private fun commit(hash: String) = Commit(hash, "Subject of $hash", someone, someone, 1_760_000_000, emptyList())

private fun commitItem(hash: String) = SelectedItem.CommitItem(commit(hash), isStash = false, scrollToItem = false)

/** The range of commits selected in the log with Shift (fork-only, step A2 of `docs/fork/squash-and-rebase.md`). */
class LogSelectionControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rows = MutableStateFlow(listOf("e", "d", "c", "b", "a"))
    private val selectedItem = MutableStateFlow<SelectedItem>(SelectedItem.None)
    private val anchorsSelected = CopyOnWriteArrayList<String>()
    private val rangesStarted = AtomicInteger()
    private val closeableViews = MutableStateFlow(emptySet<CloseableView>())

    private val controller = LogSelectionController(
        scope = scope,
        logRows = rows,
        selectedItem = selectedItem,
        selectAnchor = { hash ->
            anchorsSelected += hash
            selectedItem.value = commitItem(hash)
        },
        onRangeStarted = { rangesStarted.incrementAndGet() },
        addCloseableView = { view -> closeableViews.value += view },
        removeCloseableView = { view -> closeableViews.value -= view },
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitSelection(expected: LogSelection) = withTimeout(5.seconds) {
        controller.selection.first { it == expected }
    }

    /** Waits for the collectors to have seen what changed, for checks that something stayed as it was. */
    private suspend fun settle() = delay(200)

    @Test
    fun `Shift-click selects the rows from the selected commit, which stays selected`(): Unit = runBlocking {
        selectedItem.value = commitItem("d")

        assertTrue(controller.extendTo("b"))

        assertEquals(LogSelection("d", "b"), controller.selection.value)
        assertEquals(listOf("d", "c", "b"), controller.selection.value.commitsIn(rows.value))
        assertEquals(commitItem("d"), selectedItem.value)
        assertEquals(1, rangesStarted.get())

        // Another Shift-click moves the same range's end, and starts nothing new
        assertTrue(controller.extendTo("e"))
        settle()

        assertEquals(LogSelection("d", "e"), controller.selection.value)
        assertEquals(1, rangesStarted.get())
    }

    @Test
    fun `a range from a branch picked in the side panel starts from its commit, as the log's selection`(): Unit =
        runBlocking {
            selectedItem.value = SelectedItem.BranchItem(Branch("c", "refs/heads/feat", true), commit("c"))

            assertTrue(controller.extendTo("a"))
            settle()

            assertEquals(LogSelection("c", "a"), controller.selection.value)
            assertEquals(listOf("c"), anchorsSelected)
            assertEquals(commitItem("c"), selectedItem.value)
        }

    @Test
    fun `Shift-click with no commit selected, on the anchor, or on a row the log hasn't is no range`(): Unit =
        runBlocking {
            assertFalse(controller.extendTo("b"))

            selectedItem.value = SelectedItem.UncommittedChanges
            assertFalse(controller.extendTo("b"))

            selectedItem.value = commitItem("d")
            assertFalse(controller.extendTo("d"))
            assertFalse(controller.extendTo("z"))

            // A branch whose commit the log hasn't loaded
            selectedItem.value = SelectedItem.BranchItem(Branch("y", "refs/heads/old", true), commit("y"))
            assertFalse(controller.extendTo("b"))
            selectedItem.value = commitItem("d")

            assertEquals(LogSelection(), controller.selection.value)
            assertEquals(0, rangesStarted.get())

            // On the anchor of a range, it ends the range
            assertTrue(controller.extendTo("b"))
            assertFalse(controller.extendTo("d"))
            assertEquals(LogSelection(), controller.selection.value)
        }

    @Test
    fun `Shift and the arrows move the lead, give its row, and stop at the ends`(): Unit = runBlocking {
        selectedItem.value = commitItem("d")

        assertEquals(2, controller.step(1))
        assertEquals(LogSelection("d", "c"), controller.selection.value)
        assertEquals(3, controller.step(1))
        assertEquals("b", controller.lead)

        // Back to the anchor: no range, and the anchor's row
        assertEquals(2, controller.step(-1))
        assertEquals(1, controller.step(-1))
        assertEquals(LogSelection(), controller.selection.value)
        assertNull(controller.lead)

        assertEquals(0, controller.step(-1))
        assertNull(controller.step(-1))
        assertEquals(LogSelection("d", "e"), controller.selection.value)
    }

    @Test
    fun `the arrows need a commit selected that the log has`(): Unit = runBlocking {
        assertNull(controller.step(1))

        selectedItem.value = SelectedItem.UncommittedChanges
        assertNull(controller.step(1))

        selectedItem.value = commitItem("z")
        assertNull(controller.step(1))
        assertEquals(LogSelection(), controller.selection.value)
    }

    @Test
    fun `selecting anything else ends the range`(): Unit = runBlocking {
        for (other in listOf(commitItem("a"), SelectedItem.UncommittedChanges, SelectedItem.None)) {
            selectedItem.value = commitItem("d")
            controller.extendTo("b")

            selectedItem.value = other

            awaitSelection(LogSelection())
        }

        // A branch of the same commit, picked in the side panel, is another selection too
        selectedItem.value = commitItem("d")
        controller.extendTo("b")
        selectedItem.value = SelectedItem.BranchItem(Branch("d", "refs/heads/feat", true), commit("d"))

        awaitSelection(LogSelection())
    }

    @Test
    fun `the anchor selected again keeps the range`(): Unit = runBlocking {
        selectedItem.value = commitItem("d")
        controller.extendTo("b")

        // As the log selects a commit, with another scroll
        selectedItem.value = SelectedItem.CommitItem(commit("d"), isStash = false, scrollToItem = true)
        settle()

        assertEquals(LogSelection("d", "b"), controller.selection.value)
    }

    @Test
    fun `a refresh keeps the range while the log has both its ends`(): Unit = runBlocking {
        selectedItem.value = commitItem("d")
        controller.extendTo("b")

        // A new commit at the top
        rows.value = listOf("f", "e", "d", "c", "b", "a")
        settle()
        assertEquals(LogSelection("d", "b"), controller.selection.value)

        // A rewrite that took the lead out
        rows.value = listOf("x", "d", "a")
        awaitSelection(LogSelection())
    }

    @Test
    fun `a range made while the collectors start isn't ended by what they read before`(): Unit = runBlocking {
        repeat(200) { round ->
            val roundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val roundRows = MutableStateFlow(listOf("e", "d", "c"))
            val roundSelected = MutableStateFlow<SelectedItem>(SelectedItem.None)
            val roundController = LogSelectionController(
                scope = roundScope,
                logRows = roundRows,
                selectedItem = roundSelected,
                selectAnchor = { roundSelected.value = commitItem(it) },
                onRangeStarted = {},
                addCloseableView = {},
                removeCloseableView = {},
            )

            // Right away, while the collectors may still hold "nothing selected" and the first log
            roundSelected.value = commitItem("d")
            roundRows.value = listOf("f", "e", "d", "c")
            assertTrue(roundController.extendTo("f"))
            delay(10)

            assertEquals(LogSelection("d", "f"), roundController.selection.value, "round $round")
            roundScope.cancel()
        }
    }

    @Test
    fun `Esc ends the range while there is one`(): Unit = runBlocking {
        selectedItem.value = commitItem("d")
        controller.extendTo("b")

        withTimeout(5.seconds) { closeableViews.first { CloseableView.LOG_SELECTION in it } }

        controller.clear()

        assertEquals(LogSelection(), controller.selection.value)
        withTimeout(5.seconds) { closeableViews.first { CloseableView.LOG_SELECTION !in it } }
        assertEquals(commitItem("d"), selectedItem.value)
    }
}
