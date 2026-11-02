// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.tab

import dev.app.leaf.data.git.TestGitCli
import java.io.File

/**
 * The text that `git fast-import` reads: commits with parents, in the order of their times. It makes a repository of
 * thousands of commits in a moment, where `git commit` would take half a minute for them.
 */
class FastImportHistory {
    private val out = StringBuilder()
    private var mark = 0
    private var time = 1_700_000_000L
    val names = mutableMapOf<String, Int>()

    /** The marks of each branch's commits, oldest first. */
    val branches = mutableMapOf<String, MutableList<Int>>()

    fun stream() = out.toString()

    /**
     * Adds a commit to [branch] on top of [parents], or on top of the branch's last commit with none. The files are
     * the same in every commit but for a number, so a commit never has the changes of another.
     */
    fun commit(
        branch: String,
        message: String,
        vararg parents: Int,
        author: String = "Leaf Test",
        committer: String = author,
    ): Int {
        mark++
        time += 60

        val ref = "refs/heads/$branch"
        val actualParents = parents.toList().ifEmpty { listOfNotNull(branches[branch]?.lastOrNull()) }

        out.append("commit $ref\nmark :$mark\n")
        out.append("author $author <test@example.invalid> $time +0000\n")
        out.append("committer $committer <test@example.invalid> $time +0000\n")
        out.append("data ${message.toByteArray().size}\n$message\n")

        if (actualParents.isNotEmpty()) {
            out.append("from :${actualParents.first()}\n")
            actualParents.drop(1).forEach { out.append("merge :$it\n") }
        }

        out.append("M 644 inline file.txt\ndata ${mark.toString().length}\n$mark\n\n")
        branches.getOrPut(branch) { mutableListOf() }.add(mark)

        return mark
    }

    /** [count] commits in a row on [branch], whose messages are `<branch> 1`, `<branch> 2`, … ([message] changes them). */
    fun linear(branch: String, count: Int, message: (Int) -> String = { "$branch $it" }): List<Int> =
        List(count) { commit(branch, message(it + 1)) }
}

/** A repository made from a [FastImportHistory], and where its commits are. */
class ImportedHistory(private val history: FastImportHistory, private val hashByMark: Map<Int, String>) {
    /** The commit [depth] commits below the tip of [branch], which is the commit made with that many after it. */
    fun hash(branch: String, depth: Int): String =
        hashByMark.getValue(history.branches.getValue(branch)[history.branches.getValue(branch).size - 1 - depth])

    fun hashOf(name: String): String = hashByMark.getValue(history.names.getValue(name))
}

/** Makes [repository] from what [build] says, with `git fast-import`, with `main` checked out. */
fun importHistory(
    git: TestGitCli,
    repository: File,
    workFolder: File,
    build: FastImportHistory.() -> Unit,
): ImportedHistory {
    val history = FastImportHistory().apply(build)

    repository.mkdirs()
    git.run(repository, "init")
    val marks = File(workFolder, "marks")
    git.runWithInput(
        repository,
        history.stream(),
        "fast-import", "--quiet", "--force", "--export-marks=${marks.path}",
    )
    git.run(repository, "checkout", "-q", "-f", "main")

    val hashByMark = marks.readLines().associate { line ->
        val (mark, hash) = line.split(' ')
        mark.removePrefix(":").toInt() to hash
    }

    return ImportedHistory(history, hashByMark)
}
