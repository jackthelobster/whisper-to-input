/* GPL-3.0-or-later. Editor operations for the Andrew dictation fork. */
package com.example.whispertoinput

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import java.io.File

internal object EditorOperations {
    fun commitTranscription(connection: InputConnection, text: String, audio: File): Boolean {
        val committed = text.isNotBlank() && runCatching { connection.commitText(text, 1) }.getOrDefault(false)
        if (committed) audio.delete()
        return committed
    }

    fun deleteBackward(connection: InputConnection) {
        // Inspect selection only; never read surrounding potentially sensitive text.
        val selected = connection.getSelectedText(0)
        val deleted = if (selected.isNullOrEmpty()) {
            connection.deleteSurroundingTextInCodePoints(1, 0)
        } else {
            connection.commitText("", 1)
        }
        if (!deleted) {
            // Older/custom editors may not implement code-point deletion. Let the editor
            // handle DEL rather than blindly deleting one UTF-16 unit and splitting emoji.
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }
}
