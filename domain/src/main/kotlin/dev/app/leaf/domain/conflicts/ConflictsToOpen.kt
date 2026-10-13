// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.canOpenInMergeTool

/** What the banner's Resolve conflicts opens. */
sealed interface ConflictsToOpen {
    /** The files that `git mergetool` can take, one after another. */
    data class InMergeTool(val filePaths: List<String>) : ConflictsToOpen

    /** The built-in resolver, on [filePath], or on the first conflicted file it finds when null. */
    data class InResolver(val filePath: String?) : ConflictsToOpen
}

/**
 * What the banner's Resolve conflicts opens for [entries], Unstaged's files in its order (by path, as
 * `git mergetool` goes through them). With [inMergeTool], when Settings → Conflicts picks the external merge tool and
 * git has one: every conflicted file that the tool can take, in turn. The others wait for the next click, which opens
 * them in the built-in resolver, once the tool can take none of the files left. Otherwise the resolver opens on the
 * first conflicted file.
 */
fun conflictsToOpen(entries: List<StatusEntry>, inMergeTool: Boolean): ConflictsToOpen {
    val conflicted = entries.filter { it.statusType == StatusType.CONFLICTING }.distinctBy { it.filePath }
    val forMergeTool = conflicted.filter { it.canOpenInMergeTool }

    return if (inMergeTool && forMergeTool.isNotEmpty()) {
        ConflictsToOpen.InMergeTool(forMergeTool.map { it.filePath })
    } else {
        ConflictsToOpen.InResolver(conflicted.firstOrNull()?.filePath)
    }
}
