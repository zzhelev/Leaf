package dev.app.leaf

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import dev.app.leaf.avatarproviders.AvatarProvider
import dev.app.leaf.avatarproviders.NoneAvatarProvider
import dev.app.leaf.domain.SettingsDefaults
import dev.app.leaf.viewmodels.RepositoryTabViewModel

val LocalTab =
    compositionLocalOf<RepositoryTabViewModel> { throw IllegalStateException("Tab information requested but not provided") }
val LocalTabFocusRequester = compositionLocalOf { FocusRequester() }
val LocalAvatarProvider = compositionLocalOf<AvatarProvider> { NoneAvatarProvider() }
val LocalDateTimeFormat = compositionLocalOf { SettingsDefaults.defaultDateTimeFormat }
