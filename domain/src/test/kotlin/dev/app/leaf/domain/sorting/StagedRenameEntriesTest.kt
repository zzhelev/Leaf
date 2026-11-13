// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.usecases.GetSummaryFromStatusUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val RENAME = StatusEntry(
    "lib/Lexer.kt",
    StatusType.RENAMED,
    EntryType.STAGED,
    renamedFrom = RenamedFrom("src/Lexer.kt", 92),
)

/** A staged rename as the lists and the counts take it (fork-only, `docs/fork/status-renames.md`). */
class StagedRenameEntriesTest {
    @Test
    fun `a rename stands for its old path and its new one, any other entry for its own`() {
        assertEquals(listOf("src/Lexer.kt", "lib/Lexer.kt"), RENAME.paths)
        assertEquals(listOf("README.md"), StatusEntry("README.md", StatusType.MODIFIED).paths)
    }

    @Test
    fun `its row is a rename that says where it came from, as a commit's`() {
        val item = listOf(RENAME).toFileItems().single()

        assertEquals(FileChangeKind.Renamed, item.kind)
        assertEquals(RenamedFrom("src/Lexer.kt", 92), item.renamedFrom)
        assertEquals("lib/Lexer.kt", item.path)
        assertEquals(null, listOf(StatusEntry("README.md", StatusType.MODIFIED)).toFileItems().single().renamedFrom)
    }

    @Test
    fun `it counts as a changed file, and belongs with the changes`() {
        val summary = GetSummaryFromStatusUseCase()(Status(staged = listOf(RENAME)))

        assertEquals(1, summary.modifiedCount)
        assertEquals(0, summary.addedCount + summary.deletedCount)
        assertEquals(FileGroup.Changes, RENAME.fileGroup)
    }
}
