// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.ChangesPaneNeed
import dev.app.leaf.ui.ChangesPaneState
import dev.app.leaf.ui.dialogs.ConfirmableAction.AbortOperation.Operation
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bottom bar's button for the changes pane (fork-only): its name, its icon for each state, the amber dot and the
 * click that does nothing while something holds the pane open.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ChangesPaneToggleTest {
    private var scene: ImageComposeScene? = null
    private var time = 0L

    private var state by mutableStateOf(ChangesPaneState(isFolded = false))
    private var clicks = 0
    private var warningColor = 0

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    private fun render() {
        scene = ImageComposeScene(width = 120, height = 60, density = Density(2f)) {
            AppTheme {
                warningColor = MaterialTheme.styleColors.warningIcon.toArgb()
                ChangesPaneToggle(state = state, onToggle = { clicks++ })
            }
        }
        frames()
    }

    private fun frames(count: Int = 3) = repeat(count) {
        time += 50
        scene!!.render(time * 1_000_000)
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun descriptions() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

    private fun clickable(): SemanticsNode? = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun png() = scene!!.render(time * 1_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes.toList()

    private fun bitmap() = Bitmap.makeFromImage(scene!!.render(time * 1_000_000))

    private fun click(at: Offset) {
        time += 100
        scene!!.sendPointerEvent(PointerEventType.Move, at, timeMillis = time)
        scene!!.sendPointerEvent(PointerEventType.Press, at, timeMillis = time, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        time += 20
        scene!!.sendPointerEvent(PointerEventType.Release, at, timeMillis = time, button = PointerButton.Primary)
        frames()
    }

    @Test
    fun `it offers to hide the pane while it shows, and to show it while it is folded`() {
        render()
        assertEquals(listOf("Hide the changes pane"), descriptions())

        state = ChangesPaneState(isFolded = true)
        frames()

        assertEquals(listOf("Show the changes pane"), descriptions())
    }

    @Test
    fun `its icon differs between the states`() {
        render()
        val shown = png()

        state = ChangesPaneState(isFolded = true)
        frames()

        assertNotEquals(shown, png())
    }

    /** A pixel of the icon's right half, where the open icon has its filled strip: 18 dp in, in the middle (2 px a dp). */
    private fun rightHalf() = bitmap().getColor(18 * 2, 13 * 2)

    /** A pixel of the icon's left half, which no icon fills. */
    private fun leftHalf() = bitmap().getColor(8 * 2, 13 * 2)

    @Test
    fun `its icon is a panel on the right, and the open one fills the right half only`() {
        render()
        val open = rightHalf()

        assertNotEquals(open, leftHalf(), "the strip is on the right")

        state = ChangesPaneState(isFolded = true)
        frames()

        assertEquals(leftHalf(), rightHalf(), "the folded icon is empty")
        assertNotEquals(open, rightHalf())
    }

    @Test
    fun `a click toggles once, and the button is a 26 dp square`() {
        render()
        val node = checkNotNull(clickable())
        val at: Offset = node.boundsInRoot.center

        assertEquals(26f * 2, node.boundsInRoot.width, 1f)
        assertEquals(26f * 2, node.boundsInRoot.height, 1f)

        click(at)

        assertEquals(1, clicks)
    }

    @Test
    fun `a click outside the button does nothing`() {
        render()

        click(Offset(110f, 50f))

        assertEquals(0, clicks)
    }

    @Test
    fun `while an operation holds the pane the tooltip says which, in its own words`() {
        val names = mapOf(
            Operation.MERGE to "merge",
            Operation.REBASE to "rebase",
            Operation.CHERRY_PICK to "cherry-pick",
            Operation.REVERT to "revert",
            Operation.SQUASH to "squash merge",
        )

        state = ChangesPaneState(isFolded = true, need = ChangesPaneNeed.OperationInProgress(Operation.MERGE))
        render()

        for ((operation, name) in names) {
            state = ChangesPaneState(isFolded = true, need = ChangesPaneNeed.OperationInProgress(operation))
            frames()

            assertEquals(listOf("Changes pane: shown while a $name is in progress"), descriptions(), operation.name)
        }
    }

    @Test
    fun `while a comparison holds the pane the tooltip says so`() {
        state = ChangesPaneState(isFolded = true, need = ChangesPaneNeed.Comparison)
        render()

        assertEquals(listOf("Changes pane: shown while comparing"), descriptions())
    }

    @Test
    fun `a held pane takes no click, whether the user folded it or not`() {
        for (folded in listOf(true, false)) {
            clicks = 0
            scene?.close()
            state = ChangesPaneState(isFolded = folded, need = ChangesPaneNeed.OperationInProgress(Operation.REBASE))
            render()

            assertEquals(null, clickable(), "folded=$folded: nothing to click")

            click(Offset(26f, 26f))

            assertEquals(0, clicks, "folded=$folded")
        }
    }

    @Test
    fun `a held pane shows its icon open, as the pane is shown`() {
        render()
        val open = rightHalf()

        state = ChangesPaneState(isFolded = true)
        frames()
        val folded = rightHalf()

        state = ChangesPaneState(isFolded = true, need = ChangesPaneNeed.Comparison)
        frames()

        assertEquals(open, rightHalf())
        assertNotEquals(folded, rightHalf())
    }

    @Test
    fun `the amber dot is on a held pane's icon and on no other`() {
        // The dot's centre: 7 dp in a 11 dp ring at the box's top right
        val x = (26 - 5.5f).times(2).toInt()
        val y = (5.5f).times(2).toInt()

        render()
        assertNotEquals(warningColor, bitmap().getColor(x, y), "an unfolded pane has no dot")

        state = ChangesPaneState(isFolded = true)
        frames()
        assertNotEquals(warningColor, bitmap().getColor(x, y), "a folded pane has no dot")

        state = ChangesPaneState(isFolded = true, need = ChangesPaneNeed.OperationInProgress(Operation.MERGE))
        frames()
        assertEquals(warningColor, bitmap().getColor(x, y), "a held pane has the theme's warning colour")

        state = ChangesPaneState(isFolded = false, need = ChangesPaneNeed.Comparison)
        frames()
        assertEquals(warningColor, bitmap().getColor(x, y), "so does one that was never folded")

        state = ChangesPaneState(isFolded = false)
        frames()
        assertNotEquals(warningColor, bitmap().getColor(x, y), "and the dot goes when the need does")
        assertTrue(descriptions().single().startsWith("Hide"))
    }
}
