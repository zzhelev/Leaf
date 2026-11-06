// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.window_button_close
import dev.app.leaf.app.generated.resources.window_button_maximize
import dev.app.leaf.app.generated.resources.window_button_minimize
import dev.app.leaf.app.generated.resources.window_button_restore
import dev.app.leaf.theme.frameColors
import dev.app.leaf.ui.window.TITLE_BAR_TAB_HEIGHT
import dev.app.leaf.ui.window.TitleBarControls
import dev.app.leaf.ui.window.titleBarControl
import org.jetbrains.compose.resources.stringResource
import java.awt.Frame

/** A window button's width; it's as tall as a tab. */
val WINDOW_BUTTON_WIDTH = 34.dp

/** The room between the buttons and the window's edge. */
private val WINDOW_BUTTONS_EDGE_PADDING = 6.dp

private val WINDOW_BUTTON_SHAPE = RoundedCornerShape(6.dp)

/** The close button under the pointer, as most desktops show it; with white on it, 5.6 : 1. */
private val CLOSE_HOVER = Color(0xFFC42B1C)
private val CLOSE_PRESSED = Color(0xFFA32417)

/**
 * The window's [buttons] on one side of the title bar (fork-only, docs/fork/window-chrome.md, section 10): plain
 * glyphs in the frame's text colour, filled as the tabs are when the pointer is over them, and the close button red.
 * While another window is active, the glyphs are dimmed, as the desktop dims its own.
 */
@Composable
internal fun WindowButtons(window: Frame, buttons: List<WindowButton>, atStart: Boolean, controls: TitleBarControls) {
    val isMaximized by rememberIsMaximized(window)

    Row(
        modifier = Modifier.padding(
            start = if (atStart) WINDOW_BUTTONS_EDGE_PADDING else 0.dp,
            end = if (atStart) 0.dp else WINDOW_BUTTONS_EDGE_PADDING,
        ),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (button in buttons) {
            WindowButtonView(
                button = button,
                isMaximized = isMaximized,
                modifier = Modifier.titleBarControl(controls),
                onClick = {
                    when (button) {
                        WindowButton.MINIMIZE -> window.minimize()
                        WindowButton.MAXIMIZE -> window.toggleMaximized()
                        WindowButton.CLOSE -> window.requestClose()
                    }
                },
            )
        }
    }
}

@Composable
internal fun WindowButtonView(
    button: WindowButton,
    isMaximized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isPressed by interaction.collectIsPressedAsState()
    val isActive = LocalWindowInfo.current.isWindowFocused
    val frame = MaterialTheme.frameColors
    val isClose = button == WindowButton.CLOSE

    val fill = when {
        isClose && isPressed -> CLOSE_PRESSED
        isClose && isHovered -> CLOSE_HOVER
        isPressed -> frame.control
        isHovered -> frame.control.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val ink = when {
        isClose && (isHovered || isPressed) -> Color.White
        isActive || isHovered -> frame.text
        else -> frame.secondaryText
    }
    val description = stringResource(
        when (button) {
            WindowButton.MINIMIZE -> Res.string.window_button_minimize
            WindowButton.MAXIMIZE -> if (isMaximized) Res.string.window_button_restore else Res.string.window_button_maximize
            WindowButton.CLOSE -> Res.string.window_button_close
        }
    )

    Box(
        modifier = modifier
            .size(WINDOW_BUTTON_WIDTH, TITLE_BAR_TAB_HEIGHT)
            .clip(WINDOW_BUTTON_SHAPE)
            .background(fill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawGlyph(button, isMaximized, ink)
        }
    }
}

/** The button's glyph in a 10 dp square, in 1 dp lines, as the desktops' plain themes draw them. */
private fun DrawScope.drawGlyph(button: WindowButton, isMaximized: Boolean, color: Color) {
    val line = 1.dp.toPx()
    val half = line / 2
    val w = size.width
    val h = size.height

    when (button) {
        WindowButton.MINIMIZE -> drawLine(color, Offset(0f, h / 2), Offset(w, h / 2), line)
        WindowButton.MAXIMIZE -> if (isMaximized) {
            // Restore: a window in front of another
            val back = w * 0.25f
            drawRect(color, Offset(half, back + half), Size(w - back - line, h - back - line), style = Stroke(line))
            drawLine(color, Offset(back, half), Offset(w - half, half), line)
            drawLine(color, Offset(w - half, half), Offset(w - half, h - back), line)
        } else {
            drawRect(color, Offset(half, half), Size(w - line, h - line), style = Stroke(line))
        }

        WindowButton.CLOSE -> {
            drawLine(color, Offset(0f, 0f), Offset(w, h), line)
            drawLine(color, Offset(w, 0f), Offset(0f, h), line)
        }
    }
}
