// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.TestGitCli
import java.io.File

/** main's version of notes.txt, and feature's: both changed its only line. */
const val MAIN_NOTES = "main's line\n"
const val FEATURE_NOTES = "feature's line\n"

/**
 * Commits on main and on a new branch, feature, that conflict in each way, then merges feature into main in
 * [workTree], which stops at:
 * - notes.txt: both changed it;
 * - kept.txt: main changed it, feature deleted it (ours is a file, theirs is deleted);
 * - dropped.txt: main deleted it, feature changed it;
 * - added.txt: both added it.
 *
 * [workTree] may be a linked worktree. Its branch stands for main.
 */
fun TestGitCli.mergeWithConflicts(workTree: File) {
    val main = run(workTree, "branch", "--show-current").trim()
    write(workTree, "notes.txt" to "base line\n", "kept.txt" to "base\n", "dropped.txt" to "base\n")
    run(workTree, "add", ".")
    run(workTree, "commit", "-m", "Base")

    run(workTree, "branch", "feature")

    write(workTree, "notes.txt" to MAIN_NOTES, "kept.txt" to "main's kept\n", "added.txt" to "main's added\n")
    File(workTree, "dropped.txt").delete()
    run(workTree, "add", "-A")
    run(workTree, "commit", "-m", "Main")

    run(workTree, "switch", "feature")
    write(workTree, "notes.txt" to FEATURE_NOTES, "dropped.txt" to "feature's dropped\n", "added.txt" to "feature's added\n")
    File(workTree, "kept.txt").delete()
    run(workTree, "add", "-A")
    run(workTree, "commit", "-m", "Feature")

    run(workTree, "switch", main)
    runFailing(workTree, "merge", "feature")
}

/** Commits files on [workTree]'s branch and on feature that conflict in each of [names], then merges feature. */
fun TestGitCli.mergeWithConflictsIn(workTree: File, vararg names: String) {
    val main = run(workTree, "branch", "--show-current").trim()
    write(workTree, *names.map { it to "base\n" }.toTypedArray())
    run(workTree, "add", ".")
    run(workTree, "commit", "-m", "Base")
    run(workTree, "branch", "feature")

    write(workTree, *names.map { it to "main\n" }.toTypedArray())
    run(workTree, "commit", "-am", "Main")

    run(workTree, "switch", "feature")
    write(workTree, *names.map { it to "feature\n" }.toTypedArray())
    run(workTree, "commit", "-am", "Feature")

    run(workTree, "switch", main)
    runFailing(workTree, "merge", "feature")
}

private fun write(workTree: File, vararg files: Pair<String, String>) {
    for ((name, content) in files) {
        File(workTree, name).apply { parentFile.mkdirs() }.writeText(content)
    }
}

/** The stages that the index has for [path]: 0 once resolved, some of 1 to 3 while it has conflicts. */
fun TestGitCli.stages(workTree: File, path: String): List<Int> =
    run(workTree, "--literal-pathspecs", "ls-files", "--stage", "--", path)
        .lines()
        .filter { it.isNotBlank() }
        .map { it.substringBefore('\t').split(' ')[2].toInt() }

/** What the index has for [path], resolved (stage 0). */
fun TestGitCli.staged(workTree: File, path: String): String = run(workTree, "show", ":$path")

/** The files that git doesn't track in [workTree], as `git status` lists them. */
fun TestGitCli.untracked(workTree: File): List<String> =
    run(workTree, "ls-files", "--others", "--exclude-standard").lines().filter { it.isNotBlank() }
