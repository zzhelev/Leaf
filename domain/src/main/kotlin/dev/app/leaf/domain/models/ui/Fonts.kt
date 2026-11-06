// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models.ui

import dev.app.leaf.domain.sorting.naturalCompare

/** The font of everything but code (fork-only), stored as [key] in `interface_font`. */
enum class InterfaceFont(val key: String) {
    /** Inter, which Leaf ships. */
    INTER("inter"),

    /** The operating system's own: SF Pro on macOS, Segoe UI on Windows, fontconfig's sans-serif on Linux. */
    SYSTEM("system");

    companion object {
        val DEFAULT = INTER

        /** The choice that [key] names, or null for none, or one that this version doesn't know. */
        fun fromKey(key: String?): InterfaceFont? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The fonts Leaf draws with (fork-only). [codeFont] is the family name of an installed font, or null for Noto Sans
 * Mono, which Leaf ships. [codeLigatures] lets a code font join `!=`, `->` and the like into one sign.
 */
data class FontSettings(
    val interfaceFont: InterfaceFont = InterfaceFont.DEFAULT,
    val codeFont: String? = null,
    val codeLigatures: Boolean = false,
)

/** A family name as it's stored: trimmed, and null when blank, which stands for the bundled font. */
fun codeFontName(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }

/** One of the installed monospaced fonts that the code font list shows, with where the text typed matched its name. */
data class CodeFontChoice(val name: String, val matchStart: Int, val matchLength: Int)

/**
 * The installed fonts that the code font list shows for [text]: each family once, in natural order, and only those
 * whose name has [text], ignoring case and the spaces around it. [bundled] is left out, as the list shows it on its own.
 */
fun codeFontChoices(installed: List<String>, text: String, bundled: String): List<CodeFontChoice> {
    val typed = text.trim()

    return installed
        .mapNotNull(::codeFontName)
        .distinctBy { it.lowercase() }
        .filter { !it.equals(bundled, ignoreCase = true) }
        .sortedWith(::naturalCompare)
        .mapNotNull { name ->
            val start = if (typed.isEmpty()) 0 else name.indexOf(typed, ignoreCase = true)
            if (start < 0) null else CodeFontChoice(name, start, typed.length)
        }
}

/** Whether the bundled font's row shows for [text]: its name has it, as the other rows' names do. */
fun bundledFontShows(bundled: String, text: String): Boolean = bundled.contains(text.trim(), ignoreCase = true)
