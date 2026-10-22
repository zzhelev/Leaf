// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.styles.Oklch
import dev.app.leaf.domain.styles.StyleColorHex
import dev.app.leaf.domain.styles.StyleColorMath
import dev.app.leaf.extensions.handMouseClickable
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.ui.BelowLeftAlignedPositionProvider
import dev.app.leaf.ui.components.AdjustableOutlinedTextField
import org.jetbrains.compose.resources.stringResource

/** The hues of the palette, in degrees (option 2a of the "Custom Style" board). */
private val PALETTE_HUES = listOf(25.0, 45.0, 70.0, 95.0, 130.0, 150.0, 175.0, 195.0, 230.0, 255.0, 290.0, 330.0)

/** Its shades, lightness and chroma, from pale to deep. */
private val PALETTE_SHADES = listOf(0.94 to 0.035, 0.86 to 0.075, 0.74 to 0.11, 0.62 to 0.12, 0.50 to 0.10, 0.38 to 0.08)

/**
 * The palette (fork-only): 12 hues by 6 shades in OKLCH, so that a shade has the same lightness in every hue, and a
 * row of 12 greys from white to near black, which backgrounds need.
 */
val PALETTE: List<List<Int>> = PALETTE_SHADES.map { (l, c) ->
    PALETTE_HUES.map { h -> StyleColorMath.fromOklch(Oklch(l, c, h)) }
} + listOf((0 until 12).map { i -> StyleColorMath.fromOklch(Oklch(1.0 - i * 0.08, 0.0, 0.0)) })

/** What the picker shows under the colours: a check that the colour takes part in, and whether it holds. */
class PickerCheck(val text: String, val holds: Boolean)

/**
 * A popover by a swatch (fork-only): the palette, a hex field, a check that the colour takes part in, and, for a colour
 * set by hand, a link that lets Leaf work it out again. A pick applies at once, so the previews follow it.
 */
@Composable
fun ColourPicker(
    title: String,
    use: String?,
    color: Int,
    check: PickerCheck?,
    onPick: (Int) -> Unit,
    onUseWorkedOut: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position = remember(gap) { BelowLeftAlignedPositionProvider(gap) }

    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(304.dp)
                .shadow(14.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.body2, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colors.onBackground)
                if (use != null) {
                    Text(use, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onBackgroundSecondary)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in PALETTE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (argb in row) {
                            Swatch(argb, isSelected = argb == color, onClick = { onPick(argb) })
                        }
                    }
                }
            }

            HexField(color, onPick)

            if (check != null) {
                Text(
                    text = check.text,
                    style = MaterialTheme.typography.caption,
                    color = if (check.holds) MaterialTheme.colors.onBackground else MaterialTheme.colors.error,
                )
            }

            if (onUseWorkedOut != null) {
                Text(
                    text = stringResource(Res.string.style_editor_picker_worked_out),
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.primaryVariant,
                    modifier = Modifier.handMouseClickable(onClick = onUseWorkedOut),
                )
            }
        }
    }
}

@Composable
private fun Swatch(argb: Int, isSelected: Boolean, onClick: () -> Unit) {
    val ring = if (isSelected) MaterialTheme.colors.onBackground else Color.Transparent

    Box(
        modifier = Modifier
            .size(20.dp)
            .border(2.dp, ring, RoundedCornerShape(5.dp))
            .padding(2.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(argb))
            .border(1.dp, MaterialTheme.colors.onBackground.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
            .semantics {
                contentDescription = StyleColorHex.format(argb)
                selected = isSelected
            }
            .handMouseClickable(onClick = onClick),
    )
}

/** The colour as `#RRGGBB`, or `#RRGGBBAA` when see-through; a colour typed in is picked as soon as it reads as one. */
@Composable
private fun HexField(color: Int, onPick: (Int) -> Unit) {
    var text by remember(color) { mutableStateOf(StyleColorHex.format(color)) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.style_editor_picker_hex),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onBackgroundSecondary,
        )

        AdjustableOutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed
                StyleColorHex.parse(typed)?.let(onPick)
            },
            singleLine = true,
            isError = StyleColorHex.parse(text) == null,
            textStyle = MaterialTheme.typography.body2.copy(fontFamily = monoTypography(), color = MaterialTheme.colors.onBackground),
            modifier = Modifier.width(110.dp),
        )
    }
}

