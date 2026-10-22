// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import java.text.Normalizer
import java.util.Locale

/**
 * The ids of the user's styles (fork-only), which are the names of their files without `.json`: made from a style's
 * name, in lower case, with `a-z`, `0-9` and `-` only, and made unique with `-2`, `-3`… A built-in style's id is always
 * taken. Renaming a style keeps its id.
 */
object StyleIds {
    const val MAX_LENGTH = 40

    /** The id a style named [name] would have, before it's made unique: "Solarized Dark" is `solarized-dark`. */
    fun fromName(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)

        return plain
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_LENGTH)
            .trimEnd('-')
            .ifEmpty { "style" }
    }

    /** [wanted], or `wanted-2`, `wanted-3`…, the first that no built-in style and nothing in [taken] has. */
    fun unique(wanted: String, taken: Set<String>): String {
        fun isTaken(id: String) = id in taken || BuiltInStyles.isBuiltIn(id)

        if (!isTaken(wanted)) return wanted

        return generateSequence(2) { it + 1 }
            .map { number -> "${wanted.take(MAX_LENGTH - number.toString().length - 1)}-$number" }
            .first { !isTaken(it) }
    }
}
