// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

@file:OptIn(ExperimentalComposeUiApi::class)

package dev.app.leaf.ui.log

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.GraphCommit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.LogColumn
import dev.app.leaf.domain.models.LogColumnEntry
import dev.app.leaf.domain.models.LogColumnsSettings
import dev.app.leaf.domain.models.isCommittedBySomeoneElse
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.extensions.toSystemTimeString
import dev.app.leaf.repositoryopen.LogAction
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.AvatarImage
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.components.sort.hiddenWhenTruncated
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltipPosition
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The gap before each column, which holds its resize divider in the header. */
const val LOG_COLUMN_SPACING = 8f

/** The space after the last column, in the header and in every row. */
val LOG_ROW_END_PADDING = 4.dp

private val CELL_PADDING = 8.dp
private val AUTHOR_AVATAR_SIZE = 16.dp
private val AUTHOR_TOOLTIP_AVATAR_SIZE = 20.dp

/** Room for the avatar and its committer badge, the same in every row, so that names line up. */
private val AVATAR_SLOT_WIDTH = 20.dp
private val AVATAR_SLOT_HEIGHT = 18.dp
private val AVATAR_NAME_GAP = 4.dp
private val COMMITTER_BADGE_SIZE = 10.dp
private val COMMITTER_BADGE_TEXT_SIZE = 7.sp

/** The ring cut out of the author's avatar around the badge. */
private val COMMITTER_BADGE_GAP = 1.5.dp

/** The avatar color of a committer who isn't the commit's author, on the badge and in the Committer column. */
private val OTHER_COMMITTER_COLOR = Color(0xFF808080)
private const val REOPEN_GUARD_MS = 300

/** OpenType's tabular figures: every digit is as wide as the others, so numbers line up from row to row. */
private const val TABULAR_DIGITS = "tnum"
private const val TIME_ALPHA = 0.75f
private val DATE_TIME_GAP = 8.dp

/** The columns that the header and the rows show after the message, in display order. */
@Immutable
data class LogColumnsLayout(
    val shown: List<LogColumnEntry>,
    val dateShowsTime: Boolean,
)

@Composable
private fun LogColumn.label(): String = when (this) {
    LogColumn.Author -> stringResource(Res.string.log_column_author)
    LogColumn.Committer -> stringResource(Res.string.log_column_committer)
    LogColumn.Date -> stringResource(Res.string.log_column_date)
    LogColumn.Commit -> stringResource(Res.string.log_column_commit)
}

/** The header labels of the shown columns, each after a divider that resizes it: dragging left widens it. */
@Composable
fun LogColumnHeaders(
    columns: List<LogColumnEntry>,
    onResize: (LogColumn, delta: Float) -> Unit,
    onResizeFinished: (LogColumn) -> Unit,
) {
    val density = LocalDensity.current.density

    for (entry in columns) {
        key(entry.column) {
            SimpleDividerLog(
                modifier = Modifier.draggable(
                    state = rememberDraggableState { onResize(entry.column, it / density) },
                    orientation = Orientation.Horizontal,
                    onDragStopped = { onResizeFinished(entry.column) },
                ),
            )

            Text(
                text = entry.column.label(),
                modifier = Modifier
                    .width(entry.width.dp)
                    .padding(horizontal = CELL_PADDING),
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A commit row's cells for the shown columns, lined up with [LogColumnHeaders]. */
@Composable
fun LogColumnCells(
    graphCommit: GraphCommit,
    layout: LogColumnsLayout,
    nodeColor: Color,
    isDimmed: Boolean,
) {
    for (entry in layout.shown) {
        key(entry.column) {
            Spacer(Modifier.width(LOG_COLUMN_SPACING.dp))

            Box(
                modifier = Modifier
                    .width(entry.width.dp)
                    .padding(horizontal = CELL_PADDING),
            ) {
                when (entry.column) {
                    LogColumn.Author -> AuthorCell(graphCommit, nodeColor, isDimmed)
                    LogColumn.Committer -> CommitterCell(graphCommit, nodeColor, isDimmed)
                    LogColumn.Date -> DateCell(graphCommit, layout.dateShowsTime)
                    LogColumn.Commit -> CommitCell(graphCommit)
                }
            }
        }
    }
}

/**
 * The author. When someone else committed the commit, as after a rebase or a merge on GitHub's website, the
 * committer's small avatar sits on the author's, and the tooltip names both, with both dates.
 */
@Composable
private fun AuthorCell(graphCommit: GraphCommit, nodeColor: Color, isDimmed: Boolean) {
    val commit = graphCommit.commit
    val author = commit.author
    val committer = commit.committer
    val isCommittedBySomeoneElse = commit.isCommittedBySomeoneElse

    val tooltip = if (isCommittedBySomeoneElse) {
        stringResource(
            Res.string.log_column_author_authored_by,
            author.name.orEmpty(),
            author.email.orEmpty(),
            commit.authorDate.toTooltipDate(),
        ) + "\n" + stringResource(
            Res.string.log_column_author_committed_by,
            committer.name.orEmpty(),
            committer.email.orEmpty(),
            commit.date.toTooltipDate(),
        )
    } else {
        author.nameAndEmail()
    }

    PersonCell(
        person = author,
        avatarColor = nodeColor,
        badge = committer.takeIf { isCommittedBySomeoneElse },
        tooltip = tooltip,
        isDimmed = isDimmed,
    )
}

/** The committer, with a grey avatar when it's someone other than the author. */
@Composable
private fun CommitterCell(graphCommit: GraphCommit, nodeColor: Color, isDimmed: Boolean) {
    val commit = graphCommit.commit

    PersonCell(
        person = commit.committer,
        avatarColor = if (commit.isCommittedBySomeoneElse) OTHER_COMMITTER_COLOR else nodeColor,
        badge = null,
        tooltip = commit.committer.nameAndEmail(),
        isDimmed = isDimmed,
    )
}

@Composable
private fun PersonCell(
    person: Identity,
    avatarColor: Color,
    badge: Identity?,
    tooltip: String,
    isDimmed: Boolean,
) {
    InstantTooltip(
        text = tooltip,
        maxLines = 2,
        leadingContent = {
            AvatarImage(
                modifier = Modifier.size(AUTHOR_TOOLTIP_AVATAR_SIZE),
                personIdent = person,
                color = avatarColor,
            )
        },
        position = InstantTooltipPosition.RIGHT,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarWithBadge(person, avatarColor, badge)

            Text(
                text = person.name.orEmpty(),
                modifier = Modifier.padding(start = AVATAR_NAME_GAP),
                style = MaterialTheme.typography.body2,
                color = if (isDimmed) MaterialTheme.colors.onBackgroundSecondary else MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * [person]'s avatar, with [badge]'s smaller avatar on its bottom-right edge. The avatar is cut around the badge, so that
 * the badge stands apart on any row background, the selected row's included.
 */
@Composable
private fun AvatarWithBadge(person: Identity, color: Color, badge: Identity?) {
    Box(modifier = Modifier.size(AVATAR_SLOT_WIDTH, AVATAR_SLOT_HEIGHT)) {
        AvatarImage(
            modifier = Modifier
                .size(AUTHOR_AVATAR_SIZE)
                .then(if (badge != null) Modifier.cutOutForBadge() else Modifier),
            personIdent = person,
            color = color,
        )

        if (badge != null) {
            // AvatarImage draws the initial in the current text style, which is too big for the badge
            CompositionLocalProvider(LocalTextStyle provides TextStyle(fontSize = COMMITTER_BADGE_TEXT_SIZE)) {
                AvatarImage(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(COMMITTER_BADGE_SIZE),
                    personIdent = badge,
                    color = OTHER_COMMITTER_COLOR,
                )
            }
        }
    }
}

/** Clears the badge's circle, plus a ring, from an avatar at the top-left of [AvatarWithBadge]. */
private fun Modifier.cutOutForBadge(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val badgeRadius = COMMITTER_BADGE_SIZE.toPx() / 2

        drawCircle(
            color = Color.Black,
            radius = badgeRadius + COMMITTER_BADGE_GAP.toPx(),
            center = Offset(AVATAR_SLOT_WIDTH.toPx() - badgeRadius, AVATAR_SLOT_HEIGHT.toPx() - badgeRadius),
            blendMode = BlendMode.Clear,
        )
    }

private fun Identity.nameAndEmail() = "${name.orEmpty()} <${email.orEmpty()}>"

@Composable
private fun Long.toTooltipDate(): String = toSmartSystemString(allowRelative = false, showTime = true)

/**
 * The commit date, and with [showTime] the time at the cell's end, a shade lighter, so that the times line up in a
 * column of their own. Digits are tabular, so that numbers line up too. When the cell is too narrow, the time is left
 * out rather than cut, and the tooltip still has it. The tooltip adds the date the change was authored when it's
 * different, as after a rebase or an amend.
 */
@Composable
private fun DateCell(graphCommit: GraphCommit, showTime: Boolean) {
    val style = MaterialTheme.typography.body2.copy(fontFeatureSettings = TABULAR_DIGITS)
    val color = MaterialTheme.colors.onBackgroundSecondary
    val committed = graphCommit.date.toTooltipDate()
    val authored = graphCommit.commit.authorDate.toTooltipDate()

    val tooltip = if (authored == committed) {
        committed
    } else {
        stringResource(Res.string.log_column_date_committed, committed) + "\n" +
                stringResource(Res.string.log_column_date_authored, authored)
    }

    InstantTooltip(
        text = tooltip,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
        position = InstantTooltipPosition.RIGHT,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = graphCommit.date.toSmartSystemString(),
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (showTime) {
                Text(
                    text = graphCommit.date.toSystemTimeString(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = DATE_TIME_GAP)
                        .hiddenWhenTruncated(),
                    style = style,
                    color = color.copy(alpha = TIME_ALPHA),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CommitCell(graphCommit: GraphCommit) {
    InstantTooltip(
        text = graphCommit.hash,
        position = InstantTooltipPosition.RIGHT,
    ) {
        Text(
            text = graphCommit.commit.shortHash,
            style = MaterialTheme.typography.body2,
            fontFamily = monoTypography(),
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** The columns menu: a check for each column, the date's time, and a reset. Toggles keep the menu open. */
@Composable
fun logColumnsMenuItems(
    settings: LogColumnsSettings,
    leftOut: Set<LogColumn>,
    onAction: (LogAction) -> Unit,
): List<SortMenuItem> {
    val needsRoom = stringResource(Res.string.log_columns_menu_needs_room)

    val columns = settings.columns.map { entry ->
        SortMenuItem.Option(
            label = entry.column.label(),
            isChecked = entry.isVisible,
            hint = if (entry.column in leftOut) needsRoom else null,
            onClick = { onAction(LogAction.ToggleColumn(entry.column)) },
        )
    }

    val time = if (settings.isVisible(LogColumn.Date)) {
        listOf(
            SortMenuItem.Divider,
            SortMenuItem.Option(
                label = stringResource(Res.string.log_columns_menu_show_time),
                isChecked = settings.dateShowsTime,
                onClick = { onAction(LogAction.SetDateShowsTime(!settings.dateShowsTime)) },
            ),
        )
    } else {
        emptyList()
    }

    return listOf(SortMenuItem.Title(stringResource(Res.string.log_columns_menu_title))) +
            columns +
            time +
            listOf(
                SortMenuItem.Divider,
                SortMenuItem.Option(
                    label = stringResource(Res.string.log_columns_menu_reset),
                    isChecked = false,
                    closesMenu = true,
                    onClick = { onAction(LogAction.ResetColumns) },
                ),
            )
}

/** The header's columns button, which opens the columns menu below it. */
@Composable
fun LogColumnsMenuButton(menuItems: List<SortMenuItem>) {
    var showMenu by remember { mutableStateOf(false) }
    // A press on the button while the menu is open first dismisses the menu as an outside click; this keeps the
    // button's own click from opening it again right away
    var dismissedAt by remember { mutableStateOf(0L) }
    val tooltip = stringResource(Res.string.log_columns_menu_tooltip)

    Box {
        DelayedTooltip(tooltip) {
            IconButton(
                modifier = Modifier.handOnHover(),
                onClick = {
                    if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                        showMenu = !showMenu
                    }
                },
            ) {
                Icon(
                    painterResource(Res.drawable.view_column),
                    modifier = Modifier.size(18.dp),
                    contentDescription = tooltip,
                    tint = MaterialTheme.colors.onBackground,
                )
            }
        }

        if (showMenu) {
            SortMenuPopup(
                items = menuItems,
                onDismissRequest = {
                    showMenu = false
                    dismissedAt = System.currentTimeMillis()
                },
            )
        }
    }
}

/** Opens the columns menu where the header is right-clicked. */
@Composable
fun LogColumnsHeaderMenu(
    menuItems: List<SortMenuItem>,
    content: @Composable () -> Unit,
) {
    var menuOffset by remember { mutableStateOf<IntOffset?>(null) }

    Box(
        modifier = Modifier.onPointerEvent(PointerEventType.Press) { event ->
            if (event.buttons.isSecondaryPressed) {
                val position = event.changes.first().position
                menuOffset = IntOffset(position.x.toInt(), position.y.toInt())
            }
        },
    ) {
        content()

        val offset = menuOffset

        if (offset != null) {
            SortMenuPopup(
                items = menuItems,
                onDismissRequest = { menuOffset = null },
                positionProvider = remember(offset) { AtOffsetPositionProvider(offset) },
            )
        }
    }
}

/** Places a popup's top-left corner at [offset] inside its anchor, moved back into the window if it would leave it. */
private class AtOffsetPositionProvider(private val offset: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left + offset.x)
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)
        val y = (anchorBounds.top + offset.y)
            .coerceAtMost(windowSize.height - popupContentSize.height)
            .coerceAtLeast(0)

        return IntOffset(x, y)
    }
}
