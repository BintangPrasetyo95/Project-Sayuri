package com.example.sayuri.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.sayuri.model.SpeechResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Wraps Android's [SpeechRecognizer] with a coroutine bridge for active speech recognition.
 *
 * Each call to [listen] performs a single full recognition session and returns a [SpeechResult].
 * The highest-confidence result is selected from the recognizer's output, and the wake word
 * prefix ("sayuri" / "Sayuri") is stripped from the start of the transcript if present.
 *
 * Must be called from the main thread ([SpeechRecognizer] requires it).
 *
 * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 13.6
 */
class SpeechRecognizerWrapper(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null

    /**
     * Performs a single active speech recognition session.
     *
     * Bridges [RecognitionListener] callbacks to a [suspendCancellableCoroutine].
     *
     * - Selects the result with the highest confidence score (Requirement 3.2).
     * - Strips the wake word prefix "sayuri" / "Sayuri" from the start of the transcript
     *   if present (Requirement 3.3).
     * - Returns [SpeechResult.Success] only if the stripped transcript is non-blank
     *   (Requirement 3.3).
     * - Returns [SpeechResult.Error] for [SpeechRecognizer.ERROR_NO_MATCH],
     *   [SpeechRecognizer.ERROR_SPEECH_TIMEOUT], and permission-denied cases
     *   (Requirements 3.4, 3.6).
     * - Coroutine cancellation calls [SpeechRecognizer.cancel] (Requirement 3.5).
     *
     * Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 13.6
     */
    suspend fun listen(): SpeechResult = withContext(Dispatchers.Main) {
        // Check RECORD_AUDIO permission before attempting to start (Requirement 3.6)
        if (ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext SpeechResult.Error(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                "RECORD_AUDIO permission not granted"
            )
        }

        suspendCancellableCoroutine<SpeechResult> { continuation ->
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr

            // Release the Android resource when the coroutine is cancelled (Requirement 3.5)
            continuation.invokeOnCancellation {
                sr.cancel()
            }

            sr.setRecognitionListener(object : RecognitionListener {

                // ── Results ──────────────────────────────────────────────────────────

                override fun onResults(results: Bundle?) {
                    if (!continuation.isActive) return

                    val transcripts: List<String> =
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?: emptyList()

                    val confidences: FloatArray? =
                        results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                    val best = selectBestTranscript(transcripts, confidences)

                    if (best != null) {
                        continuation.resume(SpeechResult.Success(best))
                    } else {
                        // All results were blank after stripping — treat as no match
                        continuation.resume(
                            SpeechResult.Error(
                                SpeechRecognizer.ERROR_NO_MATCH,
                                "No usable transcript after wake word stripping"
                            )
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Partial results are disabled; this callback is not used
                }

                // ── Errors ───────────────────────────────────────────────────────────

                override fun onError(error: Int) {
                    if (!continuation.isActive) return

                    val message = speechErrorMessage(error)
                    continuation.resume(SpeechResult.Error(error, message))
                }

                // ── Unused lifecycle callbacks ────────────────────────────────────────

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            // Build the recognition intent (Requirement 3.1)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // Disable partial results — we only care about the final result
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Request multiple results so we can pick the highest-confidence one
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }

            sr.startListening(intent)
        }
    }

    /**
     * Cancels the current recognition session without destroying the recognizer.
     * Safe to call from any thread.
     *
     * Requirement: 3.5
     */
    fun cancel() {
        recognizer?.cancel()
    }

    /**
     * Destroys the underlying [SpeechRecognizer] and releases all associated resources.
     * Must be called when the wrapper is no longer needed (e.g. in ViewModel.onDestroy).
     *
     * Requirement: 14.2
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
        SpeechRecognizer.ERROR_AUDIO                     -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT                    -> "Client-side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS  -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK                   -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT           -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH                  -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY           -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER                    -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT            -> "Speech timeout"
        else                                             -> "Unknown error ($errorCode)"
    }

    companion object {

        // Wake word variants to strip from the start of the transcript
        private val WAKE_WORD_PREFIXES = listOf("sayuri ", "sayuri")

        /**
         * Selects the best transcript from a list of recognition results using confidence scores.
         *
         * Algorithm:
         * 1. Pair each transcript with its confidence score (defaulting to 0f if scores are absent
         *    or shorter than the transcript list).
         * 2. Sort by confidence descending and pick the first entry.
         * 3. Strip the wake word prefix ("sayuri", case-insensitive) from the start if present.
         * 4. Return the stripped transcript if non-blank, or `null` otherwise.
         *
         * Exposed as an `internal` companion function so it can be unit-tested without an
         * Android context.
         *
         * Requirements: 3.2, 3.3
         *
         * @param transcripts The list of recognition result strings from
         *   [SpeechRecognizer.RESULTS_RECOGNITION].
         * @param confidences The parallel array of confidence scores from
         *   [SpeechRecognizer.CONFIDENCE_SCORES], or `null` if not provided.
         * @return The highest-confidence, wake-word-stripped, non-blank transcript, or `null`
         *   if no usable transcript exists.
         */
        internal fun selectBestTranscript(
            transcripts: List<String>,
            confidences: FloatArray?
        ): String? {
            if (transcripts.isEmpty()) return null

            // Pair each transcript with its confidence score; default to 0f if missing
            val best = transcripts
                .mapIndexed { index, transcript ->
                    val confidence = confidences?.getOrNull(index) ?: 0f
                    Pair(transcript, confidence)
                }
                .maxByOrNull { (_, confidence) -> confidence }
                ?.first
                ?: return null

            // Strip wake word prefix (case-insensitive) from the start of the transcript
            val stripped = stripWakeWordPrefix(best)

            return stripped.takeIf { it.isNotBlank() }
        }

        /**
         * Strips the wake word prefix "sayuri" (case-insensitive) from the start of [transcript]
         * if present, then trims surrounding whitespace.
         *
         * Handles both "Sayuri what time is it" → "what time is it" and
         * "sayuri" alone → "" (blank, which the caller treats as no usable content).
         *
         * Requirement: 3.3
         */
        private fun stripWakeWordPrefix(transcript: String): String {
            val lower = transcript.lowercase()
            for (prefix in WAKE_WORD_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    return transcript.substring(prefix.length).trim()
                }
            }
            return transcript.trim()
        }
    }
}
