package com.example.whispertoinput

/** Only OpenAI-compatible multipart transcription endpoints are supported. */
data class AppSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val language: String = "en",
    val apiKey: String = "",
    val autoRecord: Boolean = false,
    val autoSwitch: Boolean = false,
    val trailingSpace: Boolean = false,
    val allowInsecure: Boolean = true
) {
    // Never let incidental logging of the settings object disclose a credential or URL query.
    override fun toString(): String = "AppSettings(credentials and endpoint redacted)"

    companion object {
        const val DEFAULT_ENDPOINT = "http://gx10:8020/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "distil-whisper/distil-large-v3"
    }
}
