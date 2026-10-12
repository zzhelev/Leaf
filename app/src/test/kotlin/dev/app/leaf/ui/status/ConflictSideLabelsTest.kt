// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.status

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import dev.app.leaf.domain.models.ConflictCommit
import dev.app.leaf.domain.models.ConflictOperation
import dev.app.leaf.domain.models.ConflictSide
import dev.app.leaf.domain.models.ConflictSides
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val main = ConflictCommit("main", "1111111", "Main's change")
private val feature = ConflictCommit("feature", "2222222", "Fix parser")
private val picked = ConflictCommit(null, "3333333", "Fix parser")

/** How ours and theirs are named, as few people remember which is which (fork-only). */
class ConflictSideLabelsTest {
    @Test
    fun `a merge names the branches`() {
        val sides = ConflictSides(ConflictOperation.MERGE, main, feature)

        assertEquals(listOf("main", "feature"), compose { listOf(sides.label(OURS), sides.label(THEIRS)) })
        assertEquals("Ours: main · Theirs: feature", compose { sides.summary() })
        assertEquals("3333333 \"Fix parser\"", compose { sides.copy(theirs = picked).label(THEIRS) })
    }

    @Test
    fun `a rebase names the branch it builds on, and the commit it replays with its branch`() {
        val sides = ConflictSides(ConflictOperation.REBASE, main, feature)

        assertEquals("main (onto)", compose { sides.label(OURS) })
        assertEquals("2222222 \"Fix parser\" from feature", compose { sides.label(THEIRS) })
        assertEquals("1111111 (onto)", compose { sides.copy(ours = main.copy(branch = null)).label(OURS) })
    }

    @Test
    fun `a cherry-pick and a revert name the commit`() {
        assertEquals(
            "3333333 \"Fix parser\"",
            compose { ConflictSides(ConflictOperation.CHERRY_PICK, main, picked).label(THEIRS) },
        )
        assertEquals(
            "the revert of 3333333 \"Fix parser\"",
            compose { ConflictSides(ConflictOperation.REVERT, main, picked).label(THEIRS) },
        )
    }

    @Test
    fun `what records no side of its own names what it applies`() {
        val sides = ConflictSides(ConflictOperation.OTHER, main, null)

        assertEquals("Ours: main · Theirs: the changes being applied", compose { sides.summary() })
    }

    @Test
    fun `a side is named in sentences, or left plain when unknown`() {
        val sides = ConflictSides(ConflictOperation.MERGE, main, feature)

        assertEquals(listOf("ours (main)", "theirs (feature)"), compose { listOf(OURS.named(sides), THEIRS.named(sides)) })
        assertEquals(listOf("ours", "theirs"), compose { listOf(OURS.named(null), THEIRS.named(null)) })
        assertEquals("ours", compose { OURS.named(sides.copy(ours = null)) })
        assertEquals(null, compose { sides.copy(theirs = null).summary() })
    }

    @Test
    fun `a long subject is cut`() {
        val long = picked.copy(subject = "Make the parser accept trailing commas in every kind of list")

        assertEquals(
            "3333333 \"Make the parser accept trailing commas…\"",
            compose { ConflictSides(ConflictOperation.CHERRY_PICK, main, long).label(THEIRS) },
        )
    }

    private fun <T> compose(content: @Composable () -> T): T {
        var result: Result<T>? = null
        val scene = ImageComposeScene(width = 10, height = 10) {
            result = Result.success(content())
        }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        val OURS = ConflictSide.OURS
        val THEIRS = ConflictSide.THEIRS
    }
}
