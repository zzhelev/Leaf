// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.di.modules

import dev.app.leaf.data.git.cli.GitExecutableLocator
import dev.app.leaf.domain.gitcli.IGitExecutableLocator
import dagger.Binds
import dagger.Module

@Module
interface GitCliModule {
    @Binds
    fun gitExecutableLocator(locator: GitExecutableLocator): IGitExecutableLocator
}
