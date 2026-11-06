// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.window.linux

import androidx.compose.runtime.Composable
import dev.app.leaf.app.generated.resources.Res
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_not_jetbrains_runtime
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_other_toolkit
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_restart
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_subtitle
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_tiling
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_title
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_unknown_desktop
import dev.app.leaf.app.generated.resources.settings_linux_title_bar_wsl
import dev.app.leaf.ui.dialogs.settings.SettingToggle
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Appearance → "Tabs in the title bar", on Linux only (fork-only, docs/fork/window-chrome.md, section 10).
 * Where the desktop doesn't allow Leaf's own frame, the row is greyed out and says why. A change shows when Leaf starts
 * again, which the row says while the setting and the window differ ([isDrawnNow]).
 */
@Composable
fun LinuxTitleBarRow(
    isOn: Boolean,
    support: LinuxFrameSupport,
    isDrawnNow: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val subtitle = when (support) {
        LinuxFrameSupport.Supported -> {
            val description = stringResource(Res.string.settings_linux_title_bar_subtitle)

            if (isOn != isDrawnNow) "$description ${stringResource(Res.string.settings_linux_title_bar_restart)}" else description
        }

        is LinuxFrameSupport.Unsupported -> stringResource(
            when (support.reason) {
                LinuxFrameSupport.Reason.NOT_JETBRAINS_RUNTIME -> Res.string.settings_linux_title_bar_not_jetbrains_runtime
                LinuxFrameSupport.Reason.OTHER_TOOLKIT -> Res.string.settings_linux_title_bar_other_toolkit
                LinuxFrameSupport.Reason.WSL -> Res.string.settings_linux_title_bar_wsl
                LinuxFrameSupport.Reason.TILING_WINDOW_MANAGER -> Res.string.settings_linux_title_bar_tiling
                LinuxFrameSupport.Reason.UNKNOWN_DESKTOP -> Res.string.settings_linux_title_bar_unknown_desktop
            }
        )
    }

    SettingToggle(
        title = stringResource(Res.string.settings_linux_title_bar_title),
        subtitle = subtitle,
        enabled = support == LinuxFrameSupport.Supported,
        value = isOn,
        onValueChanged = onChange,
    )
}
