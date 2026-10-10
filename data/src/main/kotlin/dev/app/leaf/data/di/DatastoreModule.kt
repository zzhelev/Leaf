package dev.app.leaf.data.di

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.app.leaf.common.printError
import dev.app.leaf.data.UserSettingsDataStore
import dev.app.leaf.data.repositories.configuration.JsonPreferencesSerializer
import dev.app.leaf.data.repositories.configuration.ProtobufPreferencesMigration
import dev.app.leaf.data.repositories.configuration.RemovedSettingsMigration
import dev.app.leaf.data.repositories.configuration.getPreferencesPath
import dev.app.leaf.data.repositories.configuration.getProtobufPreferencesPath
import dagger.Module
import dagger.Provides
import okio.FileSystem
import okio.Path.Companion.toPath
import javax.inject.Singleton

private const val TAG = "DatastoreModule"

@Module
class DatastoreModule {
    @Singleton
    @Provides
    fun provideDataStore(): UserSettingsDataStore {
        // Settings are stored as JSON rather than with DataStore's protobuf, which calls sun.misc.Unsafe.
        val preferences = PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = FileSystem.SYSTEM,
                serializer = JsonPreferencesSerializer,
                producePath = { getPreferencesPath().toPath() },
            ),
            corruptionHandler = ReplaceFileCorruptionHandler { e ->
                printError(TAG, "The settings file is damaged, so Leaf starts from the default settings", e)
                emptyPreferences()
            },
            migrations = listOf(
                ProtobufPreferencesMigration(getProtobufPreferencesPath().toPath()),
                // After it, so that the settings it moves are cleaned up too
                RemovedSettingsMigration(),
            ),
        )

        return UserSettingsDataStore(preferences)
    }
}
