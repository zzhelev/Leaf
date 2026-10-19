// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.errors.IgnoreError
import dev.app.leaf.domain.ignore.IgnoreFile
import dev.app.leaf.domain.ignore.IgnorePlace
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import javax.inject.Inject

/** The file that rules of one [IgnorePlace] go to, and why Leaf won't write to it, if so. */
data class IgnoreTarget(val file: File, val problem: IgnoreError?, val followLinks: Boolean = false)

/** Finds the file of each [IgnorePlace] (fork-only). */
class IgnoreTargets @Inject constructor(private val globalIgnoreFile: GlobalIgnoreFile) {
    suspend fun resolve(repository: Repository, place: IgnorePlace): IgnoreTarget = when (place) {
        IgnorePlace.GLOBAL -> globalIgnoreFile.locate(repository)
        else -> IgnoreFiles.fileOf(repository, place).let { IgnoreTarget(it, IgnoreFiles.problemOf(it)) }
    }
}

/**
 * The files that ignore rules are written to (fork-only), and how: rules are appended as bytes, so what the file has
 * already, in any encoding and with any line endings, stays as it is.
 */
internal object IgnoreFiles {
    /**
     * The file of a place of the repository: the `.gitignore` at the root of the tab's working tree (`workTree` is the
     * linked worktree's own folder, never the main one), or `info/exclude` in the common git dir, which the worktrees
     * share. The global list is found by [GlobalIgnoreFile].
     */
    fun fileOf(repository: Repository, place: IgnorePlace): File = when (place) {
        IgnorePlace.GITIGNORE -> File(repository.workTree, ".gitignore")
        IgnorePlace.EXCLUDE -> File(File(repository.commonDirectory ?: repository.directory, "info"), "exclude")
        IgnorePlace.GLOBAL -> error("The global list isn't a file of the repository")
    }

    /**
     * Why Leaf won't write to [file]: it's a symbolic link, whose target could be anywhere (git 2.32 and later ignores
     * a symbolic `.gitignore` as well), or something other than a file. A file that doesn't exist is created.
     *
     * With [followLinks], for the global list that the user's own config names, a link to a file is fine, as dotfile
     * managers make them, but not one to nothing or to a folder.
     */
    fun problemOf(file: File, followLinks: Boolean = false): IgnoreError? {
        val path = file.toPath()
        val options = if (followLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

        return when {
            !followLinks && Files.isSymbolicLink(path) ->
                IgnoreError.FileNotWritable(file.path, IgnoreError.Reason.SYMBOLIC_LINK)

            followLinks && Files.isSymbolicLink(path) && !Files.exists(path) ->
                IgnoreError.FileNotWritable(file.path, IgnoreError.Reason.NOT_A_FILE)

            Files.exists(path, *options) && !Files.isRegularFile(path, *options) ->
                IgnoreError.FileNotWritable(file.path, IgnoreError.Reason.NOT_A_FILE)

            else -> null
        }
    }

    /** [target] as the dialog shows it: its path, its lines, and the problem with writing to it. */
    fun read(target: IgnoreTarget): IgnoreFile {
        val bytes = if (target.problem == null && target.file.isFile) target.file.readBytes() else ByteArray(0)

        return IgnoreFile(target.file.path, linesOf(bytes), target.problem)
    }

    /**
     * Appends the [rules] that [file] doesn't have as lines, each once. A line counts as present when it equals the
     * rule without its `\r`, byte for byte. The lines get the file's line ending (its first one, `\n` for a file
     * without any), and a last line without one gets it first. Writes nothing when every rule is there.
     */
    fun append(target: IgnoreTarget, rules: List<String>) {
        require(rules.none { '\n' in it || '\r' in it }) { "A rule is one line" }

        val file = target.file
        val path = file.toPath()
        val options = if (target.followLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        val existing = if (Files.isRegularFile(path, *options)) Files.readAllBytes(path) else ByteArray(0)
        val present = byteLines(existing).toHashSet()
        val fresh = rules.distinct().filter { asBytes(it) !in present }

        if (fresh.isEmpty()) return

        val lineEnding = if (usesCrLf(existing)) "\r\n" else "\n"
        val separator = if (existing.isNotEmpty() && existing.last() != LF) lineEnding else ""
        val text = separator + fresh.joinToString(lineEnding, postfix = lineEnding)

        file.parentFile?.mkdirs()

        // NOFOLLOW_LINKS: never through a symbolic link, even one made after the check (the global list may be one)
        Files.write(
            path,
            text.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            *options,
        )
    }

    private const val LF = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()

    private fun usesCrLf(bytes: ByteArray): Boolean {
        val firstLineEnd = bytes.indexOf(LF)

        return firstLineEnd > 0 && bytes[firstLineEnd - 1] == CR
    }

    /** The lines as ISO-8859-1 text, which keeps every byte, so that comparing them compares bytes. */
    private fun byteLines(bytes: ByteArray): List<String> =
        String(bytes, StandardCharsets.ISO_8859_1).split('\n').map { it.removeSuffix("\r") }

    private fun asBytes(rule: String) = String(rule.toByteArray(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)

    private fun linesOf(bytes: ByteArray): List<String> =
        String(bytes, StandardCharsets.UTF_8).split('\n').map { it.removeSuffix("\r") }.dropLastWhile { it.isEmpty() }
}
