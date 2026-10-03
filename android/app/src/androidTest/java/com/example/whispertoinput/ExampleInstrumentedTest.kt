/* GPL-3.0-or-later. Device verification for the Andrew dictation fork. */
package com.example.whispertoinput

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.security.NetworkSecurityPolicy
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.whispertoinput.keyboard.WhisperKeyboard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun packagePermissionsAndCleartextPolicy() {
        assertEquals("com.jackthelobster.whispertoinput", context.packageName)
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("gx10"))
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("api.openai.com"))
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("attacker.gx10"))
    }

    @Test fun credentialsAreEncryptedAndRoundTrip() = runBlocking {
        val repository = SettingsRepository(context)
        val secret = "instrumentation-only-placeholder-secret-7341"
        try {
            repository.save(AppSettings(endpoint = "https://example.com/v1/audio/transcriptions", apiKey = secret, allowInsecure = false))
            assertEquals(secret, repository.load().apiKey)
            val privateFiles = File(context.applicationInfo.dataDir).walkTopDown().filter { it.isFile && (it.path.contains("shared_prefs") || it.path.contains("datastore")) }.toList()
            assertTrue(privateFiles.isNotEmpty())
            privateFiles.forEach { assertFalse("Plaintext API key found in ${it.name}", it.readBytes().toString(Charsets.ISO_8859_1).contains(secret)) }
        } finally {
            repository.save(AppSettings())
        }
    }

    @Test fun settingsAndKeyboardRenderLightAndDarkAtS10eWidth() {
        runBlocking { SettingsRepository(context).save(AppSettings()) }
        for ((mode, label) in listOf(AppCompatDelegate.MODE_NIGHT_NO to "light", AppCompatDelegate.MODE_NIGHT_YES to "dark")) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                var loaded = false
                while (!loaded && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        loaded = editFields(activity.findViewById(android.R.id.content)).any { it.text.toString() == SettingsRepository.DEFAULT_ENDPOINT }
                    }
                    if (!loaded) android.os.SystemClock.sleep(50)
                }
                assertTrue("Settings did not load", loaded)
                scenario.onActivity { activity ->
                    assertTrue("Settings screen must protect secrets from screenshots", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    capture(activity.findViewById(android.R.id.content), activity.getExternalFilesDir(null)!!, "settings-$label")
                    val keyboard = WhisperKeyboard()
                    val view = keyboard.setup(activity.layoutInflater, true, {}, {}, {}, {}, {}, {}, {}, {}, {}, { false })
                    activity.setContentView(view)
                    view.measure(View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    capture(view, activity.getExternalFilesDir(null)!!, "keyboard-$label")
                    keyboard.setSensitiveInput(true)
                    assertFalse(view.findViewById<View>(R.id.btn_mic).isEnabled)
                    keyboard.setSensitiveInput(false)
                    keyboard.tryStartRecording()
                    capture(view, activity.getExternalFilesDir(null)!!, "keyboard-listening-$label")
                    keyboard.reset()
                }
            }
        }
    }

    private fun editFields(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { editFields(view.getChildAt(it)) }
        else -> emptyList()
    }

    // Draw only dummy test data, inside the app's own instrumentation process.
    // Production FLAG_SECURE remains enabled; no screenshot permission is added.
    private fun capture(view: View, directory: File, name: String) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val target = File(directory, "screenshots/$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // AGP uninstalls the tested APK at teardown, deleting its external-files directory.
        // Copy the dummy-data screenshots out using the test runner's shell identity first.
        val result = shell("mkdir -p /sdcard/whisper-verification && cp '${target.absolutePath}' '/sdcard/whisper-verification/$name.png' && printf copied")
        assertEquals("copied", result)
    }

    @Test fun actualInputMethodStartsAndBlocksPasswordField() {
        runBlocking { SettingsRepository(context).save(AppSettings()) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val serviceInfo = requireNotNull(automation.serviceInfo)
        serviceInfo.flags = serviceInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = serviceInfo
        val previous = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        val ime = "${context.packageName}/com.example.whispertoinput.WhisperInputService"
        try {
            shell("ime enable $ime && ime set $ime")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline = android.os.SystemClock.elapsedRealtime() + 10000
                var ready = false
                while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        ready = activity.hasWindowFocus() && activity.findViewById<EditText>(R.id.field_endpoint).isEnabled
                    }
                    if (!ready) android.os.SystemClock.sleep(100)
                }
                assertTrue("Settings window did not become ready", ready)
                scenario.onActivity { activity ->
                    val field = activity.findViewById<EditText>(R.id.field_endpoint)
                    field.requestFocus()
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                assertTrue("Actual IME microphone did not become enabled for an ordinary text field", awaitMicEnabled(true))
                scenario.onActivity { activity ->
                    val field = activity.findViewById<EditText>(R.id.field_api_key)
                    field.requestFocus()
                    (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                assertTrue("Actual IME microphone did not block a password/private field", awaitMicEnabled(false))
            }
        } finally {
            if (!previous.isNullOrBlank()) shell("ime set $previous")
            shell("ime disable $ime")
        }
    }

    private fun awaitMicEnabled(expected: Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val roots = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }
            if (roots.any { micEnabled(it) == expected }) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun micEnabled(node: android.view.accessibility.AccessibilityNodeInfo): Boolean? {
        if (node.viewIdResourceName?.endsWith(":id/btn_mic") == true) return node.isEnabled
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = micEnabled(child)
            if (found != null) return found
        }
        return null
    }

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation
        .executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
}
