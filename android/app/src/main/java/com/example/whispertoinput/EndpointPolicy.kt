package com.example.whispertoinput

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** Policy is applied both when saving settings and immediately before every request. */
object EndpointPolicy {
    fun validate(settings: AppSettings): HttpUrl = validate(
        settings.endpoint, settings.allowInsecure, settings.apiKey
    )

    fun validate(endpoint: String, allowInsecure: Boolean, apiKey: String = ""): HttpUrl {
        if (endpoint.isBlank() || endpoint.any { it.isWhitespace() || it.isISOControl() } ||
            '\\' in endpoint) {
            throw TranscriptionException("Enter a valid absolute HTTP or HTTPS transcription URL.")
        }
        val uri = try { URI(endpoint) } catch (_: Exception) {
            throw TranscriptionException("Enter a valid absolute HTTP or HTTPS transcription URL.")
        }
        val url = endpoint.toHttpUrlOrNull()
            ?: throw TranscriptionException("Enter a valid absolute HTTP or HTTPS transcription URL.")
        if (!uri.isAbsolute || uri.rawAuthority == null || uri.rawUserInfo != null ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || uri.rawFragment != null) {
            throw TranscriptionException("Endpoint URLs must not contain credentials or fragments.")
        }
        val credentialParameters = setOf("key", "api_key", "apikey", "token", "access_token", "authorization", "password")
        if (url.queryParameterNames.any { it.lowercase().replace('-', '_') in credentialParameters }) {
            throw TranscriptionException("Put API credentials in the API key field, not the endpoint URL.")
        }
        if (apiKey.length > 4096 || apiKey.any { it.code !in 33..126 }) {
            throw TranscriptionException("The API key contains unsupported characters.")
        }
        if (!url.isHttps) {
            if (!allowInsecure || url.host != "gx10") {
                throw TranscriptionException("HTTPS is required except for the explicitly enabled gx10 host.")
            }
            if (apiKey.isNotEmpty()) {
                throw TranscriptionException("API keys cannot be sent over HTTP. Use HTTPS or remove the key.")
            }
        }
        // Preserve an existing query verbatim through HttpUrl; never append ASR-specific parameters.
        return url
    }
}

/** Messages in this exception are fixed client strings, never untrusted server/error text. */
class TranscriptionException(message: String) : Exception(message)
