// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import dev.app.leaf.domain.window.SavedWindow
import dev.app.leaf.domain.window.WindowMode
import dev.app.leaf.domain.window.WindowRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.prefs.AbstractPreferences

private val BOUNDS = WindowRect(x = -1200, y = 40, width = 1100, height = 700)

/** The window's placement in the prefs node, on a node in memory rather than the user's (fork-only). */
class SavedWindowPreferencesTest {
    private val node = InMemoryPreferences()

    @Test
    fun `nothing kept reads as no saved window`() {
        assertNull(node.readSavedWindow())
    }

    @Test
    fun `a floating window reads back as it was kept`() {
        node.writeSavedWindow(SavedWindow(WindowMode.FLOATING, BOUNDS))

        assertEquals(SavedWindow(WindowMode.FLOATING, BOUNDS), node.readSavedWindow())
    }

    @Test
    fun `a maximized window reads back with where it floated, or without`() {
        node.writeSavedWindow(SavedWindow(WindowMode.MAXIMIZED, BOUNDS))
        assertEquals(SavedWindow(WindowMode.MAXIMIZED, BOUNDS), node.readSavedWindow())

        node.writeSavedWindow(SavedWindow(WindowMode.MAXIMIZED, floating = null))
        assertEquals(SavedWindow(WindowMode.MAXIMIZED, floating = null), node.readSavedWindow())
    }

    @Test
    fun `the keys and values in the node`() {
        node.writeSavedWindow(SavedWindow(WindowMode.FLOATING, BOUNDS))

        assertEquals(
            mapOf(
                "windowPlacement" to "floating",
                "windowX" to "-1200",
                "windowY" to "40",
                "windowWidth" to "1100",
                "windowHeight" to "700",
            ),
            node.values,
        )
    }

    @Test
    fun `a window without bounds, or none, takes out the keys it doesn't have`() {
        node.writeSavedWindow(SavedWindow(WindowMode.FLOATING, BOUNDS))
        node.writeSavedWindow(SavedWindow(WindowMode.MAXIMIZED, floating = null))

        assertEquals(mapOf("windowPlacement" to "maximized"), node.values)

        node.writeSavedWindow(null)

        assertEquals(emptyMap<String, String>(), node.values)
    }

    @Test
    fun `values that aren't numbers read as no bounds, and an unknown mode as maximized`() {
        node.put("windowPlacement", "fullscreen")
        node.put("windowX", "10")
        node.put("windowY", "ten")
        node.put("windowWidth", "800")
        node.put("windowHeight", "600")

        assertNull(node.readSavedWindow())

        node.put("windowY", " 20 ")

        assertEquals(SavedWindow(WindowMode.MAXIMIZED, WindowRect(10, 20, 800, 600)), node.readSavedWindow())
    }

    @Test
    fun `it leaves the node's other keys alone`() {
        node.put("firstPaneWidth", "220.0")

        node.writeSavedWindow(SavedWindow(WindowMode.FLOATING, BOUNDS))
        node.writeSavedWindow(null)

        assertEquals(mapOf("firstPaneWidth" to "220.0"), node.values)
    }
}

/** A preferences node that keeps its values in a map, so that tests never write to the user's own node. */
private class InMemoryPreferences : AbstractPreferences(null, "") {
    val values = sortedMapOf<String, String>()

    override fun putSpi(key: String, value: String) {
        values[key] = value
    }

    override fun getSpi(key: String): String? = values[key]

    override fun removeSpi(key: String) {
        values.remove(key)
    }

    override fun removeNodeSpi() = throw UnsupportedOperationException()

    override fun keysSpi(): Array<String> = values.keys.toTypedArray()

    override fun childrenNamesSpi(): Array<String> = emptyArray()

    override fun childSpi(name: String) = throw UnsupportedOperationException()

    override fun syncSpi() = Unit

    override fun flushSpi() = Unit
}
