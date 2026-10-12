// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.canOpenInMergeTool
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.status.label
import dev.app.leaf.ui.status.summary
import org.jetbrains.compose.resources.stringResource

/**
 * A strip under the diff header of a file with conflicts (fork-only): which branch or commit is ours and which is
 * theirs, as few people remember it, then the external merge tool, when `git mergetool` can merge the file, and "Use
 * ours" and "Use theirs". The header itself had no room for them in a narrow diff pane.
 */
@Composable
fun ConflictBar(
    statusEntry: StatusEntry,
    sides: ConflictSides?,
    onOpenInExternalMergeTool: () -> Unit,
    onUseSide: (ConflictSide) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(MaterialTheme.colors.tertiarySurface)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Gives way to the buttons when the pane is narrow
        Text(
            text = sides?.summary().orEmpty(),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (statusEntry.canOpenInMergeTool) {
            DelayedTooltip(stringResource(Res.string.diff_header_open_in_external_merge_tool_tooltip)) {
                SecondaryButton(
                    text = stringResource(Res.string.diff_header_open_in_external_merge_tool),
                    onClick = onOpenInExternalMergeTool,
                )
            }
        }

        for (side in ConflictSide.entries) {
            val (text, tooltip) = when (side) {
                ConflictSide.OURS -> Res.string.diff_header_use_ours to Res.string.diff_header_use_ours_tooltip
                ConflictSide.THEIRS -> Res.string.diff_header_use_theirs to Res.string.diff_header_use_theirs_tooltip
            }

            DelayedTooltip(sides?.label(side)?.let { stringResource(tooltip, it) }) {
                SecondaryButton(
                    text = stringResource(text),
                    textColor = MaterialTheme.colors.onBackground,
                    backgroundButton = MaterialTheme.colors.secondarySurface,
                    onClick = { onUseSide(side) },
                )
            }
        }
    }
}
