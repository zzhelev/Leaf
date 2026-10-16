// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.repository

import dev.app.leaf.common.printError
import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.domain.RebaseConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FileUtils
import java.io.File
import java.io.IOException

private const val TAG = "RebaseEditMessage"

/*
 * The commit field's message for the commit that a rebase stopped at to edit, which Continue amends with it
 * (fork-only). It's a file of Leaf's in the rebase's `rebase-merge` folder, which git and JGit delete when the rebase
 * ends or is aborted, and each stop has its own, named after the commit: a message typed at one stop, which a Continue
 * from a terminal left behind, isn't the next stop's. Upstream saved it in MERGE_MSG and showed the draft, so a draft
 * became the amended commit's message. MERGE_MSG wouldn't do either: JGit's rebase leaves a stopped commit's MERGE_MSG
 * there through the later stops.
 */

private val COMMIT_ID = Regex("[0-9a-f]{4,64}")

/** The commit that the rebase stopped at to edit, from the `amend` file that git and JGit write there, or null. */
fun Repository.rebaseEditStop(): String? {
    val file = File(rebaseMergeDir(), RebaseConstants.AMEND)
    val commitId = if (file.isFile) file.readText().trim() else return null

    return commitId.takeIf { COMMIT_ID.matches(it) }
}

/** The message saved for [stop], or null when there is none. */
fun Repository.readRebaseEditMessage(stop: String): String? {
    val file = rebaseEditMessageFile(stop)

    return if (file.isFile) file.readText() else null
}

/**
 * Saves [message] for the commit that the rebase stopped at to edit, or removes it when [message] is null. Nothing
 * happens at another stop, where the field can't be edited.
 */
fun Repository.writeRebaseEditMessage(message: String?) {
    val file = rebaseEditStop()?.let(::rebaseEditMessageFile) ?: return

    if (message != null) {
        file.writeText(message)
    } else {
        FileUtils.delete(file, FileUtils.SKIP_MISSING)
    }
}

/**
 * The message of the commit that Continue amends at an edit stop, HEAD's, which `git commit --amend` starts from, or
 * null when it can't be read.
 */
fun Repository.headCommitMessage(): String? {
    val head = resolve(Constants.HEAD) ?: return null

    return try {
        parseCommit(head).fullMessage
    } catch (exception: IOException) {
        printError(TAG, "Failed to read HEAD's message: ${exception.message}")
        null
    }
}

private fun Repository.rebaseMergeDir() = File(directory, RebaseConstants.REBASE_MERGE)

private fun Repository.rebaseEditMessageFile(stop: String) =
    File(rebaseMergeDir(), AppStorage.REBASE_EDIT_MESSAGE_FILE_PREFIX + stop)
