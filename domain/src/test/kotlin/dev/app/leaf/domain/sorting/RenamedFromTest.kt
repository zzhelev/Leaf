// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.extensions.renamedFrom
import org.eclipse.jgit.diff.DiffEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenamedFromTest {
    private fun label(oldPath: String, newPath: String) = RenamedFrom(oldPath, 90).label(newPath)

    @Test
    fun `a file renamed in its folder shows the name that it had`() {
        assertEquals("Lexer.kt", label("src/parse/Lexer.kt", "src/parse/Parser.kt"))
        assertEquals("a.txt", label("a.txt", "b.txt"))
    }

    @Test
    fun `a file moved to another folder under its name shows the folders that differ`() {
        assertEquals("old/", label("src/ui/old/Button.kt", "src/ui/components/Button.kt").removePrefix("\u2026/"))
        assertEquals("\u2026/legacy/", label("app/ui/legacy/Button.kt", "app/ui/components/Button.kt"))
        assertEquals("a/b/", label("a/b/Button.kt", "c/d/Button.kt"))
        // Folders at the end that both have aren't shown either
        assertEquals("\u2026/old/", label("app/old/ui/Button.kt", "app/new/ui/Button.kt"))
    }

    @Test
    fun `a file moved up out of a folder shows that folder`() {
        assertEquals("\u2026/ui/", label("src/ui/Button.kt", "src/Button.kt"))
    }

    @Test
    fun `a file that changed both shows the old folders that differ with its old name`() {
        assertEquals("\u2026/parse/Lex.kt", label("src/parse/Lex.kt", "src/lexer/Tokens.kt"))
        assertEquals("parse/Lex.kt", label("parse/Lex.kt", "lexer/Tokens.kt"))
    }

    @Test
    fun `the whole old path is shown when nothing of its folder would be left`() {
        // Into a folder below its own, or from the top folder
        assertEquals("src/Button.kt", label("src/Button.kt", "src/ui/Button.kt"))
        assertEquals("Button.kt", label("Button.kt", "src/Button.kt"))
        assertEquals("Lex.kt", label("Lex.kt", "src/Tokens.kt"))
    }

    @Test
    fun `only a score of 100 means that the content didn't change`() {
        assertTrue(RenamedFrom("a", 100).isContentUnchanged)
        assertFalse(RenamedFrom("a", 99).isContentUnchanged)
    }

    @Test
    fun `a renamed entry says where it came from, and any other change says nothing`() {
        val rename = Entry(DiffEntry.ChangeType.RENAME, oldPath = "old/a.txt", newPath = "new/a.txt", score = 87)

        assertEquals(RenamedFrom("old/a.txt", 87), rename.renamedFrom)
        assertNull(Entry(DiffEntry.ChangeType.MODIFY, "a.txt", "a.txt", 0).renamedFrom)
        assertNull(Entry(DiffEntry.ChangeType.ADD, DiffEntry.DEV_NULL, "a.txt", 0).renamedFrom)
        assertNull(Entry(DiffEntry.ChangeType.DELETE, "a.txt", DiffEntry.DEV_NULL, 0).renamedFrom)
    }

    private class Entry(type: ChangeType, oldPath: String, newPath: String, score: Int) : DiffEntry() {
        init {
            this.changeType = type
            this.oldPath = oldPath
            this.newPath = newPath
            this.score = score
        }
    }
}
