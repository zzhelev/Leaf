// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.runtime.compositionLocalOf

/** Whether Leaf's window is minimized. The worktree list doesn't refresh meanwhile. */
val LocalWindowMinimized = compositionLocalOf { false }
