// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.log_selected_commits_clear_tooltip
import dev.app.leaf.app.generated.resources.log_selected_commits_title
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.repositoryopen.SelectedCommits
import dev.app.leaf.theme.linesHeight
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.compare.CommitMark
import dev.app.leaf.ui.compare.HeaderButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the right pane shows for commits selected in the log with Shift (fork-only, step A2 of
 * `docs/fork/squash-and-rebase.md`): how many, and each one's subject and short hash, newest first, as the log has them.
 * [onClear] ends the range, as Esc does.
 */
@Composable
fun SelectedCommitsPane(selected: SelectedCommits, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(MaterialTheme.colors.tertiarySurface)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val count = selected.commits.size

            Text(
                text = pluralStringResource(Res.plurals.log_selected_commits_title, count, count),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            HeaderButton(
                icon = Res.drawable.close,
                description = stringResource(Res.string.log_selected_commits_clear_tooltip),
                onClick = onClear,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colors.background),
        ) {
            items(selected.commits, key = { it.hash }) { commit ->
                SelectedCommitRow(commit)
            }
        }
    }
}

@Composable
private fun SelectedCommitRow(commit: Commit) {
    DelayedTooltip(commit.message.trim()) {
        Row(
            modifier = Modifier
                // Follows Settings → Appearance → Lists spacing, as the log does
                .height(MaterialTheme.linesHeight.logCommitHeight)
                .fillMaxWidth()
                // Read as one: its subject, then its hash
                .semantics(mergeDescendants = true) {}
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommitMark(modifier = Modifier.padding(end = 8.dp))

            Text(
                text = commit.shortMessage,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = commit.shortHash,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
