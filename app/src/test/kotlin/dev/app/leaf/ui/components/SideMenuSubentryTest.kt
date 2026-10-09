// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.components

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.branch
import dev.app.leaf.theme.AppTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SideMenuSubentryTest {
    @Test
    fun `a trailing lambda adds to the row's text instead of replacing it`() {
        // Remote branch, tag and submodule rows pass their age or state label this way
        val texts = renderedTexts {
            SideMenuSubentry(
                text = "origin/main",
                iconResourcePath = Res.drawable.branch,
                isSelected = false,
                onClick = {},
            ) {
                Text("3d")
            }
        }

        assertEquals(listOf("origin/main", "3d"), texts)
    }

    @Test
    fun `textContent replaces the row's text`() {
        val texts = renderedTexts {
            SideMenuSubentry(
                text = "feature",
                iconResourcePath = Res.drawable.branch,
                isSelected = false,
                onClick = {},
                textContent = { Text("feature, then its worktree") },
            )
        }

        assertEquals(listOf("feature, then its worktree"), texts)
    }

    /** The texts that [content] shows, in order. */
    private fun renderedTexts(content: @Composable () -> Unit): List<String> {
        val scene = ImageComposeScene(width = 300, height = 40) {
            AppTheme {
                content()
            }
        }

        try {
            scene.render()

            return scene.semanticsOwners
                .flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
        } finally {
            scene.close()
        }
    }
}
