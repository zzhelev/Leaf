// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editor

/** How an editor takes a file, a line and a folder on its command line (see [editorCommand]). */
enum class EditorKind {
    /** VS Code and the editors built on it: `--goto file:line`, after a folder to open the file in. */
    VS_CODE,

    /** IntelliJ IDEA and the other JetBrains IDEs: `[project] --line n file`, or `-e file` for LightEdit. */
    JETBRAINS,

    /** Zed: `file:line`, after a folder to open the file in. */
    ZED,

    /** Sublime Text: `file:line`. */
    SUBLIME,

    /** Xcode's `xed`: `--line n file`. */
    XCODE,

    /** TextMate's `mate`: `-l n file`. */
    TEXTMATE,

    /** Anything else: the file alone, without a line. */
    OTHER,
}

/**
 * An editor that Leaf knows how to find and start (fork-only).
 * - [macBundleIds]: the bundle ids of its macOS app, which Leaf looks for in the folders that hold apps.
 * - [macTools]: its command-line tool inside the app bundle, the first that exists. Without one, Leaf opens the file
 *   with `open -a`, which takes no line. JetBrains IDEs start through `open -na <app> --args`, as their own scripts do.
 * - [commands]: names to look for on the PATH (Linux, and Windows, where `.cmd` and `.exe` are tried too).
 * - [windowsPaths]: where its installer puts it on Windows, with `%LOCALAPPDATA%` and `%PROGRAMFILES%` expanded.
 */
data class KnownEditor(
    val id: String,
    val name: String,
    val kind: EditorKind,
    val macBundleIds: List<String> = emptyList(),
    val macTools: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val windowsPaths: List<String> = emptyList(),
)

private const val VS_CODE_BIN = "Contents/Resources/app/bin"
private const val JETBRAINS_TOOLBOX = "%LOCALAPPDATA%\\JetBrains\\Toolbox\\scripts"

private fun jetBrains(id: String, name: String, bundleIds: List<String>, command: String) = KnownEditor(
    id = id,
    name = name,
    kind = EditorKind.JETBRAINS,
    macBundleIds = bundleIds,
    commands = listOf(command),
    windowsPaths = listOf("$JETBRAINS_TOOLBOX\\$command.cmd"),
)

/**
 * The editors that Leaf finds by itself, in the order its lists show them. The ids are stored in the settings, so they
 * never change.
 */
val KNOWN_EDITORS: List<KnownEditor> = listOf(
    KnownEditor(
        id = "vscode",
        name = "Visual Studio Code",
        kind = EditorKind.VS_CODE,
        macBundleIds = listOf("com.microsoft.VSCode"),
        macTools = listOf("$VS_CODE_BIN/code"),
        commands = listOf("code"),
        windowsPaths = listOf(
            "%LOCALAPPDATA%\\Programs\\Microsoft VS Code\\bin\\code.cmd",
            "%PROGRAMFILES%\\Microsoft VS Code\\bin\\code.cmd",
        ),
    ),
    KnownEditor(
        id = "vscode-insiders",
        name = "Visual Studio Code - Insiders",
        kind = EditorKind.VS_CODE,
        macBundleIds = listOf("com.microsoft.VSCodeInsiders"),
        macTools = listOf("$VS_CODE_BIN/code-insiders"),
        commands = listOf("code-insiders"),
        windowsPaths = listOf("%LOCALAPPDATA%\\Programs\\Microsoft VS Code Insiders\\bin\\code-insiders.cmd"),
    ),
    KnownEditor(
        id = "vscodium",
        name = "VSCodium",
        kind = EditorKind.VS_CODE,
        macBundleIds = listOf("com.vscodium"),
        macTools = listOf("$VS_CODE_BIN/codium"),
        commands = listOf("codium"),
        windowsPaths = listOf("%LOCALAPPDATA%\\Programs\\VSCodium\\bin\\codium.cmd"),
    ),
    KnownEditor(
        id = "cursor",
        name = "Cursor",
        kind = EditorKind.VS_CODE,
        macBundleIds = listOf("com.todesktop.230313mzl4w4u92"),
        macTools = listOf("$VS_CODE_BIN/cursor"),
        commands = listOf("cursor"),
        windowsPaths = listOf("%LOCALAPPDATA%\\Programs\\cursor\\resources\\app\\bin\\cursor.cmd"),
    ),
    KnownEditor(
        id = "windsurf",
        name = "Windsurf",
        kind = EditorKind.VS_CODE,
        macBundleIds = listOf("com.exafunction.windsurf"),
        macTools = listOf("$VS_CODE_BIN/windsurf"),
        commands = listOf("windsurf"),
        windowsPaths = listOf("%LOCALAPPDATA%\\Programs\\Windsurf\\bin\\windsurf.cmd"),
    ),
    KnownEditor(
        id = "zed",
        name = "Zed",
        kind = EditorKind.ZED,
        macBundleIds = listOf("dev.zed.Zed"),
        macTools = listOf("Contents/MacOS/cli"),
        // Some Linux distributions name it zeditor
        commands = listOf("zed", "zeditor"),
    ),
    KnownEditor(
        id = "zed-preview",
        name = "Zed Preview",
        kind = EditorKind.ZED,
        macBundleIds = listOf("dev.zed.Zed-Preview"),
        macTools = listOf("Contents/MacOS/cli"),
    ),
    KnownEditor(
        id = "sublime-text",
        name = "Sublime Text",
        kind = EditorKind.SUBLIME,
        macBundleIds = listOf("com.sublimetext.4", "com.sublimetext.3"),
        macTools = listOf("Contents/SharedSupport/bin/subl"),
        commands = listOf("subl"),
        windowsPaths = listOf("%PROGRAMFILES%\\Sublime Text\\subl.exe"),
    ),
    jetBrains("intellij-idea", "IntelliJ IDEA", listOf("com.jetbrains.intellij"), "idea"),
    jetBrains("intellij-idea-ce", "IntelliJ IDEA CE", listOf("com.jetbrains.intellij.ce"), "idea-ce"),
    jetBrains("android-studio", "Android Studio", listOf("com.google.android.studio"), "studio"),
    jetBrains("pycharm", "PyCharm", listOf("com.jetbrains.pycharm"), "pycharm"),
    jetBrains("pycharm-ce", "PyCharm CE", listOf("com.jetbrains.pycharm.ce"), "pycharm-ce"),
    jetBrains("webstorm", "WebStorm", listOf("com.jetbrains.WebStorm"), "webstorm"),
    jetBrains("goland", "GoLand", listOf("com.jetbrains.goland"), "goland"),
    jetBrains("phpstorm", "PhpStorm", listOf("com.jetbrains.PhpStorm"), "phpstorm"),
    jetBrains("rubymine", "RubyMine", listOf("com.jetbrains.rubymine"), "rubymine"),
    jetBrains("clion", "CLion", listOf("com.jetbrains.CLion"), "clion"),
    jetBrains("rider", "Rider", listOf("com.jetbrains.rider"), "rider"),
    jetBrains("rustrover", "RustRover", listOf("com.jetbrains.rustrover"), "rustrover"),
    KnownEditor(
        id = "xcode",
        name = "Xcode",
        kind = EditorKind.XCODE,
        macBundleIds = listOf("com.apple.dt.Xcode"),
        macTools = listOf("Contents/Developer/usr/bin/xed"),
    ),
    KnownEditor(
        id = "textmate",
        name = "TextMate",
        kind = EditorKind.TEXTMATE,
        macBundleIds = listOf("com.macromates.TextMate"),
        macTools = listOf("Contents/MacOS/mate"),
    ),
    KnownEditor(id = "bbedit", name = "BBEdit", kind = EditorKind.OTHER, macBundleIds = listOf("com.barebones.bbedit")),
    KnownEditor(id = "nova", name = "Nova", kind = EditorKind.OTHER, macBundleIds = listOf("com.panic.Nova")),
    KnownEditor(id = "kate", name = "Kate", kind = EditorKind.OTHER, commands = listOf("kate")),
    KnownEditor(id = "gedit", name = "gedit", kind = EditorKind.OTHER, commands = listOf("gedit")),
    KnownEditor(
        id = "notepad-plus-plus",
        name = "Notepad++",
        kind = EditorKind.OTHER,
        windowsPaths = listOf("%PROGRAMFILES%\\Notepad++\\notepad++.exe"),
    ),
)

/** The known editor with [id], or null for an id that this version doesn't know. */
fun knownEditor(id: String): KnownEditor? = KNOWN_EDITORS.firstOrNull { it.id == id }
