// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.io.File
import java.nio.file.Files

/** How long JGit gets to save its measurement, which it does on a thread of its own. It takes milliseconds. */
private const val SAVE_TIMEOUT_MS = 5_000L

private val measured: Unit by lazy { measureFileStore() }

/**
 * Has JGit measure the file system's timestamps before any test runs, in a temporary folder of its own.
 *
 * JGit measures them once per JVM, the first time it takes a snapshot of a file on a file store that its config has no
 * measurement for. For 3 seconds, it writes a `.probe-<uuid>` file again and again in that file's folder, creating the
 * folder again when it's gone. Then it saves the result in the JGit config that is current at that moment. Tests point
 * that config at an empty file ([IsolatedSystemReader]), so the first test in each JVM that read a repository with
 * JGit had the probe in that repository's git dir. `CleaningUpWorktreesTest` opens a linked worktree, then has git
 * remove it, and git failed to delete its git dir ("Directory not empty") while the probe still wrote in it. And a
 * save that came after the test had put the developer's config back would have gone to `~/.config/jgit/config`.
 *
 * Registered in `META-INF/services`, so it runs once per test JVM, before the first test.
 */
class MeasureJGitFileStore : LauncherSessionListener {
    override fun launcherSessionOpened(session: LauncherSession) = measured
}

private fun measureFileStore() {
    val folder = Files.createTempDirectory("leaf-jgit-measurement").toFile()
    val previousReader = SystemReader.getInstance()
    val reader = IsolatedSystemReader(File(folder, "config"), previousReader)

    reader.jGitConfigFile.parentFile.mkdirs()
    SystemReader.setInstance(reader)

    try {
        // Measures in the folder, and returns once done. All the tests' folders are on this file store.
        val attributes = FS.getFileStoreAttributes(folder.toPath())

        if (attributes == FS.FileStoreAttributes.FALLBACK_FILESTORE_ATTRIBUTES) {
            System.err.println("JGit couldn't measure the timestamps in $folder; a test's repository may get the probe")
        } else if (!awaitSaved(reader.jGitConfigFile)) {
            System.err.println("JGit didn't save its measurement in ${SAVE_TIMEOUT_MS}ms; it may save it elsewhere")
        }
    } finally {
        SystemReader.setInstance(previousReader)
        FileUtils.delete(folder, FileUtils.RECURSIVE or FileUtils.SKIP_MISSING or FileUtils.IGNORE_ERRORS)
    }
}

/** Waits until JGit has saved a file store's measurement in [jGitConfigFile]. */
private fun awaitSaved(jGitConfigFile: File): Boolean {
    val deadline = System.currentTimeMillis() + SAVE_TIMEOUT_MS

    while (System.currentTimeMillis() < deadline) {
        if (jGitConfigFile.isFile && "[filesystem " in jGitConfigFile.readText()) {
            return true
        }

        Thread.sleep(10)
    }

    return false
}
