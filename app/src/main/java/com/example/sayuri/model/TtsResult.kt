package com.example.sayuri.model

/**
 * Result returned by [com.example.sayuri.audio.TtsWrapper] after a speak() call.
 */
sealed class TtsResult {
    /** All utterance chunks completed successfully. */
    object Done : TtsResult()

    /** The TTS engine reported a failure. */
    data class Error(val message: String) : TtsResult()
}
