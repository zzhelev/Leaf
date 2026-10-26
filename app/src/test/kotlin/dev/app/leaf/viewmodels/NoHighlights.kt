// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.interfaces.IHighlightTextAction

/** A highlighter that colours nothing, for the view models whose tests don't look at colours. */
object NoHighlights : IHighlightTextAction {
    override suspend fun invoke(scopeName: String, text: String): HighlightedText? = null
}
