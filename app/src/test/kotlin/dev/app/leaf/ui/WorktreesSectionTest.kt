// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.models.AheadBehind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorktreesSectionTest {
    @Test
    fun `shows commits ahead and behind, leaving out a direction without any`() {
        assertEquals("↑3 ↓1", AheadBehind(ahead = 3, behind = 1).compactText())
        assertEquals("↑2", AheadBehind(ahead = 2, behind = 0).compactText())
        assertEquals("↓4", AheadBehind(ahead = 0, behind = 4).compactText())
        assertEquals("", AheadBehind(ahead = 0, behind = 0).compactText())
    }
}
