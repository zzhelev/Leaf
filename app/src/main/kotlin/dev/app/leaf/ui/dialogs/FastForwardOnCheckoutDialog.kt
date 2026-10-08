// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RemoteBranchCheckout
import dev.app.leaf.ui.dialogs.base.IconBasedDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val CONTENT_WIDTH = 360.dp

/** Checking out [remoteBranch] checks out [localBranch], which can be fast-forwarded to it first. */
data class FastForwardOffer(
    val remoteBranch: Branch,
    val localBranch: RemoteBranchCheckout.ChecksOutLocalBranch,
)

/**
 * Asks whether to fast-forward the local branch of a remote branch that's being checked out. [onCheckout] checks it
 * out, after the fast-forward or without it. When the local branch is already checked out, only the fast-forward is
 * left to do.
 */
@Composable
fun FastForwardOnCheckoutDialog(
    offer: FastForwardOffer,
    onCheckout: (fastForward: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val localBranch = offer.localBranch
    val isCurrentBranch = localBranch.isCurrentBranch

    IconBasedDialog(
        icon = painterResource(Res.drawable.download),
        title = if (isCurrentBranch) {
            stringResource(Res.string.fast_forward_dialog_title_current)
        } else {
            stringResource(Res.string.fast_forward_dialog_title_checkout)
        },
        subtitle = pluralStringResource(
            if (isCurrentBranch) {
                Res.plurals.fast_forward_dialog_subtitle_current
            } else {
                Res.plurals.fast_forward_dialog_subtitle_checkout
            },
            localBranch.commitsBehind,
            localBranch.commitsBehind,
            localBranch.localBranch,
            offer.remoteBranch.simpleNameWithRemote,
        ),
        primaryActionText = stringResource(Res.string.fast_forward_dialog_fast_forward),
        secondaryActionText = if (isCurrentBranch) {
            null
        } else {
            stringResource(Res.string.fast_forward_dialog_check_out_only)
        },
        onSecondaryActionClicked = { onCheckout(false) },
        onDismiss = onDismiss,
        onPrimaryActionClicked = { onCheckout(true) },
        beforeActionsFocusRequester = null,
        actionsFocusRequester = null,
        afterActionsFocusRequester = null,
    ) {
        // The dialog is as wide as its content, so this keeps the subtitle on fewer lines
        Spacer(modifier = Modifier.width(CONTENT_WIDTH))
    }
}
