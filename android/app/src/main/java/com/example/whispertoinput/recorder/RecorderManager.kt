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

package com.example.whispertoinput.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.whispertoinput.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Main-thread owned recorder. A stopped file transfers to the caller; cancel never retains it. */
class RecorderManager(context: Context) {
    companion object {
        private const val MAX_DURATION_MS = 120_000
        private const val MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L
        private const val FILE_PREFIX = "dictation-"

        // Notifications are not needed to use a visible IME's microphone, on any API level.
        fun requiredPermissions() = arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recordingDirectory = File(this.context.cacheDir, "recordings")
    private val amplitudeReportPeriod =
        context.resources.getInteger(R.integer.recorder_amplitude_report_period).toLong().coerceAtLeast(50L)
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var amplitudeJob: Job? = null
    private var durationJob: Job? = null
    private var limitDispatched = false
    private var closed = false
    private var onUpdateMicrophoneAmplitude: (Int) -> Unit = { }
    private var onLimitReached: () -> Unit = { }
    private var onRecordingError: () -> Unit = { }

    val isRecording: Boolean get() = recorder != null

    init {
        // The service creates exactly one manager. Files left by a killed previous service have
        // no retry owner, so purge them on creation rather than retaining sensitive audio overnight.
        runCatching {
            recordingDirectory.listFiles()?.filter {
                it.isFile && it.name.startsWith(FILE_PREFIX)
            }?.forEach { it.delete() }
        }
    }

    /** Returns a unique, app-private M4A file only after prepare/start both succeed. */
    fun start(): File? {
        cancel()
        if (closed || !allPermissionsGranted(context)) return null
        // Samsung devices normally accept 16 kHz AAC; retry a fresh recorder at 44.1 kHz when not.
        for (sampleRate in intArrayOf(16_000, 44_100)) {
            var candidate: MediaRecorder? = null
            var file: File? = null
            try {
                check(recordingDirectory.isDirectory || recordingDirectory.mkdirs())
                val output = File.createTempFile(FILE_PREFIX, ".m4a", recordingDirectory)
                file = output
                val newRecorder = if (Build.VERSION.SDK_INT >= 31) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                candidate = newRecorder
                newRecorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1)
                    setAudioSamplingRate(sampleRate)
                    setAudioEncodingBitRate(64_000)
                    setMaxDuration(MAX_DURATION_MS)
                    setMaxFileSize(MAX_FILE_SIZE_BYTES)
                    setOutputFile(output.absolutePath)
                    setOnInfoListener { source, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                            dispatchLimit(source)
                        }
                    }
                    setOnErrorListener { source, _, _ ->
                        scope.launch {
                            if (recorder === source) {
                                cancel()
                                onRecordingError()
                            }
                        }
                    }
                    prepare()
                    start()
                }
                recorder = newRecorder
                recordingFile = output
                limitDispatched = false
                val startedRecorder = newRecorder
                amplitudeJob = scope.launch {
                    while (isActive && recorder === startedRecorder) {
                        val amplitude = try {
                            startedRecorder.maxAmplitude
                        } catch (_: RuntimeException) {
                            0
                        }
                        onUpdateMicrophoneAmplitude(amplitude)
                        delay(amplitudeReportPeriod)
                    }
                }
                // Native limits enforce the bound even if the main thread is delayed. This timer
                // also finishes recording if a vendor omits its OnInfo notification.
                durationJob = scope.launch {
                    delay(MAX_DURATION_MS.toLong())
                    dispatchLimit(startedRecorder)
                }
                return output
            } catch (_: Exception) {
                if (recorder === candidate) {
                    recorder = null
                    recordingFile = null
                    cancelJobs()
                }
                release(candidate)
                file?.delete()
            }
        }
        return null
    }

    /** A too-short/invalid stop is not an uploadable recording. Always release the native object. */
    fun stop(): File? {
        val activeRecorder = recorder ?: return null
        val file = recordingFile
        recorder = null
        recordingFile = null
        cancelJobs()
        val stopped = try {
            activeRecorder.stop()
            true
        } catch (_: RuntimeException) {
            false
        } finally {
            release(activeRecorder)
        }
        if (!stopped || file == null || !file.isFile || file.length() == 0L ||
            file.length() > MAX_FILE_SIZE_BYTES) {
            file?.delete()
            return null
        }
        return file
    }

    fun cancel() {
        val file = recordingFile
        // stop may already delete an invalid file; deleting the exact captured file is idempotent.
        stop()?.delete()
        file?.delete()
        cancelJobs()
    }

    fun close() {
        closed = true
        cancel()
        scope.cancel()
        onUpdateMicrophoneAmplitude = { }
        onLimitReached = { }
        onRecordingError = { }
    }

    private fun dispatchLimit(source: MediaRecorder) {
        scope.launch {
            if (recorder === source && !limitDispatched) {
                limitDispatched = true
                onLimitReached()
                // A missing/rejected owner callback must not leave the microphone running.
                if (recorder === source) cancel()
            }
        }
    }

    private fun cancelJobs() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        durationJob?.cancel()
        durationJob = null
    }

    private fun release(value: MediaRecorder?) {
        if (value == null) return
        runCatching { value.setOnInfoListener(null) }
        runCatching { value.setOnErrorListener(null) }
        runCatching { value.release() }
    }

    fun setOnUpdateMicrophoneAmplitude(callback: (Int) -> Unit) {
        onUpdateMicrophoneAmplitude = callback
    }

    fun setOnLimitReached(callback: () -> Unit) {
        onLimitReached = callback
    }

    fun setOnRecordingError(callback: () -> Unit) {
        onRecordingError = callback
    }

    fun allPermissionsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}
