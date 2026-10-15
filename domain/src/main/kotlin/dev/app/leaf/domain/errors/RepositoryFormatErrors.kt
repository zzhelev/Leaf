// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

import dev.app.leaf.domain.index.UnsupportedIndex

/** JGit couldn't read the worktree's index, as git wrote it in a format that JGit doesn't know ([index]). */
data class UnsupportedIndexError(val index: UnsupportedIndex) : GitError

/**
 * JGit couldn't find the object [objectId] that it needed: the repository is a partial clone that hasn't downloaded it
 * yet, or the repository is damaged. [message] is JGit's ("Missing blob <id>").
 */
data class MissingObjectError(val objectId: String, val message: String) : GitError

/**
 * The repository is a partial clone (`git clone --filter=blob:none`), which downloads files from [remote] when they're
 * first needed, and Leaf couldn't download the ones that this needed.
 *
 * @param error why git couldn't fetch them, or null when git fetched without an error but [remote] didn't send them.
 * @param nothingChanged the download was to come before a checkout, a merge or another change, which didn't start.
 */
data class PartialCloneFetchError(
    val remote: String,
    val error: GitError?,
    val nothingChanged: Boolean = false,
) : GitError

/** Whether this is about an object that the repository doesn't have, which a diff or a blame shows rather than hides. */
fun AppError.isMissingObject(): Boolean = this is PartialCloneFetchError || this is MissingObjectError
