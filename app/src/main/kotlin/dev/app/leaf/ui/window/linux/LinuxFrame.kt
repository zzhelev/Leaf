// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import com.jetbrains.JBR
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printLog
import dev.app.leaf.ui.window.WindowTitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Toolkit
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "LinuxFrame"

/** How long the desktop's tool may take to say where its window buttons go. */
private const val BUTTON_LAYOUT_TIMEOUT_SECONDS = 2L

/**
 * Leaf's own window frame on Linux (fork-only, docs/fork/window-chrome.md, section 10), chosen in Settings → Appearance
 * ("Tabs in the title bar") and decided when Leaf starts, as a window's frame can't come or go while it's open: the
 * window is undecorated, [LinuxWindowFrame] draws its border and resizes it, and [titleBar] holds the tabs, moves the
 * window and draws its [buttons].
 */
class LinuxFrame(val buttons: WindowButtonLayout) {
    fun titleBar(window: Frame): WindowTitleBar = LinuxWindowTitleBar(window, buttons)

    companion object {
        /** Whether this run of Leaf draws its own frame, which Settings compares with the setting. */
        @Volatile
        var isDrawn: Boolean = false
            private set

        /** Whether this desktop allows Leaf's own frame. Read once: the desktop doesn't change while Leaf runs. */
        val support: LinuxFrameSupport by lazy { linuxFrameSupport(currentSystem()) }

        /** The frame, when [isOn] and this is a Linux desktop that allows it; null keeps the system's frame. */
        suspend fun atStart(isOn: Boolean): LinuxFrame? {
            if (currentOs != OS.LINUX || !isOn) return null

            return withContext(Dispatchers.IO) {
                when (val support = support) {
                    is LinuxFrameSupport.Unsupported -> {
                        printLog(TAG, "The system's frame stays: ${support.reason}")
                        null
                    }

                    LinuxFrameSupport.Supported -> {
                        val desktops = desktopNames(System.getenv())
                        val buttons = readWindowButtonLayout(desktops, ::outputOf)

                        printLog(TAG, "Leaf draws the window's frame on ${desktops.joinToString(":")}, buttons $buttons")
                        isDrawn = true
                        LinuxFrame(buttons)
                    }
                }
            }
        }
    }
}

private fun currentSystem() = LinuxSystem(
    environment = System.getenv(),
    procVersion = try {
        File("/proc/version").takeIf { it.isFile }?.readText()
    } catch (e: IOException) {
        null
    },
    toolkitClass = Toolkit.getDefaultToolkit().javaClass.name,
    canMoveWindows = try {
        JBR.isWindowMoveSupported()
    } catch (e: Throwable) { // Another Java has no JBR implementation behind the API
        false
    },
)

/** What [command] prints, trimmed, or null when it can't run, fails or takes too long. */
private fun outputOf(command: List<String>): String? = try {
    val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()

    process.outputStream.close()

    if (!process.waitFor(BUTTON_LAYOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        null
    } else if (process.exitValue() != 0) {
        null
    } else {
        process.inputStream.bufferedReader().readText().trim()
    }
} catch (e: IOException) { // Not installed
    null
}
