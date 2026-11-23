// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private const val FILE = "/Users/me/leaf/app/src/main/kotlin/Diff Pane.kt"
private const val WORKTREE = "/Users/me/leaf"

/** The command lines that open a file in each kind of editor (fork-only), as their own command-line tools take them. */
class EditorCommandsTest {
    private val vsCode = InstalledEditor(
        "vscode", "Visual Studio Code", EditorKind.VS_CODE,
        tool = "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code",
        macApp = "/Applications/Visual Studio Code.app",
    )
    private val intellij = InstalledEditor(
        "intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS,
        tool = null,
        macApp = "/Users/me/Applications/IntelliJ IDEA.app",
    )
    private val intellijOnLinux = InstalledEditor(
        "intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS,
        tool = "/home/me/.local/share/JetBrains/Toolbox/scripts/idea",
    )
    private val zed = InstalledEditor("zed", "Zed", EditorKind.ZED, tool = "/Applications/Zed.app/Contents/MacOS/cli")
    private val sublime = InstalledEditor("sublime-text", "Sublime Text", EditorKind.SUBLIME, tool = "/bin/subl")
    private val xcode = InstalledEditor("xcode", "Xcode", EditorKind.XCODE, tool = "/x/xed", macApp = "/Applications/Xcode.app")
    private val textMate = InstalledEditor("textmate", "TextMate", EditorKind.TEXTMATE, tool = "/t/mate")
    private val nova = InstalledEditor("nova", "Nova", EditorKind.OTHER, tool = null, macApp = "/Applications/Nova.app")
    private val kate = InstalledEditor("kate", "Kate", EditorKind.OTHER, tool = "/usr/bin/kate")

    private fun command(editor: InstalledEditor, line: Int? = null, folder: String? = null, isMac: Boolean = true) =
        editorCommand(editor, EditorTarget(FILE, line, folder), isMac)

    @Test
    fun `VS Code goes to the line, in the worktree's window when given its folder`() {
        assertEquals(listOf(vsCode.tool, WORKTREE, "--goto", "$FILE:42"), command(vsCode, 42, WORKTREE))
        assertEquals(listOf(vsCode.tool, "--goto", "$FILE:42"), command(vsCode, 42))
        assertEquals(listOf(vsCode.tool, WORKTREE, FILE), command(vsCode, folder = WORKTREE), "no line: no --goto")
    }

    @Test
    fun `an editor without its tool opens the file through its app, without a line`() {
        assertEquals(
            listOf("open", "-a", vsCode.macApp, FILE),
            command(vsCode.copy(tool = null), 42, WORKTREE),
        )
        assertNull(command(vsCode.copy(tool = null, macApp = null), 42), "nothing to run")
    }

    @Test
    fun `JetBrains IDEs start through open on macOS, with the project before the line and the file`() {
        assertEquals(
            listOf("open", "-na", intellij.macApp, "--args", WORKTREE, "--line", "42", FILE),
            command(intellij, 42, WORKTREE),
        )
        assertEquals(
            listOf("open", "-na", intellij.macApp, "--args", WORKTREE, FILE),
            command(intellij, folder = WORKTREE),
        )
    }

    @Test
    fun `a JetBrains IDE opens a file alone in LightEdit, which takes no line`() {
        assertEquals(listOf("open", "-na", intellij.macApp, "--args", "-e", FILE), command(intellij, 42))
        assertEquals(listOf(intellijOnLinux.tool, "-e", FILE), command(intellijOnLinux, 42, isMac = false))
    }

    @Test
    fun `a JetBrains IDE elsewhere runs its script`() {
        assertEquals(
            listOf(intellijOnLinux.tool, WORKTREE, "--line", "7", FILE),
            command(intellijOnLinux, 7, WORKTREE, isMac = false),
        )
        assertNull(command(intellij, 7, WORKTREE, isMac = false), "a macOS app on another system")
    }

    @Test
    fun `Zed takes the folder and file colon line`() {
        assertEquals(listOf(zed.tool, WORKTREE, "$FILE:42"), command(zed, 42, WORKTREE))
        assertEquals(listOf(zed.tool, FILE), command(zed))
    }

    @Test
    fun `Sublime Text, Xcode and TextMate take the line their own way, and no folder`() {
        assertEquals(listOf(sublime.tool, "$FILE:3"), command(sublime, 3, WORKTREE))
        assertEquals(listOf(xcode.tool, "--line", "3", FILE), command(xcode, 3, WORKTREE))
        assertEquals(listOf(xcode.tool, FILE), command(xcode))
        assertEquals(listOf(textMate.tool, "-l", "3", FILE), command(textMate, 3))
    }

    @Test
    fun `any other editor gets the file alone`() {
        assertEquals(listOf("open", "-a", nova.macApp, FILE), command(nova, 3, WORKTREE))
        assertEquals(listOf(kate.tool, FILE), command(kate, 3, WORKTREE, isMac = false))
    }

    @Test
    fun `a line below 1 is line 1`() {
        assertEquals(listOf(zed.tool, "$FILE:1"), command(zed, 0))
    }

    @Test
    fun `a custom command's placeholders are replaced inside its words, and a path with spaces stays one word`() {
        val target = EditorTarget(FILE, line = 12, folder = null)

        assertEquals(
            listOf("my-editor", "--line=12", FILE, "--project", WORKTREE),
            customEditorCommand("my-editor --line={line} {file} --project {folder}", target, WORKTREE),
        )
    }

    @Test
    fun `a custom command without the file gets it last, and without a line has line 1`() {
        assertEquals(
            listOf("emacsclient", "-n", "+1", FILE),
            customEditorCommand("emacsclient -n +{line}", EditorTarget(FILE), WORKTREE),
        )
        assertNull(customEditorCommand("   ", EditorTarget(FILE), WORKTREE))
    }

    @Test
    fun `command lines split as a shell splits them`() {
        assertEquals(listOf("a", "b c", "d"), splitCommandLine("a 'b c' d"))
        assertEquals(listOf("a", "b \"c\" \\ d"), splitCommandLine("a \"b \\\"c\\\" \\\\ d\""))
        assertEquals(listOf("/Applications/My Editor.app/x", "--x"), splitCommandLine("/Applications/My\\ Editor.app/x  --x"))
        assertEquals(listOf("ab", "c"), splitCommandLine("a'b' \"c\""))
        assertEquals(listOf(""), splitCommandLine("''"), "an empty quoted word is a word")
        assertEquals(listOf("open"), splitCommandLine("'open"), "an unclosed quote runs to the end")
        assertEquals(emptyList<String>(), splitCommandLine(" \t "))
    }

    @Test
    fun `every known editor has its own id, and a way to be found`() {
        assertEquals(KNOWN_EDITORS.size, KNOWN_EDITORS.map { it.id }.toSet().size)

        for (editor in KNOWN_EDITORS) {
            val found = editor.macBundleIds.isNotEmpty() || editor.commands.isNotEmpty() || editor.windowsPaths.isNotEmpty()
            assertEquals(true, found, editor.id)
        }
    }
}
