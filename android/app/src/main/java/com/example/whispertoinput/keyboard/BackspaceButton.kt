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

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.AppCompatImageButton

private const val QUICK_BACKSPACE_DELAY = 80L
private const val DELAY_BEFORE_QUICK_BACKSPACE = 500L

/** Repeat is owned by this visible view, never by a free-floating coroutine. */
class BackspaceButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.imageButtonStyle,
) : AppCompatImageButton(context, attrs, defStyleAttr) {
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var backspaceCallback: () -> Unit = {}
    private var repeating = false
    private val repeatAction = object : Runnable {
        override fun run() {
            if (!repeating || !isPressed || !isEnabled || !isShown || !hasWindowFocus()) {
                stopRepeating()
                return
            }
            backspaceCallback()
            repeatHandler.postDelayed(this, QUICK_BACKSPACE_DELAY)
        }
    }

    fun setBackspaceCallback(callback: () -> Unit) {
        stopRepeating()
        backspaceCallback = callback
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { stopRepeating(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopRepeating()
                isPressed = true
                performClick()
                if (isPressed && isShown && isEnabled && hasWindowFocus()) {
                    repeating = true
                    repeatHandler.postDelayed(repeatAction, DELAY_BEFORE_QUICK_BACKSPACE)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.x < 0 || event.x >= width || event.y < 0 || event.y >= height) stopRepeating()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stopRepeating()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // TalkBack, keyboard and switch access call exactly the same one-delete action.
    override fun performClick(): Boolean {
        if (!isEnabled) return false
        super.performClick()
        backspaceCallback()
        return true
    }

    fun stopRepeating() {
        repeating = false
        repeatHandler.removeCallbacks(repeatAction)
        isPressed = false
    }

    override fun onDetachedFromWindow() {
        stopRepeating()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View constructors may dispatch this before Kotlin field initialization.
        if (visibility != View.VISIBLE) stopRepeatingSafely()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) stopRepeatingSafely()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopRepeatingSafely()
    }

    private fun stopRepeatingSafely() {
        // Avoid touching the handler until the subclass constructor has completed.
        if (initialized) stopRepeating()
    }

    private var initialized = true
}
