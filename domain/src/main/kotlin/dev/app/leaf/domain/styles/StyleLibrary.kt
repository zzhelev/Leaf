// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A file in the styles folder that couldn't be read, shown under the styles in Settings. */
data class UnreadableStyleFile(val fileName: String, val reason: UnreadableStyle, val detail: String)

/** The user's styles, as their files say (fork-only). The built-in styles aren't in it. */
data class StyleLibrary(
    val styles: List<StyleDefinition> = emptyList(),
    val unreadable: List<UnreadableStyleFile> = emptyList(),
) {
    fun byId(id: String): StyleDefinition? = styles.firstOrNull { it.id == id }
}

/** Why a style couldn't be saved, deleted, imported or exported. */
sealed interface StyleLibraryError {
    /** The file to import isn't a style. */
    data class NotAStyle(val fileName: String, val reason: UnreadableStyle, val detail: String) : StyleLibraryError

    data class CantRead(val path: String, val message: String) : StyleLibraryError

    data class CantWrite(val path: String, val message: String) : StyleLibraryError

    /** A built-in style can't be saved over or deleted. */
    data class BuiltIn(val id: String) : StyleLibraryError
}

/**
 * The folder of the user's styles (fork-only): one `<id>.json` file per style ([StyleFileCodec]). Leaf writes the
 * files; one edited outside Leaf shows when the folder is read again ([reload]).
 */
interface StyleLibraryRepository {
    val library: StateFlow<StyleLibrary>

    /** Reads the folder again. */
    suspend fun reload()

    /** Writes [definition] to its file, replacing what was there, and reads the folder again. */
    suspend fun save(definition: StyleDefinition): Either<StyleDefinition, StyleLibraryError>

    suspend fun delete(id: String): Either<Unit, StyleLibraryError>

    /**
     * Copies [file] into the folder as it is, with an id made from its name, new when that's taken, after checking that
     * it's a style. Gives the style as it reads.
     */
    suspend fun import(file: File): Either<StyleDefinition, StyleLibraryError>

    /** Writes [definition] to [file], wherever the user picked. */
    suspend fun export(definition: StyleDefinition, file: File): Either<Unit, StyleLibraryError>

    /** A free id for a new style named [name]. */
    fun newId(name: String): String
}
