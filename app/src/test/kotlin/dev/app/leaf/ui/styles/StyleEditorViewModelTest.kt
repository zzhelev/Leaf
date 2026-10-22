// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleIds
import dev.app.leaf.domain.styles.StyleLibrary
import dev.app.leaf.domain.styles.StyleLibraryError
import dev.app.leaf.domain.styles.StyleLibraryRepository
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StylesService
import dev.app.leaf.system.SaveFilePickerUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/** Saving, exporting and deleting from the style editor (fork-only), against a library kept in memory. */
class StyleEditorViewModelTest {
    private val storedId = MutableStateFlow<String?>(null)
    private val library = MutableStateFlow(StyleLibrary())
    private val exported = mutableListOf<Pair<StyleDefinition, File>>()
    private var failSaving = false

    private val settings = mockk<AppSettingsRepository> {
        every { styleId } returns storedId
        coEvery { setConfiguration(any()) } answers {
            (firstArg<AppConfig>() as? AppConfig.Style)?.let { storedId.value = it.id }
        }
    }

    private val repository = object : StyleLibraryRepository {
        override val library = this@StyleEditorViewModelTest.library
        override suspend fun reload() = Unit
        override suspend fun save(definition: StyleDefinition): Either<StyleDefinition, StyleLibraryError> {
            if (failSaving) return Either.Err(StyleLibraryError.CantWrite("/styles/${definition.id}.json", "Disk full"))
            library.value = StyleLibrary(library.value.styles.filter { it.id != definition.id } + definition)
            return Either.Ok(definition)
        }
        override suspend fun delete(id: String): Either<Unit, StyleLibraryError> {
            library.value = StyleLibrary(library.value.styles.filter { it.id != id })
            return Either.Ok(Unit)
        }
        override suspend fun import(file: File): Either<StyleDefinition, StyleLibraryError> = error("not used")
        override suspend fun export(definition: StyleDefinition, file: File): Either<Unit, StyleLibraryError> {
            exported += definition to file
            return Either.Ok(Unit)
        }
        override fun newId(name: String) = StyleIds.unique(StyleIds.fromName(name), library.value.styles.map { it.id }.toSet())
    }

    private val service = StylesService(AppSettingsService(settings), repository)

    private fun viewModel(start: StyleEditorStart) = StyleEditorViewModel(service, SaveFilePickerUseCase(ShellManager()), start)

    private fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { first(predicate) } }

    private val teal = BuiltInStyles.QUIET.copy(id = "teal", name = "Teal", look = AccentStyle.QUIET)

    @Test
    fun `a new style is saved under an id from its name, and drawn with`() {
        val viewModel = viewModel(StyleEditorStart.From(BuiltInStyles.TONAL, "Tonal copy", "Tonal"))

        viewModel.onAction(StyleEditorAction.SetSeed(StyleSeed.ACCENT, 0xFF268BD2.toInt()))
        viewModel.onAction(StyleEditorAction.Rename("  Solar  "))
        viewModel.onAction(StyleEditorAction.Save)
        viewModel.isDone.await { it }

        val saved = library.value.styles.single()
        assertEquals("solar", saved.id)
        assertEquals("Solar", saved.name)
        assertEquals(0xFF268BD2.toInt(), saved.light.seeds.accent)
        assertEquals("solar", storedId.value)
    }

    @Test
    fun `an edited style is saved over its file, and the selection stays`() {
        library.value = StyleLibrary(listOf(teal))
        storedId.value = "classic"
        val viewModel = viewModel(StyleEditorStart.Edit(teal))

        viewModel.onAction(StyleEditorAction.SetLook(AccentStyle.TONAL))
        viewModel.onAction(StyleEditorAction.Save)
        viewModel.isDone.await { it }

        assertEquals(listOf(teal.copy(look = AccentStyle.TONAL)), library.value.styles)
        assertEquals("classic", storedId.value)
    }

    @Test
    fun `a style without a name isn't saved`() {
        val viewModel = viewModel(StyleEditorStart.Edit(teal))

        viewModel.onAction(StyleEditorAction.Rename("   "))
        viewModel.onAction(StyleEditorAction.Save)
        Thread.sleep(300)

        assertFalse(viewModel.isDone.value)
        assertEquals(emptyList<StyleDefinition>(), library.value.styles)
    }

    @Test
    fun `a save that fails says why, and the editor stays open`() {
        failSaving = true
        val viewModel = viewModel(StyleEditorStart.Edit(teal))

        viewModel.onAction(StyleEditorAction.Rename("Teal 2"))
        viewModel.onAction(StyleEditorAction.Save)

        val message = viewModel.message.await { it != null }
        assertEquals(StyleEditorMessage.Failed(StyleLibraryError.CantWrite("/styles/teal.json", "Disk full")), message)
        assertFalse(viewModel.isDone.value)

        viewModel.onAction(StyleEditorAction.DismissMessage)
        assertEquals(null, viewModel.message.value)
    }

    @Test
    fun `export writes the style as the editor shows it, unsaved changes included`() {
        val viewModel = viewModel(StyleEditorStart.Edit(teal))

        viewModel.onAction(StyleEditorAction.SetSeed(StyleSeed.DANGER, 0xFFB4405A.toInt()))
        viewModel.onAction(StyleEditorAction.Export("/tmp/Teal.json"))

        assertEquals(StyleEditorMessage.Exported(File("/tmp/Teal.json").absolutePath), viewModel.message.await { it != null })
        assertEquals(0xFFB4405A.toInt(), exported.single().first.light.seeds.danger)
        assertEquals(emptyList<StyleDefinition>(), library.value.styles, "Exporting doesn't save")
    }

    @Test
    fun `deleting the selected style closes the editor and selects Tonal`() {
        library.value = StyleLibrary(listOf(teal))
        storedId.value = "teal"
        val viewModel = viewModel(StyleEditorStart.Edit(teal))

        viewModel.onAction(StyleEditorAction.Delete)
        viewModel.isDone.await { it }

        assertEquals(emptyList<StyleDefinition>(), library.value.styles)
        assertEquals(BuiltInStyles.TONAL_ID, storedId.value)
    }

    @Test
    fun `a new style that was never saved can't be deleted`() {
        val viewModel = viewModel(StyleEditorStart.From(teal, "Teal copy", "Teal"))
        library.value = StyleLibrary(listOf(teal))

        viewModel.onAction(StyleEditorAction.Delete)
        Thread.sleep(300)

        assertEquals(listOf(teal), library.value.styles)
        assertFalse(viewModel.isDone.value)
    }
}
