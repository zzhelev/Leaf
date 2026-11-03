// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.tabs

import dev.app.leaf.domain.worktrees.TabNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TabLookTest {
    @Test
    fun `a name is kept on one line, trimmed`() {
        assertEquals("Acme app", tabNameOf("  Acme app  "))
        assertEquals("Acme app", tabNameOf("Acme\napp"))
        assertEquals("Acme app", tabNameOf("Acme\t\r\n  app"))
        assertEquals("Acme app", tabNameOf("Acme app"))
        assertEquals("Acme app", tabNameOf("Acme\u0000app"))
        assertEquals("Ünïcödé 名前 🌿", tabNameOf("Ünïcödé 名前 🌿"))
    }

    @Test
    fun `a blank name, or the folder's, is no name`() {
        assertNull(tabNameOf(null))
        assertNull(tabNameOf(""))
        assertNull(tabNameOf(" \n\t "))
        assertNull(tabNameOf("app", folderName = "app"))
        assertNull(tabNameOf("  app ", folderName = "app"))
        assertEquals("App", tabNameOf("App", folderName = "app"))
    }

    @Test
    fun `a long name is cut between characters`() {
        assertEquals("a".repeat(MAX_TAB_NAME_LENGTH), tabNameOf("a".repeat(MAX_TAB_NAME_LENGTH + 20)))

        // Leaves are two UTF-16 units each, so a cut by units would split the last one
        val leaves = "🌿".repeat(MAX_TAB_NAME_LENGTH + 1)
        val kept = tabNameOf(leaves)!!

        assertEquals(MAX_TAB_NAME_LENGTH, kept.codePointCount(0, kept.length))
        assertEquals("🌿".repeat(MAX_TAB_NAME_LENGTH), kept)

        // No space left at the end of the cut
        assertEquals("a".repeat(MAX_TAB_NAME_LENGTH - 1), tabNameOf("a".repeat(MAX_TAB_NAME_LENGTH - 1) + " b"))
    }

    @Test
    fun `a colour is one of the lanes`() {
        assertEquals((1..TAB_COLOR_COUNT).toList(), (1..TAB_COLOR_COUNT).map { tabColorOf(it) })
        assertNull(tabColorOf(null))
        assertNull(tabColorOf(0))
        assertNull(tabColorOf(-1))
        assertNull(tabColorOf(TAB_COLOR_COUNT + 1))
    }

    @Test
    fun `the title is the name, or the repository's folder`() {
        val names = TabNames(repository = "app", worktree = "fix-login")

        assertEquals("Acme app", names.titleWith(TabLook(name = "Acme app")))
        assertEquals("app", names.titleWith(TabLook(color = 3)))
        assertEquals("app", names.titleWith(null))
    }
}
