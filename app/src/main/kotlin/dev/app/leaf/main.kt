package dev.app.leaf

import dev.app.leaf.data.git.disableJGitAutoGc
import dev.app.leaf.data.repositories.configuration.initPreferencesPath
import dev.app.leaf.di.DaggerAppComponent


suspend fun main(args: Array<String>) {
    if (args.contains("--graalvm")) {
        val currentDir = System.getProperty("user.dir")

        System.setProperty("java.home", currentDir)
    }

    initPreferencesPath()
    disableJGitAutoGc()

    val app: App = DaggerAppComponent
        .create()
        .app()

    app.start(args)
}
