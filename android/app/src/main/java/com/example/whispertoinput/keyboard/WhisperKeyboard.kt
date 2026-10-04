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

package com.example.whispertoinput.keyboard

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.example.whispertoinput.R
import kotlin.math.log10

/** View-only dictation controller; audio and retry-file ownership remain in the input service. */
class WhisperKeyboard {
    private enum class KeyboardStatus { Idle, Recording, Transcribing }
    private var keyboardStatus = KeyboardStatus.Idle
    private var sensitiveInput = false
    private var errorMessage: String? = null
    private var onStartRecording: () -> Unit = {}
    private var onCancelRecording: () -> Unit = {}
    private var onStartTranscribing: (String) -> Unit = {}
    private var onCancelTranscribing: () -> Unit = {}
    private var shouldShowRetry: () -> Boolean = { false }
    private var keyboardView: View? = null
    private var buttonMic: ImageButton? = null
    private var buttonEnter: ImageButton? = null
    private var buttonCancel: ImageButton? = null
    private var buttonRetry: ImageButton? = null
    private var buttonSpaceBar: ImageButton? = null
    private var buttonBackspace: BackspaceButton? = null
    private var labelStatus: TextView? = null
    private var labelHint: TextView? = null
    private var waitingIcon: ProgressBar? = null
    private var amplitudeRing: View? = null

    // Keep the existing service's positional and named argument contract intact.
    fun setup(
        layoutInflater: LayoutInflater,
        shouldOfferImeSwitch: Boolean,
        onStartRecording: () -> Unit,
        onCancelRecording: () -> Unit,
        onStartTranscribing: (attachToEnd: String) -> Unit,
        onCancelTranscribing: () -> Unit,
        onButtonBackspace: () -> Unit,
        onEnter: () -> Unit,
        onSpaceBar: () -> Unit,
        onSwitchIme: () -> Unit,
        onOpenSettings: () -> Unit,
        shouldShowRetry: () -> Boolean,
    ): View {
        buttonBackspace?.stopRepeating()
        this.onStartRecording = onStartRecording
        this.onCancelRecording = onCancelRecording
        this.onStartTranscribing = onStartTranscribing
        this.onCancelTranscribing = onCancelTranscribing
        this.shouldShowRetry = shouldShowRetry
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = view
        buttonMic = view.findViewById(R.id.btn_mic)
        buttonEnter = view.findViewById(R.id.btn_enter)
        buttonCancel = view.findViewById(R.id.btn_cancel)
        buttonRetry = view.findViewById(R.id.btn_retry)
        buttonSpaceBar = view.findViewById(R.id.btn_space_bar)
        buttonBackspace = view.findViewById(R.id.btn_backspace)
        labelStatus = view.findViewById(R.id.label_status)
        labelHint = view.findViewById(R.id.label_hint)
        waitingIcon = view.findViewById(R.id.pb_waiting_icon)
        amplitudeRing = view.findViewById(R.id.mic_amplitude_ring)
        buttonMic?.setOnClickListener {
            when (keyboardStatus) {
                KeyboardStatus.Idle -> tryStartRecording()
                KeyboardStatus.Recording -> tryStartTranscribing("")
                KeyboardStatus.Transcribing -> Unit
            }
        }
        buttonCancel?.setOnClickListener { cancelActiveOperation() }
        buttonRetry?.setOnClickListener {
            // Only an error in this session plus a private current file may expose retry.
            if (!sensitiveInput && keyboardStatus == KeyboardStatus.Idle && errorMessage != null && shouldShowRetry()) {
                errorMessage = null
                setKeyboardStatus(KeyboardStatus.Transcribing)
                this.onStartTranscribing("")
            }
        }
        buttonSpaceBar?.setOnClickListener {
            if (keyboardStatus == KeyboardStatus.Recording) tryStartTranscribing(" ")
            else if (keyboardStatus == KeyboardStatus.Idle) onSpaceBar()
        }
        buttonEnter?.setOnClickListener {
            if (keyboardStatus == KeyboardStatus.Recording) tryStartTranscribing("\r\n")
            else if (keyboardStatus == KeyboardStatus.Idle) onEnter()
        }
        buttonBackspace?.setBackspaceCallback(onButtonBackspace)
        view.findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            buttonBackspace?.stopRepeating()
            onOpenSettings()
        }
        view.findViewById<ImageButton>(R.id.btn_previous_ime).apply {
            visibility = if (shouldOfferImeSwitch) View.VISIBLE else View.GONE
            setOnClickListener {
                buttonBackspace?.stopRepeating()
                onSwitchIme()
            }
        }
        reset()
        return view
    }

    /** Clear any previous-editor error and stop repeating deletion when the IME hides/resets. */
    fun reset() {
        buttonBackspace?.stopRepeating()
        errorMessage = null
        setKeyboardStatus(KeyboardStatus.Idle)
    }

    /** The service must call this before considering auto-record in a new editor. */
    fun setSensitiveInput(sensitive: Boolean) {
        sensitiveInput = sensitive
        if (sensitive) {
            cancelActiveOperation()
            errorMessage = null
            buttonBackspace?.stopRepeating()
        }
        render()
    }

    /** Accepts a sanitized, user-facing message, never a raw server body or exception. */
    fun showError(message: String) {
        errorMessage = message.take(180).ifBlank {
            keyboardView?.context?.getString(R.string.keyboard_error).orEmpty()
        }
        setKeyboardStatus(KeyboardStatus.Idle)
    }

    fun updateMicrophoneAmplitude(amplitude: Int) {
        if (keyboardStatus != KeyboardStatus.Recording || sensitiveInput) return
        val level = ((log10(amplitude.coerceIn(10, 25000).toFloat()) - 1f) / 3.398f).coerceIn(0f, 1f)
        amplitudeRing?.apply {
            animate().cancel()
            alpha = 0.15f + 0.65f * level
            scaleX = 0.88f + 0.12f * level
            scaleY = scaleX
            animate().alpha(0.15f).setDuration(300L).start()
        }
    }

    fun tryStartRecording() {
        if (keyboardView == null || sensitiveInput || keyboardStatus != KeyboardStatus.Idle) return
        errorMessage = null
        setKeyboardStatus(KeyboardStatus.Recording)
        onStartRecording()
    }

    fun tryCancelRecording() {
        if (keyboardStatus != KeyboardStatus.Recording) return
        setKeyboardStatus(KeyboardStatus.Idle)
        onCancelRecording()
    }

    fun tryStartTranscribing(attachToEnd: String) {
        if (sensitiveInput || keyboardStatus != KeyboardStatus.Recording) return
        setKeyboardStatus(KeyboardStatus.Transcribing)
        onStartTranscribing(attachToEnd)
    }

    private fun cancelActiveOperation() {
        val previous = keyboardStatus
        errorMessage = null
        setKeyboardStatus(KeyboardStatus.Idle)
        when (previous) {
            KeyboardStatus.Recording -> onCancelRecording()
            KeyboardStatus.Transcribing -> onCancelTranscribing()
            KeyboardStatus.Idle -> Unit
        }
    }

    private fun setKeyboardStatus(status: KeyboardStatus) {
        keyboardStatus = status
        render()
    }

    private fun render() {
        val view = keyboardView ?: return
        val context = view.context
        val recording = keyboardStatus == KeyboardStatus.Recording
        val transcribing = keyboardStatus == KeyboardStatus.Transcribing
        val active = recording || transcribing
        val status = when {
            sensitiveInput -> R.string.keyboard_sensitive
            recording -> R.string.recording
            transcribing -> R.string.transcribing
            errorMessage != null -> R.string.keyboard_error
            else -> R.string.keyboard_ready
        }
        val hint = when {
            sensitiveInput -> context.getString(R.string.keyboard_sensitive_hint)
            recording -> context.getString(R.string.keyboard_recording_hint)
            transcribing -> context.getString(R.string.keyboard_transcribing_hint)
            errorMessage != null -> errorMessage
            else -> context.getString(R.string.keyboard_ready_hint)
        }
        labelStatus?.setText(status)
        labelHint?.text = hint
        buttonMic?.apply {
            isEnabled = !sensitiveInput && !transcribing
            alpha = if (sensitiveInput) 0.38f else 1f
            setImageResource(if (recording) R.drawable.ic_dictation_stop else R.drawable.ic_dictation_mic)
            imageAlpha = if (transcribing) 0 else 255
            contentDescription = context.getString(when {
                sensitiveInput -> R.string.keyboard_sensitive
                recording -> R.string.stop_speech_to_text
                transcribing -> R.string.transcribing
                else -> R.string.start_speech_to_text
            })
        }
        waitingIcon?.visibility = if (transcribing) View.VISIBLE else View.GONE
        buttonCancel?.visibility = if (active && !sensitiveInput) View.VISIBLE else View.INVISIBLE
        buttonRetry?.apply {
            val available = !sensitiveInput && !active && errorMessage != null && shouldShowRetry()
            visibility = if (available) View.VISIBLE else View.INVISIBLE
            isEnabled = available
        }
        buttonEnter?.apply {
            isEnabled = !transcribing
            alpha = if (transcribing) 0.38f else 1f
            contentDescription = context.getString(if (recording) R.string.transcribe_enter else R.string.enter_key)
        }
        buttonSpaceBar?.apply {
            isEnabled = !transcribing
            alpha = if (transcribing) 0.38f else 1f
            contentDescription = context.getString(if (recording) R.string.transcribe_space else R.string.desc_space_bar)
        }
        amplitudeRing?.apply {
            if (!recording || sensitiveInput) animate().cancel()
            visibility = if (recording && !sensitiveInput) View.VISIBLE else View.INVISIBLE
            if (!recording) { alpha = 0.15f; scaleX = 1f; scaleY = 1f }
        }
        view.keepScreenOn = active && !sensitiveInput
    }
}
