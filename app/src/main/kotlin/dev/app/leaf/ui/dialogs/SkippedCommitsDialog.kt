// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.generic_button_ok
import dev.app.leaf.app.generated.resources.skipped_commits_cherry_pick_back
import dev.app.leaf.app.generated.resources.skipped_commits_text
import dev.app.leaf.app.generated.resources.skipped_commits_title
import dev.app.leaf.app.generated.resources.skipped_commits_title_branch
import dev.app.leaf.app.generated.resources.warning
import dev.app.leaf.domain.rebase.SkippedCommitsNotice
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.dialogs.base.MaterialDialog
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Lists the commits that Skip left out of a rebase that has ended (fork-only): they're no longer on the branch, but
 * still in the repository, and can be cherry-picked back. A toast would be gone before it was read.
 */
@Composable
fun SkippedCommitsDialog(
    notice: SkippedCommitsNotice,
    onCherryPickBack: () -> Unit,
    onAccept: () -> Unit,
) {
    MaterialDialog(onCloseRequested = onAccept) {
        SkippedCommitsContent(notice, onCherryPickBack, onAccept)
    }
}

/** [SkippedCommitsDialog]'s content, which a test renders without a window. */
@Composable
fun SkippedCommitsContent(
    notice: SkippedCommitsNotice,
    onCherryPickBack: () -> Unit,
    onAccept: () -> Unit,
) {
    val count = notice.commits.size

    Column(modifier = Modifier.width(520.dp)) {
        Row {
            Text(
                text = when (val branch = notice.branch) {
                    null -> stringResource(Res.string.skipped_commits_title)
                    else -> stringResource(Res.string.skipped_commits_title_branch, branch)
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
            )

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                painterResource(Res.drawable.warning),
                contentDescription = null,
                tint = MaterialTheme.styleColors.warningIcon,
                modifier = Modifier.size(24.dp),
            )
        }

        Text(
            text = pluralStringResource(Res.plurals.skipped_commits_text, count, count),
            color = MaterialTheme.colors.onBackground,
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 16.dp),
        )

        // The hashes can be copied, for a terminal or the log's search
        SelectionContainer {
            Text(
                text = buildAnnotatedString {
                    notice.commits.forEachIndexed { index, commit ->
                        if (index > 0) append("\n")

                        withStyle(SpanStyle(fontFamily = monoTypography())) { append(commit.shortHash) }
                        append("  ")
                        append(commit.subject)
                    }
                },
                color = MaterialTheme.colors.onBackground,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 32.dp),
        ) {
            PrimaryButton(
                text = pluralStringResource(Res.plurals.skipped_commits_cherry_pick_back, count),
                onClick = onCherryPickBack,
                backgroundColor = Color.Transparent,
                textColor = MaterialTheme.colors.onBackground,
            )

            PrimaryButton(
                text = stringResource(Res.string.generic_button_ok),
                onClick = onAccept,
            )
        }
    }
}
