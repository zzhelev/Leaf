// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.ui.dialogs.ConfirmableAction.AbortOperation.Operation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the changes pane shows, and what holds it open although the user folded it (fork-only). */
class ChangesPaneStateTest {
    private val merging = ChangesPaneNeed.OperationInProgress(Operation.MERGE)

    @Test
    fun `an unfolded pane shows, and nothing holds it`() {
        val state = ChangesPaneState(isFolded = false)

        assertTrue(state.isShown)
        assertFalse(state.isHeld)
    }

    @Test
    fun `a folded pane is out of sight when nothing needs it`() {
        val state = ChangesPaneState(isFolded = true)

        assertFalse(state.isShown)
        assertFalse(state.isHeld)
    }

    @Test
    fun `a need shows a folded pane and holds it`() {
        val state = ChangesPaneState(isFolded = true, need = merging)

        assertTrue(state.isShown)
        assertTrue(state.isHeld)
    }

    @Test
    fun `a need holds an unfolded pane too, so the pane can't be folded while it lasts`() {
        val state = ChangesPaneState(isFolded = false, need = ChangesPaneNeed.Comparison)

        assertTrue(state.isShown)
        assertTrue(state.isHeld)
    }

    @Test
    fun `the user's fold is part of the state whatever the need, so the pane folds again when it ends`() {
        assertTrue(ChangesPaneState(isFolded = true, need = merging).isFolded)
        assertFalse(ChangesPaneState(isFolded = false, need = merging).isFolded)
        assertFalse(ChangesPaneState(isFolded = true, need = null).isShown)
    }

    @Test
    fun `every operation in progress is a need`() {
        for (operation in Operation.entries) {
            assertEquals(ChangesPaneNeed.OperationInProgress(operation), changesPaneNeed(operation, isComparing = false), operation.name)
        }
    }

    @Test
    fun `a comparison is a need`() {
        assertEquals(ChangesPaneNeed.Comparison, changesPaneNeed(operation = null, isComparing = true))
    }

    @Test
    fun `without an operation or a comparison there is no need`() {
        assertNull(changesPaneNeed(operation = null, isComparing = false))
    }

    @Test
    fun `an operation comes before a comparison, as it is the one that waits for the user`() {
        assertEquals(merging, changesPaneNeed(Operation.MERGE, isComparing = true))
    }

    private fun config(saved: Boolean): Pair<ChangesPaneConfig, AppSettingsRepository> {
        val repository = mockk<AppSettingsRepository>(relaxed = true) { every { changesPaneFolded } returns saved }

        return ChangesPaneConfig(repository) to repository
    }

    @Test
    fun `toggling folds and shows the pane while nothing needs it`() {
        val (config, repository) = config(saved = false)
        val needs = ChangesPaneNeeds()

        assertTrue(needs.toggleUnlessHeld(config))
        assertTrue(config.isFolded.value)

        assertTrue(needs.toggleUnlessHeld(config))
        assertFalse(config.isFolded.value)

        verify(exactly = 1) { repository.changesPaneFolded = true }
        verify(exactly = 1) { repository.changesPaneFolded = false }
    }

    @Test
    fun `toggling does nothing while something holds the pane, and saves nothing`() {
        for (need in listOf(merging, ChangesPaneNeed.Comparison)) {
            for (saved in listOf(false, true)) {
                val (config, repository) = config(saved)
                val needs = ChangesPaneNeeds().apply { set(need) }

                assertFalse(needs.toggleUnlessHeld(config), "$need, folded=$saved")
                assertEquals(saved, config.isFolded.value, "$need, folded=$saved")
                verify(exactly = 0) { repository.changesPaneFolded = any() }
            }
        }
    }

    @Test
    fun `toggling works again once the need ends, from the choice the user left`() {
        val (config, _) = config(saved = true)
        val needs = ChangesPaneNeeds().apply { set(merging) }

        assertFalse(needs.toggleUnlessHeld(config))

        needs.set(null)

        assertTrue(needs.toggleUnlessHeld(config))
        assertFalse(config.isFolded.value)
    }

    @Test
    fun `the tab's need is kept for whoever reads it`() {
        val needs = ChangesPaneNeeds()
        val seen = needs.need

        assertNull(seen.value)

        needs.set(merging)
        assertEquals(merging, seen.value)

        needs.set(null)
        assertNull(seen.value)
    }
}
