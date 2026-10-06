// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Moves the settings from DataStore's protobuf file into the JSON store written by [JsonPreferencesSerializer], then
 * renames the old file to `<name>.migrated`.
 *
 * Reading the protobuf file goes through DataStore's bundled protobuf, so JDK 25 prints its `sun.misc.Unsafe` warning
 * on the launch that migrates, and never again.
 */
class ProtobufPreferencesMigration(
    private val protobufFile: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = fileSystem.exists(protobufFile)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val bytes = fileSystem.read(protobufFile) { readByteString() }
        val old = PreferencesSerializer.readFrom(Buffer().write(bytes))
        // Values already in the JSON store win, in case a previous migration was interrupted after writing them.
        return old.toMutablePreferences().apply { plusAssign(currentData) }.toPreferences()
    }

    override suspend fun cleanUp() {
        fileSystem.atomicMove(protobufFile, "$protobufFile.migrated".toPath())
    }
}
