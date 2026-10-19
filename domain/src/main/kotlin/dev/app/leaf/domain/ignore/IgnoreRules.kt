// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.ignore

import dev.app.leaf.domain.errors.IgnoreError
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType

/** Where an ignore rule is written (fork-only). */
enum class IgnorePlace {
    /** The `.gitignore` at the root of the tab's working tree, which the repository shares once it's committed. */
    GITIGNORE,

    /** `info/exclude` in the common git dir: only on this computer, for every worktree of the repository. */
    EXCLUDE,

    /**
     * The user's global ignore file, `core.excludesFile` in their git config or else git's default
     * (`$XDG_CONFIG_HOME/git/ignore`): on this computer, for every repository. It is the one place outside the
     * repository, so the dialog names its path.
     */
    GLOBAL,
}

/** What the user asked to ignore from the Unstaged list (fork-only). */
sealed interface IgnoreRequest {
    /** Untracked files: the one whose menu was opened, or the selected ones. */
    data class Files(val paths: List<String>) : IgnoreRequest

    /** An untracked folder row of the tree view. [path] has no trailing slash. */
    data class Folder(val path: String) : IgnoreRequest
}

enum class IgnoreKind { FILES, FOLDERS, EXTENSIONS }

/**
 * One way to ignore what was asked for. [names] are what the dialog shows for it (`build/`, `*.log`) and [rules] the
 * lines that it writes. [unsupported] files were left out, as a line of a pattern file can't hold a line break.
 */
data class IgnoreOption(
    val kind: IgnoreKind,
    val names: List<String>,
    val rules: List<String>,
    val unsupported: Int = 0,
)

/** An ignore file as the dialog shows it: where it is, the lines it has, and why Leaf won't write to it, if so. */
data class IgnoreFile(val path: String, val lines: List<String>, val problem: IgnoreError? = null)

/** What the ignore dialog reads when it opens: the place used last, and each place's file. */
data class IgnoreContext(val remembered: IgnorePlace, val files: Map<IgnorePlace, IgnoreFile>)

/**
 * What "Ignore…" takes from [entries], the files that a menu acts on: the untracked ones, which are the new files of the
 * Unstaged pane. Ignoring a tracked file changes nothing until it's removed from the index. Null when there is none,
 * and for the Staged pane, whose files are in the index.
 */
fun ignoreRequestFor(entries: List<StatusEntry>, entryType: EntryType): IgnoreRequest.Files? {
    if (entryType != EntryType.UNSTAGED) return null

    return entries
        .filter { it.statusType == StatusType.ADDED }
        .map { it.filePath }
        .takeIf { it.isNotEmpty() }
        ?.let { IgnoreRequest.Files(it) }
}

/**
 * What "Ignore folder…" takes from a folder row of the Unstaged pane: the folder, when [entriesUnder] it, the entries
 * the row stands for, include an untracked file, which is what ignoring hides. Null otherwise.
 */
fun ignoreRequestForFolder(entriesUnder: List<StatusEntry>, entryType: EntryType, folderPath: String): IgnoreRequest.Folder? =
    IgnoreRequest.Folder(folderPath).takeIf {
        entryType == EntryType.UNSTAGED && entriesUnder.any { it.statusType == StatusType.ADDED }
    }

/**
 * The ways to ignore [request], each with the rules it writes: the files themselves, their folders and their
 * extensions. A way that gives no rule isn't offered.
 */
fun ignoreOptions(request: IgnoreRequest): List<IgnoreOption> = when (request) {
    is IgnoreRequest.Folder -> listOfNotNull(
        ignoreFolderRule(request.path)?.let { IgnoreOption(IgnoreKind.FOLDERS, listOf("${request.path}/"), listOf(it)) }
    )

    is IgnoreRequest.Files -> filesOptions(request.paths)
}

private fun filesOptions(paths: List<String>): List<IgnoreOption> {
    val representable = paths.filterNot { it.hasLineBreak() }
    val skipped = paths.size - representable.size

    val files = representable.distinct()
    val folders = outermost(files.mapNotNull { folderOf(it) }.distinct().sorted())
    val extensions = files.mapNotNull { extensionOf(it) }.distinct()

    return listOfNotNull(
        files.takeIf { it.isNotEmpty() }?.let {
            IgnoreOption(IgnoreKind.FILES, it, it.map(::ignoreFileRule), unsupported = skipped)
        },
        // The folders of representable files are representable too
        folders.takeIf { it.isNotEmpty() }?.let {
            IgnoreOption(IgnoreKind.FOLDERS, it.map { f -> "$f/" }, it.mapNotNull(::ignoreFolderRule))
        },
        extensions.takeIf { it.isNotEmpty() }?.let {
            IgnoreOption(IgnoreKind.EXTENSIONS, it.map { e -> "*.$e" }, it.map(::ignoreExtensionRule))
        },
    )
}

/** [sortedFolders] without those inside another one of them: ignoring the outer folder covers them. */
private fun outermost(sortedFolders: List<String>): List<String> {
    val kept = mutableListOf<String>()

    for (folder in sortedFolders) {
        if (kept.none { folder.startsWith("$it/") }) {
            kept += folder
        }
    }

    return kept
}

/** The rule that ignores exactly the file at [path] (relative to the working tree): `/build/out.log`. */
fun ignoreFileRule(path: String): String = "/" + escapeIgnorePath(path)

/** The rule that ignores the folder at [path], and what's in it: `/build/`. Null for a name with a line break. */
fun ignoreFolderRule(path: String): String? = if (path.hasLineBreak()) null else "/" + escapeIgnorePath(path) + "/"

/** The rule that ignores every file with [extension], at any depth: `*.log`. */
fun ignoreExtensionRule(extension: String): String = "*." + escapeIgnoreName(extension)

/** The folder that [path] is in, or null for a file at the root of the working tree. */
fun folderOf(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }

/** The extension of the file at [path]: after the last dot of its name, unless that dot starts the name or ends it. */
fun extensionOf(path: String): String? {
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')

    return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else null
}

/** The rules typed as a custom pattern, one per line, as typed: the user writes the syntax. Blank lines are left out. */
fun customRules(text: String): List<String> = text.lines().filter { it.isNotBlank() }

/** [rules] that [existing], the lines of the file, don't have yet, each once, in order. */
fun rulesToAdd(existing: List<String>, rules: List<String>): List<String> {
    val present = existing.toHashSet()

    return rules.distinct().filter { it !in present }
}

private fun String.hasLineBreak() = '\n' in this || '\r' in this

/** [path]'s names, each as [escapeIgnoreName] writes it. */
private fun escapeIgnorePath(path: String): String = path.split('/').joinToString("/") { escapeIgnoreName(it) }

/**
 * [name] as a pattern that matches exactly it: a backslash goes before each character that a pattern reads as more
 * than itself (`\ * ? [`), and before the white space that ends the name.
 *
 * git drops only trailing spaces from a line, unless a backslash is before them, but JGit, which reads the files for
 * Leaf's own status, drops tabs and other white space too, and does so at the end of a folder's name as well, where the
 * folder rule's slash follows. A backslash before a character always means that character, so every white space at the
 * end of a name is escaped for both.
 */
private fun escapeIgnoreName(name: String): String {
    val escaped = buildString(name.length) {
        for (character in name) {
            if (character == '\\' || character == '*' || character == '?' || character == '[') {
                append('\\')
            }

            append(character)
        }
    }

    val text = escaped.trimEnd { it.isWhitespace() }

    return text + escaped.substring(text.length).map { "\\$it" }.joinToString("")
}
