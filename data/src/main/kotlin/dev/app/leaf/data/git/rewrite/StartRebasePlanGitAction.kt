// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.operations.GitCliOperations
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.workspace.messageCleanup
import dev.app.leaf.domain.commitmessage.cleanCommitMessage
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICheckRebasePlanGitAction
import dev.app.leaf.domain.interfaces.IGetPlanRecordGitAction
import dev.app.leaf.domain.interfaces.IStartRebasePlanGitAction
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.PLAN_RECORD_FILE_NAME
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanRebaseOutcome
import dev.app.leaf.domain.rewrite.PlanRecord
import dev.app.leaf.domain.rewrite.PlanRefusal
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.RebaseTodo
import dev.app.leaf.domain.rewrite.RewriteKind
import dev.app.leaf.domain.rewrite.rebaseTodo
import dev.app.leaf.domain.rewrite.twinsFor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File
import java.nio.file.Files
import javax.inject.Inject

private const val TAG = "StartRebasePlan"

/** The folder that git keeps an interactive rebase in, in the git dir of the worktree it runs in. */
private const val REBASE_FOLDER = "rebase-merge"

/** Where the plan's rebase runs, and what it gets. */
private class PreparedRebase(
    val gitDir: File,
    val branchArgument: String,
    val oldTip: String,
    val todo: RebaseTodo,
    val twins: Map<String, String>,
)

/**
 * Starts a commit editor's plan that stops (fork-only, step B4 of `docs/fork/commit-editor.md`) as `git rebase -i`, in
 * the worktree that has the branch checked out, or in the tab's, where git checks it out first (decision 2a). Leaf's
 * todo ([rebaseTodo]) replaces git's through `GIT_SEQUENCE_EDITOR`, and the groups' new messages go in as twins
 * ([twinsFor]), commits that `git commit-tree` makes with the tree, parent and author that they stand for, so git never
 * opens an editor for the plan.
 *
 * git runs through [GitCliOperations.run] (Leaf's dialogs for signing, Cancel, and the notice when changes that it set
 * aside don't go back), with `--empty=drop` (a commit whose changes are there already is left out, as in the check),
 * `--force-rebase` (every commit is made again, signed as git's config says) and `--autostash` or `--no-autostash`, so
 * that `rebase.autoStash` doesn't decide for the user. Leaf's todo replaces git's whole, so what `rebase.autoSquash`
 * and `rebase.updateRefs` put in git's, and the cherry-picks that git leaves out of it, never reach the rebase.
 *
 * When it stops, Leaf writes the plan's record ([PlanRecord]) into git's `rebase-merge` folder, which git removes when
 * the rebase ends or is aborted.
 */
class StartRebasePlanGitAction @Inject constructor(
    private val jgit: JGit,
    private val remoteCommand: GitCliRemoteCommand,
    private val operations: GitCliOperations,
    private val checkPlan: ICheckRebasePlanGitAction,
) : IStartRebasePlanGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        plan: RebasePlan,
        autostash: Boolean,
    ): Either<PlanRebaseOutcome, GitError> = either {
        when (val refusal = plan.refusal(stops = true)) {
            is PlanRefusal.JoinsMerge -> raiseError(RewriteBranchError.JoinsMerge(refusal.merge))
            is PlanRefusal.ReplaysMerge -> raiseError(RewriteBranchError.ReplaysMerge(refusal.merge))
            is PlanRefusal.MergeInRebase -> raiseError(RewriteBranchError.ReplaysMerge(refusal.merge))
            null -> Unit
        }

        require(plan.isChanged) { "The plan changes nothing" }

        val check = checkPlan(repositoryPath, plan).bind()
        val prepared = jgit.provide(repositoryPath) { git -> prepare(git.repository, plan, check) }.bind()
        val todoFile = Files.createTempFile("leaf-rebase-todo-", ".txt").toFile()

        try {
            todoFile.writeText(prepared.todo.lines.joinToString("\n", postfix = "\n"))

            // A branch from the repository's first commit has no upstream: git starts at the root
            val upstream = prepared.todo.upstream?.let { listOf(it) } ?: listOf("--root")
            val onto = prepared.todo.onto?.let { listOf("--onto", it) }.orEmpty()
            val args = listOf(
                "rebase", "-i", "--empty=drop", "--force-rebase", if (autostash) "--autostash" else "--no-autostash",
            ) + onto + upstream + prepared.branchArgument
            val sequenceEditor = "cp ${shellQuoted(todoFile.absolutePath)}"

            operations.run(
                prepared.gitDir.absolutePath,
                args,
                environment = mapOf("GIT_SEQUENCE_EDITOR" to sequenceEditor),
            ).bind()
        } finally {
            todoFile.delete()
        }

        val rebaseFolder = File(prepared.gitDir, REBASE_FOLDER)

        if (rebaseFolder.isDirectory) {
            val record = PlanRecord.of(plan, prepared.twins).format()

            runCatching { File(rebaseFolder, PLAN_RECORD_FILE_NAME).writeText(record) }
                .onFailure { printError(TAG, "Couldn't write the plan's record: ${it.message}") }

            Either.Ok(PlanRebaseOutcome.Stopped(prepared.gitDir.absolutePath))
        } else {
            val newTip = jgit.provide(repositoryPath) { git -> git.repository.exactRef(plan.line.branch)?.objectId?.name }
                .bind()
                ?: raiseError(RewriteBranchError.BranchMoved(Repository.shortenRefName(plan.line.branch)))

            val result = BranchRewriteResult(
                branch = plan.line.branch,
                kind = RewriteKind.REBASE,
                oldTip = prepared.oldTip,
                newTip = newTip,
                rewritten = emptyList(),
                onto = plan.onto,
            )

            Either.Ok(PlanRebaseOutcome.Done(result))
        }
    }

    /**
     * Where the rebase runs, checked: the branch at the plan's tip, a worktree to run in, and the twins made, by the group
     * they stand for.
     */
    private suspend fun EitherContext<GitError>.prepare(
        repository: Repository,
        plan: RebasePlan,
        check: PlanCheck,
    ): PreparedRebase {
        val branch = plan.line.branch
        val branchName = Repository.shortenRefName(branch)
        val place = branchPlace(repository, branch)
        val oldTip = repository.exactRef(branch)?.objectId?.name

        if (oldTip == null || oldTip != plan.expectedTip) {
            raiseError(RewriteBranchError.BranchMoved(branchName))
        }

        val gitDir = place.gitDir

        if (!place.hasWorkingTree || gitDir == null) {
            raiseError(RewriteBranchError.NoWorkingTree(branchName))
        }

        val cleanup = repository.config.messageCleanup()
        val twins = mutableMapOf<String, String>()

        RevWalk(repository).use { walk ->
            for (twin in plan.twinsFor(check)) {
                val source = walk.parseCommit(ObjectId.fromString(twin.source))
                val authorOf = walk.parseCommit(ObjectId.fromString(twin.authorOf))
                val message = cleanCommitMessage(twin.message, cleanup)

                if (message.isBlank()) {
                    raiseError(RewriteBranchError.EmptyMessage)
                }

                val newCommit = NewCommit(listOf(authorOf), message.toByteArray(Charsets.UTF_8), UTF_8, emptyList())
                val parents = source.parents.take(1).map { it.name }

                twins[twin.group] = commitTree(remoteCommand, place, newCommit, parents, false, source.tree.name)
            }
        }

        val branchArgument = operations.branchArgument(gitDir.absolutePath, branch).bind()

        return PreparedRebase(gitDir, branchArgument, oldTip, plan.rebaseTodo(check, twins), twins)
    }
}

/** Reads the plan's record from git's `rebase-merge` folder in [repositoryPath]'s worktree (fork-only, B4). */
class GetPlanRecordGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetPlanRecordGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<PlanRecord?, GitError> =
        jgit.provide(repositoryPath) { git ->
            val file = File(git.repository.directory, "$REBASE_FOLDER/$PLAN_RECORD_FILE_NAME")

            if (file.isFile) PlanRecord.parse(file.readText()) else null
        }
}

/** [text] in single quotes, as `sh` reads it, for `GIT_SEQUENCE_EDITOR`, which git runs through the shell. */
private fun shellQuoted(text: String) = "'" + text.replace("'", "'\\''") + "'"
