package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.models.ProxySettings

interface SystemProxyRepository {
    suspend fun setProxy(proxySettings: ProxySettings)
    suspend fun clearProxy()
}