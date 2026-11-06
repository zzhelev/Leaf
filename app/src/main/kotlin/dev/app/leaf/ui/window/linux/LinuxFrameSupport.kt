// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

/**
 * Whether Leaf can draw the window's own frame on this Linux desktop (fork-only, docs/fork/window-chrome.md, section 10).
 * It needs JetBrains Runtime, which asks the window manager to move the window, AWT on X11 or Wayland, and a window
 * manager that stacks windows: a tiling one places them itself and has no use for a title bar.
 */
sealed interface LinuxFrameSupport {
    data object Supported : LinuxFrameSupport

    data class Unsupported(val reason: Reason) : LinuxFrameSupport

    enum class Reason {
        /** A Java other than JetBrains Runtime, as for the Linux `.jar` run with another Java: nothing moves the window. */
        NOT_JETBRAINS_RUNTIME,

        /** AWT draws through neither X11 nor Wayland. */
        OTHER_TOOLKIT,

        /** WSL's window manager draws its own frames, and handles a window without one poorly. */
        WSL,

        TILING_WINDOW_MANAGER,

        /** No desktop that Leaf knows stacks its windows, or none is named. */
        UNKNOWN_DESKTOP,
    }
}

/** What [linuxFrameSupport] looks at, so that tests can give it. */
data class LinuxSystem(
    /** The environment: `XDG_CURRENT_DESKTOP`, `XDG_SESSION_DESKTOP`, `DESKTOP_SESSION`, WSL's variables. */
    val environment: Map<String, String>,
    /** `/proc/version`, which names Microsoft's kernel under WSL. */
    val procVersion: String?,
    /** The class of AWT's toolkit. */
    val toolkitClass: String,
    /** Whether JetBrains Runtime offers `WindowMove` (`JBR.isWindowMoveSupported()`). */
    val canMoveWindows: Boolean,
)

private val LINUX_TOOLKITS = setOf("sun.awt.X11.XToolkit", "sun.awt.wl.WLToolkit")

/** Window managers that tile, as their sessions and desktops name themselves, in lower case. */
private val TILING_WINDOW_MANAGERS = setOf(
    "i3", "sway", "hyprland", "bspwm", "awesome", "dwm", "qtile", "xmonad", "river", "herbstluftwm", "spectrwm",
    "leftwm", "niri", "regolith", "ratpoison", "stumpwm", "notion", "exwm",
)

/** Desktops whose window managers stack windows, in lower case. */
private val STACKING_DESKTOPS = setOf(
    "gnome", "ubuntu", "unity", "kde", "plasma", "xfce", "x-cinnamon", "cinnamon", "mate", "budgie", "budgie-desktop",
    "pantheon", "lxqt", "lxde", "deepin", "dde", "cosmic", "gnome-flashback", "gnome-classic",
)

/** The desktop's names, from the variables that sessions set, in lower case: `ubuntu:GNOME` gives `ubuntu`, `gnome`. */
internal fun desktopNames(environment: Map<String, String>): List<String> =
    listOf("XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "DESKTOP_SESSION")
        .mapNotNull { environment[it] }
        .flatMap { it.split(':') }
        // DESKTOP_SESSION can be a path to the session file
        .map { it.substringAfterLast('/').trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()

fun linuxFrameSupport(system: LinuxSystem): LinuxFrameSupport {
    val reason = when {
        !system.canMoveWindows -> LinuxFrameSupport.Reason.NOT_JETBRAINS_RUNTIME
        system.toolkitClass !in LINUX_TOOLKITS -> LinuxFrameSupport.Reason.OTHER_TOOLKIT
        isWsl(system) -> LinuxFrameSupport.Reason.WSL
        else -> {
            val names = desktopNames(system.environment)

            when {
                // Before the stacking ones: Regolith calls itself "Regolith:GNOME", and runs i3
                names.any { it in TILING_WINDOW_MANAGERS } -> LinuxFrameSupport.Reason.TILING_WINDOW_MANAGER
                names.none { it in STACKING_DESKTOPS } -> LinuxFrameSupport.Reason.UNKNOWN_DESKTOP
                else -> null
            }
        }
    }

    return reason?.let { LinuxFrameSupport.Unsupported(it) } ?: LinuxFrameSupport.Supported
}

private fun isWsl(system: LinuxSystem): Boolean =
    "WSL_DISTRO_NAME" in system.environment ||
        "WSL_INTEROP" in system.environment ||
        system.procVersion?.contains("microsoft", ignoreCase = true) == true
