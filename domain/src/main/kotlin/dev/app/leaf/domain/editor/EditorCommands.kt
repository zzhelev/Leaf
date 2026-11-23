// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editor

/**
 * An editor that Leaf found on this computer (fork-only): [tool] is the program to run, its command-line tool, and
 * [macApp] the app bundle on macOS, which JetBrains IDEs and editors without a tool start through `open`. At least one
 * of them is set. [icon] is the macOS app's icon file (`.icns`), which the editor picker shows.
 */
data class InstalledEditor(
    val id: String,
    val name: String,
    val kind: EditorKind,
    val tool: String?,
    val macApp: String? = null,
    val icon: String? = null,
)

/**
 * What to open: the absolute path of [file], at [line] (from 1) when there is one, in the editor's window for [folder]
 * when there is one: the worktree that the file is in. Without a folder, the editor opens the file alone.
 */
data class EditorTarget(
    val file: String,
    val line: Int? = null,
    val folder: String? = null,
)

/**
 * The command line that opens [target] in [editor], or null when there is nothing to run. [isMac] says whether to
 * start apps through `open`.
 *
 * - VS Code and the editors built on it take `--goto file:line`. Given the worktree's folder first, they open the file
 *   in the window that has the folder, or in a new window with it.
 * - JetBrains IDEs take `[project] --line n file`, and open the file in the project's window. Without a folder they
 *   open it in LightEdit (`-e`), their window for one file, which takes no line. On macOS they start through
 *   `open -na <app> --args`, as the scripts of JetBrains Toolbox do: a running IDE takes the arguments.
 * - Zed takes `file:line`, after the folder.
 * - Sublime Text takes `file:line`, Xcode's `xed` `--line n`, TextMate's `mate` `-l n`.
 * - Any other editor gets the file alone, through `open -a` on macOS when it has no tool.
 */
fun editorCommand(editor: InstalledEditor, target: EditorTarget, isMac: Boolean): List<String>? {
    val file = target.file
    val line = target.line?.coerceAtLeast(1)
    val withLine = if (line != null) "$file:$line" else file

    fun openWithApp(): List<String>? = editor.macApp?.let { listOf("open", "-a", it, file) }

    return when (editor.kind) {
        EditorKind.JETBRAINS -> {
            val arguments = if (target.folder != null) {
                listOf(target.folder) + (line?.let { listOf("--line", it.toString()) } ?: emptyList()) + file
            } else {
                listOf("-e", file)
            }

            when {
                isMac && editor.macApp != null -> listOf("open", "-na", editor.macApp, "--args") + arguments
                editor.tool != null -> listOf(editor.tool) + arguments
                else -> null
            }
        }

        EditorKind.VS_CODE -> editor.tool?.let { tool ->
            listOfNotNull(tool, target.folder) + if (line != null) listOf("--goto", withLine) else listOf(file)
        } ?: openWithApp()

        EditorKind.ZED -> editor.tool?.let { tool -> listOfNotNull(tool, target.folder, withLine) } ?: openWithApp()

        EditorKind.SUBLIME -> editor.tool?.let { tool -> listOf(tool, withLine) } ?: openWithApp()

        EditorKind.XCODE -> editor.tool?.let { tool ->
            listOf(tool) + (line?.let { listOf("--line", it.toString()) } ?: emptyList()) + file
        } ?: openWithApp()

        EditorKind.TEXTMATE -> editor.tool?.let { tool ->
            listOf(tool) + (line?.let { listOf("-l", it.toString()) } ?: emptyList()) + file
        } ?: openWithApp()

        EditorKind.OTHER -> if (isMac && editor.macApp != null) openWithApp() else editor.tool?.let { listOf(it, file) }
    }
}

/** The placeholders of a custom command: the file's absolute path, its line (1 without one) and its worktree. */
const val FILE_PLACEHOLDER = "{file}"
const val LINE_PLACEHOLDER = "{line}"
const val FOLDER_PLACEHOLDER = "{folder}"

/**
 * The command line of the user's own [template], or null when it has no program. It's split as a shell splits words
 * (spaces outside quotes, `'…'`, `"…"` and `\`), and each word's placeholders are replaced, so a path with spaces stays
 * one argument. A template without `{file}` gets the file as its last argument. `{folder}` is the worktree, also when
 * the file opens alone.
 */
fun customEditorCommand(template: String, target: EditorTarget, worktree: String): List<String>? {
    val words = splitCommandLine(template)

    if (words.isEmpty()) return null

    val line = (target.line ?: 1).coerceAtLeast(1).toString()
    val replaced = words.map { word ->
        word.replace(FILE_PLACEHOLDER, target.file)
            .replace(LINE_PLACEHOLDER, line)
            .replace(FOLDER_PLACEHOLDER, worktree)
    }

    return if (words.any { it.contains(FILE_PLACEHOLDER) }) replaced else replaced + target.file
}

/**
 * [commandLine]'s words, as a POSIX shell splits them: white space separates them unless it's quoted, single quotes
 * keep everything as it is, double quotes keep everything but `\"` and `\\`, and a backslash outside quotes keeps the
 * character after it. An unclosed quote runs to the end.
 */
fun splitCommandLine(commandLine: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var inWord = false
    var index = 0

    while (index < commandLine.length) {
        val char = commandLine[index]

        when {
            char.isWhitespace() -> {
                if (inWord) {
                    words.add(word.toString())
                    word.clear()
                    inWord = false
                }
            }

            char == '\'' -> {
                inWord = true
                val end = commandLine.indexOf('\'', index + 1).let { if (it < 0) commandLine.length else it }
                word.append(commandLine, index + 1, end)
                index = end
            }

            char == '"' -> {
                inWord = true
                index++
                while (index < commandLine.length && commandLine[index] != '"') {
                    val inner = commandLine[index]
                    if (inner == '\\' && index + 1 < commandLine.length && commandLine[index + 1] in "\"\\") {
                        word.append(commandLine[index + 1])
                        index++
                    } else {
                        word.append(inner)
                    }
                    index++
                }
            }

            char == '\\' && index + 1 < commandLine.length -> {
                inWord = true
                word.append(commandLine[index + 1])
                index++
            }

            else -> {
                inWord = true
                word.append(char)
            }
        }

        index++
    }

    if (inWord) words.add(word.toString())

    return words
}
