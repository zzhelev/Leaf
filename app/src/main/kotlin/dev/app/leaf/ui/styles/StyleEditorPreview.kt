// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.app.leaf.app.generated.resources.*
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.backgroundSelected
import dev.app.leaf.theme.bottomEdge
import dev.app.leaf.theme.conflictFile
import dev.app.leaf.theme.diffContentAdded
import dev.app.leaf.theme.diffContentRemoved
import dev.app.leaf.theme.syntaxColors
import dev.app.leaf.theme.diffLineAdded
import dev.app.leaf.theme.diffLineRemoved
import dev.app.leaf.theme.graphLanes
import dev.app.leaf.theme.modifyFile
import dev.app.leaf.theme.monoTypography
import dev.app.leaf.theme.onBackgroundSecondary
import dev.app.leaf.theme.secondarySurface
import dev.app.leaf.theme.styleColors
import dev.app.leaf.theme.tertiarySurface
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.SecondaryButton
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * A slice of Leaf in [style]'s light or dark side (fork-only, the style editor's preview): the operation banner, a
 * diff's header, a hunk and its lines, a list's header and two files, Commit, the restored strip and the graph's
 * lanes. It sits in an `AppTheme` of its own, so it's drawn with Leaf's own buttons, in the colours the app will have.
 */
@Composable
fun StyleEditorPreview(style: StyleDefinition, isLight: Boolean, modifier: Modifier = Modifier) {
    AppTheme(selectedTheme = if (isLight) Theme.Light else Theme.Dark, style = style) {
        val colors = MaterialTheme.colors
        val roles = MaterialTheme.styleColors
        val shape = RoundedCornerShape(6.dp)
        val side = stringResource(if (isLight) Res.string.style_editor_light else Res.string.style_editor_dark)

        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.background)
                .border(1.dp, colors.onBackground.copy(alpha = 0.12f), shape)
                .semantics { contentDescription = side },
        ) {
            // The banner of a merge stopped at conflicts
            val strip = roles.warningStrip
            Row(
                modifier = Modifier.fillMaxWidth().background(strip.background).bottomEdge(strip.border).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(Res.drawable.warning), null, tint = strip.icon, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(Res.string.style_editor_preview_banner),
                    color = strip.content,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(text = stringResource(Res.string.style_editor_preview_resolve), colors = strip.button, onClick = {})
                SecondaryButton(text = stringResource(Res.string.style_editor_preview_abort), colors = roles.destructive, onClick = {})
            }

            // A diff's header and a hunk's
            HeaderRow(colors.tertiarySurface) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.onBackgroundSecondary)) { append("src/main/kotlin/…/parse/") }
                        append("Scanner.kt")
                    },
                    color = colors.onBackground,
                    style = MaterialTheme.typography.body2,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(text = stringResource(Res.string.style_editor_preview_stage_file), onClick = {})
            }
            HeaderRow(colors.secondarySurface) {
                Text(
                    "@@ -48,6 +48,7 @@",
                    color = colors.onBackgroundSecondary,
                    style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography()),
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(text = stringResource(Res.string.style_editor_preview_discard_hunk), colors = roles.destructive, onClick = {})
                SecondaryButton(text = stringResource(Res.string.style_editor_preview_stage_hunk), onClick = {})
            }

            val syntax = MaterialTheme.syntaxColors
            val removedWords = colors.diffContentRemoved
            val addedWords = colors.diffContentAdded

            // Code in the syntax colours: a keyword, a function, a type, a number and a string
            DiffLine(null) {
                append("    ")
                withStyle(SpanStyle(color = syntax.keyword)) { append("fun") }
                append(" ")
                withStyle(SpanStyle(color = syntax.function)) { append("next") }
                append("(): ")
                withStyle(SpanStyle(color = syntax.type)) { append("Token") }
                append(" {")
            }
            DiffLine(colors.diffLineRemoved) {
                append("        ")
                withStyle(SpanStyle(color = syntax.keyword)) { append("val") }
                append(" c = ")
                withStyle(SpanStyle(background = removedWords)) {
                    append("source[pos + ")
                    withStyle(SpanStyle(color = syntax.number)) { append("1") }
                    append("]")
                }
            }
            DiffLine(colors.diffLineAdded) {
                append("        ")
                withStyle(SpanStyle(color = syntax.keyword)) { append("val") }
                append(" c = ")
                withStyle(SpanStyle(background = addedWords)) {
                    withStyle(SpanStyle(color = syntax.function)) { append("peek") }
                    append("()")
                }
                append(" ?: ")
                withStyle(SpanStyle(color = syntax.function)) { append("error") }
                append("(")
                withStyle(SpanStyle(color = syntax.string)) { append("\"end\"") }
                append(")")
            }

            // A list's header and two files, the first selected
            HeaderRow(colors.tertiarySurface) {
                Text(
                    stringResource(Res.string.style_editor_preview_unstaged),
                    color = colors.onBackground,
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(text = stringResource(Res.string.style_editor_preview_unstage_all), colors = roles.neutral, onClick = {})
            }
            FileRow("Lexer.kt", colors.conflictFile, isSelected = true)
            FileRow("Parser.kt", colors.modifyFile, isSelected = false)

            // Commit, and the restored strip of file history
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryButton(text = stringResource(Res.string.style_editor_preview_commit), onClick = {}, modifier = Modifier.width(120.dp))

                val restored = roles.successStrip
                Row(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(restored.background).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.style_editor_preview_restored),
                        color = restored.content,
                        style = MaterialTheme.typography.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(text = stringResource(Res.string.style_editor_preview_show_changes), colors = restored.button, onClick = {})
                }
            }

            // The graph's lanes
            Row(
                modifier = Modifier.padding(start = 10.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (lane in MaterialTheme.graphLanes) {
                    Box(Modifier.size(width = 22.dp, height = 6.dp).clip(CircleShape).background(lane))
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(color: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun DiffLine(line: Color?, text: AnnotatedString.Builder.() -> Unit) {
    Text(
        text = buildAnnotatedString(text),
        color = MaterialTheme.colors.onBackground,
        style = MaterialTheme.typography.body2.copy(fontFamily = monoTypography(), fontSize = 12.sp),
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (line != null) Modifier.background(line) else Modifier)
            .padding(horizontal = 10.dp, vertical = 1.dp),
    )
}

@Composable
private fun FileRow(name: String, iconColor: Color, isSelected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isSelected) Modifier.background(MaterialTheme.colors.backgroundSelected) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painterResource(if (isSelected) Res.drawable.warning else Res.drawable.edit),
            null,
            tint = iconColor,
            modifier = Modifier.size(14.dp),
        )
        Text(name, color = MaterialTheme.colors.onBackground, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
        Text("…/leaf/parse", color = MaterialTheme.colors.onBackgroundSecondary, style = MaterialTheme.typography.body2)
    }
}
