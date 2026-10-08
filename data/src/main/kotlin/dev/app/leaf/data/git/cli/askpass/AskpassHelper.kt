// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cli.askpass

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.domain.TempFilesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AskpassHelper"

/** The helper's file name, which the build also uses (`askpassName` in `app/build.gradle.kts`). */
val ASKPASS_HELPER_NAME = if (currentOs == OS.WINDOWS) "leaf-askpass.exe" else "leaf-askpass"

private const val HASH_LENGTH = 12

/**
 * The `leaf-askpass` program (`rs/leaf-askpass.rs`), which the build puts in the app's resources next to the Rust
 * library. A program can't run from inside a jar, so it's copied to Leaf's temp folder the first time it's needed. The
 * folder is named after the program's hash, so that a running copy (which Windows can't replace) is never overwritten
 * by another version.
 */
@Singleton
class AskpassHelper(private val extract: () -> File?) {
    @Inject
    constructor(tempFilesManager: TempFilesManager) : this({ extractAskpassHelper(tempFilesManager.tempDir()) })

    private val mutex = Mutex()
    private var file: File? = null

    /** The helper to run, or null when this build doesn't have it (then the git CLI can't ask the user anything). */
    suspend fun path(): File? = mutex.withLock {
        // The temp folder is emptied when Leaf closes, but someone may also remove it while Leaf runs
        file?.takeIf { it.isFile } ?: withContext(Dispatchers.IO) { extract() }.also { file = it }
    }
}

private fun extractAskpassHelper(directory: File): File? {
    val bytes = AskpassHelper::class.java.getResourceAsStream("/$ASKPASS_HELPER_NAME")?.use { it.readBytes() }

    if (bytes == null) {
        printError(TAG, "$ASKPASS_HELPER_NAME isn't in the app's resources")
        return null
    }

    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val target = File(directory, "askpass-${hash.take(HASH_LENGTH)}/$ASKPASS_HELPER_NAME")

    if (target.isFile && target.length() == bytes.size.toLong()) {
        return target
    }

    return try {
        target.parentFile.mkdirs()

        // Written next to the target and then moved, so that no one runs a half-written program
        val temporary = File.createTempFile("leaf-askpass", ".tmp", target.parentFile)
        temporary.writeBytes(bytes)

        if (!temporary.setExecutable(true, true)) {
            throw IOException("Could not make $temporary executable")
        }

        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        target
    } catch (e: IOException) {
        printError(TAG, "Could not extract $ASKPASS_HELPER_NAME to $target", e)
        null
    }
}
