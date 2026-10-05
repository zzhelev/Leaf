package dev.app.leaf.common.storage

/** Set by jpackage's launcher in packaged apps. `./gradlew :app:run` and `java -jar` don't set it. */
private const val PACKAGED_APP_PROPERTY = "jpackage.app-version"

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
            forApp(isPackagedApp = System.getProperty(PACKAGED_APP_PROPERTY) != null)
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
