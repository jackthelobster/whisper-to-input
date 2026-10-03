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

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPolicyTest {
    @Test
    fun blocksAllTextPasswordVariationsWithOtherFlags() {
        val variations = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        for (variation in variations) {
            val type = InputType.TYPE_CLASS_TEXT or variation or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            assertTrue(EditorPolicy.isSensitive(type, EditorInfo.IME_ACTION_DONE))
            assertFalse(EditorPolicy.allowsDictation(type, EditorInfo.IME_ACTION_DONE))
        }
    }

    @Test
    fun blocksNumericPasswordWithNumberFlags() {
        val type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD or
            InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        assertTrue(EditorPolicy.isSensitive(type, 0))
        assertFalse(EditorPolicy.allowsDictation(type, 0))
    }

    @Test
    fun passwordVariationIsInterpretedWithinItsClass() {
        // Numeric-password variation shares the value of text URI; URI is not a password.
        assertFalse(EditorPolicy.isSensitive(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0,
        ))
        assertFalse(EditorPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER, 0))
    }

    @Test
    fun allowsOrdinaryTextWebEmailAndNumbers() {
        val types = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_PHONE,
        )
        for (type in types) assertTrue(EditorPolicy.allowsDictation(type, EditorInfo.IME_ACTION_SEND))
    }

    @Test
    fun noPersonalizedLearningBlocksEvenOtherwiseOrdinaryEditors() {
        val options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        for (type in listOf(InputType.TYPE_CLASS_TEXT, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE)) {
            assertTrue(EditorPolicy.isSensitive(type, options))
            assertFalse(EditorPolicy.allowsDictation(type, options))
        }
    }

    @Test
    fun nullEditorCannotRecord() {
        assertFalse(EditorPolicy.allowsDictation(InputType.TYPE_NULL, 0))
    }

    @Test
    fun recognizesEveryExplicitStandardEnterActionAndMasksFlags() {
        val actions = listOf(
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_PREVIOUS,
        )
        for (action in actions) {
            assertEquals(action, EditorPolicy.enterAction(action or EditorInfo.IME_FLAG_NO_EXTRACT_UI))
        }
    }

    @Test
    fun noEnterActionFlagRequiresKeyEventFallback() {
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_FLAG_NO_ENTER_ACTION, 42, true))
    }

    @Test
    fun noneUnspecifiedAndUnknownActionsRequireKeyEventFallback() {
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_ACTION_NONE))
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertNull(EditorPolicy.enterAction(EditorInfo.IME_MASK_ACTION))
    }

    @Test
    fun honorsExplicitCustomActionLabelAndId() {
        assertEquals(42, EditorPolicy.enterAction(EditorInfo.IME_ACTION_SEND, 42, true))
        assertEquals(EditorInfo.IME_ACTION_SEND, EditorPolicy.enterAction(EditorInfo.IME_ACTION_SEND, 42, false))
        assertEquals(0, EditorPolicy.enterAction(0, 0, true))
    }
}
