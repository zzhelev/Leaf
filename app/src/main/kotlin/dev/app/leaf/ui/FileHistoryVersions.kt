// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.close
import dev.app.leaf.app.generated.resources.compare_arrows
import dev.app.leaf.app.generated.resources.compare_badge_a
import dev.app.leaf.app.generated.resources.compare_badge_b
import dev.app.leaf.app.generated.resources.compare_swap
import dev.app.leaf.app.generated.resources.file_history_versions_apart
import dev.app.leaf.app.generated.resources.file_history_versions_end
import dev.app.leaf.app.generated.resources.file_history_versions_same
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.compare.CompareBadge
import dev.app.leaf.ui.compare.CompareLetter
import dev.app.leaf.ui.compare.HeaderButton
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import dev.app.leaf.ui.context_menu.compareClickName
import dev.app.leaf.viewmodels.FileVersions
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What file history needs to compare two versions of the file (fork-only, `docs/fork/compare.md`, 7.3): the two shown,
 * and what ⌘-click, the menu's "Compare with selected", swap and close do.
 */
class FileVersionsActions(
    val versions: FileVersions?,
    /** ⌘-click on a row, and the menu's "Compare with selected". */
    val onPick: (Commit) -> Unit,
    val onSwap: () -> Unit,
    /** Back to one commit: the close button and Esc. */
    val onEnd: () -> Unit,
)

/**
 * The strip above the diff while two versions are compared, in place of the selected commit's: A and B, each with its
 * commit and subject (the subjects are cut first), how many of the file's commits apart they are, the diff's View menu,
 * swap and back to one commit.
 */
@Composable
fun FileVersionsStrip(
    versions: FileVersions,
    distance: Int,
    onSwap: () -> Unit,
    onEnd: () -> Unit,
    viewMenu: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colors.surface)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VersionSide(CompareLetter.A, versions.a)

            Text(text = "→", color = MaterialTheme.colors.onBackgroundSecondary, style = MaterialTheme.typography.body2)

            VersionSide(CompareLetter.B, versions.b)

            Text(
                text = pluralStringResource(Res.plurals.file_history_versions_apart, distance, distance),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
            )
        }

        viewMenu?.invoke()

        HeaderButton(
            icon = Res.drawable.compare_arrows,
            description = stringResource(Res.string.compare_swap),
            rotation = 90f,
            onClick = onSwap,
        )

        HeaderButton(
            icon = Res.drawable.close,
            description = stringResource(Res.string.file_history_versions_end),
            onClick = onEnd,
        )
    }
}

/** A side's letter, its commit and its subject, which is cut first; the whole message is the tooltip. */
@Composable
private fun RowScope.VersionSide(letter: CompareLetter, commit: Commit) {
    DelayedTooltip(
        text = remember(commit.message) { commit.message.forTooltip().ifBlank { null } },
        modifier = Modifier.weight(1f, fill = false),
    ) {
        Row(
            // Read as one: "A e4f5a6b Select a parent from the commit details"
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            CompareBadge(letter)

            Text(
                text = commit.shortHash,
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.onBackground,
                maxLines = 1,
            )

            Text(
                text = commit.shortMessage,
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** In place of the diff when the two versions are the same: a change and its revert, or a commit that only moved it. */
@Composable
fun SameVersionsNote() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background),
    ) {
        Text(
            text = stringResource(Res.string.file_history_versions_same),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** The A or B badge at the end of a row of file history, whose tooltip says how to leave the row out. */
@Composable
fun VersionRowBadge(letter: CompareLetter, modifier: Modifier = Modifier) {
    val text = stringResource(
        if (letter == CompareLetter.A) Res.string.compare_badge_a else Res.string.compare_badge_b,
        compareClickName(),
    )

    DelayedTooltip(text, modifier = modifier) {
        CompareBadge(letter)
    }
}
