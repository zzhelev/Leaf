// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.editor

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.editor.IExternalEditors
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.editor.KNOWN_EDITORS
import dev.app.leaf.domain.editor.KnownEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExternalEditors"

/**
 * The editors of [KNOWN_EDITORS] that are on this computer (fork-only), looked for once and kept until asked to look
 * again (Settings › Editor, the editor picker, and an editor that isn't found where it was).
 */
@Singleton
class ExternalEditors @Inject constructor(
    private val loginShellEnvironment: LoginShellEnvironment,
) : IExternalEditors {
    private val mutex = Mutex()
    private var found: List<InstalledEditor>? = null

    override suspend fun installed(refresh: Boolean): List<InstalledEditor> = mutex.withLock {
        found?.takeIf { !refresh }?.let { return it }

        val editors = withContext(Dispatchers.IO) {
            try {
                detect()
            } catch (e: Exception) {
                printError(TAG, "Could not look for editors", e)
                emptyList()
            }
        }

        found = editors
        editors
    }

    private suspend fun detect(): List<InstalledEditor> {
        val home = System.getProperty("user.home")
        val path = loginShellEnvironment.variables()["PATH"] ?: System.getenv("PATH").orEmpty()

        return when (currentOs) {
            OS.MAC -> macEditors(macAppFolders(home), ::bundleIdOf)
            OS.WINDOWS -> windowsEditors(System.getenv(), path)
            else -> posixEditors(path.split(File.pathSeparator) + linuxExtraFolders(home))
        }
    }
}

/** Where macOS apps are, the system's folder first. */
internal fun macAppFolders(home: String): List<File> = listOf(
    File("/Applications"),
    File(home, "Applications"),
    File("/Applications/Setapp"),
)

/**
 * The known editors among the apps in [appFolders] and in the folders in them (as `JetBrains Toolbox` once was), by
 * [bundleIdOf] each app. The first app of an editor wins, in the order of [appFolders].
 */
internal fun macEditors(appFolders: List<File>, bundleIdOf: (File) -> String?): List<InstalledEditor> {
    val apps = appFolders.flatMap { folder ->
        val entries = folder.listFiles()?.sortedBy { it.name }.orEmpty()

        entries.filter { it.isApp() } +
                entries.filter { it.isDirectory && !it.isApp() }
                    .flatMap { subfolder -> subfolder.listFiles()?.sortedBy { it.name }.orEmpty().filter { it.isApp() } }
    }

    val appsByEditor = mutableMapOf<String, File>()

    for (app in apps) {
        val bundleId = bundleIdOf(app) ?: continue
        val editor = KNOWN_EDITORS.firstOrNull { bundleId in it.macBundleIds } ?: continue

        appsByEditor.putIfAbsent(editor.id, app)
    }

    return KNOWN_EDITORS.mapNotNull { editor ->
        val app = appsByEditor[editor.id] ?: return@mapNotNull null
        val tool = editor.macTools.map { File(app, it) }.firstOrNull { it.isFile && it.canExecute() }

        InstalledEditor(editor.id, editor.name, editor.kind, tool = tool?.path, macApp = app.path, icon = iconFileOf(app))
    }
}

/** The app's icon file, which its `Info.plist` names in `Contents/Resources`, with or without `.icns`. */
internal fun iconFileOf(app: File): String? {
    val name = plistString(app, "CFBundleIconFile")?.takeIf { it.isNotBlank() } ?: return null
    val icon = File(app, "Contents/Resources/" + if (name.endsWith(".icns")) name else "$name.icns")

    return icon.path.takeIf { icon.isFile }
}

private fun File.isApp() = isDirectory && name.endsWith(".app")

/** The bundle id of [app]. Null when it can't be read. */
internal fun bundleIdOf(app: File): String? = plistString(app, "CFBundleIdentifier")

/**
 * The string [key] of [app]'s `Info.plist`: read as XML, or through `plutil` for a binary one, as Xcode's is. Null when
 * it can't be read.
 */
internal fun plistString(app: File, key: String): String? {
    val plist = File(app, "Contents/Info.plist")

    val bytes = try {
        plist.readBytes()
    } catch (e: IOException) {
        return null
    }

    return if (bytes.decodeToString(0, minOf(6, bytes.size)) == "bplist") {
        binaryPlistString(plist, key)
    } else {
        Regex("<key>${Regex.escape(key)}</key>\\s*<string>([^<]*)</string>")
            .find(bytes.decodeToString())
            ?.groupValues
            ?.get(1)
            ?.trim()
    }
}

private fun binaryPlistString(plist: File, key: String): String? = try {
    val process = ProcessBuilder("/usr/bin/plutil", "-extract", key, "raw", "-o", "-", plist.path)
        .redirectErrorStream(true)
        .start()
    process.outputStream.close()
    val output = process.inputStream.bufferedReader().readText().trim()

    if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) output.ifEmpty { null } else null
} catch (e: IOException) {
    null
}

/** Folders that hold editors' commands on Linux beyond the PATH: JetBrains Toolbox's scripts and snaps. */
internal fun linuxExtraFolders(home: String): List<String> = listOf(
    "$home/.local/share/JetBrains/Toolbox/scripts",
    "$home/.local/bin",
    "/snap/bin",
)

/** The known editors whose commands are executable files in [folders], the first folder that has one winning. */
internal fun posixEditors(folders: List<String>): List<InstalledEditor> {
    val directories = folders.filter { it.isNotBlank() }.map(::File)

    return KNOWN_EDITORS.mapNotNull { editor ->
        val tool = editor.commands.firstNotNullOfOrNull { command ->
            directories.map { File(it, command) }.firstOrNull { it.isFile && it.canExecute() }
        } ?: return@mapNotNull null

        installed(editor, tool.path)
    }
}

/**
 * The known editors on Windows: where their installers put them ([KnownEditor.windowsPaths], with the variables of
 * [environment] expanded), else their commands on [pathVariable] as `.cmd` or `.exe`.
 */
internal fun windowsEditors(environment: Map<String, String>, pathVariable: String): List<InstalledEditor> {
    val directories = pathVariable.split(';').filter { it.isNotBlank() }.map(::File)

    return KNOWN_EDITORS.mapNotNull { editor ->
        val installedAt = editor.windowsPaths
            .mapNotNull { expandWindowsVariables(it, environment) }
            .map(::File)
            .firstOrNull { it.isFile }

        val onPath = editor.commands.firstNotNullOfOrNull { command ->
            directories.flatMap { listOf(File(it, "$command.cmd"), File(it, "$command.exe")) }.firstOrNull { it.isFile }
        }

        val tool = installedAt ?: onPath ?: return@mapNotNull null

        installed(editor, tool.path)
    }
}

/** [path] with its `%NAME%` variables from [environment] (any case), or null when one isn't set. */
internal fun expandWindowsVariables(path: String, environment: Map<String, String>): String? {
    val variables = environment.mapKeys { it.key.uppercase() }
    var missing = false

    val expanded = Regex("%([^%]+)%").replace(path) { match ->
        variables[match.groupValues[1].uppercase()] ?: run {
            missing = true
            ""
        }
    }

    return expanded.takeIf { !missing }
}

private fun installed(editor: KnownEditor, tool: String) = InstalledEditor(editor.id, editor.name, editor.kind, tool)
