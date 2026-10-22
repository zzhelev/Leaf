// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.repositories.AppSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Whether the branches pane is folded, kept for every tab and after a restart (fork-only). */
class BranchesPaneConfigTest {
    private fun repository(saved: Boolean) = mockk<AppSettingsRepository>(relaxed = true) {
        every { branchesPaneFolded } returns saved
    }

    @Test
    fun `it starts as it was saved`() {
        assertFalse(BranchesPaneConfig(repository(saved = false)).isFolded.value)
        assertTrue(BranchesPaneConfig(repository(saved = true)).isFolded.value)
    }

    @Test
    fun `folding the pane is kept, and showing it is kept too`() {
        val repository = repository(saved = false)
        val config = BranchesPaneConfig(repository)

        config.setFolded(true)

        assertTrue(config.isFolded.value)
        verify(exactly = 1) { repository.branchesPaneFolded = true }

        config.setFolded(false)

        assertFalse(config.isFolded.value)
        verify(exactly = 1) { repository.branchesPaneFolded = false }
    }

    @Test
    fun `toggling flips it each time and keeps each choice`() {
        val repository = repository(saved = false)
        val config = BranchesPaneConfig(repository)

        config.toggle()
        assertTrue(config.isFolded.value)

        config.toggle()
        assertFalse(config.isFolded.value)

        config.toggle()
        assertTrue(config.isFolded.value)

        verify(exactly = 2) { repository.branchesPaneFolded = true }
        verify(exactly = 1) { repository.branchesPaneFolded = false }
    }

    @Test
    fun `every tab reads the same state, as the config is one object`() {
        val config = BranchesPaneConfig(repository(saved = false))
        val seenByAnotherTab = config.isFolded

        config.toggle()

        assertTrue(seenByAnotherTab.value)
    }
}
