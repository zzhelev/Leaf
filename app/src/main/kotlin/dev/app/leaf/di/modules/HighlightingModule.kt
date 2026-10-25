// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.di.modules

import dev.app.leaf.data.highlighting.TextMateHighlighter
import dev.app.leaf.domain.interfaces.IHighlightTextAction
import dagger.Binds
import dagger.Module

@Module
interface HighlightingModule {
    @Binds
    fun highlightTextAction(highlighter: TextMateHighlighter): IHighlightTextAction
}
