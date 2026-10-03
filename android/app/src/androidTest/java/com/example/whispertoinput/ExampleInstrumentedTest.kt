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
        val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.toSet()
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
    }
}
