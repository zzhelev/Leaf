// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.api.MergeCommand.ConflictStyle
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.DefaultTypedConfigGetter

/**
 * Reads the values of `merge.conflictStyle` and `diff.algorithm` that git accepts and JGit's enums lack. JGit refuses
 * them with "Invalid value", and it reads both keys in every merge (`ResolveMerger`, `MergeConfig`), so merges,
 * rebases, cherry-picks, reverts, stash applies and pulls failed with either set, and diffs (`DiffFormatter`) with
 * `diff.algorithm` set.
 *
 * Each value is read as the closest one that JGit has:
 * - `merge.conflictStyle=zdiff3` as `diff3`: JGit's diff3 already leaves the lines that both sides have alike at the
 *   start and end of a conflict outside of it, which is what zdiff3 adds to diff3;
 * - `diff.algorithm=default` and `minimal` as `myers`, which they are in git (JGit's Myers finds a shortest diff,
 *   which is what `minimal` asks of git's), and `patience` as `histogram`, JGit's extended form of patience. git's
 *   merges use `diff.algorithm` too.
 *
 * Other values go to JGit's own reader, so values that git refuses still fail. Nothing is written to any config.
 */
class GitCompatibleConfigGetter : DefaultTypedConfigGetter() {
    override fun <T : Enum<*>?> getEnum(
        config: Config,
        all: Array<out T>,
        section: String,
        subsection: String?,
        name: String,
        defaultValue: T?,
    ): T? {
        val value = config.getString(section, subsection, name)

        val closest = when {
            value == null || subsection != null -> null

            all.isArrayOf<ConflictStyle>() && isKey(section, name, MERGE, CONFLICT_STYLE) -> closestConflictStyle(value)

            all.isArrayOf<SupportedAlgorithm>() && isKey(section, name, DIFF, ALGORITHM) -> closestAlgorithm(value)

            else -> null
        }

        @Suppress("UNCHECKED_CAST")
        return closest as T? ?: super.getEnum(config, all, section, subsection, name, defaultValue)
    }

    private fun isKey(section: String, name: String, keySection: String, keyName: String) =
        section.equals(keySection, ignoreCase = true) && name.equals(keyName, ignoreCase = true)

    private fun closestConflictStyle(value: String) =
        if (value.equals("zdiff3", ignoreCase = true)) ConflictStyle.DIFF3 else null

    private fun closestAlgorithm(value: String) = when (value.lowercase()) {
        "default", "minimal" -> SupportedAlgorithm.MYERS
        "patience" -> SupportedAlgorithm.HISTOGRAM
        else -> null
    }

    private companion object {
        const val MERGE = ConfigConstants.CONFIG_MERGE_SECTION
        const val CONFLICT_STYLE = ConfigConstants.CONFIG_KEY_CONFLICTSTYLE
        const val DIFF = ConfigConstants.CONFIG_DIFF_SECTION
        const val ALGORITHM = ConfigConstants.CONFIG_KEY_ALGORITHM
    }
}

/** Makes every JGit config read use [GitCompatibleConfigGetter]. JGit keeps one reader for all of its configs. */
fun readGitOnlyConfigValues() {
    Config.setTypedConfigGetter(GitCompatibleConfigGetter())
}
