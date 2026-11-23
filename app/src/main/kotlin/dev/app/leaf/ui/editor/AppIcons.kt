// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.open_in_new
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.skia.Image
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

/** A 16 dp icon is 32 pixels on a 2x screen. */
private const val ICON_PIXELS = 32

/**
 * The PNG image of a macOS icon file (`.icns`) that is [minSize] pixels wide or the least above it, else the largest
 * (fork-only). An icon file is a list of images, each a 4-byte type and a 4-byte length; macOS keeps the larger sizes
 * as PNG, and the PNG's own header says how wide it is. Null for a file that isn't an icon file or has no PNG.
 */
internal fun pngOfIcns(bytes: ByteArray, minSize: Int = ICON_PIXELS): ByteArray? {
    if (bytes.size < 8 || bytes.decodeToString(0, 4) != "icns") return null

    val pngs = mutableListOf<Pair<Int, ByteArray>>()
    var index = 8

    while (index + 8 <= bytes.size) {
        val length = bytes.intAt(index + 4)
        if (length < 8 || index + length > bytes.size) break

        val start = index + 8
        if (length - 8 >= 24 && PNG_SIGNATURE.indices.all { bytes[start + it] == PNG_SIGNATURE[it] }) {
            pngs += bytes.intAt(start + 16) to bytes.copyOfRange(start, index + length)
        }

        index += length
    }

    return (pngs.filter { it.first >= minSize }.minByOrNull { it.first } ?: pngs.maxByOrNull { it.first })?.second
}

private fun ByteArray.intAt(index: Int): Int =
    (this[index].toInt() and 0xff shl 24) or (this[index + 1].toInt() and 0xff shl 16) or
            (this[index + 2].toInt() and 0xff shl 8) or (this[index + 3].toInt() and 0xff)

private val appIcons = ConcurrentHashMap<String, ImageBitmap>()
private val missingIcons = ConcurrentHashMap.newKeySet<String>()

/** The image of the icon file at [path], kept once read; null when it can't be read. Reads the disk. */
internal fun appIcon(path: String): ImageBitmap? {
    appIcons[path]?.let { return it }
    if (path in missingIcons) return null

    val bitmap = try {
        pngOfIcns(File(path).readBytes())?.let { Image.makeFromEncoded(it).toComposeImageBitmap() }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    if (bitmap != null) appIcons[path] = bitmap else missingIcons.add(path)

    return bitmap
}

/** The image already read for [path], without reading the disk. */
internal fun cachedAppIcon(path: String): ImageBitmap? = appIcons[path]

/**
 * An app's own icon, from its icon file [iconFile] (read off the UI thread), or else, and while it's read, the box with
 * an arrow that stands for opening something outside Leaf, in [tint].
 */
@Composable
fun AppIconOrOpenIcon(iconFile: String?, tint: Color, modifier: Modifier) {
    val icon by produceState(iconFile?.let(::cachedAppIcon), iconFile) {
        value = iconFile?.let { withContext(Dispatchers.IO) { appIcon(it) } }
    }

    val loaded = icon

    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = null, modifier = modifier)
    } else {
        Icon(painter = painterResource(Res.drawable.open_in_new), contentDescription = null, tint = tint, modifier = modifier)
    }
}
