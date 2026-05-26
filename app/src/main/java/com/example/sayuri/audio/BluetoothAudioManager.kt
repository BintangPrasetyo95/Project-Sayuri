package com.example.sayuri.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the Bluetooth SCO audio connection lifecycle for headset input/output.
 *
 * Usage:
 * 1. Call [initialize] once to register the SCO state broadcast receiver.
 * 2. Call [startBluetoothSco] before a listening or speaking session.
 * 3. Observe [isBluetoothScoAvailable] to know whether SCO connected successfully.
 * 4. Call [stopBluetoothSco] after the session ends.
 * 5. Call [release] when the component is no longer needed (e.g. in ViewModel.onDestroy).
 *
 * On Android API 31+ the [Manifest.permission.BLUETOOTH_CONNECT] permission is required
 * before any SCO operation. If the permission is absent the operation is skipped and
 * [isBluetoothScoAvailable] remains / is set to `false`.
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7
 */
class BluetoothAudioManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── StateFlow ─────────────────────────────────────────────────────────────────

    private val _isBluetoothScoAvailable = MutableStateFlow(false)

    /**
     * Emits `true` when a Bluetooth SCO connection is established and ready for audio
     * routing; `false` otherwise (including after a 3-second connection timeout).
     *
     * Requirement: 6.3, 6.4
     */
    val isBluetoothScoAvailable: StateFlow<Boolean> = _isBluetoothScoAvailable.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────────────────

    private var receiverRegistered = false
    private var timeoutJob: Job? = null

    /**
     * Internal [CoroutineScope] used exclusively for the SCO connection timeout.
     * Uses [SupervisorJob] so a cancelled timeout does not affect other coroutines.
     */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── BroadcastReceiver ─────────────────────────────────────────────────────────

    /**
     * Listens for [AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED] broadcasts and updates
     * [isBluetoothScoAvailable] accordingly.
     *
     * Requirement: 6.1, 6.3, 6.4
     */
    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )

            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    // SCO connected — cancel the timeout and emit true
                    timeoutJob?.cancel()
                    timeoutJob = null
                    _isBluetoothScoAvailable.value = true
                    Log.d(TAG, "Bluetooth SCO connected")
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    _isBluetoothScoAvailable.value = false
                    Log.d(TAG, "Bluetooth SCO disconnected or error (state=$state)")
                }
                // SCO_AUDIO_STATE_CONNECTING — intermediate state, no action needed
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Registers the [BroadcastReceiver] for [AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED].
     *
     * Must be called once before [startBluetoothSco]. Safe to call multiple times —
     * subsequent calls are no-ops if the receiver is already registered.
     *
     * Requirement: 6.1
     */
    fun initialize() {
        if (receiverRegistered) return

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        context.registerReceiver(scoStateReceiver, filter)
        receiverRegistered = true
        Log.d(TAG, "BluetoothAudioManager initialized — SCO receiver registered")
    }

    /**
     * Attempts to start a Bluetooth SCO connection.
     *
     * - Checks for [Manifest.permission.BLUETOOTH_CONNECT] on API 31+ (Requirement 6.7).
     * - Calls [AudioManager.startBluetoothSco] to initiate the connection.
     * - Starts a 3-second timeout: if [AudioManager.SCO_AUDIO_STATE_CONNECTED] is not
     *   received within [SCO_TIMEOUT_MS] milliseconds, [isBluetoothScoAvailable] is set
     *   to `false` (Requirement 6.4).
     *
     * Requirement: 6.2, 6.4, 6.7
     */
    fun startBluetoothSco() {
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted — skipping SCO start")
            _isBluetoothScoAvailable.value = false
            return
        }

        Log.d(TAG, "Starting Bluetooth SCO")
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        // Cancel any existing timeout before starting a new one
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(SCO_TIMEOUT_MS)
            // If we reach here, SCO_AUDIO_STATE_CONNECTED was never received in time
            if (_isBluetoothScoAvailable.value != true) {
                Log.w(TAG, "Bluetooth SCO connection timed out after ${SCO_TIMEOUT_MS}ms")
                _isBluetoothScoAvailable.value = false
            }
        }
    }

    /**
     * Stops the active Bluetooth SCO connection.
     *
     * Checks for [Manifest.permission.BLUETOOTH_CONNECT] on API 31+ before proceeding.
     *
     * Requirement: 6.5, 6.7
     */
    fun stopBluetoothSco() {
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted — skipping SCO stop")
            return
        }

        Log.d(TAG, "Stopping Bluetooth SCO")
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
    }

    /**
     * Releases all Bluetooth resources:
     * - Stops any active SCO connection.
     * - Cancels the pending timeout job.
     * - Unregisters the [BroadcastReceiver].
     * - Resets [isBluetoothScoAvailable] to `false`.
     * - Cancels the internal coroutine scope.
     *
     * Requirement: 6.6, 14.6
     */
    fun release() {
        stopBluetoothSco()

        timeoutJob?.cancel()
        timeoutJob = null

        if (receiverRegistered) {
            try {
                context.unregisterReceiver(scoStateReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered — safe to ignore
                Log.w(TAG, "SCO receiver was not registered when release() was called")
            }
            receiverRegistered = false
        }

        _isBluetoothScoAvailable.value = false
        scope.cancel()

        Log.d(TAG, "BluetoothAudioManager released")
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    /**
     * Returns `true` if the [Manifest.permission.BLUETOOTH_CONNECT] permission is
     * granted (required on API 31+), or if the device is running below API 31 where
     * the permission is not required.
     *
     * Requirement: 6.7
     */
    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // BLUETOOTH_CONNECT is not required below API 31
            true
        }
    }

    // ── Constants ─────────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "BluetoothAudioManager"

        /** Timeout in milliseconds to wait for SCO_AUDIO_STATE_CONNECTED (Requirement 6.4). */
        const val SCO_TIMEOUT_MS = 3_000L
    }
}
