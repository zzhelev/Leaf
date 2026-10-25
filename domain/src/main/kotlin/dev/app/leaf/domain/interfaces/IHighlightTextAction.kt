// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.highlighting.HighlightedText

interface IHighlightTextAction {
    /**
     * [text] coloured by the grammar of [scopeName], or null when that grammar can't colour it. Cancelling the
     * caller stops the work.
     */
    suspend operator fun invoke(scopeName: String, text: String): HighlightedText?
}
