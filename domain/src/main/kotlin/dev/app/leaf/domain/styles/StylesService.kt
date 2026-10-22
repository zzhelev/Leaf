// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.common.printLog
import dev.app.leaf.domain.services.AppSettingsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
