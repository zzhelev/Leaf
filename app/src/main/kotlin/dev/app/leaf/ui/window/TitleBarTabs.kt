// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.tabs_scroll_back
import dev.app.leaf.app.generated.resources.tabs_scroll_forward
import dev.app.leaf.app.generated.resources.topic
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.onDoubleClick
import dev.app.leaf.extensions.onMiddleMouseButtonClick
import dev.app.leaf.theme.frameColors
import dev.app.leaf.theme.withText
import dev.app.leaf.ui.components.TAB_COLOR_STRIPE_HEIGHT
import dev.app.leaf.ui.components.TabNameField
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The part of the title bar at its right end that holds no tab, so that the window can always be moved from there
 * (option 1, chosen with the user on 2026-11-04).
 */
val TITLE_BAR_FREE_STRIP = 110.dp

/** How narrow a tab gets in the title bar before the tabs scroll. */
val TITLE_BAR_MIN_TAB_WIDTH = 120.dp

/** A tab's width in the title bar when it has room: mockup A's. */
private val TITLE_BAR_TAB_WIDTH = 168.dp

/** The height of a tab, of the + button and of the arrows in the title bar; a tab grows with the text size. */
val TITLE_BAR_TAB_HEIGHT = 28.dp

/** The space between two tabs, and before the + button. */
val TITLE_BAR_TAB_GAP = 4.dp

/** The + button after the tabs, with the space before it. */
val ADD_TAB_BUTTON_WIDTH = TITLE_BAR_TAB_HEIGHT + TITLE_BAR_TAB_GAP

private val SCROLL_ARROW_WIDTH = 24.dp

private val TAB_SHAPE = RoundedCornerShape(7.dp)

/** The width of [tabCount] tabs of [tabWidth] each, with the space between them. */
private fun tabsWidth(tabWidth: Dp, tabCount: Int): Dp =
    tabWidth * tabCount + TITLE_BAR_TAB_GAP * (tabCount - 1).coerceAtLeast(0)

/** Whether [tabCount] tabs fit in [width] at [TITLE_BAR_MIN_TAB_WIDTH] each, with the + button after them. */
fun tabsFitTitleBar(width: Dp, tabCount: Int): Boolean =
    tabsWidth(TITLE_BAR_MIN_TAB_WIDTH, tabCount) <= width - ADD_TAB_BUTTON_WIDTH

/**
 * The most that each tab may take: an equal share of [width] when the tabs don't fit at their own widths, but never
 * less than [TITLE_BAR_MIN_TAB_WIDTH]. Below that, the tabs scroll.
 */
fun titleBarTabWidth(width: Dp, tabCount: Int): Dp {
    if (tabCount == 0) return width

    val share = (width - ADD_TAB_BUTTON_WIDTH - TITLE_BAR_TAB_GAP * (tabCount - 1)) / tabCount

    return maxOf(TITLE_BAR_MIN_TAB_WIDTH, share)
}

/**
 * A tab in the window's title bar (mockup A of the "Leaf window chrome review" board): 28 dp tall and rounded, the
 * selected one filled and outlined in the frame's control colours, the others plain text that a hover fills lightly.
 * A tab with [color] keeps its stripe along its top. [hasRepository] is false for a Welcome page's tab, which has no
 * repository icon.
 */
@Composable
fun TitleBarTab(
    modifier: Modifier,
    title: String,
    worktreeName: String?,
    isSelected: Boolean,
    hasRepository: Boolean,
    onClick: () -> Unit,
    onCloseTab: () -> Unit,
    color: Color?,
    isRenaming: Boolean,
    onDoubleClick: (() -> Unit)?,
    onRenameDone: (typed: String?, byKey: Boolean) -> Unit,
) {
    val frame = MaterialTheme.frameColors
    val isLight = MaterialTheme.colors.isLight
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    val textColor = if (isSelected) frame.text else frame.secondaryText
    val fill = when {
        isSelected -> frame.control
        isHovered -> frame.control.copy(alpha = 0.5f)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .widthIn(min = TITLE_BAR_TAB_WIDTH)
            // A tab in the LazyRow has no width to fill: its own content's, with the name cut by the width it's given
            .width(IntrinsicSize.Max)
            .height(TITLE_BAR_TAB_HEIGHT.withText())
            .run { if (isSelected && isLight) shadow(1.dp, TAB_SHAPE, clip = false) else this }
            .clip(TAB_SHAPE)
            .background(fill)
            .run { if (isSelected) border(1.dp, frame.controlEdge, TAB_SHAPE) else this }
            .hoverable(hoverInteraction)
            .handMouseClickable { onClick() }
            .run { if (onDoubleClick != null && !isRenaming) onDoubleClick(onDoubleClick) else this }
            .onMiddleMouseButtonClick { onCloseTab() },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasRepository) {
                Icon(
                    painter = painterResource(Res.drawable.topic),
                    contentDescription = null,
                    tint = if (isSelected) frame.icon else frame.secondaryText,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(15.dp),
                )
            }

            if (isRenaming) {
                Box(modifier = Modifier.weight(1f)) {
                    TabNameField(name = title, onDone = onRenameDone, textColor = frame.text)
                }
            } else {
                val worktreeColor = frame.secondaryText
                val text = remember(title, worktreeName, worktreeColor) {
                    buildAnnotatedString {
                        append(title)

                        if (worktreeName != null) {
                            withStyle(SpanStyle(color = worktreeColor, fontWeight = FontWeight.Normal)) {
                                append(" · $worktreeName")
                            }
                        }
                    }
                }

                Text(
                    text = text,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 720.dp),
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body2,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            IconButton(
                onClick = onCloseTab,
                enabled = isHovered || isSelected,
                modifier = Modifier
                    .alpha(if (isHovered || isSelected) 1f else 0f)
                    .padding(start = 8.dp)
                    .size(14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.close),
                    contentDescription = null,
                    tint = frame.secondaryText,
                )
            }
        }

        if (color != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(TAB_COLOR_STRIPE_HEIGHT)
                    .background(color),
            )
        }
    }
}

/** Scrolls the tabs by most of what shows, back or [forward]; dimmed at that end. */
@Composable
fun TabScrollArrow(state: LazyListState, forward: Boolean) {
    val scope = rememberCoroutineScope()
    val canScroll = if (forward) state.canScrollForward else state.canScrollBackward
    val label = stringResource(if (forward) Res.string.tabs_scroll_forward else Res.string.tabs_scroll_back)

    IconButton(
        onClick = {
            val distance = state.layoutInfo.viewportSize.width * 0.8f

            scope.launch {
                state.animateScrollBy(if (forward) distance else -distance)
            }
        },
        enabled = canScroll,
        modifier = Modifier
            .size(width = SCROLL_ARROW_WIDTH, height = TITLE_BAR_TAB_HEIGHT)
            .run { if (canScroll) handOnHover() else this },
    ) {
        Icon(
            imageVector = if (forward) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
            contentDescription = label,
            tint = MaterialTheme.frameColors.secondaryText.copy(alpha = if (canScroll) 1f else 0.3f),
        )
    }
}
