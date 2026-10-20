package dev.app.leaf.data.repositories.configuration.mappers

import dev.app.leaf.data.mappers.DataMapper
import dev.app.leaf.domain.models.ui.Theme
import javax.inject.Inject

private const val DARK = "dark"
private const val LIGHT = "light"
private const val CUSTOM = "custom"
private const val SYSTEM = "system"

class ThemeMapper @Inject constructor() : DataMapper<Theme?, String?> {
    override fun toData(value: Theme?): String? {
        return when (value) {
            Theme.Light -> LIGHT
            Theme.Dark -> DARK
            Theme.Custom -> CUSTOM
            Theme.System -> SYSTEM
            null -> null
        }
    }


    override fun toDomain(value: String?): Theme? {
        return when (value) {
            LIGHT -> Theme.Light
            DARK -> Theme.Dark
            CUSTOM -> Theme.Custom
            SYSTEM -> Theme.System
            // Fork-only: a value from a newer Leaf reads as no choice (the default), where it used to throw
            // inside the settings flow and stop the app at launch
            else -> null
        }
    }
}
