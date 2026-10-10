// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.services

import dev.app.leaf.domain.repositories.AppSettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Discarding a hunk or a line asks first until the user says otherwise (fork-only). */
class ConfirmHunkAndLineDiscardsSettingTest {
    @Test
    fun `asks when the settings file has no value`(): Unit = runBlocking {
        assertEquals(true, settingsWith(null).confirmHunkAndLineDiscards.first())
    }

    @Test
    fun `keeps the value the user chose`(): Unit = runBlocking {
        assertEquals(false, settingsWith(false).confirmHunkAndLineDiscards.first())
        assertEquals(true, settingsWith(true).confirmHunkAndLineDiscards.first())
    }

    private fun settingsWith(stored: Boolean?): AppSettingsService {
        val repository = mockk<AppSettingsRepository> {
            every { confirmHunkAndLineDiscards } returns flowOf(stored)
        }

        return AppSettingsService(repository)
    }
}
