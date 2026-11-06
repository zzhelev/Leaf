// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import dev.app.leaf.ui.window.linux.WindowButton.CLOSE
import dev.app.leaf.ui.window.linux.WindowButton.MAXIMIZE
import dev.app.leaf.ui.window.linux.WindowButton.MINIMIZE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Where the window's buttons go, as each desktop's settings say (docs/fork/window-chrome.md, section 10). */
class WindowButtonLayoutTest {
    private fun layout(start: List<WindowButton>, end: List<WindowButton>) = WindowButtonLayout(start, end)

    @Test
    fun `GNOME's layout, as gsettings prints it`() {
        // GNOME's own default: the app menu at the left, close alone at the right
        assertEquals(layout(emptyList(), listOf(CLOSE)), gnomeButtonLayout("'appmenu:close'"))
        // Ubuntu's
        assertEquals(layout(emptyList(), listOf(MINIMIZE, MAXIMIZE, CLOSE)), gnomeButtonLayout("':minimize,maximize,close'\n"))
        // macOS's order at the left, with a spacer that Leaf leaves out
        assertEquals(
            layout(listOf(CLOSE, MINIMIZE, MAXIMIZE), listOf()),
            gnomeButtonLayout("'close,minimize,spacer,maximize:'"),
        )
        // Without a colon, every button is at the left, as Mutter reads it
        assertEquals(layout(listOf(CLOSE), emptyList()), gnomeButtonLayout("'close'"))
        assertEquals(layout(emptyList(), emptyList()), gnomeButtonLayout("':'"))
    }

    @Test
    fun `a GNOME value that isn't one gives nothing`() {
        assertNull(gnomeButtonLayout(""))
        assertNull(gnomeButtonLayout("''"))
        assertNull(gnomeButtonLayout("No such key “button-layout”\nmore"))
    }

    @Test
    fun `KDE's letters, with its defaults for a side that isn't set`() {
        assertEquals(layout(emptyList(), listOf(MINIMIZE, MAXIMIZE, CLOSE)), kdeButtonLayout(null, null))
        assertEquals(layout(emptyList(), listOf(MINIMIZE, MAXIMIZE, CLOSE)), kdeButtonLayout("", ""))
        assertEquals(layout(listOf(CLOSE, MAXIMIZE, MINIMIZE), emptyList()), kdeButtonLayout("XAI", "S"))
        assertEquals(layout(emptyList(), listOf(CLOSE)), kdeButtonLayout("MS", "X"))
    }

    @Test
    fun `Xfce's letters, parted by the title`() {
        assertEquals(layout(emptyList(), listOf(MINIMIZE, MAXIMIZE, CLOSE)), xfceButtonLayout("O|SHMC"))
        assertEquals(layout(listOf(CLOSE, MINIMIZE), listOf(MAXIMIZE)), xfceButtonLayout("CH|M"))
        assertNull(xfceButtonLayout("OHMC"))
    }

    @Test
    fun `each desktop is asked with its own tool, and anything that fails gives the default`() {
        val asked = mutableListOf<List<String>>()

        fun read(desktops: List<String>, answers: Map<String, String?>) = readWindowButtonLayout(desktops) { command ->
            asked += command
            answers[command.first()]
        }

        assertEquals(layout(emptyList(), listOf(CLOSE)), read(listOf("ubuntu", "gnome"), mapOf("gsettings" to "'appmenu:close'")))
        assertEquals(listOf("gsettings", "get", "org.gnome.desktop.wm.preferences", "button-layout"), asked.last())

        assertEquals(layout(listOf(CLOSE), listOf(MINIMIZE)), read(listOf("x-cinnamon"), mapOf("gsettings" to "'close:minimize'")))
        assertEquals("org.cinnamon.desktop.wm.preferences", asked.last()[2])

        read(listOf("mate"), mapOf("gsettings" to "'menu:close'"))
        assertEquals("org.mate.Marco.general", asked.last()[2])

        assertEquals(layout(emptyList(), listOf(CLOSE)), read(listOf("xfce"), mapOf("xfconf-query" to "O|C")))

        // KDE 6 first, then 5, a key each side; an unset key prints nothing, which is KDE's default
        asked.clear()
        val kde5 = readWindowButtonLayout(listOf("kde")) { command ->
            asked += command
            when {
                command.first() != "kreadconfig5" -> null
                command.last() == "ButtonsOnRight" -> "AX"
                else -> "I"
            }
        }
        assertEquals(layout(listOf(MINIMIZE), listOf(MAXIMIZE, CLOSE)), kde5)
        assertEquals(listOf("kreadconfig6", "kreadconfig5"), asked.take(2).map { it.first() })
        assertEquals(layout(emptyList(), listOf(MINIMIZE, MAXIMIZE, CLOSE)), read(listOf("kde"), mapOf("kreadconfig6" to "")))

        // Nothing answers
        assertEquals(WindowButtonLayout.DEFAULT, read(listOf("kde"), emptyMap()))
        assertEquals(WindowButtonLayout.DEFAULT, read(listOf("gnome"), emptyMap()))
        assertEquals(WindowButtonLayout.DEFAULT, read(listOf("xfce"), mapOf("xfconf-query" to "")))
    }
}
