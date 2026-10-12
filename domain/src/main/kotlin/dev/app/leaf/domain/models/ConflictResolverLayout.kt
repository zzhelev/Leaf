// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * How the built-in resolver lays out ours, the result and theirs (`docs/fork/conflict-resolver.md`, section 5): the
 * toolbar's toggle and Settings → Conflicts, for the whole app.
 */
enum class ConflictResolverLayout {
    /** Ours, the result and theirs side by side, with arrows into the result (Option A). */
    COLUMNS,

    /** Ours and theirs side by side on top, and the result below at the full width (Option B). */
    STACKED;

    companion object {
        val DEFAULT = COLUMNS
    }
}
