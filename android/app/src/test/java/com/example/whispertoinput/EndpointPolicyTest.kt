package com.example.whispertoinput

import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    @Test fun acceptsCustomHttpsAndOnlyApprovedHttpHost() {
        assertTrue(EndpointPolicy.validate("https://example.test:8443/custom/transcribe", false).isHttps)
        assertEquals("gx10", EndpointPolicy.validate(AppSettings()).host)
        assertEquals("gx10", EndpointPolicy.validate("http://gx10:8020/custom", true).host)
    }

    @Test fun refusesHttpOutsideExactGx10AndWhenToggleIsOff() {
        listOf("gx10.example.test", "gx10.", "localhost", "127.0.0.1", "192.168.1.10", "[::1]").forEach { host ->
            assertThrows(TranscriptionException::class.java) {
                EndpointPolicy.validate("http://$host:8020/v1/audio/transcriptions", true)
            }
        }
        assertThrows(TranscriptionException::class.java) {
            EndpointPolicy.validate(AppSettings(allowInsecure = false))
        }
    }

    @Test fun refusesCredentialsFragmentsAndMalformedUrls() {
        listOf("https://user:password@example.test/transcribe", "https://@example.test/transcribe",
            "https://example.test/transcribe#fragment", "https://example.test/transcribe#", "ftp://example.test/file",
            "/v1/audio/transcriptions", " https://example.test/transcribe", "https://example.test/\ntranscribe",
            "https://gx10\\@example.test/transcribe").forEach { endpoint ->
            assertThrows(TranscriptionException::class.java) { EndpointPolicy.validate(endpoint, true) }
        }
    }

    @Test fun credentialQueryParametersAreRejectedWithoutEchoingSecrets() {
        listOf("api_key", "API-KEY", "token", "password", "%61pi_key").forEach { parameter ->
            val error = assertThrows(TranscriptionException::class.java) {
                EndpointPolicy.validate("https://example.test/transcribe?$parameter=unit-test-token", false)
            }
            assertFalse(error.message!!.contains("unit-test-token"))
        }
    }

    @Test fun apiKeyIsNeverAllowedOverCleartextAndCannotInjectHeaders() {
        assertThrows(TranscriptionException::class.java) {
            EndpointPolicy.validate(AppSettings(apiKey = "unit-test-token"))
        }
        assertThrows(TranscriptionException::class.java) {
            EndpointPolicy.validate("https://example.test/transcribe", false, "token\r\nInjected: yes")
        }
        assertThrows(TranscriptionException::class.java) {
            EndpointPolicy.validate("https://example.test/transcribe", false, " token ")
        }
    }
}
