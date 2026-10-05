package dev.app.leaf

import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.storage.AppStorage
import dev.app.leaf.domain.extensions.openDirectory
import org.apache.log4j.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogsRepository @Inject constructor() {
    val logsDirectory by lazy {
        val directory = when (currentOs) {
            OS.LINUX -> linuxLogsDirectory()
            OS.WINDOWS -> windowsLogsDirectory()
            OS.MAC -> macLogsDirectory()
            OS.UNKNOWN -> defaultLogsPath()
        }

        if (!directory.exists()) {
            if (directory.isFile) {
                directory.delete()
            }

            directory.mkdirs()
        }

        directory
    }

    fun initLogging() {
        val layout = PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n")

        val filePath = logsFile()

        val fileAppender = RollingFileAppender(layout, filePath, true)
        fileAppender.maximumFileSize = 10 * 1024 * 1024 // 10MB
        fileAppender.maxBackupIndex = 5

        val consoleAppender = ConsoleAppender(layout)

        LogManager.getRootLogger().apply {
            addAppender(fileAppender)
            addAppender(consoleAppender)
            level = Level.INFO
        }
    }

    private fun defaultLogsPath(): File {
        val homePath = System.getProperty("user.home").orEmpty()

        return File("$homePath/${AppStorage.current.directoryName}/")
    }

    private fun macLogsDirectory(): File {
        val logsDir = File(System.getProperty("user.home") + "/Library/Logs/")
            .openDirectory(AppStorage.current.macLogsDirectoryName)

        return logsDir
    }

    private fun windowsLogsDirectory(): File {
        val localAppData = System.getenv("LOCALAPPDATA")

        val appDir = File(localAppData).openDirectory(AppStorage.current.directoryName)
        val logsDir = appDir.openDirectory("logs")

        return logsDir
    }

    private fun linuxLogsDirectory(): File {
        // Based on this https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
        val homePath = System.getProperty("user.home")
        val xdgStateHome = System.getenv("XDG_STATE_HOME")

        val safeXdgStateHome = if (xdgStateHome.isNullOrBlank())
            "$homePath/.local/state"
        else
            xdgStateHome

        val appDir = File(safeXdgStateHome).openDirectory(AppStorage.current.directoryName)
        val logsDir = appDir.openDirectory("logs")

        return logsDir
    }

    private fun logsFile(): String {
        val file = File(logsDirectory, AppStorage.LOG_FILE_NAME)

        return file.absolutePath
    }
}
