// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.styles

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleFileCodec
import dev.app.leaf.domain.styles.StyleFileRead
import dev.app.leaf.domain.styles.StyleLibrary
import dev.app.leaf.domain.styles.StyleLibraryError
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSide
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.domain.styles.UnreadableStyle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileStyleLibraryRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val stylesDir get() = File(dir, "styles")

    private fun repository() = FileStyleLibraryRepository(StylesFolder(stylesDir))

    private fun style(id: String, name: String) = StyleDefinition(
        id = id,
        name = name,
        look = AccentStyle.QUIET,
        light = StyleSide(
            BuiltInStyles.templateSeeds(AccentStyle.QUIET, true).with(StyleSeed.ACCENT, 0xFF007475.toInt()),
            setByHand = mapOf(StyleSlot.TEXT to 0xFF1D2B2B.toInt()),
        ),
        dark = StyleSide(BuiltInStyles.templateSeeds(AccentStyle.QUIET, false)),
    )

    private val lagoon = style("lagoon", "Lagoon")

    private fun files() = stylesDir.list().orEmpty().sorted()

    private fun <T> ok(result: Either<T, StyleLibraryError>): T = assertInstanceOf(Either.Ok::class.java, result).value as T

    private fun err(result: Either<*, StyleLibraryError>): StyleLibraryError =
        assertInstanceOf(Either.Err::class.java, result).error as StyleLibraryError

    @Test
    fun `without a styles folder, there are no user styles, and nothing is made`() {
        assertEquals(StyleLibrary(), repository().library.value)
        assertFalse(stylesDir.exists())
    }

    @Test
    fun `a saved style is its file, which reads back as it was, with no temporary file left`(): Unit = runBlocking {
        val repository = repository()

        assertEquals(lagoon, ok(repository.save(lagoon)))
        assertEquals(listOf("lagoon.json"), files())
        assertEquals(StyleFileCodec.encode(lagoon), File(stylesDir, "lagoon.json").readText())
        assertEquals(listOf(lagoon), repository.library.value.styles)

        // A new start reads it from the folder
        assertEquals(listOf(lagoon), repository().library.value.styles)
    }

    @Test
    fun `saving again replaces the file, and a rename keeps the id`(): Unit = runBlocking {
        val repository = repository()
        repository.save(lagoon)
        repository.save(lagoon.copy(name = "Deep lagoon"))

        assertEquals(listOf("lagoon.json"), files())
        assertEquals(listOf(lagoon.copy(name = "Deep lagoon")), repository.library.value.styles)
    }

    @Test
    fun `a built-in style can't be saved over or deleted`(): Unit = runBlocking {
        val repository = repository()

        assertEquals(StyleLibraryError.BuiltIn("tonal"), err(repository.save(BuiltInStyles.TONAL)))
        assertEquals(StyleLibraryError.BuiltIn("classic"), err(repository.delete("classic")))
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `files that can't be read are left out and listed, and the others still read`(): Unit = runBlocking {
        stylesDir.mkdirs()
        File(stylesDir, "lagoon.json").writeText(StyleFileCodec.encode(lagoon))
        File(stylesDir, "broken.json").writeText("{ \"leafStyle\": 1, ")
        File(stylesDir, "old-theme.json").writeText("""{ "primary": "FF456b00", "isLight": true }""")
        File(stylesDir, "tonal.json").writeText(StyleFileCodec.encode(lagoon.copy(id = "tonal")))
        File(stylesDir, "huge.json").writeText(" ".repeat(1024 * 1024 + 1))
        File(stylesDir, "notes.txt").writeText("not a style, and not a .json file")
        File(stylesDir, "folder.json").mkdirs()

        val library = repository().library.value

        assertEquals(listOf(lagoon), library.styles)
        assertEquals(
            listOf(
                "broken.json" to UnreadableStyle.NOT_JSON,
                "huge.json" to UnreadableStyle.CANT_READ,
                "old-theme.json" to UnreadableStyle.NOT_A_STYLE,
                "tonal.json" to UnreadableStyle.BUILT_IN_ID,
            ),
            library.unreadable.map { it.fileName to it.reason },
        )
    }

    @Test
    fun `a file changed outside Leaf shows after a reload`(): Unit = runBlocking {
        val repository = repository()
        stylesDir.mkdirs()
        File(stylesDir, "lagoon.json").writeText(StyleFileCodec.encode(lagoon))

        assertEquals(StyleLibrary(), repository.library.value)

        repository.reload()

        assertEquals(listOf(lagoon), repository.library.value.styles)
    }

    @Test
    fun `deleting a style removes its file, and deleting one that's gone is fine`(): Unit = runBlocking {
        val repository = repository()
        repository.save(lagoon)

        ok(repository.delete("lagoon"))
        assertEquals(emptyList<String>(), files())
        assertEquals(StyleLibrary(), repository.library.value)

        ok(repository.delete("lagoon"))
    }

    @Test
    fun `an import is copied as it is, under an id from its file's name`(): Unit = runBlocking {
        val repository = repository()
        // A key that this version doesn't know stays in the copy, for a newer Leaf
        val text = StyleFileCodec.encode(lagoon).replace("\"name\": \"Lagoon\"", "\"name\": \"Lagoon\",\n  \"author\": \"Maya\"")
        val source = File(dir, "Solarized Dark.json").apply { writeText(text) }

        val imported = ok(repository.import(source))

        assertEquals(lagoon.copy(id = "solarized-dark"), imported)
        assertEquals(listOf("solarized-dark.json"), files())
        assertEquals(text, File(stylesDir, "solarized-dark.json").readText())
        assertEquals(listOf(imported), repository.library.value.styles)
        assertTrue(source.exists(), "The file picked stays where it was")
    }

    @Test
    fun `an import whose id is taken gets a new one, a built-in style's included`(): Unit = runBlocking {
        val repository = repository()
        repository.save(lagoon)
        val lagoonFile = File(dir, "lagoon.json").apply { writeText(StyleFileCodec.encode(lagoon.copy(name = "Other lagoon"))) }
        val tonalFile = File(dir, "tonal.json").apply { writeText(StyleFileCodec.encode(lagoon.copy(name = "My tonal"))) }

        assertEquals("lagoon-2", ok(repository.import(lagoonFile)).id)
        assertEquals("lagoon-3", ok(repository.import(lagoonFile)).id)
        assertEquals("tonal-2", ok(repository.import(tonalFile)).id)
        assertEquals(listOf("lagoon-2.json", "lagoon-3.json", "lagoon.json", "tonal-2.json"), files())
        assertEquals("Lagoon", repository.library.value.styles.single { it.id == "lagoon" }.name, "The first is untouched")
    }

    @Test
    fun `an import that isn't a style is refused, and nothing is written`(): Unit = runBlocking {
        val repository = repository()
        val oldTheme = File(dir, "theme.json").apply { writeText("""{ "primary": "FF456b00" }""") }
        val text = File(dir, "notes.json").apply { writeText("Solarized") }

        val notAStyle = err(repository.import(oldTheme))
        assertEquals(StyleLibraryError.NotAStyle("theme.json", UnreadableStyle.NOT_A_STYLE, "it has no \"leafStyle\" version"), notAStyle)
        assertEquals(UnreadableStyle.NOT_JSON, (err(repository.import(text)) as StyleLibraryError.NotAStyle).reason)
        assertInstanceOf(StyleLibraryError.CantRead::class.java, err(repository.import(File(dir, "missing.json"))))
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `an export is the style's file, wherever it's written, and imports back`(): Unit = runBlocking {
        val repository = repository()
        val target = File(dir, "exports/Lagoon.json").apply { parentFile.mkdirs() }

        ok(repository.export(lagoon, target))

        assertEquals(StyleFileCodec.encode(lagoon), target.readText())
        val read = assertInstanceOf(StyleFileRead.Read::class.java, StyleFileCodec.decode(target.readText(), "lagoon"))
        assertEquals(lagoon, read.definition)
        assertEquals(emptyList<String>(), files(), "Exporting doesn't add it to the library")
        assertEquals(lagoon.copy(id = "lagoon"), ok(repository.import(target)))
    }

    @Test
    fun `an export that can't be written says where`(): Unit = runBlocking {
        val blocked = File(dir, "a-file").apply { writeText("") }
        val error = err(repository().export(lagoon, File(blocked, "Lagoon.json")))

        assertInstanceOf(StyleLibraryError.CantWrite::class.java, error)
        assertTrue((error as StyleLibraryError.CantWrite).path.endsWith("a-file/Lagoon.json"), error.path)
    }

    @Test
    fun `a new style's id is free of every file in the folder, readable or not, and of the built-in ids`(): Unit =
        runBlocking {
            stylesDir.mkdirs()
            File(stylesDir, "lagoon.json").writeText("not JSON")
            val repository = repository()

            assertEquals("lagoon-2", repository.newId("Lagoon"))
            assertEquals("quiet-2", repository.newId("Quiet"))
            assertEquals("solarized-2", repository.newId("Solarized"))
            assertEquals("teal-2", repository.newId("Teal"))
            assertEquals("nord", repository.newId("Nord"))
        }
}
