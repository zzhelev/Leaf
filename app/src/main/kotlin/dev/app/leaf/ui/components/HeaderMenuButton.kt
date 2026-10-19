// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.more_horiz
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.ui.components.sort.SortMenuItem
import dev.app.leaf.ui.components.sort.SortMenuPopup
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.painterResource

private const val REOPEN_GUARD_MS = 300

/**
 * A "⋯" button for a file list's header, as big as its search button, with a menu of [items] (fork-only). Actions that
 * can lose work go here rather than next to the primary button, so that a click can't trigger one by mistake.
 */
@Composable
fun HeaderMenuButton(items: List<SortMenuItem>, tooltip: String) {
    var showMenu by remember { mutableStateOf(false) }
    // A press on the button while the menu is open first dismisses it as an outside click: don't open it again at once
    var dismissedAt by remember { mutableStateOf(0L) }

    Box {
        DelayedTooltip(tooltip) {
            IconButton(
                onClick = {
                    if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
                        showMenu = !showMenu
                    }
                },
                modifier = Modifier.handOnHover(),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.more_horiz),
                    contentDescription = tooltip,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colors.onBackground,
                )
            }
        }

        if (showMenu) {
            SortMenuPopup(
                items = items,
                onDismissRequest = {
                    showMenu = false
                    dismissedAt = System.currentTimeMillis()
                },
            )
        }
    }
}
