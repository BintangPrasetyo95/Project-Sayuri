package com.example.sayuri.viewmodel

import android.util.Log
import com.example.sayuri.audio.BluetoothAudioManager
import com.example.sayuri.audio.SpeechRecognizerWrapper
import com.example.sayuri.audio.TtsWrapper
import com.example.sayuri.audio.WakeWordDetector
import com.example.sayuri.data.GeminiRepository
import com.example.sayuri.model.SpeechResult
import com.example.sayuri.model.TtsResult
import com.example.sayuri.model.WakeWordResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException

/**
 * Represents one event in an arbitrary interaction sequence.
 * Each event drives one full wake-word → listen → Gemini → TTS cycle.
 */
sealed class InteractionEvent {
    /** A full happy-path cycle: wake word detected, speech recognised, Gemini succeeds, TTS done. */
    data class HappyPath(val transcript: String, val response: String) : InteractionEvent()

    /** Wake word detected but speech recognition fails. */
    data class SpeechError(val code: Int) : InteractionEvent()

    /** Wake word detected, speech recognised, but Gemini returns a network error. */
    data class NetworkError(val transcript: String) : InteractionEvent()

    /** Wake word detected, speech recognised, but Gemini returns an API error. */
    data class ApiError(val transcript: String, val code: Int) : InteractionEvent()
}

/**
 * Property test for TTS and STT mutual exclusion.
 *
 * **Property 3: TTS and STT Mutual Exclusion**
 *
 * For any sequence of interaction events, [TtsWrapper.speak] and
 * [SpeechRecognizerWrapper.listen] are never active simultaneously — at no point
 * in time are both the TTS engine speaking and the speech recognizer listening.
 *
 * **Validates: Requirements 1.8**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsAndSttMutualExclusionTest : StringSpec({

    // ── Android Log stub ──────────────────────────────────────────────────────

    beforeSpec {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    // ── Arbitrary generators ──────────────────────────────────────────────────

    /** Arbitrary non-blank transcript (the user command after wake word). */
    val arbTranscript: Arb<String> = arbitrary { rs ->
        val base = Arb.string(minSize = 3, maxSize = 30).bind()
        val trimmed = base.trim()
        if (trimmed.isBlank()) "hello" else trimmed
    }

    /** Arbitrary non-blank Gemini response text. */
    val arbResponse: Arb<String> = arbitrary { rs ->
        val base = Arb.string(minSize = 1, maxSize = 60).bind()
        val trimmed = base.trim()
        if (trimmed.isBlank()) "ok" else trimmed
    }

    /** Arbitrary speech error code. */
    val arbSpeechErrorCode: Arb<Int> = Arb.int(1..10)

    /** Arbitrary HTTP error code. */
    val arbApiErrorCode: Arb<Int> = Arb.int(400..599)

    /** Arbitrary single [InteractionEvent]. */
    val arbEvent: Arb<InteractionEvent> = arbitrary { rs ->
        when (rs.random.nextInt(4)) {
            0 -> InteractionEvent.HappyPath(
                transcript = arbTranscript.bind(),
                response = arbResponse.bind()
            )
            1 -> InteractionEvent.SpeechError(arbSpeechErrorCode.bind())
            2 -> InteractionEvent.NetworkError(arbTranscript.bind())
            else -> InteractionEvent.ApiError(
                transcript = arbTranscript.bind(),
                code = arbApiErrorCode.bind()
            )
        }
    }

    /** Arbitrary sequence of 1–5 interaction events. */
    val arbEventSequence: Arb<List<InteractionEvent>> = Arb.list(arbEvent, 1..5)

    // ── Helper: build a ViewModel that tracks concurrent TTS/STT activity ─────

    /**
     * Builds a [VoiceAssistantViewModel] whose [TtsWrapper.speak] and
     * [SpeechRecognizerWrapper.listen] mocks track concurrent invocations.
     *
     * The mocks use a shared [Mutex]-protected counter to record whether the
     * other operation is already active when each one starts. Any overlap is
     * recorded in [overlapDetected].
     *
     * The [WakeWordDetector.detect] mock returns [WakeWordResult.Detected] for
     * each event in [events], then suspends via [awaitCancellation] so the loop
     * parks at [WakeWordListening] after all events are processed.
     *
     * @return Pair of (viewModel, overlapDetected-flag-holder)
     */
    fun buildViewModel(events: List<InteractionEvent>): Pair<VoiceAssistantViewModel, () -> Boolean> {
        val wakeWordDetector = mockk<WakeWordDetector>()
        val speechRecognizer = mockk<SpeechRecognizerWrapper>()
        val tts = mockk<TtsWrapper>()
        val geminiRepository = mockk<GeminiRepository>()
        val bluetoothAudioManager = mockk<BluetoothAudioManager>()

        every { bluetoothAudioManager.isBluetoothScoAvailable } returns MutableStateFlow(false)
        every { wakeWordDetector.cancel() } returns Unit
        every { wakeWordDetector.destroy() } returns Unit
        every { speechRecognizer.cancel() } returns Unit
        every { speechRecognizer.destroy() } returns Unit
        every { tts.stop() } returns Unit
        every { tts.shutdown() } returns Unit

        // ── Concurrency tracking ──────────────────────────────────────────────
        // ttsActive / sttActive are guarded by a mutex so that the check-and-set
        // is atomic within the coroutine dispatcher.
        val mutex = Mutex()
        var ttsActive = false
        var sttActive = false
        var overlapSeen = false

        // ── WakeWordDetector: Detected for each event, then park ──────────────
        var detectCallCount = 0
        coEvery { wakeWordDetector.detect() } coAnswers {
            detectCallCount++
            if (detectCallCount <= events.size) {
                WakeWordResult.Detected
            } else {
                awaitCancellation()
            }
        }

        // ── SpeechRecognizerWrapper.listen: tracks STT activity ───────────────
        var listenCallCount = 0
        coEvery { speechRecognizer.listen() } coAnswers {
            val eventIndex = listenCallCount++
            val event = events.getOrNull(eventIndex)

            mutex.withLock {
                // Check if TTS is already active — that would be a violation
                if (ttsActive) overlapSeen = true
                sttActive = true
            }

            val result = when (event) {
                is InteractionEvent.HappyPath ->
                    SpeechResult.Success(event.transcript)
                is InteractionEvent.SpeechError ->
                    SpeechResult.Error(event.code, "Speech error ${event.code}")
                is InteractionEvent.NetworkError ->
                    SpeechResult.Success(event.transcript)
                is InteractionEvent.ApiError ->
                    SpeechResult.Success(event.transcript)
                null ->
                    SpeechResult.Error(1, "No more events")
            }

            mutex.withLock { sttActive = false }
            result
        }

        // ── TtsWrapper.speak: tracks TTS activity ─────────────────────────────
        var speakCallCount = 0
        coEvery { tts.speak(any()) } coAnswers {
            val eventIndex = speakCallCount++
            val event = events.getOrNull(eventIndex)

            mutex.withLock {
                // Check if STT is already active — that would be a violation
                if (sttActive) overlapSeen = true
                ttsActive = true
            }

            // Simulate the TTS completing (always Done for happy-path events)
            val result = TtsResult.Done

            mutex.withLock { ttsActive = false }
            result
        }

        // ── GeminiRepository.sendMessage: configured per event ────────────────
        var geminiCallCount = 0
        coEvery { geminiRepository.sendMessage(any()) } coAnswers {
            val eventIndex = geminiCallCount++
            val event = events.getOrNull(eventIndex)
            when (event) {
                is InteractionEvent.HappyPath ->
                    Result.success(event.response)
                is InteractionEvent.NetworkError ->
                    Result.failure(IOException("Simulated network failure"))
                is InteractionEvent.ApiError ->
                    Result.failure(
                        com.example.sayuri.model.ApiException(event.code, "API error ${event.code}")
                    )
                else ->
                    Result.success("ok")
            }
        }

        val viewModel = VoiceAssistantViewModel(
            wakeWordDetector = wakeWordDetector,
            speechRecognizer = speechRecognizer,
            tts = tts,
            geminiRepository = geminiRepository,
            bluetoothAudioManager = bluetoothAudioManager
        )

        return Pair(viewModel, { overlapSeen })
    }

    // ── Property 3: TTS and STT Mutual Exclusion ──────────────────────────────

    /**
     * For any sequence of interaction events, [TtsWrapper.speak] and
     * [SpeechRecognizerWrapper.listen] are never active simultaneously.
     *
     * **Validates: Requirements 1.8**
     */
    "Property 3 - TTS speak and STT listen are never active simultaneously for any event sequence" {
        forAll(
            PropTestConfig(iterations = 20),
            arbEventSequence
        ) { events ->
            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)

            var noOverlapDetected = true

            Dispatchers.setMain(dispatcher)
            try {
                runTest(dispatcher) {
                    val (viewModel, overlapDetected) = buildViewModel(events)

                    // ttsReady = true so onResume skips TTS init and starts the loop directly
                    viewModel.ttsReady = true
                    viewModel.onResume()

                    // Advance virtual time past all delays and run all pending coroutines.
                    // The final awaitCancellation() in detect() parks the loop at
                    // WakeWordListening once all events are consumed.
                    advanceUntilIdle()

                    noOverlapDetected = !overlapDetected()

                    // Cancel the loop so the ViewModel doesn't leak into the next iteration
                    viewModel.listeningJob?.cancel()
                }
            } finally {
                Dispatchers.resetMain()
            }

            noOverlapDetected
        }
    }
})
