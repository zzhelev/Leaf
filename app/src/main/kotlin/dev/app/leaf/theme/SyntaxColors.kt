// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.styles.CodeColours

/**
 * The colours of code, one per [SyntaxCategory] (fork-only, `docs/fork/syntax-highlighting.md`, 4.4). The style sets
 * them, and the Code colours setting may put VS Code's or IntelliJ's in their place. The keyword, the annotation and
 * the comment are the scheme's `diffKeyword`, `diffAnnotation` and `diffComment` too, which upstream's code reads.
 */
@Immutable
data class SyntaxColors(
    val keyword: Color,
    val annotation: Color,
    val comment: Color,
    val string: Color,
    val number: Color,
    val type: Color,
    val function: Color,
    val tag: Color,
    val attribute: Color,
) {
    fun colorOf(category: SyntaxCategory): Color = when (category) {
        SyntaxCategory.KEYWORD -> keyword
        SyntaxCategory.ANNOTATION -> annotation
        SyntaxCategory.COMMENT -> comment
        SyntaxCategory.STRING -> string
        SyntaxCategory.NUMBER -> number
        SyntaxCategory.TYPE -> type
        SyntaxCategory.FUNCTION -> function
        SyntaxCategory.TAG -> tag
        SyntaxCategory.ATTRIBUTE -> attribute
    }
}

/**
 * Palette 1a of the "Syntax Colours" board, which every look starts from: the scheme's keyword, annotation and comment,
 * and six colours in the hues between them, each holding 4.5 : 1 on the background and both line tints. Tags take the
 * keyword's colour and attributes the type's, so markup reads in code's colours.
 */
fun syntaxTemplate(scheme: ColorsScheme): SyntaxColors {
    val type = if (scheme.isLight) Color(0xFF7652AC) else Color(0xFFC7AFF5)

    return SyntaxColors(
        keyword = scheme.diffKeyword,
        annotation = scheme.diffAnnotation,
        comment = scheme.diffComment,
        string = if (scheme.isLight) Color(0xFFA44C1D) else Color(0xFFF2AB83),
        number = if (scheme.isLight) Color(0xFFA73B6D) else Color(0xFFF3A4C2),
        type = type,
        function = if (scheme.isLight) Color(0xFF06717A) else Color(0xFF62CFCE),
        tag = scheme.diffKeyword,
        attribute = type,
    )
}

internal val LocalSyntaxColors = staticCompositionLocalOf { syntaxTemplate(darkTheme) }

/** The Code colours setting, which an `AppTheme` inside another, as the style editor's previews, follows. */
internal val LocalCodeColours = staticCompositionLocalOf { CodeColours.DEFAULT }

val MaterialTheme.syntaxColors: SyntaxColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSyntaxColors.current
