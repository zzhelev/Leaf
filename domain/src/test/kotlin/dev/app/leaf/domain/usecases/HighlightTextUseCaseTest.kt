// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.interfaces.IHighlightTextAction
import kotlinx.coroutines.runBlocking
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
}
