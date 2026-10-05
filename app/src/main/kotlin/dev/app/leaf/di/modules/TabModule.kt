package dev.app.leaf.di.modules

import dev.app.leaf.common.TabScope
import dev.app.leaf.domain.TabCoroutineScope
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
class TabModule {    @TabScope
    @Provides
    fun provideTabCoroutineScope() = TabCoroutineScope()
}
