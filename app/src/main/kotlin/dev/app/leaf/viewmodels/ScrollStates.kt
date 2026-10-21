// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.Snapshot

/**
 * A list's scroll state for a view model to make off the UI thread, at the top of the list or at [firstVisibleItemIndex],
 * which every composition can read.
 *
 * A state made on another thread is in the global snapshot, and a composition whose snapshot is older can't read it:
 * Compose throws "Reading a state that was created after the snapshot was taken". A `StateFlow` hands it to the
 * composition without a snapshot, so a frame that began before it was made can get it, and the diff pane did
 * (2026-10-15, after discarding the file's last unstaged line). Made inside a mutable snapshot, each of its states also
 * gets a record that every snapshot can read.
 */
fun newLazyListState(firstVisibleItemIndex: Int = 0): LazyListState =
    Snapshot.withMutableSnapshot { LazyListState(firstVisibleItemIndex, 0) }
