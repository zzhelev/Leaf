// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.diff_renamed_similar
import dev.app.leaf.app.generated.resources.diff_renamed_unchanged
import dev.app.leaf.domain.extensions.renamedFrom
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.sorting.RenamedFrom
import dev.app.leaf.theme.onBackgroundSecondary
import org.jetbrains.compose.resources.stringResource

/**
 * Where the file of a renamed file's diff came from, when the diff has nothing to show: a rename that didn't change
 * the content has no hunks, which left the pane blank. Null for any other diff.
 */
fun DiffResult.renamedWithoutChanges(): RenamedFrom? {
    val noHunks = when (this) {
        is DiffResult.Text -> hunks.isEmpty()
        is DiffResult.TextSplit -> hunks.isEmpty()
        else -> false
    }

    return if (noHunks) diffEntry.renamedFrom else null
}

/**
 * After the file's name in the diff header: `← src/ui/old/Button.kt · 92% similar`. When the header is narrow, the old
 * path is cut short and how much is alike stays.
 */
@Composable
fun RowScope.RenamedFromHeader(renamedFrom: RenamedFrom) {
    Row(
        modifier = Modifier
            .weight(1f, fill = false)
            .padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u2190 ${renamedFrom.path}",
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        if (!renamedFrom.isContentUnchanged) {
            Text(
                text = "\u00B7 " + stringResource(Res.string.diff_renamed_similar, "${renamedFrom.similarity}%"),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onBackgroundSecondary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The body of the diff of a file that was renamed without changing its content. */
@Composable
fun RenamedWithoutChanges(renamedFrom: RenamedFrom) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.diff_renamed_unchanged, renamedFrom.path),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp),
        )
    }
}
