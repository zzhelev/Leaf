// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

/**
 * A style's look (fork-only): how its buttons and notices are drawn, which the editor calls "Buttons look". Each look
 * is also a built-in style (`BuiltInStyles`), whose colours the other styles in that look are worked out from. A
 * custom theme has its own colours, laid out as [CLASSIC], whatever the style. A style's file stores it as the name in
 * lower case.
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
    }
}
