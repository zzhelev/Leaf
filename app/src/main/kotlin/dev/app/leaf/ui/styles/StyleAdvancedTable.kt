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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.components.tooltip.DelayedTooltip
import org.jetbrains.compose.resources.stringResource

private val SIDE_WIDTH = 120.dp

/**
 * Every colour of the style, light and dark, grouped (fork-only, the editor's Advanced section). A dot marks a row with
 * a colour set by hand; a click on a swatch opens the palette for it.
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
