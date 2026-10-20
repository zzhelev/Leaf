// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * A working file that is out of the way while git writes in its place, and that goes back if git fails.
 *
 * `git restore` removes the file, or empties it, before it has the version it writes: a partial clone that can't
 * download the blob left it empty, and a Git LFS filter that failed left it deleted, whatever `filter.lfs.required`
 * says. Moving the file aside keeps it whole, with its content, mode and link target, and costs a rename where the
 * folder is on the same file system.
 */
class WorkingFileBackup private constructor(
    private val original: Path,
    /** Where the original waits, or null when nothing was there. */
    private val aside: Path?,
) {
    /** Puts the original back, over whatever git left there. Returns where it still is when that fails, else null. */
    fun putBack(): String? {
        try {
            // A partial file, or a file where there was none; JGit's delete doesn't follow links
            if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                FileUtils.delete(original.toFile(), FileUtils.RECURSIVE or FileUtils.SKIP_MISSING)
            }

            if (aside != null) {
                Files.move(aside, original)
            }
        } catch (e: IOException) {
            return aside?.toString()
        }

        removeFolder()

        return null
    }

    /** The restore worked: the original isn't needed any more. */
    fun discard() {
        removeFolder()
    }

    private fun removeFolder() {
        val folder = aside?.parent ?: return

        FileUtils.delete(folder.toFile(), FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.IGNORE_ERRORS)
        // The folder of all of them, when it's the last one
        try {
            Files.deleteIfExists(folder.parent)
        } catch (_: IOException) {
        }
    }

    companion object {
        /**
         * Moves [original], a file or a link, into a new folder in [folder]. Nothing happens when nothing is there.
         * A folder isn't moved: the caller refuses it first.
         */
        fun moveAside(original: File, folder: File): WorkingFileBackup {
            val path = original.toPath()

            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return WorkingFileBackup(path, null)
            }

            Files.createDirectories(folder.toPath())
            val own = Files.createTempDirectory(folder.toPath(), "restore-")
            val aside = own.resolve(original.name)

            try {
                Files.move(path, aside)
            } catch (e: IOException) {
                FileUtils.delete(own.toFile(), FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.IGNORE_ERRORS)
                throw e
            }

            return WorkingFileBackup(path, aside)
        }
    }
}
