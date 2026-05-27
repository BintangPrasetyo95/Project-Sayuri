package com.example.sayuri

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.provider.Settings
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import com.example.sayuri.audio.TtsWrapper
import com.example.sayuri.model.AssistantState
import android.util.Log
import com.example.sayuri.agent.ScreenAgent
import com.example.sayuri.service.SayuriNotificationService
import com.example.sayuri.viewmodel.VoiceAssistantViewModel
import com.example.sayuri.viewmodel.VoiceAssistantViewModelFactory
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

/**
 * Single-screen entry point for Sayuri.
 *
 * Responsibilities:
 * - Inflate the dark-themed layout (activity_main.xml)
 * - Hold references to all UI views
 * - Observe [VoiceAssistantViewModel.state] and call [renderState] on every emission
 * - Bind mic button click to [VoiceAssistantViewModel.onMicPressed]
 * - Map each [AssistantState] to the correct visual representation
 * - Manage pulse animations and speaking waveform animations
 * - Request RECORD_AUDIO and BLUETOOTH_CONNECT permissions at runtime
 *
 * Note: ViewModel factory and dependency injection are wired in task 13.4.
 * Lifecycle hooks (onResume/onPause/onDestroy delegation to ViewModel) are added in task 13.4.
 *
 * Requirements: 2.7, 2.8, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 9.1, 9.2, 9.3, 9.4
 */
class MainActivity : AppCompatActivity() {

    // ── Constants ─────────────────────────────────────────────────────────────────

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
    }

    // ── ViewModel ─────────────────────────────────────────────────────────────────

    /**
     * The ViewModel that drives the UI state.
     *
     * Instantiated here with a placeholder (no-arg) approach so that state observation
     * and rendering logic (task 13.2) can be implemented and tested independently.
     * Full factory-based injection with all dependencies is wired in task 13.4.
     *
     * Requirements: 2.7, 2.8, 8.2
     */
    internal lateinit var viewModel: VoiceAssistantViewModel
    private lateinit var screenAgent: ScreenAgent

    // ── View references ───────────────────────────────────────────────────────────

    private lateinit var micButton: FloatingActionButton
    private lateinit var pulseRing: View
    private lateinit var processingSpinner: ProgressBar
    private lateinit var speakingWaveform: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var subtitleScroll: android.widget.ScrollView
    private lateinit var voiceButton: com.google.android.material.button.MaterialButton
    private lateinit var screenAgentButton: com.google.android.material.button.MaterialButton

    // Waveform bar views
    private lateinit var waveBar1: View
    private lateinit var waveBar2: View
    private lateinit var waveBar3: View
    private lateinit var waveBar4: View
    private lateinit var waveBar5: View

    // Active waveform animator set (cancelled when leaving Speaking state)
    private var waveformAnimatorSet: AnimatorSet? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        // Instantiate ViewModel via ViewModelProvider with a factory that injects all
        // dependencies (Requirements: 14.1, 14.4, 14.5).
        val factory = VoiceAssistantViewModelFactory(applicationContext)
        viewModel = ViewModelProvider(this, factory)[VoiceAssistantViewModel::class.java]
        screenAgent = ScreenAgent(this, BuildConfig.GEMINI_API_KEY)

        observeState()
        bindMicButton()
        bindVoiceButton()
        bindScreenAgentButton()

        checkAndRequestPermissions()
        checkNotificationListenerPermission()
        // Uncomment the next line to run a quick screen-agent smoke test on startup.
        // startScreenAgentTest()
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
        // Delegate to ViewModel only when RECORD_AUDIO permission is already granted.
        // If the permission is not yet granted, onRequestPermissionsResult will call
        // viewModel.onResume() once the user grants it (Requirement 14.5).
        if (hasRecordAudioPermission()) {
            viewModel.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause wake word detection and any active listening session (Requirement 14.4).
        viewModel.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWaveformAnimation()
        // Release all audio and Bluetooth resources via the ViewModel (Requirement 14.1).
        viewModel.onDestroy()
    }

    // ── Permission handling ───────────────────────────────────────────────────────

    /**
     * Checks whether RECORD_AUDIO (and BLUETOOTH_CONNECT on API 31+) are granted,
     * and requests any that are missing.
     *
     * Called from [onCreate] and [onResume] so that the user is prompted on first
     * launch and again if they revoke a permission while the app is backgrounded.
     *
     * Requirements: 9.1, 9.2
     */
    internal fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (!hasRecordAudioPermission()) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    /**
     * Returns `true` if the RECORD_AUDIO permission is currently granted.
     *
     * Requirements: 9.1
     */
    internal fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Handles the result of the runtime permission request.
     *
     * - If RECORD_AUDIO is granted: enables the mic button and calls
     *   [VoiceAssistantViewModel.onResume] (if the ViewModel is initialized) so
     *   that always-on listening starts automatically.
     * - If RECORD_AUDIO is denied: disables the mic button and shows a rationale
     *   dialog explaining why the permission is required.
     *
     * Requirements: 9.3, 9.4
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_CODE_PERMISSIONS) return

        val recordAudioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (recordAudioIndex == -1) return // RECORD_AUDIO was not part of this request

        val recordAudioGranted =
            grantResults[recordAudioIndex] == PackageManager.PERMISSION_GRANTED

        if (recordAudioGranted) {
            // Requirement 9.4: enable mic button and start always-on listening
            micButton.isEnabled = true
            if (::viewModel.isInitialized) {
                viewModel.onResume()
            }
        } else {
            // Requirement 9.3: disable mic button and show rationale dialog
            micButton.isEnabled = false
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_rationale_title))
                .setMessage(getString(R.string.permission_rationale_message))
                .setPositiveButton(getString(R.string.permission_rationale_ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    // ── ViewModel observation ─────────────────────────────────────────────────────

    /**
     * Observes [VoiceAssistantViewModel.state] using [lifecycleScope] +
     * [repeatOnLifecycle] so that collection is automatically paused when the
     * Activity is stopped and resumed when it returns to the STARTED state.
     *
     * Calls [renderState] on every emission.
     *
     * Requirements: 8.2
     */
    internal fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderState(state)
                }
            }
        }
    }
    private fun startScreenAgentTest() {
        lifecycleScope.launch {
            val result = screenAgent.run("Open Chrome and search for Kotlin tutorials")
            Log.d("ScreenAgentTest", result)
        }
    }
    // ── Notification listener permission ─────────────────────────────────────────

    /**
     * Checks if Sayuri has notification access. If not, shows a one-time dialog
     * directing the user to Android Settings to grant it.
     */
    private fun checkNotificationListenerPermission() {
        if (isNotificationListenerEnabled()) return

        AlertDialog.Builder(this)
            .setTitle("Enable Notification Access")
            .setMessage(
                "To read and reply to messages (WhatsApp, Telegram, etc.) by voice, " +
                "Sayuri needs Notification Access.\n\nTap OK to open Settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, SayuriNotificationService::class.java)
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(cn.flattenToString())
    }

    // ── Voice selector button ─────────────────────────────────────────────────────

    /**
     * Cycles through TTS locales on each tap and updates the button label.
     * Order: EN-US → EN-AU → EN-GB → ID → EN-US → …
     */
    internal fun bindVoiceButton() {
        val locales = TtsWrapper.TtsLocale.entries
        updateVoiceButtonLabel(viewModel.getTtsLocale())

        voiceButton.setOnClickListener {
            val current = viewModel.getTtsLocale()
            val next = locales[(current.ordinal + 1) % locales.size]
            viewModel.setTtsLocale(next)
            updateVoiceButtonLabel(next)
        }
    }

    private fun updateVoiceButtonLabel(locale: TtsWrapper.TtsLocale) {
        voiceButton.text = "🗣 ${locale.displayName}"
    }

    // ── Mic button binding ────────────────────────────────────────────────────────

    /**
     * Binds the mic button's click listener to [VoiceAssistantViewModel.onMicPressed].
     *
     * Requirements: 2.7
     */
    internal fun bindScreenAgentButton() {
        screenAgentButton.setOnClickListener {
            lifecycleScope.launch {
                val task = "Open Chrome and search for Kotlin tutorials"
                statusText.text = "Running screen agent test..."
                val result = screenAgent.run(task)
                statusText.text = "ScreenAgent result: ${result.take(120)}"
                Toast.makeText(this@MainActivity, "ScreenAgent result shown in status text", Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun bindMicButton() {
        micButton.setOnClickListener {
            viewModel.onMicPressed()
        }
    }

    // ── View binding ──────────────────────────────────────────────────────────────

    private fun bindViews() {
        micButton = findViewById(R.id.micButton)
        pulseRing = findViewById(R.id.pulseRing)
        processingSpinner = findViewById(R.id.processingSpinner)
        speakingWaveform = findViewById(R.id.speakingWaveform)
        statusText = findViewById(R.id.statusText)
        subtitleText = findViewById(R.id.subtitleText)
        subtitleScroll = findViewById(R.id.subtitleScroll)
        voiceButton = findViewById(R.id.voiceButton)
        screenAgentButton = findViewById(R.id.screenAgentButton)

        waveBar1 = findViewById(R.id.waveBar1)
        waveBar2 = findViewById(R.id.waveBar2)
        waveBar3 = findViewById(R.id.waveBar3)
        waveBar4 = findViewById(R.id.waveBar4)
        waveBar5 = findViewById(R.id.waveBar5)
    }

    // ── State rendering ───────────────────────────────────────────────────────────

    /**
     * Maps an [AssistantState] to the correct UI representation.
     *
     * Called by the ViewModel observer on every state emission.
     *
     * Visual mapping per Requirement 8:
     * - [AssistantState.Idle]              → mic-off icon, muted tint, "Paused", no animation
     * - [AssistantState.WakeWordListening] → mic icon, active tint, dim pulse, status text
     * - [AssistantState.ActiveListening]   → mic icon, active tint, bright pulse, status text
     * - [AssistantState.Processing]        → mic icon, active tint, spinner, "Thinking…"
     * - [AssistantState.Speaking]          → mic icon, active tint, waveform, "Speaking…"
     * - [AssistantState.Error]             → mic-off icon, muted tint, error message
     *
     * Requirements: 8.3, 8.4, 8.5, 8.6, 8.7, 8.8
     */
    fun renderState(state: AssistantState) {
        // Stop any running animations first
        stopPulseAnimation()
        stopWaveformAnimation()

        // Hide subtitle by default — only shown in Speaking state
        subtitleScroll.visibility = View.GONE

        when (state) {
            is AssistantState.Idle -> renderIdle()
            is AssistantState.WakeWordListening -> renderWakeWordListening()
            is AssistantState.ActiveListening -> renderActiveListening()
            is AssistantState.Processing -> renderProcessing()
            is AssistantState.Speaking -> renderSpeaking(state.text)
            is AssistantState.Error -> renderError(state.message)
        }
    }

    // ── Per-state render helpers ──────────────────────────────────────────────────

    /**
     * Idle / muted state.
     * Mic button shows mic-off icon with dim tint. Status: "Paused".
     * Requirement: 8.8
     */
    private fun renderIdle() {
        setMicMuted()
        hidePulseRing()
        hideSpinner()
        hideWaveform()
        statusText.text = getString(R.string.status_idle)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorStatusText))
    }

    /**
     * Passive wake word listening state.
     * Dim pulse animation behind the mic button. Status: "Listening for 'Sayuri'…".
     * Requirement: 8.3
     */
    private fun renderWakeWordListening() {
        setMicActive()
        showPulseRingDim()
        hideSpinner()
        hideWaveform()
        statusText.text = getString(R.string.status_wake_word_listening)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorStatusText))
    }

    /**
     * Active listening state — user is speaking their command.
     * Bright, fast pulse animation. Status: "Listening…".
     * Requirement: 8.4
     */
    private fun renderActiveListening() {
        setMicActive()
        showPulseRingBright()
        hideSpinner()
        hideWaveform()
        statusText.text = getString(R.string.status_active_listening)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorStatusTextActive))
    }

    /**
     * Processing state — waiting for Gemini response.
     * Indeterminate spinner. Status: "Thinking…".
     * Requirement: 8.5
     */
    private fun renderProcessing() {
        setMicActive()
        hidePulseRing()
        showSpinner()
        hideWaveform()
        statusText.text = getString(R.string.status_processing)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorStatusText))
    }

    /**
     * Speaking state — TTS is reading the response aloud.
     * Animated waveform bars + subtitle showing the response text.
     * Requirement: 8.6
     */
    private fun renderSpeaking(text: String) {
        setMicActive()
        hidePulseRing()
        hideSpinner()
        showWaveform()
        statusText.text = getString(R.string.status_speaking)
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorStatusTextActive))
        // Show subtitle
        subtitleText.text = text
        subtitleScroll.visibility = View.VISIBLE
        // Scroll to top so long responses start from the beginning
        subtitleScroll.scrollTo(0, 0)
    }

    /**
     * Error state — displays the error message to the user.
     * Mic button shows muted appearance.
     * Requirement: 8.7
     */
    private fun renderError(message: String) {
        setMicMuted()
        hidePulseRing()
        hideSpinner()
        hideWaveform()
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, R.color.colorError))
    }

    // ── Mic button appearance helpers ─────────────────────────────────────────────

    /**
     * Active mic appearance: bright primary color, mic icon.
     * Used for WakeWordListening, ActiveListening, Processing, Speaking.
     * Requirement: 2.7
     */
    private fun setMicActive() {
        micButton.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.colorMicActive)
        micButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_mic))
    }

    /**
     * Muted mic appearance: dim grey color, mic-off icon.
     * Used for Idle and Error states.
     * Requirement: 2.7
     */
    private fun setMicMuted() {
        micButton.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.colorMicMuted)
        micButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_mic_off))
    }

    // ── Pulse ring helpers ────────────────────────────────────────────────────────

    /**
     * Shows the pulse ring with the dim color and starts the slow pulse animation.
     * Requirement: 8.3
     */
    private fun showPulseRingDim() {
        pulseRing.setBackgroundResource(R.drawable.bg_pulse_ring)
        // Tint the ring to the dim pulse color
        pulseRing.background?.setTint(
            ContextCompat.getColor(this, R.color.colorPulseDim)
        )
        pulseRing.visibility = View.VISIBLE
        val anim = AnimationUtils.loadAnimation(this, R.anim.pulse_dim)
        pulseRing.startAnimation(anim)
    }

    /**
     * Shows the pulse ring with the bright color and starts the fast pulse animation.
     * Requirement: 8.4
     */
    private fun showPulseRingBright() {
        pulseRing.setBackgroundResource(R.drawable.bg_pulse_ring)
        // Tint the ring to the bright pulse color
        pulseRing.background?.setTint(
            ContextCompat.getColor(this, R.color.colorPulseBright)
        )
        pulseRing.visibility = View.VISIBLE
        val anim = AnimationUtils.loadAnimation(this, R.anim.pulse_bright)
        pulseRing.startAnimation(anim)
    }

    private fun hidePulseRing() {
        pulseRing.clearAnimation()
        pulseRing.visibility = View.INVISIBLE
    }

    private fun stopPulseAnimation() {
        pulseRing.clearAnimation()
    }

    // ── Spinner helpers ───────────────────────────────────────────────────────────

    private fun showSpinner() {
        processingSpinner.visibility = View.VISIBLE
    }

    private fun hideSpinner() {
        processingSpinner.visibility = View.GONE
    }

    // ── Waveform helpers ──────────────────────────────────────────────────────────

    /**
     * Shows the speaking waveform and starts the bar animations.
     * Each bar oscillates at a slightly different speed to create a natural waveform effect.
     * Requirement: 8.6
     */
    private fun showWaveform() {
        speakingWaveform.visibility = View.VISIBLE
        startWaveformAnimation()
    }

    private fun hideWaveform() {
        speakingWaveform.visibility = View.GONE
        stopWaveformAnimation()
    }

    /**
     * Animates the five waveform bars with staggered scaleY oscillations.
     * Each bar has a different duration to simulate a live audio waveform.
     */
    private fun startWaveformAnimation() {
        val bars = listOf(waveBar1, waveBar2, waveBar3, waveBar4, waveBar5)
        val durations = listOf(400L, 300L, 250L, 350L, 450L)

        val animators = bars.mapIndexed { index, bar ->
            ObjectAnimator.ofFloat(bar, "scaleY", 0.3f, 1.0f).apply {
                duration = durations[index]
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                startDelay = index * 60L
            }
        }

        waveformAnimatorSet = AnimatorSet().apply {
            playTogether(*animators.toTypedArray())
            start()
        }
    }

    private fun stopWaveformAnimation() {
        waveformAnimatorSet?.cancel()
        waveformAnimatorSet = null
        // Reset bar scales to natural size — guard against uninitialized views
        if (::waveBar1.isInitialized) {
            listOf(waveBar1, waveBar2, waveBar3, waveBar4, waveBar5).forEach { bar ->
                bar.scaleY = 1.0f
            }
        }
    }
}
