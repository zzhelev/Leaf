// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores the user's [WorktreeLabelRule]s as JSON (`[{"label":"Aider","pattern":"~/aider-worktrees/<repo-name>-*"}]`), in their order, which
 * is the order they're tried in. Reading never fails: unreadable text gives no rules. Rules that are being typed, with
 * a blank label or pattern, are kept, so that the Settings list shows them again.
 */
object WorktreeLabelRulesCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun encode(rules: List<WorktreeLabelRule>): String = json.encodeToString(
        rules.map { StoredRule(it.label, it.pattern) }
    )

    fun decode(text: String?): List<WorktreeLabelRule> {
        if (text.isNullOrBlank()) return emptyList()

        val stored = try {
            json.decodeFromString<List<StoredRule>>(text)
        } catch (ex: SerializationException) {
            return emptyList()
        } catch (ex: IllegalArgumentException) {
            return emptyList()
        }

        return stored.map { WorktreeLabelRule(it.label, it.pattern) }
    }

    @Serializable
    private data class StoredRule(
        val label: String = "",
        val pattern: String = "",
    )
}
