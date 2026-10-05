// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.gitcli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GitVersionTest {
    @Test
    fun `parses the version output of common git builds`() {
        assertEquals(GitVersion(2, 54, 0), GitVersion.parse("git version 2.54.0 (Apple Git-157)"))
        assertEquals(GitVersion(2, 45, 1), GitVersion.parse("git version 2.45.1.windows.1"))
        assertEquals(GitVersion(2, 39, 5), GitVersion.parse("git version 2.39.5\n"))
        assertEquals(GitVersion(2, 48, 0), GitVersion.parse("git version 2.48.0-rc1"))
        assertEquals(GitVersion(2, 36, 0), GitVersion.parse("git version 2.36"))
    }

    @Test
    fun `returns null for unexpected output`() {
        assertNull(GitVersion.parse(""))
        assertNull(GitVersion.parse("hello"))
        assertNull(GitVersion.parse("version 2.40.0"))
    }

    @Test
    fun `compares major, minor and patch numerically`() {
        assertTrue(GitVersion(2, 9, 0) < GitVersion(2, 10, 0))
        assertTrue(GitVersion(2, 36, 0) < GitVersion(2, 36, 1))
        assertTrue(GitVersion(2, 99, 99) < GitVersion(3, 0, 0))
        assertTrue(GitVersion(2, 35, 9) < GitVersion.MINIMUM_SUPPORTED)
        assertEquals(0, GitVersion(2, 36, 0).compareTo(GitVersion.MINIMUM_SUPPORTED))
    }
}
