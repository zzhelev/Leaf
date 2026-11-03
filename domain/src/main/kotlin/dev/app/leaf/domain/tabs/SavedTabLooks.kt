// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.tabs

import dev.app.leaf.common.printError
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val TAG = "SavedTabLooks"

private val json = Json { ignoreUnknownKeys = true }

/** A saved tab's look, with the path of the saved tab that it belongs to. */
@Serializable
private data class SavedTabLook(val path: String, val name: String? = null, val color: Int? = null)

/**
 * The looks of the saved tabs, as saved next to their paths (fork-only, upstream issue 242): one for each tab, in the
 * same order, with the tab's path. The paths stay a list of their own, which older versions of Leaf read as before.
 */
fun savedTabLooksJson(tabs: List<Pair<String, TabLook>>): String =
    json.encodeToString(tabs.map { (path, look) -> SavedTabLook(path, look.name, look.color) })

/**
 * The look of each of the saved tabs at [paths], from [savedJson]: the one saved in the same place for the same path,
 * so that a list that an older Leaf saved again without the looks gives them none. A look that can't be read is none.
 */
fun savedTabLooks(paths: List<String>, savedJson: String): List<TabLook> {
    val saved = if (savedJson.isBlank()) {
        emptyList()
    } else {
        try {
            json.decodeFromString<List<SavedTabLook>>(savedJson)
        } catch (e: SerializationException) {
            printError(TAG, "Ignoring the saved tabs' looks, which can't be read", e)
            emptyList()
        }
    }

    return paths.mapIndexed { index, path ->
        val look = saved.getOrNull(index)?.takeIf { it.path == path }

        TabLook(name = tabNameOf(look?.name), color = tabColorOf(look?.color))
    }
}
