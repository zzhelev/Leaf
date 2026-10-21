// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

/**
 * How strong the colours of buttons and notices are, in the light and the dark theme (Settings → Appearance → Style).
 * A custom theme has its own colours, laid out as [CLASSIC], whatever the style. Stored as the name in lower case; none,
 * or a name that this version doesn't know, reads as [DEFAULT].
 */
enum class AccentStyle {
    /** Pale tints with dark text, in the family of the diff's tints; only the main action of a pane is filled. */
    TONAL,

    /** Filled buttons in a strong blue and red, and amber strips with near-black text: Leaf's look until 2026-10-21. */
    CLASSIC,

    /** Neutral buttons, red only in the text of destructive ones, and strips that read as notes. */
    QUIET;

    companion object {
        val DEFAULT = TONAL

        /** The style that a stored [name] means, or [DEFAULT] for none or one that this version doesn't know. */
        fun fromStored(name: String?): AccentStyle =
            entries.firstOrNull { it.name.lowercase() == name } ?: DEFAULT
    }
}
