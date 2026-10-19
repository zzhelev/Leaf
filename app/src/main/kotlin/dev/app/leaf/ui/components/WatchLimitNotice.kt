// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.refresh.WATCH_LIMIT
import dev.app.leaf.domain.refresh.WatchUsage
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltipPosition
import dev.app.leaf.ui.status.OnConflictColor
import org.jetbrains.compose.resources.stringResource

/**
 * Fork-only: tells that the file watchers hold about as many folders as macOS delivers events for, so that the user
 * closes some repositories. Past the limit, changes in the folders registered last go unreported, silently.
 */
@Composable
fun WatchLimitNotice(usage: WatchUsage, modifier: Modifier = Modifier) {
    InstantTooltip(
        text = stringResource(
            Res.string.bottom_info_bar_watch_limit_tooltip,
            usage.registrations,
            WATCH_LIMIT,
            usage.tabs,
        ),
        position = InstantTooltipPosition.TOP,
        maxLines = 5,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colors.conflictFile)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(Res.drawable.warning),
                contentDescription = null,
                tint = OnConflictColor,
                modifier = Modifier.size(14.dp),
            )

            Text(
                text = stringResource(Res.string.bottom_info_bar_watch_limit),
                style = MaterialTheme.typography.body2,
                color = OnConflictColor,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
