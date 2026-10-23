// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.RawParseUtils
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.math.abs
import kotlin.time.Duration

// The commits that part A's rewrites and the commit editor's plans make (fork-only), with git's `commit-tree`.

private const val TAG = "RewriteCommits"

/** git's name for the encoding of a commit without an `encoding` header. */
internal const val UTF_8 = "UTF-8"

/**
 * A commit to make: [sources], oldest first, become one commit with the tree of the newest, the author of the oldest,
 * [message] in [encoding], and [otherParents] after its first parent. One source that keeps its message is a commit
 * made again on a new parent.
 */
internal class NewCommit(
    val sources: List<RevCommit>,
    val message: ByteArray,
    val encoding: String,
    val otherParents: List<String>,
)

/** Whether git signs the commits it makes, which `git commit-tree` leaves to its `-S` (`commit.gpgSign`). */
internal suspend fun EitherContext<GitError>.signsCommits(gitCli: GitCli, place: BranchPlace): Boolean {
    val args = place.gitArgs + listOf("config", "--type=bool", "--get", "commit.gpgSign")
    val output = gitCli.execute(place.workingDirectory, args).bind()

    return when (output.exitCode) {
        0 -> output.stdout.trim() == "true"
        // Not set
        1 -> false
        else -> raiseError(
            GitCliError.CommandFailed("git config commit.gpgSign", output.exitCode, output.stderr.trim())
        )
    }
}

/** `git commit-tree` of [newCommit], with the tree of its newest source, or [tree] in its place. */
internal suspend fun EitherContext<GitError>.commitTree(
    remoteCommand: GitCliRemoteCommand,
    place: BranchPlace,
    newCommit: NewCommit,
    parents: List<String>,
    sign: Boolean,
    tree: String? = null,
): String {
    val author = newCommit.sources.first().authorIdent
    val treeId = tree ?: newCommit.sources.last().tree.name
    val args = place.gitArgs +
        listOf("-c", "i18n.commitEncoding=${newCommit.encoding}", "commit-tree", treeId) +
        parents.flatMap { listOf("-p", it) } +
        (if (sign) listOf("-S") else emptyList()) +
        listOf("-F", "-")
    val environment = mapOf(
        "GIT_AUTHOR_NAME" to author.name,
        "GIT_AUTHOR_EMAIL" to author.emailAddress,
        "GIT_AUTHOR_DATE" to gitDate(author),
    )
    val output = remoteCommand.run(
        workingDirectory = place.workingDirectory,
        args = args,
        onProgress = {},
        input = newCommit.message,
        environment = environment,
    ).bind()

    if (output.exitCode != 0) {
        raiseError(
            GitCliError.CommandFailed("git commit-tree", output.exitCode, readableGitOutput(output.stderr))
        )
    }

    return output.stdout.trim()
}

/**
 * Runs `post-rewrite` as `git rebase` does, with `rebase` and a line per old commit and its new one, when git finds
 * the hook. git ignores what it returns, and so does Leaf, but logs it.
 */
internal suspend fun runPostRewrite(gitCli: GitCli, place: BranchPlace, rewritten: List<Pair<String, String>>) {
    val list = Files.createTempFile("leaf-rewritten-", ".txt").toFile()

    try {
        list.writeText(rewritten.joinToString("") { (old, new) -> "$old $new\n" })

        val args = place.gitArgs +
            listOf("hook", "run", "--ignore-missing", "--to-stdin=${list.absolutePath}", "post-rewrite", "--", "rebase")
        val output = gitCli.execute(place.workingDirectory, args, timeout = Duration.INFINITE)

        if (output !is Either.Ok || output.value.exitCode != 0) {
            printError(TAG, "post-rewrite didn't run cleanly: $output")
        }
    } finally {
        list.delete()
    }
}

/** The commit's message as it's stored, byte for byte, after its headers. */
internal fun RevCommit.rawMessage(): ByteArray {
    val buffer = rawBuffer
    val start = RawParseUtils.commitMessage(buffer, 0)

    return if (start < 0) ByteArray(0) else buffer.copyOfRange(start, buffer.size)
}

/** The encoding that the commit's message is in, its `encoding` header, which is UTF-8 without one. */
internal fun RevCommit.encodingName(): String = encodingName ?: UTF_8

internal fun RevCommit.otherParents(): List<String> = parents.drop(1).map { it.name }

internal fun RevCommit.madeAgain() = NewCommit(listOf(this), rawMessage(), encodingName(), otherParents())

/** The first line of [newCommit]'s message that has something, for the reflog. */
internal fun subjectOf(newCommit: NewCommit): String {
    val charset = runCatching { Charset.forName(newCommit.encoding) }.getOrDefault(Charsets.UTF_8)

    return String(newCommit.message, charset).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
}

/** [ident]'s time as git reads it in `GIT_AUTHOR_DATE`: `@<seconds since the epoch> <+hhmm>`. */
internal fun gitDate(ident: PersonIdent): String {
    val offset = ident.timeZoneOffset
    val sign = if (offset < 0) '-' else '+'
    val minutes = abs(offset)

    return "@%d %c%02d%02d".format(ident.whenAsInstant.epochSecond, sign, minutes / 60, minutes % 60)
}
