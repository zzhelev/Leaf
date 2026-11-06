// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

enum class WindowButton { MINIMIZE, MAXIMIZE, CLOSE }

/**
 * Which window buttons Leaf draws on Linux, and on which side of the title bar, as the desktop's own windows have them
 * (fork-only, docs/fork/window-chrome.md, section 10). The buttons that Leaf has no use for (a window menu, "on all
 * desktops", help) are left out.
 */
data class WindowButtonLayout(val start: List<WindowButton>, val end: List<WindowButton>) {
    companion object {
        /** Minimize, maximize and close at the right, as most desktops have them out of the box. */
        val DEFAULT = WindowButtonLayout(
            start = emptyList(),
            end = listOf(WindowButton.MINIMIZE, WindowButton.MAXIMIZE, WindowButton.CLOSE),
        )
    }
}

/**
 * GNOME's `button-layout` as `gsettings get` prints it, Cinnamon's and MATE's too: `'appmenu:minimize,maximize,close'`.
 * The colon parts the left corner from the right one; without one, every button is at the left, as Mutter reads it.
 * Null when it isn't such a value.
 */
fun gnomeButtonLayout(value: String): WindowButtonLayout? {
    val layout = value.trim().removeSurrounding("'").removeSurrounding("\"").trim()

    if (layout.isEmpty() || layout.any { it == '\n' }) return null

    fun buttonsOf(side: String): List<WindowButton> = side.split(',').mapNotNull { name ->
        when (name.trim()) {
            "minimize" -> WindowButton.MINIMIZE
            "maximize" -> WindowButton.MAXIMIZE
            "close" -> WindowButton.CLOSE
            else -> null
        }
    }

    val sides = layout.split(':', limit = 2)

    return WindowButtonLayout(start = buttonsOf(sides[0]), end = buttonsOf(sides.getOrElse(1) { "" }))
}

/** KDE's defaults for `ButtonsOnLeft` and `ButtonsOnRight`: the window menu and "on all desktops", then help, minimize, maximize and close. */
private const val KDE_DEFAULT_LEFT = "MS"
private const val KDE_DEFAULT_RIGHT = "HIAX"

/**
 * KDE's `ButtonsOnLeft` and `ButtonsOnRight` in `kwinrc` (`[org.kde.kdecoration2]`), one letter a button: `I`
 * minimize, `A` maximize, `X` close. A side that isn't set has KDE's default.
 */
fun kdeButtonLayout(left: String?, right: String?): WindowButtonLayout {
    fun buttonsOf(side: String): List<WindowButton> = side.mapNotNull { letter ->
        when (letter) {
            'I' -> WindowButton.MINIMIZE
            'A' -> WindowButton.MAXIMIZE
            'X' -> WindowButton.CLOSE
            else -> null
        }
    }

    return WindowButtonLayout(
        start = buttonsOf(left?.trim()?.takeIf { it.isNotEmpty() } ?: KDE_DEFAULT_LEFT),
        end = buttonsOf(right?.trim()?.takeIf { it.isNotEmpty() } ?: KDE_DEFAULT_RIGHT),
    )
}

/**
 * Xfce's `/general/button_layout` in `xfwm4`: `O|HMC`, one letter a button and `|` for the title: `H` minimize (hide),
 * `M` maximize, `C` close. Null when it has no title.
 */
fun xfceButtonLayout(value: String): WindowButtonLayout? {
    val layout = value.trim()
    val title = layout.indexOf('|')

    if (title < 0) return null

    fun buttonsOf(side: String): List<WindowButton> = side.mapNotNull { letter ->
        when (letter) {
            'H' -> WindowButton.MINIMIZE
            'M' -> WindowButton.MAXIMIZE
            'C' -> WindowButton.CLOSE
            else -> null
        }
    }

    return WindowButtonLayout(start = buttonsOf(layout.substring(0, title)), end = buttonsOf(layout.substring(title + 1)))
}

/**
 * The desktop's button layout, read with its own tool ([run] gives a command's output, or null when it fails), or
 * [WindowButtonLayout.DEFAULT] when it can't be read. [desktops] are [desktopNames].
 */
fun readWindowButtonLayout(desktops: List<String>, run: (List<String>) -> String?): WindowButtonLayout {
    fun gsettings(schema: String) = run(listOf("gsettings", "get", schema, "button-layout"))?.let(::gnomeButtonLayout)

    fun kreadconfig(key: String): String? = listOf("kreadconfig6", "kreadconfig5").firstNotNullOfOrNull { tool ->
        run(listOf(tool, "--file", "kwinrc", "--group", "org.kde.kdecoration2", "--key", key))
    }

    val layout = when {
        desktops.any { it == "kde" || it == "plasma" } -> {
            val right = kreadconfig("ButtonsOnRight")
            // Neither tool there: KDE's defaults would only be a guess
            if (right == null) null else kdeButtonLayout(kreadconfig("ButtonsOnLeft"), right)
        }

        desktops.any { it == "xfce" } ->
            run(listOf("xfconf-query", "-c", "xfwm4", "-p", "/general/button_layout"))?.let(::xfceButtonLayout)

        desktops.any { it == "x-cinnamon" || it == "cinnamon" } -> gsettings("org.cinnamon.desktop.wm.preferences")
        desktops.any { it == "mate" } -> gsettings("org.mate.Marco.general")
        else -> gsettings("org.gnome.desktop.wm.preferences")
    }

    return layout ?: WindowButtonLayout.DEFAULT
}
