package com.example.sayuri.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.sayuri.model.WakeWordResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Passively monitors audio for the wake word "Sayuri" using Android's [SpeechRecognizer]
 * in a lightweight polling loop.
 *
 * Each call to [detect] performs a single recognition cycle and returns a [WakeWordResult].
 * The caller (e.g. VoiceAssistantViewModel) is responsible for looping until the wake word
 * is detected or the coroutine is cancelled.
 *
 * Must be called from the main thread (SpeechRecognizer requires it).
 */
class WakeWordDetector(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * Performs a single wake-word recognition cycle.
     *
     * Bridges [RecognitionListener] callbacks to a [suspendCancellableCoroutine].
     * Returns [WakeWordResult.Detected] if any result string contains "sayuri"
     * (case-insensitive), [WakeWordResult.NotDetected] if no match, or
     * [WakeWordResult.Error] if the recognizer reports an error.
     *
     * Coroutine cancellation calls [SpeechRecognizer.cancel] to release the
     * underlying Android resource.
     *
     * Requirements: 1a.1, 1a.2, 1a.5, 1a.6
     */
    suspend fun detect(): WakeWordResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            // Create a fresh SpeechRecognizer for each polling cycle
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr

            // Register cancellation handler — releases the Android resource when the
            // coroutine is cancelled (Requirement 1a.6)
            continuation.invokeOnCancellation {
                sr.cancel()
            }

            sr.setRecognitionListener(object : RecognitionListener {

                // ── Results ──────────────────────────────────────────────────────────

                override fun onResults(results: Bundle?) {
                    if (!continuation.isActive) return

                    val matches: List<String> =
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?: emptyList()

                    continuation.resume(classifyResults(matches))
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Partial results are not used for wake-word detection
                }

                // ── Errors ───────────────────────────────────────────────────────────

                override fun onError(error: Int) {
                    if (!continuation.isActive) return

                    val message = speechErrorMessage(error)
                    continuation.resume(WakeWordResult.Error(error, message))
                }

                // ── Unused lifecycle callbacks ────────────────────────────────────────

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // Build the recognition intent with a short timeout (Requirement 1a.1)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // Request only a single result to keep the polling cycle lightweight
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Prefer a short speech timeout so the loop stays responsive
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
            }

            sr.startListening(intent)
        }
    }

    /**
     * Cancels the current recognition session without destroying the recognizer.
     * Safe to call from any thread.
     *
     * Requirement: 1a.6
     */
    fun cancel() {
        recognizer?.cancel()
    }

    /**
     * Destroys the underlying [SpeechRecognizer] and releases all associated resources.
     * Must be called when the detector is no longer needed (e.g. in ViewModel.onDestroy).
     *
     * Requirement: 14.1
     */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    /**
     * Maps a [SpeechRecognizer] error code to a human-readable message.
     */
    private fun speechErrorMessage(errorCode: Int): String = when (errorCode) {
        SpeechRecognizer.ERROR_AUDIO              -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT             -> "Client-side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK            -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH           -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER             -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "Speech timeout"
        else                                      -> "Unknown error ($errorCode)"
    }

    companion object {
        /**
         * Classifies a list of recognition result strings into a [WakeWordResult].
         *
         * Returns [WakeWordResult.Detected] if any string in [results] contains the
         * substring "sayuri" (case-insensitive), or [WakeWordResult.NotDetected] otherwise.
         *
         * Extracted as a companion function so it can be unit-tested without an Android context.
         *
         * Requirement: 1a.2
         */
        fun classifyResults(results: List<String>): WakeWordResult {
            val detected = results.any { it.contains("sayuri", ignoreCase = true) }
            return if (detected) WakeWordResult.Detected else WakeWordResult.NotDetected
        }
    }
}
