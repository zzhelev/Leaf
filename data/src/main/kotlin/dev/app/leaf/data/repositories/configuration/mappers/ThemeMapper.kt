package dev.app.leaf.data.repositories.configuration.mappers

import dev.app.leaf.data.mappers.DataMapper
import dev.app.leaf.domain.models.ui.Theme
import javax.inject.Inject

private const val DARK = "dark"
private const val LIGHT = "light"
private const val CUSTOM = "custom"

class ThemeMapper @Inject constructor() : DataMapper<Theme?, String?> {
    override fun toData(value: Theme?): String? {
        return when (value) {
            Theme.Light -> LIGHT
            Theme.Dark -> DARK
            Theme.Custom -> CUSTOM
            null -> null
        }
    }


    override fun toDomain(value: String?): Theme? {
        return when (value) {
            LIGHT -> Theme.Light
            DARK -> Theme.Dark
            CUSTOM -> Theme.Custom
            null -> null
            else -> throw IllegalStateException("Unhandled theme $value")
        }
    }
}