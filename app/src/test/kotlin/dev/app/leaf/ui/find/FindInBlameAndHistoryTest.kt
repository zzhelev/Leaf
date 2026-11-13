// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package dev.app.leaf.ui.find

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.layout.Box
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import dev.app.leaf.ui.Blame
import dev.app.leaf.ui.HistoryContentLoaded
import dev.app.leaf.viewmodels.HistoryState
import dev.app.leaf.domain.models.ViewDiffResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val FIND = "Find"
private const val LINE = "Line"

/** Find, go to line and Select all in blame and in file history's diff (fork-only, upstream issues 275 and 312). */
class FindInBlameAndHistoryTest {
    @TempDir
    lateinit var dir: File

    private val fileLines = (1..300).map { if (it == 250) "val needle = 250" else "line $it" }

    private fun blame(): BlameResult = Git.init().setDirectory(dir).call().use { git ->
        File(dir, "notes.kt").writeText(fileLines.joinToString("") { "$it\n" })
        git.add().addFilepattern("notes.kt").call()
        val ident = PersonIdent("Maya Chen", "maya.chen@example.com")
        git.commit().setMessage("Notes").setSign(false).setAuthor(ident).setCommitter(ident).call()

        git.blame().setFilePath("notes.kt").call()!!
    }

    private class BlameScene(val result: BlameResult) : FindScene(width = 1000, height = 400, theme = Theme.Light) {
        init {
            viewContent = { Blame("notes.kt", result, onSelectCommit = {}, onClose = {}) }
        }

        val find: FindState get() = targets.current!!
    }

    private fun withBlame(test: suspend BlameScene.() -> Unit): Unit = runBlocking(Dispatchers.Swing) {
        BlameScene(blame()).use { scene ->
            scene.settle()
            scene.test()
        }
    }

    @Test
    fun `find in blame counts the file's matches, and scrolls to the first`() = withBlame {
        press(command(Key.F))
        type(FIND, "needle")

        assertTrue("1 of 1" in texts(), texts().toString())
        assertEquals(249, find.currentMatch?.row)
        assertTrue(texts().any { it.startsWith("val needle") }, "line 250 is composed, in sight")
    }

    @Test
    fun `go to line in blame goes to the file's line, and says how many there are`() = withBlame {
        press(goToLineKey)

        assertTrue(isFocused(LINE))
        assertTrue("of 300" in texts(), texts().toString())

        type(LINE, "200")
        press(plain(Key.Enter))

        assertEquals(199, find.goToRow)
        assertTrue("line 200" in texts(), texts().toString())
    }

    @Test
    fun `Select all and Copy in blame give the file's lines, not the authors`() = withBlame {
        click(Offset(700f, 60f))
        press(command(Key.A))
        press(command(Key.C))

        assertEquals(fileLines.joinToString("\n"), clipboard.text)
    }

    @Test
    fun `Select all in blame works with the focus that blame opens with`() = withBlame {
        press(command(Key.A))
        press(command(Key.C))

        assertEquals(fileLines.joinToString("\n"), clipboard.text)
    }

    @Test
    fun `Esc closes blame's strip first`() = withBlame {
        press(command(Key.F))
        assertTrue(find.isOpen)

        press(plain(Key.Escape))
        assertFalse(find.isOpen)
        assertFalse(hasField(FIND))
    }

    private class HistoryScene : FindScene(width = 1100, height = 400) {
        private val author = Identity("Maya Chen", "maya.chen@example.com")
        private val commits = listOf("a", "b").map { Commit(it.repeat(40), "Commit $it", author, author, 0L, emptyList()) }

        var diff: DiffResult.TextDiff by mutableStateOf(hunksDiff)
        val find = FindState()
        val listState = LazyListState()
        var completeFileRequests = 0
        var commitSteps = 0

        init {
            viewContent = {
                // File history steps through its commits with the arrows, as FileHistory does
                Box(Modifier.onKeyEvent { event ->
                    val isStep = event.matchesBinding(KeybindingOption.UP) || event.matchesBinding(KeybindingOption.DOWN)
                    if (isStep) commitSteps++
                    isStep
                }.findableView(find)) {
                    HistoryContentLoaded(
                        historyState = HistoryState.Loaded("src/Parser.kt", commits),
                        viewDiffResult = ViewDiffResult.Loaded(commitDiff, diff),
                        scrollState = listState,
                        selectedCommit = commits[0],
                        onCommitSelected = {},
                        onSelectAdjacentCommit = {},
                        find = HistoryFind(
                            state = find,
                            onShowCompleteFile = {
                                completeFileRequests++
                                diff = fullDiff
                            },
                            focusAfterClose = root,
                        ),
                    )
                }
            }
        }
    }

    private fun withHistory(test: suspend HistoryScene.() -> Unit): Unit = runBlocking(Dispatchers.Swing) {
        HistoryScene().use { scene ->
            scene.settle()
            scene.root.requestFocus()
            scene.settle()
            scene.test()
        }
    }

    @Test
    fun `find in file history's diff counts and steps, and the arrows in its field don't step the commits`() =
        withHistory {
            press(command(Key.F))
            type(FIND, "parser")

            assertTrue("1 of 5" in texts(), texts().toString())

            press(plain(Key.Enter))
            assertEquals(1, find.current)

            press(plain(Key.DirectionDown))
            press(plain(Key.DirectionUp))
            assertEquals(0, commitSteps, "the arrows stay in the field")
        }

    @Test
    fun `go to a line that file history's hunks leave out shows the whole file, then goes there`() = withHistory {
        press(goToLineKey)
        type(LINE, "150")
        press(plain(Key.Enter))

        assertEquals(1, completeFileRequests)
        assertEquals(151, find.goToRow)
    }
}
