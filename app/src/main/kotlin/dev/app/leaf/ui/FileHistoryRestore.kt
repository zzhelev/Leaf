// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.check
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.file_history_restored
import dev.app.leaf.app.generated.resources.file_history_restored_dismiss
import dev.app.leaf.app.generated.resources.file_history_restored_show_changes
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.theme.addFile
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.viewmodels.RestoredVersion
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Dark text on the added-file green, which is the same in the light and the dark theme. */
private val OnRestoredColor = Color(0xFF1E1E1E)

/**
 * Under the header of file history, once a version of the file was restored: the status pane, where the file's change
 * shows, isn't on screen here, and a toast is gone before it's read. It stays until it's dismissed.
 */
@Composable
fun RestoredVersionStrip(
    restored: RestoredVersion,
    onShowChanges: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colors.addFile)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.check),
            contentDescription = null,
            tint = OnRestoredColor,
            modifier = Modifier.size(16.dp),
        )

        Text(
            text = stringResource(Res.string.file_history_restored, restored.commit.shortHash),
            color = OnRestoredColor,
            style = MaterialTheme.typography.body2,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )

        SecondaryButton(
            text = stringResource(Res.string.file_history_restored_show_changes),
            textColor = Color.White,
            backgroundButton = OnRestoredColor,
            onClick = onShowChanges,
        )

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(32.dp)
                .handOnHover(),
        ) {
            Icon(
                painter = painterResource(Res.drawable.close),
                contentDescription = stringResource(Res.string.file_history_restored_dismiss),
                tint = OnRestoredColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * A commit's whole message for a tooltip, which has no width of its own: lines are broken at [width] columns, between
 * words, and it stops after [maxLines] lines with an ellipsis.
 */
fun String.forTooltip(width: Int = 72, maxLines: Int = 20): String {
    val lines = trim().replace("\r\n", "\n").lines().flatMap { line ->
        val broken = mutableListOf<String>()
        var current = ""

        for (word in line.split(' ')) {
            current = when {
                current.isEmpty() -> word
                current.length + 1 + word.length <= width -> "$current $word"
                else -> {
                    broken += current
                    word
                }
            }
        }

        broken + current
    }

    return if (lines.size <= maxLines) lines.joinToString("\n") else lines.take(maxLines).joinToString("\n") + "\n…"
}
