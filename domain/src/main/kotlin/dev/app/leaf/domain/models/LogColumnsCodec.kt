// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores [LogColumnsSettings] as JSON. Reading never fails: unknown columns and fields are skipped, a column listed
 * twice keeps its first entry, missing columns are added at the end with their defaults, bad widths become the
 * defaults, and unreadable text gives the default settings.
 */
object LogColumnsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun encode(settings: LogColumnsSettings): String = json.encodeToString(
        StoredLogColumns(
            columns = settings.columns.map { StoredColumn(it.column.name, it.isVisible, it.width) },
            dateShowsTime = settings.dateShowsTime,
            graphMaxWidth = settings.graphMaxWidth,
        )
    )

    fun decode(text: String?): LogColumnsSettings {
        val stored = read(text) ?: return LogColumnsSettings()

        val known = stored.columns
            .mapNotNull { entry ->
                val column = LogColumn.entries.firstOrNull { it.name == entry.column } ?: return@mapNotNull null
                val width = entry.width ?: column.defaultWidth

                LogColumnEntry(column, entry.visible, LogColumnsSettings.clampColumnWidth(width, column.defaultWidth))
            }
            .distinctBy { it.column }

        val missing = LogColumnsSettings.DEFAULT_COLUMNS.filter { default -> known.none { it.column == default.column } }

        return LogColumnsSettings(
            columns = known + missing,
            dateShowsTime = stored.dateShowsTime,
            graphMaxWidth = LogColumnsSettings.clampGraphWidth(
                stored.graphMaxWidth ?: LogColumnsSettings.DEFAULT_GRAPH_MAX_WIDTH,
                LogColumnsSettings.DEFAULT_GRAPH_MAX_WIDTH,
            ),
        )
    }

    private fun read(text: String?): StoredLogColumns? {
        if (text.isNullOrBlank()) return null

        return try {
            json.decodeFromString<StoredLogColumns>(text)
        } catch (ex: SerializationException) {
            null
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    // Columns are stored by name, so that a column this version doesn't know is skipped instead of failing the list
    @Serializable
    private data class StoredColumn(
        val column: String,
        val visible: Boolean = false,
        val width: Float? = null,
    )

    @Serializable
    private data class StoredLogColumns(
        val columns: List<StoredColumn> = emptyList(),
        val dateShowsTime: Boolean = false,
        val graphMaxWidth: Float? = null,
    )
}
