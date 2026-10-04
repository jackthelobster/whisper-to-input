package com.example.whispertoinput

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

object TranscriptionProtocol {
    const val MAX_RESPONSE_BYTES = 1024 * 1024
    const val MAX_AUDIO_BYTES = 25L * 1024 * 1024

    fun validateSettings(settings: AppSettings) {
        EndpointPolicy.validate(settings)
        if (settings.model.isBlank() || settings.model.length > 4096 ||
            settings.model.any { it.isISOControl() }) {
            throw TranscriptionException("Enter a non-empty transcription model.")
        }
        if (settings.language.length > 64 || settings.language.any { it.isISOControl() }) {
            throw TranscriptionException("Enter a valid language code, or leave language blank for detection.")
        }
    }

    fun buildRequest(settings: AppSettings, audio: File, mediaType: String): Request {
        validateSettings(settings)
        if (!audio.isFile || !audio.canRead() || audio.length() == 0L) {
            throw TranscriptionException("The recording is missing or empty. Record audio and try again.")
        }
        if (audio.length() > MAX_AUDIO_BYTES) {
            throw TranscriptionException("The recording exceeds the 25 MiB upload limit. Use a shorter recording.")
        }
        val type = mediaType.toMediaTypeOrNull()
            ?: throw TranscriptionException("Unsupported recording media type.")
        if (type.type != "audio") throw TranscriptionException("Unsupported recording media type.")
        val uploadName = when (type.subtype) {
            "ogg" -> "audio.ogg"
            "wav", "x-wav" -> "audio.wav"
            "mpeg", "mp3" -> "audio.mp3"
            "webm" -> "audio.webm"
            else -> "audio.m4a"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", uploadName, audio.asRequestBody(type))
            .addFormDataPart("model", settings.model)
            .addFormDataPart("response_format", "json")
            .apply {
                if (settings.language.isNotBlank()) addFormDataPart("language", settings.language)
            }.build()
        return Request.Builder().url(EndpointPolicy.validate(settings))
            .header("Accept", "application/json, text/plain")
            .apply {
                if (settings.apiKey.isNotEmpty()) header("Authorization", "Bearer ${settings.apiKey}")
            }
            // OkHttp owns Content-Type, including the generated multipart boundary.
            .post(body).build()
    }

    fun parseText(body: String, contentType: String? = null): String {
        val text = body.trim().removePrefix("\uFEFF").trim()
        val jsonContent = contentType?.substringBefore(';')?.trim()?.lowercase()?.let {
            it == "application/json" || it.endsWith("+json")
        } == true
        val result = if (jsonContent || text.startsWith("{") || text.startsWith("[")) {
            try {
                // optString coerces numbers/objects; transcription text must actually be a string.
                val value = JSONObject(text).opt("text")
                if (value !is String) throw TranscriptionException("The server returned no transcription text.")
                value.trim()
            } catch (e: TranscriptionException) {
                throw e
            } catch (_: Exception) {
                throw TranscriptionException("The server returned an invalid transcription response.")
            }
        } else {
            if (contentType?.contains("html", ignoreCase = true) == true || text.startsWith("<")) {
                throw TranscriptionException("The server returned an invalid transcription response.")
            }
            text
        }
        if (result.isBlank()) throw TranscriptionException("No speech was recognized. Try recording again.")
        return result
    }

    fun httpError(code: Int): TranscriptionException = TranscriptionException(when (code) {
        in 300..399 -> "The endpoint redirected the request. Configure its final HTTPS URL; redirects are disabled."
        401, 403 -> "The server rejected authentication. Check the API key and permissions."
        404 -> "The transcription endpoint was not found. Check the full endpoint URL."
        413 -> "The server rejected the recording size. Use a shorter recording."
        429 -> "The server is busy or rate-limited. Try again later."
        in 500..599 -> "The transcription server is unavailable. Try again later."
        else -> "The server rejected the transcription request (HTTP $code). Check the model and language."
    })
}
