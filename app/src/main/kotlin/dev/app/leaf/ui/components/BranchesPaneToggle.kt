// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.branches_pane_hide
import dev.app.leaf.app.generated.resources.branches_pane_show
import dev.app.leaf.app.generated.resources.side_panel
import dev.app.leaf.app.generated.resources.side_panel_open
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.extensions.ignoreKeyEvents
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.keyBinding
import dev.app.leaf.ui.KeybindingHint
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import dev.app.leaf.ui.components.tooltip.InstantTooltipPosition
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The bottom bar's button that folds the branches pane away and shows it again (fork-only). Its icon is a panel on the
 * left, filled while the pane shows and empty while it's folded. It stays where it is either way, under the pane's column,
 * so one click brings the pane back.
 */
@Composable
fun BranchesPaneToggle(
    isFolded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val binding = KeybindingOption.BRANCHES_PANE.keyBinding

    InstantTooltip(
        text = stringResource(if (isFolded) Res.string.branches_pane_show else Res.string.branches_pane_hide),
        trailingContent = binding?.let { { KeybindingHint(it) } },
        position = InstantTooltipPosition.TOP,
    ) {
        Box(
            modifier = modifier
                .size(26.dp)
                .ignoreKeyEvents()
                .clip(RoundedCornerShape(4.dp))
                .handMouseClickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (isFolded) Res.drawable.side_panel else Res.drawable.side_panel_open),
                contentDescription = stringResource(if (isFolded) Res.string.branches_pane_show else Res.string.branches_pane_hide),
                tint = MaterialTheme.colors.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
