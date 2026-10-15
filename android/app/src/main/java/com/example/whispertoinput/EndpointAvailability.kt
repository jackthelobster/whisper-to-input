package com.example.whispertoinput

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal sealed class ProbeResult {
    object Ready : ProbeResult()
    object Unreachable : ProbeResult()
    object Timeout : ProbeResult()
    data class HttpError(val code: Int) : ProbeResult()
    data class ModelMissing(val model: String) : ProbeResult()
    object InvalidResponse : ProbeResult()
    object InvalidEndpoint : ProbeResult()
}

internal data class EndpointSelection(
    val selected: AppSettings?,
    val primary: ProbeResult,
    val fallback: ProbeResult? = null
) {
    val timedOut: Boolean
        get() = primary == ProbeResult.Timeout || fallback == ProbeResult.Timeout
}

/** Checks never upload audio, follow redirects, or reuse the main endpoint's key for fallback. */
internal class EndpointAvailability(
    private val probe: suspend (AppSettings) -> ProbeResult = sharedProbe::check
) {
    suspend fun select(settings: AppSettings, checkBoth: Boolean = false): AppSettings? =
        inspect(settings, checkBoth).selected

    suspend fun inspect(settings: AppSettings, checkBoth: Boolean = false): EndpointSelection = coroutineScope {
        val primary = async { probe(settings) }
        val fallbackSettings = settings.takeIf { it.fallbackEndpoint.isNotBlank() }?.copy(
            endpoint = settings.fallbackEndpoint, apiKey = settings.fallbackApiKey,
            model = settings.fallbackModel, language = settings.fallbackLanguage,
            fallbackEndpoint = "", fallbackApiKey = ""
        )
        val fallback = fallbackSettings?.let { async { probe(it) } }
        val primaryResult = primary.await()
        // A dead optional fallback must not delay a healthy primary on keyboard open.
        if (primaryResult == ProbeResult.Ready && !checkBoth) {
            fallback?.cancel()
            return@coroutineScope EndpointSelection(settings, primaryResult)
        }
        val fallbackResult = fallback?.await()
        EndpointSelection(when {
            primaryResult == ProbeResult.Ready -> settings
            fallbackResult == ProbeResult.Ready -> fallbackSettings
            else -> null
        }, primaryResult, fallbackResult)
    }

    companion object {
        // One client and connection pool for the keyboard and settings screen, for this process.
        private val sharedProbe = EndpointProbe()
    }
}

internal class EndpointProbe(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS).build()) {
    init { require(!client.followRedirects && !client.followSslRedirects) }

    suspend fun isAvailable(settings: AppSettings): Boolean = check(settings) == ProbeResult.Ready

    suspend fun check(settings: AppSettings): ProbeResult {
        try {
            val url = EndpointPolicy.validate(settings)
            val path = url.encodedPath
            if (!path.endsWith("/audio/transcriptions")) return ProbeResult.InvalidEndpoint
            val models = url.newBuilder()
                .encodedPath(path.removeSuffix("audio/transcriptions") + "models").build()
            val request = Request.Builder().url(models).get().apply {
                if (settings.apiKey.isNotEmpty()) header("Authorization", "Bearer ${settings.apiKey}")
            }.build()
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resume(e.probeResult())
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = response.use {
                            if (!it.isSuccessful) ProbeResult.HttpError(it.code) else try {
                                val data = JSONObject(it.peekBody(64 * 1024).string()).optJSONArray("data")
                                when {
                                    data == null -> ProbeResult.InvalidResponse
                                    (0 until data.length()).any { index ->
                                        data.optJSONObject(index)?.optString("id") == settings.model
                                    } -> ProbeResult.Ready
                                    else -> ProbeResult.ModelMissing(settings.model)
                                }
                            } catch (e: IOException) { e.probeResult()
                            } catch (_: Exception) { ProbeResult.InvalidResponse }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ProbeResult.InvalidEndpoint
        }
    }
}

private fun IOException.probeResult(): ProbeResult =
    if (this is InterruptedIOException) ProbeResult.Timeout else ProbeResult.Unreachable
