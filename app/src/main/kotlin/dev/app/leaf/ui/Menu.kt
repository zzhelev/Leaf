package dev.app.leaf.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.app.leaf.keybindings.Keybinding
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary

// Fork-only: upstream's toolbar (Menu, MenuButton and ExtendedMenuButton) was here. Leaf's is in ui/toolbar
// (docs/fork/window-chrome.md); the keyboard hints stay, as the tooltips and the Actions list show them.

@Composable
fun KeybindingHint(
    keybinding: Keybinding,
    // Fork-only: the toolbar's is on the window's frame (docs/fork/window-chrome.md)
    color: Color = MaterialTheme.colors.onBackgroundSecondary,
) {
    val parts = remember(keybinding) { getParts(keybinding) }.joinToString("+")

    Text(
        parts,
        fontFamily = monoTypography(),
        fontSize = MaterialTheme.typography.caption.fontSize,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

@Preview
@Composable
fun KeybindingHintPartPreview() {
    KeybindingHintPart("CTRL")
}

@Composable
fun KeybindingHintPart(part: String) {
    Text(
        text = part,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colors.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(2.dp, MaterialTheme.colors.primary, RoundedCornerShape(4.dp))
            .background(MaterialTheme.colors.primary.copy(alpha = 0.05f))
            .padding(horizontal = 4.dp, vertical = 4.dp)

    )
}

fun getParts(keybinding: Keybinding): List<String> {
    val parts = mutableListOf<String>()

    if (keybinding.control) {
        parts.add("Ctrl")
    }

    if (keybinding.meta) {
        parts.add("⌘")
    }

    if (keybinding.alt) {
        parts.add("Alt")
    }

    if (keybinding.shift) {
        parts.add("Shift")
    }

    val key = when (keybinding.key) {
        Key.A -> "A"
        Key.B -> "B"
        Key.C -> "C"
        Key.D -> "D"
        Key.E -> "E"
        Key.F -> "F"
        Key.G -> "G"
        Key.H -> "H"
        Key.I -> "I"
        Key.J -> "J"
        Key.K -> "K"
        Key.L -> "L"
        Key.M -> "M"
        Key.N -> "N"
        Key.O -> "O"
        Key.P -> "P"
        Key.Q -> "Q"
        Key.R -> "R"
        Key.S -> "S"
        Key.T -> "T"
        Key.U -> "U"
        Key.V -> "V"
        Key.W -> "W"
        Key.X -> "X"
        Key.Y -> "Y"
        Key.Z -> "Z"
        Key.Tab -> "Tab"
        Key.Equals -> "="
        Key.Plus, Key.NumPadAdd -> "+"
        Key.Minus, Key.NumPadSubtract -> "−"
        Key.Zero, Key.NumPad0 -> "0"
        else -> throw NotImplementedError("Key not implemented")
    }

    parts.add(key)

    return parts
}
