// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonPreferencesSerializerTest {

    private fun write(preferences: Preferences): String = runBlocking {
        val buffer = Buffer()
        JsonPreferencesSerializer.writeTo(preferences, buffer)
        buffer.readUtf8()
    }

    private fun read(text: String): Preferences = runBlocking {
        JsonPreferencesSerializer.readFrom(Buffer().writeUtf8(text))
    }

    @Test
    fun `every value type reads back with the same type and value`() {
        val original = preferencesOf(
            booleanPreferencesKey("boolean") to true,
            intPreferencesKey("int") to 42,
            longPreferencesKey("long") to Long.MAX_VALUE,
            floatPreferencesKey("float") to 1.25f,
            doublePreferencesKey("double") to 0.1,
            stringPreferencesKey("string") to "Leaf \"quoted\" \\ ü\n",
            stringSetPreferencesKey("string_set") to setOf("a", "b", ""),
        )

        assertEquals(original, read(write(original)))
    }

    @Test
    fun `byte arrays read back`() {
        val bytes = byteArrayOf(0, 1, -1, 127, -128)
        val read = read(write(preferencesOf(byteArrayPreferencesKey("bytes") to bytes)))

        assertArrayEquals(bytes, read[byteArrayPreferencesKey("bytes")])
    }

    @Test
    fun `special floating point values read back`() {
        val original = preferencesOf(
            floatPreferencesKey("nan") to Float.NaN,
            doublePreferencesKey("infinity") to Double.POSITIVE_INFINITY,
        )
        val read = read(write(original))

        assertTrue(read[floatPreferencesKey("nan")]!!.isNaN())
        assertEquals(Double.POSITIVE_INFINITY, read[doublePreferencesKey("infinity")])
    }

    @Test
    fun `the file is readable JSON that records each type`() {
        val text = write(
            preferencesOf(floatPreferencesKey("scale_ui") to 1.5f, stringPreferencesKey("theme") to "DARK"),
        )

        assertTrue(text.contains("\"scale_ui\""), text)
        assertTrue(text.contains("\"type\": \"float\""), text)
        assertTrue(text.contains("\"value\": \"DARK\""), text)
    }

    @Test
    fun `an empty or blank file is empty settings`() {
        assertEquals(emptyPreferences(), read(""))
        assertEquals(emptyPreferences(), read("  \n"))
    }

    @Test
    fun `invalid JSON is a CorruptionException`() {
        assertThrows<CorruptionException> { read("{ not json") }
    }

    @Test
    fun `an unknown type is a CorruptionException`() {
        assertThrows<CorruptionException> { read("""{"x": {"type": "colour", "value": "red"}}""") }
    }

    @Test
    fun `a value of the wrong type is a CorruptionException`() {
        assertThrows<CorruptionException> { read("""{"x": {"type": "boolean", "value": "maybe"}}""") }
        assertThrows<CorruptionException> { read("""{"x": {"type": "int", "value": "many"}}""") }
    }

    @Test
    fun `a setting without a value is a CorruptionException`() {
        assertThrows<CorruptionException> { read("""{"x": {"type": "string"}}""") }
    }
}
