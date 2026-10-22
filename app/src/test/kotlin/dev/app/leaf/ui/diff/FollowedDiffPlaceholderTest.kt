// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.repositoryopen.MissingFollowedFile
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.domain.models.ui.Theme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val someone = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com")

private val commit = Commit(
    hash = "a4fefd80f6ffa7b563da060f9893f6a12aecdefc",
    message = "Move the log's selection",
    committer = someone,
    author = someone,
    date = 1_760_000_000,
    parentsHashes = emptyList(),
)

private val missing = MissingFollowedFile("app/src/main/kotlin/dev/app/leaf/ui/log/Log.kt", commit, unreadable = false)

/**
 * What the diff area shows while the commit has no changes to the file that was open (fork-only): the file's path, why
 * there is no diff, a way to close it, and Esc closing it as it closes a diff. And the header button that hides the log.
 */
@OptIn(ExperimentalComposeUiApi::class)
class FollowedDiffPlaceholderTest {
    private var scene: ImageComposeScene? = null
    private var time = 0L

    private var current by mutableStateOf(missing)
    private var withToggle by mutableStateOf(true)
    private var keepsLog by mutableStateOf(true)

    private val closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private var shown = 0
    private var closed = 0
    private val toggled = mutableListOf<Boolean>()

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun render() {
        scene = ImageComposeScene(width = 700, height = 300, density = Density(1f)) {
            AppTheme(Theme.Dark) {
                FollowedDiffPlaceholder(
                    missing = current,
                    closeRequests = closeRequests,
                    onShown = { shown++ },
                    onClose = { closed++ },
                    logToggle = if (withToggle) {
                        { DiffLogToggle(keepsLog = keepsLog, onKeepsLogChanged = { toggled += it; keepsLog = it }) }
                    } else null,
                )
            }
        }
        frames()
    }

    private fun frames(count: Int = 4) = repeat(count) {
        time += 50
        scene!!.render(time * 1_000_000)
        Thread.sleep(20)
    }

    private fun nodes(): List<SemanticsNode> =
        scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }

    private fun click(node: SemanticsNode) {
        val at = node.boundsInRoot.center

        time += 100
        scene!!.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
        scene!!.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        time += 20
        scene!!.sendPointerEvent(PointerEventType.Release, at, timeMillis = time, button = PointerButton.Primary)
        frames(2)
    }

    private fun closeButton() = nodes().first {
        it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Close diff")
    }

    private fun png(): List<Byte> = scene!!.render(time * 1_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes.toList()

    @Test
    fun `it names the file in the header, and says that the commit has no changes to it`() {
        render()

        val texts = texts()
        assertTrue("Log.kt has no changes in a4fefd8" in texts, texts.toString())
        assertTrue(texts.any { it.startsWith("Select a commit that changes it") }, texts.toString())
        assertTrue("Log.kt" in texts, "the file's name is in the header")
        assertTrue(texts.any { it.startsWith("app/src/main/kotlin") }, "and its folder")
    }

    @Test
    fun `a commit whose files could not be read says that, not that the file is unchanged`() {
        current = missing.copy(unreadable = true)
        render()

        val texts = texts()
        assertTrue("Log.kt can't be shown for a4fefd8" in texts, texts.toString())
        assertTrue("The commit's files couldn't be read." in texts, texts.toString())
        assertFalse(texts.any { it.contains("has no changes") })
    }

    @Test
    fun `a file in the top folder has no folder in its header`() {
        current = MissingFollowedFile("README.md", commit, unreadable = false)
        render()

        assertTrue("README.md has no changes in a4fefd8" in texts())
        assertFalse(texts().any { it == "/" })
    }

    @Test
    fun `it joins the views that Esc closes once, when it shows`() {
        render()
        frames(4)

        assertEquals(1, shown)
    }

    @Test
    fun `Esc closes it, as it closes a diff`() {
        render()
        assertEquals(0, closed)

        closeRequests.tryEmit(Unit)
        frames()

        assertEquals(1, closed)
    }

    @Test
    fun `its close button closes it`() {
        render()

        click(closeButton())

        assertEquals(1, closed)
    }

    @Test
    fun `another file's placeholder joins the closeable views again`() {
        render()
        current = MissingFollowedFile("README.md", commit, unreadable = false)
        frames(4)

        assertEquals(2, shown)
    }

    @Test
    fun `the log button is in the header when it is given, and not when it isn't`() {
        render()
        val buttons = nodes().count { it.config.getOrNull(SemanticsActions.OnClick) != null }

        withToggle = false
        frames()

        assertEquals(buttons - 1, nodes().count { it.config.getOrNull(SemanticsActions.OnClick) != null })
    }

    @Test
    fun `the log button hides the log while it shows, and shows it while it is hidden`() {
        render()
        val withLog = png()

        // The close button is the last of the header, and the log button the one before it
        fun logButton(): SemanticsNode = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .sortedBy { it.boundsInRoot.left }
            .let { it[it.size - 2] }

        click(logButton())

        assertEquals(listOf(false), toggled)
        assertNotEquals(withLog, png(), "its icon changes while the log is hidden")

        click(logButton())

        assertEquals(listOf(false, true), toggled)
        assertTrue(keepsLog)
    }
}
