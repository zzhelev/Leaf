// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.styles.ContrastShortfall
import dev.app.leaf.theme.styles.ICON_CONTRAST
import dev.app.leaf.theme.styles.TEXT_CONTRAST
import dev.app.leaf.theme.styles.contrastChecks
import dev.app.leaf.theme.styles.contrastOf
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val SIDE_WIDTH = 120.dp

/** The amber of a warning chip, readable on light and dark surfaces alike as its own fill is pale. */
private val CHIP_TEXT = Color(0xFF7A4A00)
private val CHIP_FILL = Color(0xFFFBEFD6)

/**
 * Every colour of the style, light and dark, grouped (fork-only, the editor's Advanced section). A dot marks a row with
 * a colour set by hand; a click on a swatch opens the palette for it. Checks that colours set by hand leave short show
 * under their row.
 */
@Composable
fun StyleAdvancedTable(state: StyleEditorState, resolved: ResolvedDraft, onAction: (StyleEditorAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Box(Modifier.weight(1f))
            ColumnTitle(stringResource(Res.string.style_editor_light))
            ColumnTitle(stringResource(Res.string.style_editor_dark))
        }

        for (group in ADVANCED_GROUPS) {
            Text(
                text = stringResource(group.title).uppercase(),
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colors.primaryVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )

            for (row in group.rows) {
                AdvancedTableRow(row, state, resolved, onAction)
            }
        }
    }
}

@Composable
private fun ColumnTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colors.onBackgroundSecondary,
        modifier = Modifier.width(SIDE_WIDTH),
    )
}

@Composable
private fun AdvancedTableRow(row: AdvancedRow, state: StyleEditorState, resolved: ResolvedDraft, onAction: (StyleEditorAction) -> Unit) {
    val slots = row.slots.map { it.first }
    val isSetByHand = slots.any { state.isSetByHand(it, true) || state.isSetByHand(it, false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (isSetByHand) {
                    val setByHand = stringResource(Res.string.style_editor_set_by_hand)

                    DelayedTooltip(setByHand) {
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colors.primaryVariant)
                                .semantics { contentDescription = setByHand },
                        )
                    }
                }

                Text(rowLabel(row), style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onBackground)
            }

            for (isLight in listOf(true, false)) {
                Row(modifier = Modifier.width(SIDE_WIDTH), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for ((slot, part) in row.slots) {
                        SlotSwatch(slot, part, isLight, state, resolved, onAction)
                    }
                }
            }
        }

        for (isLight in listOf(true, false)) {
            val shortfalls = resolved.shortfalls(isLight).filter { it.check.foreground in slots }
            if (shortfalls.isNotEmpty()) ShortfallChip(shortfalls, isLight)
        }
    }
}

@Composable
private fun SlotSwatch(
    slot: StyleSlot,
    part: SlotPart?,
    isLight: Boolean,
    state: StyleEditorState,
    resolved: ResolvedDraft,
    onAction: (StyleEditorAction) -> Unit,
) {
    val palette = resolved.side(isLight).palette
    val color = palette[slot]
    val target = ColourTarget.Slot(slot, isLight)
    val side = stringResource(if (isLight) Res.string.style_editor_light else Res.string.style_editor_dark)
    val name = slotLabel(slot)
    val hex = StyleColorHex.format(color.toArgb())
    val shape = RoundedCornerShape(4.dp)

    Box {
        DelayedTooltip("$name ($side): $hex") {
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 20.dp)
                    .clip(shape)
                    // See-through colours show as they would on the side's background
                    .background(color.compositeOver(palette[StyleSlot.BACKGROUND]))
                    .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.15f), shape)
                    .semantics { contentDescription = "$name $side" }
                    .handMouseClickable { onAction(StyleEditorAction.OpenPicker(target)) },
            )
        }

        if (state.picker == target) {
            ColourPicker(
                title = "$name · $side",
                use = null,
                color = color.toArgb(),
                check = pickerCheck(slot, isLight, resolved),
                onPick = { onAction(StyleEditorAction.SetColour(slot, isLight, it)) },
                onUseWorkedOut = if (state.isSetByHand(slot, isLight)) {
                    { onAction(StyleEditorAction.UseWorkedOutColour(slot, isLight)) }
                } else {
                    null
                },
                onDismiss = { onAction(StyleEditorAction.OpenPicker(null)) },
            )
        }
    }
}

/** The check that [slot] takes part in and comes closest to failing, worded for the palette. */
@Composable
internal fun pickerCheck(slot: StyleSlot, isLight: Boolean, resolved: ResolvedDraft): PickerCheck? {
    val palette = resolved.side(isLight).palette
    val check = contrastChecks(resolved.draft.look)
        .filter { slot == it.foreground || slot in it.layers }
        .minByOrNull { palette.contrastOf(it) / it.minimum }
        ?: return null
    val ratio = palette.contrastOf(check)

    return PickerCheck(
        text = stringResource(Res.string.style_editor_picker_check, slotLabel(check.foreground), groundLabel(check), ratioText(ratio)),
        holds = ratio >= check.minimum,
    )
}

/** The checks of a row that fall short on one side: the worst, and how many more, which the tooltip lists. */
@Composable
private fun ShortfallChip(shortfalls: List<ContrastShortfall>, isLight: Boolean) {
    val side = stringResource(if (isLight) Res.string.style_editor_light else Res.string.style_editor_dark)
    val texts = shortfalls.sortedBy { it.ratio / it.check.minimum }.map { shortfall ->
        stringResource(
            Res.string.style_editor_shortfall,
            slotLabel(shortfall.check.foreground),
            groundLabel(shortfall.check),
            ratioText(shortfall.ratio),
            ratioText(shortfall.check.minimum),
        )
    }
    val more = texts.size - 1
    val text = "$side: " + texts.first() +
        if (more > 0) " " + pluralStringResource(Res.plurals.style_editor_shortfall_more, more, more) else ""

    DelayedTooltip(texts.joinToString("\n")) {
        Text(
            text = text,
            style = MaterialTheme.typography.caption.copy(fontSize = 11.sp),
            fontWeight = FontWeight.SemiBold,
            color = CHIP_TEXT,
            modifier = Modifier
                .padding(start = 12.dp, top = 1.dp, bottom = 3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(CHIP_FILL)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * A contrast ratio as people write it, to one decimal, except that a ratio short of a threshold never reads as the
 * threshold: 4.47 is "4.4", not "4.5".
 */
internal fun ratioText(ratio: Double): String {
    var tenths = kotlin.math.round(ratio * 10) / 10

    for (threshold in listOf(ICON_CONTRAST, TEXT_CONTRAST)) {
        if (ratio < threshold && tenths >= threshold) tenths = threshold - 0.1
    }

    return if (tenths % 1.0 == 0.0) tenths.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, tenths)
}
