// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** How [WindowsFs] finds Git Bash and builds the command line for a hook. Pure, so it runs on every system. */
class GitBashTest {
    @Test
    fun `finds Git Bash in each Git for Windows folder that can be on PATH`() {
        val path = listOf(
            "C:\\Windows\\system32",
            "C:\\Program Files\\Git\\cmd",
            "D:\\PortableGit\\mingw64\\bin\\",
            "\"E:\\Git Tools\\usr\\bin\"",
            "F:\\Git\\BIN",
        ).joinToString(";")

        val candidates = gitBashCandidates(path) { null }

        assertEquals(
            listOf(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "D:\\PortableGit\\bin\\bash.exe",
                "E:\\Git Tools\\bin\\bash.exe",
                "F:\\Git\\bin\\bash.exe",
            ),
            candidates,
        )
    }

    @Test
    fun `then searches the default install folders, without duplicates`() {
        val environment = mapOf(
            "ProgramFiles" to "C:\\Program Files",
            "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
        )

        val candidates = gitBashCandidates("C:\\Program Files\\Git\\cmd") { environment[it] }

        assertEquals(
            listOf(
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Users\\me\\AppData\\Local\\Programs\\Git\\bin\\bash.exe",
            ),
            candidates,
        )
    }

    @Test
    fun `ignores PATH entries outside a Git for Windows install`() {
        assertEquals(emptyList<String>(), gitBashCandidates("C:\\Windows;;C:\\cmdtools;\\cmd") { null })
        assertEquals(emptyList<String>(), gitBashCandidates(null) { null })
    }

    @Test
    fun `uses the first candidate that exists`() {
        val installed = "C:\\Program Files\\Git\\bin\\bash.exe"
        val path = "D:\\OldGit\\cmd;C:\\Program Files\\Git\\cmd"

        val gitBash = GitBash.find(path, { null }, isFile = { it == installed })

        assertEquals(installed, gitBash?.executable)
    }

    @Test
    fun `finds nothing without Git for Windows`() {
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
