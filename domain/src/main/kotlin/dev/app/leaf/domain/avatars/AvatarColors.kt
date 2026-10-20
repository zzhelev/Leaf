// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.avatars

import dev.app.leaf.domain.models.Identity
import java.text.Normalizer
import java.util.Locale

/**
 * The fills of an avatar without a picture, as ARGB (fork-only, upstream issue 231). The initial on it is white, so each
 * is at least 4.5 : 1 against white, and at least 3 : 1 against the plain row of the built-in light and dark themes.
 * They're Tailwind's 600 and 700 tones, in hue order: red, orange, gold, lime, green, teal, sky, blue, indigo, purple,
 * fuchsia, pink. `AvatarPaletteTest` checks the contrast and that no two look alike.
 *
 * The list is part of what a person's colour is: changing or reordering it gives everyone a new colour.
 */
val AVATAR_PALETTE: List<Int> = listOf(
    0xFFDC2626.toInt(),
    0xFFC2410C.toInt(),
    0xFFA16207.toInt(),
    0xFF4D7C0F.toInt(),
    0xFF15803D.toInt(),
    0xFF0F766E.toInt(),
    0xFF0369A1.toInt(),
    0xFF2563EB.toInt(),
    0xFF4F46E5.toInt(),
    0xFF9333EA.toInt(),
    0xFFC026D3.toInt(),
    0xFFDB2777.toInt(),
)

/**
 * What tells one person from another: the email, trimmed and in lower case, or the name when there is no email. Null for
 * someone with neither, who has no colour of their own.
 */
fun Identity.avatarKey(): String? {
    val text = email?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: return null

    // The same name typed with a composed or a decomposed accent is the same person
    return Normalizer.normalize(text.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

/** Where this person's colour is in [AVATAR_PALETTE]: the same for the same [avatarKey], in every run. */
fun Identity.avatarPaletteIndex(): Int? = avatarKey()?.let { Math.floorMod(stableHash(it), AVATAR_PALETTE.size) }

/** This person's fill, as ARGB, or null when they have no name and no email. */
fun Identity.avatarColorArgb(): Int? = avatarPaletteIndex()?.let { AVATAR_PALETTE[it] }

/**
 * FNV-1a over the UTF-8 bytes, then the finalizer of MurmurHash3, which spreads each bit over the others. Java's
 * `String.hashCode` is stable too, but similar emails (`dev1@x`, `dev2@x`) hash to neighbours, and neighbouring
 * palette entries would follow.
 */
internal fun stableHash(text: String): Int {
    var hash = 0x811C9DC5.toInt()

    for (byte in text.toByteArray(Charsets.UTF_8)) {
        hash = (hash xor (byte.toInt() and 0xFF)) * 0x01000193
    }

    hash = hash xor (hash ushr 16)
    hash *= 0x85EBCA6B.toInt()
    hash = hash xor (hash ushr 13)
    hash *= 0xC2B2AE35.toInt()

    return hash xor (hash ushr 16)
}
