// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.avatars

import dev.app.leaf.domain.models.Identity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A person's avatar colour (fork-only, upstream issue 231). */
class AvatarColorsTest {
    private fun person(email: String?, name: String? = "Someone") = Identity(name = name, email = email)

    @Test
    fun `a person's colour never changes`() {
        // Worked out apart from the code (FNV-1a and MurmurHash3's finalizer, in Python, with the hash as a signed int and
        // a floor modulo, as Math.floorMod), so an edit of the hash or of the order of the palette, which would give
        // everyone a new colour, shows here
        val expected = mapOf(
            "maya.chen@example.com" to 7,
            "tomas.garcia@example.com" to 1,
            "zzhelev@gmail.com" to 2,
            "priya@acme-corp.io" to 4,
            "a@b.c" to 6,
        )

        for ((email, index) in expected) {
            assertEquals(index, person(email).avatarPaletteIndex(), email)
            assertEquals(AVATAR_PALETTE[index], person(email).avatarColorArgb(), email)
        }

        assertEquals(5, person(email = null, name = "Maya Chen").avatarPaletteIndex(), "a name without an email")
    }

    @Test
    fun `the colours of five people never change`() {
        // The colours themselves, as ARGB, so that reordering or editing the palette shows here too
        assertEquals(0xFF2563EB.toInt(), person("maya.chen@example.com").avatarColorArgb(), "blue")
        assertEquals(0xFFC2410C.toInt(), person("tomas.garcia@example.com").avatarColorArgb(), "orange")
        assertEquals(0xFFA16207.toInt(), person("zzhelev@gmail.com").avatarColorArgb(), "gold")
        assertEquals(0xFF15803D.toInt(), person("priya@acme-corp.io").avatarColorArgb(), "green")
        assertEquals(0xFF0369A1.toInt(), person("a@b.c").avatarColorArgb(), "sky")
    }

    @Test
    fun `case and surrounding spaces don't change the colour`() {
        val plain = person("maya.chen@example.com").avatarPaletteIndex()

        assertEquals(plain, person("Maya.Chen@Example.COM").avatarPaletteIndex())
        assertEquals(plain, person("  maya.chen@example.com\t").avatarPaletteIndex())
        assertEquals(plain, person("MAYA.CHEN@EXAMPLE.COM", name = "A different name").avatarPaletteIndex())
    }

    @Test
    fun `the email decides, not the name`() {
        val first = person("maya.chen@example.com", name = "Maya Chen")
        val second = person("maya.chen@example.com", name = "M. Chen")

        assertEquals(first.avatarColorArgb(), second.avatarColorArgb())
    }

    @Test
    fun `a blank email falls back to the name, ignoring case and spaces`() {
        val byName = person(email = null, name = "Maya Chen").avatarPaletteIndex()

        assertEquals(byName, person(email = "", name = "Maya Chen").avatarPaletteIndex())
        assertEquals(byName, person(email = "   ", name = "maya chen").avatarPaletteIndex())
        assertEquals(byName, person(email = null, name = "  MAYA CHEN ").avatarPaletteIndex())
    }

    @Test
    fun `an accent typed composed or decomposed is the same person`() {
        val composed = person(email = null, name = "Ünal")
        val decomposed = person(email = null, name = "Ünal")

        assertEquals(composed.avatarPaletteIndex(), decomposed.avatarPaletteIndex())
    }

    @Test
    fun `no name and no email has no colour of its own`() {
        assertNull(Identity(name = null, email = null).avatarColorArgb())
        assertNull(Identity(name = "", email = "").avatarColorArgb())
        assertNull(Identity(name = "  ", email = " \t").avatarColorArgb())
    }

    @Test
    fun `a colour is always one of the palette`() {
        for (i in 0 until 2_000) {
            val index = person("user$i@example.com").avatarPaletteIndex()

            assertNotNull(index)
            assertTrue(index!! in AVATAR_PALETTE.indices, "$i -> $index")
        }
    }

    @Test
    fun `5000 emails spread over every colour`() {
        val counts = IntArray(AVATAR_PALETTE.size)

        for (i in 0 until 5_000) counts[person("user$i@example.com").avatarPaletteIndex()!!]++

        // 417 on average with a standard deviation of 20: 80% to 120% is more than four deviations
        val mean = 5_000.0 / AVATAR_PALETTE.size

        counts.forEachIndexed { index, count ->
            assertTrue(count in (mean * 0.8).toInt()..(mean * 1.2).toInt(), "colour $index has $count of 5000: ${counts.toList()}")
        }
    }

    @Test
    fun `emails that differ by one digit don't get neighbouring colours`() {
        // String.hashCode would map dev1@, dev2@, dev3@ to consecutive values, so to consecutive palette entries
        val indices = (0 until 1_000).map { person("dev$it@example.com").avatarPaletteIndex()!! }
        val neighbours = indices.zipWithNext().count { (a, b) -> (b - a + AVATAR_PALETTE.size) % AVATAR_PALETTE.size == 1 }

        // 1 in 12 by chance, so about 83 of 999
        assertTrue(neighbours in 40..140, "$neighbours of 999 pairs are neighbours")
    }

    @Test
    fun `non-ASCII text is hashed as UTF-8, whatever the platform's charset`() {
        // 0 from the same Python reference as the golden values above, which encodes as UTF-8
        val bulgarian = person(email = null, name = "Zhelyazko \u0416\u0435\u043b\u044f\u0437\u043a\u043e")

        assertEquals(0, bulgarian.avatarPaletteIndex())
        assertEquals(0, person(email = null, name = "ZHELYAZKO \u0416\u0415\u041b\u042f\u0417\u041a\u041e").avatarPaletteIndex())
    }

    @Test
    fun `the palette has twelve different colours`() {
        assertEquals(12, AVATAR_PALETTE.size)
        assertEquals(AVATAR_PALETTE.size, AVATAR_PALETTE.toSet().size)
    }
}
