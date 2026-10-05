package dev.app.leaf.domain

import dev.app.leaf.domain.models.AvatarProviderType
import dev.app.leaf.domain.models.DateTimeFormat

object SettingsDefaults {
    val defaultAvatarProviderType = AvatarProviderType.Gravatar
    val defaultDateTimeFormat = DateTimeFormat(
        useSystemDefault = true,
        customFormat = "dd MMM yyyy",
        is24hours = true,
        useRelativeDate = true,
    )
}