// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.mapOk

/** The most commits of its own that Leaf reads for a branch, and of its base, for the menus. */
const val MAX_LINE_COMMITS = 2_000

/**
 * Every local branch's own commits (fork-only, step A3 of `docs/fork/squash-and-rebase.md`), which the log's and the
 * branches' menus read to offer squashing and rewording them, and to say why they don't.
 *
 * @param base the branch the lines leave from (`refs/heads/main`), the worktrees' base. Null when there's none.
 * @param lines each local branch's line, by its full name.
 * @param baseCommits the newest commits of [base], up to [MAX_LINE_COMMITS], to tell a commit of the base from one that
 * only a remote branch, a tag or a stash has.
 */
data class BranchLines(
    val base: String? = null,
    val lines: Map<String, BranchLine> = emptyMap(),
    val baseCommits: Set<String> = emptySet(),
) {
    /**
     * The line that has all of [commits] as its own: the only one, or the current branch's when several have them, as
     * a branch made at another's commit shares its line. Refused when none does, or several do and none is current.
     */
    fun lineHolding(commits: Collection<String>, currentBranch: String?): Either<BranchLine, RewriteRefusal> {
        val holding = lines.values.filter { line -> commits.all(line::has) }

        return when {
            holding.size == 1 -> Either.Ok(holding.single())
            holding.size > 1 -> holding.firstOrNull { it.branch == currentBranch }?.let { Either.Ok(it) }
                ?: Either.Err(RewriteRefusal.OnSeveralBranches(holding.size))

            base != null && commits.any { it in baseCommits } ->
                Either.Err(RewriteRefusal.OnBase(shortName(sharedBase(base))))

            else -> Either.Err(RewriteRefusal.NotOnLocalBranch)
        }
    }

    /**
     * What a commit row's items do with [commit]: "Edit message…" ([CommitRewriteChoices.reword]), "Squash into
     * parent…" ([CommitRewriteChoices.intoParent]) and "Fixup into parent" ([CommitRewriteChoices.fixup]). [busy] says
     * what a worktree is in the middle of with a branch, which stops a rewrite of it.
     */
    fun commitChoices(
        commit: String,
        currentBranch: String?,
        busy: (branch: String) -> BranchOperation?,
    ): CommitRewriteChoices {
        val line = when (val holding = usableLine(listOf(commit), currentBranch, busy)) {
            is Either.Err -> return CommitRewriteChoices(holding, holding, holding)
            is Either.Ok -> holding.value
        }

        return CommitRewriteChoices(
            reword = line.commitToReword(commit),
            intoParent = line.commitWithParent(commit),
            fixup = line.commitToFixUp(commit),
        )
    }

    /** What "Squash N commits…" does with [commits], selected in the log. */
    fun rangeChoice(
        commits: Collection<String>,
        currentBranch: String?,
        busy: (branch: String) -> BranchOperation?,
    ): Either<LineRange, RewriteRefusal> = when (val line = usableLine(commits, currentBranch, busy)) {
        is Either.Err -> line
        is Either.Ok -> line.value.commitsToJoin(commits)
    }

    /**
     * What "Squash branch into one commit…" does with the local branch [branch], or null when it has no commits of its
     * own to offer it for: the base branch, a branch just made, or one that Leaf didn't read.
     */
    fun branchChoice(branch: String, busy: (branch: String) -> BranchOperation?): Either<LineRange, RewriteRefusal>? {
        val line = lines[branch]?.takeIf { it.commits.isNotEmpty() } ?: return null

        return busy(branch)?.let { Either.Err(RewriteRefusal.Busy(it)) } ?: line.allCommits()
    }

    /**
     * What "Edit branch commits…" opens for the local branch [branch]: a plan of all its own commits, or null when it has
     * none to offer it for, as for [branchChoice].
     */
    fun editorChoice(branch: String, busy: (branch: String) -> BranchOperation?): Either<RebasePlan, RewriteRefusal>? {
        val line = lines[branch]?.takeIf { it.commits.isNotEmpty() } ?: return null

        busy(branch)?.let { return Either.Err(RewriteRefusal.Busy(it)) }

        return if (line.isTruncated) {
            Either.Err(RewriteRefusal.TooManyCommits)
        } else {
            Either.Ok(RebasePlan.of(line, line.commits.reversed()))
        }
    }

    /** What "Interactive rebase from here…" opens for [commit]: a plan of it and the commits after it on its line. */
    fun editorFrom(
        commit: String,
        currentBranch: String?,
        busy: (branch: String) -> BranchOperation?,
    ): Either<RebasePlan, RewriteRefusal> = editorFromOldestOf(listOf(commit), currentBranch, busy)

    /** What "Edit these in the rebase editor…" opens for [commits], selected in the log: the line from the oldest. */
    fun editorForRange(
        commits: Collection<String>,
        currentBranch: String?,
        busy: (branch: String) -> BranchOperation?,
    ): Either<RebasePlan, RewriteRefusal> = editorFromOldestOf(commits, currentBranch, busy)

    /** What "Drop commit…" does with [commit]: the plan from it, with it dropped. */
    fun dropChoice(
        commit: String,
        currentBranch: String?,
        busy: (branch: String) -> BranchOperation?,
    ): Either<RebasePlan, RewriteRefusal> =
        editorFrom(commit, currentBranch, busy).mapOk { it.withAction(commit, PlanAction.DROP) }

    private fun editorFromOldestOf(
        commits: Collection<String>,
        currentBranch: String?,
        busy: (branch: String) -> BranchOperation?,
    ): Either<RebasePlan, RewriteRefusal> {
        val line = when (val holding = usableLine(commits, currentBranch, busy)) {
            is Either.Err -> return holding
            is Either.Ok -> holding.value
        }
        val oldest = line.commits.indexOfLast { it.hash in commits }

        return Either.Ok(RebasePlan.of(line, line.commits.subList(0, oldest + 1).reversed()))
    }

    /**
     * Where the base's commits are shared: its upstream, when the base's own line is its unpushed commits
     * ([BranchLine.base] is the upstream then), else the base itself.
     */
    private fun sharedBase(base: String): String = lines[base]?.base?.takeIf { it != base } ?: base

    private fun usableLine(
        commits: Collection<String>,
        currentBranch: String?,
        busy: (branch: String) -> BranchOperation?,
    ): Either<BranchLine, RewriteRefusal> = when (val line = lineHolding(commits, currentBranch)) {
        is Either.Err -> line
        is Either.Ok -> busy(line.value.branch)?.let { Either.Err(RewriteRefusal.Busy(it)) } ?: line
    }
}

/**
 * What a commit row's rewrite items do: give it another message, and join it with its parent, as a squash or as a fixup,
 * which a `fixup!` subject that names another commit refuses.
 */
data class CommitRewriteChoices(
    val reword: Either<LineRange, RewriteRefusal>,
    val intoParent: Either<LineRange, RewriteRefusal>,
    val fixup: Either<LineRange, RewriteRefusal>,
)

/** What the dialog of a rewrite does with its commits. */
enum class RewriteDialogKind {
    /** "Squash N commits…" and "Squash into parent…". */
    SQUASH,

    /** "Squash branch into one commit…": every commit of the branch's own. */
    SQUASH_BRANCH,

    /** "Edit message…". */
    REWORD,
}

/** A rewrite that its dialog asks a message for: [range]'s commits, and what to do with them. */
data class RewriteRequest(
    val kind: RewriteDialogKind,
    val range: LineRange,
) {
    /** The message the dialog starts from, [start] for a squash. */
    fun messageStart(start: SquashMessageStart = SquashMessageStart.SUBJECTS): String = when (kind) {
        RewriteDialogKind.REWORD -> range.commits.single().message.replace("\r\n", "\n").trim()
        RewriteDialogKind.SQUASH, RewriteDialogKind.SQUASH_BRANCH -> squashMessageStart(range.commits, start)
    }

    /** The rewrite with [message], the dialog's, on [onto] for the whole branch when the dialog's box is checked. */
    fun rewrite(message: String, onto: OntoBase? = null): BranchRewrite = when (kind) {
        RewriteDialogKind.REWORD -> range.reword(message)
        RewriteDialogKind.SQUASH -> range.squash(message)
        RewriteDialogKind.SQUASH_BRANCH -> range.squash(message, onto)
    }
}

/** A branch's name without `refs/heads/` or `refs/remotes/`, as the menus show it. */
fun shortName(ref: String): String = ref.removePrefix("refs/heads/").removePrefix("refs/remotes/")
