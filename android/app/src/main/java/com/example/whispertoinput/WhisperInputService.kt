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

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.example.whispertoinput.keyboard.WhisperKeyboard
import com.example.whispertoinput.recorder.RecorderManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import kotlinx.coroutines.flow.drop
import java.io.File

private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber = WhisperTranscriber()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorderManager: RecorderManager? = null
    private var settingsJob: Job? = null
    private val availability = EndpointAvailability()
    private val repository by lazy { SettingsRepository.get(this) }
    private var networkCallbackRegistered = false
    private var networkRefreshJob: Job? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshAfterNetworkChange()
        override fun onLost(network: Network) = refreshAfterNetworkChange()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshAfterNetworkChange()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refreshAfterNetworkChange()
    }

    private var generation = 0L
    private var requestGeneration = 0L
    private var windowShown = false
    private var inputViewActive = false
    private var keyboardReady = false
    private var destroyed = false
    private var editorInfo: EditorInfo? = null
    private var activeSession: EditorSession? = null

    // The connection is captured once. A callback must never look up a replacement editor to type.
    private class EditorSession(
        val generation: Long,
        val connection: InputConnection,
        val inputType: Int,
        val imeOptions: Int,
        val actionId: Int,
        val hasActionLabel: Boolean,
    ) {
        var settings: AppSettings? = null
        var selectedSettings: AppSettings? = null
        var userInteracted = false
        var endpointProbeTimedOut = false
        var audio: File? = null
        var transcribing = false
    }

    private fun refreshAfterNetworkChange() {
        serviceScope.launch {
            // Connectivity delivers availability, capabilities and routes in a burst.
            networkRefreshJob?.cancel()
            networkRefreshJob = serviceScope.launch refresh@{
                delay(150)
                val session = activeSession ?: return@refresh
                if (isCurrentDictationTarget(session) && !session.endpointProbeTimedOut) startAvailabilityChecks(session)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivity.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
        serviceScope.launch {
            repository.changes.drop(1).collect {
                val session = activeSession
                if (session != null && isCurrentDictationTarget(session)) startAvailabilityChecks(session)
            }
        }
        recorderManager = RecorderManager(this).apply {
            setOnUpdateMicrophoneAmplitude { amplitude ->
                val session = activeSession
                if (keyboardReady && session != null && isCurrentDictationTarget(session)) {
                    whisperKeyboard.updateMicrophoneAmplitude(amplitude)
                }
            }
            setOnLimitReached {
                val session = activeSession
                if (session != null && isCurrentDictationTarget(session) && isRecording) {
                    whisperKeyboard.tryStartTranscribing("")
                } else {
                    cancel()
                }
            }
            setOnRecordingError {
                val session = activeSession
                session?.audio?.delete()
                session?.audio = null
                if (session != null && isCurrentTarget(session)) {
                    showError("Recording failed. Please try again.")
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        invalidateSession()
        keyboardReady = false
        val view = whisperKeyboard.setup(
            layoutInflater,
            { onStartRecording() },
            { onCancelRecording() },
            { attachToEnd -> onStartTranscription(attachToEnd) },
            { onCancelTranscription() },
            { onDeleteText() },
            { onEnter() },
            { onSpaceBar() },
            { onSwitchIme() },
            { onOpenSettings() },
            { shouldShowRetry() },
            { onShiftEnter() },
            { onRefreshEndpoints() },
        )
        keyboardReady = true
        whisperKeyboard.setSensitiveInput(true)
        beginSessionIfReady()
        return view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        invalidateSession()
        inputViewActive = false
        editorInfo = attribute
        super.onStartInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        invalidateSession()
        editorInfo = info
        inputViewActive = true
        super.onStartInputView(info, restarting)
        beginSessionIfReady()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        windowShown = true
        beginSessionIfReady()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputViewActive = false
        invalidateSession()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        inputViewActive = false
        editorInfo = null
        invalidateSession()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        windowShown = false
        invalidateSession()
        super.onWindowHidden()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun beginSessionIfReady() {
        if (destroyed || !windowShown || !inputViewActive || !keyboardReady || activeSession != null) return
        val info = editorInfo ?: return
        val connection = currentInputConnection ?: return
        val session = EditorSession(
            generation, connection, info.inputType, info.imeOptions,
            info.actionId, info.actionLabel != null,
        )
        activeSession = session
        val allowed = EditorPolicy.allowsDictation(session.inputType, session.imeOptions)
        whisperKeyboard.setSensitiveInput(!allowed)
        whisperKeyboard.reset()
        whisperKeyboard.setEndpointStatus(R.string.testing_connection)
        if (!allowed) return
        startAvailabilityChecks(session)
    }

    private fun onRefreshEndpoints() {
        val session = activeSession ?: return
        if (!isCurrentDictationTarget(session)) return
        session.userInteracted = true
        startAvailabilityChecks(session, checkBoth = true)
    }

    private fun startAvailabilityChecks(session: EditorSession, checkBoth: Boolean = false) {
        settingsJob?.cancel()
        session.endpointProbeTimedOut = false
        whisperKeyboard.setEndpointStatus(R.string.testing_connection)
        settingsJob = serviceScope.launch {
            val schedule = EndpointPollSchedule()
            var checkAll = checkBoth
            while (isCurrentDictationTarget(session)) {
                val started = SystemClock.elapsedRealtime()
                val settings = try {
                    repository.load()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                if (!isCurrentDictationTarget(session)) return@launch
                whisperKeyboard.setShowShiftEnter(settings?.showShiftEnter == true)
                val result = settings?.let { availability.inspect(it, checkAll) }
                checkAll = false
                val selected = result?.selected
                if (!isCurrentDictationTarget(session)) return@launch
                session.settings = settings
                session.selectedSettings = selected
                session.endpointProbeTimedOut = result?.timedOut == true
                whisperKeyboard.setEndpointStatus(when {
                    selected == null -> R.string.endpoint_unavailable
                    selected.endpoint != settings.endpoint -> R.string.keyboard_ready_fallback
                    else -> R.string.keyboard_ready
                }, result?.statusText(this@WhisperInputService) ?: getString(R.string.settings_load_failed))
                // A late check must never restart a microphone after a manual gesture.
                if (selected != null && settings.autoRecord == true && !session.userInteracted &&
                    session.audio == null && !session.transcribing && recorderManager?.isRecording == false) {
                    whisperKeyboard.tryStartRecording()
                }
                // A timeout waits for an explicit refresh, settings change, or a new session.
                if (result?.timedOut == true) return@launch
                val interval = schedule.nextDelayMillis(selected != null)
                delay((interval - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1L))
            }
        }
    }

    private fun isCurrentTarget(session: EditorSession): Boolean =
        !destroyed && windowShown && inputViewActive && keyboardReady &&
            activeSession === session && generation == session.generation &&
            currentInputConnection === session.connection

    private fun isCurrentDictationTarget(session: EditorSession): Boolean {
        if (!isCurrentTarget(session) || !EditorPolicy.allowsDictation(session.inputType, session.imeOptions)) return false
        // Recheck the live flags as well as the snapshot: restartInput may mark an editor private.
        val info = currentInputEditorInfo ?: return false
        return EditorPolicy.allowsDictation(info.inputType, info.imeOptions)
    }

    private fun invalidateSession() {
        generation++
        requestGeneration++
        val previous = activeSession
        activeSession = null
        settingsJob?.cancel()
        settingsJob = null
        whisperTranscriber.stop()
        recorderManager?.cancel()
        previous?.audio?.delete()
        previous?.audio = null
        previous?.transcribing = false
        if (keyboardReady) {
            whisperKeyboard.setSensitiveInput(true)
            whisperKeyboard.reset()
        }
    }

    private fun onStartRecording() {
        val session = activeSession
        if (session == null || !isCurrentDictationTarget(session)) {
            showError("Dictation is unavailable in this field.")
            return
        }
        session.userInteracted = true
        val recorder = recorderManager ?: return
        if (!recorder.allPermissionsGranted(this)) {
            launchMainActivity()
            return
        }
        requestGeneration++
        whisperTranscriber.stop()
        session.transcribing = false
        recorder.cancel()
        session.audio?.delete()
        session.audio = recorder.start()
        if (session.audio == null) showError("Unable to start the microphone. Please try again.")
    }

    private fun onCancelRecording() {
        activeSession?.userInteracted = true
        discardAudio()
        resetKeyboard()
    }

    private fun onStartTranscription(attachToEnd: String) {
        val session = activeSession
        if (session == null || !isCurrentDictationTarget(session)) {
            discardAudio()
            showError("Dictation is unavailable in this field.")
            return
        }
        session.userInteracted = true
        if (session.transcribing) return
        val recorder = recorderManager ?: return
        if (recorder.isRecording) {
            session.audio = recorder.stop()
        }
        val audio = session.audio
        if (audio == null || !audio.isFile || audio.length() == 0L) {
            session.audio?.delete()
            session.audio = null
            showError("No usable audio was recorded. Please record again.")
            return
        }
        val selected = session.selectedSettings
        if (selected == null) {
            showError("No transcription endpoint is available. Retry after the next connection check.")
            return
        }
        val request = ++requestGeneration
        session.transcribing = true
        // Enter while recording means finish dictation, NOT submit a chat/message or insert an
        // actionable newline. Only an explicitly requested space is attached to transcription.
        val suffix = if (attachToEnd == " ") " " else ""
        try {
            whisperTranscriber.startAsync(
                selected, audio.absolutePath, AUDIO_MEDIA_TYPE_M4A, suffix,
                { text ->
                    serviceScope.launch {
                        if (!ownsRequest(session, request, audio)) return@launch
                        // The transcriber emits callback(null) before its error callback; let
                        // that error path finish the request instead of treating it as empty speech.
                        if (text == null) return@launch
                        val committed = EditorOperations.commitTranscription(session.connection, text, audio)
                        if (committed) {
                            session.transcribing = false
                            session.audio = null
                            resetKeyboard()
                            if (session.settings?.autoSwitch == true && isCurrentDictationTarget(session)) {
                                onSwitchIme()
                            }
                        } else {
                            session.transcribing = false
                            showError("No text was inserted. Please retry or record again.")
                        }
                    }
                },
                { message ->
                    serviceScope.launch {
                        if (!ownsRequest(session, request, audio)) return@launch
                        session.transcribing = false
                        // WhisperTranscriber only emits fixed, sanitized client-side messages.
                        showError(message)
                    }
                },
            )
        } catch (_: Exception) {
            if (ownsRequest(session, request, audio)) {
                session.transcribing = false
                showError("Transcription failed. Check settings or retry.")
            }
        }
    }

    private fun ownsRequest(session: EditorSession, request: Long, audio: File): Boolean =
        isCurrentDictationTarget(session) && requestGeneration == request &&
            session.transcribing && session.audio === audio

    private fun onCancelTranscription() {
        activeSession?.userInteracted = true
        discardAudio()
        resetKeyboard()
    }

    private fun discardAudio() {
        requestGeneration++
        whisperTranscriber.stop()
        recorderManager?.cancel()
        activeSession?.apply {
            audio?.delete()
            audio = null
            transcribing = false
        }
    }

    private fun shouldShowRetry(): Boolean {
        val session = activeSession ?: return false
        val audio = session.audio ?: return false
        return isCurrentDictationTarget(session) && !session.transcribing &&
            recorderManager?.isRecording == false && audio.isFile && audio.length() > 0L
    }

    private fun onDeleteText() {
        val session = activeSession ?: return
        if (!isCurrentTarget(session)) return
        session.userInteracted = true
        EditorOperations.deleteBackward(session.connection)
    }

    private fun onEnter() {
        val session = activeSession ?: return
        if (!isCurrentTarget(session) || session.transcribing || recorderManager?.isRecording == true) return
        session.userInteracted = true
        val action = EditorPolicy.enterAction(session.imeOptions, session.actionId, session.hasActionLabel)
        if (action != null && session.connection.performEditorAction(action)) return
        session.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        session.connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun onShiftEnter() {
        val session = activeSession ?: return
        if (!isCurrentTarget(session) || session.transcribing || recorderManager?.isRecording == true) return
        session.userInteracted = true
        EditorOperations.shiftEnter(session.connection)
    }

    private fun onSpaceBar() {
        val session = activeSession ?: return
        if (!isCurrentTarget(session) || session.transcribing) return
        session.userInteracted = true
        session.connection.commitText(" ", 1)
    }

    private fun onSwitchIme() {
        invalidateSession()
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val switched = if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
            switchToPreviousInputMethod()
        } else {
            val token: IBinder? = window?.window?.attributes?.token
            manager.switchToLastInputMethod(token)
        }
        if (!switched) {
            // No previous keyboard must not leave this visible keyboard permanently disabled.
            beginSessionIfReady()
            manager.showInputMethodPicker()
        }
    }

    private fun onOpenSettings() {
        launchMainActivity()
    }

    private fun launchMainActivity() {
        // Do not depend on a later onWindowHidden callback to release the microphone/audio.
        invalidateSession()
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun resetKeyboard() {
        if (keyboardReady) whisperKeyboard.reset()
    }

    private fun showError(message: String) {
        if (!keyboardReady || destroyed) return
        whisperKeyboard.reset()
        whisperKeyboard.showError(message)
    }

    override fun onDestroy() {
        destroyed = true
        windowShown = false
        inputViewActive = false
        invalidateSession()
        recorderManager?.close()
        recorderManager = null
        whisperTranscriber.close()
        if (networkCallbackRegistered) {
            connectivity.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        serviceScope.cancel()
        keyboardReady = false
        super.onDestroy()
    }
}
