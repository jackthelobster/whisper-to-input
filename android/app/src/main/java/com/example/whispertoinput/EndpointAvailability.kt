package com.example.whispertoinput

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Checks never upload audio, follow redirects, or reuse the main endpoint's key for fallback. */
class EndpointAvailability(
    private val probe: suspend (AppSettings) -> Boolean = EndpointProbe()::isAvailable
) {
    suspend fun select(settings: AppSettings): AppSettings? {
        if (probe(settings)) return settings
        if (settings.fallbackEndpoint.isBlank()) return null
        val fallback = settings.copy(
            endpoint = settings.fallbackEndpoint, apiKey = settings.fallbackApiKey,
            fallbackEndpoint = "", fallbackApiKey = ""
        )
        return fallback.takeIf { probe(it) }
    }
}

internal class EndpointProbe(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
    .callTimeout(4, TimeUnit.SECONDS).build()) {
    init { require(!client.followRedirects && !client.followSslRedirects) }

    suspend fun isAvailable(settings: AppSettings): Boolean {
        try {
            val url = EndpointPolicy.validate(settings)
            val path = url.encodedPath
            if (!path.endsWith("/audio/transcriptions")) return false
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
                        if (continuation.isActive) continuation.resume(false)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val available = response.use {
                            if (!it.isSuccessful || !continuation.isActive) false else try {
                                val data = JSONObject(it.peekBody(64 * 1024).string()).optJSONArray("data")
                                data != null && (0 until data.length()).any { index ->
                                    data.optJSONObject(index)?.optString("id") == settings.model
                                }
                            } catch (_: Exception) { false }
                        }
                        if (continuation.isActive) continuation.resume(available)
                    }
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
    }
}
