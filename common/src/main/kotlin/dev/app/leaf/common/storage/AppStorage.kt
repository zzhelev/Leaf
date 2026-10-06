// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.common.storage

import java.io.File
import java.util.jar.JarFile

/** Set by jpackage's launcher in packaged apps. `./gradlew :app:run` and `java -jar` don't set it. */
private const val PACKAGED_APP_PROPERTY = "jpackage.app-version"

/**
 * Manifest attribute of the Linux fat jar (`fatJarLinux`), which runs with `java -jar` instead of jpackage's launcher.
 * The module jars and class folders used by `./gradlew :app:run` and IDE runs don't have it.
 */
const val PACKAGED_JAR_ATTRIBUTE = "Leaf-Packaged"

/**
 * Names of everything Leaf stores outside a repository, so it never shares data with an installed Gitnuro.
 *
 * Dev runs get their own names, so they can't change the tabs or settings of an installed Leaf, or overwrite the native
 * library a running Leaf extracted.
 */
data class AppStorage(
    /** `java.util.prefs` node holding the tabs, recent repositories and pane widths. */
    val preferencesNode: String,
    /** Folder for the settings file and temp files, and for logs on Linux and Windows. */
    val directoryName: String,
    /** Folder in `~/Library/Logs` on macOS. */
    val macLogsDirectoryName: String,
) {
    companion object {
        const val LOG_FILE_NAME = "leaf.log"

        /** Per-repository settings file in the git dir, such as sign-off. */
        const val REPOSITORY_CONFIG_FILE_NAME = "leaf"

        val current: AppStorage by lazy {
            val loadedFrom = AppStorage::class.java.protectionDomain?.codeSource?.location
                ?.let { runCatching { File(it.toURI()) }.getOrNull() }
            forApp(isPackagedApp = System.getProperty(PACKAGED_APP_PROPERTY) != null || isPackagedJar(loadedFrom))
        }

        /** Whether [file] is a jar whose manifest marks it as a packaged Leaf, as the Linux fat jar is. */
        fun isPackagedJar(file: File?): Boolean {
            if (file == null || !file.isFile) return false
            return runCatching {
                JarFile(file).use { it.manifest?.mainAttributes?.getValue(PACKAGED_JAR_ATTRIBUTE) == "true" }
            }.getOrDefault(false)
        }

        fun forApp(isPackagedApp: Boolean): AppStorage = if (isPackagedApp) {
            AppStorage(
                preferencesNode = "LeafConfig",
                directoryName = "leaf",
                macLogsDirectoryName = "io.github.zzhelev.leaf",
            )
        } else {
            AppStorage(
                preferencesNode = "LeafDevConfig",
                directoryName = "leaf-dev",
                macLogsDirectoryName = "io.github.zzhelev.leaf-dev",
            )
        }
    }
}
