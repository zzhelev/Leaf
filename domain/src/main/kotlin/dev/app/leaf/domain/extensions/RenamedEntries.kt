// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.extensions

import dev.app.leaf.domain.sorting.RenamedFrom
import org.eclipse.jgit.diff.DiffEntry

/** Where a renamed file came from, or null for any other change. Its [DiffEntry.filePath] is the new path. */
val DiffEntry.renamedFrom: RenamedFrom?
    get() = if (changeType == DiffEntry.ChangeType.RENAME) RenamedFrom(oldPath, score) else null
