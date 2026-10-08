package dev.app.leaf.updates

import dev.app.leaf.AppConstants
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val updateJson = Json {
    this.ignoreUnknownKeys = true
}

private const val TAG = "UpdatesRepository"

@Singleton
class UpdatesRepository(
    private val httpClient: HttpClient,
    private val versionCheckUrl: String,
    private val currentVersionCode: Int,
    private val checkInterval: Duration,
    scope: CoroutineScope,
) {
    @Inject
    constructor(httpClient: HttpClient) : this(
        httpClient = httpClient,
        versionCheckUrl = AppConstants.VERSION_CHECK_URL,
        currentVersionCode = AppConstants.APP_VERSION_CODE,
        checkInterval = 5.minutes,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /**
     * The latest release when it is newer than this build. One check for the whole app, which every tab shows: it
     * starts when the first tab looks at it and repeats every [checkInterval]. A failed check is logged and keeps the
     * last answer.
     */
    val update: StateFlow<Update?> = flow {
        while (true) {
            val latestRelease = try {
                fetchLatestRelease()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                printError(TAG, "Checking for updates failed: $e")
                null
            }

            if (latestRelease != null && latestRelease.appCode > currentVersionCode) {
                emit(latestRelease)
            }

            delay(checkInterval)
        }
    }.stateIn(scope, SharingStarted.Lazily, null)

    private suspend fun fetchLatestRelease(): Update? {
        printLog(TAG, "Checking for new updates in $versionCheckUrl")

        val response = httpClient.get(versionCheckUrl)

        check(response.status.isSuccess()) { "$versionCheckUrl answered ${response.status}" }

        return updateJson.decodeFromString<Update?>(response.bodyAsText())
    }
}
