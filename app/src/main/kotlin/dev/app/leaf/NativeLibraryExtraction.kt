// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private const val HASH_LENGTH = 16

/**
 * Copies the Rust library ([bytes], from the app's resources) to `<directory>/leaf_rs-<hash>/<fileName>` and returns
 * that file, for `System.load`.
 *
 * Another Leaf may have loaded a copy from the same temp folder: a dev run from another worktree, whose cargo build
 * embeds its own path, or another version. Rewriting a file that a process has mapped leaves the kernel's code
 * signature for it stale, and macOS kills the next process that loads it (and the running one, at its next page-in).
 * So the folder is named after the library's hash, an existing file is used only when it has the same bytes, and a new
 * one is written next to it and renamed into place, a new file rather than new bytes in an old one. The file keeps its
 * name, as Windows matches a loaded DLL by its file name when JNA loads `leaf_rs`.
 */
internal fun extractNativeLibrary(
    bytes: ByteArray,
    directory: File,
    fileName: String,
    move: (source: Path, target: Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    },
): File {
    val hash = sha256(bytes)
    val hex = hash.joinToString("") { "%02x".format(it) }
    val target = File(directory, "leaf_rs-${hex.take(HASH_LENGTH)}/$fileName")

    if (target.hasContent(bytes.size, hash)) {
        return target
    }

    target.parentFile.mkdirs()
    val temporary = File.createTempFile("leaf_rs", ".tmp", target.parentFile)

    try {
        temporary.writeBytes(bytes)
        move(temporary.toPath(), target.toPath())
    } catch (e: IOException) {
        // Another Leaf put it there meanwhile, and Windows can't replace a DLL that a process has loaded
        if (!target.hasContent(bytes.size, hash)) {
            throw e
        }
    } finally {
        temporary.delete()
    }

    return target
}

private fun File.hasContent(size: Int, hash: ByteArray): Boolean {
    return isFile && length() == size.toLong() && sha256(readBytes()).contentEquals(hash)
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
