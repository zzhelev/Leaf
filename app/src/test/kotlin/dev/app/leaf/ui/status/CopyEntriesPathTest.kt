// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

class CopyEntriesPathTest {
    @TempDir
    lateinit var temp: File

    private val entries = listOf(
        StatusEntry("src/A.kt", StatusType.MODIFIED),
        StatusEntry("B.txt", StatusType.ADDED),
    )

    private fun copied(repositoryPath: String?, relative: Boolean): String? {
        val clipboard = RecordingClipboard()
        runBlocking { copyEntriesPath(clipboard, entries, relative, repositoryPath) }
        return clipboard.text
    }

    @Test
    fun `absolute paths leave out the git dir`(): Unit {
        val repo = File(temp, "repo").apply { mkdirs() }

        assertEquals(
            "$repo/src/A.kt\n$repo/B.txt",
            copied(File(repo, ".git").path, relative = false),
        )
    }

    @Test
    fun `absolute paths of a linked worktree name its own folder`(): Unit {
        val worktree = File(temp, "wt").apply { mkdirs() }
        val gitDir = File(temp, "repo/.git/worktrees/wt").apply { mkdirs() }
        File(gitDir, "commondir").writeText("../..\n")
        File(gitDir, "gitdir").writeText("${File(worktree, ".git").path}\n")

        assertEquals("$worktree/B.txt", copied(gitDir.path, relative = false)?.lines()?.last())
    }

    @Test
    fun `relative paths are the entries' own`(): Unit {
        assertEquals("src/A.kt\nB.txt", copied("/x/.git", relative = true))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private class RecordingClipboard : Clipboard {
    @Volatile
    var text: String? = null

    override val nativeClipboard: NativeClipboard get() = error("Not used")

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        text = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
    }
}
