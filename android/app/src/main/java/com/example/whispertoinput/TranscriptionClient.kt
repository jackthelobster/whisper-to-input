package com.example.whispertoinput

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The callback owns and closes every response; no response object escapes to a cancelled coroutine. */
class TranscriptionClient internal constructor(
    private val client: OkHttpClient = newHttpClient()
) {
    init {
        require(!client.followRedirects && !client.followSslRedirects) { "Redirects must be disabled." }
    }

    suspend fun transcribe(settings: AppSettings, audio: File, mediaType: String): String {
        val request = TranscriptionProtocol.buildRequest(settings, audio, mediaType)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkError(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!continuation.isActive) return
                            // Never display, log, or allocate an untrusted HTTP error body.
                            if (!it.isSuccessful) throw TranscriptionProtocol.httpError(it.code)
                            val body = it.body ?: throw TranscriptionException("The server returned an empty response.")
                            if (body.contentLength() > TranscriptionProtocol.MAX_RESPONSE_BYTES) {
                                throw TranscriptionException("The transcription response exceeded the size limit.")
                            }
                            val buffer = Buffer()
                            val source = body.source()
                            val max = TranscriptionProtocol.MAX_RESPONSE_BYTES.toLong()
                            while (buffer.size <= max) {
                                if (!continuation.isActive) return
                                val read = source.read(buffer, minOf(8192L, max + 1 - buffer.size))
                                if (read == -1L) break
                            }
                            if (buffer.size > max) {
                                throw TranscriptionException("The transcription response exceeded the size limit.")
                            }
                            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                            TranscriptionProtocol.parseText(buffer.readString(charset), body.contentType()?.toString())
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(when (e) {
                            is TranscriptionException -> e
                            is IOException -> networkError(e)
                            else -> TranscriptionException("The server returned an invalid transcription response.")
                        })
                    }
                }
            })
        }
    }

    companion object {
        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // Avoid transparently resending an upload after a connection failure.
            .retryOnConnectionFailure(false)
            .build()

        private fun networkError(error: IOException): TranscriptionException = TranscriptionException(when (error) {
            is SSLException -> "The secure connection failed. Check the server's HTTPS certificate."
            is InterruptedIOException -> "The transcription request timed out. Check the server and try again."
            else -> "Could not reach the transcription server. Check the endpoint and network connection."
        })
    }
}
