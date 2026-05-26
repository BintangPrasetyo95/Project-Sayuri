package com.example.sayuri.model

/**
 * Result returned by [com.example.sayuri.audio.WakeWordDetector] after a single
 * recognition polling cycle.
 */
sealed class WakeWordResult {
    /** The wake word "Sayuri" was found in the recognition results. */
    object Detected : WakeWordResult()

    /** No wake word was found; the detector should loop immediately. */
    object NotDetected : WakeWordResult()

    /** The underlying SpeechRecognizer reported an error. */
    data class Error(val code: Int, val message: String) : WakeWordResult()
}
