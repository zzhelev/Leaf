package dev.app.leaf.domain.usecases

import dev.app.leaf.common.printError
import java.awt.Desktop
import java.io.File
import javax.inject.Inject

private const val TAG = "SystemUtils"

/**
 * Opens a file with the default external app.
 * An example would be opening an image with the default image viewer
 */
class OpenFileInExternalAppUseCase @Inject constructor(
    private val openPathInSystemGitAction: OpenPathInSystemUseCase,
) {
    operator fun invoke(filePath: String) {
        if (!openPathInSystemGitAction(filePath)) {
            openFileJdk(filePath)
        }
    }

    private fun openFileJdk(filePath: String) {
        try {
            Desktop.getDesktop().open(File(filePath))
        } catch (ex: Exception) {
            printError(TAG, "Failed to open URL in browser")
            ex.printStackTrace()
        }
    }
}
