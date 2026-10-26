// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.SyntaxCategory
import dev.app.leaf.domain.highlighting.SyntaxSpan
import dev.app.leaf.domain.highlighting.decodedText
import dev.app.leaf.domain.highlighting.highlightedTextOf
import dev.app.leaf.domain.highlighting.lineCountOf
import dev.app.leaf.domain.interfaces.IHighlightTextAction
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.RawText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HighlightTextUseCaseTest {

    private class RecordingAction : IHighlightTextAction {
        val calls = mutableListOf<Pair<String, String>>()
        val result = HighlightedText.Builder().build(1)

        override suspend fun invoke(scopeName: String, text: String): HighlightedText {
            calls += scopeName to text
            return result
        }
    }

    @Test
    fun `the path names the grammar`(): Unit = runBlocking {
        val action = RecordingAction()
        val useCase = HighlightTextUseCase(action)
        assertSame(action.result, useCase("app/src/Main.kt", "fun main() {}"))
        useCase("build.gradle.kts", "")
        useCase("Dockerfile", "FROM scratch")
        assertEquals(
            listOf("source.kotlin" to "fun main() {}", "source.kotlin" to "", "source.dockerfile" to "FROM scratch"),
            action.calls,
        )
    }

    @Test
    fun `a file without an extension is named by its #! line`(): Unit = runBlocking {
        val action = RecordingAction()
        HighlightTextUseCase(action)("tools/sync", "#!/usr/bin/env python3\r\nprint(1)\n")
        HighlightTextUseCase(action)("deploy", "#!/bin/sh")
        assertEquals(listOf("source.python", "source.shell"), action.calls.map { it.first })
    }

    @Test
    fun `a file that nothing names stays plain, and the grammar isn't asked`(): Unit = runBlocking {
        val action = RecordingAction()
        assertNull(HighlightTextUseCase(action)("LICENSE", "Permission is hereby granted"))
        // Only the first line can be a #! line.
        assertNull(HighlightTextUseCase(action)("notes", "x".repeat(300) + "\n#!/bin/sh"))
        assertEquals(emptyList<Pair<String, String>>(), action.calls)
    }

    @Test
    fun `a version that a diff holds is decoded a line at a time, once its language is found`(): Unit = runBlocking {
        val action = RecordingAction()
        val useCase = HighlightTextUseCase(action)
        val latin1 = RawText("val caf".toByteArray() + byteArrayOf(0xE9.toByte()) + "\nval na\u00EFve = 1\n".toByteArray())

        assertSame(action.result, useCase("src/Main.kt", latin1))
        useCase("tools/sync", RawText("#!/usr/bin/env python3\nprint(1)\n".toByteArray()))

        assertEquals(listOf("source.kotlin" to latin1.decodedText(), "source.python" to "#!/usr/bin/env python3\nprint(1)\n"), action.calls)
        assertEquals("val café\nval naïve = 1\n", action.calls.first().second)
    }

    @Test
    fun `a version that nothing names, or that is empty, isn't asked about`(): Unit = runBlocking {
        val action = RecordingAction()
        val useCase = HighlightTextUseCase(action)

        assertNull(useCase("LICENSE", RawText("Permission is hereby granted\n".toByteArray())))
        assertNull(useCase("data.bin", RawText(("x".repeat(300) + "\n#!/bin/sh\n").toByteArray())))
        assertNull(useCase("src/Main.kt", RawText.EMPTY_TEXT))
        assertEquals(emptyList<Pair<String, String>>(), action.calls)
    }

    /** Colours each line of a text with one span that starts at the line's number, and notes the texts. */
    private class NumberingAction : IHighlightTextAction {
        val texts = mutableListOf<String>()

        override suspend fun invoke(scopeName: String, text: String): HighlightedText {
            texts += text
            val lines = lineCountOf(text)
            return highlightedTextOf((0 until lines).map { listOf(SyntaxSpan(it, it + 1, SyntaxCategory.KEYWORD)) })
        }
    }

    @Test
    fun `lines coloured in context are coloured after the lines before them, and keep only their own`(): Unit = runBlocking {
        val action = NumberingAction()
        val useCase = HighlightTextUseCase(action)

        val typed = useCase.inContext("src/Main.kt", "/* a\n   b\n", "c */\nval d")!!
        assertEquals("/* a\n   b\nc */\nval d", action.texts.single())
        assertEquals(2, typed.lineCount)
        assertEquals(listOf(SyntaxSpan(2, 3, SyntaxCategory.KEYWORD)), typed.spansOf(0))
        assertEquals(listOf(SyntaxSpan(3, 4, SyntaxCategory.KEYWORD)), typed.spansOf(1))

        // Lines before without a last line ending still end before the text; none at all is the text alone
        assertEquals(listOf(SyntaxSpan(1, 2, SyntaxCategory.KEYWORD)), useCase.inContext("src/Main.kt", "x", "y")!!.spansOf(0))
        assertEquals("x\ny", action.texts[1])
        assertEquals(listOf(SyntaxSpan(0, 1, SyntaxCategory.KEYWORD)), useCase.inContext("src/Main.kt", "", "y")!!.spansOf(0))
        assertNull(useCase.inContext("LICENSE", "x\n", "y"))
    }
}
