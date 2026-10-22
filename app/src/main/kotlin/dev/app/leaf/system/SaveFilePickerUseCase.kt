// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.system

import dev.app.leaf.common.currentOs
import dev.app.leaf.domain.ShellManager
import java.awt.FileDialog
import java.io.File
import javax.inject.Inject
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Asks where to save a file (fork-only), as [OpenFilePickerUseCase] asks for one to open: the system's save dialog on
 * macOS, zenity's on Linux when it's installed, Swing's otherwise. Gives the path picked, or null when cancelled.
 */
class SaveFilePickerUseCase @Inject constructor(
    private val shellManager: ShellManager,
) {
    operator fun invoke(title: String, fileName: String): String? = when {
        currentOs.isMac() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            val dialog = FileDialog(null as java.awt.Frame?, title, FileDialog.SAVE).apply { file = fileName }
            dialog.isVisible = true

            if (dialog.file != null && dialog.directory != null) dialog.directory + dialog.file else null
        }

        currentOs.isLinux() && !shellManager.runCommand(listOf("which", "zenity")).isNullOrEmpty() ->
            shellManager.runCommand(
                listOf("zenity", "--file-selection", "--save", "--confirm-overwrite", "--title=$title", "--filename=$fileName")
            )?.trim()?.ifEmpty { null }

        else -> {
            if (!currentOs.isLinux()) UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())

            val chooser = JFileChooser().apply {
                dialogTitle = title
                selectedFile = File(fileName)
            }

            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
        }
    }
}
