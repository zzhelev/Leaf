// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.diff

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.extensions.removeLineDelimiters
import dev.app.leaf.domain.extensions.replaceTabs
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.ui.components.LocalSidewaysScroll
import dev.app.leaf.ui.components.SidewaysEndMargin
import dev.app.leaf.ui.components.SidewaysScroll
import dev.app.leaf.ui.components.SidewaysScrollbar
import dev.app.leaf.ui.components.scrollsSideways
import kotlin.math.max

/** The room that [LineNumber] adds around its digits: 8 dp before and 4 after. */
private val LineNumberPadding = 12.dp

/** The gap between the two sides of a split diff, as [SplitDiffLine] lays it out. */
private val SplitGap = 4.dp

/**
 * The lines of [diffResult]: wrapped while [sideways] is null, or each on one row, scrolled sideways together with Shift
 * and the wheel, a sideways swipe or the scrollbar under them. The longest line sets how far they scroll, in the side's
 * width less its line numbers and [textStart], where the text starts.
 */
@Composable
fun DiffLines(
    sideways: SidewaysScroll?,
    diffResult: DiffResult.TextDiff,
    textStart: Dp,
    content: @Composable () -> Unit,
) {
    if (sideways == null) {
        content()
        return
    }

    // Unified shows the old and the new line number, split one per side
    val sides = if (diffResult is DiffResult.TextSplit) 2 else 1
    val numberColumns = 3 - sides

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = (maxWidth - SplitGap * (sides - 1)) / sides

        FitDiffSideways(sideways, diffResult, numberColumns, viewport = side - textStart)

        CompositionLocalProvider(LocalSidewaysScroll provides sideways) {
            Column(Modifier.fillMaxSize().scrollsSideways(sideways)) {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    content()
                }

                SidewaysScrollbar(sideways)
            }
        }
    }
}

/** Fits [sideways] to the longest line of [diffResult], in a side [viewport] wide less its [numberColumns] line numbers. */
@Composable
private fun FitDiffSideways(
    sideways: SidewaysScroll,
    diffResult: DiffResult.TextDiff,
    numberColumns: Int,
    viewport: Dp,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography())
    val hunks = remember(diffResult) { diffResult.sourceHunks() }
    val longest = remember(hunks) { longestDiffLine(hunks.asSequence().flatMap { it.lines }) }
    val numberDigits = remember(hunks) { lineNumberDigits(hunks) }
    val lineWidth = remember(longest, style) { measurer.measure(longest, style, softWrap = false).size.width }
    val numbersWidth = remember(numberDigits, style) {
        measurer.measure("0".repeat(numberDigits), style, softWrap = false).size.width
    }
    val density = LocalDensity.current

    SideEffect {
        with(density) {
            sideways.fit(
                contentWidth = lineWidth + SidewaysEndMargin.toPx(),
                viewport = viewport.toPx() - numberColumns * (numbersWidth + LineNumberPadding.toPx()),
            )
        }
    }
}

/** The hunks of a unified or a split diff. */
internal fun DiffResult.TextDiff.sourceHunks(): List<Hunk> = when (this) {
    is DiffResult.Text -> hunks
    is DiffResult.TextSplit -> hunks.map { it.sourceHunk }
}

/** The most digits that a line number of [hunks] has, as the diff pads each hunk's numbers to its own highest. */
internal fun lineNumberDigits(hunks: List<Hunk>): Int =
    hunks.maxOfOrNull { hunk ->
        hunk.lines.maxOfOrNull { max(it.displayOldLineNumber, it.displayNewLineNumber).toString().length } ?: 1
    } ?: 1

/**
 * The longest of [lines] as [DiffText] shows it: with tabs as spaces, without its line ending, and cut where it cuts,
 * with the note of what it left out. Empty for no lines.
 */
internal fun longestDiffLine(lines: Sequence<Line>): String {
    var longest = ""

    for (line in lines) {
        val shown = line.text.replaceTabs().removeLineDelimiters()

        if (shown.length > longest.length) {
            longest = shown
        }
    }

    // DiffText ends a longer line with a note of what it left out, such as "… 1,234 more characters"
    return if (longest.length > MAX_DIFF_LINE_CHARACTERS) {
        longest.take(MAX_DIFF_LINE_CHARACTERS) + " … 1,000,000 more characters"
    } else {
        longest
    }
}
