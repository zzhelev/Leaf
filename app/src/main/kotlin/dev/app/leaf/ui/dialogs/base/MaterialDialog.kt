@file:OptIn(ExperimentalComposeUiApi::class)

package dev.app.leaf.ui.dialogs.base

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import java.awt.Cursor
import kotlin.math.roundToInt

private val movePointerIcon = PointerIcon(Cursor(Cursor.MOVE_CURSOR))

interface MaterialDialogScope {
    /**
     * Lets the user move the dialog by dragging this element, the same as the strip along the dialog's top edge.
     */
    fun Modifier.dialogDragHandle(): Modifier
}

/**
 * The top 16 dp of the dialog are its drag strip, so keep controls out of them.
 */
@Composable
fun MaterialDialog(
    paddingHorizontal: Dp = 16.dp,
    paddingVertical: Dp = 16.dp,
    background: Color = MaterialTheme.colors.surface,
    onCloseRequested: () -> Unit = {},
    content: @Composable MaterialDialogScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val dragState = remember { DialogDragState() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Fills the space it's given and places the dialog itself: centered, then moved by the drag offset. A Compose
    // Dialog only takes clicks inside its content's bounds, so a dialog-sized layout that was only offset would stop
    // taking clicks wherever it left its centered place.
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.matchesBinding(KeybindingOption.EXIT) && keyEvent.type == KeyEventType.KeyDown) {
                            onCloseRequested()
                            true
                        } else
                            false
                    }
                    .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(background),
            ) {
                Box(
                    modifier = Modifier.padding(
                        horizontal = paddingHorizontal,
                        vertical = paddingVertical,
                    ),
                ) {
                    dragState.content()
                }

                Box(modifier = Modifier.matchParentSize()) {
                    dragState.DragStrip()
                }
            }
        },
    ) { measurables, constraints ->
        val dialog = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else dialog.width
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else dialog.height
        val centeredX = (width - dialog.width) / 2
        val centeredY = (height - dialog.height) / 2

        dragState.maxOffset = Offset(centeredX.coerceAtLeast(0).toFloat(), centeredY.coerceAtLeast(0).toFloat())

        layout(width, height) {
            val offset = dragState.offset()
            dialog.place(centeredX + offset.x.roundToInt(), centeredY + offset.y.roundToInt())
        }
    }
}

@Composable
private fun MaterialDialogScope.DragStrip() {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .hoverable(interactionSource)
            .dialogDragHandle(),
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(
                    MaterialTheme.colors.onBackground.copy(alpha = if (isHovered) 0.4f else 0.15f),
                    RoundedCornerShape(2.dp),
                )
        )
    }
}

private class DialogDragState : MaterialDialogScope {
    private var dragOffset by mutableStateOf(Offset.Zero)

    /**
     * How far the dialog can move from the center along each axis and stay inside the space it's given. Set by the
     * layout, which also clamps the offset when the window shrinks.
     */
    var maxOffset = Offset.Zero

    fun offset() = dragOffset.coerceIn(maxOffset)

    override fun Modifier.dialogDragHandle(): Modifier = this
        .pointerHoverIcon(movePointerIcon)
        .pointerInput(this@DialogDragState) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                dragOffset = (offset() + dragAmount).coerceIn(maxOffset)
            }
        }
}

private fun Offset.coerceIn(max: Offset) = Offset(x.coerceIn(-max.x, max.x), y.coerceIn(-max.y, max.y))
