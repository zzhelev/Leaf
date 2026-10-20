// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.pollSystemTheme
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalComposeUiApi::class)
class SystemThemePollingTest {
    private var shipped = false

    @BeforeEach
    fun remember() {
        shipped = ComposeUiFlags.pollSystemTheme
        ComposeUiFlags.pollSystemTheme = false
    }

    @AfterEach
    fun putBack() {
        ComposeUiFlags.pollSystemTheme = shipped
    }

    @Test
    fun `Linux polls the system's setting`() {
        followSystemThemeOnLinux(OS.LINUX)

        assertTrue(ComposeUiFlags.pollSystemTheme)
    }

    @Test
    fun `the other systems keep what Compose ships`() {
        for (os in listOf(OS.MAC, OS.WINDOWS, OS.UNKNOWN)) {
            followSystemThemeOnLinux(os)

            assertFalse(ComposeUiFlags.pollSystemTheme, "$os")
        }
    }

    /**
     * Compose Desktop 1.12.1 polls on macOS and Windows and not on Linux, which is why Leaf turns it on there. If a
     * Compose update changes that, this says so, and the System theme needs a second look.
     */
    @Test
    fun `Compose polls on macOS and Windows and not on Linux, as of 1_12_1`() {
        // `shipped` is the flag as Compose set it, before @BeforeEach turned it off
        when (val os = currentOs) {
            OS.MAC, OS.WINDOWS -> assertTrue(shipped, "$os")
            OS.LINUX -> assertFalse(shipped, "$os")
            OS.UNKNOWN -> Unit
        }
    }
}
