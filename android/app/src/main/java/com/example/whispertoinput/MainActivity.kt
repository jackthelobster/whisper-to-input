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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MICROPHONE_PERMISSION_REQUEST_CODE = 200

class MainActivity : AppCompatActivity() {
    private lateinit var repository: SettingsRepository
    private lateinit var endpoint: TextInputEditText
    private lateinit var fallbackEndpoint: TextInputEditText
    private lateinit var fallbackModel: TextInputEditText
    private lateinit var fallbackLanguage: TextInputEditText
    private lateinit var fallbackApiKey: TextInputEditText
    private lateinit var model: TextInputEditText
    private lateinit var language: TextInputEditText
    private lateinit var apiKey: TextInputEditText
    private lateinit var autoRecord: MaterialSwitch
    private lateinit var autoSwitch: MaterialSwitch
    private lateinit var trailingSpace: MaterialSwitch
    private lateinit var allowInsecure: MaterialSwitch
    private lateinit var saveButton: MaterialButton
    private lateinit var testButton: MaterialButton
    private lateinit var testResult: TextView
    private var loaded = false
    private var busy = false
    private var savedSettings: AppSettings? = null
    private val availability = EndpointAvailability()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Secrets are never included in screenshots, recents, autofill or view-state bundles.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.settings_root)
        root.fitsSystemWindows = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        listOf(R.id.setup_heading, R.id.server_heading, R.id.fallback_server_heading, R.id.behavior_heading).forEach {
            ViewCompat.setAccessibilityHeading(findViewById(it), true)
        }
        repository = SettingsRepository(applicationContext)
        endpoint = findViewById(R.id.field_endpoint)
        fallbackEndpoint = findViewById(R.id.field_fallback_endpoint)
        fallbackApiKey = findViewById(R.id.field_fallback_api_key)
        fallbackModel = findViewById(R.id.field_fallback_model)
        fallbackLanguage = findViewById(R.id.field_fallback_language_code)
        model = findViewById(R.id.field_model)
        language = findViewById(R.id.field_language_code)
        apiKey = findViewById(R.id.field_api_key)
        listOf(apiKey, fallbackApiKey).forEach { field ->
            field.isSaveEnabled = false
            field.isSaveFromParentEnabled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        }
        autoRecord = findViewById(R.id.switch_auto_record)
        autoSwitch = findViewById(R.id.switch_auto_switch)
        trailingSpace = findViewById(R.id.switch_trailing_space)
        allowInsecure = findViewById(R.id.switch_allow_insecure)
        saveButton = findViewById(R.id.btn_settings_apply)
        testButton = findViewById(R.id.btn_test_connection)
        testResult = findViewById(R.id.connection_result)
        listOf(endpoint, fallbackEndpoint, fallbackApiKey, fallbackModel, fallbackLanguage, model, language, apiKey).forEach { field ->
            field.doAfterTextChanged { invalidateDiagnostics(); refreshButtons() }
        }
        listOf(autoRecord, autoSwitch, trailingSpace, allowInsecure).forEach { toggle ->
            toggle.setOnCheckedChangeListener { _, _ -> invalidateDiagnostics(); refreshButtons() }
        }
        findViewById<MaterialButton>(R.id.btn_permission).setOnClickListener {
            onRequestMicrophonePermission(it)
        }
        findViewById<MaterialButton>(R.id.btn_enable_keyboard).setOnClickListener {
            openSystemScreen(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btn_choose_keyboard).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        saveButton.setOnClickListener { saveSettings() }
        testButton.setOnClickListener { testConnection() }
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    private fun readForm() = AppSettings(
        endpoint = endpoint.text.toString().trim(),
        fallbackEndpoint = fallbackEndpoint.text.toString().trim(),
        fallbackApiKey = fallbackApiKey.text.toString().trim(),
        fallbackModel = fallbackModel.text.toString().trim(),
        fallbackLanguage = fallbackLanguage.text.toString().trim(),
        model = model.text.toString().trim(),
        language = language.text.toString().trim(),
        apiKey = apiKey.text.toString().trim(),
        autoRecord = autoRecord.isChecked,
        autoSwitch = autoSwitch.isChecked,
        trailingSpace = trailingSpace.isChecked,
        allowInsecure = allowInsecure.isChecked,
    )

    private fun loadSettings() {
        loaded = false
        setBusy(true)
        lifecycleScope.launch {
            try {
                val settings = repository.load()
                endpoint.setText(settings.endpoint)
                fallbackEndpoint.setText(settings.fallbackEndpoint)
                fallbackApiKey.setText(settings.fallbackApiKey)
                fallbackModel.setText(settings.fallbackModel)
                fallbackLanguage.setText(settings.fallbackLanguage)
                model.setText(settings.model)
                language.setText(settings.language)
                apiKey.setText(settings.apiKey)
                autoRecord.isChecked = settings.autoRecord
                autoSwitch.isChecked = settings.autoSwitch
                trailingSpace.isChecked = settings.trailingSpace
                allowInsecure.isChecked = settings.allowInsecure
                savedSettings = settings
                loaded = true
                checkConnection(settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Snackbar.make(findViewById(R.id.settings_root), R.string.settings_load_failed, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.reenter_settings) {
                        // Do not silently retry with empty credentials. Explicit recovery enables
                        // the form; the service still refuses unreadable settings until Save succeeds.
                        val defaults = AppSettings()
                        endpoint.setText(defaults.endpoint)
                        fallbackEndpoint.setText("")
                        fallbackApiKey.setText("")
                        fallbackModel.setText(defaults.fallbackModel)
                        fallbackLanguage.setText(defaults.fallbackLanguage)
                        model.setText(defaults.model)
                        language.setText(defaults.language)
                        apiKey.setText("")
                        autoRecord.isChecked = false
                        autoSwitch.isChecked = false
                        trailingSpace.isChecked = false
                        allowInsecure.isChecked = defaults.allowInsecure
                        savedSettings = null
                        loaded = true
                        setBusy(false)
                    }.show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun validatedForm(): AppSettings? {
        val settings = readForm()
        listOf(R.id.input_endpoint, R.id.input_fallback_endpoint, R.id.input_fallback_api_key, R.id.input_fallback_model, R.id.input_fallback_language, R.id.input_model, R.id.input_language, R.id.input_api_key).forEach {
            findViewById<TextInputLayout>(it).error = null
        }
        if (settings.fallbackEndpoint.isNotEmpty() || settings.fallbackApiKey.isNotEmpty()) {
            val fallback = settings.fallbackEndpoint.toHttpUrlOrNull()
            val fallbackError = try {
                EndpointPolicy.validate(settings.fallbackEndpoint, settings.allowInsecure, settings.fallbackApiKey)
                if (fallback == null || fallback.query != null ||
                    !fallback.encodedPath.endsWith("/v1/audio/transcriptions")) getString(R.string.invalid_endpoint) else null
            } catch (e: TranscriptionException) { e.message }
            if (fallbackError != null) {
                findViewById<TextInputLayout>(R.id.input_fallback_endpoint).error = fallbackError
                fallbackEndpoint.requestFocus()
                return null
            }
            val fallbackFieldError = when {
                settings.fallbackModel.isBlank() || settings.fallbackModel.length > 4096 ||
                    settings.fallbackModel.any { it.isISOControl() } ->
                    R.id.input_fallback_model to R.string.invalid_model
                !settings.fallbackLanguage.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?")) ->
                    R.id.input_fallback_language to R.string.invalid_language
                settings.fallbackModel == SettingsRepository.DEFAULT_MODEL && settings.fallbackLanguage != "en" ->
                    R.id.input_fallback_language to R.string.english_model_only
                else -> null
            }
            if (fallbackFieldError != null) {
                val input = findViewById<TextInputLayout>(fallbackFieldError.first)
                input.error = getString(fallbackFieldError.second)
                input.editText?.requestFocus()
                return null
            }
        }
        val url = settings.endpoint.toHttpUrlOrNull()
        val error = when {
            url == null || settings.endpoint.any { it.isWhitespace() || it.isISOControl() } ||
                '\\' in settings.endpoint || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null ||
                !url.encodedPath.endsWith("/v1/audio/transcriptions") ->
                R.id.input_endpoint to R.string.invalid_endpoint
            url.scheme == "http" && !settings.allowInsecure ->
                R.id.input_endpoint to R.string.http_not_allowed
            url.scheme == "http" && settings.apiKey.isNotEmpty() ->
                R.id.input_api_key to R.string.http_key_not_allowed
            settings.model.isBlank() || settings.model.length > 4096 || settings.model.any { it.isISOControl() } ->
                R.id.input_model to R.string.invalid_model
            !settings.language.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?")) ->
                R.id.input_language to R.string.invalid_language
            settings.model == SettingsRepository.DEFAULT_MODEL && settings.language != "en" ->
                R.id.input_language to R.string.english_model_only
            settings.apiKey.length > 4096 || settings.apiKey.any { it.code !in 33..126 } ->
                R.id.input_api_key to R.string.invalid_api_key
            else -> null
        }
        if (error != null) {
            val input = findViewById<TextInputLayout>(error.first)
            input.error = getString(error.second)
            input.editText?.requestFocus()
            return null
        }
        return settings
    }

    private fun saveSettings() {
        if (!loaded || busy) return
        val settings = validatedForm() ?: return
        setBusy(true)
        lifecycleScope.launch {
            try {
                // Repository persistence finishes before announcing success; keys are destination-bound.
                repository.save(settings)
                savedSettings = settings
                checkConnection(settings)
                Snackbar.make(findViewById(R.id.settings_root), R.string.successfully_set, Snackbar.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                showMessage(R.string.settings_invalid)
            } catch (_: Exception) {
                showMessage(R.string.settings_save_failed)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun testConnection() {
        if (!loaded || busy) return
        val settings = validatedForm() ?: return
        setBusy(true)
        lifecycleScope.launch {
            try { checkConnection(settings) } finally { setBusy(false) }
        }
    }

    private suspend fun checkConnection(settings: AppSettings) {
        testResult.visibility = View.VISIBLE
        testResult.setText(R.string.testing_connection)
        val selected = availability.select(settings)
        testResult.setText(when {
            selected == null -> R.string.endpoint_unavailable
            selected.endpoint != settings.endpoint -> R.string.keyboard_ready_fallback
            else -> R.string.keyboard_ready
        })
    }

    private fun invalidateDiagnostics() {
        testResult.visibility = View.GONE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(endpoint, fallbackEndpoint, fallbackApiKey, fallbackModel, fallbackLanguage, model, language, apiKey, autoRecord, autoSwitch, trailingSpace, allowInsecure)
            .forEach { it.isEnabled = loaded && !busy }
        saveButton.setText(if (value) R.string.settings_working else R.string.settings_btn_apply)
        refreshButtons()
    }

    private fun refreshButtons() {
        saveButton.isEnabled = loaded && !busy && readForm() != savedSettings
        testButton.isEnabled = loaded && !busy
    }

    private fun refreshSetupStatus() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        findViewById<TextView>(R.id.microphone_status).setText(if (granted) R.string.microphone_allowed else R.string.microphone_needed)
        findViewById<MaterialButton>(R.id.btn_permission).apply {
            isEnabled = !granted
            setText(if (granted) R.string.microphone_ready else R.string.grant_microphone_permission)
        }
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any {
            it.packageName == packageName && it.serviceName == WhisperInputService::class.java.name
        }
        findViewById<TextView>(R.id.keyboard_status).setText(if (enabled) R.string.keyboard_enabled else R.string.keyboard_not_enabled)
        findViewById<MaterialButton>(R.id.btn_choose_keyboard).isEnabled = enabled
    }

    @Suppress("UNUSED_PARAMETER")
    fun onRequestMicrophonePermission(view: View) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        val asked = getPreferences(MODE_PRIVATE).getBoolean("microphone_requested", false)
        if (asked && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            openSystemScreen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            getPreferences(MODE_PRIVATE).edit().putBoolean("microphone_requested", true).apply()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            refreshSetupStatus()
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) showMessage(R.string.mic_permission_required)
        }
    }

    private fun openSystemScreen(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { showMessage(R.string.system_screen_unavailable) }
    }

    private fun showMessage(message: Int) {
        Snackbar.make(findViewById(R.id.settings_root), message, Snackbar.LENGTH_LONG).show()
    }
}
