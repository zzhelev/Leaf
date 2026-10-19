// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WatchRegistrationsTest {
    private val tabA = Any()
    private val tabB = Any()

    @Test
    fun `no warning below the budget`() {
        val registrations = WatchRegistrations(isLimited = true)

        registrations.update(tabA, WATCH_WARNING_AT - 1)

        assertNull(registrations.warning.value)
    }

    @Test
    fun `the tabs together pass the budget, and the warning counts them`() {
        val registrations = WatchRegistrations(isLimited = true)

        registrations.update(tabA, 120)
        assertNull(registrations.warning.value)
        registrations.update(tabB, 90)

        assertEquals(WatchUsage(registrations = 210, tabs = 2), registrations.warning.value)
    }

    @Test
    fun `closing a tab takes its folders off the count, and the warning goes`() {
        val registrations = WatchRegistrations(isLimited = true)
        registrations.update(tabA, 120)
        registrations.update(tabB, 90)

        registrations.release(tabB)

        assertNull(registrations.warning.value)
        assertEquals(WatchUsage(120, 1), registrations.usage.value)
    }

    @Test
    fun `a watcher that watches nothing any more isn't a tab`() {
        val registrations = WatchRegistrations(isLimited = true)
        registrations.update(tabA, 5)

        registrations.update(tabA, 0)

        assertEquals(WatchUsage(0, 0), registrations.usage.value)
    }

    @Test
    fun `systems without the limit never warn`() {
        val registrations = WatchRegistrations(isLimited = false)

        registrations.update(tabA, 5_000)

        assertNull(registrations.warning.value)
        assertEquals(5_000, registrations.usage.value.registrations)
    }
}
