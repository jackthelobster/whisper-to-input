/* GPL-3.0-or-later. Editor deletion regression tests. */
package com.example.whispertoinput

import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorOperationsInstrumentedTest {
    private fun onMain(test: (View) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { test(View(instrumentation.targetContext)) }
    }

    @Test fun rejectedInsertionRetainsAudioUntilSuccessfulRetry() = onMain { view ->
        val audio = java.io.File.createTempFile("insertion-retry-", ".m4a", view.context.cacheDir)
        audio.writeText("test-only-audio")
        var accepted = false
        val connection = object : BaseInputConnection(view, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean = accepted
        }
        try {
            assertFalse(EditorOperations.commitTranscription(connection, "recognized text", audio))
            assertTrue(audio.isFile)
            assertTrue(audio.length() > 0)
            accepted = true
            assertTrue(EditorOperations.commitTranscription(connection, "recognized text", audio))
            assertFalse(audio.exists())
        } finally {
            audio.delete()
        }
    }

    @Test fun insertionExceptionAndBlankSpeechRetainAudio() = onMain { view ->
        val audio = java.io.File.createTempFile("insertion-error-", ".m4a", view.context.cacheDir)
        val connection = object : BaseInputConnection(view, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
                throw IllegalStateException("Editor rejected insertion")
        }
        try {
            assertFalse(EditorOperations.commitTranscription(connection, "recognized text", audio))
            assertTrue(audio.exists())
            assertFalse(EditorOperations.commitTranscription(connection, " ", audio))
            assertTrue(audio.exists())
        } finally {
            audio.delete()
        }
    }

    @Test fun unsupportedCodePointDeletionSendsPairedDeleteEvents() = onMain { view ->
        val events = mutableListOf<Pair<Int, Int>>()
        val connection = object : BaseInputConnection(view, true) {
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                fail("Must not fall back to blind UTF-16 deletion")
                return false
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                events.add(event.action to event.keyCode)
                return true
            }
        }
        EditorOperations.deleteBackward(connection)
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_DEL,
            KeyEvent.ACTION_UP to KeyEvent.KEYCODE_DEL), events)
    }

    @Test fun supportedDeletionPreservesSupplementaryCodePoints() = onMain { view ->
        val connection = BaseInputConnection(view, true)
        val editable = requireNotNull(connection.editable)
        editable.append("a\uD83D\uDE00")
        Selection.setSelection(editable, editable.length)
        EditorOperations.deleteBackward(connection)
        assertEquals("a", editable.toString())
        EditorOperations.deleteBackward(connection)
        assertEquals("", editable.toString())
    }

    @Test fun selectedTextIsDeletedWithoutRemovingAdjacentText() = onMain { view ->
        val connection = BaseInputConnection(view, true)
        val editable = requireNotNull(connection.editable)
        editable.append("a\uD83D\uDE00b")
        Selection.setSelection(editable, 1, 3)
        EditorOperations.deleteBackward(connection)
        assertEquals("ab", editable.toString())
    }

    @Test fun rejectedSelectedTextCommitAlsoFallsBackToDeleteEvents() = onMain { view ->
        val events = mutableListOf<Int>()
        val connection = object : BaseInputConnection(view, true) {
            override fun getSelectedText(flags: Int): CharSequence = "selected"
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean = false
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                events.add(event.action)
                assertEquals(KeyEvent.KEYCODE_DEL, event.keyCode)
                return true
            }
        }
        EditorOperations.deleteBackward(connection)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), events)
    }
}
