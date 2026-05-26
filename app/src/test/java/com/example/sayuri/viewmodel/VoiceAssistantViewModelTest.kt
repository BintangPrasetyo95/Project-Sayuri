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
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException

/**
 * Unit tests for [VoiceAssistantViewModel] state transitions.
 *
 * All dependencies are mocked with MockK.
 * Coroutines are controlled with [UnconfinedTestDispatcher] + [TestCoroutineScheduler].
 *
 * Requirements: 1.1–1.9, 2.1–2.6, 10.1–10.6
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAssistantViewModelTest : DescribeSpec({

    // ── Android Log stub ──────────────────────────────────────────────────────

    beforeSpec {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
    }

    // ── Shared mock factory ───────────────────────────────────────────────────

    /**
     * Creates a fresh set of mocked dependencies with sensible defaults:
     * - [WakeWordDetector.detect] suspends via [awaitCancellation] (loop parks)
     * - [SpeechRecognizerWrapper.listen] returns blank transcript (no-op)
     * - [TtsWrapper.speak] returns [TtsResult.Done]
     * - [GeminiRepository.sendMessage] returns success("ok")
     * - [BluetoothAudioManager.isBluetoothScoAvailable] emits false
     */
    fun buildMocks(
        detectAnswer: suspend () -> WakeWordResult = { awaitCancellation() },
        listenAnswer: suspend () -> SpeechResult = { SpeechResult.Success("  ") },
        ttsAnswer: suspend () -> TtsResult = { TtsResult.Done },
        geminiAnswer: suspend () -> Result<String> = { Result.success("ok") }
    ): Triple<VoiceAssistantViewModel, Array<Any>, MutableStateFlow<Boolean>> {
        val wakeWordDetector = mockk<WakeWordDetector>()
        val speechRecognizer = mockk<SpeechRecognizerWrapper>()
        val tts = mockk<TtsWrapper>()
        val geminiRepository = mockk<GeminiRepository>()
        val bluetoothAudioManager = mockk<BluetoothAudioManager>()
        val scoFlow = MutableStateFlow(false)

        every { bluetoothAudioManager.isBluetoothScoAvailable } returns scoFlow
        coEvery { wakeWordDetector.detect() } coAnswers { detectAnswer() }
        every { wakeWordDetector.cancel() } returns Unit
        every { wakeWordDetector.destroy() } returns Unit
        coEvery { speechRecognizer.listen() } coAnswers { listenAnswer() }
        every { speechRecognizer.cancel() } returns Unit
        every { speechRecognizer.destroy() } returns Unit
        coEvery { tts.speak(any()) } coAnswers { ttsAnswer() }
        coEvery { tts.initialize() } returns true
        every { tts.stop() } returns Unit
        every { tts.shutdown() } returns Unit
        coEvery { geminiRepository.sendMessage(any()) } coAnswers { geminiAnswer() }

        val vm = VoiceAssistantViewModel(
            wakeWordDetector = wakeWordDetector,
            speechRecognizer = speechRecognizer,
            tts = tts,
            geminiRepository = geminiRepository,
            bluetoothAudioManager = bluetoothAudioManager
        )

        val deps = arrayOf<Any>(
            wakeWordDetector, speechRecognizer, tts, geminiRepository, bluetoothAudioManager
        )
        return Triple(vm, deps, scoFlow)
    }

    /**
     * Runs a test with a shared [TestCoroutineScheduler] so that [Dispatchers.Main]
     * and [runTest] share the same virtual clock, allowing [advanceUntilIdle] to
     * advance delays inside [viewModelScope].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun runVmTest(block: suspend kotlinx.coroutines.test.TestScope.(
        scheduler: TestCoroutineScheduler,
        dispatcher: kotlinx.coroutines.test.TestDispatcher
    ) -> Unit) {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                block(scheduler, dispatcher)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Permission granted on start → WakeWordListening
    // ─────────────────────────────────────────────────────────────────────────

    describe("onResume with TTS already ready") {
        it("transitions to WakeWordListening immediately (Requirement 1.1, 14.5)") {
            runVmTest { _, _ ->
                val (vm, _, _) = buildMocks()
                vm.ttsReady = true

                vm.onResume()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }
    }

    describe("onResume with TTS not yet initialised") {
        it("initialises TTS, then transitions to WakeWordListening (Requirement 10.4, 14.5)") {
            runVmTest { _, _ ->
                val (vm, _, _) = buildMocks()
                // ttsReady starts false by default

                vm.onResume()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.ttsReady shouldBe true
                vm.listeningJob?.cancel()
            }
        }

        it("sets Error state when TTS initialisation fails (Requirement 10.4)") {
            runVmTest { _, _ ->
                val (vm, deps, _) = buildMocks()
                val tts = deps[2] as TtsWrapper
                coEvery { tts.initialize() } returns false

                vm.onResume()
                advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<AssistantState.Error>()
                vm.ttsReady shouldBe false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Wake word detected → ActiveListening
    // ─────────────────────────────────────────────────────────────────────────

    describe("wake word detection") {
        it("transitions to ActiveListening when wake word is detected (Requirement 1.3)") {
            runVmTest { _, _ ->
                // detect() returns Detected once, then parks
                var callCount = 0
                val (vm, deps, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    }
                )
                val wakeWordDetector = deps[0] as WakeWordDetector

                // Capture the state at the moment listen() is called
                val stateAtListen = mutableListOf<AssistantState?>(null)
                val speechRecognizer = deps[1] as SpeechRecognizerWrapper
                coEvery { speechRecognizer.listen() } coAnswers {
                    stateAtListen[0] = vm.state.value
                    awaitCancellation() // park so we can inspect the state
                }

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                stateAtListen[0] shouldBe AssistantState.ActiveListening
                verify { wakeWordDetector.cancel() }

                vm.listeningJob?.cancel()
            }
        }

        it("WakeWordDetector is cancelled before SpeechRecognizer starts (Requirement 1.8)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, deps, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    }
                )
                val wakeWordDetector = deps[0] as WakeWordDetector
                val speechRecognizer = deps[1] as SpeechRecognizerWrapper

                val cancelledBeforeListen = mutableListOf(false)
                coEvery { speechRecognizer.listen() } coAnswers {
                    // At this point, cancel() should already have been called
                    cancelledBeforeListen[0] = true
                    awaitCancellation()
                }

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                cancelledBeforeListen[0] shouldBe true
                verify { wakeWordDetector.cancel() }

                vm.listeningJob?.cancel()
            }
        }

        it("loops back to WakeWordListening when wake word is not detected (Requirement 1a.3)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount < 3) WakeWordResult.NotDetected else awaitCancellation()
                    }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Transcript received → Processing → Speaking
    // ─────────────────────────────────────────────────────────────────────────

    describe("successful interaction flow") {
        it("transitions Processing → Speaking after transcript received (Requirements 1.4, 1.5)") {
            runVmTest { _, _ ->
                val responseText = "Hello, Bintang!"
                var callCount = 0
                val (vm, deps, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("what time is it") },
                    geminiAnswer = { Result.success(responseText) }
                )

                // Capture state when speak() is called
                val stateAtSpeak = mutableListOf<AssistantState?>(null)
                val tts = deps[2] as TtsWrapper
                coEvery { tts.speak(any()) } coAnswers {
                    stateAtSpeak[0] = vm.state.value
                    TtsResult.Done
                }

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                stateAtSpeak[0] shouldBe AssistantState.Speaking(responseText)
                vm.listeningJob?.cancel()
            }
        }

        it("state is Processing when GeminiRepository.sendMessage is called (Requirement 1.4)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, deps, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("tell me a joke") }
                )

                val stateAtSendMessage = mutableListOf<AssistantState?>(null)
                val geminiRepository = deps[3] as GeminiRepository
                coEvery { geminiRepository.sendMessage(any()) } coAnswers {
                    stateAtSendMessage[0] = vm.state.value
                    Result.success("ok")
                }

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                stateAtSendMessage[0] shouldBe AssistantState.Processing
                vm.listeningJob?.cancel()
            }
        }

        it("strips wake word prefix from transcript before sending to Gemini (Requirement 1.9)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, deps, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("Sayuri what is the weather") }
                )

                val capturedText = mutableListOf<String?>(null)
                val geminiRepository = deps[3] as GeminiRepository
                coEvery { geminiRepository.sendMessage(any()) } coAnswers {
                    capturedText[0] = firstArg()
                    Result.success("ok")
                }

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                // The wake word prefix should be stripped
                capturedText[0]?.lowercase()?.contains("sayuri") shouldBe false
                vm.listeningJob?.cancel()
            }
        }

        it("does NOT send to Gemini when transcript is only the wake word (Requirement 1.9)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, deps, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("Sayuri") }
                )

                val geminiRepository = deps[3] as GeminiRepository

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                coVerify(exactly = 0) { geminiRepository.sendMessage(any()) }
                vm.listeningJob?.cancel()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. TTS done → WakeWordListening
    // ─────────────────────────────────────────────────────────────────────────

    describe("after TTS completes") {
        it("automatically returns to WakeWordListening (Requirement 1.5)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("hello") },
                    geminiAnswer = { Result.success("Hi there!") },
                    ttsAnswer = { TtsResult.Done }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                // After TTS done, the loop restarts → WakeWordListening
                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Speech error → Error → WakeWordListening (after delay)
    // ─────────────────────────────────────────────────────────────────────────

    describe("speech recognition error") {
        it("transitions to Error with correct message (Requirement 10.1)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Error(6, "No match") } // ERROR_NO_MATCH = 6
                )

                vm.ttsReady = true
                vm.onResume()
                // Advance only enough to trigger the error state, before the recovery delay
                advanceTimeBy(100)

                vm.state.value shouldBe AssistantState.Error("Couldn't hear you. Try again.")
                vm.listeningJob?.cancel()
            }
        }

        it("recovers to WakeWordListening after recovery delay (Requirements 1.6, 10.6)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected
                        else if (callCount == 2) WakeWordResult.Detected
                        else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Error(6, "No match") }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }

        it("recovery delay is at most 1 second (Requirements 1.6, 10.6)") {
            runVmTest { _, _ ->
                VoiceAssistantViewModel.RECOVERY_DELAY_MS shouldBe 1_000L
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Network error → Error → WakeWordListening
    // ─────────────────────────────────────────────────────────────────────────

    describe("network error from Gemini") {
        it("transitions to Error with 'No internet connection' message (Requirement 10.2)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("hello") },
                    geminiAnswer = { Result.failure(IOException("Network unreachable")) }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceTimeBy(100)

                vm.state.value shouldBe AssistantState.Error("No internet connection")
                vm.listeningJob?.cancel()
            }
        }

        it("recovers to WakeWordListening after recovery delay (Requirements 1.6, 10.6)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected
                        else if (callCount == 2) WakeWordResult.Detected
                        else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("hello") },
                    geminiAnswer = { Result.failure(IOException("Network unreachable")) }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }
    }

    describe("API error from Gemini") {
        it("transitions to Error with 'Service error. Try again.' message (Requirement 10.3)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("hello") },
                    geminiAnswer = { Result.failure(ApiException(500, "Internal Server Error")) }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceTimeBy(100)

                vm.state.value shouldBe AssistantState.Error("Service error. Try again.")
                vm.listeningJob?.cancel()
            }
        }

        it("recovers to WakeWordListening after recovery delay (Requirements 1.6, 10.6)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Detected
                        else if (callCount == 2) WakeWordResult.Detected
                        else awaitCancellation()
                    },
                    listenAnswer = { SpeechResult.Success("hello") },
                    geminiAnswer = { Result.failure(ApiException(500, "Internal Server Error")) }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }
    }

    describe("wake word error") {
        it("transitions to Error and recovers to WakeWordListening (Requirements 1.6, 10.6)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) WakeWordResult.Error(7, "Recognizer error")
                        else awaitCancellation()
                    }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceTimeBy(100)

                vm.state.value.shouldBeInstanceOf<AssistantState.Error>()
                vm.listeningJob?.cancel()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Mic press in each state → correct next state
    // ─────────────────────────────────────────────────────────────────────────

    describe("onMicPressed") {

        /**
         * Helper: force the ViewModel's internal state to [targetState] via reflection.
         */
        fun forceState(vm: VoiceAssistantViewModel, targetState: AssistantState) {
            val stateField = VoiceAssistantViewModel::class.java
                .getDeclaredField("_state")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (stateField.get(vm) as MutableStateFlow<AssistantState>).value = targetState
        }

        it("WakeWordListening → Idle: cancels detector and recognizer (Requirement 2.2)") {
            runVmTest { _, _ ->
                val (vm, deps, _) = buildMocks()
                val wakeWordDetector = deps[0] as WakeWordDetector
                val speechRecognizer = deps[1] as SpeechRecognizerWrapper

                forceState(vm, AssistantState.WakeWordListening)
                vm.onMicPressed()

                vm.state.value shouldBe AssistantState.Idle
                verify { wakeWordDetector.cancel() }
                verify { speechRecognizer.cancel() }
            }
        }

        it("ActiveListening → Idle: cancels detector and recognizer (Requirement 2.3)") {
            runVmTest { _, _ ->
                val (vm, deps, _) = buildMocks()
                val wakeWordDetector = deps[0] as WakeWordDetector
                val speechRecognizer = deps[1] as SpeechRecognizerWrapper

                forceState(vm, AssistantState.ActiveListening)
                vm.onMicPressed()

                vm.state.value shouldBe AssistantState.Idle
                verify { wakeWordDetector.cancel() }
                verify { speechRecognizer.cancel() }
            }
        }

        it("Speaking → Idle: stops TTS (Requirement 2.5)") {
            runVmTest { _, _ ->
                val (vm, deps, _) = buildMocks()
                val tts = deps[2] as TtsWrapper

                forceState(vm, AssistantState.Speaking("Hello"))
                vm.onMicPressed()

                vm.state.value shouldBe AssistantState.Idle
                verify { tts.stop() }
            }
        }

        it("Idle → WakeWordListening: resumes wake word loop (Requirement 2.4)") {
            runVmTest { _, _ ->
                val (vm, _, _) = buildMocks()
                // Start in Idle (default)
                vm.state.value shouldBe AssistantState.Idle

                vm.onMicPressed()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }

        it("Processing → Processing: press is ignored (Requirement 2.6)") {
            runVmTest { _, _ ->
                val (vm, _, _) = buildMocks()

                forceState(vm, AssistantState.Processing)
                vm.onMicPressed()

                vm.state.value shouldBe AssistantState.Processing
            }
        }

        it("Error → WakeWordListening: resumes wake word loop (Requirement 2.6 / Error recovery)") {
            runVmTest { _, _ ->
                val (vm, _, _) = buildMocks()

                forceState(vm, AssistantState.Error("Something went wrong"))
                vm.onMicPressed()
                advanceUntilIdle()

                vm.state.value shouldBe AssistantState.WakeWordListening
                vm.listeningJob?.cancel()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Lifecycle hooks
    // ─────────────────────────────────────────────────────────────────────────

    describe("onPause") {
        it("cancels listening job and transitions to Idle (Requirement 14.4)") {
            runVmTest { _, _ ->
                val (vm, deps, _) = buildMocks()
                val wakeWordDetector = deps[0] as WakeWordDetector
                val speechRecognizer = deps[1] as SpeechRecognizerWrapper

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                vm.onPause()

                vm.state.value shouldBe AssistantState.Idle
                vm.listeningJob shouldBe null
                verify { wakeWordDetector.cancel() }
                verify { speechRecognizer.cancel() }
            }
        }
    }

    describe("onDestroy") {
        it("releases all resources (Requirement 14.1)") {
            runVmTest { _, _ ->
                val (vm, deps, _) = buildMocks()
                val wakeWordDetector = deps[0] as WakeWordDetector
                val speechRecognizer = deps[1] as SpeechRecognizerWrapper
                val tts = deps[2] as TtsWrapper
                val bluetoothAudioManager = deps[4] as BluetoothAudioManager

                every { bluetoothAudioManager.release() } returns Unit

                vm.ttsReady = true
                vm.onResume()
                advanceUntilIdle()

                vm.onDestroy()

                verify { wakeWordDetector.destroy() }
                verify { speechRecognizer.destroy() }
                verify { tts.shutdown() }
                verify { bluetoothAudioManager.release() }
                vm.listeningJob shouldBe null
                vm.ttsReady shouldBe false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Exception safety
    // ─────────────────────────────────────────────────────────────────────────

    describe("unexpected exception in wake word loop") {
        it("transitions to Error without crashing (Requirement 10.5)") {
            runVmTest { _, _ ->
                var callCount = 0
                val (vm, _, _) = buildMocks(
                    detectAnswer = {
                        callCount++
                        if (callCount == 1) throw RuntimeException("Unexpected crash!")
                        else awaitCancellation()
                    }
                )

                vm.ttsReady = true
                vm.onResume()
                advanceTimeBy(100)

                vm.state.value.shouldBeInstanceOf<AssistantState.Error>()
                vm.listeningJob?.cancel()
            }
        }
    }
})
