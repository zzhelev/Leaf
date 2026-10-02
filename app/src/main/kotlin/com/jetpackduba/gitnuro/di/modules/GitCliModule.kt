package com.jetpackduba.gitnuro.di.modules

import com.jetpackduba.gitnuro.data.git.cli.GitExecutableLocator
import com.jetpackduba.gitnuro.domain.gitcli.IGitExecutableLocator
import dagger.Binds
import dagger.Module

@Module
interface GitCliModule {
    @Binds
    fun gitExecutableLocator(locator: GitExecutableLocator): IGitExecutableLocator
}
