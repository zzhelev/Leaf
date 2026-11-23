// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editor

import dev.app.leaf.domain.errors.EditorError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val LEAF = "/Users/me/leaf/.git"
private const val WEBSITE = "/Users/me/website/.git"

/** Which editor a repository's files open in, and how (fork-only). */
class EditorSettingsTest {
    private val vsCode = InstalledEditor("vscode", "Visual Studio Code", EditorKind.VS_CODE, tool = "/bin/code")
    private val target = EditorTarget("/Users/me/leaf/a.kt", line = 4, folder = "/Users/me/leaf")

    @Test
    fun `a repository's own editor comes before the default`() {
        val settings = EditorSettings(editor = "intellij-idea", repositoryEditors = mapOf(WEBSITE to "vscode"))

        assertEquals("vscode", settings.editorFor(WEBSITE))
        assertEquals("intellij-idea", settings.editorFor(LEAF))
        assertEquals("intellij-idea", settings.editorFor(null))
        assertNull(EditorSettings().editorFor(LEAF))
    }

    @Test
    fun `an id is an installed editor, one that isn't installed, the default app, a command, or nothing`() {
        assertEquals(EditorChoice.Installed(vsCode), editorChoice("vscode", listOf(vsCode), ""))
        assertEquals(EditorChoice.NotInstalled("zed", "Zed"), editorChoice("zed", listOf(vsCode), ""))
        assertEquals(EditorChoice.DefaultApp, editorChoice(DEFAULT_APP_EDITOR, emptyList(), ""))
        assertEquals(EditorChoice.Custom("ed {file}"), editorChoice(CUSTOM_EDITOR, emptyList(), "ed {file}"))
        assertEquals(EditorChoice.None, editorChoice(null, listOf(vsCode), ""))
        assertEquals(EditorChoice.None, editorChoice("an-editor-of-a-later-leaf", listOf(vsCode), ""))
    }

    @Test
    fun `only an editor has a name for the menu`() {
        assertEquals("Visual Studio Code", EditorChoice.Installed(vsCode).editorName)
        assertEquals("Zed", EditorChoice.NotInstalled("zed", "Zed").editorName)
        assertNull(EditorChoice.DefaultApp.editorName)
        assertNull(EditorChoice.Custom("x").editorName)
        assertNull(EditorChoice.None.editorName)
    }

    @Test
    fun `each choice opens its own way`() {
        assertEquals(
            EditorOpening.Run(listOf("/bin/code", "/Users/me/leaf", "--goto", "/Users/me/leaf/a.kt:4")),
            editorOpening(EditorChoice.Installed(vsCode), target, "/Users/me/leaf", isMac = true),
        )
        assertEquals(
            EditorOpening.Refused(EditorError.NotInstalled("Zed")),
            editorOpening(EditorChoice.NotInstalled("zed", "Zed"), target, "/Users/me/leaf", isMac = true),
        )
        assertEquals(
            EditorOpening.Refused(EditorError.NoCustomCommand),
            editorOpening(EditorChoice.Custom(""), target, "/Users/me/leaf", isMac = true),
        )
        assertEquals(
            EditorOpening.Run(listOf("ed", "/Users/me/leaf/a.kt")),
            editorOpening(EditorChoice.Custom("ed"), target, "/Users/me/leaf", isMac = true),
        )
        assertEquals(
            EditorOpening.WithDefaultApp("/Users/me/leaf/a.kt"),
            editorOpening(EditorChoice.DefaultApp, target, "/Users/me/leaf", isMac = true),
        )
    }

    @Test
    fun `an editor that can't run is refused as not installed`() {
        val withoutTool = vsCode.copy(tool = null)

        assertEquals(
            EditorOpening.Refused(EditorError.NotInstalled("Visual Studio Code")),
            editorOpening(EditorChoice.Installed(withoutTool), target, "/Users/me/leaf", isMac = true),
        )
    }

    @Test
    fun `a linked worktree's file opens alone only when the setting says so`() {
        assertEquals("/wt", folderToOpenWith("/wt", isLinkedWorktree = true, LinkedWorktreeOpening.WORKTREE))
        assertNull(folderToOpenWith("/wt", isLinkedWorktree = true, LinkedWorktreeOpening.FILE_ALONE))
        assertEquals("/main", folderToOpenWith("/main", isLinkedWorktree = false, LinkedWorktreeOpening.FILE_ALONE))
    }

    @Test
    fun `the stored names of how a linked worktree's file opens`() {
        assertEquals(LinkedWorktreeOpening.FILE_ALONE, LinkedWorktreeOpening.of("file"))
        assertEquals(LinkedWorktreeOpening.WORKTREE, LinkedWorktreeOpening.of("worktree"))
        assertEquals(LinkedWorktreeOpening.WORKTREE, LinkedWorktreeOpening.of(null))
        assertEquals(LinkedWorktreeOpening.WORKTREE, LinkedWorktreeOpening.of("a later one"))
    }

    @Test
    fun `a repository goes by its main worktree's folder, or a bare one's without git`() {
        assertEquals("leaf", repositoryNameOf("/Users/me/leaf/.git"))
        assertEquals("mirror", repositoryNameOf("/srv/mirror.git"))
        assertEquals("mirror", repositoryNameOf("/srv/mirror"))
    }

    @Test
    fun `the repositories' editors survive a round trip, and text that isn't them reads as none`() {
        val editors = mapOf(LEAF to "intellij-idea", "/a \"quoted\" path/.git" to "vscode")

        assertEquals(editors, RepositoryEditorsCodec.decode(RepositoryEditorsCodec.encode(editors)))
        assertEquals(emptyMap<String, String>(), RepositoryEditorsCodec.decode(null))
        assertEquals(emptyMap<String, String>(), RepositoryEditorsCodec.decode("[1, 2]"))
        assertEquals(emptyMap<String, String>(), RepositoryEditorsCodec.decode("{not json"))
    }
}
