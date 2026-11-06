// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.chevron_right
import dev.app.leaf.app.generated.resources.expand_more
import dev.app.leaf.app.generated.resources.section_folded
import dev.app.leaf.app.generated.resources.section_open
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.onBackgroundSecondary
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * A section of a pane that folds to its header (fork-only): the status pane's Staged, Unstaged and Commit message. A
 * click on the header, but on its buttons, folds the section or opens it.
 */
@Immutable
data class SectionFold(val isFolded: Boolean, val onToggle: () -> Unit)

/** What a header that folds its section does on a click, and says to screen readers (fork-only). */
@Composable
fun Modifier.foldsSection(fold: SectionFold?): Modifier {
    if (fold == null) return this

    val state = stringResource(if (fold.isFolded) Res.string.section_folded else Res.string.section_open)

    return this
        .handMouseClickable { fold.onToggle() }
        .semantics { stateDescription = state }
}

/** The chevron at a folding header's start, as the branches pane's sections have it (fork-only). */
@Composable
fun FoldChevron(isFolded: Boolean) {
    Icon(
        painter = painterResource(if (isFolded) Res.drawable.chevron_right else Res.drawable.expand_more),
        contentDescription = null,
        tint = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier
            .padding(start = 10.dp, end = 6.dp)
            .size(14.dp),
    )
}
