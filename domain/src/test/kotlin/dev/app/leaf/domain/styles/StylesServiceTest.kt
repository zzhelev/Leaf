// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.domain.models.AppConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class StylesServiceTest {
    private val storedId = MutableStateFlow<String?>(null)
    private val library = MutableStateFlow(StyleLibrary())

    private val settings = mockk<AppSettingsRepository> {
        every { styleId } returns storedId
        coEvery { setConfiguration(any()) } answers {
            (firstArg<AppConfig>() as? AppConfig.Style)?.let { storedId.value = it.id }
        }
    }

    private val saved = mutableListOf<StyleDefinition>()
    private val deleted = mutableListOf<String>()

    private val repository = object : StyleLibraryRepository {
        override val library = this@StylesServiceTest.library
        override suspend fun reload() = Unit
        override suspend fun save(definition: StyleDefinition): Either<StyleDefinition, StyleLibraryError> {
            saved += definition
            library.value = StyleLibrary(library.value.styles.filter { it.id != definition.id } + definition)
            return Either.Ok(definition)
        }
        override suspend fun delete(id: String): Either<Unit, StyleLibraryError> {
            deleted += id
            library.value = StyleLibrary(library.value.styles.filter { it.id != id })
            return Either.Ok(Unit)
        }
        override suspend fun import(file: File): Either<StyleDefinition, StyleLibraryError> = error("not used")
        override suspend fun export(definition: StyleDefinition, file: File) = Either.Ok(Unit)
        override fun newId(name: String) = StyleIds.unique(StyleIds.fromName(name), library.value.styles.map { it.id }.toSet())
    }

    private val service = StylesService(AppSettingsService(settings), repository)

    private fun style(id: String, name: String) = BuiltInStyles.QUIET.copy(id = id, name = name, look = AccentStyle.QUIET)

    private val teal = style("teal", "Teal")
    private val amber = style("amber", "amber")

    @Test
    fun `no choice draws Tonal`(): Unit = runBlocking {
        assertEquals(BuiltInStyles.TONAL, service.selected.first())
    }

    @Test
    fun `a built-in style's id draws it, and a user style's id draws theirs`(): Unit = runBlocking {
        library.value = StyleLibrary(listOf(teal))

        for (builtIn in BuiltInStyles.ALL) {
            storedId.value = builtIn.id
            assertEquals(builtIn, service.selected.first())
        }

        storedId.value = "teal"
        assertEquals(teal, service.selected.first())
    }

    @Test
    fun `a style deleted, unreadable or unknown draws Tonal, and is logged once`(): Unit = runBlocking {
        val unreadable = StyleLibrary(unreadable = listOf(UnreadableStyleFile("broken.json", UnreadableStyle.NOT_JSON, "x")))
        val logged = capturingOutput {
            library.value = StyleLibrary(listOf(teal))
            storedId.value = "teal"
            assertEquals(teal, service.selected.first())

            // Its file deleted, outside Leaf or in another window
            library.value = StyleLibrary()
            assertEquals(BuiltInStyles.TONAL, service.selected.first())
            assertEquals(BuiltInStyles.TONAL, service.selected.first())

            library.value = unreadable
            storedId.value = "broken"
            assertEquals(BuiltInStyles.TONAL, service.selected.first())

            storedId.value = "vivid"
            assertEquals(BuiltInStyles.TONAL, service.selected.first())
        }

        val lines = logged.lines().filter { "StylesService" in it }
        assertEquals(3, lines.size, logged)
        assertEquals(1, lines.count { "\"teal\"" in it && "no style has that id" in it }, logged)
        assertEquals(1, lines.count { "\"broken\"" in it && "can't be read" in it && "NOT_JSON" in it }, logged)
        assertEquals(1, lines.count { "\"vivid\"" in it }, logged)
    }

    @Test
    fun `every style is listed, the built-in ones first, then the user's by name`(): Unit = runBlocking {
        library.value = StyleLibrary(listOf(teal, amber))

        assertEquals(BuiltInStyles.ALL + listOf(amber, teal), service.styles.first())
    }

    @Test
    fun `deleting the selected style selects Tonal, and deleting another leaves the choice`(): Unit = runBlocking {
        library.value = StyleLibrary(listOf(teal, amber))
        storedId.value = "teal"

        service.delete(amber)
        assertEquals("teal", storedId.value)

        service.delete(teal)
        assertEquals(BuiltInStyles.TONAL_ID, storedId.value)
        assertEquals(listOf("amber", "teal"), deleted)
        assertEquals(BuiltInStyles.TONAL, service.selected.first())
    }

    @Test
    fun `selecting a style stores its id`(): Unit = runBlocking {
        service.select("classic")

        assertEquals("classic", storedId.value)
        assertEquals(BuiltInStyles.CLASSIC, service.selected.first())
    }

    private inline fun capturingOutput(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))

        try {
            block()
        } finally {
            System.setOut(original)
        }

        return captured.toString()
    }
}
