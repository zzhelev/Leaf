// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editing

import dev.app.leaf.domain.models.TypedLines
import org.eclipse.jgit.diff.RawText
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * How lines typed in place of a part are written: in [charset], each with [lineEnding], except the last when the part
 * is where the file ends without one ([endsWithLineEnding] false).
 */
data class LineFormat(val charset: Charset, val lineEnding: String, val endsWithLineEnding: Boolean) {
    /** Whether [text] can be written in [charset]: a file in ISO-8859-1 has no room for other characters. */
    fun canWrite(text: String): Boolean = charset.newEncoder().canEncode(text)

    /**
     * [text] as lines of the file: each line between `\n`s (or `\r\n`s, as pasted text can have) is a line. An empty
     * text is no line at all, as when the user deleted every line.
     */
    fun typed(text: String): TypedLines {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')

        if (normalized.isEmpty()) {
            return TypedLines(RawText(ByteArray(0)), lineEnding)
        }

        val lines = normalized.split('\n')
        val out = ByteArrayOutputStream()

        lines.forEachIndexed { index, line ->
            out.writeBytes(line.toByteArray(charset))

            if (index < lines.lastIndex || endsWithLineEnding) {
                out.writeBytes(lineEnding.toByteArray(charset))
            }
        }

        return TypedLines(RawText(out.toByteArray()), lineEnding)
    }
}

const val LF = "\n"
const val CRLF = "\r\n"

/** Line [index]'s bytes, without its line ending. */
fun RawText.lineBytes(index: Int): ByteArray {
    // Without the '\n', but with the '\r' of CRLF
    val raw = getRawString(index)
    val bytes = ByteArray(raw.remaining()).also { raw.get(it) }

    return if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte() && lineEnding(index) == CRLF) {
        bytes.copyOf(bytes.size - 1)
    } else {
        bytes
    }
}

/** Line [index]'s line ending, or null for a last line without one. */
fun RawText.lineEnding(index: Int): String? {
    if (index == size() - 1 && isMissingNewlineAtEnd) {
        return null
    }

    val raw = getRawString(index)

    return if (raw.hasRemaining() && raw.get(raw.limit() - 1) == '\r'.code.toByte()) CRLF else LF
}

/**
 * The charset that decodes every line of [lines] as RawText shows them: UTF-8 when they are valid UTF-8, else
 * ISO-8859-1, which JGit falls back to and which decodes and encodes any byte as it is. Null without lines.
 */
fun charsetOf(lines: List<ByteArray>): Charset? = when {
    lines.isEmpty() -> null
    lines.all { it.isUtf8() } -> Charsets.UTF_8
    else -> Charsets.ISO_8859_1
}

fun ByteArray.isUtf8(): Boolean = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
    true
} catch (_: CharacterCodingException) {
    false
}
