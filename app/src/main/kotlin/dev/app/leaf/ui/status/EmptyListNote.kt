// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.check
import dev.app.leaf.app.generated.resources.status_empty_no_matches
import dev.app.leaf.app.generated.resources.status_empty_no_unstaged_changes
import dev.app.leaf.app.generated.resources.status_empty_nothing_staged
import dev.app.leaf.app.generated.resources.status_empty_working_tree_clean
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.theme.onBackgroundSecondary
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * What an empty Staged or Unstaged list says (fork-only, `docs/fork/window-chrome.md`, section 6), from mockup A of the
 * window chrome review: "Nothing staged", and "Working tree clean" with a check when both lists are empty.
 */
enum class EmptyListNote {
    NOTHING_STAGED,

    /** Unstaged and Staged are both empty: there's nothing to commit. */
    WORKING_TREE_CLEAN,

    /** Unstaged is empty, but files are staged, so the working tree isn't clean. */
    NO_UNSTAGED_CHANGES,

    /** The list has files, and a search shows none of them. */
    NO_MATCHES,
}

/**
 * The note for [entryType]'s list, or null while it shows rows, or has none to show yet. Staged's amended files, under
 * it while Amend is on, count as its rows.
 */
fun StatusState.emptyListNote(entryType: EntryType): EmptyListNote? {
    if (!hasStatus) return null

    val (rows, entries) = when (entryType) {
        EntryType.STAGED -> stagedRows to staged
        EntryType.UNSTAGED -> unstagedRows to unstaged
    }

    return when {
        rows.isNotEmpty() -> null
        entryType == EntryType.STAGED && amended != null -> null
        entries.isNotEmpty() -> EmptyListNote.NO_MATCHES
        entryType == EntryType.STAGED -> EmptyListNote.NOTHING_STAGED
        staged.isEmpty() -> EmptyListNote.WORKING_TREE_CLEAN
        else -> EmptyListNote.NO_UNSTAGED_CHANGES
    }
}

/** [note] in the middle of its list, in the secondary text colour. */
@Composable
fun EmptyListNoteView(note: EmptyListNote, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colors.onBackgroundSecondary

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (note == EmptyListNote.WORKING_TREE_CLEAN) {
            Icon(painterResource(Res.drawable.check), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }

        Text(
            text = stringResource(
                when (note) {
                    EmptyListNote.NOTHING_STAGED -> Res.string.status_empty_nothing_staged
                    EmptyListNote.WORKING_TREE_CLEAN -> Res.string.status_empty_working_tree_clean
                    EmptyListNote.NO_UNSTAGED_CHANGES -> Res.string.status_empty_no_unstaged_changes
                    EmptyListNote.NO_MATCHES -> Res.string.status_empty_no_matches
                },
            ),
            color = color,
            style = MaterialTheme.typography.caption,
            textAlign = TextAlign.Center,
        )
    }
}
