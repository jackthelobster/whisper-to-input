/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class WhisperTranscriber {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Main)
    private val client = TranscriptionClient()
    private val lifecycleLock = Any()
    private var currentTranscriptionJob: Job? = null

    fun startAsync(
        context: Context,
        filename: String,
        mediaType: String,
        attachToEnd: String,
        callback: (String?) -> Unit,
        exceptionCallback: (String) -> Unit
    ) {
        val application = context.applicationContext
        val audio = File(filename)
        synchronized(lifecycleLock) {
            if (!supervisor.isActive) return
            currentTranscriptionJob?.cancel()
            val lease = RecordingLease.acquire(audio)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    withContext(Dispatchers.IO) {
                        val settings = SettingsRepository(application).load()
                        val text = client.transcribe(settings, audio, mediaType)
                        coroutineContext.ensureActive()
                        // HTTP success is not editor insertion success. The service owns deletion
                        // after commitText succeeds (or the user cancels/leaves the editor), so keep
                        // this recording available for retry even after this job releases its lease.
                        text + when {
                            attachToEnd.isNotEmpty() -> attachToEnd
                            settings.trailingSpace -> " "
                            else -> ""
                        }
                    }
                } catch (e: CancellationException) {
                    // Propagate cancellation: it cancels the actual OkHttp Call and emits no callbacks.
                    throw e
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    val message = if (e is TranscriptionException) e.message!!
                        else "Transcription failed. Check your settings and try again."
                    callback(null)
                    coroutineContext.ensureActive()
                    exceptionCallback(message)
                    return@launch
                }
                coroutineContext.ensureActive()
                callback(result)
            }
            currentTranscriptionJob = job
            job.invokeOnCompletion {
                lease.release()
                synchronized(lifecycleLock) {
                    if (currentTranscriptionJob === job) currentTranscriptionJob = null
                }
            }
            job.start()
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        currentTranscriptionJob?.cancel()
        currentTranscriptionJob = null
    }

    /** Call from service destruction; stop() remains reusable for subsequent recordings. */
    fun close() = synchronized(lifecycleLock) {
        currentTranscriptionJob?.cancel()
        currentTranscriptionJob = null
        scope.cancel()
    }

    fun destroy() = close()
}
