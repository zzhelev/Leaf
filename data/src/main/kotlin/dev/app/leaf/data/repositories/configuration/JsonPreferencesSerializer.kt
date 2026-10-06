// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.putJsonObject
import okio.BufferedSink
import okio.BufferedSource
import java.util.Base64

private const val TYPE = "type"
private const val VALUE = "value"

/**
 * Stores DataStore [Preferences] as JSON instead of DataStore's protobuf format. DataStore's bundled protobuf calls
 * `sun.misc.Unsafe`, which JDK 25 warns about at runtime and a later JDK will remove.
 *
 * Each key maps to `{"type": ..., "value": ...}`, so every value reads back with the type it was written with.
 */
object JsonPreferencesSerializer : OkioSerializer<Preferences> {
    private val json = Json {
        prettyPrint = true
        allowSpecialFloatingPointValues = true
    }

    override val defaultValue: Preferences = emptyPreferences()

    override suspend fun readFrom(source: BufferedSource): Preferences {
        val text = source.readUtf8()
        if (text.isBlank()) return emptyPreferences()

        try {
            val preferences = mutablePreferencesOf()
            for ((name, entry) in json.parseToJsonElement(text).jsonObject) {
                val typed = entry.jsonObject
                val type = typed[TYPE]?.jsonPrimitive?.content
                val value = typed[VALUE] ?: throw CorruptionException("Setting $name has no value")

                when (type) {
                    "boolean" -> preferences[booleanPreferencesKey(name)] = value.jsonPrimitive.boolean
                    "int" -> preferences[intPreferencesKey(name)] = value.jsonPrimitive.int
                    "long" -> preferences[longPreferencesKey(name)] = value.jsonPrimitive.long
                    "float" -> preferences[floatPreferencesKey(name)] = value.jsonPrimitive.float
                    "double" -> preferences[doublePreferencesKey(name)] = value.jsonPrimitive.double
                    "string" -> preferences[stringPreferencesKey(name)] = value.jsonPrimitive.content
                    "string_set" -> preferences[stringSetPreferencesKey(name)] =
                        value.jsonArray.map { it.jsonPrimitive.content }.toSet()

                    "bytes" -> preferences[byteArrayPreferencesKey(name)] =
                        Base64.getDecoder().decode(value.jsonPrimitive.content)

                    else -> throw CorruptionException("Setting $name has an unknown type: $type")
                }
            }
            return preferences.toPreferences()
        } catch (e: SerializationException) {
            throw CorruptionException("The settings file isn't valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("The settings file has an unexpected structure or value", e)
        } catch (e: IllegalStateException) {
            throw CorruptionException("The settings file has a value of the wrong type", e)
        }
    }

    override suspend fun writeTo(t: Preferences, sink: BufferedSink) {
        val root = buildJsonObject {
            for ((key, value) in t.asMap().entries.sortedBy { it.key.name }) {
                val (type, element) = when (value) {
                    is Boolean -> "boolean" to JsonPrimitive(value)
                    is Int -> "int" to JsonPrimitive(value)
                    is Long -> "long" to JsonPrimitive(value)
                    is Float -> "float" to JsonPrimitive(value)
                    is Double -> "double" to JsonPrimitive(value)
                    is String -> "string" to JsonPrimitive(value)
                    is Set<*> -> "string_set" to JsonArray(value.map { JsonPrimitive(it as String) })
                    is ByteArray -> "bytes" to JsonPrimitive(Base64.getEncoder().encodeToString(value))
                    else -> error("Setting ${key.name} has an unsupported type: ${value::class}")
                }
                putJsonObject(key.name) {
                    put(TYPE, JsonPrimitive(type))
                    put(VALUE, element)
                }
            }
        }
        sink.writeUtf8(json.encodeToString(JsonObject.serializer(), root) + "\n")
    }
}
