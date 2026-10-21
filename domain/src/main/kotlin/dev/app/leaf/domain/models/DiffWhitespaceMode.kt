// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * How the diff treats whitespace when it compares two versions of a file: the View menu's "Ignore whitespace", for the
 * whole app. Stored as the name in lower case; a name this version doesn't know reads as [DEFAULT].
 */
enum class DiffWhitespaceMode {
    /** Every difference is a change, as before. */
    SHOW_ALL,

    /**
     * Lines that only differ in whitespace are equal, as `git diff -w` compares them: indentation, trailing spaces,
     * tabs against spaces, line endings and the spacing inside a line.
     */
    IGNORE_ALL;

    /** Whether a diff made in this mode hides changes, which hunk and line actions can't tell from their patches. */
    val ignoresWhitespace: Boolean get() = this != SHOW_ALL

    companion object {
        val DEFAULT = SHOW_ALL

        /** The mode that a stored [name] means, or [DEFAULT] for none or one that this version doesn't know. */
        fun fromStored(name: String?): DiffWhitespaceMode =
            entries.firstOrNull { it.name.lowercase() == name } ?: DEFAULT
    }
}
