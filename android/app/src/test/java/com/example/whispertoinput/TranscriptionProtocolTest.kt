package com.example.whispertoinput

import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptionProtocolTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun request(settings: AppSettings = AppSettings()) = TranscriptionProtocol.buildRequest(
        settings, temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }, "audio/mp4"
    )

    @Test fun approvedDefaultIsKeylessAndMultipartHasBoundary() {
        val request = request()
        assertEquals(AppSettings.DEFAULT_ENDPOINT, request.url.toString())
        assertNull(request.header("Authorization"))
        assertNull(request.header("Content-Type")) // The transport derives it from the body.
        val body = request.body as MultipartBody
        assertTrue(body.contentType().toString().contains("boundary=${body.boundary}"))
        val buffer = Buffer()
        body.writeTo(buffer)
        val wire = buffer.readUtf8()
        assertTrue(wire.contains("name=\"file\"; filename=\"audio.m4a\""))
        assertTrue(wire.contains("name=\"model\""))
        assertTrue(wire.contains("\r\n\r\n${AppSettings.DEFAULT_MODEL}\r\n"))
        assertTrue(wire.contains("name=\"language\""))
        assertTrue(wire.contains("\r\n\r\nen\r\n"))
        assertTrue(wire.contains("name=\"response_format\""))
        assertTrue(wire.contains("\r\n\r\njson\r\n"))
        assertFalse(wire.contains("audio_file"))
    }

    @Test fun httpsHasOptionalAuthorizationAndPreservesOnlyConfiguredQuery() {
        val endpoint = "https://example.test/transcribe?tenant=a%20b&tenant=c"
        val request = request(AppSettings(endpoint = endpoint, apiKey = "unit-test-token"))
        assertEquals("Bearer unit-test-token", request.header("Authorization"))
        assertEquals(endpoint, request.url.toString())
        assertEquals(2, request.url.querySize)
        assertNull(request.url.queryParameter("encode"))
        assertNull(request.url.queryParameter("language"))
        assertNull(request.url.queryParameter("task"))
        assertNull(request(AppSettings(endpoint = "https://example.test/transcribe")).header("Authorization"))
    }

    @Test fun blankLanguageOmitsLanguageForAutomaticDetection() {
        val buffer = Buffer()
        request(AppSettings(language = "")).body!!.writeTo(buffer)
        assertFalse(buffer.readUtf8().contains("name=\"language\""))
    }

    @Test fun jsonAndTextAreParsedWithoutReturningJsonToTheKeyboard() {
        assertEquals("hello", TranscriptionProtocol.parseText(" {\"text\":\" hello \"} ", "application/json"))
        assertEquals("hello", TranscriptionProtocol.parseText("{\"text\":\"hello\",\"segments\":[]}"))
        assertEquals("hello\nworld", TranscriptionProtocol.parseText(" hello\nworld ", "text/plain"))
        assertEquals("hello", TranscriptionProtocol.parseText("\uFEFF{\"text\":\"hello\"}"))
    }

    @Test fun invalidEmptyOrErrorResponsesAreRejected() {
        listOf("", "  ", "{}", "{\"text\":null}", "{\"text\":42}", "{\"text\":\" \"}",
            "{\"error\":\"secret server trace\"}", "{broken", "[]", "<html>error</html>").forEach { body ->
            val error = assertThrows(TranscriptionException::class.java) { TranscriptionProtocol.parseText(body) }
            assertFalse(error.message!!.contains("secret server trace"))
        }
        assertThrows(TranscriptionException::class.java) {
            TranscriptionProtocol.parseText("not JSON", "application/json; charset=utf-8")
        }
    }

    @Test fun invalidRecordingAndModelAreRejectedBeforeNetworking() {
        val empty = temporary.newFile()
        assertThrows(TranscriptionException::class.java) {
            TranscriptionProtocol.buildRequest(AppSettings(), empty, "audio/mp4")
        }
        assertThrows(TranscriptionException::class.java) { request(AppSettings(model = "")) }
        assertThrows(TranscriptionException::class.java) {
            TranscriptionProtocol.buildRequest(AppSettings(), empty.apply { writeText("audio") }, "text/plain")
        }
    }

    @Test fun settingsToStringRedactsBothKeyAndEndpoint() {
        val settings = AppSettings(endpoint = "https://example.test/?secret=query", apiKey = "unit-test-token")
        assertFalse(settings.toString().contains("unit-test-token"))
        assertFalse(settings.toString().contains("secret=query"))
    }
}
