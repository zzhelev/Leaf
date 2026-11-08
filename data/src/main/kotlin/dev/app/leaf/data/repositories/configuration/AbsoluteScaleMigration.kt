// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.repositories.configuration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.domain.models.ui.UiScale
import java.awt.GraphicsEnvironment

private const val TAG = "AbsoluteScaleMigration"

/** The Scale setting until 2026-11-08: the scale itself, the same on every screen. */
internal val absoluteScalePreference get() = floatPreferencesKey("scale_ui")

/** The Scale setting since 2026-11-08: on top of the screen's own scale. See [UiScale]. */
internal val relativeScalePreference get() = floatPreferencesKey("relative_scale")

/**
 * Turns a Scale setting stored before 2026-11-08 into one on top of the screen's own scale, once, so that Leaf keeps
 * the size it had on the screen that it starts on (fork-only). An older Leaf then finds no scale and follows the
 * screen. While the screen's scale can't be read (a headless run), the old setting stays and nothing reads it.
 */
class AbsoluteScaleMigration(
    private val screenScale: () -> Float? = ::mainScreenScale,
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.contains(absoluteScalePreference)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val preferences = currentData.toMutablePreferences()
        val absolute = preferences[absoluteScalePreference] ?: return currentData

        if (preferences.contains(relativeScalePreference)) { // Set again since, by a newer Leaf
            preferences.remove(absoluteScalePreference)
            return preferences.toPreferences()
        }

        val screen = screenScale() ?: return currentData
        val relative = UiScale.fromAbsolute(absolute, screen)

        printLog(TAG, "Scale $absolute on a screen at $screen is now ${relative ?: UiScale.DEFAULT} of the screen's")
        if (relative != null) preferences[relativeScalePreference] = relative
        preferences.remove(absoluteScalePreference)

        return preferences.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

/** The scale of the main screen, where Leaf's window opens, or null where there is none. */
private fun mainScreenScale(): Float? = try {
    if (GraphicsEnvironment.isHeadless()) {
        null
    } else {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
            .defaultTransform.scaleX.toFloat()
    }
} catch (e: Exception) {
    printError(TAG, "Couldn't read the screen's scale", e)
    null
}
