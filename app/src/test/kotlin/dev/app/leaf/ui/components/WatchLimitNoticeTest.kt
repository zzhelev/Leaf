// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.app.leaf.domain.refresh.WatchUsage
import dev.app.leaf.theme.AppTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WatchLimitNoticeTest {
    private var scene: ImageComposeScene? = null

    @AfterEach
    fun closeScene() {
        scene?.close()
    }

    @Test
    fun `the bottom bar says that file watching is near its limit`() {
        render(WatchUsage(registrations = 214, tabs = 8))

        assertTrue("File watching near its limit" in texts(), texts().toString())
        assertTrue(texts().any { it.startsWith("Version") }, "the version stays")
    }

    @Test
    fun `the bottom bar says nothing below the limit`() {
        render(null)

        assertFalse(texts().any { "File watching" in it })
    }

    private fun render(usage: WatchUsage?) {
        scene = ImageComposeScene(width = 700, height = 40) {
            AppTheme { BottomInfoBar(newUpdate = null, onOpenUrlInBrowser = {}, watchWarning = usage) }
        }.also { it.render() }
    }

    private fun texts(): List<String> = scene!!.semanticsOwners
        .flatMap { it.getAllSemanticsNodes(false) }
        .flatMap { node: SemanticsNode -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
}
