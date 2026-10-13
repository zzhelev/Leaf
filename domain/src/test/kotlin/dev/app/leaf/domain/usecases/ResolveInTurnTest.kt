// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.MergeToolRefusal
import dev.app.leaf.domain.models.MergeToolTurn
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The banner's merge tool, which goes through the conflicted files one after another. */
class ResolveInTurnTest {
    private val opened = mutableListOf<Pair<String, MergeToolTurn>>()

    @Test
    fun `every file is opened in turn, and the count is what the tool resolved`(): Unit = runBlocking {
        val result = resolveInTurn(listOf("a.txt", "b.txt", "c.txt"), opening())

        assertEquals(Either.Ok(3), result)
        assertEquals(
            listOf(
                "a.txt" to MergeToolTurn(1, 3),
                "b.txt" to MergeToolTurn(2, 3),
                "c.txt" to MergeToolTurn(3, 3),
            ),
            opened,
        )
    }

    @Test
    fun `a file that has no conflicts any more, or that the tool can't take, is skipped`(): Unit = runBlocking {
        val result = resolveInTurn(
            listOf("a.txt", "b.txt", "c.txt", "d.txt"),
            opening(
                "a.txt" to ConflictError.NotConflicted(listOf("a.txt")),
                "c.txt" to ConflictError.MergeToolRefused("c.txt", MergeToolRefusal.NOT_TWO_FILES),
            ),
        )

        assertEquals(Either.Ok(2), result)
        assertEquals(listOf("a.txt", "b.txt", "c.txt", "d.txt"), opened.map { it.first })
    }

    @Test
    fun `a file that the tool didn't resolve stops it, and says how many it resolved before`(): Unit = runBlocking {
        val result = resolveInTurn(
            listOf("a.txt", "b.txt", "c.txt", "d.txt"),
            opening(
                "a.txt" to ConflictError.NotConflicted(listOf("a.txt")),
                "c.txt" to ConflictError.NotResolved("meld", "c.txt", "merge of c.txt failed"),
            ),
        )

        assertEquals(Either.Err(ConflictError.NotResolved("meld", "c.txt", "merge of c.txt failed", 1)), result)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), opened.map { it.first })
    }

    @Test
    fun `markers left stop it too, with the files resolved before`(): Unit = runBlocking {
        val result = resolveInTurn(
            listOf("a.txt", "b.txt", "c.txt"),
            opening("c.txt" to ConflictError.MarkersLeft("meld", "c.txt")),
        )

        assertEquals(Either.Err(ConflictError.MarkersLeft("meld", "c.txt", resolvedBefore = 2)), result)
    }

    @Test
    fun `any other failure stops it as it is`(): Unit = runBlocking {
        val result = resolveInTurn(listOf("a.txt", "b.txt"), opening("a.txt" to ConflictError.NoMergeTool))

        assertEquals(Either.Err(ConflictError.NoMergeTool), result)
        assertEquals(listOf("a.txt"), opened.map { it.first })
    }

    /** Opens each file, and fails with the error given for its path, if any. */
    private fun opening(vararg failures: Pair<String, GitError>): suspend (String, MergeToolTurn) -> Either<Unit, GitError> {
        val errors = failures.toMap()

        return { filePath, turn ->
            opened += filePath to turn
            errors[filePath]?.let { Either.Err(it) } ?: Either.Ok(Unit)
        }
    }
}
