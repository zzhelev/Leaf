// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rebase

import dev.app.leaf.domain.models.RebaseLine

/**
 * The steps, with a squash or fixup that comes first turned into a pick. git refuses to squash without a commit before
 * it ("cannot 'squash' without a previous commit"), and the first row's menu offers only pick, reword and drop.
 * Dropped steps don't count, as they're left out of the rebase.
 */
fun List<RebaseLine>.withFirstStepPicked(): List<RebaseLine> {
    val firstIndex = indexOfFirst { it.action != RebaseLine.Action.DROP && it.action != RebaseLine.Action.COMMENT }

    if (firstIndex < 0) {
        return this
    }

    val first = this[firstIndex]

    return if (first.action == RebaseLine.Action.SQUASH || first.action == RebaseLine.Action.FIXUP) {
        toMutableList().apply { set(firstIndex, first.copy(action = RebaseLine.Action.PICK)) }
    } else {
        this
    }
}
