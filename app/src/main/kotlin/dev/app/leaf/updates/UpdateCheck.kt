// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

/** What one check for updates found. */
sealed interface UpdateCheck {
    /** [update] is newer than this build. */
    data class Available(val update: Update) : UpdateCheck

    /** The latest release is this build or an older one. */
    data object UpToDate : UpdateCheck

    /** The check got no release. [reason] says why, for the user. */
    data class Failed(val reason: String) : UpdateCheck
}
