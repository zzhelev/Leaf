// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.RewriteGroup
import dev.app.leaf.domain.rewrite.RewriteKind
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.RawParseUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** When every commit is made, in both repositories, so that the same commit has the same id in both. */
private const val COMMITTER_DATE = "@1800000000 +0000"

private const val FEAT = "refs/heads/feat"

/**
 * The rewrite engine (fork-only), against git: a squash, a fixup and a reword give, commit for commit, what
 * `git rebase -i` gives for the same todo in a copy of the repository, with the same committer date. Then where the
 * branch moves, what stops it, merges after the rewritten commits, signing, the message's bytes, `post-rewrite`, and
 * Undo.
 */
@DisabledOnOs(OS.WINDOWS)
class RewriteBranchGitActionTest {
    @TempDir
    lateinit var root: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(root, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val jgit by lazy { testJGit() }
    private val remote by lazy {
        TestRemoteCommand(
            helper = checkNotNull(builtAskpassHelper()),
            globalConfig = globalConfig,
            shellVariables = mapOf("GIT_COMMITTER_DATE" to COMMITTER_DATE),
            jgit = jgit,
        )
    }
    private val rewriteBranch by lazy {
        RewriteBranchGitAction(jgit, remote.gitCli, remote.command, remote.stateRepository)
    }
    private val undoRewrite by lazy { UndoBranchRewriteGitAction(jgit, remote.gitCli) }

    private lateinit var work: File
    private var clock = 1_700_000_000L

    private val gitDir get() = File(work, ".git").path

    @BeforeEach
    fun setUp() {
        assumeTrue(builtAskpassHelper() != null, "Leaf's askpass helper isn't built")
        SystemReader.setInstance(IsolatedSystemReader(File(root, "jgit-config"), originalReader))
        globalConfig.parentFile.mkdirs()
        // The identity that TestGitCli gives git, so that both repositories commit as the same person
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        work = File(root, "work")
        git.run(root, "init", "-q", work.path)
        commit(work, "Base", "a.txt" to "a\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    /** Commits every change in [dir], an hour after the last commit, with an author time zone other than UTC. */
    private fun commit(
        dir: File,
        message: String,
        vararg files: Pair<String, String>,
        author: String = "Ann Author <ann@example.invalid>",
    ): String {
        for ((path, text) in files) {
            File(dir, path).apply { parentFile.mkdirs() }.writeText(text)
        }

        clock += 3600
        val dates = mapOf("GIT_AUTHOR_DATE" to "@$clock +0530", "GIT_COMMITTER_DATE" to "@$clock +0000")
        git.run(dir, "add", "-A")
        git.run(dir, dates, "commit", "-q", "--allow-empty", "--author", author, "-m", message)

        return hash("HEAD", dir)
    }

    private fun hash(rev: String, dir: File = work) = git.run(dir, "rev-parse", rev).trim()

    /** feat, checked out: Base, then A (Bea's), B, C and D (Ann's), each with its own file. */
    private fun feature(): List<String> {
        git.run(work, "switch", "-q", "-c", "feat")

        return listOf(
            commit(work, "Add the parser\n\nReads the files.", "b.txt" to "b\n", author = "Bea Writer <bea@example.invalid>"),
            commit(work, "wip", "c.txt" to "c\n"),
            commit(work, "Cache the parsed files", "d.txt" to "d\n"),
            commit(work, "Test the cache", "e.txt" to "e\n"),
        )
    }

    /** A copy of [work], where git rebases the same commits. */
    private fun twin(): File = File(root, "twin").also { work.copyRecursively(it) }

    /**
     * Runs `git rebase -i` in [dir] onto [onto] (`--root` when null) with [todo], and [message] for the commit that its
     * editor opens on, with the same committer date as Leaf's commits.
     */
    private fun rebaseLikeGit(dir: File, onto: String?, todo: List<String>, message: String? = null) {
        val scripts = File(root, "scripts").apply { mkdirs() }
        val todoFile = File(scripts, "todo").apply { writeText(todo.joinToString("\n", postfix = "\n")) }
        val messageFile = File(scripts, "message").apply { writeText(message.orEmpty()) }
        val sequenceEditor = File(scripts, "sequence-editor").writeExecutable("#!/bin/sh\ncat '$todoFile' > \"\$1\"\n")
        val editor = File(scripts, "editor").writeExecutable("#!/bin/sh\ncat '$messageFile' > \"\$1\"\n")
        val environment = mapOf(
            "GIT_SEQUENCE_EDITOR" to sequenceEditor.path,
            "GIT_EDITOR" to editor.path,
            "GIT_COMMITTER_DATE" to COMMITTER_DATE,
        )

        git.run(dir, environment, "rebase", "-q", "-i", onto ?: "--root")
    }

    /** Every commit of [ref], with its id, tree, parents, author, committer and message. */
    private fun rawLog(dir: File, ref: String = FEAT) = git.run(dir, "log", "--format=raw", ref)

    private suspend fun rewrite(vararg groups: RewriteGroup, kind: RewriteKind = RewriteKind.SQUASH) =
        rewriteBranch(gitDir, BranchRewrite(FEAT, hash(FEAT), groups.toList(), kind))

    private fun <T> Either<T, GitError>.ok(): T = when (this) {
        is Either.Ok -> value
        is Either.Err -> fail("Expected it to work, but got $error")
    }

    private fun Either<*, GitError>.error(): GitError = when (this) {
        is Either.Err -> error
        is Either.Ok -> fail("Expected an error, but got $value")
    }

    // The same commits as git rebase -i

    @Test
    fun `a squash in the middle of the branch gives what git rebase -i's squash gives`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val twin = twin()
        val message = "Cache the parsed files\n\n# A comment that git leaves out\nKeeps them by path."

        val result = rewrite(RewriteGroup(listOf(b, c), message)).ok()
        rebaseLikeGit(twin, a, listOf("pick $b", "squash $c", "pick $d"), message)

        assertEquals(rawLog(twin), rawLog(work))
        assertEquals(hash(FEAT, twin), result.newTip)
        assertEquals(d, result.oldTip)
        assertEquals(listOf(b to hash("feat~1"), c to hash("feat~1"), d to hash(FEAT)), result.rewritten)
    }

    @Test
    fun `a fixup into the parent gives what git rebase -i's fixup gives`(): Unit = runBlocking {
        val (_, b, c, d) = feature()
        val twin = twin()

        rewrite(RewriteGroup(listOf(c, d), null), kind = RewriteKind.FIXUP).ok()
        rebaseLikeGit(twin, b, listOf("pick $c", "fixup $d"))

        assertEquals(rawLog(twin), rawLog(work))
    }

    @Test
    fun `a reword of the branch's first commit gives what git rebase -i's reword gives`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val twin = twin()
        val message = "Add the file parser\n\nReads the files, one at a time."

        rewrite(RewriteGroup(listOf(a), message), kind = RewriteKind.REWORD).ok()
        rebaseLikeGit(twin, hash("main"), listOf("reword $a", "pick $b", "pick $c", "pick $d"), message)

        assertEquals(rawLog(twin), rawLog(work))
    }

    @Test
    fun `squashing the whole branch keeps its oldest commit's author and gives git's commit`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        val twin = twin()
        val message = "Add a parser with a cache"

        rewrite(RewriteGroup(listOf(a, b, c, d), message)).ok()
        rebaseLikeGit(twin, hash("main"), listOf("pick $a", "squash $b", "squash $c", "squash $d"), message)

        assertEquals(rawLog(twin), rawLog(work))
        assertEquals("Bea Writer <bea@example.invalid> 1700007200 +0530", git.run(work, "log", "-1", "--format=%an <%ae> %ad", "--date=raw").trim())
        assertEquals(hash("main"), hash("feat~1"))
    }

    @Test
    fun `squashing the first commit of a repository makes a root commit, as git rebase --root does`(): Unit =
        runBlocking {
            git.run(work, "switch", "-q", "-c", "feat")
            val base = hash("HEAD")
            val second = commit(work, "Second", "b.txt" to "b\n")
            val third = commit(work, "Third", "c.txt" to "c\n")
            val twin = twin()

            rewrite(RewriteGroup(listOf(base, second), "Start")).ok()
            rebaseLikeGit(twin, onto = null, listOf("pick $base", "squash $second", "pick $third"), "Start")

            assertEquals(rawLog(twin), rawLog(work))
            assertEquals("", git.run(work, "log", "-1", "--format=%P", "feat~1").trim())
        }

    @Test
    fun `post-rewrite gets what git rebase gives it`(): Unit = runBlocking {
        val (a, b, c, d) = feature()
        // Each repository's hook writes next to it: work.post-rewrite and twin.post-rewrite
        val out = "../\$(basename \"\$PWD\").post-rewrite"
        val twin = twin()

        // After the copy, which doesn't keep a file's executable bit
        for (dir in listOf(work, twin)) {
            File(dir, ".git/hooks/post-rewrite").writeExecutable("#!/bin/sh\necho \"\$*\" > $out\ncat >> $out\n")
        }

        rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()
        rebaseLikeGit(twin, a, listOf("pick $b", "squash $c", "pick $d"), "Cache the parsed files")

        val leaf = File(root, "work.post-rewrite").readText()
        assertEquals("rebase\n$b ${hash("feat~1")}\n$c ${hash("feat~1")}\n$d ${hash(FEAT)}\n", leaf)
        assertEquals(File(root, "twin.post-rewrite").readText(), leaf)
    }

    // Where the branch moves

    @Test
    fun `the branch checked out here moves with HEAD, and every uncommitted change stays`(): Unit = runBlocking {
        val (_, b, c, _) = feature()
        File(work, "b.txt").writeText("b, staged\n")
        git.run(work, "add", "b.txt")
        File(work, "b.txt").writeText("b, staged, then changed again\n")
        File(work, "e.txt").writeText("e, changed\n")
        File(work, "new.txt").writeText("new\n")
        val status = git.run(work, "status", "--porcelain=v1")
        val staged = git.run(work, "diff", "--cached")
        val unstaged = git.run(work, "diff")
        val tree = hash("feat^{tree}")

        rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()

        assertEquals(status, git.run(work, "status", "--porcelain=v1"))
        assertEquals(staged, git.run(work, "diff", "--cached"))
        assertEquals(unstaged, git.run(work, "diff"))
        assertEquals(tree, hash("feat^{tree}"))
        assertEquals("ref: refs/heads/feat", File(work, ".git/HEAD").readText().trim())
        assertEquals("squash: Cache the parsed files", git.run(work, "reflog", "-1", "--format=%gs", "HEAD").trim())
        assertEquals("squash: Cache the parsed files", git.run(work, "reflog", "-1", "--format=%gs", "feat").trim())
    }

    @Test
    fun `a branch checked out in another worktree moves there, with that worktree's HEAD`(): Unit = runBlocking {
        val (_, b, c, _) = feature()
        git.run(work, "switch", "-q", "main")
        val linked = File(root, "linked")
        git.run(work, "worktree", "add", "-q", linked.path, "feat")
        File(linked, "e.txt").writeText("e, changed\n")
        val ownHeadLog = git.run(work, "reflog", "--format=%H %gs", "HEAD")

        val result = rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()

        assertEquals(result.newTip, hash("HEAD", linked))
        assertEquals(" M e.txt\n", git.run(linked, "status", "--porcelain=v1"))
        assertEquals("squash: Cache the parsed files", git.run(linked, "reflog", "-1", "--format=%gs", "HEAD").trim())
        // The tab's own HEAD, on main, didn't move
        assertEquals(ownHeadLog, git.run(work, "reflog", "--format=%H %gs", "HEAD"))
    }

    @Test
    fun `a branch that no worktree has checked out moves alone`(): Unit = runBlocking {
        val (_, b, c, _) = feature()
        git.run(work, "switch", "-q", "main")
        val main = hash("main")
        val headLog = git.run(work, "reflog", "--format=%H %gs", "HEAD")

        val result = rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()

        assertEquals(result.newTip, hash(FEAT))
        assertEquals(main, hash("HEAD"))
        assertEquals(headLog, git.run(work, "reflog", "--format=%H %gs", "HEAD"))
        assertEquals("squash: Cache the parsed files", git.run(work, "reflog", "-1", "--format=%gs", "feat").trim())
    }

    // What stops it, changing nothing

    @Test
    fun `a branch that moved since the rewrite was planned is refused`(): Unit = runBlocking {
        val (_, b, c, d) = feature()
        val plan = BranchRewrite(FEAT, d, listOf(RewriteGroup(listOf(b, c), "Joined")), RewriteKind.SQUASH)
        val moved = commit(work, "An agent's commit", "f.txt" to "f\n")

        assertEquals(RewriteBranchError.BranchMoved("feat"), rewriteBranch(gitDir, plan).error())
        assertEquals(moved, hash(FEAT))
    }

    @Test
    fun `the branch moving between the check and the update is refused too`(): Unit = runBlocking {
        val (_, _, c, d) = feature()
        val place = BranchPlace(work, emptyList())

        val result = either<Unit, GitError> {
            moveBranch(remote.gitCli, place, FEAT, from = c, to = c, reflogMessage = "squash: Joined")
            Either.Ok(Unit)
        }

        assertEquals(Either.Err(RewriteBranchError.BranchMoved("feat")), result)
        assertEquals(d, hash(FEAT))
    }

    @Test
    fun `commits that aren't one after the other on the branch's line are refused`(): Unit = runBlocking {
        val (a, b, c, d) = feature()

        assertEquals(RewriteBranchError.NotOnLine("feat", c), rewrite(RewriteGroup(listOf(a, c), "Gap")).error())
        assertEquals(RewriteBranchError.NotOnLine("feat", a), rewrite(RewriteGroup(listOf(b, a), "Reversed")).error())
        val main = hash("main")
        assertEquals(
            RewriteBranchError.NotOnLine("feat", "0".repeat(40)),
            rewrite(RewriteGroup(listOf("0".repeat(40)), "Elsewhere")).error(),
        )
        // Two groups in the wrong order
        assertEquals(
            RewriteBranchError.NotOnLine("feat", a),
            rewrite(RewriteGroup(listOf(c, d), null), RewriteGroup(listOf(a, b), null)).error(),
        )
        assertEquals(d, hash(FEAT))
        assertEquals(main, hash("main"))
    }

    @Test
    fun `a message that git's clean-up leaves empty is refused`(): Unit = runBlocking {
        val (_, b, c, d) = feature()

        assertEquals(
            RewriteBranchError.EmptyMessage,
            rewrite(RewriteGroup(listOf(b, c), "# Only a comment\n\n")).error(),
        )
        assertEquals(d, hash(FEAT))
    }

    @Test
    fun `a merge in progress where the branch is checked out stops it`(): Unit = runBlocking {
        val (_, b, c, _) = feature()
        commit(work, "Change a on feat", "a.txt" to "a, feat\n")
        git.run(work, "switch", "-q", "main")
        commit(work, "Change a on main", "a.txt" to "a, main\n")
        git.run(work, "switch", "-q", "feat")
        git.runFailing(work, "merge", "main")
        val tip = hash(FEAT)

        val error = rewriteBranch(gitDir, BranchRewrite(FEAT, tip, listOf(RewriteGroup(listOf(b, c), "J")), RewriteKind.SQUASH))
            .error() as RewriteBranchError.BranchBusy

        assertEquals(BranchOperation.MERGE, error.operation)
        assertEquals(work.canonicalPath, File(error.worktreePath).canonicalPath)
        assertEquals(tip, hash(FEAT))
    }

    @Test
    fun `a worktree that rebases the branch, or bisects from it, stops it`(): Unit = runBlocking {
        val (a, _, c, d) = feature()
        git.run(work, "switch", "-q", "main")
        commit(work, "Change b on main", "b.txt" to "b, main\n")
        val linked = File(root, "linked")
        git.run(work, "worktree", "add", "-q", linked.path, "feat")
        git.runFailing(linked, "rebase", "main")

        val rebasing = rewrite(RewriteGroup(listOf(c, d), "J")).error() as RewriteBranchError.BranchBusy

        assertEquals(BranchOperation.REBASE, rebasing.operation)
        assertEquals(linked.canonicalPath, File(rebasing.worktreePath).canonicalPath)

        git.run(linked, "rebase", "--abort")
        git.run(linked, "bisect", "start", "feat", a)

        val bisecting = rewrite(RewriteGroup(listOf(c, d), "J")).error() as RewriteBranchError.BranchBusy

        assertEquals(BranchOperation.BISECT, bisecting.operation)
        assertEquals(d, hash(FEAT))
    }

    // Merges

    @Test
    fun `a merge after the rewritten commits is made again with its other parent and its tree`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "side")
        val side = commit(work, "On the side", "s.txt" to "s\n")
        git.run(work, "switch", "-q", "main")
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "A", "b.txt" to "b\n")
        val b = commit(work, "B", "c.txt" to "c\n")
        git.run(work, "merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = hash("HEAD")
        val d = commit(work, "D", "d.txt" to "d\n")

        val result = rewrite(RewriteGroup(listOf(a, b), "A and B")).ok()

        val newMerge = hash("feat~1")
        assertEquals("${hash("feat~2")} $side", git.run(work, "log", "-1", "--format=%P", newMerge).trim())
        assertEquals(hash("$merge^{tree}"), hash("$newMerge^{tree}"))
        assertEquals("Merge side", git.run(work, "log", "-1", "--format=%s", newMerge).trim())
        assertEquals(hash("$d^{tree}"), hash("feat^{tree}"))
        assertEquals(listOf(a, b, merge, d), result.rewritten.map { it.first })
    }

    @Test
    fun `a merge that brings the rewritten commits in again is refused`(): Unit = runBlocking {
        val (a, b, _, _) = feature()
        git.run(work, "switch", "-q", "-c", "side", b)
        commit(work, "On a branch from B", "s.txt" to "s\n")
        git.run(work, "switch", "-q", "feat")
        git.run(work, "merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = hash("HEAD")

        assertEquals(RewriteBranchError.MergedAgain("feat", merge), rewrite(RewriteGroup(listOf(a, b), "J")).error())
        assertEquals(merge, hash(FEAT))
    }

    @Test
    fun `a merge can't be joined, and can be reworded`(): Unit = runBlocking {
        git.run(work, "switch", "-q", "-c", "side")
        val side = commit(work, "On the side", "s.txt" to "s\n")
        git.run(work, "switch", "-q", "main")
        git.run(work, "switch", "-q", "-c", "feat")
        val a = commit(work, "A", "b.txt" to "b\n")
        git.run(work, "merge", "-q", "--no-ff", "-m", "Merge side", "side")
        val merge = hash("HEAD")

        assertEquals(RewriteBranchError.JoinsMerge(merge), rewrite(RewriteGroup(listOf(a, merge), "J")).error())

        rewrite(RewriteGroup(listOf(merge), "Bring in the side"), kind = RewriteKind.REWORD).ok()

        assertEquals("$a $side", git.run(work, "log", "-1", "--format=%P", FEAT).trim())
        assertEquals("Bring in the side", git.run(work, "log", "-1", "--format=%B", FEAT).trim())
    }

    // The commits themselves

    @Test
    fun `commit gpgSign signs every new commit with git's own signing program`(): Unit = runBlocking {
        val (_, b, c, _) = feature()
        val gpg = File(root, "bin/gpg").writeExecutable(
            "#!/bin/sh\ncat > /dev/null\n" +
                "echo >&2\necho '[GNUPG:] SIG_CREATED D 22 10 00 1791381795 4AE4445EFE49B9DFFBC8E1A8B4CE4B3E4E90D11A' >&2\n" +
                "printf -- '-----BEGIN PGP SIGNATURE-----\\n\\nfake\\n-----END PGP SIGNATURE-----\\n'\n"
        )
        globalConfig.appendText("[commit]\n\tgpgSign = true\n[gpg]\n\tprogram = ${gpg.path}\n")

        rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()

        for (rev in listOf("feat", "feat~1")) {
            assertTrue(git.run(work, "cat-file", "commit", rev).contains("gpgsig -----BEGIN PGP SIGNATURE-----"), rev)
        }
        assertTrue(!git.run(work, "cat-file", "commit", "feat~2").contains("gpgsig"))
    }

    @Test
    fun `a commit made again keeps its message's bytes and their encoding`(): Unit = runBlocking {
        val (_, b, c, _) = feature()
        val latin1 = File(root, "latin1.txt").apply { writeBytes("Café au lait\n".toByteArray(Charsets.ISO_8859_1)) }
        File(work, "f.txt").writeText("f\n")
        git.run(work, "add", "-A")
        git.run(work, "-c", "i18n.commitEncoding=ISO-8859-1", "commit", "-q", "-F", latin1.path)
        val old = hash(FEAT)

        rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()

        val (oldRaw, newRaw) = jgit.provide(gitDir) { git ->
            RevWalk(git.repository).use { walk ->
                walk.parseCommit(ObjectId.fromString(old)).rawBuffer to
                    walk.parseCommit(git.repository.resolve(FEAT)).rawBuffer
            }
        }.ok()

        assertEquals("ISO-8859-1", RawParseUtils.parseEncodingName(newRaw))
        assertArrayEquals(
            oldRaw.copyOfRange(RawParseUtils.commitMessage(oldRaw, 0), oldRaw.size),
            newRaw.copyOfRange(RawParseUtils.commitMessage(newRaw, 0), newRaw.size),
        )
    }

    // Undo

    @Test
    fun `undo puts the branch back where it was, and says so in the reflog`(): Unit = runBlocking {
        val (_, b, c, d) = feature()
        File(work, "e.txt").writeText("e, changed\n")
        val result = rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()

        assertEquals(Either.Ok(Unit), undoRewrite(gitDir, result))

        assertEquals(d, hash(FEAT))
        assertEquals(" M e.txt\n", git.run(work, "status", "--porcelain=v1"))
        assertEquals("undo squash: back to ${d.take(7)}", git.run(work, "reflog", "-1", "--format=%gs", "HEAD").trim())
    }

    @Test
    fun `undo of a branch that moved since is refused`(): Unit = runBlocking {
        val (_, b, c, _) = feature()
        val result: BranchRewriteResult = rewrite(RewriteGroup(listOf(b, c), "Cache the parsed files")).ok()
        val moved = commit(work, "An agent's commit", "f.txt" to "f\n")

        assertEquals(RewriteBranchError.BranchMoved("feat"), undoRewrite(gitDir, result).error())
        assertEquals(moved, hash(FEAT))
    }
}
