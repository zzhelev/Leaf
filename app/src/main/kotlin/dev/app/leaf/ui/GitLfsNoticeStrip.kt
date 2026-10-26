// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.git_lfs_notice_dismiss
import dev.app.leaf.app.generated.resources.git_lfs_notice_install
import dev.app.leaf.app.generated.resources.git_lfs_notice_install_tooltip
import dev.app.leaf.app.generated.resources.git_lfs_notice_missing
import dev.app.leaf.app.generated.resources.git_lfs_notice_not_installed
import dev.app.leaf.app.generated.resources.git_lfs_notice_not_set_up
import dev.app.leaf.app.generated.resources.git_lfs_notice_set_up
import dev.app.leaf.app.generated.resources.git_lfs_notice_set_up_tooltip
import dev.app.leaf.app.generated.resources.warning
import dev.app.leaf.domain.lfs.GitLfsNotice
import dev.app.leaf.extensions.handOnHover
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.tooltip.InstantTooltip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Under the toolbar while a repository uses LFS and git outside Leaf has no working LFS filter (fork-only, open
 * question 2 of `docs/fork/lfs-filter.md`): the git commands that Leaf runs get Leaf's own, so the working tree has the
 * files' content, which a terminal's or an agent's git commits whole, or fails on. Its button installs git-lfs
 * (git-lfs.com) or sets it up (`git lfs install`), right after the text, and × hides that notice for the repository.
 */
@Composable
fun GitLfsNoticeStrip(
    notice: GitLfsNotice,
    onInstall: () -> Unit,
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strip = MaterialTheme.styleColors.warningStrip

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(strip.background)
            .bottomEdge(strip.border)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The text only as wide as it needs, so that the button follows it rather than the window's edge
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.warning),
                contentDescription = null,
                tint = strip.icon,
                modifier = Modifier.size(16.dp),
            )

            Text(
                text = gitLfsNoticeText(notice),
                color = strip.content,
                style = MaterialTheme.typography.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 4.dp),
            )

            InstantTooltip(
                text = if (notice.needsInstall) {
                    stringResource(Res.string.git_lfs_notice_install_tooltip)
                } else {
                    stringResource(Res.string.git_lfs_notice_set_up_tooltip)
                },
            ) {
                SecondaryButton(
                    text = if (notice.needsInstall) {
                        stringResource(Res.string.git_lfs_notice_install)
                    } else {
                        stringResource(Res.string.git_lfs_notice_set_up)
                    },
                    colors = strip.button,
                    onClick = if (notice.needsInstall) onInstall else onSetUp,
                )
            }
        }

        val dismiss = stringResource(Res.string.git_lfs_notice_dismiss)

        InstantTooltip(text = dismiss) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(32.dp)
                    .handOnHover(),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.close),
                    contentDescription = dismiss,
                    tint = strip.content,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** What git outside Leaf does with the repository's LFS files, and why. */
@Composable
internal fun gitLfsNoticeText(notice: GitLfsNotice): String = when (notice) {
    GitLfsNotice.NOT_INSTALLED -> stringResource(Res.string.git_lfs_notice_not_installed)
    GitLfsNotice.MISSING -> stringResource(Res.string.git_lfs_notice_missing)
    GitLfsNotice.NOT_SET_UP -> stringResource(Res.string.git_lfs_notice_not_set_up)
}
