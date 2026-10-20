// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.errors.RestoreFileError
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.ConflictVersion.FILE as FILE_VERSION
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.Line
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.NestedRepository
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.restore.CurrentFile
import dev.app.leaf.domain.restore.FileRestorePlan
import dev.app.leaf.domain.sorting.DiscardAllPlan
import dev.app.leaf.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val STOP_ASKING = "Don't ask again for hunks and lines"
private const val FILE = "src/app/Foo.kt"
private const val DISCARD_AND_DELETE = "Discard and delete"

private val addedLine = Line("    println(\"debug\")\n", 4, 5, LineType.ADDED)
private val removedLine = Line("    val old = 1\n", 6, 6, LineType.REMOVED)
private val contextLine = Line("fun foo() {\n", 3, 3, LineType.CONTEXT)

/** The dialog only reads the header and the lines, not the texts that the patch comes from. */
private fun hunk(header: String, vararg lines: Line) =
    Hunk(header, lines.toList(), RawText.EMPTY_TEXT, RawText.EMPTY_TEXT)

/** The confirmations of discards, and the "Don't ask again" of hunks and lines (fork-only). */
class ConfirmActionDialogTest {
    @Test
    fun `deleting a nested repository says so and asks for Delete repository`(): Unit = runBlocking(Dispatchers.Swing) {
        val entry = StatusEntry(
            "tools/vendor/some-very-long-nested-repository-name",
            StatusType.ADDED,
            nestedRepository = NestedRepository.REPOSITORY,
        )

        DialogScene(ConfirmableAction.DeleteFile(entry)).use { dialog ->
            assertTrue(dialog.texts().any { it.contains("another git repository") })
            assertTrue(dialog.isClickable("Delete repository"))
            assertFalse(dialog.isClickable("Delete"))

            dialog.click("Delete repository")

            assertEquals(listOf("confirm"), dialog.calls)
        }
    }

    @Test
    fun `deleting an untracked file keeps the usual dialog`(): Unit = runBlocking(Dispatchers.Swing) {
        val entry = StatusEntry("notes.txt", StatusType.ADDED)

        DialogScene(ConfirmableAction.DeleteFile(entry)).use { dialog ->
            assertTrue(dialog.texts().any { it.contains("can't be restored") })
            assertTrue(dialog.isClickable("Delete"))
            assertFalse(dialog.texts().any { it.contains("another git repository") })
        }
    }

    @Test
    fun `discarding with the box checked stops asking, then discards`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = true).use { dialog ->
            dialog.click(STOP_ASKING)
            dialog.click("Discard")

            assertEquals(listOf("stop asking", "confirm"), dialog.calls)
        }
    }

    @Test
    fun `discarding with the box unchecked keeps asking`(): Unit = runBlocking(Dispatchers.Swing) {
        val discarded = hunk("@@ -4,3 +4,4 @@", contextLine, addedLine)

        DialogScene(ConfirmableAction.DiscardHunk(FILE, discarded), canStopAsking = true).use { dialog ->
            assertTrue(STOP_ASKING in dialog.texts())

            dialog.click("Discard")

            assertEquals(listOf("confirm"), dialog.calls)
        }
    }

    @Test
    fun `checking the box twice unchecks it`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = true).use { dialog ->
            dialog.click(STOP_ASKING)
            dialog.click(STOP_ASKING)
            dialog.click("Discard")

            assertEquals(listOf("confirm"), dialog.calls)
        }
    }

    @Test
    fun `cancelling with the box checked keeps asking`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = true).use { dialog ->
            dialog.click(STOP_ASKING)
            dialog.click("Cancel")

            assertEquals(listOf("dismiss"), dialog.calls)
        }
    }

    @Test
    fun `without a way to stop asking there is no box`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine), canStopAsking = false).use { dialog ->
            assertFalse(STOP_ASKING in dialog.texts())
        }
    }

    @Test
    fun `discarding files always asks`(): Unit = runBlocking(Dispatchers.Swing) {
        val actions = listOf(
            ConfirmableAction.DiscardFile(StatusEntry(FILE, StatusType.MODIFIED, EntryType.UNSTAGED)),
            ConfirmableAction.DiscardFiles(3, EntryType.STAGED),
        )

        for (action in actions) {
            DialogScene(action, canStopAsking = true).use { dialog ->
                assertFalse(dialog.texts().any { it.startsWith("Don't ask again") }, action.toString())
            }
        }
    }

    @Test
    fun `an added line can't be restored, a removed line comes back`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardLine(FILE, addedLine)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard this added line in \"$FILE\"?" in texts, texts.toString())
            assertTrue("+ println(\"debug\")" in texts, texts.toString())
            assertTrue("It isn't staged, so it can't be restored." in texts, texts.toString())
        }

        DialogScene(ConfirmableAction.DiscardLine(FILE, removedLine)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Put this removed line back into \"$FILE\"?" in texts, texts.toString())
            assertTrue("- val old = 1" in texts, texts.toString())
            assertFalse(texts.any { it.contains("can't be restored") }, texts.toString())
        }
    }

    @Test
    fun `a hunk warns about its added lines only`(): Unit = runBlocking(Dispatchers.Swing) {
        val secondAdded = addedLine.copy(newLineNumber = 6)
        val withAdded = hunk("@@ -4,3 +4,5 @@ fun foo()\n", contextLine, addedLine, secondAdded, removedLine)

        DialogScene(ConfirmableAction.DiscardHunk(FILE, withAdded)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("@@ -4,3 +4,5 @@ fun foo()" in texts, texts.toString())
            assertTrue("Its 2 added lines aren't staged, so they can't be restored." in texts, texts.toString())
        }

        val removalsOnly = hunk("@@ -4,3 +4,2 @@", contextLine, removedLine)

        DialogScene(ConfirmableAction.DiscardHunk(FILE, removalsOnly)).use { dialog ->
            assertFalse(dialog.texts().any { it.contains("can't be restored") }, dialog.texts().toString())
        }
    }

    @Test
    fun `discarding a staged file warns that its unstaged changes go too`(): Unit = runBlocking(Dispatchers.Swing) {
        val staged = StatusEntry(FILE, StatusType.MODIFIED, EntryType.STAGED)

        DialogScene(ConfirmableAction.DiscardFile(staged)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the changes to \"$FILE\"?" in texts, texts.toString())
            assertTrue(
                "Both its staged and unstaged changes are discarded. They can't be restored." in texts,
                texts.toString(),
            )
        }

        DialogScene(ConfirmableAction.DiscardFiles(3, EntryType.UNSTAGED)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the unstaged changes to 3 files?" in texts, texts.toString())
            assertTrue("They can't be restored." in texts, texts.toString())
        }
    }

    @Test
    fun `discarding a selection says how many files with conflicts it leaves`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(ConfirmableAction.DiscardFiles(3, EntryType.UNSTAGED, keptConflicted = 1)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the unstaged changes to 3 files?" in texts, texts.toString())
            assertTrue("1 file with conflicts is left as it is." in texts, texts.toString())
        }

        DialogScene(ConfirmableAction.DiscardFiles(1, EntryType.UNSTAGED, keptConflicted = 2)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the unstaged changes to 1 file?" in texts, texts.toString())
            assertTrue("2 files with conflicts are left as they are." in texts, texts.toString())
        }

        DialogScene(ConfirmableAction.DiscardFiles(3, EntryType.UNSTAGED)).use { dialog ->
            assertFalse(dialog.texts().any { "conflicts" in it }, dialog.texts().toString())
        }
    }

    @Test
    fun `discarding the deletion of a file loses nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        val deleted = StatusEntry(FILE, StatusType.REMOVED, EntryType.UNSTAGED)

        DialogScene(ConfirmableAction.DiscardFile(deleted)).use { dialog ->
            assertTrue("Discard the unstaged changes to \"$FILE\"?" in dialog.texts())
            assertFalse(dialog.texts().any { it.contains("can't be restored") }, dialog.texts().toString())
        }
    }

    @Test
    fun `using one side names both, and what is lost`(): Unit = runBlocking(Dispatchers.Swing) {
        val sides = ConflictSides(
            ConflictOperation.MERGE,
            ConflictCommit("main", "1111111", "Main"),
            ConflictCommit("feature", "2222222", "Feature"),
        )
        val bothChanged = StatusEntry(FILE, StatusType.CONFLICTING, conflict = FileConflict(FILE_VERSION, FILE_VERSION))
        val deletedByThem = StatusEntry("kept.txt", StatusType.CONFLICTING, conflict = FileConflict(FILE_VERSION, null))

        DialogScene(ConfirmableAction.UseConflictSide(listOf(bothChanged), ConflictSide.OURS, sides)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Use ours" in texts, texts.toString())
            assertTrue("Resolve \"$FILE\" with ours (main)?" in texts, texts.toString())
            assertTrue(
                "The changes from theirs (feature), and any edits you made to the file, are lost." in texts,
                texts.toString(),
            )

            dialog.click("Resolve")
            assertEquals(listOf("confirm"), dialog.calls)
        }

        val both = listOf(bothChanged, deletedByThem)

        DialogScene(ConfirmableAction.UseConflictSide(both, ConflictSide.THEIRS, sides)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Use theirs" in texts, texts.toString())
            assertTrue("Resolve 2 files with theirs (feature)?" in texts, texts.toString())
            assertTrue(
                "The changes from ours (main), and any edits you made to the files, are lost. " +
                    "1 file is deleted, as theirs (feature) deleted it." in texts,
                texts.toString(),
            )
        }

        DialogScene(ConfirmableAction.UseConflictSide(listOf(deletedByThem), ConflictSide.THEIRS, sides)).use { dialog ->
            assertTrue(
                "The changes from ours (main), and any edits you made to the file, are lost. " +
                    "The file is deleted, as theirs (feature) deleted it." in dialog.texts(),
                dialog.texts().toString(),
            )
        }

        DialogScene(ConfirmableAction.UseConflictSide(listOf(bothChanged), ConflictSide.THEIRS, null)).use { dialog ->
            assertTrue("Resolve \"$FILE\" with theirs?" in dialog.texts(), dialog.texts().toString())
        }
    }

    /** [ConfirmActionDialog] for [action], rendered offscreen. [calls] records what the dialog called. */
    // "Discard all" (fork-only)

    private fun changed(count: Int) = (1..count).map { StatusEntry("src/file$it.kt", StatusType.MODIFIED) }
    private fun fresh(count: Int) = (1..count).map { StatusEntry("new/file$it.kt", StatusType.ADDED) }
    private fun conflicted(vararg names: String) = names.map { StatusEntry(it, StatusType.CONFLICTING) }

    private fun discardAll(
        restored: List<StatusEntry>,
        newFiles: List<StatusEntry> = emptyList(),
        conflicts: List<StatusEntry> = emptyList(),
        submodules: List<StatusEntry> = emptyList(),
        search: String? = null,
    ) = ConfirmableAction.DiscardAll(DiscardAllPlan(restored, newFiles, conflicts, submodules), search)

    @Test
    fun `discard all counts the files and says that they can't be restored`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(discardAll(changed(12))).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard all changes" in texts, texts.toString())
            assertTrue("Discard the unstaged changes to 12 files?" in texts, texts.toString())
            assertTrue("They can't be restored." in texts, texts.toString())
            assertFalse(texts.any { it.startsWith("Also delete") }, "Without new files there is no box")
            assertFalse(texts.any { it.contains("aren't in git") }, texts.toString())
            assertFalse(DISCARD_AND_DELETE in texts)
            assertTrue(dialog.isClickable("Discard"))
        }
    }

    @Test
    fun `discard all counts a single file in the singular`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(discardAll(changed(1), fresh(1))).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the unstaged changes to 1 file?" in texts, texts.toString())
            assertTrue("Also delete 1 new file" in texts, texts.toString())
        }
    }

    @Test
    fun `discard all keeps the new files unless the box is ticked`(): Unit = runBlocking(Dispatchers.Swing) {
        val action = discardAll(changed(12), fresh(9))

        DialogScene(action).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Also delete 9 new files" in texts, texts.toString())
            assertTrue("They aren't in git, so they can't be restored either." in texts, texts.toString())
            assertTrue("They can't be restored." in texts, texts.toString())
            assertFalse(action.deleteNewFiles.isChecked, "The box starts unticked")

            dialog.click("Discard")

            assertEquals(listOf("confirm"), dialog.calls)
            assertFalse(action.deleteNewFiles.isChecked, "Confirming without the box leaves the new files")
        }
    }

    @Test
    fun `ticking the box says that the new files go too`(): Unit = runBlocking(Dispatchers.Swing) {
        val action = discardAll(changed(12), fresh(9))

        DialogScene(action).use { dialog ->
            dialog.click("Also delete 9 new files")

            val texts = dialog.texts()

            assertTrue(action.deleteNewFiles.isChecked)
            assertTrue("Neither the changes nor the 9 new files can be restored." in texts, texts.toString())
            assertFalse("They can't be restored." in texts, texts.toString())
            assertTrue(DISCARD_AND_DELETE in texts, texts.toString())
            assertFalse(dialog.isClickable("Discard"), "The button reads Discard and delete now")

            dialog.click(DISCARD_AND_DELETE)

            assertEquals(listOf("confirm"), dialog.calls)
            assertTrue(action.deleteNewFiles.isChecked)
        }
    }

    @Test
    fun `ticking the box twice goes back to keeping the new files`(): Unit = runBlocking(Dispatchers.Swing) {
        val action = discardAll(changed(2), fresh(3))

        DialogScene(action).use { dialog ->
            dialog.click("Also delete 3 new files")
            dialog.click("Also delete 3 new files")

            assertFalse(action.deleteNewFiles.isChecked)
            assertTrue("They can't be restored." in dialog.texts())
            assertTrue(dialog.isClickable("Discard"))
        }
    }

    @Test
    fun `cancelling with the box ticked confirms nothing`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(discardAll(changed(2), fresh(3))).use { dialog ->
            dialog.click("Also delete 3 new files")
            dialog.click("Cancel")

            assertEquals(listOf("dismiss"), dialog.calls)
        }
    }

    @Test
    fun `every discard all starts with the box unticked`() {
        val first = discardAll(changed(1), fresh(1))
        first.deleteNewFiles.isChecked = true

        assertFalse(discardAll(changed(1), fresh(1)).deleteNewFiles.isChecked)
        assertFalse(
            ConfirmableAction.DiscardAll(DiscardAllPlan(changed(1), fresh(1), emptyList()), null)
                .deleteNewFiles.isChecked,
        )
    }

    @Test
    fun `discard all with only new files does nothing until the box is ticked`(): Unit = runBlocking(Dispatchers.Swing) {
        val action = discardAll(restored = emptyList(), newFiles = fresh(4))

        DialogScene(action).use { dialog ->
            val texts = dialog.texts()

            assertTrue("No file has changes to restore." in texts, texts.toString())
            assertFalse(texts.any { it.contains("can't be restored") && !it.contains("aren't in git") }, texts.toString())
            assertFalse(dialog.isClickable("Discard"), "Nothing to discard without the box")

            dialog.click("Also delete 4 new files")

            assertTrue("The 4 new files can't be restored." in dialog.texts(), dialog.texts().toString())
            assertTrue(dialog.isClickable(DISCARD_AND_DELETE))
        }
    }

    @Test
    fun `discard all of a search says what it matches`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(discardAll(changed(3), search = "parser")).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Discard the unstaged changes to 3 files matching \"parser\"?" in texts, texts.toString())
        }

        DialogScene(discardAll(changed(1), search = "parser")).use { dialog ->
            assertTrue(
                "Discard the unstaged changes to 1 file matching \"parser\"?" in dialog.texts(),
                dialog.texts().toString(),
            )
        }
    }

    @Test
    fun `discard all names the files with conflicts that it leaves`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(discardAll(changed(2), conflicts = conflicted("src/Merge.kt", "README.md"))).use { dialog ->
            val texts = dialog.texts()

            assertTrue("2 files with conflicts are left as they are." in texts, texts.toString())
            assertTrue("src/Merge.kt, README.md" in texts, texts.toString())
        }

        DialogScene(discardAll(changed(2), conflicts = conflicted("only.kt"))).use { dialog ->
            assertTrue("1 file with conflicts is left as it is." in dialog.texts(), dialog.texts().toString())
            assertTrue("only.kt" in dialog.texts())
        }
    }

    @Test
    fun `discard all names five files with conflicts and counts the rest`(): Unit = runBlocking(Dispatchers.Swing) {
        val names = (1..8).map { "c$it.kt" }

        DialogScene(discardAll(changed(2), conflicts = conflicted(*names.toTypedArray()))).use { dialog ->
            val texts = dialog.texts()

            assertTrue("8 files with conflicts are left as they are." in texts, texts.toString())
            assertTrue("c1.kt, c2.kt, c3.kt, c4.kt, c5.kt and 3 more" in texts, texts.toString())
            assertFalse(texts.any { it.contains("c6.kt") }, texts.toString())
        }
    }

    @Test
    fun `discard all says which submodules it leaves`(): Unit = runBlocking(Dispatchers.Swing) {
        val one = listOf(StatusEntry("libs/core", StatusType.MODIFIED, isSubmodule = true))
        val two = one + StatusEntry("libs/ui", StatusType.REMOVED, isSubmodule = true)

        DialogScene(discardAll(changed(2), submodules = one)).use { dialog ->
            assertTrue("1 submodule that moved on is left as it is." in dialog.texts(), dialog.texts().toString())
        }

        DialogScene(discardAll(changed(2), submodules = two)).use { dialog ->
            assertTrue("2 submodules that moved on are left as they are." in dialog.texts(), dialog.texts().toString())
        }

        DialogScene(discardAll(changed(2))).use { dialog ->
            assertFalse(dialog.texts().any { it.contains("submodule") })
        }
    }

    @Test
    fun `discard all never offers to stop asking`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(discardAll(changed(2), fresh(1)), canStopAsking = true).use { dialog ->
            assertFalse(dialog.texts().any { it.startsWith("Don't ask again") }, dialog.texts().toString())
        }
    }

    @Test
    fun `restoring a file says which version, and that only the working file changes`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(restore(CurrentFile.UNCHANGED)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("Restore this version of the file?" in texts, texts.toString())
            assertTrue(
                "\"src/parser/Lexer.kt\" as it was in e4f5a6b \"Rename Lexer to Tokenizer\"." in texts,
                texts.toString(),
            )
            assertTrue("Only the file in your working tree changes. Nothing is staged or committed." in texts, texts.toString())
            assertFalse(texts.any { it.contains("will be lost") }, "A file without changes loses nothing: $texts")

            dialog.click("Restore")

            assertEquals(listOf("confirm"), dialog.calls)
        }
    }

    @Test
    fun `restoring a file with unstaged changes warns that they are lost, and that staged ones stay`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(restore(CurrentFile.UNSTAGED_CHANGES, hasStagedChanges = true)).use { dialog ->
                val texts = dialog.texts()

                assertTrue(
                    "Your unstaged changes to this file will be lost. They can't be restored." in texts,
                    texts.toString(),
                )
                assertTrue("Its staged changes stay staged." in texts, texts.toString())
            }
        }

    @Test
    fun `restoring over a new file or an ignored one warns that its content is replaced`(): Unit =
        runBlocking(Dispatchers.Swing) {
            DialogScene(restore(CurrentFile.UNTRACKED)).use { dialog ->
                assertTrue(dialog.texts().any { it.startsWith("This file isn't tracked by git.") }, dialog.texts().toString())
            }

            DialogScene(restore(CurrentFile.IGNORED)).use { dialog ->
                assertTrue(dialog.texts().any { it.startsWith("This file is ignored by git.") }, dialog.texts().toString())
            }
        }

    @Test
    fun `restoring a file that isn't there says that it is created`(): Unit = runBlocking(Dispatchers.Swing) {
        DialogScene(restore(CurrentFile.MISSING)).use { dialog ->
            val texts = dialog.texts()

            assertTrue("The file isn't in your working tree now. Restoring creates it." in texts, texts.toString())
            assertFalse(texts.any { it.contains("will be lost") }, texts.toString())
        }
    }

    @Test
    fun `a commit that has no version of the file says why, and its button does nothing`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val refusal = RestoreFileError.NotInCommit("src/parser/Lexer.kt", "e4f5a6b".padEnd(40, '0'))

            DialogScene(restore(CurrentFile.UNCHANGED, refusal = refusal)).use { dialog ->
                val texts = dialog.texts()

                assertTrue(
                    texts.any { it.startsWith("e4f5a6b has no version of \"src/parser/Lexer.kt\" to restore") },
                    texts.toString(),
                )
                assertFalse(texts.any { it.startsWith("Only the file in your working tree changes") }, texts.toString())
                assertFalse(dialog.isClickable("Restore"), "the button is disabled")
            }
        }

    @Test
    fun `a subject that is too long is cut in the subtitle`(): Unit = runBlocking(Dispatchers.Swing) {
        val long = "A subject that goes on and on about what the change does and why, far past the width"
        val action = ConfirmableAction.RestoreFileVersion(
            FILE,
            restoreCommit.copy(message = long),
            FileRestorePlan(FILE, restoreCommit.hash, CurrentFile.UNCHANGED, hasStagedChanges = false),
        )

        DialogScene(action).use { dialog ->
            assertTrue(dialog.texts().any { it.contains("A subject that goes on and on") && it.contains("…\".") }, dialog.texts().toString())
        }
    }

    private val restoreCommit = Commit(
        hash = "e4f5a6b".padEnd(40, '0'),
        message = "Rename Lexer to Tokenizer\n\nIt reads tokens now.",
        committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
        date = 1_760_000_000,
        parentsHashes = emptyList(),
    )

    private fun restore(
        current: CurrentFile,
        hasStagedChanges: Boolean = false,
        refusal: RestoreFileError? = null,
    ) = ConfirmableAction.RestoreFileVersion(
        "src/parser/Lexer.kt",
        restoreCommit,
        FileRestorePlan("src/parser/Lexer.kt", restoreCommit.hash, current, hasStagedChanges, refusal),
    )

    private class DialogScene(action: ConfirmableAction, canStopAsking: Boolean = false) : AutoCloseable {
        val calls = mutableListOf<String>()

        private val scene = ImageComposeScene(width = 800, height = 800, density = Density(1f)) {
            AppTheme {
                ConfirmActionDialog(
                    action = action,
                    onConfirm = { calls += "confirm" },
                    onDismiss = { calls += "dismiss" },
                    onStopAsking = if (canStopAsking) ({ calls += "stop asking" }) else null,
                )
            }
        }

        init {
            scene.render()
        }

        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

        /** Whether the element with the text [text] takes a click: a disabled button doesn't. */
        fun isClickable(text: String): Boolean = nodes().any { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null &&
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }

        /** Clicks the clickable element whose text is [text]. */
        fun click(text: String) {
            val node = nodes().single { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
            }

            checkNotNull(node.config[SemanticsActions.OnClick].action).invoke()
            scene.render()
        }

        /** Every node of the merged tree, where a button's text is part of the button. */
        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }
}
