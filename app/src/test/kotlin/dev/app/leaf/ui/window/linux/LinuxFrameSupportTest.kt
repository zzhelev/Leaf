// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import dev.app.leaf.ui.window.linux.LinuxFrameSupport.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which Linux desktops get Leaf's own window frame (docs/fork/window-chrome.md, section 10). */
class LinuxFrameSupportTest {
    private fun support(
        environment: Map<String, String>,
        procVersion: String? = "Linux version 6.8.0-45-generic (buildd@lcy02-amd64-075) (gcc 13.2.0)",
        toolkitClass: String = "sun.awt.X11.XToolkit",
        canMoveWindows: Boolean = true,
    ) = linuxFrameSupport(LinuxSystem(environment, procVersion, toolkitClass, canMoveWindows))

    private fun unsupported(reason: Reason) = LinuxFrameSupport.Unsupported(reason)

    @Test
    fun `the stacking desktops are supported, as their sessions name themselves`() {
        for (desktop in listOf("ubuntu:GNOME", "GNOME", "KDE", "XFCE", "X-Cinnamon", "MATE", "Budgie:GNOME", "Pantheon", "LXQt", "Unity")) {
            assertEquals(LinuxFrameSupport.Supported, support(mapOf("XDG_CURRENT_DESKTOP" to desktop)), desktop)
        }

        // Without XDG_CURRENT_DESKTOP, the session's own name
        assertEquals(LinuxFrameSupport.Supported, support(mapOf("DESKTOP_SESSION" to "plasma")))
        assertEquals(LinuxFrameSupport.Supported, support(mapOf("DESKTOP_SESSION" to "/usr/share/xsessions/xfce")))
        // Wayland's toolkit too
        assertEquals(
            LinuxFrameSupport.Supported,
            support(mapOf("XDG_CURRENT_DESKTOP" to "GNOME"), toolkitClass = "sun.awt.wl.WLToolkit"),
        )
    }

    @Test
    fun `tiling window managers keep the system's frame, even where they also name a stacking desktop`() {
        for (desktop in listOf("i3", "sway", "Hyprland", "bspwm", "awesome", "Regolith:GNOME", "niri")) {
            assertEquals(unsupported(Reason.TILING_WINDOW_MANAGER), support(mapOf("XDG_CURRENT_DESKTOP" to desktop)), desktop)
        }

        assertEquals(
            unsupported(Reason.TILING_WINDOW_MANAGER),
            support(mapOf("XDG_CURRENT_DESKTOP" to "GNOME", "DESKTOP_SESSION" to "i3")),
        )
    }

    @Test
    fun `a desktop that names nothing Leaf knows, or none, keeps the system's frame`() {
        assertEquals(unsupported(Reason.UNKNOWN_DESKTOP), support(emptyMap()))
        assertEquals(unsupported(Reason.UNKNOWN_DESKTOP), support(mapOf("XDG_CURRENT_DESKTOP" to "")))
        assertEquals(unsupported(Reason.UNKNOWN_DESKTOP), support(mapOf("XDG_CURRENT_DESKTOP" to "Openbox")))
    }

    @Test
    fun `WSL keeps the system's frame, by its variables or its kernel`() {
        val gnome = mapOf("XDG_CURRENT_DESKTOP" to "GNOME")

        assertEquals(unsupported(Reason.WSL), support(gnome + ("WSL_DISTRO_NAME" to "Ubuntu")))
        assertEquals(unsupported(Reason.WSL), support(gnome + ("WSL_INTEROP" to "/run/WSL/1_interop")))
        assertEquals(
            unsupported(Reason.WSL),
            support(gnome, procVersion = "Linux version 5.15.153.1-microsoft-standard-WSL2"),
        )
    }

    @Test
    fun `a Java that can't move windows, or another toolkit, comes first`() {
        val gnome = mapOf("XDG_CURRENT_DESKTOP" to "GNOME")

        assertEquals(unsupported(Reason.NOT_JETBRAINS_RUNTIME), support(gnome, canMoveWindows = false))
        assertEquals(unsupported(Reason.NOT_JETBRAINS_RUNTIME), support(mapOf("XDG_CURRENT_DESKTOP" to "i3"), canMoveWindows = false))
        assertEquals(unsupported(Reason.OTHER_TOOLKIT), support(gnome, toolkitClass = "sun.awt.HeadlessToolkit"))
    }

    @Test
    fun `the desktop's names are split, lower case and without repeats`() {
        assertEquals(
            listOf("ubuntu", "gnome", "ubuntu-xorg"),
            desktopNames(
                mapOf(
                    "XDG_CURRENT_DESKTOP" to "ubuntu:GNOME",
                    "XDG_SESSION_DESKTOP" to "ubuntu",
                    "DESKTOP_SESSION" to "ubuntu-xorg",
                )
            ),
        )
    }
}
