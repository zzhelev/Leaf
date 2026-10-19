// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.EntryContent
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.sorting.RenamedFrom
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What the diff pane says about a file that was renamed: where it came from, and that a rename may change nothing. */
class RenamedDiffTest {
    @Test
    fun `a rename that changed nothing has no hunks to show, so the pane says where the file came from`() {
        val result = DiffResult.Text(entry(DiffEntry.ChangeType.RENAME, score = 100), emptyList())

        assertEquals(RenamedFrom("old/Button.kt", 100), result.renamedWithoutChanges())
        assertEquals(
            RenamedFrom("old/Button.kt", 100),
            DiffResult.TextSplit(entry(DiffEntry.ChangeType.RENAME, score = 100), emptyList()).renamedWithoutChanges(),
        )
    }

    @Test
    fun `a rename with changes, and every other empty diff, shows its diff`() {
        val hunk = Hunk("@@ -1 +1 @@", listOf(Line("x", 0, 0, LineType.ADDED)), RawText(ByteArray(0)), RawText(ByteArray(0)))
        val rename = entry(DiffEntry.ChangeType.RENAME, score = 90)

        assertNull(DiffResult.Text(rename, listOf(hunk)).renamedWithoutChanges())
        assertNull(DiffResult.Text(entry(DiffEntry.ChangeType.MODIFY, score = 0), emptyList()).renamedWithoutChanges())
        assertNull(DiffResult.NonText(rename, EntryContent.Binary, EntryContent.Binary).renamedWithoutChanges())
    }

    @Test
    fun `the header names the old path, and how much is alike unless it's all of it`(): Unit = runBlocking(Dispatchers.Swing) {
        assertEquals(
            listOf("\u2190 old/Button.kt"),
            texts { Row { RenamedFromHeader(RenamedFrom("old/Button.kt", 100)) } },
        )
        assertEquals(
            listOf("\u2190 old/Button.kt", "\u00B7 92% similar"),
            texts { Row { RenamedFromHeader(RenamedFrom("old/Button.kt", 92)) } },
        )
    }

    @Test
    fun `the body of a rename without changes says so`(): Unit = runBlocking(Dispatchers.Swing) {
        assertEquals(
            listOf("Renamed from old/Button.kt. The content is unchanged."),
            texts { RenamedWithoutChanges(RenamedFrom("old/Button.kt", 100)) },
        )
    }

    private fun texts(content: @Composable () -> Unit): List<String> {
        val scene = ImageComposeScene(width = 600, height = 120, density = Density(1f)) { AppTheme { content() } }

        try {
            scene.render()

            return scene.semanticsOwners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        } finally {
            scene.close()
        }
    }

    private fun entry(type: DiffEntry.ChangeType, score: Int): DiffEntry = object : DiffEntry() {
        init {
            changeType = type
            oldPath = "old/Button.kt"
            newPath = "new/Button.kt"
            this.score = score
        }
    }
}
