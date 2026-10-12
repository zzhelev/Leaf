// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.TestGitCli
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import java.io.File
import java.nio.file.Files

/**
 * Lets [write] set up [workTree] for each side, `base`, `main` and `feature`, commits each (feature on a branch of its
 * own, from the base), then merges feature into [workTree]'s branch, which stops at conflicts.
 */
fun TestGitCli.mergeSides(workTree: File, write: (side: String) -> Unit) {
    val main = run(workTree, "branch", "--show-current").trim()

    write("base")
    run(workTree, "add", "-A")
    run(workTree, "commit", "-m", "Base")
    run(workTree, "branch", "feature")

    write("main")
    run(workTree, "add", "-A")
    run(workTree, "commit", "-m", "Main")

    run(workTree, "switch", "feature")
    write("feature")
    run(workTree, "add", "-A")
    run(workTree, "commit", "-m", "Feature")

    run(workTree, "switch", main)
    runFailing(workTree, "merge", "feature")
}

/** Writes each of [files] as its content for the side, then merges, as [mergeSides]. */
fun TestGitCli.mergeContents(workTree: File, vararg files: Pair<String, (side: String) -> String>) =
    mergeSides(workTree) { side ->
        for ((name, content) in files) {
            File(workTree, name).apply { parentFile.mkdirs() }.writeText(content(side))
        }
    }

/** Makes [name] a symbolic link to `<side>-target` on each side, then merges, as [mergeSides]. */
fun TestGitCli.mergeLinks(workTree: File, name: String) = mergeSides(workTree) { side ->
    val link = File(workTree, name).toPath()

    Files.deleteIfExists(link)
    Files.createSymbolicLink(link, File("$side-target").toPath())
}

/** Writes a conflict at [path] straight into [workTree]'s index: [entries]' modes and ids, at stages 1, 2 and 3. */
fun writeConflictStages(workTree: File, path: String, vararg entries: Pair<FileMode, String>?) {
    Git.open(workTree).use { git ->
        val index = git.repository.lockDirCache()
        val builder = index.builder()

        for (i in 0 until index.entryCount) {
            builder.add(index.getEntry(i))
        }

        entries.forEachIndexed { stage, entry ->
            if (entry != null) {
                builder.add(
                    DirCacheEntry(path, stage + 1).apply {
                        fileMode = entry.first
                        setObjectId(ObjectId.fromString(entry.second))
                    }
                )
            }
        }

        builder.commit()
    }
}
