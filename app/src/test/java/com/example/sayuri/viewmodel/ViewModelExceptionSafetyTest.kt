package com.example.sayuri.viewmodel

import android.util.Log
import com.example.sayuri.audio.BluetoothAudioManager
import com.example.sayuri.audio.SpeechRecognizerWrapper
import com.example.sayuri.audio.TtsWrapper
import com.example.sayuri.audio.WakeWordDetector
import com.example.sayuri.data.GeminiRepository
import com.example.sayuri.model.AssistantState
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Property test for ViewModel exception safety.
 *
 * **Property 18: ViewModel Exception Safety**
 *
 * For any exception thrown within a `viewModelScope` coroutine,
 * [VoiceAssistantViewModel] catches the exception and transitions to
 * [AssistantState.Error] without propagating an uncaught exception that
 * would crash the app.
 *
 * **Validates: Requirements 10.5**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelExceptionSafetyTest : StringSpec({

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
     * Arbitrary exceptions that can be thrown inside the wake word loop.
     * Covers common exception types to exercise the catch(Exception) handler.
     */
    val arbException: Arb<Exception> = Arb.element(
        RuntimeException("Simulated runtime error"),
        IllegalStateException("Simulated illegal state"),
        IllegalArgumentException("Simulated illegal argument"),
        NullPointerException("Simulated null pointer"),
        UnsupportedOperationException("Simulated unsupported operation"),
        ArithmeticException("Simulated arithmetic error"),
        IndexOutOfBoundsException("Simulated index out of bounds"),
        ClassCastException("Simulated class cast error"),
        Exception("Generic simulated exception"),
        RuntimeException("Another runtime error with a longer message for variety")
    )

    /**
     * Arbitrary non-blank exception messages to verify the error message
     * content doesn't affect the state transition.
     */
    val arbExceptionMessage: Arb<String> = Arb.string(minSize = 1, maxSize = 60)

    // ── Helper: build a ViewModel that throws on detect() ────────────────────

    /**
     * Creates a [VoiceAssistantViewModel] with all dependencies mocked via MockK.
     *
     * The [WakeWordDetector.detect] mock is configured to throw [exception] on
     * the first call, then suspend via [awaitCancellation] on subsequent calls
     * (so the loop parks at WakeWordListening after the error is handled).
     */
    fun buildViewModelThrowingOnDetect(exception: Exception): VoiceAssistantViewModel {
        val wakeWordDetector = mockk<WakeWordDetector>()
        val speechRecognizer = mockk<SpeechRecognizerWrapper>()
        val tts = mockk<TtsWrapper>()
        val geminiRepository = mockk<GeminiRepository>()
        val bluetoothAudioManager = mockk<BluetoothAudioManager>()

        // BluetoothAudioManager: expose a simple StateFlow
        every { bluetoothAudioManager.isBluetoothScoAvailable } returns MutableStateFlow(false)

        // WakeWordDetector: first call → throws the arbitrary exception;
        // second call → parks until cancelled (proves the loop recovered and restarted)
        var detectCallCount = 0
        coEvery { wakeWordDetector.detect() } coAnswers {
            detectCallCount++
            if (detectCallCount == 1) {
                throw exception
            } else {
                // Suspend until the test cancels the job — proves the loop restarted
                awaitCancellation()
            }
        }
        every { wakeWordDetector.cancel() } returns Unit
        every { wakeWordDetector.destroy() } returns Unit

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
     * advances virtual time until idle, and returns the observed states.
     *
     * Uses a single [TestCoroutineScheduler] shared between [Dispatchers.Main]
     * and [runTest] so that [delay] calls inside [viewModelScope] are advanced
     * by [advanceUntilIdle].
     *
     * State emissions are collected via a [CoroutineScope] launched on the same
     * [UnconfinedTestDispatcher] so they run eagerly without needing a separate
     * coroutine builder in scope.
     */
    fun runIteration(exception: Exception): List<AssistantState> {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val observedStates = mutableListOf<AssistantState>()

        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val viewModel = buildViewModelThrowingOnDetect(exception)

                // ttsReady = true so onResume skips TTS init and calls startWakeWordLoop directly
                viewModel.ttsReady = true

                // Collect state emissions using a CoroutineScope on the same dispatcher.
                // This avoids needing TestScope.launch or backgroundScope in scope.
                val collectScope = CoroutineScope(dispatcher)
                val collectJob = collectScope.launch {
                    viewModel.state.collect { state ->
                        observedStates.add(state)
                    }
                }

                viewModel.onResume()

                // Advance virtual time past all delays (including RECOVERY_DELAY_MS).
                // The second detect() call suspends via awaitCancellation(), so
                // advanceUntilIdle() stops there.
                advanceUntilIdle()

                // Cancel the loop and collector so the ViewModel doesn't leak
                viewModel.listeningJob?.cancel()
                collectJob.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }

        return observedStates
    }

    // ── Property 18: ViewModel Exception Safety ───────────────────────────────

    /**
     * For any exception thrown inside the wake word loop, the ViewModel must
     * transition to [AssistantState.Error] — the exception is caught and does
     * not propagate as an uncaught exception.
     *
     * **Validates: Requirements 10.5**
     */
    "Property 18 - ViewModel transitions to AssistantState.Error for any exception thrown in viewModelScope" {
        forAll(
            PropTestConfig(iterations = 20),
            arbException
        ) { exception ->
            val states = runIteration(exception)
            // The state sequence must include AssistantState.Error at some point,
            // proving the exception was caught and handled rather than propagated.
            states.any { it is AssistantState.Error }
        }
    }

    /**
     * For any exception thrown inside the wake word loop, the ViewModel must
     * eventually recover and return to [AssistantState.WakeWordListening],
     * proving the loop is not permanently broken by the exception.
     *
     * **Validates: Requirements 10.5, 10.6**
     */
    "Property 18 - ViewModel recovers to WakeWordListening after catching any exception" {
        forAll(
            PropTestConfig(iterations = 20),
            arbException
        ) { exception ->
            val states = runIteration(exception)
            // After the Error state, the loop must restart → WakeWordListening
            val errorIndex = states.indexOfFirst { it is AssistantState.Error }
            errorIndex >= 0 && states.drop(errorIndex).any { it is AssistantState.WakeWordListening }
        }
    }

    /**
     * For any exception message, the ViewModel transitions to
     * [AssistantState.Error] regardless of the exception message content.
     *
     * **Validates: Requirements 10.5**
     */
    "Property 18 - ViewModel transitions to Error regardless of exception message content" {
        forAll(
            PropTestConfig(iterations = 20),
            arbExceptionMessage
        ) { message ->
            val exception = RuntimeException(message)
            val states = runIteration(exception)
            states.any { it is AssistantState.Error }
        }
    }
})
