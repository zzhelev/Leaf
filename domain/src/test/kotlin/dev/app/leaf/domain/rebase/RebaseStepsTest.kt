// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rebase

import dev.app.leaf.domain.models.RebaseLine
import dev.app.leaf.domain.models.RebaseLine.Action.DROP
import dev.app.leaf.domain.models.RebaseLine.Action.FIXUP
import dev.app.leaf.domain.models.RebaseLine.Action.PICK
import dev.app.leaf.domain.models.RebaseLine.Action.REWORD
import dev.app.leaf.domain.models.RebaseLine.Action.SQUASH
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RebaseStepsTest {
    private fun steps(vararg actions: RebaseLine.Action) = actions.mapIndexed { index, action ->
        RebaseLine(action, "commit$index", "Message $index", "Message $index")
    }

    private fun List<RebaseLine>.actions() = map { it.action }

    @Test
    fun `a squash or fixup that comes first becomes a pick`() {
        assertEquals(listOf(PICK, SQUASH), steps(SQUASH, SQUASH).withFirstStepPicked().actions())
        assertEquals(listOf(PICK, PICK), steps(FIXUP, PICK).withFirstStepPicked().actions())
    }

    @Test
    fun `dropped steps before it don't count`() {
        assertEquals(listOf(DROP, PICK, FIXUP), steps(DROP, SQUASH, FIXUP).withFirstStepPicked().actions())
    }

    @Test
    fun `other steps stay as they are`() {
        val steps = steps(REWORD, SQUASH, FIXUP)

        assertSame(steps, steps.withFirstStepPicked())
        assertEquals(listOf(DROP, DROP), steps(DROP, DROP).withFirstStepPicked().actions())
    }
}
