// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.styles

import dev.app.leaf.domain.models.ui.AccentStyle
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Why a style's file couldn't be read at all. */
enum class UnreadableStyle {
    /** The text isn't JSON. */
    NOT_JSON,

    /** JSON without a `leafStyle` version, such as the custom theme of Leaf before 2026-10-22. */
    NOT_A_STYLE,

    /** A file in the styles folder named after a built-in style, which would hide it. */
    BUILT_IN_ID,

    /** A file that couldn't be opened. */
    CANT_READ,
}

/** What reading a style's file gave. */
sealed interface StyleFileRead {
    /** The style, and what was left out or replaced while reading it, in English, for the log. */
    data class Read(val definition: StyleDefinition, val problems: List<String>) : StyleFileRead

    data class Unreadable(val reason: UnreadableStyle, val detail: String) : StyleFileRead
}

/**
 * A style's file (fork-only): JSON with the format's version, the name, the look, and for each side the seeds and the
 * colours set by hand, keyed by [StyleSeed.key] and [StyleSlot.key]. The id is the file's name, so it isn't written.
 *
 * ```json
 * {
 *   "leafStyle": 1,
 *   "name": "Teal",
 *   "look": "tonal",
 *   "light": {
 *     "seeds": { "background": "#FFFFFF", "accent": "#007475", "danger": "#B4405A", "warning": "#E0A03A", "success": "#3F8F52" },
 *     "setByHand": { "text": "#1D2B2B" }
 *   },
 *   "dark": { … }
 * }
 * ```
 *
 * Reading is lenient where it can be, so that a file from another version still opens: an unknown look reads as Tonal,
 * a missing or broken seed as the look's own, an unknown key or a colour that doesn't parse is left out, and a missing
 * name is the id. Each is reported in [StyleFileRead.Read.problems]. Only text that isn't JSON, or JSON without a
 * `leafStyle` version, can't be read.
 */
object StyleFileCodec {
    const val VERSION_KEY = "leafStyle"
    const val VERSION = 1

    private const val NAME_KEY = "name"
    private const val LOOK_KEY = "look"
    private const val LIGHT_KEY = "light"
    private const val DARK_KEY = "dark"
    private const val SEEDS_KEY = "seeds"
    private const val SET_BY_HAND_KEY = "setByHand"

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(definition: StyleDefinition): String {
        val root = buildJsonObject {
            put(VERSION_KEY, VERSION)
            put(NAME_KEY, definition.name)
            put(LOOK_KEY, definition.look.name.lowercase())
            put(LIGHT_KEY, encodeSide(definition.light))
            put(DARK_KEY, encodeSide(definition.dark))
        }

        return json.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    private fun encodeSide(side: StyleSide): JsonObject = buildJsonObject {
        putJsonObject(SEEDS_KEY) {
            for (seed in StyleSeed.entries) put(seed.key, StyleColorHex.format(side.seeds[seed]))
        }
        putJsonObject(SET_BY_HAND_KEY) {
            // In the slots' order, so that a file changes only where a colour does
            for (slot in StyleSlot.entries) {
                val argb = side.setByHand[slot] ?: continue
                put(slot.key, StyleColorHex.format(argb))
            }
        }
    }

    /** Reads the file of the style whose id is [id]. Never throws. */
    fun decode(text: String, id: String): StyleFileRead {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (ex: SerializationException) {
            return StyleFileRead.Unreadable(UnreadableStyle.NOT_JSON, ex.message.orEmpty().lineSequence().first())
        } catch (ex: IllegalArgumentException) {
            return StyleFileRead.Unreadable(UnreadableStyle.NOT_JSON, ex.message.orEmpty().lineSequence().first())
        }

        // kotlinx's parser takes a bare word, such as `Solarized`, for a value
        if (root is JsonPrimitive && !root.isString && root.content !in JSON_WORDS && root.content.toDoubleOrNull() == null) {
            return StyleFileRead.Unreadable(UnreadableStyle.NOT_JSON, "\"${root.content.take(40)}\" isn't JSON")
        }

        if (root !is JsonObject) {
            return StyleFileRead.Unreadable(UnreadableStyle.NOT_A_STYLE, "it isn't a JSON object")
        }

        val version = (root[VERSION_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (version == null || version < 1) {
            return StyleFileRead.Unreadable(UnreadableStyle.NOT_A_STYLE, "it has no \"$VERSION_KEY\" version")
        }

        val problems = mutableListOf<String>()

        if (version > VERSION) {
            problems += "it was made by a newer Leaf (format $version), and is read as format $VERSION"
        }

        val name = root.string(NAME_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: id.also {
            problems += "it has no name, so it's named after its file"
        }

        val lookText = root.string(LOOK_KEY)
        val look = AccentStyle.entries.firstOrNull { it.name.lowercase() == lookText } ?: AccentStyle.TONAL.also {
            problems += "its look \"${lookText.orEmpty()}\" isn't known, so it's Tonal"
        }

        val definition = StyleDefinition(
            id = id,
            name = name,
            look = look,
            light = decodeSide(root[LIGHT_KEY], look, isLight = true, problems),
            dark = decodeSide(root[DARK_KEY], look, isLight = false, problems),
        )

        return StyleFileRead.Read(definition, problems)
    }

    private fun decodeSide(element: JsonElement?, look: AccentStyle, isLight: Boolean, problems: MutableList<String>): StyleSide {
        val sideName = if (isLight) LIGHT_KEY else DARK_KEY
        val templateSeeds = BuiltInStyles.templateSeeds(look, isLight)

        if (element !is JsonObject) {
            problems += "it has no $sideName side, so that side is the look's"
            return StyleSide(templateSeeds)
        }

        val seedsObject = element[SEEDS_KEY] as? JsonObject
        if (seedsObject == null) problems += "its $sideName side has no seeds, so they're the look's"

        var seeds = templateSeeds
        for (seed in StyleSeed.entries) {
            if (seedsObject == null) break

            val text = seedsObject.string(seed.key)
            val argb = text?.let(StyleColorHex::parse)

            when {
                text == null -> problems += "its $sideName ${seed.key} is missing, so it's the look's"
                argb == null -> problems += "its $sideName ${seed.key} \"$text\" isn't a colour, so it's the look's"
                // A seed is a colour that things are drawn in, never a see-through one
                argb ushr 24 != 0xFF -> {
                    problems += "its $sideName ${seed.key} is see-through, so it's used opaque"
                    seeds = seeds.with(seed, argb or OPAQUE)
                }

                else -> seeds = seeds.with(seed, argb)
            }
        }
        seedsObject?.keys?.filter { StyleSeed.fromKey(it) == null }?.forEach {
            problems += "its $sideName seed \"$it\" isn't known, so it's left out"
        }

        val setByHand = mutableMapOf<StyleSlot, Int>()
        when (val byHand = element[SET_BY_HAND_KEY]) {
            null -> Unit
            is JsonObject -> for ((key, value) in byHand) {
                val slot = StyleSlot.fromKey(key)
                val argb = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(StyleColorHex::parse)

                when {
                    slot == null -> problems += "its $sideName colour \"$key\" isn't known, so it's left out"
                    argb == null -> problems += "its $sideName $key \"$value\" isn't a colour, so it's left out"
                    else -> setByHand[slot] = argb
                }
            }

            else -> problems += "its $sideName colours set by hand aren't a JSON object, so they're left out"
        }

        return StyleSide(seeds, setByHand)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val OPAQUE = 0xFF000000.toInt()

    private val JSON_WORDS = setOf("true", "false", "null")
}

/** Colours as text in a style's file: `#RRGGBB`, or `#RRGGBBAA` when see-through, in CSS's order. */
object StyleColorHex {
    private val pattern = Regex("#([0-9A-Fa-f]{6})([0-9A-Fa-f]{2})?")

    fun format(argb: Int): String {
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
        val alpha = argb ushr 24

        return if (alpha == 0xFF) "#$rgb" else "#$rgb" + alpha.toString(16).padStart(2, '0').uppercase()
    }

    /** The ARGB of [text], or null when it isn't `#RRGGBB` or `#RRGGBBAA`. Spaces around it are allowed. */
    fun parse(text: String): Int? {
        val match = pattern.matchEntire(text.trim()) ?: return null
        val rgb = match.groupValues[1].toInt(16)
        val alpha = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(16) ?: 0xFF

        return (alpha shl 24) or rgb
    }
}
