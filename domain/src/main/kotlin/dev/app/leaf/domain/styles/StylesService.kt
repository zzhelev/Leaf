// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.common.printLog
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StylesService"

/**
 * Leaf's styles (fork-only): the built-in ones and the user's, and the one the settings select (`accent_style`, which
 * holds its id).
 */
@Singleton
class StylesService @Inject constructor(
    private val appSettingsService: AppSettingsService,
    private val libraryRepository: StyleLibraryRepository,
) {
    private val loggedIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    val library: Flow<StyleLibrary> get() = libraryRepository.library

    /** Every style: the built-in ones in their order, then the user's by name. */
    val styles: Flow<List<StyleDefinition>> get() = libraryRepository.library.map(::allStyles)

    /** The style that draws Leaf. */
    val selected: Flow<StyleDefinition>
        get() = combine(appSettingsService.styleId, libraryRepository.library, ::selectedIn).distinctUntilChanged()

    /** Reads the styles folder again, for files changed outside Leaf. */
    suspend fun reload() = libraryRepository.reload()

    suspend fun select(id: String) = appSettingsService.setConfiguration(AppConfig.Style(id))

    /** Writes [style], new or changed, to its file. */
    suspend fun save(style: StyleDefinition): Either<StyleDefinition, StyleLibraryError> = libraryRepository.save(style)

    /** Deletes [style]'s file. When it was the selected style, Tonal is selected, so that nothing is left pointing at it. */
    suspend fun delete(style: StyleDefinition): Either<Unit, StyleLibraryError> {
        val result = libraryRepository.delete(style.id)

        if (result is Either.Ok && appSettingsService.styleId.first() == style.id) {
            select(BuiltInStyles.DEFAULT_ID)
        }

        return result
    }

    suspend fun import(file: File): Either<StyleDefinition, StyleLibraryError> = libraryRepository.import(file)

    suspend fun export(style: StyleDefinition, file: File): Either<Unit, StyleLibraryError> =
        libraryRepository.export(style, file)

    /** A free id for a new style named [name]. */
    fun newId(name: String): String = libraryRepository.newId(name)

    fun allStyles(library: StyleLibrary): List<StyleDefinition> =
        BuiltInStyles.ALL + library.styles.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * The style whose id is [id]: a built-in one, or the user's. An id that names neither (a style deleted, its file
     * unreadable or gone, or a value from another version) gives the default, Tonal, and is logged once.
     */
    fun selectedIn(id: String, library: StyleLibrary): StyleDefinition {
        BuiltInStyles.byId(id)?.let { return it }
        library.byId(id)?.let { return it }

        if (loggedIds.add(id)) {
            val why = library.unreadable.firstOrNull { it.fileName == "$id.json" }
                ?.let { "its file can't be read (${it.reason}: ${it.detail})" }
                ?: "no style has that id"
            printLog(TAG, "The style \"$id\" is selected, but $why, so Leaf uses ${BuiltInStyles.DEFAULT_ID}")
        }

        return BuiltInStyles.DEFAULT
    }
}
