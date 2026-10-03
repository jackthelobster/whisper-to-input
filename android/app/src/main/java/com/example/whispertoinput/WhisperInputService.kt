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
import java.io.File

private const val AUDIO_MEDIA_TYPE_M4A = "audio/mp4"
private const val IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL = 28

class WhisperInputService : InputMethodService() {
    private val whisperKeyboard = WhisperKeyboard()
    private val whisperTranscriber = WhisperTranscriber()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorderManager: RecorderManager? = null
    private var settingsJob: Job? = null
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
        var userInteracted = false
        var audio: File? = null
        var transcribing = false
    }

    override fun onCreate() {
        super.onCreate()
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
        val shouldOfferImeSwitch =
            if (Build.VERSION.SDK_INT >= IME_SWITCH_OPTION_AVAILABILITY_API_LEVEL) {
                shouldOfferSwitchingToNextInputMethod()
            } else {
                val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                val token: IBinder? = window?.window?.attributes?.token
                manager.shouldOfferSwitchingToNextInputMethod(token)
            }
        val view = whisperKeyboard.setup(
            layoutInflater,
            shouldOfferImeSwitch,
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
        if (!allowed) return
        settingsJob = serviceScope.launch {
            try {
                val settings = SettingsRepository(this@WhisperInputService).load()
                if (!isCurrentDictationTarget(session)) return@launch
                session.settings = settings
                // Default false is supplied by the repository. A delayed load cannot override a
                // manual gesture, resume a hidden editor, or start recording in a replacement field.
                if (settings.autoRecord && !session.userInteracted && session.audio == null &&
                    !session.transcribing && recorderManager?.isRecording == false) {
                    whisperKeyboard.tryStartRecording()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed delayed settings load must not put a manually started recorder's UI
                // back into idle while leaving its microphone running.
                if (isCurrentTarget(session) && !session.userInteracted) {
                    showError("Unable to load dictation settings.")
                }
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
        val request = ++requestGeneration
        session.transcribing = true
        // Enter while recording means finish dictation, NOT submit a chat/message or insert an
        // actionable newline. Only an explicitly requested space is attached to transcription.
        val suffix = if (attachToEnd == " ") " " else ""
        try {
            whisperTranscriber.startAsync(
                this, audio.absolutePath, AUDIO_MEDIA_TYPE_M4A, suffix,
                { text ->
                    serviceScope.launch {
                        if (!ownsRequest(session, request, audio)) return@launch
                        // The transcriber emits callback(null) before its error callback; let
                        // that error path finish the request instead of treating it as empty speech.
                        if (text == null) return@launch
                        val committed = !text.isNullOrBlank() && runCatching {
                            session.connection.commitText(text, 1)
                        }.getOrDefault(false)
                        if (committed) {
                            session.transcribing = false
                            audio.delete()
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
        // Only inspect selection to implement deletion; never read surrounding editor contents.
        val selectedText = session.connection.getSelectedText(0)
        if (selectedText.isNullOrEmpty()) {
            if (Build.VERSION.SDK_INT >= 24) {
                session.connection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                session.connection.deleteSurroundingText(1, 0)
            }
        } else {
            session.connection.commitText("", 1)
        }
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
        serviceScope.cancel()
        keyboardReady = false
        super.onDestroy()
    }
}
