// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk

/**
 * Counts the commits that [refName] reaches and that no other ref or HEAD reaches: the commits that deleting it would
 * leave on no branch or tag. Refs to annotated tags count by their commit. Symbolic refs to [refName], refs that don't
 * point at a commit, and refs to missing objects reach nothing.
 *
 * Deleting several refs at once leaves what none of the remaining refs reach, so the refs in [alsoIgnoring], which go
 * too, count as no ref (fork-only, upstream issue 116).
 */
fun Repository.countCommitsOnlyOn(refName: String, alsoIgnoring: Set<String> = emptySet()): Int {
    val ref = exactRef(refName) ?: return 0

    RevWalk(this).use { walk ->
        val tip = walk.commitOrNull(ref.objectId) ?: return 0
        walk.markStart(tip)

        val otherRefs = refDatabase.refs + listOfNotNull(exactRef(Constants.HEAD))

        for (other in otherRefs) {
            if (other.leaf.name == refName || other.leaf.name in alsoIgnoring) continue

            walk.commitOrNull(other.objectId)?.let { walk.markUninteresting(it) }
        }

        return walk.count()
    }
}

private fun RevWalk.commitOrNull(id: AnyObjectId?): RevCommit? {
    if (id == null) return null

    return try {
        peel(parseAny(id)) as? RevCommit
    } catch (_: MissingObjectException) {
        null
    }
}
