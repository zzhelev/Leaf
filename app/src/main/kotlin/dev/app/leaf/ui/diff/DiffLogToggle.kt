// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.diff_log_toggle_hide
import dev.app.leaf.app.generated.resources.diff_log_toggle_show
import dev.app.leaf.app.generated.resources.fullscreen
import dev.app.leaf.app.generated.resources.fullscreen_exit
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The diff header's button that gives the diff the whole centre pane, as it had before the log stayed above it, and
 * shows the log again (fork-only). It stays on while the log is hidden.
 */
@Composable
fun DiffLogToggle(
    keepsLog: Boolean,
    onKeepsLogChanged: (Boolean) -> Unit,
) {
    DelayedTooltip(
        stringResource(if (keepsLog) Res.string.diff_log_toggle_hide else Res.string.diff_log_toggle_show)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .handMouseClickable { onKeepsLogChanged(!keepsLog) }
                .padding(4.dp)
        ) {
            Icon(
                painter = painterResource(if (keepsLog) Res.drawable.fullscreen else Res.drawable.fullscreen_exit),
                contentDescription = null,
                tint = MaterialTheme.colors.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
