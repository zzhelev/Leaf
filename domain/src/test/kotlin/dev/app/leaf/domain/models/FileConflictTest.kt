// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import dev.app.leaf.domain.models.ConflictVersion.FILE
import dev.app.leaf.domain.models.ConflictVersion.SUBMODULE
import dev.app.leaf.domain.models.ConflictVersion.SYMLINK
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileConflictTest {
    @Test
    fun `the merge tool takes a conflict between two files`() {
        assertEquals(null, mergeToolRefusal("src/notes.txt", FileConflict(FILE, FILE)))
        assertEquals(null, mergeToolRefusal("my notes.txt", FileConflict(FILE, FILE)))
    }

    @Test
    fun `the merge tool doesn't take a deletion, a symbolic link or a submodule on either side`() {
        val otherThanTwoFiles = listOf(
            FileConflict(FILE, null),
            FileConflict(null, FILE),
            FileConflict(SYMLINK, FILE),
            FileConflict(FILE, SYMLINK),
            FileConflict(SUBMODULE, SUBMODULE),
            FileConflict(null, null),
        )

        for (conflict in otherThanTwoFiles) {
            assertEquals(MergeToolRefusal.NOT_TWO_FILES, mergeToolRefusal("notes.txt", conflict), "$conflict")
        }
    }

    @Test
    fun `the merge tool doesn't take a name that the shell would expand`() {
        for (path in listOf("[ab].txt", "dir/a*.txt", "what?.md", "d[1]/notes.txt")) {
            assertEquals(MergeToolRefusal.FILE_NAME, mergeToolRefusal(path, FileConflict(FILE, FILE)), path)
        }
    }

    @Test
    fun `a status entry opens in the merge tool only with a conflict that it takes`() {
        assertTrue(StatusEntry("a.txt", StatusType.CONFLICTING, conflict = FileConflict(FILE, FILE)).canOpenInMergeTool)
        assertFalse(StatusEntry("a.txt", StatusType.CONFLICTING, conflict = FileConflict(FILE, null)).canOpenInMergeTool)
        assertFalse(StatusEntry("a.txt", StatusType.MODIFIED).canOpenInMergeTool)
    }

    @Test
    fun `a side's version`() {
        val conflict = FileConflict(ours = FILE, theirs = null)

        assertEquals(FILE, conflict.version(ConflictSide.OURS))
        assertEquals(null, conflict.version(ConflictSide.THEIRS))
    }
}
