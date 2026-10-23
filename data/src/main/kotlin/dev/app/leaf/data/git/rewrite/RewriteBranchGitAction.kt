// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.data.git.workspace.messageCleanup
import dev.app.leaf.domain.commitmessage.cleanCommitMessage
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IRewriteBranchGitAction
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.RawParseUtils
import java.nio.charset.Charset
import java.nio.file.Files
import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration

private const val TAG = "RewriteBranchGitAction"

/** git's name for the encoding of a commit without an `encoding` header. */
private const val UTF_8 = "UTF-8"

/**
 * A commit to make: [sources], oldest first, become one commit with the tree of the newest, the author of the oldest,
 * [message] in [encoding], and [otherParents] after its first parent. One source that keeps its message is a commit
 * made again on a new parent.
 */
private class NewCommit(
    val sources: List<RevCommit>,
    val message: ByteArray,
    val encoding: String,
    val otherParents: List<String>,
)

/**
 * Rewrites a local branch's commits as a [BranchRewrite] says (fork-only), with git's commands, so that its identity,
 * signing and hooks apply as in a terminal. Every new commit is `git commit-tree` of a tree that the branch already has,
 * so nothing is replayed and nothing can conflict, and the branch ends with the tree it had: the working tree and the
 * index stay as they are. One `git update-ref` that expects the old tip then moves the branch, and `post-rewrite` runs
 * with what changed, as after `git rebase`.
 *
 * git runs in the worktree that has the branch checked out ([branchPlace]), or in the tab's. Each `commit-tree` goes
 * through [GitCliRemoteCommand], as ssh-keygen may ask for the passphrase of a key that signs commits. Cancelling before
 * the branch moves leaves only commits that nothing points to.
 */
class RewriteBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
    private val remoteCommand: GitCliRemoteCommand,
    private val repositoryStateRepository: RepositoryStateRepository,
) : IRewriteBranchGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        rewrite: BranchRewrite,
    ): Either<BranchRewriteResult, GitError> = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        val branchName = Repository.shortenRefName(rewrite.branch)
        val place = branchPlace(repository, rewrite.branch)
        val oldTip = repository.exactRef(rewrite.branch)?.objectId?.name

        if (oldTip == null || oldTip != rewrite.expectedTip) {
            raiseError(RewriteBranchError.BranchMoved(branchName))
        }

        val line = readLine(repository, rewrite, branchName)
        val newCommits = newCommits(repository, rewrite, line, branchName)
        refuseIfMergedAgain(repository, line, newCommits, branchName)

        val sign = signsCommits(place)
        val rewritten = mutableListOf<Pair<String, String>>()
        var parent = line.first().parents.firstOrNull()?.name

        try {
            for ((index, newCommit) in newCommits.withIndex()) {
                currentCoroutineContext().ensureActive()
                repositoryStateRepository.updateTaskProgress(TaskProgress(null, index * 100 / newCommits.size))

                val id = commitTree(place, newCommit, listOfNotNull(parent) + newCommit.otherParents, sign)
                newCommit.sources.forEach { source -> rewritten.add(source.name to id) }
                parent = id
            }
        } finally {
            repositoryStateRepository.updateTaskProgress(null)
        }

        val newTip = checkNotNull(parent)
        val subject = subjectOf(newCommits.first())

        currentCoroutineContext().ensureActive()

        // Once the branch moves, the rewrite is done: what follows isn't cancelled
        withContext(NonCancellable) {
            moveBranch(gitCli, place, rewrite.branch, oldTip, newTip, "${rewrite.kind.reflogName}: $subject")
            runPostRewrite(place, rewritten)
        }

        BranchRewriteResult(rewrite.branch, rewrite.kind, oldTip, newTip, rewritten)
    }

    /**
     * The branch's first-parent line from the first group's oldest commit to the tip, oldest first. A commit that isn't
     * on it is refused.
     */
    private fun EitherContext<GitError>.readLine(
        repository: Repository,
        rewrite: BranchRewrite,
        branchName: String,
    ): List<RevCommit> {
        val oldest = rewrite.groups.firstOrNull()?.commits?.firstOrNull()
            ?: raiseError(RewriteBranchError.NotOnLine(branchName, rewrite.expectedTip))

        val line = mutableListOf<RevCommit>()

        RevWalk(repository).use { walk ->
            var commit: RevCommit? = walk.parseCommit(ObjectId.fromString(rewrite.expectedTip))

            while (commit != null) {
                line.add(commit)

                if (commit.name == oldest) {
                    return line.reversed()
                }

                commit = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
            }
        }

        raiseError(RewriteBranchError.NotOnLine(branchName, oldest))
    }

    /**
     * The commits to make, oldest first: each group as one, and every other commit of [line] made again. The groups'
     * commits must follow each other on [line] in their order, and a group of several can't join a merge. A new message is
     * cleaned up as `git commit` cleans an edited one, with the repository's `commit.cleanup`.
     */
    private fun EitherContext<GitError>.newCommits(
        repository: Repository,
        rewrite: BranchRewrite,
        line: List<RevCommit>,
        branchName: String,
    ): List<NewCommit> {
        val cleanup = repository.config.messageCleanup()
        val newCommits = mutableListOf<NewCommit>()
        var position = 0

        for (group in rewrite.groups) {
            val first = group.commits.firstOrNull() ?: raiseError(RewriteBranchError.NotOnLine(branchName, ""))
            val start = (position until line.size).firstOrNull { line[it].name == first }
                ?: raiseError(RewriteBranchError.NotOnLine(branchName, first))

            for (index in group.commits.indices) {
                val commit = line.getOrNull(start + index)

                if (commit?.name != group.commits[index]) {
                    raiseError(RewriteBranchError.NotOnLine(branchName, group.commits[index]))
                }
            }

            line.subList(position, start).mapTo(newCommits) { it.madeAgain() }

            val sources = line.subList(start, start + group.commits.size)

            if (sources.size > 1) {
                sources.firstOrNull { it.parentCount > 1 }?.let { raiseError(RewriteBranchError.JoinsMerge(it.name)) }
            }

            val otherParents = if (sources.size == 1) sources.first().otherParents() else emptyList()
            val text = group.message
            val newCommit = if (text == null) {
                NewCommit(sources, sources.first().rawMessage(), sources.first().encodingName(), otherParents)
            } else {
                val message = cleanCommitMessage(text, cleanup)

                if (message.isBlank()) {
                    raiseError(RewriteBranchError.EmptyMessage)
                }

                NewCommit(sources, message.toByteArray(Charsets.UTF_8), UTF_8, otherParents)
            }

            newCommits.add(newCommit)
            position = start + group.commits.size
        }

        line.subList(position, line.size).mapTo(newCommits) { it.madeAgain() }

        return newCommits
    }

    /**
     * Refuses a merge after the first rewritten commit whose other parent has that commit: it comes from a branch that
     * started on this one, which would bring the old commits back next to the new ones.
     */
    private fun EitherContext<GitError>.refuseIfMergedAgain(
        repository: Repository,
        line: List<RevCommit>,
        newCommits: List<NewCommit>,
        branchName: String,
    ) {
        val merges = newCommits.filter { it.otherParents.isNotEmpty() }

        if (merges.isEmpty()) {
            return
        }

        RevWalk(repository).use { walk ->
            val firstChanged = walk.parseCommit(line.first())

            for (merge in merges) {
                for (parent in merge.otherParents) {
                    if (walk.isMergedInto(firstChanged, walk.parseCommit(ObjectId.fromString(parent)))) {
                        raiseError(RewriteBranchError.MergedAgain(branchName, merge.sources.first().name))
                    }
                }
            }
        }
    }

    /** Whether git signs the commits it makes, which `git commit-tree` leaves to its `-S` (`commit.gpgSign`). */
    private suspend fun EitherContext<GitError>.signsCommits(place: BranchPlace): Boolean {
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

    private suspend fun EitherContext<GitError>.commitTree(
        place: BranchPlace,
        newCommit: NewCommit,
        parents: List<String>,
        sign: Boolean,
    ): String {
        val author = newCommit.sources.first().authorIdent
        val args = place.gitArgs +
            listOf("-c", "i18n.commitEncoding=${newCommit.encoding}", "commit-tree", newCommit.sources.last().tree.name) +
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
    private suspend fun runPostRewrite(place: BranchPlace, rewritten: List<Pair<String, String>>) {
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
}

/** The commit's message as it's stored, byte for byte, after its headers. */
private fun RevCommit.rawMessage(): ByteArray {
    val buffer = rawBuffer
    val start = RawParseUtils.commitMessage(buffer, 0)

    return if (start < 0) ByteArray(0) else buffer.copyOfRange(start, buffer.size)
}

/** The encoding that the commit's message is in, its `encoding` header, which is UTF-8 without one. */
private fun RevCommit.encodingName(): String = encodingName ?: UTF_8

private fun RevCommit.otherParents(): List<String> = parents.drop(1).map { it.name }

private fun RevCommit.madeAgain() = NewCommit(listOf(this), rawMessage(), encodingName(), otherParents())

/** The first line of [newCommit]'s message that has something, for the reflog. */
private fun subjectOf(newCommit: NewCommit): String {
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
