// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.TabCoroutineScope
import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.sorting.DiscardedFiles
import dev.app.leaf.domain.sorting.folderDiscard
import dev.app.leaf.domain.usecases.DiscardEntriesUseCase
import dev.app.leaf.theme.AppTheme
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.inject.Provider

/** The dialog that discards a folder's unstaged changes, and says which files it leaves as they are (fork-only). */
class DiscardChangesDialogTest {
    private val tabScope = TabCoroutineScope()

    private val viewModel = DiscardChangesViewModel(
        DiscardEntriesUseCase(
            UseCaseExecutor(
                mockk(),
                InMemoryRepositoryStateRepository(),
                Provider { error("Nothing is refreshed") },
                tabScope,
            ),
            mockk(),
        ),
        emptyList(),
    )

    @AfterEach
    fun tearDown() {
        tabScope.cancel()
    }

    @Test
    fun `says how many new files and files with conflicts it keeps`(): Unit = runBlocking(Dispatchers.Swing) {
        val discard = listOf(
            unstaged("src/new.kt", StatusType.ADDED),
            unstaged("src/modified.kt", StatusType.MODIFIED),
            unstaged("src/removed.kt", StatusType.REMOVED),
            unstaged("src/a.kt", StatusType.CONFLICTING),
            unstaged("src/b.kt", StatusType.CONFLICTING),
        ).folderDiscard()

        val texts = texts(discard)

        assertTrue("Discard the unstaged changes to 2 files in src? This can't be undone." in texts, texts.toString())
        assertTrue("1 new file is kept." in texts, texts.toString())
        assertTrue("2 files with conflicts are left as they are." in texts, texts.toString())
    }

    @Test
    fun `says nothing about files with conflicts when there are none`(): Unit = runBlocking(Dispatchers.Swing) {
        val texts = texts(listOf(unstaged("src/modified.kt", StatusType.MODIFIED)).folderDiscard())

        assertFalse(texts.any { "conflicts" in it || "kept" in it }, texts.toString())
    }

    private fun unstaged(path: String, type: StatusType) = StatusEntry(path, type, EntryType.UNSTAGED)

    /** The texts of the dialog for [discard] in the folder `src`, rendered offscreen. */
    private fun texts(discard: DiscardedFiles): List<String> {
        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            AppTheme {
                DiscardChangesDialog(viewModel = viewModel, folderPath = "src", discard = discard, onDismiss = {})
            }
        }

        try {
            scene.render()

            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes.flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        } finally {
            scene.close()
        }
    }
}
