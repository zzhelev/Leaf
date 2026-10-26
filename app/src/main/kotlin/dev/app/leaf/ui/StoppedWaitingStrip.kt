// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.check
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.stopped_waiting_asking_delete
import dev.app.leaf.app.generated.resources.stopped_waiting_asking_push
import dev.app.leaf.app.generated.resources.stopped_waiting_deleted
import dev.app.leaf.app.generated.resources.stopped_waiting_dismiss
import dev.app.leaf.app.generated.resources.stopped_waiting_failed
import dev.app.leaf.app.generated.resources.stopped_waiting_fetch
import dev.app.leaf.app.generated.resources.stopped_waiting_not_deleted
import dev.app.leaf.app.generated.resources.stopped_waiting_not_deleted_moved
import dev.app.leaf.app.generated.resources.stopped_waiting_not_pushed
import dev.app.leaf.app.generated.resources.stopped_waiting_not_pushed_moved
import dev.app.leaf.app.generated.resources.stopped_waiting_not_pushed_no_branch
import dev.app.leaf.app.generated.resources.stopped_waiting_pushed
import dev.app.leaf.app.generated.resources.stopped_waiting_stop_checking
import dev.app.leaf.app.generated.resources.stopped_waiting_try_again
import dev.app.leaf.app.generated.resources.stopped_waiting_unknown_delete
import dev.app.leaf.app.generated.resources.stopped_waiting_unknown_push
import dev.app.leaf.app.generated.resources.warning
import dev.app.leaf.domain.remote.StoppedWaitingState
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.SecondaryButton
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Under the toolbar, once the user stopped waiting for a server that had a push or a delete (fork-only): what Leaf asks
 * it, then what it answered. The processing screen is gone by then and the tab can be used, so it stays until it's
 * closed, as a toast would be gone before the answer came.
 */
@Composable
fun StoppedWaitingStrip(
    state: StoppedWaitingState,
    onStopChecking: () -> Unit,
    onFetch: () -> Unit,
    onTryAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val handOff = state.handOff
    val remote = handOff.remote
    val branch = handOff.branchName

    when (state) {
        is StoppedWaitingState.Asking -> StripRow(
            colors = askingColors(),
            icon = { tint ->
                // In the icons' 16 dp, so that the text starts where theirs does
                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = tint,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                }
            },
            text = if (handOff.isDelete) {
                stringResource(Res.string.stopped_waiting_asking_delete, remote, branch)
            } else {
                stringResource(Res.string.stopped_waiting_asking_push, remote, branch)
            },
            actions = {
                SecondaryButton(text = stringResource(Res.string.stopped_waiting_stop_checking), onClick = onStopChecking)
            },
            onDismiss = null,
        )

        is StoppedWaitingState.Done -> StripRow(
            colors = MaterialTheme.styleColors.successStrip.let { StripRowColors(it.background, it.border, it.content, it.icon) },
            icon = { tint -> StripIcon(Res.drawable.check, tint) },
            text = if (handOff.isDelete) {
                stringResource(Res.string.stopped_waiting_deleted, remote, branch)
            } else {
                stringResource(Res.string.stopped_waiting_pushed, remote, branch, handOff.pushed.orEmpty().take(7))
            },
            actions = {},
            onDismiss = onDismiss,
        )

        is StoppedWaitingState.NotDone -> {
            val now = state.now
            val asBefore = now == handOff.before

            WarningStrip(
                text = when {
                    handOff.isDelete && asBefore -> stringResource(Res.string.stopped_waiting_not_deleted, remote, branch)
                    handOff.isDelete ->
                        stringResource(Res.string.stopped_waiting_not_deleted_moved, remote, branch, now.orEmpty().take(7))
                    now == null -> stringResource(Res.string.stopped_waiting_not_pushed_no_branch, remote, branch)
                    asBefore -> stringResource(Res.string.stopped_waiting_not_pushed, remote, branch, now.take(7))
                    else -> stringResource(Res.string.stopped_waiting_not_pushed_moved, remote, branch, now.take(7))
                },
                action = stringResource(Res.string.stopped_waiting_fetch),
                onAction = onFetch,
                onDismiss = onDismiss,
            )
        }

        is StoppedWaitingState.Unknown -> WarningStrip(
            text = if (handOff.isDelete) {
                stringResource(Res.string.stopped_waiting_unknown_delete, remote, branch)
            } else {
                stringResource(Res.string.stopped_waiting_unknown_push, remote, branch)
            },
            action = stringResource(Res.string.stopped_waiting_fetch),
            onAction = onFetch,
            onDismiss = onDismiss,
        )

        is StoppedWaitingState.Failed -> {
            val reason = state.error.getErrorText().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

            WarningStrip(
                text = stringResource(Res.string.stopped_waiting_failed, remote, branch, reason),
                action = stringResource(Res.string.stopped_waiting_try_again),
                onAction = onTryAgain,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun WarningStrip(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    val strip = MaterialTheme.styleColors.warningStrip

    StripRow(
        colors = StripRowColors(strip.background, strip.border, strip.content, strip.icon),
        icon = { tint -> StripIcon(Res.drawable.warning, tint) },
        text = text,
        actions = { SecondaryButton(text = action, colors = strip.button, onClick = onAction) },
        onDismiss = onDismiss,
    )
}

private class StripRowColors(val background: Color, val border: Color, val content: Color, val icon: Color)

/** While Leaf asks: nothing to warn about yet, so the panes' second surface, as the diff's notes have. */
@Composable
private fun askingColors() = StripRowColors(
    background = MaterialTheme.colors.secondarySurface,
    border = MaterialTheme.colors.onBackground.copy(alpha = 0.15f),
    content = MaterialTheme.colors.onBackground,
    icon = MaterialTheme.colors.onBackgroundSecondary,
)

@Composable
private fun StripRow(
    colors: StripRowColors,
    icon: @Composable (tint: Color) -> Unit,
    text: String,
    actions: @Composable () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .bottomEdge(colors.border)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon(colors.icon)

        Text(
            text = text,
            color = colors.content,
            style = MaterialTheme.typography.body2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )

        actions()

        if (onDismiss != null) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .handOnHover(),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.close),
                    contentDescription = stringResource(Res.string.stopped_waiting_dismiss),
                    tint = colors.content,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StripIcon(icon: DrawableResource, tint: Color) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}
