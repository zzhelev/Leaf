// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.expand_more
import dev.app.leaf.app.generated.resources.file_history_newer_commit
import dev.app.leaf.app.generated.resources.file_history_older_commit
import dev.app.leaf.app.generated.resources.file_history_position
import dev.app.leaf.app.generated.resources.file_history_position_tooltip
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.extensions.toSmartSystemString
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.AvatarImage
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val DISABLED_BUTTON_ALPHA = 0.3F

/**
 * A strip above the diff in file history (fork-only): the commit whose diff is shown, where it is in the list, and
 * buttons that select the next newer or older commit, as ↑ and ↓ do. The list highlights the commit too, but a long
 * list scrolls it out of view, so its position also brings it back.
 *
 * [position] counts from 0, the newest commit.
 */
@Composable
fun FileHistoryCommitStrip(
    commit: Commit,
    position: Int,
    count: Int,
    onSelectAdjacentCommit: (offset: Int) -> Unit,
    onShowInList: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colors.surface)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarImage(
            modifier = Modifier.size(32.dp),
            personIdent = commit.author,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = commit.shortMessage,
                style = MaterialTheme.typography.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = listOf(
                    commit.shortHash,
                    commit.author.name.orEmpty(),
                    commit.date.toSmartSystemString(showTime = true),
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        InstantTooltip(text = stringResource(Res.string.file_history_position_tooltip)) {
            Text(
                text = stringResource(Res.string.file_history_position, position + 1, count),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .handMouseClickable(onClick = onShowInList)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        StepButton(
            isNewer = true,
            enabled = position > 0,
            onClick = { onSelectAdjacentCommit(-1) },
        )

        StepButton(
            isNewer = false,
            enabled = position < count - 1,
            onClick = { onSelectAdjacentCommit(1) },
        )
    }
}

@Composable
private fun StepButton(
    isNewer: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val title = stringResource(if (isNewer) Res.string.file_history_newer_commit else Res.string.file_history_older_commit)

    InstantTooltip(
        text = title,
        enabled = enabled,
        trailingContent = { ArrowKeyHint(if (isNewer) "↑" else "↓") },
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(32.dp)
                .run { if (enabled) handOnHover() else this },
        ) {
            Icon(
                painter = painterResource(Res.drawable.expand_more),
                contentDescription = title,
                tint = MaterialTheme.colors.onBackground.copy(alpha = if (enabled) 1F else DISABLED_BUTTON_ALPHA),
                modifier = Modifier
                    .size(24.dp)
                    .rotate(if (isNewer) 180F else 0F),
            )
        }
    }
}

/** As [KeybindingHint] shows a shortcut, which can't name the arrow keys. */
@Composable
private fun ArrowKeyHint(key: String) {
    Text(
        text = key,
        fontFamily = monoTypography(),
        fontSize = MaterialTheme.typography.caption.fontSize,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.onBackgroundSecondary,
    )
}

/**
 * Scrolls the file's commits as little as it takes to show the whole row at [index], after ↑ or ↓ selected it. Rows
 * have the same height, so the row below the last whole one becomes the last whole one, as in the status pane's lists.
 */
suspend fun LazyListState.keepInView(index: Int) {
    val visible = layoutInfo.visibleItemsInfo

    if (index < 0 || visible.isEmpty()) return

    val first = visible.first()
    val last = visible.last()

    if (index < first.index || (index == first.index && first.offset < layoutInfo.viewportStartOffset)) {
        scrollToItem(index)
    } else if (index > last.index || (index == last.index && last.offset + last.size > layoutInfo.viewportEndOffset)) {
        scrollToItem((index - visible.size + 2).coerceAtLeast(0))
    }
}

/** Scrolls the file's commits to show the row at [index] in the middle, where the list was scrolled away from it. */
suspend fun LazyListState.showInMiddle(index: Int) {
    if (index < 0) return

    animateScrollToItem((index - layoutInfo.visibleItemsInfo.size / 2).coerceAtLeast(0))
}
