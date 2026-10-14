// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FileSizesTest {
    @Test
    fun `sizes are bytes and kilobytes whole, megabytes and gigabytes to one decimal, without a dot zero`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("1023 B", formatFileSize(1023))
        assertEquals("1 KB", formatFileSize(1024))
        // Rounded up: a few bytes over a kilobyte isn't one
        assertEquals("2 KB", formatFileSize(1025))
        assertEquals("48 KB", formatFileSize(48 * 1024))
        assertEquals("1 MB", formatFileSize(1024 * 1024))
        assertEquals("10 MB", formatFileSize(10L * 1024 * 1024))
        assertEquals("12.4 MB", formatFileSize((12.4 * 1024 * 1024).toLong()))
        assertEquals("1.5 GB", formatFileSize(3L * 512 * 1024 * 1024))
    }
}
