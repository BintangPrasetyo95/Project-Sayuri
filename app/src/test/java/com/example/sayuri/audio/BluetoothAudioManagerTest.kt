package com.example.sayuri.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Unit tests for [BluetoothAudioManager] SCO state transitions.
 *
 * Strategy:
 * - Mock [Context] and [AudioManager] using MockK so no Android runtime is needed.
 * - Capture the [BroadcastReceiver] registered via [Context.registerReceiver] using a
 *   MockK [slot], then manually invoke [BroadcastReceiver.onReceive] with a mocked
 *   [Intent] to simulate SCO state broadcasts.
 * - Use [kotlinx.coroutines.test] with [StandardTestDispatcher] and [advanceTimeBy] to
 *   fast-forward the 3-second SCO connection timeout without real wall-clock delays.
 * - [android.util.Log] is stubbed via [mockkStatic] so JVM tests don't crash on Log calls.
 * - [Build.VERSION.SDK_INT] is 0 in JVM unit tests (below API 31), so
 *   [hasBluetoothConnectPermission] always returns `true` — no permission mocking needed.
 *
 * Requirements: 6.3, 6.4, 6.6
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothAudioManagerTest : StringSpec({

    // ── Shared helpers ────────────────────────────────────────────────────────────

    /**
     * Creates a fully-mocked [Context] + [AudioManager] pair and returns them together
     * with a [slot] that will capture the [BroadcastReceiver] passed to
     * [Context.registerReceiver].
     */
    fun buildMocks(): Triple<Context, AudioManager, io.mockk.CapturingSlot<BroadcastReceiver>> {
        // Stub android.util.Log so JVM tests don't throw UnsatisfiedLinkError
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        val mockAudioManager = mockk<AudioManager>(relaxed = true)
        val mockContext = mockk<Context>(relaxed = true)

        // Return the mocked AudioManager when getSystemService(AUDIO_SERVICE) is called
        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockAudioManager

        // Capture the BroadcastReceiver registered by initialize()
        val receiverSlot = slot<BroadcastReceiver>()
        every {
            mockContext.registerReceiver(capture(receiverSlot), any<IntentFilter>())
        } returns null

        every { mockContext.unregisterReceiver(any()) } just runs

        return Triple(mockContext, mockAudioManager, receiverSlot)
    }

    /**
     * Builds a mocked [Intent] that simulates an [AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED]
     * broadcast with the given [scoState].
     */
    fun buildScoIntent(scoState: Int): Intent {
        val intent = mockk<Intent>()
        every { intent.action } returns AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED
        every {
            intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )
        } returns scoState
        return intent
    }

    // ── Test: SCO_AUDIO_STATE_CONNECTED → isBluetoothScoAvailable == true ─────────

    /**
     * Requirement 6.3:
     * WHEN the Bluetooth SCO connection is established, THE BluetoothAudioManager SHALL
     * expose `isBluetoothScoAvailable = true` via its StateFlow.
     */
    "SCO_AUDIO_STATE_CONNECTED broadcast sets isBluetoothScoAvailable to true" {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        try {
            runTest(testDispatcher) {
                val (mockContext, _, receiverSlot) = buildMocks()
                val manager = BluetoothAudioManager(mockContext)

                // Register the receiver
                manager.initialize()

                // Start SCO — this also starts the 3-second timeout coroutine
                manager.startBluetoothSco()

                // Verify initial state is false before any broadcast
                manager.isBluetoothScoAvailable.value shouldBe false

                // Simulate the SCO_AUDIO_STATE_CONNECTED broadcast
                val connectedIntent = buildScoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED)
                receiverSlot.captured.onReceive(mockContext, connectedIntent)

                // After the CONNECTED broadcast, the StateFlow should be true
                manager.isBluetoothScoAvailable.value shouldBe true

                manager.release()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── Test: 3-second timeout → isBluetoothScoAvailable == false ────────────────

    /**
     * Requirement 6.4:
     * WHEN the Bluetooth SCO connection does not establish within 3 seconds, THE
     * BluetoothAudioManager SHALL emit `isBluetoothScoAvailable = false`.
     */
    "3-second timeout without SCO connection sets isBluetoothScoAvailable to false" {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        try {
            runTest(testDispatcher) {
                val (mockContext, _, _) = buildMocks()
                val manager = BluetoothAudioManager(mockContext)

                manager.initialize()
                manager.startBluetoothSco()

                // State should still be false before the timeout fires
                manager.isBluetoothScoAvailable.value shouldBe false

                // Fast-forward past the 3-second timeout
                advanceTimeBy(BluetoothAudioManager.SCO_TIMEOUT_MS + 1)

                // After the timeout, the StateFlow should remain / be set to false
                manager.isBluetoothScoAvailable.value shouldBe false

                manager.release()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Requirement 6.4 (complementary):
     * WHEN the SCO connection is established before the 3-second timeout, the timeout
     * is cancelled and `isBluetoothScoAvailable` stays `true` after the timeout window.
     */
    "SCO connection before timeout keeps isBluetoothScoAvailable true after timeout window" {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        try {
            runTest(testDispatcher) {
                val (mockContext, _, receiverSlot) = buildMocks()
                val manager = BluetoothAudioManager(mockContext)

                manager.initialize()
                manager.startBluetoothSco()

                // Simulate connection arriving before the timeout
                val connectedIntent = buildScoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED)
                receiverSlot.captured.onReceive(mockContext, connectedIntent)

                manager.isBluetoothScoAvailable.value shouldBe true

                // Fast-forward past the timeout window — value should remain true
                advanceTimeBy(BluetoothAudioManager.SCO_TIMEOUT_MS + 1)

                manager.isBluetoothScoAvailable.value shouldBe true

                manager.release()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── Test: release() unregisters receiver and stops SCO ────────────────────────

    /**
     * Requirement 6.6:
     * WHEN the App is destroyed, THE BluetoothAudioManager SHALL release all Bluetooth
     * resources and unregister the BroadcastReceiver.
     */
    "release() unregisters the BroadcastReceiver" {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        try {
            runTest(testDispatcher) {
                val (mockContext, _, receiverSlot) = buildMocks()
                val manager = BluetoothAudioManager(mockContext)

                manager.initialize()

                // Verify the receiver was registered
                verify(exactly = 1) {
                    mockContext.registerReceiver(any(), any<IntentFilter>())
                }

                manager.release()

                // Verify the receiver was unregistered with the same instance
                verify(exactly = 1) {
                    mockContext.unregisterReceiver(receiverSlot.captured)
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Requirement 6.6 (complementary):
     * WHEN release() is called, THE BluetoothAudioManager SHALL stop the SCO connection
     * and reset isBluetoothScoAvailable to false.
     */
    "release() stops SCO and resets isBluetoothScoAvailable to false" {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        try {
            runTest(testDispatcher) {
                val (mockContext, mockAudioManager, receiverSlot) = buildMocks()
                val manager = BluetoothAudioManager(mockContext)

                manager.initialize()
                manager.startBluetoothSco()

                // Simulate SCO connected
                val connectedIntent = buildScoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED)
                receiverSlot.captured.onReceive(mockContext, connectedIntent)
                manager.isBluetoothScoAvailable.value shouldBe true

                manager.release()

                // After release, the StateFlow should be false
                manager.isBluetoothScoAvailable.value shouldBe false

                // AudioManager.stopBluetoothSco() should have been called (once from release)
                @Suppress("DEPRECATION")
                verify(atLeast = 1) { mockAudioManager.stopBluetoothSco() }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Requirement 6.6 (idempotence):
     * Calling release() when the receiver was never registered should not throw.
     */
    "release() without prior initialize() does not throw" {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        try {
            runTest(testDispatcher) {
                val (mockContext, _, _) = buildMocks()
                val manager = BluetoothAudioManager(mockContext)

                // release() without initialize() — should be a no-op for unregisterReceiver
                manager.release()

                // unregisterReceiver should NOT have been called
                verify(exactly = 0) { mockContext.unregisterReceiver(any()) }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ── Test: SCO_AUDIO_STATE_DISCONNECTED broadcast ───────────────────────────────

    /**
     * Requirement 6.3 (disconnected path):
     * WHEN a SCO_AUDIO_STATE_DISCONNECTED broadcast is received, isBluetoothScoAvailable
     * SHALL be set to false.
     */
    "SCO_AUDIO_STATE_DISCONNECTED broadcast sets isBluetoothScoAvailable to false" {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        try {
            runTest(testDispatcher) {
                val (mockContext, _, receiverSlot) = buildMocks()
                val manager = BluetoothAudioManager(mockContext)

                manager.initialize()
                manager.startBluetoothSco()

                // First connect
                val connectedIntent = buildScoIntent(AudioManager.SCO_AUDIO_STATE_CONNECTED)
                receiverSlot.captured.onReceive(mockContext, connectedIntent)
                manager.isBluetoothScoAvailable.value shouldBe true

                // Then disconnect
                val disconnectedIntent = buildScoIntent(AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                receiverSlot.captured.onReceive(mockContext, disconnectedIntent)
                manager.isBluetoothScoAvailable.value shouldBe false

                manager.release()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
})
