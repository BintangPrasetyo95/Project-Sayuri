package com.example.sayuri.model

/**
 * Result returned by [com.example.sayuri.audio.SpeechRecognizerWrapper] after an
 * active listening session.
 */
sealed class SpeechResult {
    /**
     * Recognition succeeded.
     * @param transcript The highest-confidence, non-blank transcript string.
     */
    data class Success(val transcript: String) : SpeechResult()

    /** Recognition failed or was cancelled. */
    data class Error(val code: Int, val message: String) : SpeechResult()
}
