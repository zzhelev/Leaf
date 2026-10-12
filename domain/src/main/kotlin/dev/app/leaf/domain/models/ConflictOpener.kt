// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/**
 * What double-clicking a conflicted file and the banner's Resolve conflicts open (Settings → Conflicts). The other one
 * stays in the file's menu and the diff's conflict bar.
 */
enum class ConflictOpener {
    /** Leaf's own resolver, with ours, the result and theirs. */
    BUILT_IN,

    /**
     * The merge tool that `git mergetool --gui` runs (`merge.guitool`, then `merge.tool`). A file that it can't take
     * from Leaf, or a repository without such a tool, opens in the built-in resolver.
     */
    EXTERNAL_TOOL;

    companion object {
        val DEFAULT = BUILT_IN
    }
}
