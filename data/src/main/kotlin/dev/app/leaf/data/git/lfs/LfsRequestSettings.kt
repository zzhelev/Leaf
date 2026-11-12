// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import dev.app.leaf.data.network.ProxySettings
import dev.app.leaf.data.network.isSslVerify
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.network.HttpRequestSettings
import dev.app.leaf.domain.network.ProxyChoice
import org.eclipse.jgit.lib.Repository

/**
 * What a request of [repository]'s to [url] needs: `http.sslVerify` and the proxy that git-lfs would use, or the error
 * for a proxy that Leaf can't use, so that the request doesn't go around it.
 */
internal suspend fun ProxySettings.lfsRequestSettings(
    repository: Repository,
    url: String,
): Either<HttpRequestSettings, LfsError> {
    val config = repository.config

    return when (val proxy = forRequest(config, url)) {
        is ProxyChoice.Unusable -> Either.Err(LfsError.UnusableProxy(proxy))
        else -> Either.Ok(HttpRequestSettings(config.isSslVerify(url), proxy))
    }
}
