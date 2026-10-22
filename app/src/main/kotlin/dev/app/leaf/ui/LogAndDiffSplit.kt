// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.CenterSplitSizes
import dev.app.leaf.ui.components.SectionDivider
import dev.app.leaf.ui.components.coveringWhatIsBelow

/**
 * The centre pane while a diff is open (fork-only, upstream issues 343, 136 and 128): the log above and the diff below it,
 * with a divider that resizes them. Where the log can't stay, with [keepsLog] off or a pane too short for both, the diff
 * is drawn over the log, as it was before the log stayed: the log is composed under it, so closing the diff doesn't
 * compose it again.
 *
 * [log] is given the modifier to put on it, which lets the log take the focus only while it shows. The log stays at the
 * same place in the tree in both layouts, so switching between them doesn't compose it again or make it forget its focus.
 */
@Composable
fun LogAndDiffSplit(
    modifier: Modifier,
    sizes: CenterSplitSizes,
    keepsLog: Boolean,
    /** A diff is open, or stands open with its file missing from the commit. */
    isDiffShown: Boolean,
    /** The sizes while the divider is dragged. */
    onSizesChange: (CenterSplitSizes) -> Unit,
    onSizesChangeFinished: () -> Unit,
    onResetSizes: () -> Unit,
    log: @Composable (Modifier) -> Unit,
    diff: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val paneHeight = maxHeight.value
        val heights = if (keepsLog && isDiffShown) sizes.fitTo(paneHeight) else null

        // Several moves of the divider can come before the next composition, so each starts from the last one's result
        val dragged = remember { object { var sizes = sizes } }
        SideEffect { dragged.sizes = sizes }

        Column(Modifier.fillMaxSize()) {
            Box(
                if (heights != null) Modifier.fillMaxWidth().height(heights.log.dp)
                else Modifier.fillMaxWidth().weight(1f, true)
            ) {
                log(Modifier.focusable(heights != null || !isDiffShown))

                if (isDiffShown && heights == null) {
                    Box(Modifier.coveringWhatIsBelow()) { diff() }
                }
            }

            if (heights != null) {
                SectionDivider(
                    onDrag = { delta ->
                        dragged.sizes = dragged.sizes.withDividerMoved(paneHeight, delta)
                        onSizesChange(dragged.sizes)
                    },
                    onDragStopped = onSizesChangeFinished,
                    onDoubleClick = onResetSizes,
                )

                Box(Modifier.fillMaxWidth().weight(1f, true)) { diff() }
            }
        }
    }
}
