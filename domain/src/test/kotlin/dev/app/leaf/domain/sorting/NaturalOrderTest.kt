// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NaturalOrderTest {
    @Test
    fun `compares version tags numerically`() {
        assertTrue(naturalCompare("3.9.0", "3.10.0") < 0)
        assertTrue(naturalCompare("3.10.0", "3.9.0") > 0)
        assertTrue(naturalCompare("v2.0.0", "v10.0.0") < 0)

        assertEquals(
            listOf("3.1.0", "3.2.0", "3.2.1", "3.3.0", "3.10.0"),
            listOf("3.10.0", "3.2.1", "3.1.0", "3.3.0", "3.2.0").sortedWith(NaturalOrder),
        )
    }

    @Test
    fun `compares ticket numbers numerically`() {
        assertTrue(naturalCompare("CAPS-1034", "CAPS-1249") < 0)
        assertTrue(naturalCompare("CAPS-656-pallet-crop", "CAPS-1034-migrate") < 0)
        assertTrue(naturalCompare("feature/CAPS-1249-1-safe-archive", "feature/CAPS-1249-device-space") < 0)
    }

    @Test
    fun `ignores case`() {
        assertTrue(naturalCompare("alpha", "Beta") < 0)
        assertTrue(naturalCompare("Alpha", "beta") < 0)
    }

    @Test
    fun `orders strings that differ only in case or leading zeros consistently`() {
        assertEquals(0, naturalCompare("same", "same"))
        assertTrue(naturalCompare("Main", "main") != 0)
        assertEquals(-naturalCompare("Main", "main"), naturalCompare("main", "Main"))
        assertTrue(naturalCompare("v01", "v1") != 0)
        assertTrue(naturalCompare("v01", "v2") < 0)
    }

    @Test
    fun `a shorter prefix comes first`() {
        assertTrue(naturalCompare("release", "release-2") < 0)
        assertTrue(naturalCompare("v1", "v1.1") < 0)
    }

    @Test
    fun `handles numbers longer than a Long`() {
        assertTrue(naturalCompare("x99999999999999999999", "x100000000000000000000") < 0)
    }
}
