// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.log

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/**
 * The focus requester of the log's list, when what shows it wants to give the focus back to it (fork-only). Closing a diff
 * that stood under the log does, so that the arrow keys go on moving the selection. Without one, the list makes its own.
 */
val LocalLogFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }
