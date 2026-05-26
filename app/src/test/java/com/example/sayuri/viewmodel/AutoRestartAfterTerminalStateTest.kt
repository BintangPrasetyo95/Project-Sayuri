package com.example.sayuri.viewmodel

import android.util.Log
import com.example.sayuri.audio.BluetoothAudioManager
import com.example.sayuri.audio.SpeechRecognizerWrapper
import com.example.sayuri.audio.TtsWrapper
import com.example.sayuri.audio.WakeWordDetector
import com.example.sayuri.data.GeminiRepository
import com.example.sayuri.model.ApiException
import com.example.sayuri.model.AssistantState
import com.example.sayuri.model.SpeechResult
import com.example.sayuri.model.TtsResult
import com.example.sayuri.model.WakeWordResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException

/** Represents one of the terminal outcomes the ViewModel must recover from. */
sealed class TerminalOutcome {
    /** TTS completed successfully after a full interaction. */
    object TtsDone : TerminalOutcome()

    /** SpeechRecognizer returned an error during ActiveListening. */
    data class SpeechError(val code: Int) : TerminalOutcome()

    /** GeminiRepository failed with a network IOException. */
    object NetworkError : TerminalOutcome()

    /** GeminiRepository failed with an HTTP ApiException. */
    data class ApiError(val code: Int) : TerminalOutcome()
}

/**
 * Property test for auto-restart after terminal state.
 *
 * **Property 2: Auto-Restart After Terminal State**
 *
 * For any terminal interaction outcome (TTS done, speech error, network error,
 * API error), [VoiceAssistantViewModel] always transitions back to
 * [AssistantState.WakeWordListening] after each terminal outcome — the always-on
 * listening loop is never permanently broken by a single interaction result.
 *
 * **Validates: Requirements 1.2, 1.5, 1.6**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoRestartAfterTerminalStateTest : StringSpec({

    // ── Android Log stub ──────────────────────────────────────────────────────

    beforeSpec {
        // Stub android.util.Log so JVM unit tests don't crash on Log calls
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    // ── Arbitrary generators ──────────────────────────────────────────────────

    /** Arbitrary speech error codes (non-zero to distinguish from success). */
    val arbSpeechErrorCode: Arb<Int> = Arb.int(1..10)

    /** Arbitrary HTTP error codes in the 4xx–5xx range. */
    val arbApiErrorCode: Arb<Int> = Arb.int(400..599)

    /** Arbitrary non-blank transcript (the user command after wake word). */
    val arbTranscript: Arb<String> = Arb.string(minSize = 3, maxSize = 40)
        .map { it.trim() }
        .map { if (it.isBlank()) "hello" else it }

    /** Arbitrary non-blank Gemini response text. */
    val arbResponseText: Arb<String> = Arb.string(minSize = 1, maxSize = 80)
        .map { it.trim() }
        .map { if (it.isBlank()) "ok" else it }

    /** Arbitrary terminal outcome — one of the four variants. */
    val arbTerminalOutcome: Arb<TerminalOutcome> = Arb.element(
        TerminalOutcome.TtsDone,
        TerminalOutcome.SpeechError(1),
        TerminalOutcome.NetworkError,
        TerminalOutcome.ApiError(500)
    )

    // ── Helper: build a ViewModel configured for the given outcome ────────────

    /**
     * Creates a [VoiceAssistantViewModel] with all dependencies mocked via MockK.
     *
     * The mocks are configured so that:
     * 1. [WakeWordDetector.detect] returns [WakeWordResult.Detected] on the first
     *    call, then suspends via [awaitCancellation] (so the loop parks at
     *    WakeWordListening after one full cycle).
     * 2. The subsequent pipeline steps are configured according to [outcome].
     */
    fun buildViewModel(
        outcome: TerminalOutcome,
        transcript: String,
        responseText: String
    ): VoiceAssistantViewModel {
        val wakeWordDetector = mockk<WakeWordDetector>()
        val speechRecognizer = mockk<SpeechRecognizerWrapper>()
        val tts = mockk<TtsWrapper>()
        val geminiRepository = mockk<GeminiRepository>()
        val bluetoothAudioManager = mockk<BluetoothAudioManager>()

        // BluetoothAudioManager: expose a simple StateFlow
        every { bluetoothAudioManager.isBluetoothScoAvailable } returns MutableStateFlow(false)

        // WakeWordDetector: first call → Detected; second call → parks until cancelled
        var detectCallCount = 0
        coEvery { wakeWordDetector.detect() } coAnswers {
            detectCallCount++
            if (detectCallCount == 1) {
                WakeWordResult.Detected
            } else {
                // Suspend until the test cancels the job — proves the loop restarted
                awaitCancellation()
            }
        }
        every { wakeWordDetector.cancel() } returns Unit
        every { wakeWordDetector.destroy() } returns Unit

        // Configure the pipeline steps based on the terminal outcome
        when (outcome) {
            is TerminalOutcome.TtsDone -> {
                // Full happy path: listen → success → Gemini → TTS done
                coEvery { speechRecognizer.listen() } returns SpeechResult.Success(transcript)
                coEvery { geminiRepository.sendMessage(any()) } returns Result.success(responseText)
                coEvery { tts.speak(any()) } returns TtsResult.Done
            }

            is TerminalOutcome.SpeechError -> {
                // Speech recognition fails
                coEvery { speechRecognizer.listen() } returns
                    SpeechResult.Error(outcome.code, "Speech recognition error ${outcome.code}")
            }

            is TerminalOutcome.NetworkError -> {
                // Listen succeeds, but Gemini call fails with IOException
                coEvery { speechRecognizer.listen() } returns SpeechResult.Success(transcript)
                coEvery { geminiRepository.sendMessage(any()) } returns
                    Result.failure(IOException("Simulated network failure"))
            }

            is TerminalOutcome.ApiError -> {
                // Listen succeeds, but Gemini call fails with ApiException
                coEvery { speechRecognizer.listen() } returns SpeechResult.Success(transcript)
                coEvery { geminiRepository.sendMessage(any()) } returns
                    Result.failure(ApiException(outcome.code, "Simulated API error ${outcome.code}"))
            }
        }

        every { speechRecognizer.cancel() } returns Unit
        every { speechRecognizer.destroy() } returns Unit
        every { tts.stop() } returns Unit
        every { tts.shutdown() } returns Unit

        return VoiceAssistantViewModel(
            wakeWordDetector = wakeWordDetector,
            speechRecognizer = speechRecognizer,
            tts = tts,
            geminiRepository = geminiRepository,
            bluetoothAudioManager = bluetoothAudioManager
        )
    }

    /**
     * Runs a single property iteration: builds the ViewModel, starts the loop,
     * advances virtual time until idle, and returns the final state.
     *
     * Uses a single [TestCoroutineScheduler] shared between [Dispatchers.Main] and
     * [runTest] so that [delay] calls inside [viewModelScope] are advanced by
     * [advanceUntilIdle].
     */
    suspend fun runIteration(
        outcome: TerminalOutcome,
        transcript: String,
        responseText: String
    ): AssistantState {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)

        var finalState: AssistantState = AssistantState.Idle

        // Set Dispatchers.Main to the same dispatcher so viewModelScope delays
        // are controlled by the same scheduler as runTest.
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val viewModel = buildViewModel(outcome, transcript, responseText)

                // ttsReady = true so onResume skips TTS init and calls startWakeWordLoop directly
                viewModel.ttsReady = true
                viewModel.onResume()

                // Advance virtual time past all delays (including RECOVERY_DELAY_MS)
                // and run all pending coroutines. The second detect() call suspends via
                // awaitCancellation(), so advanceUntilIdle() stops there.
                advanceUntilIdle()

                finalState = viewModel.state.value

                // Cancel the loop so the ViewModel doesn't leak into the next iteration
                viewModel.listeningJob?.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }

        return finalState
    }

    // ── Property 2: Auto-Restart After Terminal State ─────────────────────────

    /**
     * For any terminal outcome, the ViewModel always transitions back to
     * [AssistantState.WakeWordListening] after the outcome is processed.
     *
     * **Validates: Requirements 1.2, 1.5, 1.6**
     */
    "Property 2 - ViewModel always returns to WakeWordListening after any terminal outcome" {
        forAll(
            PropTestConfig(iterations = 20),
            arbTerminalOutcome,
            arbTranscript,
            arbResponseText
        ) { outcome, transcript, responseText ->
            val finalState = runIteration(outcome, transcript, responseText)
            finalState == AssistantState.WakeWordListening
        }
    }

    // ── Focused sub-properties for each terminal outcome variant ──────────────

    "Property 2 - ViewModel returns to WakeWordListening after TTS Done for any response text" {
        forAll(
            PropTestConfig(iterations = 20),
            arbTranscript,
            arbResponseText
        ) { transcript, responseText ->
            val finalState = runIteration(TerminalOutcome.TtsDone, transcript, responseText)
            finalState == AssistantState.WakeWordListening
        }
    }

    "Property 2 - ViewModel returns to WakeWordListening after speech error for any error code" {
        forAll(
            PropTestConfig(iterations = 20),
            arbSpeechErrorCode,
            arbTranscript,
            arbResponseText
        ) { errorCode, transcript, responseText ->
            val finalState = runIteration(
                TerminalOutcome.SpeechError(errorCode), transcript, responseText
            )
            finalState == AssistantState.WakeWordListening
        }
    }

    "Property 2 - ViewModel returns to WakeWordListening after network error for any transcript" {
        forAll(
            PropTestConfig(iterations = 20),
            arbTranscript,
            arbResponseText
        ) { transcript, responseText ->
            val finalState = runIteration(TerminalOutcome.NetworkError, transcript, responseText)
            finalState == AssistantState.WakeWordListening
        }
    }

    "Property 2 - ViewModel returns to WakeWordListening after API error for any HTTP error code" {
        forAll(
            PropTestConfig(iterations = 20),
            arbApiErrorCode,
            arbTranscript,
            arbResponseText
        ) { errorCode, transcript, responseText ->
            val finalState = runIteration(
                TerminalOutcome.ApiError(errorCode), transcript, responseText
            )
            finalState == AssistantState.WakeWordListening
        }
    }
})
