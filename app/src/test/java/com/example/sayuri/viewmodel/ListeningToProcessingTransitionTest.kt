package com.example.sayuri.viewmodel

import android.util.Log
import com.example.sayuri.audio.BluetoothAudioManager
import com.example.sayuri.audio.SpeechRecognizerWrapper
import com.example.sayuri.audio.TtsWrapper
import com.example.sayuri.audio.WakeWordDetector
import com.example.sayuri.data.GeminiRepository
import com.example.sayuri.model.AssistantState
import com.example.sayuri.model.SpeechResult
import com.example.sayuri.model.TtsResult
import com.example.sayuri.model.WakeWordResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
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

/**
 * Property test for LISTENING to PROCESSING transition.
 *
 * **Property 6: LISTENING to PROCESSING Transition**
 *
 * For any non-blank transcript, [VoiceAssistantViewModel] must transition to
 * [AssistantState.Processing] before [GeminiRepository.sendMessage] is invoked.
 * This guarantees the UI always shows a "processing" indicator while the network
 * call is in flight.
 *
 * **Validates: Requirements 1.4**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningToProcessingTransitionTest : StringSpec({

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

    /**
     * Arbitrary non-blank transcript — the user command delivered after wake word
     * detection. Guaranteed to survive the wake-word-prefix stripping in
     * [VoiceAssistantViewModel.startActiveListening] and remain non-blank.
     */
    val arbTranscript: Arb<String> = Arb.string(minSize = 3, maxSize = 40)
        .map { it.trim() }
        .map { if (it.isBlank()) "hello world" else it }

    // ── Helper: build a ViewModel that captures state at sendMessage call ─────

    /**
     * Builds a [VoiceAssistantViewModel] whose [GeminiRepository.sendMessage] mock
     * captures the ViewModel's [AssistantState] at the exact moment it is invoked.
     *
     * The captured state is stored in [capturedStateHolder] (a single-element list
     * so it can be mutated from inside the coAnswers lambda).
     *
     * The pipeline is configured so that:
     * 1. [WakeWordDetector.detect] returns [WakeWordResult.Detected] on the first
     *    call, then suspends via [awaitCancellation] so the loop parks at
     *    [AssistantState.WakeWordListening] after one full cycle.
     * 2. [SpeechRecognizerWrapper.listen] returns [SpeechResult.Success] with the
     *    given [transcript].
     * 3. [GeminiRepository.sendMessage] captures the current state, then returns
     *    a success result so the pipeline can complete normally.
     * 4. [TtsWrapper.speak] returns [TtsResult.Done] immediately.
     *
     * @return Pair of (viewModel, capturedStateHolder)
     */
    fun buildViewModel(
        transcript: String,
        capturedStateHolder: MutableList<AssistantState?>
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

        // SpeechRecognizerWrapper: return the arbitrary transcript
        coEvery { speechRecognizer.listen() } returns SpeechResult.Success(transcript)
        every { speechRecognizer.cancel() } returns Unit
        every { speechRecognizer.destroy() } returns Unit

        // GeminiRepository: capture the ViewModel state at the moment sendMessage is called.
        // The ViewModel reference is not available yet, so we use a late-init holder.
        // We capture via a lambda that reads from the viewModel field set after construction.
        val viewModelHolder = arrayOfNulls<VoiceAssistantViewModel>(1)
        coEvery { geminiRepository.sendMessage(any()) } coAnswers {
            // Record the state at the exact moment sendMessage is invoked
            capturedStateHolder[0] = viewModelHolder[0]?.state?.value
            Result.success("ok")
        }

        // TtsWrapper: complete immediately
        coEvery { tts.speak(any()) } returns TtsResult.Done
        every { tts.stop() } returns Unit
        every { tts.shutdown() } returns Unit

        val viewModel = VoiceAssistantViewModel(
            wakeWordDetector = wakeWordDetector,
            speechRecognizer = speechRecognizer,
            tts = tts,
            geminiRepository = geminiRepository,
            bluetoothAudioManager = bluetoothAudioManager
        )

        // Wire the holder so the sendMessage lambda can read the state
        viewModelHolder[0] = viewModel

        return viewModel
    }

    // ── Property 6: LISTENING to PROCESSING Transition ───────────────────────

    /**
     * For any non-blank transcript, the ViewModel must be in
     * [AssistantState.Processing] at the moment [GeminiRepository.sendMessage]
     * is invoked.
     *
     * **Validates: Requirements 1.4**
     */
    "Property 6 - state is Processing when GeminiRepository.sendMessage is invoked for any non-blank transcript" {
        forAll(
            PropTestConfig(iterations = 20),
            arbTranscript
        ) { transcript ->
            val capturedStateHolder = mutableListOf<AssistantState?>(null)

            val scheduler = TestCoroutineScheduler()
            val dispatcher = UnconfinedTestDispatcher(scheduler)

            var stateWasProcessing = false

            Dispatchers.setMain(dispatcher)
            try {
                runTest(dispatcher) {
                    val viewModel = buildViewModel(transcript, capturedStateHolder)

                    // ttsReady = true so onResume skips TTS init and calls startWakeWordLoop directly
                    viewModel.ttsReady = true
                    viewModel.onResume()

                    // Advance virtual time past all delays and run all pending coroutines.
                    // The second detect() call suspends via awaitCancellation(), so
                    // advanceUntilIdle() stops there after the full cycle completes.
                    advanceUntilIdle()

                    // The captured state must be Processing — set before sendMessage was called
                    stateWasProcessing = capturedStateHolder[0] == AssistantState.Processing

                    // Cancel the loop so the ViewModel doesn't leak into the next iteration
                    viewModel.listeningJob?.cancel()
                }
            } finally {
                Dispatchers.resetMain()
            }

            stateWasProcessing
        }
    }
})
