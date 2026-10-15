// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.common.storage.AppStorage
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FileUtils
import java.io.File

/*
 * The commit field's draft, kept in a file of Leaf's own in the worktree's git dir (fork-only). Upstream kept it in
 * git's COMMIT_EDITMSG, which git writes as scratch space: every `git commit` from a terminal (agents commit there all
 * the time) and `git rebase --continue` left their message there, comment lines included, and a blank field took it as
 * the draft.
 */

/** The draft in the worktree's git dir, or null when there is none. */
fun Repository.readCommitDraft(): String? {
    val file = commitDraftFile()

    return if (file.isFile) file.readText() else null
}

/** Saves [message] as the worktree's draft, or removes the draft when [message] is null. */
fun Repository.writeCommitDraft(message: String?) {
    val file = commitDraftFile()

    if (message != null) {
        file.writeText(message)
    } else {
        FileUtils.delete(file, FileUtils.SKIP_MISSING)
    }
}

/** The worktree's own git dir, `<common git dir>/worktrees/<name>` in a linked worktree, as for COMMIT_EDITMSG. */
private fun Repository.commitDraftFile() = File(directory, AppStorage.COMMIT_DRAFT_FILE_NAME)
