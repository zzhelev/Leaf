// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.conflicts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.app.leaf.ui.diff.syntax_highlighter.getSyntaxHighlighterFromExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The colours of the part being edited, which are the rows' colours as it's typed. */
class EditorColoursTest {
    private val colours = EditorColours(
        syntaxHighlighter = getSyntaxHighlighterFromExtension("kt"),
        keywordColor = Color.Blue,
        commentColor = Color.Green,
        annotationColor = Color.Yellow,
    )

    @Test
    fun `keywords, annotations and comment lines get the rows' colours, where tabs show as spaces`() {
        val text = "\tval x = 1\n// a comment\n@Inject fun load()"
        val transformed = colours.filter(AnnotatedString(text))

        assertEquals("    val x = 1\n// a comment\n@Inject fun load()", transformed.text.text)
        assertEquals(
            listOf(
                Triple(4, 7, Color.Blue),
                Triple(14, 26, Color.Green),
                Triple(27, 34, Color.Yellow),
                Triple(35, 38, Color.Blue),
            ),
            transformed.text.spanStyles.map { Triple(it.start, it.end, it.item.color) }.sortedBy { it.first },
        )

        // Offsets are the tabs' mapping
        assertEquals(4, transformed.offsetMapping.originalToTransformed(1))
        assertEquals(1, transformed.offsetMapping.transformedToOriginal(4))
    }

    @Test
    fun `a part too long to colour on every change stays plain`() {
        val text = "val x = 1\n".repeat(MAX_COLOURED_EDIT_CHARACTERS / 10 + 1)

        assertEquals(emptyList<AnnotatedString.Range<*>>(), colours.filter(AnnotatedString(text)).text.spanStyles)
        assertEquals(10, colours.filter(AnnotatedString("val x = 1\n".repeat(10))).text.spanStyles.size)
    }
}
