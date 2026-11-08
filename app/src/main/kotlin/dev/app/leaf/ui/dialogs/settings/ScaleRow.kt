// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.UiScale
import dev.app.leaf.ui.dropdowns.DropDownOption
import dev.app.leaf.viewmodels.SettingsAction
import kotlin.math.roundToInt

/** The Scale row's choices, on top of the screen's own scale, with a [stored] one that the list lacks in its place. */
internal fun scaleOptions(stored: Float): List<DropDownOption<Float>> =
    (UiScale.CHOICES + stored).distinct().sorted().map { scale ->
        val percent = "${(scale * 100).roundToInt()}%"
        DropDownOption(scale, if (scale == UiScale.DEFAULT) "$percent (default)" else percent)
    }

/** Settings → Appearance → Scale (fork-only since 2026-11-08: on top of the screen's own scale, see [UiScale]). */
@Composable
internal fun ScaleRow(stored: Float, onAction: (SettingsAction) -> Unit) {
    val options = remember(stored) { scaleOptions(stored) }

    SettingDropDown(
        title = "Scale",
        subtitle = "The size of everything: text, icons and spacing",
        dropDownOptions = options,
        currentOption = stored,
        onOptionSelected = { onAction(SettingsAction.SetConfig(AppConfig.ScaleUi(it.value))) },
    )
}
