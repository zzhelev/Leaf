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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
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

    private val foundByHand = MutableSharedFlow<Update>(replay = 1)

    /**
     * The latest release when it is newer than this build. One check for the whole app, which every tab shows: it
     * starts when the first tab looks at it and repeats every [checkInterval]. An update that [checkNow] finds shows
     * here too. A failed check is logged and keeps the last answer.
     */
    val update: StateFlow<Update?> = merge(
        flow {
            while (true) {
                val check = check()

                if (check is UpdateCheck.Available) {
                    emit(check.update)
                }

                delay(checkInterval)
            }
        },
        foundByHand,
    ).stateIn(scope, SharingStarted.Lazily, null)

    /** Checks right away, for the user who asked. A newer release also becomes [update]. */
    suspend fun checkNow(): UpdateCheck {
        val check = check()

        if (check is UpdateCheck.Available) {
            foundByHand.emit(check.update)
        }

        return check
    }

    private suspend fun check(): UpdateCheck {
        val latestRelease = try {
            fetchLatestRelease()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            printError(TAG, "Checking for updates failed: $e" + e.cause?.let { ", caused by $it" }.orEmpty())

            return UpdateCheck.Failed(failureReason(e))
        }

        return if (latestRelease != null && latestRelease.appCode > currentVersionCode) {
            UpdateCheck.Available(latestRelease)
        } else {
            UpdateCheck.UpToDate
        }
    }

    private suspend fun fetchLatestRelease(): Update? {
        printLog(TAG, "Checking for new updates in $versionCheckUrl")

        val response = httpClient.get(versionCheckUrl)

        if (!response.status.isSuccess()) {
            throw UnexpectedAnswerException("$versionCheckUrl answered ${response.status}")
        }

        return try {
            updateJson.decodeFromString<Update?>(response.bodyAsText())
        } catch (e: SerializationException) {
            throw UnexpectedAnswerException("$versionCheckUrl didn't answer with a release", e)
        }
    }

    private fun failureReason(e: Exception): String {
        val host = Url(versionCheckUrl).host

        return when (e) {
            is UnexpectedAnswerException -> e.reason
            // Also what happens offline. The exception has no message.
            is UnresolvedAddressException, is UnknownHostException -> "Couldn't find the address of $host"
            else -> "Couldn't reach $host: ${e.message ?: e::class.simpleName}"
        }
    }
}

/** The server answered, but not with a release. */
private class UnexpectedAnswerException(val reason: String, cause: Throwable? = null) : Exception(reason, cause)
