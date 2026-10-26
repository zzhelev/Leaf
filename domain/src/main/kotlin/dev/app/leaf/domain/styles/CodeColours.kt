// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

/**
 * Whose colours code is drawn in (fork-only, `docs/fork/syntax-highlighting.md`, 4.5), stored as [key] in
 * `code_colours`. [LEAF] draws each style's own; [VS_CODE] and [INTELLIJ] put their published colours in every style's
 * syntax slots, which Leaf then checks as it checks any colour it works out.
 */
enum class CodeColours(val key: String) {
    LEAF("leaf"),
    VS_CODE("vscode"),
    INTELLIJ("intellij");

    /**
     * The published colour of each syntax slot, as ARGB, on [isLight]'s side, or null for [LEAF], which has none. A
     * slot mapped to null takes the style's text colour: IntelliJ leaves types plain.
     */
    fun published(isLight: Boolean): Map<StyleSlot, Int?>? = when (this) {
        LEAF -> null
        VS_CODE -> if (isLight) VS_CODE_LIGHT else VS_CODE_DARK
        INTELLIJ -> if (isLight) INTELLIJ_LIGHT else INTELLIJ_DARK
    }

    companion object {
        val DEFAULT = LEAF

        /** The choice that [key] names, or null for none, or one that this version doesn't know. */
        fun fromKey(key: String?): CodeColours? = entries.firstOrNull { it.key == key }
    }
}

private fun table(vararg colors: Long?): Map<StyleSlot, Int?> {
    require(colors.size == StyleSlot.SYNTAX.size)

    return StyleSlot.SYNTAX.zip(colors.map { it?.toInt() }).toMap()
}

// In StyleSlot.SYNTAX's order: keyword, annotation, comment, string, number, type, function, tag and attribute. VS
// Code's are its Light Modern and Dark Modern themes: one keyword colour for the purple of `if` and `return` too, and
// the annotation in the type's teal
private val VS_CODE_LIGHT = table(
    0xFF0000FF, 0xFF267F99, 0xFF008000, 0xFFA31515, 0xFF098658, 0xFF267F99, 0xFF795E26, 0xFF800000, 0xFFE50000,
)

private val VS_CODE_DARK = table(
    0xFF569CD6, 0xFF4EC9B0, 0xFF6A9955, 0xFFCE9178, 0xFFB5CEA8, 0xFF4EC9B0, 0xFFDCDCAA, 0xFF569CD6, 0xFF9CDCFE,
)

// IntelliJ IDEA's and Android Studio's Light and Dark schemes, which leave types in the text colour
private val INTELLIJ_LIGHT = table(
    0xFF0033B3, 0xFF9E880D, 0xFF8C8C8C, 0xFF067D17, 0xFF1750EB, null, 0xFF00627A, 0xFF0033B3, 0xFF871094,
)

private val INTELLIJ_DARK = table(
    0xFFCF8E6D, 0xFFB3AE60, 0xFF7A7E85, 0xFF6AAB73, 0xFF2AACB8, null, 0xFF56A8F5, 0xFFD5B778, 0xFFC77DBB,
)
