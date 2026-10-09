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
 * twice keeps its first entry, missing columns are added with their defaults where they'd be by default, bad widths
 * become the defaults, and unreadable text gives the default settings.
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

        return LogColumnsSettings(
            columns = withMissingColumns(known),
            dateShowsTime = stored.dateShowsTime,
            graphMaxWidth = LogColumnsSettings.clampGraphWidth(
                stored.graphMaxWidth ?: LogColumnsSettings.DEFAULT_GRAPH_MAX_WIDTH,
                LogColumnsSettings.DEFAULT_GRAPH_MAX_WIDTH,
            ),
        )
    }

    /**
     * Adds each column missing from [columns], such as one added after the settings were saved, right after the column
     * that comes before it by default, or first when there's none.
     */
    private fun withMissingColumns(columns: List<LogColumnEntry>): List<LogColumnEntry> {
        val result = columns.toMutableList()
        val defaults = LogColumnsSettings.DEFAULT_COLUMNS

        for ((index, default) in defaults.withIndex()) {
            if (result.any { it.column == default.column }) continue

            val previous = defaults.take(index).lastOrNull { before -> result.any { it.column == before.column } }
            val position = if (previous == null) 0 else result.indexOfFirst { it.column == previous.column } + 1

            result.add(position, default)
        }

        return result
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
