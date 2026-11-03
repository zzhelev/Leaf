// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.domain.interfaces.OtherWorktreeHead
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * What a reftable repository keeps in each worktree's `HEAD` file, whatever HEAD is: a symbolic ref to a branch that
 * can't exist. The real HEAD is in the reftable.
 */
private const val REFTABLE_HEAD_STUB = "ref: refs/heads/.invalid"

/**
 * The commits that the other worktrees have checked out with a detached HEAD, read from their `HEAD` files as
 * [worktreeHeads] reads them (see [Repository.otherWorktreeHeads]).
 *
 * @param heads the commits, each once, with the branch that the worktree rebases or bisects (fork-only, issue 207).
 * @param needsGit whether a `HEAD` file is the reftable's stub, so that these files can't tell and git has to.
 */
internal data class OtherWorktreeHeads(val heads: List<OtherWorktreeHead>, val needsGit: Boolean) {
    val commits: List<String> get() = heads.map { it.commit }
}

/**
 * The commits that this repository's other worktrees have detached, as the log has to start from them: no ref points to
 * such a commit. Leaves out:
 * - this repository's own worktree, whose HEAD JGit lists as a branch named HEAD;
 * - a worktree on a branch, which includes an unborn one: its tip is in the log as that branch's;
 * - a worktree whose folder, or its `.git` file, is missing: git calls it prunable ("gitdir file points to non-existent
 *   location"), or the folder is on a drive that isn't there, and a locked one isn't prunable but is as missing;
 * - a `HEAD` file that can't be read, or whose commit the repository doesn't have.
 *
 * A rebase or a bisect has a detached HEAD, so it counts. The main worktree of a bare repository isn't one.
 */
internal fun Repository.otherWorktreeHeads(): OtherWorktreeHeads {
    val ownGitDir = directory.canonicalFile
    val heads = LinkedHashMap<String, OtherWorktreeHead>()
    var needsGit = false

    for ((gitDir, path) in worktreeGitDirs()) {
        if (gitDir.canonicalFile == ownGitDir) continue

        val head = File(gitDir, Constants.HEAD).readTextOrNull()?.trim() ?: continue

        when {
            head == REFTABLE_HEAD_STUB -> needsGit = true
            // On a branch
            head.startsWith("ref:") -> Unit
            ObjectId.isId(head) && File(path, Constants.DOT_GIT).exists() && objectDatabase.has(ObjectId.fromString(head)) ->
                heads.getOrPut(head) {
                    val state = worktreeHead(gitDir, path)

                    OtherWorktreeHead(head, state.rebasingBranch ?: state.bisectingBranch)
                }
        }
    }

    return OtherWorktreeHeads(heads.values.toList(), needsGit)
}
