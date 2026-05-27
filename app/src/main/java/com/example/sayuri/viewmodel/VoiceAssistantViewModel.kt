package com.example.sayuri.viewmodel

import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.sayuri.audio.BluetoothAudioManager
import com.example.sayuri.audio.SpeechRecognizerWrapper
import com.example.sayuri.audio.TtsWrapper
import com.example.sayuri.audio.WakeWordDetector
import com.example.sayuri.data.GeminiRepository
import com.example.sayuri.model.ApiException
import com.example.sayuri.model.AssistantState
import com.example.sayuri.model.SpeechResult
import com.example.sayuri.model.WakeWordResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Central orchestrator ViewModel that manages the full voice assistant interaction lifecycle
 * and exposes UI state as a [StateFlow].
 *
 * State machine:
 *   Idle → WakeWordListening → ActiveListening → Processing → Speaking → WakeWordListening
 *   Any state → Error → WakeWordListening (after recovery delay)
 *
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9,
 *               1a.3, 1a.4, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6,
 *               14.1, 14.4, 14.5
 */
class VoiceAssistantViewModel(
    private val wakeWordDetector: WakeWordDetector,
    private val speechRecognizer: SpeechRecognizerWrapper,
    private val tts: TtsWrapper,
    private val geminiRepository: GeminiRepository,
    private val bluetoothAudioManager: BluetoothAudioManager
) : ViewModel() {

    // ── State ─────────────────────────────────────────────────────────────────────

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Idle)

    /**
     * The current state of the voice assistant. Observed by the UI layer.
     *
     * Requirement: 1.7
     */
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    // ── Job handle ────────────────────────────────────────────────────────────────

    /**
     * The coroutine job running the wake word detection loop.
     * Cancelled when the user mutes (onMicPressed) or the ViewModel is destroyed.
     */
    internal var listeningJob: Job? = null

    // ── TTS readiness flag ────────────────────────────────────────────────────────

    /**
     * Tracks whether the TTS engine has been successfully initialised.
     * Set to `true` after [TtsWrapper.initialize] returns `true`.
     * Reset to `false` in [onDestroy] so that the next [onResume] re-initialises.
     *
     * Requirement: 10.4
     */
    internal var ttsReady: Boolean = false

    // ── Wake word loop ────────────────────────────────────────────────────────────

    /**
     * Starts the always-on wake word detection loop inside [viewModelScope].
     *
     * The loop:
     * 1. Sets state to [AssistantState.WakeWordListening].
     * 2. Calls [WakeWordDetector.detect] (suspends until a result is available).
     * 3. On [WakeWordResult.Detected]: cancels the detector, calls [startActiveListening],
     *    then loops back to step 1 after [startActiveListening] returns.
     * 4. On [WakeWordResult.NotDetected]: immediately loops back to step 1.
     * 5. On [WakeWordResult.Error]: sets error state, waits [RECOVERY_DELAY_MS], loops back.
     *
     * The entire loop body is wrapped in try/catch so unexpected exceptions transition
     * to [AssistantState.Error] without crashing the app (Requirement 10.5).
     *
     * Requirements: 1.1, 1.2, 1.5, 1.6, 1.7, 1a.3, 1a.4, 10.5, 10.6
     */
    fun startWakeWordLoop() {
        // Cancel any existing loop before starting a new one
        listeningJob?.cancel()

        listeningJob = viewModelScope.launch {
            while (isActive) {
                try {
                    _state.value = AssistantState.WakeWordListening

                    when (val wakeResult = wakeWordDetector.detect()) {
                        is WakeWordResult.Detected -> {
                            // Stop the detector before starting active listening (Requirement 1.8)
                            wakeWordDetector.cancel()
                            startActiveListening()
                            // After startActiveListening() returns (Speaking done or error),
                            // the outer while loop restarts → back to WakeWordListening
                        }

                        is WakeWordResult.NotDetected -> {
                            // Immediately loop back — no state change needed (Requirement 1a.3)
                        }

                        is WakeWordResult.Error -> {
                            // Set error state and wait before retrying (Requirement 1.6, 10.6)
                            _state.value = AssistantState.Error(
                                mapWakeWordError(wakeResult.code)
                            )
                            delay(RECOVERY_DELAY_MS)
                        }
                    }
                } catch (e: Exception) {
                    // Catch any unexpected exception to prevent app crash (Requirement 10.5)
                    Log.e(TAG, "Unexpected exception in wake word loop", e)
                    _state.value = AssistantState.Error(
                        "Something went wrong. Restarting…"
                    )
                    delay(RECOVERY_DELAY_MS)
                }
            }
        }
    }

    // ── Active listening ──────────────────────────────────────────────────────────

    /**
     * Performs a single active speech recognition session.
     *
     * 1. Sets state to [AssistantState.ActiveListening].
     * 2. Calls [SpeechRecognizerWrapper.listen] (suspends until a result is available).
     * 3. On [SpeechResult.Success]: strips the wake word prefix; if blank, returns
     *    immediately (Requirement 1.9); otherwise sets [AssistantState.Processing] and
     *    calls [handleTranscript].
     * 4. On [SpeechResult.Error]: sets error state, waits [RECOVERY_DELAY_MS].
     *
     * This is a suspend function called from within the wake word loop coroutine.
     * It returns after the full interaction (Speaking) completes or after an error delay,
     * so the outer loop can resume wake word detection.
     *
     * Requirements: 1.3, 1.4, 1.8, 1.9, 10.1
     */
    internal suspend fun startActiveListening() {
        _state.value = AssistantState.ActiveListening

        when (val result = speechRecognizer.listen()) {
            is SpeechResult.Success -> {
                // Strip wake word prefix in case the recognizer included it
                // (SpeechRecognizerWrapper already strips it, but we guard here too)
                val clean = result.transcript
                    .removePrefix("sayuri ")
                    .removePrefix("Sayuri ")
                    .removePrefix("sayuri")
                    .removePrefix("Sayuri")
                    .trim()

                if (clean.isBlank()) {
                    // Only the wake word was spoken — return to wake word loop (Requirement 1.9)
                    return
                }

                // Transition to Processing before calling Gemini (Requirement 1.4)
                _state.value = AssistantState.Processing
                handleTranscript(clean)
            }

            is SpeechResult.Error -> {
                // Map the error code to a user-friendly message (Requirement 10.1)
                _state.value = AssistantState.Error(mapSpeechError(result.code))
                delay(RECOVERY_DELAY_MS)
            }
        }
    }

    // ── Transcript handling ───────────────────────────────────────────────────────

    /**
     * Sends [text] to the Gemini API and handles the response.
     *
     * 1. Calls [GeminiRepository.sendMessage].
     * 2. On success: sets [AssistantState.Speaking], calls [TtsWrapper.speak], then returns
     *    (the outer loop will resume wake word detection — Requirement 1.5).
     * 3. On failure: sets [AssistantState.Error] with a user-friendly message
     *    (Requirements 10.2, 10.3).
     *
     * Requirements: 1.4, 1.5, 10.2, 10.3
     */
    internal suspend fun handleTranscript(text: String) {
        geminiRepository.sendMessage(text)
            .onSuccess { response ->
                _state.value = AssistantState.Speaking(response)
                tts.speak(response)
                // Returns here after TTS completes; outer loop resumes WakeWordListening
            }
            .onFailure { error ->
                val (message, delayMs) = when {
                    error is ApiException && error.code == 429 ->
                        "Rate limited (6/min). Ready in 60s…" to 60_000L
                    error is IOException ->
                        "No internet connection" to RECOVERY_DELAY_MS
                    error is ApiException ->
                        "Service error ${error.code}. Try again." to RECOVERY_DELAY_MS
                    else ->
                        (error.message?.takeIf { it.isNotBlank() }
                            ?: "Something went wrong. Try again.") to RECOVERY_DELAY_MS
                }
                _state.value = AssistantState.Error(message)
                delay(delayMs)
                _state.value = AssistantState.Idle
            }
    }

    // ── Locale / accent selection ─────────────────────────────────────────────────

    /**
     * Changes the TTS voice locale. Takes effect immediately on the next spoken utterance.
     */
    fun setTtsLocale(locale: TtsWrapper.TtsLocale) {
        tts.locale = locale
    }

    fun getTtsLocale(): TtsWrapper.TtsLocale = tts.locale

    // ── Mic button toggle (TEST MODE: press-to-talk, no wake word) ───────────────

    /**
     * TEST MODE: Press-to-talk toggle.
     *
     * - [AssistantState.Idle] or [AssistantState.Error]: start a single listen session directly.
     * - [AssistantState.ActiveListening]: cancel listening, return to Idle.
     * - [AssistantState.Speaking]: stop TTS, return to Idle.
     * - [AssistantState.Processing]: ignore.
     * - [AssistantState.WakeWordListening]: cancel, return to Idle (shouldn't happen in test mode).
     */
    fun onMicPressed() {
        when (_state.value) {
            is AssistantState.Idle,
            is AssistantState.Error -> {
                startPushToTalk()
            }

            is AssistantState.ActiveListening -> {
                speechRecognizer.cancel()
                listeningJob?.cancel()
                listeningJob = null
                _state.value = AssistantState.Idle
            }

            is AssistantState.Speaking -> {
                tts.stop()
                listeningJob?.cancel()
                listeningJob = null
                _state.value = AssistantState.Idle
            }

            is AssistantState.Processing -> {
                // Ignore during processing
            }

            is AssistantState.WakeWordListening -> {
                wakeWordDetector.cancel()
                listeningJob?.cancel()
                listeningJob = null
                _state.value = AssistantState.Idle
            }
        }
    }

    /**
     * TEST MODE: Starts a single press-to-talk session.
     * Listens once, sends to Gemini, speaks response, then returns to Idle.
     */
    private fun startPushToTalk() {
        listeningJob?.cancel()
        listeningJob = viewModelScope.launch {
            try {
                _state.value = AssistantState.ActiveListening
                when (val result = speechRecognizer.listen()) {
                    is SpeechResult.Success -> {
                        val clean = result.transcript.trim()
                        if (clean.isBlank()) {
                            _state.value = AssistantState.Idle
                            return@launch
                        }
                        _state.value = AssistantState.Processing
                        handleTranscript(clean)
                        _state.value = AssistantState.Idle
                    }
                    is SpeechResult.Error -> {
                        _state.value = AssistantState.Error(mapSpeechError(result.code))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception in push-to-talk", e)
                _state.value = AssistantState.Error("Something went wrong. Try again.")
            }
        }
    }

    // ── Lifecycle hooks ───────────────────────────────────────────────────────────

    /**
     * Called when the ViewModel is about to be destroyed (e.g. from [MainActivity.onDestroy]).
     *
     * Releases all audio and Bluetooth resources.
     *
     * Requirements: 14.1
     */
    fun onDestroy() {
        listeningJob?.cancel()
        listeningJob = null
        wakeWordDetector.destroy()
        speechRecognizer.destroy()
        tts.shutdown()
        ttsReady = false
        bluetoothAudioManager.release()
    }

    /**
     * Called when the app moves to the background ([MainActivity.onPause]).
     *
     * Pauses both wake word detection and any active listening session.
     *
     * Requirement: 14.4
     */
    fun onPause() {
        wakeWordDetector.cancel()
        speechRecognizer.cancel()
        listeningJob?.cancel()
        listeningJob = null
        _state.value = AssistantState.Idle
    }

    /**
     * Called when the app returns to the foreground ([MainActivity.onResume]) and
     * the `RECORD_AUDIO` permission is granted.
     *
     * If the TTS engine has not yet been initialised (or previously failed to initialise),
     * this method attempts initialisation first. On failure it logs a warning and sets
     * [AssistantState.Error] so the UI reflects the problem; the next [onResume] call
     * will retry. On success it starts the wake word loop.
     *
     * If TTS is already ready, the wake word loop is started immediately.
     *
     * Requirements: 10.4, 14.5
     */
    fun onResume() {
        // TEST MODE: don't auto-start the wake word loop — wait for mic button press.
        // Just initialise TTS so it's ready when the user presses the button.
        if (ttsReady) return

        viewModelScope.launch {
            val success = tts.initialize()
            if (success) {
                ttsReady = true
                // Stay in Idle — user presses mic to start
            } else {
                Log.w(TAG, "TTS initialisation failed; will retry on next onResume")
                _state.value = AssistantState.Error(
                    "Voice output unavailable. Will retry shortly."
                )
            }
        }
    }

    // ── ViewModel.onCleared ───────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        onDestroy()
    }

    // ── Error message helpers ─────────────────────────────────────────────────────

    /**
     * Maps a [SpeechRecognizer] error code to a user-friendly message.
     *
     * Requirement: 10.1
     */
    private fun mapSpeechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Couldn't hear you. Try again."
        else -> "Couldn't hear you. Try again."
    }

    /**
     * Maps a [WakeWordResult.Error] code to a user-friendly message.
     */
    private fun mapWakeWordError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Couldn't hear you. Try again."
        else -> "Microphone error. Retrying…"
    }

    // ── Constants ─────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VoiceAssistantViewModel"

        /**
         * Recovery delay in milliseconds before restarting wake word detection after
         * an error. Must be ≤ 1000ms per Requirements 1.6 and 10.6.
         */
        const val RECOVERY_DELAY_MS = 1_000L
    }
}
