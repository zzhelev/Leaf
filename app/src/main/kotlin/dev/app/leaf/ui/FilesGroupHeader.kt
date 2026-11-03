// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.chevron_right
import dev.app.leaf.app.generated.resources.expand_more
import dev.app.leaf.app.generated.resources.files_group_changes
import dev.app.leaf.app.generated.resources.files_group_conflicts
import dev.app.leaf.app.generated.resources.files_group_untracked
import dev.app.leaf.domain.sorting.FileGroup
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.CountPill
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The header of a group of rows in a files list (fork-only): `[chevron] Title ........ [count]`, on a band of its own. A
 * click closes the group or opens it. [trailingAction] is drawn over the row's end while it is hovered, as a folder
 * row's button is. The Amend group and Unstaged's groups (upstream issues 260 and 345) use it.
 */
@Composable
internal fun FilesGroupHeader(
    title: String,
    count: Int?,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    trailingAction: (@Composable BoxScope.(isHovered: Boolean) -> Unit)? = null,
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colors.onBackground.copy(alpha = 0.08f))
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(hoverInteraction)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MaterialTheme.linesHeight.fileHeight)
                    .background(MaterialTheme.colors.surface)
                    .handMouseClickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(if (isCollapsed) Res.drawable.chevron_right else Res.drawable.expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colors.onBackgroundSecondary,
                    modifier = Modifier
                        .padding(start = 10.dp, end = 8.dp)
                        .size(14.dp),
                )

                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onBackground,
                )

                if (count != null) {
                    CountPill(count, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
                }
            }

            trailingAction?.invoke(this, isHovered)
        }
    }
}

/** The name of one of Unstaged's groups (fork-only, upstream issue 345). */
@Composable
internal fun fileGroupTitle(group: FileGroup): String = stringResource(
    when (group) {
        FileGroup.Conflicts -> Res.string.files_group_conflicts
        FileGroup.Changes -> Res.string.files_group_changes
        FileGroup.Untracked -> Res.string.files_group_untracked
    }
)
