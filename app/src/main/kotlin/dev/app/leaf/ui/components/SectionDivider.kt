// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.SECTION_DIVIDER_HEIGHT
import dev.app.leaf.ui.resizePointerIconNorth

/** The handle between two sections of a pane. Dragging it resizes them, and a double-click resets them. */
@Composable
fun SectionDivider(
    /** How far the handle moved down, in dp. */
    onDrag: (Float) -> Unit,
    onDragStopped: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    val density = LocalDensity.current.density
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SECTION_DIVIDER_HEIGHT.dp)
            .pointerHoverIcon(resizePointerIconNorth)
            .draggable(
                state = rememberDraggableState { onDrag(it / density) },
                orientation = Orientation.Vertical,
                onDragStopped = { onDragStopped() },
            )
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnDoubleClick() })
            }
    )
}
