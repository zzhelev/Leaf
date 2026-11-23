// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editor

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/** The editor choice that opens files with the system's default app for their type. */
const val DEFAULT_APP_EDITOR = "default-app"

/** The editor choice that runs the user's own command ([EditorSettings.customCommand]). */
const val CUSTOM_EDITOR = "custom"

/**
 * How a file in a linked worktree opens when the editor doesn't have that worktree open (fork-only): in the editor's
 * window for the worktree, or alone. A file of the main worktree always opens with the main worktree.
 */
enum class LinkedWorktreeOpening(val storedName: String) {
    WORKTREE("worktree"),
    FILE_ALONE("file");

    companion object {
        val DEFAULT = WORKTREE

        /** The stored [name]'s value, or the default for one that this version doesn't know. */
        fun of(name: String?): LinkedWorktreeOpening = entries.firstOrNull { it.storedName == name } ?: DEFAULT
    }
}

/**
 * The settings of Settings › Editor (fork-only).
 * - [editor]: the id of a known editor, [DEFAULT_APP_EDITOR], [CUSTOM_EDITOR], or null when none was chosen yet.
 * - [customCommand]: the command of [CUSTOM_EDITOR], see [customEditorCommand].
 * - [repositoryEditors]: the editors chosen for one repository, by the real path of the git dir that its worktrees
 *   share, set from "Open in another editor…" in a file's menu.
 */
data class EditorSettings(
    val editor: String? = null,
    val customCommand: String = "",
    val linkedWorktree: LinkedWorktreeOpening = LinkedWorktreeOpening.DEFAULT,
    val repositoryEditors: Map<String, String> = emptyMap(),
) {
    /** The editor that opens the files of [repository] (a common git dir): its own, else the default. */
    fun editorFor(repository: String?): String? = repository?.let { repositoryEditors[it] } ?: editor
}

/** What "Open in …" does for a repository, worked out from its editor's id and the editors that Leaf found. */
sealed interface EditorChoice {
    /** An editor that Leaf found. */
    data class Installed(val editor: InstalledEditor) : EditorChoice

    /** A known editor that was chosen and isn't installed any more. */
    data class NotInstalled(val id: String, val name: String) : EditorChoice

    data object DefaultApp : EditorChoice

    data class Custom(val command: String) : EditorChoice

    /** Nothing chosen yet: the menu asks which editor. */
    data object None : EditorChoice
}

/** The choice that [id] stands for among [installed] editors. An id that this version doesn't know is [EditorChoice.None]. */
fun editorChoice(id: String?, installed: List<InstalledEditor>, customCommand: String): EditorChoice = when (id) {
    null -> EditorChoice.None
    DEFAULT_APP_EDITOR -> EditorChoice.DefaultApp
    CUSTOM_EDITOR -> EditorChoice.Custom(customCommand)
    else -> installed.firstOrNull { it.id == id }?.let(EditorChoice::Installed)
        ?: knownEditor(id)?.let { EditorChoice.NotInstalled(it.id, it.name) }
        ?: EditorChoice.None
}

/** The name that "Open in …" shows for [this] choice, or null when the menu says "Open in editor…" or uses the default app. */
val EditorChoice.editorName: String?
    get() = when (this) {
        is EditorChoice.Installed -> editor.name
        is EditorChoice.NotInstalled -> name
        else -> null
    }

/**
 * The folder that a file of the worktree [workTree] opens with: the worktree, unless it's a linked worktree and
 * [opening] says to open the file alone.
 */
fun folderToOpenWith(workTree: String, isLinkedWorktree: Boolean, opening: LinkedWorktreeOpening): String? =
    if (isLinkedWorktree && opening == LinkedWorktreeOpening.FILE_ALONE) null else workTree

/**
 * The name a repository goes by in Settings › Editor, from the git dir that its worktrees share: the main worktree's
 * folder, or a bare repository's folder without `.git`.
 */
fun repositoryNameOf(commonGitDir: String): String {
    val gitDir = File(commonGitDir)

    return if (gitDir.name == ".git") {
        gitDir.parentFile?.name ?: gitDir.name
    } else {
        gitDir.name.removeSuffix(".git")
    }
}

/** The editors chosen per repository, stored as a JSON object from the repository's common git dir to an editor id. */
object RepositoryEditorsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(editors: Map<String, String>): String = json.encodeToString(editors)

    /** An empty map for nothing, or for text that isn't such an object. */
    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()

        return try {
            json.decodeFromString<Map<String, String>>(text)
        } catch (ex: SerializationException) {
            emptyMap()
        } catch (ex: IllegalArgumentException) {
            emptyMap()
        }
    }
}
