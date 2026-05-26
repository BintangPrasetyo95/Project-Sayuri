package com.example.sayuri.audio

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.sayuri.model.TtsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Wraps Android's [TextToSpeech] engine with coroutine support and Bluetooth SCO audio routing.
 *
 * Usage:
 * 1. Call [initialize] once and check the returned Boolean before calling [speak].
 * 2. Call [speak] with any text; long texts are automatically chunked on sentence boundaries.
 * 3. Call [stop] to halt playback mid-utterance.
 * 4. Call [shutdown] when the wrapper is no longer needed (e.g. in ViewModel.onDestroy).
 *
 * All Android TTS API calls are dispatched on [Dispatchers.Main] as required by the engine.
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 13.4, 13.6
 */
class TtsWrapper(private val context: Context) {

    private var tts: TextToSpeech? = null

    // ── Initialization ────────────────────────────────────────────────────────────

    /**
     * Initialises the [TextToSpeech] engine with [Locale.US].
     *
     * Bridges [TextToSpeech.OnInitListener] to a [CompletableDeferred] so the caller
     * can `await` the result in a coroutine.
     *
     * @return `true` if the engine initialised successfully; `false` on error.
     *
     * Requirements: 5.2
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Boolean>()

        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                deferred.complete(true)
            } else {
                deferred.complete(false)
            }
        }

        val success = deferred.await()

        if (success) {
            val langResult = engine.setLanguage(Locale.US)
            val langOk = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED
            if (langOk) {
                tts = engine
            } else {
                engine.shutdown()
                return@withContext false
            }
        } else {
            engine.shutdown()
        }

        success
    }

    // ── Speaking ──────────────────────────────────────────────────────────────────

    /**
     * Speaks [text] aloud using the TTS engine.
     *
     * If [text] exceeds 500 characters it is split on sentence boundaries so that
     * no individual chunk passed to the engine exceeds 500 characters (Requirement 5.6).
     *
     * Audio is routed to Bluetooth SCO when a SCO connection is available; otherwise
     * it falls back to the device speaker (Requirement 5.7).
     *
     * Each chunk is spoken sequentially via [TextToSpeech.QUEUE_ADD]. The coroutine
     * suspends until [UtteranceProgressListener.onDone] fires for that chunk, or
     * resumes with [TtsResult.Error] if [UtteranceProgressListener.onError] fires.
     *
     * Coroutine cancellation calls [tts.stop] immediately (Requirement 5.3).
     *
     * @return [TtsResult.Done] when all chunks complete; [TtsResult.Error] on failure.
     *
     * Requirements: 5.1, 5.3, 5.4, 5.5, 5.6, 5.7, 13.4
     */
    suspend fun speak(text: String): TtsResult = withContext(Dispatchers.Main) {
        val engine = tts ?: return@withContext TtsResult.Error("TTS engine not initialised")

        // Route audio to Bluetooth SCO if available, otherwise speaker (Requirement 5.7)
        routeAudio()

        val chunks = splitIntoChunks(text)

        for (chunk in chunks) {
            val result = speakChunk(engine, chunk)
            if (result is TtsResult.Error) {
                return@withContext result
            }
        }

        TtsResult.Done
    }

    /**
     * Stops the current TTS utterance immediately.
     *
     * Requirement: 5.3, 14.3
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Shuts down the TTS engine and releases all associated resources.
     * Must be called when the wrapper is no longer needed.
     *
     * Requirement: 14.3
     */
    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    /**
     * Speaks a single [chunk] and suspends until the utterance completes or fails.
     *
     * Bridges [UtteranceProgressListener.onDone] / [UtteranceProgressListener.onError]
     * to a [suspendCancellableCoroutine].
     *
     * Requirements: 5.4, 5.5, 13.4
     */
    private suspend fun speakChunk(engine: TextToSpeech, chunk: String): TtsResult =
        suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()

            // Register cancellation handler — stops TTS when the coroutine is cancelled
            // (Requirement 5.3)
            continuation.invokeOnCancellation {
                engine.stop()
            }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {

                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resume(TtsResult.Done)
                    }
                }

                @Deprecated("Deprecated in API 21; onError(String, Int) is preferred")
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resume(TtsResult.Error("TTS engine reported an error"))
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resume(
                            TtsResult.Error("TTS engine error (code $errorCode)")
                        )
                    }
                }

                override fun onStart(utteranceId: String?) {
                    // Not used
                }
            })

            @Suppress("DEPRECATION")
            engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }

    /**
     * Routes TTS audio output to Bluetooth SCO if a headset SCO connection is active;
     * otherwise routes to the device speaker.
     *
     * Requirement: 5.7
     */
    private fun routeAudio() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isBluetoothScoOn) {
            // SCO is already active — TTS will naturally route through the headset
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            // Fall back to speaker output
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        }
    }

    /**
     * Splits [text] into chunks on sentence boundaries such that no chunk exceeds
     * [MAX_CHUNK_LENGTH] characters.
     *
     * Strategy:
     * 1. If the text is within the limit, return it as a single-element list.
     * 2. Otherwise, split on sentence-ending punctuation (`.`, `!`, `?`) followed by
     *    whitespace or end-of-string, accumulating sentences until the next sentence
     *    would push the chunk over the limit.
     * 3. If a single sentence itself exceeds the limit, it is hard-split at the nearest
     *    whitespace boundary before [MAX_CHUNK_LENGTH].
     *
     * Requirement: 5.6
     */
    internal fun splitIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_LENGTH) return listOf(text)

        val chunks = mutableListOf<String>()
        // Split on sentence-ending punctuation followed by whitespace or end-of-string
        val sentences = text.split(SENTENCE_BOUNDARY_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val current = StringBuilder()

        for (sentence in sentences) {
            val candidate = if (current.isEmpty()) sentence else "${current} $sentence"

            when {
                candidate.length <= MAX_CHUNK_LENGTH -> {
                    // Sentence fits — accumulate
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                }
                current.isNotEmpty() -> {
                    // Flush the current buffer before starting a new chunk
                    chunks.add(current.toString())
                    current.clear()

                    // The sentence itself may still exceed the limit — hard-split if needed
                    if (sentence.length > MAX_CHUNK_LENGTH) {
                        chunks.addAll(hardSplit(sentence))
                    } else {
                        current.append(sentence)
                    }
                }
                else -> {
                    // current is empty and the sentence alone exceeds the limit
                    chunks.addAll(hardSplit(sentence))
                }
            }
        }

        if (current.isNotEmpty()) {
            chunks.add(current.toString())
        }

        return chunks
    }

    /**
     * Hard-splits [text] at whitespace boundaries so that each piece is at most
     * [MAX_CHUNK_LENGTH] characters. Used as a fallback when a single sentence is
     * longer than the limit.
     */
    private fun hardSplit(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var remaining = text

        while (remaining.length > MAX_CHUNK_LENGTH) {
            // Find the last whitespace at or before the limit
            var splitAt = remaining.lastIndexOf(' ', MAX_CHUNK_LENGTH)
            if (splitAt <= 0) {
                // No whitespace found — force a hard cut at the limit
                splitAt = MAX_CHUNK_LENGTH
            }
            pieces.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trim()
        }

        if (remaining.isNotEmpty()) {
            pieces.add(remaining)
        }

        return pieces
    }

    // ── Constants ─────────────────────────────────────────────────────────────────

    companion object {
        /** Maximum number of characters per TTS chunk (Requirement 5.6). */
        const val MAX_CHUNK_LENGTH = 500

        /**
         * Regex that matches sentence-ending punctuation (`.`, `!`, `?`) followed by
         * one or more whitespace characters or end-of-string, used to split text into
         * individual sentences.
         */
        private val SENTENCE_BOUNDARY_REGEX = Regex("(?<=[.!?])\\s+")
    }
}
