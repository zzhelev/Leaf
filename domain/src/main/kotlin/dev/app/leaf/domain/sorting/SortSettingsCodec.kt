// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores [RefPanelSettings] and [FilesViewState] as JSON. Reading never fails: unknown fields are ignored, unknown enum
 * values become the field's default, and unreadable text gives the defaults.
 */
object SortSettingsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun encodeRefPanelSettings(settings: RefPanelSettings): String = json.encodeToString(settings)

    fun decodeRefPanelSettings(text: String?): RefPanelSettings = decode(text) ?: RefPanelSettings()

    fun encodeFilesViewState(state: FilesViewState): String = json.encodeToString(state)

    fun decodeFilesViewState(text: String?): FilesViewState {
        val state = decode<FilesViewState>(text) ?: return FilesViewState()

        return if (state.splitRatio in MIN_SPLIT_RATIO..MAX_SPLIT_RATIO) {
            state
        } else {
            state.copy(splitRatio = DEFAULT_FILES_SPLIT_RATIO)
        }
    }

    /**
     * The files view when none is saved yet. Up to Leaf 1.1.0, Staged and Unstaged had a list/tree toggle instead of
     * the view menu, and a tree turned on there carries over.
     */
    fun filesViewStateOrLegacy(saved: FilesViewState?, legacyShowAsTree: Boolean?): FilesViewState = when {
        saved != null -> saved
        legacyShowAsTree == true -> FilesViewState(viewMode = FilesViewMode.FolderTree)
        else -> FilesViewState()
    }

    const val MIN_SPLIT_RATIO = 0.15f
    const val MAX_SPLIT_RATIO = 0.85f

    private inline fun <reified T> decode(text: String?): T? {
        if (text.isNullOrBlank()) return null

        return try {
            json.decodeFromString<T>(text)
        } catch (ex: SerializationException) {
            null
        } catch (ex: IllegalArgumentException) {
            null
        }
    }
}
