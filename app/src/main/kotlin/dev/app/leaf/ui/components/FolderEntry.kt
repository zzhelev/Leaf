// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.chevron_right
import dev.app.leaf.app.generated.resources.expand_more
import dev.app.leaf.app.generated.resources.folder
import dev.app.leaf.app.generated.resources.folder_open
import dev.app.leaf.theme.onBackgroundSecondary
import org.jetbrains.compose.resources.painterResource

/**
 * The content of a folder row: `[chevron] [folder icon] name ........ [count]`. The caller provides the row size,
 * click handling and background.
 */
@Composable
fun RowScope.FolderEntryContent(
    label: String,
    count: Int,
    isExpanded: Boolean,
    startPadding: Dp,
    iconTint: Color = MaterialTheme.colors.onBackground,
) {
    Icon(
        painter = painterResource(if (isExpanded) Res.drawable.expand_more else Res.drawable.chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier
            .padding(start = startPadding, end = 4.dp)
            .size(14.dp),
    )

    Icon(
        painter = painterResource(if (isExpanded) Res.drawable.folder_open else Res.drawable.folder),
        contentDescription = null,
        tint = iconTint,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(16.dp),
    )

    Text(
        text = label,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.body2,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.onBackground,
    )

    CountPill(count, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
}

@Composable
fun CountPill(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colors.onBackground.copy(alpha = 0.08f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
        )
    }
}
