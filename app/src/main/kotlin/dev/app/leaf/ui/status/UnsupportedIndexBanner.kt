// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.index.UnsupportedIndex
import dev.app.leaf.domain.index.conversionSteps
import dev.app.leaf.domain.worktrees.commandLine
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.styleColors
import dev.app.leaf.ui.components.SecondaryButton
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** What git did to the index: "This repository's index is split, which Leaf can't read." */
@Composable
fun UnsupportedIndex.summary(): String = when (this) {
    is UnsupportedIndex.Split -> stringResource(Res.string.unsupported_index_split)
    is UnsupportedIndex.Sparse -> stringResource(Res.string.unsupported_index_sparse)
    is UnsupportedIndex.UnknownExtension -> stringResource(Res.string.unsupported_index_unknown, extension)
}

/** What doesn't work until the index is converted, and what converting keeps. */
@Composable
fun UnsupportedIndex.consequence(): String = when {
    conversionSteps().isEmpty() -> stringResource(Res.string.unsupported_index_no_conversion)

    this is UnsupportedIndex.Sparse -> stringResource(Res.string.unsupported_index_until_converted) + " " +
        stringResource(Res.string.unsupported_index_sparse_kept)

    else -> stringResource(Res.string.unsupported_index_until_converted)
}

/** The commands that convert the index, as they would be typed in a terminal in the worktree. */
fun UnsupportedIndex.commandLines(): List<String> = conversionSteps().map { it.commandLine() }

/**
 * A strip across the repository view while the worktree's index is in a format that JGit can't read (fork-only), as
 * git writes it for large repositories. It says why the uncommitted changes don't show and what else fails, with the
 * commands that convert the index, and stays until the index is converted, whoever converts it.
 */
@Composable
fun UnsupportedIndexBanner(
    index: UnsupportedIndex,
    onConvert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val commands = index.commandLines()
    val summary = index.summary()
    val consequence = index.consequence()
    val monospace = SpanStyle(fontFamily = monoTypography(), background = Color.Black.copy(alpha = 0.1f))

    val strip = MaterialTheme.styleColors.warningStrip

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(strip.background)
            .bottomEdge(strip.border)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(Res.drawable.warning),
            contentDescription = null,
            tint = strip.icon,
            modifier = Modifier.size(20.dp),
        )

        // The commands can be copied, for a terminal
        SelectionContainer(
            // Only as wide as the text, so that the button follows it, as in the operation banner
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(summary) }
                    append(" ")
                    append(consequence)

                    commands.forEachIndexed { position, command ->
                        append(if (position == 0) "  " else " && ")
                        withStyle(monospace) { append(command) }
                    }
                },
                style = MaterialTheme.typography.body1,
                color = strip.content,
            )
        }

        if (commands.isNotEmpty()) {
            SecondaryButton(
                text = stringResource(Res.string.unsupported_index_convert),
                colors = strip.button,
                onClick = onConvert,
            )
        }
    }
}
