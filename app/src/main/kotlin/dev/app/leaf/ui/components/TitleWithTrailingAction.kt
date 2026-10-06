// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * A title followed by a right-aligned action, where the title keeps priority: when both don't fit, the action gets
 * what is left after the full title, but never less than [actionMinWidth], and the title ellipsizes only then.
 */
@Composable
fun TitleWithTrailingAction(
    title: @Composable () -> Unit,
    action: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actionMinWidth: Dp = 48.dp,
) {
    Layout(contents = listOf(title, action), modifier = modifier) { (titleMeasurables, actionMeasurables), constraints ->
        val titleMeasurable = titleMeasurables.single()
        val actionMeasurable = actionMeasurables.single()
        val maxWidth = constraints.maxWidth
        val titleFullWidth = titleMeasurable.maxIntrinsicWidth(constraints.maxHeight)
        val actionFullWidth = actionMeasurable.maxIntrinsicWidth(constraints.maxHeight)

        val actionWidth = if (titleFullWidth + actionFullWidth <= maxWidth) {
            actionFullWidth
        } else {
            max(maxWidth - titleFullWidth, min(actionFullWidth, actionMinWidth.roundToPx()))
        }.coerceIn(0, maxWidth)

        val actionPlaceable = actionMeasurable.measure(Constraints(maxWidth = actionWidth, maxHeight = constraints.maxHeight))
        val titlePlaceable = titleMeasurable.measure(
            Constraints(maxWidth = (maxWidth - actionPlaceable.width).coerceAtLeast(0), maxHeight = constraints.maxHeight)
        )
        val height = max(titlePlaceable.height, actionPlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(maxWidth, height) {
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            actionPlaceable.place(maxWidth - actionPlaceable.width, (height - actionPlaceable.height) / 2)
        }
    }
}
