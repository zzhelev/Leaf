// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * How Leaf finds Git for Windows and its Git Bash, and builds the command line for a hook. Pure, so it runs on every
 * system.
 */
class GitBashTest {
    @Test
    fun `finds the Git for Windows install of each of its folders that can be on PATH`() {
        val path = listOf(
            "C:\\Windows\\system32",
            "C:\\Program Files\\Git\\cmd",
            "D:\\PortableGit\\mingw64\\bin\\",
            "\"E:\\Git Tools\\usr\\bin\"",
            "F:\\Git\\BIN",
            "G:\\Git\\ucrt64\\bin",
        ).joinToString(";")

        val installs = gitForWindowsInstalls(path) { null }

        assertEquals(
            listOf("C:\\Program Files\\Git", "D:\\PortableGit", "E:\\Git Tools", "F:\\Git", "G:\\Git"),
            installs,
        )
    }

    @Test
    fun `then searches the default install folders, without duplicates`() {
        val environment = mapOf(
            "ProgramFiles" to "C:\\Program Files",
            "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
        )

        val installs = gitForWindowsInstalls("C:\\Program Files\\Git\\cmd") { environment[it] }

        assertEquals(
            listOf("C:\\Program Files\\Git", "C:\\Users\\me\\AppData\\Local\\Programs\\Git"),
            installs,
        )
    }

    @Test
    fun `ignores PATH entries outside a Git for Windows install`() {
        assertEquals(emptyList<String>(), gitForWindowsInstalls("C:\\Windows;;C:\\cmdtools;\\cmd") { null })
        assertEquals(emptyList<String>(), gitForWindowsInstalls(null) { null })
    }

    @Test
    fun `uses the first install with Git Bash`() {
        val installed = "C:\\Program Files\\Git\\bin\\bash.exe"
        val path = "D:\\MinGit\\cmd;C:\\Program Files\\Git\\cmd"

        assertEquals("C:\\Program Files\\Git", findGitForWindows(path, { null }, isFile = { it == installed }))
        assertEquals(installed, GitBash.find(path, { null }, isFile = { it == installed })?.executable)
    }

    @Test
    fun `finds nothing without Git for Windows`() {
        assertNull(findGitForWindows("C:\\Program Files\\Git\\cmd", { null }, isFile = { false }))
        assertNull(GitBash.find("C:\\Program Files\\Git\\cmd", { null }, isFile = { false }))
    }

    @Test
    fun `runs the hook with forward slashes and quotes the arguments for MSYS2`() {
        val gitBash = GitBash("C:\\Program Files\\Git\\bin\\bash.exe")

        val command = gitBash.hookCommand(
            "C:\\Users\\O'Brien\\repo\\.git\\hooks\\pre-push",
            listOf("origin", "C:\\Remote Repos\\repo.git"),
        )

        assertEquals(
            listOf(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "\"C:/Users/O'Brien/repo/.git/hooks/pre-push\"",
                "origin",
                "\"C:\\\\Remote Repos\\\\repo.git\"",
            ),
            command,
        )
    }

    @Test
    fun `leaves arguments that MSYS2 takes literally as they are`() {
        for (argument in listOf("origin", ".git/COMMIT_EDITMSG", "C:/repo/.git/hooks/commit-msg", "git@host:a/b.git")) {
            assertEquals(argument, quoteForMsys(argument))
        }
    }

    @Test
    fun `quotes arguments the way Git for Windows does for MSYS2`() {
        val expected = mapOf(
            "" to "\"\"",
            "C:/Users/John Doe/repo" to "\"C:/Users/John Doe/repo\"",
            "C:/Users/O'Brien" to "\"C:/Users/O'Brien\"",
            "C:\\repos\\bare.git" to "\"C:\\\\repos\\\\bare.git\"",
            "say \"hi\"" to "\"say \\\"hi\\\"\"",
            "*.txt" to "\"*.txt\"",
            "what?" to "\"what?\"",
            "{a,b}" to "\"{a,b}\"",
            "~/repo" to "\"~/repo\"",
            "tab\there" to "\"tab\there\"",
        )

        assertEquals(expected, expected.mapValues { (argument, _) -> quoteForMsys(argument) })
    }
}
