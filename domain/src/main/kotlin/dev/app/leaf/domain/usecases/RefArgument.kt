// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IRunGitCommandGitAction

private val SHORTENED_PREFIXES = listOf("refs/heads/", "refs/remotes/")

/**
 * How a git command run in [folder] names the branch [ref] (fork-only): its short name (`agent1`, `origin/main`), which
 * reads better and gives git's usual messages ("Merge branch 'agent1'"), unless git takes it for something else, such
 * as a tag of the same name, which git looks for first. Then its full name.
 */
internal suspend fun IRunGitCommandGitAction.refArgument(folder: String, ref: String): String {
    val prefix = SHORTENED_PREFIXES.firstOrNull { ref.startsWith(it) } ?: return ref
    val shortName = ref.removePrefix(prefix)
    val resolved = invoke(folder, listOf("rev-parse", "--symbolic-full-name", shortName)).okOrNull()?.trim()

    return if (resolved == ref) shortName else ref
}
