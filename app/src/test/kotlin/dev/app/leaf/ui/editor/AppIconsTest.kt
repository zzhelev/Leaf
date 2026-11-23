// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** The editors' app icons in the picker (fork-only): a PNG out of a macOS icon file. */
class AppIconsTest {
    /** The start of a PNG [width] pixels wide: its signature and the IHDR chunk with the width. */
    private fun png(width: Int): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            writeInt(13)
            writeBytes("IHDR")
            writeInt(width)
            writeInt(width)
            write(ByteArray(5))
        }
    }.toByteArray()

    private fun icns(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeBytes("icns")
            writeInt(8 + entries.sumOf { 8 + it.second.size })
            for ((type, data) in entries) {
                writeBytes(type)
                writeInt(8 + data.size)
                write(data)
            }
        }
    }.toByteArray()

    @Test
    fun `the smallest PNG of at least 32 pixels is taken, past the images that aren't PNG`() {
        val small = png(64)
        val file = icns("is32" to ByteArray(40), "ic10" to png(1024), "ic12" to small, "ic07" to png(128))

        assertArrayEquals(small, pngOfIcns(file))
    }

    @Test
    fun `with none that large, the largest is taken`() {
        val largest = png(16)

        assertArrayEquals(largest, pngOfIcns(icns("icp4" to png(8), "icp5" to largest)))
    }

    @Test
    fun `a file that isn't an icon file, or has no PNG, or is cut short, gives none`() {
        assertNull(pngOfIcns("not an icon".toByteArray()))
        assertNull(pngOfIcns(icns("is32" to ByteArray(40))))
        assertNull(pngOfIcns(icns("ic07" to png(128)).copyOf(30)))
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `a real app's icon decodes`() {
        val icon = File("/Applications/Xcode.app/Contents/Resources/Xcode.icns")
        assumeTrue(icon.isFile, "needs Xcode")

        val bitmap = appIcon(icon.path)

        assertEquals(32, bitmap?.width, "ic11, the 16-point icon at 2x")
    }
}
