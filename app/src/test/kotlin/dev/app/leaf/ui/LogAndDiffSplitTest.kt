// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import dev.app.leaf.domain.models.CENTER_MIN_DIFF_HEIGHT
import dev.app.leaf.domain.models.CENTER_MIN_LOG_HEIGHT
import dev.app.leaf.domain.models.CenterSplitSizes
import dev.app.leaf.domain.models.SECTION_DIVIDER_HEIGHT
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val WIDTH = 400
private const val DELTA = 1.5f

/**
 * The centre pane with a diff open (fork-only, upstream issues 343, 136 and 128): the log above and the diff below it
 * where there is room, the diff over the log where there isn't, the divider between them, and the log's focus. Rendered at
 * 1 px per dp, with a box for the log and one for the diff.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LogAndDiffSplitTest {
    private var scene: ImageComposeScene? = null
    private var time = 0L

    private var sizes by mutableStateOf(CenterSplitSizes())
    private var keepsLog by mutableStateOf(true)
    private var isDiffShown by mutableStateOf(false)

    private var logHeight = -1f
    private var diffTop = -1f
    private var diffHeight = -1f
    private var logPlacements = 0
    private var logDisposals = 0
    private var diffDisposals = 0

    private val sizeChanges = mutableListOf<CenterSplitSizes>()
    private var finished = 0
    private var resets = 0

    private val logFocus = FocusRequester()

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun render(height: Int) {
        scene = ImageComposeScene(width = WIDTH, height = height, density = Density(1f)) {
            LogAndDiffSplit(
                modifier = Modifier.fillMaxSize(),
                sizes = sizes,
                keepsLog = keepsLog,
                isDiffShown = isDiffShown,
                onSizesChange = {
                    sizeChanges += it
                    sizes = it
                },
                onSizesChangeFinished = { finished++ },
                onResetSizes = {
                    resets++
                    sizes = CenterSplitSizes()
                },
                log = { modifier ->
                    DisposableEffect(Unit) { onDispose { logDisposals++ } }

                    // The requester goes before the modifier's focusable, which is what it asks for the focus
                    Box(
                        Modifier
                            .focusRequester(logFocus)
                            .then(modifier)
                            .fillMaxSize()
                            .onGloballyPositioned {
                                logHeight = it.boundsInRoot().height
                                logPlacements++
                            }
                    )
                },
                diff = {
                    DisposableEffect(Unit) { onDispose { diffDisposals++ } }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                diffTop = it.boundsInRoot().top
                                diffHeight = it.boundsInRoot().height
                            }
                    )
                },
            )
        }
        frames()
    }

    private fun frames(count: Int = 2) = repeat(count) {
        time += 50
        scene!!.render(time * 1_000_000)
    }

    /** Whether the log's focus target took the focus: one that isn't focusable refuses it, or isn't there at all. */
    private fun canFocusLog() = try {
        logFocus.requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

    private fun press(at: Offset, buttons: PointerButtons = PointerButtons(isPrimaryPressed = true)) {
        time += 100
        scene!!.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
        scene!!.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = buttons, button = PointerButton.Primary)
    }

    private fun release(at: Offset) {
        time += 20
        scene!!.sendPointerEvent(PointerEventType.Release, at, timeMillis = time, button = PointerButton.Primary)
    }

    @Test
    fun `without a diff the log has the whole pane and the diff is not composed`() {
        render(808)

        assertEquals(808f, logHeight, DELTA)
        assertEquals(-1f, diffTop, "the diff is not composed")
    }

    @Test
    fun `with a diff the log keeps its share above it, and the divider sits between them`() {
        isDiffShown = true
        render(808)

        assertEquals(320f, logHeight, DELTA)
        assertEquals(320f + SECTION_DIVIDER_HEIGHT, diffTop, DELTA)
        assertEquals(480f, diffHeight, DELTA)
    }

    @Test
    fun `the saved share decides the heights`() {
        sizes = CenterSplitSizes(logShare = 0.25f)
        isDiffShown = true
        render(808)

        assertEquals(200f, logHeight, DELTA)
        assertEquals(600f, diffHeight, DELTA)
    }

    @Test
    fun `with the log hidden the diff covers the whole pane, over the log`() {
        keepsLog = false
        isDiffShown = true
        render(808)

        assertEquals(808f, logHeight, DELTA, "the log is composed under the diff")
        assertEquals(0f, diffTop, DELTA)
        assertEquals(808f, diffHeight, DELTA)
    }

    @Test
    fun `a pane too short for both covers the log with the diff too`() {
        isDiffShown = true
        render((CENTER_MIN_LOG_HEIGHT + CENTER_MIN_DIFF_HEIGHT + SECTION_DIVIDER_HEIGHT).toInt() - 5)

        assertEquals(0f, diffTop, DELTA)
        assertEquals(logHeight, diffHeight, DELTA)
    }

    @Test
    fun `a pane just tall enough for both splits at their minimums`() {
        sizes = CenterSplitSizes(logShare = 0.9f)
        isDiffShown = true
        render((CENTER_MIN_LOG_HEIGHT + CENTER_MIN_DIFF_HEIGHT + SECTION_DIVIDER_HEIGHT).toInt())

        assertEquals(CENTER_MIN_DIFF_HEIGHT, diffHeight, DELTA)
        assertEquals(CENTER_MIN_LOG_HEIGHT, logHeight, DELTA)
    }

    @Test
    fun `opening and closing the diff, or hiding the log, doesn't compose the log again`() {
        render(808)

        isDiffShown = true
        frames()
        keepsLog = false
        frames()
        keepsLog = true
        frames()
        isDiffShown = false
        frames()

        assertEquals(0, logDisposals, "the log was never left")
        assertEquals(808f, logHeight, DELTA)
        assertTrue(diffDisposals >= 1, "the diff was left each time it closed")
    }

    @Test
    fun `the log takes the focus when it shows, and not under a diff that covers it`() {
        render(808)
        assertTrue(canFocusLog(), "the log alone")

        isDiffShown = true
        frames()
        assertTrue(canFocusLog(), "the log above its diff")

        keepsLog = false
        frames()
        assertFalse(canFocusLog(), "the log under the diff that covers it")

        isDiffShown = false
        frames()
        assertTrue(canFocusLog(), "the log again")
    }

    @Test
    fun `dragging the divider moves it by the distance and reports each size`() {
        isDiffShown = true
        render(808)
        val divider = Offset(200f, 320f + SECTION_DIVIDER_HEIGHT / 2)

        press(divider)
        for (step in 1..6) {
            time += 16
            scene!!.sendPointerEvent(PointerEventType.Move, Offset(200f, divider.y + step * 10f), timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true))
            frames(1)
        }
        release(Offset(200f, divider.y + 60f))
        frames()

        assertTrue(sizeChanges.isNotEmpty(), "the drag was reported")
        assertEquals(380f, logHeight, 8f)
        assertEquals(1, finished, "the end of the drag is reported once")
        assertEquals(sizeChanges.last(), sizes)
    }

    @Test
    fun `a drag can't take either part under its minimum`() {
        isDiffShown = true
        render(808)
        val divider = Offset(200f, 320f + SECTION_DIVIDER_HEIGHT / 2)

        press(divider)
        for (step in 1..24) {
            time += 16
            scene!!.sendPointerEvent(PointerEventType.Move, Offset(200f, divider.y + step * 20f), timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true))
            frames(1)
        }
        release(Offset(200f, divider.y + 24 * 20f))
        frames()

        assertEquals(CENTER_MIN_DIFF_HEIGHT, diffHeight, DELTA, "the log's heights during the drag: ${sizeChanges.map { (it.logShare * 800).toInt() }}")
        assertEquals(808f - SECTION_DIVIDER_HEIGHT - CENTER_MIN_DIFF_HEIGHT, logHeight, DELTA)
    }

    @Test
    fun `sizes that changed elsewhere, as in another tab, are where the next drag starts from`() {
        isDiffShown = true
        render(808)

        sizes = CenterSplitSizes(logShare = 0.7f)
        frames()
        assertEquals(560f, logHeight, DELTA)

        val divider = Offset(200f, 560f + SECTION_DIVIDER_HEIGHT / 2)

        press(divider)
        for (step in 1..3) {
            time += 16
            scene!!.sendPointerEvent(PointerEventType.Move, Offset(200f, divider.y + step * 10f), timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true))
            frames(1)
        }
        release(Offset(200f, divider.y + 30f))
        frames()

        assertEquals(590f, logHeight, 8f, "from the new share, not from the one the drag found at the first composition")
    }

    @Test
    fun `a double click on the divider resets the sizes`() {
        sizes = CenterSplitSizes(logShare = 0.7f)
        isDiffShown = true
        render(808)
        val divider = Offset(200f, 0.7f * 800f + SECTION_DIVIDER_HEIGHT / 2)

        for (click in 1..2) {
            press(divider)
            release(divider)
            frames(1)
        }

        assertEquals(1, resets)
        assertEquals(CenterSplitSizes(), sizes)
        assertEquals(320f, logHeight, DELTA)
    }

    @Test
    fun `no drag is reported while no diff is open, as there is no divider to drag`() {
        render(808)

        press(Offset(200f, 320f))
        release(Offset(200f, 320f))
        frames()

        assertTrue(sizeChanges.isEmpty())
    }
}
