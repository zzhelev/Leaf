// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.theme.frameColors
import java.awt.Dimension
import java.awt.Frame

/** The smallest window that resizing leaves, in the window's units. */
const val MIN_WINDOW_WIDTH = 640
const val MIN_WINDOW_HEIGHT = 400

/**
 * Leaf's own frame on Linux around [content] (fork-only, docs/fork/window-chrome.md, section 10): a 1 dp line, as the
 * undecorated window has neither the desktop's border nor its shadow, except while the window is maximized. Without
 * [frame] (any other system, or the setting off) only [content].
 *
 * Compose resizes an undecorated window from its edges itself (`UndecoratedWindowResizer`, a layer over the content),
 * so the frame only sets the smallest size, which that resizing and the window manager's both respect. Without it,
 * dragging an edge left the window a pixel tall.
 */
@Composable
fun LinuxWindowFrame(frame: LinuxFrame?, window: Frame, content: @Composable () -> Unit) {
    if (frame == null) {
        content()
        return
    }

    val isMaximized by rememberIsMaximized(window)

    LaunchedEffect(window) {
        window.minimumSize = Dimension(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT)
    }

    // The content stays at one place in the composition, so that maximizing doesn't make it again
    Box(
        modifier = Modifier
            .fillMaxSize()
            .run {
                if (isMaximized) this else border(1.dp, MaterialTheme.frameColors.controlEdge).padding(1.dp)
            }
    ) {
        content()
    }
}
