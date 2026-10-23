// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.rewrite.autosquashTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The commit that a `fixup!`, `squash!` or `amend!` subject names (fork-only), against git: `git rebase -i --autosquash`
 * puts each such commit under the one it names, and its todo list, read before the rebase starts, says which.
 */
@DisabledOnOs(OS.WINDOWS)
class AutosquashTargetTest {
    @TempDir
    lateinit var root: File

    private val git by lazy { TestGitCli(File(root, "global.gitconfig")) }

    @Test
    fun `each commit names the commit that git's autosquash puts it under`() {
        val work = File(root, "work")
        File(root, "global.gitconfig").writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        git.run(root, "init", "-q", "-b", "main", work.path)
        var files = 0
        val commit = { message: String ->
            File(work, "f${++files}").writeText("$files\n")
            git.run(work, "add", "-A")
            git.run(work, "commit", "-q", "-m", message)
            git.run(work, "rev-parse", "HEAD").trim()
        }

        commit("Base")
        git.run(work, "switch", "-q", "-c", "feat")
        val parser = commit("Add the parser")
        val cache = commit("Cache the parsed files")
        val messages = listOf(
            "fixup! Add the parser",
            "squash! Cache the parsed",
            "amend! fixup! Cache the parsed files\n\nBetter words.",
            "fixup! ${parser.take(7)}",
            "fixup! Base",
            "Add the parser",
            "fixup! Add the parser",
            "Handle the empty file",
            "Test the cache",
            "fixup! Handle the\nempty file",
            "Add",
            "fixup! Add",
        )
        messages.forEach { commit(it) }

        val commits = git.run(work, "log", "--reverse", "--format=%H%x00%B%x1e", "main..feat")
            .split('\u001e').map { it.trim('\n') }.filter { it.isNotEmpty() }
            .map { entry -> entry.split('\u0000').let { (hash, message) -> commitOf(hash, message) } }
        val subjects = commits.associate { it.hash to it.message.lineSequence().first() }
        val leaf = commits.mapIndexed { index, it -> it.hash to autosquashTarget(it, commits.take(index))?.hash }.toMap()

        assertEquals(autosquashTargetsOfGit(work), leaf)
        assertEquals(
            mapOf(
                "fixup! Add the parser" to "Add the parser",
                "squash! Cache the parsed" to "Cache the parsed files",
                "amend! fixup! Cache the parsed files" to "Cache the parsed files",
                "fixup! ${parser.take(7)}" to "Add the parser",
                "fixup! Handle the" to "Handle the empty file",
                "fixup! Add" to "Add",
            ),
            leaf.filterValues { it != null }.entries.associate { (fix, named) -> subjects[fix] to subjects[named] },
        )
        // The second "fixup! Add the parser" names the oldest commit with that subject, not the one right below it
        assertEquals(listOf("Add the parser", "fixup! Add the parser"), commits.slice(7..8).map { subjects[it.hash] })
        assertEquals(parser, leaf[commits[8].hash])
        assertEquals(cache, leaf[commits[3].hash])
    }

    /** The commit that git's todo list puts each of the branch's commits under, or null for one that it picks. */
    private fun autosquashTargetsOfGit(work: File): Map<String, String?> {
        val todo = File(root, "todo.txt")
        val editor = File(root, "sequence-editor.sh").apply {
            writeText("#!/bin/sh\ncp \"$1\" '${todo.path}'\nexit 1\n")
            setExecutable(true)
        }

        git.runFailing(work, "-c", "sequence.editor=${editor.path}", "rebase", "-i", "--autosquash", "main")

        val full = git.run(work, "rev-list", "main..feat").lines().filter { it.isNotBlank() }
        var picked: String? = null

        return todo.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val words = line.split(' ')
            val short = if (words[1].startsWith("-")) words[2] else words[1]
            val hash = full.single { it.startsWith(short) }

            if (words[0] == "pick") {
                picked = hash
                hash to null
            } else {
                hash to picked
            }
        }
    }

    private fun commitOf(hash: String, message: String): Commit {
        val someone = Identity("Leaf Test", "test@example.invalid")

        return Commit(hash, message, someone, someone, 0, emptyList())
    }
}
