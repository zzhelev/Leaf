// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.discards

import dev.app.leaf.domain.discards.DiscardKind
import dev.app.leaf.domain.errors.DiscardRecordError
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.raiseError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Repository
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The discard that a [keepingDiscarded] runs, which a discard inside it joins rather than keeping one of its own. */
private class OuterDiscard(val pending: PendingDiscard) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OuterDiscard>
}

/**
 * Runs [discard] with what it throws away kept, so that it can be undone: copies of [paths], and with [withIndex] their
 * index entries, first ([DiscardStore.begin]), then the record of what changed, whether the discard ended well or not
 * ([PendingDiscard.finish]). When a copy fails, raises [DiscardRecordError.NotKept] and doesn't run [discard].
 *
 * Inside another one, it joins that one's record: Discard all restores its files through the discard of a selection.
 */
internal suspend fun <T> EitherContext<GitError>.keepingDiscarded(
    store: DiscardStore,
    repositoryPath: String,
    repository: Repository,
    kind: DiscardKind,
    paths: Collection<String>,
    withIndex: Boolean = false,
    discard: suspend (PendingDiscard) -> T,
): T {
    currentCoroutineContext()[OuterDiscard]?.let { outer -> return discard(outer.pending) }

    val pending = try {
        withContext(Dispatchers.IO) { store.begin(repositoryPath, repository, kind, paths, withIndex) }
    } catch (e: IOException) {
        val path = (e as? NotKeptException)?.path ?: paths.firstOrNull().orEmpty()
        raiseError(DiscardRecordError.NotKept(path, e.message.orEmpty()))
    }

    try {
        return withContext(OuterDiscard(pending)) { discard(pending) }
    } finally {
        pending.finish()
    }
}
