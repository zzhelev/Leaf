// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * "4 selected ✕" in the header of the local branches while two or more are selected (fork-only, upstream issue 116).
 * A click clears the selection, as Esc does.
 */
@Composable
fun BranchSelectionChip(count: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    DelayedTooltip(stringResource(Res.string.side_pane_branches_clear_selection_tooltip)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colors.secondarySurface)
                .handMouseClickable(onClick = onClear)
                .padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                text = stringResource(Res.string.side_pane_branches_selected, count),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colors.primaryVariant,
                maxLines = 1,
            )

            Icon(
                painter = painterResource(Res.drawable.close),
                contentDescription = null,
                tint = MaterialTheme.colors.primaryVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(12.dp),
            )
        }
    }
}
