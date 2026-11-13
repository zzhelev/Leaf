// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.ContentSource
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.SystemReader
import org.eclipse.jgit.util.io.NullOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/**
 * Fork-only measurement behind section 3 of docs/fork/status-renames.md: what Leaf's rename detection (`withRenames`)
 * would cost at a status refresh, on the staged side (HEAD against the index) and the unstaged side (the index against
 * the working tree, untracked files included), in a clone of this repository with files moved in several ways.
 *
 * Not part of the build. To run it, copy it to `data/src/test/kotlin/dev/app/leaf/data/git/diff/` and run
 * `./gradlew :data:test --tests '*StatusRenamesCostProbeTest' --rerun`, with `LEAF_PROBE_OUT` set to a file for the
 * table (it's also printed to the test's output). Then delete the copy. Measure on a quiet machine.
 */
class StatusRenamesCostProbeTest {
    @TempDir
    lateinit var tempDir: File

    private val out = StringBuilder()

    private fun log(line: String) {
        println(line)
        out.appendLine(line)
    }

    @Test
    fun measure(): Unit = runBlocking {
        val original = SystemReader.getInstance()
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), original))
        try {
            run()
        } finally {
            SystemReader.setInstance(original)
            System.getenv("LEAF_PROBE_OUT")?.let { File(it).writeText(out.toString()) }
        }
    }

    private suspend fun run() {
        val git = TestGitCli(File(tempDir, "config/global.gitconfig"))
        val source = File("..").canonicalFile
        git.run(tempDir, "clone", "-q", source.path, "leaf")
        val repo = File(tempDir, "leaf")
        val ui = "app/src/main/kotlin/dev/app/leaf/ui"
        val uiFiles = File(repo, ui).walk().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        log("Clone of Leaf: ${git.run(repo, "ls-files").lines().count { it.isNotBlank() }} tracked files; $ui has ${uiFiles.size} .kt files")
        log("scenario | status ms | staged: candidates, ms, pairs | unstaged: candidates, ms, pairs")

        fun reset() {
            git.run(repo, "reset", "-q", "--hard", "HEAD")
            git.run(repo, "clean", "-qfdx")
        }

        suspend fun scenario(name: String, setup: () -> Unit) {
            reset()
            setup()
            Git.open(repo).use { g -> measure(name, g) }
        }

        scenario("A clean") {}
        scenario("B staged: 1 git mv") { git.run(repo, "mv", "$ui/toolbar/Toolbar.kt", "$ui/Toolbar.kt") }
        scenario("C staged: git mv of ${uiFiles.size} files") { git.run(repo, "mv", ui, "${ui}2") }
        scenario("D staged: C, package line edited") {
            git.run(repo, "mv", ui, "${ui}2")
            editPackages(File(repo, "${ui}2"))
            git.run(repo, "add", "-A")
        }
        scenario("E staged: 100 renamed and edited") { renameAndEdit(git, repo, uiFiles.take(100), stage = true) }
        scenario("F staged: 150 renamed and edited") { renameAndEdit(git, repo, uiFiles.take(150), stage = true) }
        scenario("G unstaged: mv of ${uiFiles.size} files outside git") {
            File(repo, ui).renameTo(File(repo, "${ui}2"))
        }
        scenario("H unstaged: G, package line edited") {
            File(repo, ui).renameTo(File(repo, "${ui}2"))
            editPackages(File(repo, "${ui}2"))
        }
        scenario("K unstaged: 100 renamed and edited outside git") { renameAndEdit(git, repo, uiFiles.take(100), stage = false) }
        scenario("I unstaged: 1 deleted, 2,000 untracked 100 KB files") {
            File(repo, "$ui/toolbar/Toolbar.kt").delete()
            val dir = File(repo, "out").apply { mkdirs() }
            val random = Random(1)
            repeat(2000) { i -> File(dir, "chunk$i.bin").writeBytes(random.nextBytes(100_000)) }
        }
        scenario("J unstaged: 1 deleted, 1 untracked 300 MB file") {
            File(repo, "$ui/toolbar/Toolbar.kt").delete()
            val random = Random(2)
            File(repo, "big.log").outputStream().use { o -> repeat(300) { o.write(random.nextBytes(1_000_000)) } }
        }
    }

    private fun editPackages(dir: File) {
        dir.walk().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            f.writeText(f.readText().replaceFirst("package dev.app.leaf.ui", "package dev.app.leaf.ui2"))
        }
    }

    private fun renameAndEdit(git: TestGitCli, repo: File, files: List<File>, stage: Boolean) {
        for (f in files) {
            val relative = f.relativeTo(repo).path
            val target = File(f.parentFile, f.nameWithoutExtension + "Renamed.kt")
            if (stage) git.run(repo, "mv", relative, target.relativeTo(repo).path) else f.renameTo(target)
            target.writeText(target.readText().replaceFirst("import ", "import kotlin.math.max\nimport "))
        }
        if (stage) git.run(repo, "add", "-A")
    }

    private suspend fun measure(name: String, git: Git) {
        val repo = git.repository
        val statusTimes = mutableListOf<Long>()
        val stagedTimes = mutableListOf<Long>()
        val unstagedTimes = mutableListOf<Long>()
        var stagedResult = "-"
        var unstagedResult = "-"

        repeat(6) { round ->
            var t = System.nanoTime()
            val status = git.status().call()
            val statusMs = (System.nanoTime() - t) / 1_000_000

            val stagedPaths = status.added + status.removed
            t = System.nanoTime()
            val staged = if (status.added.isNotEmpty() && status.removed.isNotEmpty()) {
                repo.withRenames(scan(repo, headTree(repo), DirCacheIterator(repo.readDirCache()), stagedPaths))
            } else {
                null
            }
            val stagedMs = (System.nanoTime() - t) / 1_000_000

            val unstagedPaths = status.missing + status.untracked
            t = System.nanoTime()
            val unstaged = if (status.missing.isNotEmpty() && status.untracked.isNotEmpty()) {
                repo.withRenames(
                    scan(repo, DirCacheIterator(repo.readDirCache()), FileTreeIterator(repo), unstagedPaths),
                    newSource = ContentSource.create(FileTreeIterator(repo)),
                )
            } else {
                null
            }
            val unstagedMs = (System.nanoTime() - t) / 1_000_000

            if (round > 0) {
                statusTimes += statusMs
                stagedTimes += stagedMs
                unstagedTimes += unstagedMs
            }
            stagedResult = staged?.let { "${stagedPaths.size}, ${pairs(it)} pairs" } ?: "${stagedPaths.size}, not run"
            unstagedResult = unstaged?.let { "${unstagedPaths.size}, ${pairs(it)} pairs" } ?: "${unstagedPaths.size}, not run"
        }

        log("$name | ${median(statusTimes)} | $stagedResult, ${median(stagedTimes)} ms | $unstagedResult, ${median(unstagedTimes)} ms")
    }

    private fun pairs(entries: List<DiffEntry>) = entries.count { it.changeType == DiffEntry.ChangeType.RENAME }

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    private fun scan(repo: Repository, a: org.eclipse.jgit.treewalk.AbstractTreeIterator, b: org.eclipse.jgit.treewalk.AbstractTreeIterator, paths: Set<String>): List<DiffEntry> =
        DiffFormatter(NullOutputStream.INSTANCE).use { f ->
            f.setRepository(repo)
            f.setDetectRenames(false)
            f.pathFilter = PathFilterGroup.createFromStrings(paths)
            f.scan(a, b)
        }

    private fun headTree(repo: Repository) = RevWalk(repo).use { walk ->
        CanonicalTreeParser().apply { reset(repo.newObjectReader(), walk.parseCommit(repo.resolve("HEAD")).tree) }
    }
}
