// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: MIT

package dev.textmate.regex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** A line's offsets, between its characters and its UTF-8 bytes, which the scanner searches. */
class OnigStringOffsetsTest {

    @Test
    fun `offsets convert as encoding the string up to them does`() {
        val random = Random(5)
        val pieces = listOf("a", " ", "Ж", "中", "😀", "\uD800", "\uDC00", "é", "\n")
        repeat(2_000) {
            val content = (0 until random.nextInt(0, 30)).joinToString("") { pieces[random.nextInt(pieces.size)] }
            val string = OnigString(content)
            for (char in 0..content.length) {
                val encoded = content.substring(0, char).toByteArray().size
                assertEquals(encoded, string.charToByteOffset(char), "\"$content\" at $char")
            }
            // Each character that starts a code point maps back to itself.
            var char = 0
            while (char < content.length) {
                assertEquals(char, string.byteToCharOffset(string.charToByteOffset(char)), "\"$content\" at $char")
                char += Character.charCount(content.codePointAt(char))
            }
            assertEquals(content.length, string.byteToCharOffset(string.utf8Bytes.size))
        }
    }
}
