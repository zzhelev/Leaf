// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.commitmessage.SignOffChanges
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.ISaveLocalRepositoryConfigGitAction
import dev.app.leaf.domain.models.SignOffConfig
import dev.app.leaf.domain.repositories.RepositoryDataRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Saving the sign-off setting tells the tab (fork-only), so that the commit message's menu and its "with Signed-off-by" tag
 * follow a change made in the Actions list's dialog, which refreshes nothing.
 */
class SaveSignOffConfigUseCaseTest {
    @Test
    fun `each save of the setting counts as a change`(): Unit = runBlocking {
        val saved = mutableListOf<SignOffConfig>()
        val changes = SignOffChanges()
        val saveSignOffConfig = SaveSignOffConfigUseCase(
            mockk<RepositoryDataRepository> { every { repositoryPath } returns "/repo/.git" },
            object : ISaveLocalRepositoryConfigGitAction {
                override suspend fun invoke(repositoryPath: String, signOffConfig: SignOffConfig) =
                    Either.Ok(Unit).also { saved += signOffConfig }
            },
            changes,
        )

        saveSignOffConfig(SignOffConfig(isEnabled = true, format = "Signed-off-by: %user <%email>"))
        saveSignOffConfig(SignOffConfig(isEnabled = false, format = "Signed-off-by: %user <%email>"))

        assertEquals(listOf(true, false), saved.map { it.isEnabled })
        assertEquals(2, changes.count.value)
    }
}
