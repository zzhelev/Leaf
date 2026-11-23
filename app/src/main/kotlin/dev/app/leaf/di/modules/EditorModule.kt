// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.di.modules

import dagger.Binds
import dagger.Module
import dev.app.leaf.data.editor.EditorLauncher
import dev.app.leaf.data.editor.ExternalEditors
import dev.app.leaf.domain.editor.IEditorLauncher
import dev.app.leaf.domain.editor.IExternalEditors

/** Fork-only: the editors on this computer, and starting them. */
@Module
interface EditorModule {
    @Binds
    fun externalEditors(editors: ExternalEditors): IExternalEditors

    @Binds
    fun editorLauncher(launcher: EditorLauncher): IEditorLauncher
}
