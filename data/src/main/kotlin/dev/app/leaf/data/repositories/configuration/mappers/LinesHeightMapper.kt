package dev.app.leaf.data.repositories.configuration.mappers

import dev.app.leaf.data.mappers.DataMapper
import dev.app.leaf.domain.models.ui.LinesHeightType
import javax.inject.Inject

private const val SPACED = "spaced"
private const val COMPACT = "compact"

class LinesHeightMapper @Inject constructor(): DataMapper<LinesHeightType?, String?>  {
    override fun toData(value: LinesHeightType?): String? {
        return when (value) {
            LinesHeightType.SPACED -> SPACED
            LinesHeightType.COMPACT -> COMPACT
            null -> null
        }
    }

    override fun toDomain(value: String?): LinesHeightType? {
        return when (value) {
            SPACED -> LinesHeightType.SPACED
            COMPACT -> LinesHeightType.COMPACT
            null -> null
            else -> throw IllegalStateException("Unhandled linesHeightType $value")
        }
    }
}