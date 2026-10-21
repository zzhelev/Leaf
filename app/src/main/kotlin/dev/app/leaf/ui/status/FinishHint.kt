// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.theme.styleColors
import dev.app.leaf.theme.onBackgroundSecondary
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One line between the commit field and its buttons that says why the primary button is disabled (fork-only, upstream
 * issue 124): the user can't tell otherwise whether a message, staged files or something else is missing. It is only
 * there while something blocks the button ([finishHint]).
 */
@Composable
fun FinishHint(blocker: FinishBlocker, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(Res.drawable.info),
            contentDescription = null,
            tint = MaterialTheme.styleColors.warningIcon,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )

        Text(
            text = blocker.text(),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun FinishBlocker.text(): String = when (this) {
    is FinishBlocker.Conflicts -> pluralStringResource(Res.plurals.finish_hint_conflicts, files, files)
    is FinishBlocker.UnstagedChanges -> pluralStringResource(Res.plurals.finish_hint_unstaged_changes, files, files)
    FinishBlocker.NothingStaged -> stringResource(Res.string.finish_hint_nothing_staged)
    is FinishBlocker.EmptyMessage -> if (commentsOnly) {
        stringResource(Res.string.finish_hint_comments_only)
    } else {
        stringResource(Res.string.finish_hint_empty_message)
    }

    is FinishBlocker.UneditedStart -> if (isTemplate || key == null) {
        stringResource(Res.string.finish_hint_unedited_template)
    } else {
        stringResource(Res.string.finish_hint_key_only, key)
    }
}
