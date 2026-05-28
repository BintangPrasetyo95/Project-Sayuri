package com.example.sayuri

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.sayuri.ui.SayuriScreen
import com.example.sayuri.viewmodel.VoiceAssistantViewModel
import com.example.sayuri.viewmodel.VoiceAssistantViewModelFactory
import com.example.sayuri.service.SayuriNotificationService

/**
 * Single-screen entry point for Sayuri using Jetpack Compose.
 *
 * Responsibilities:
 * - Set up Compose UI via setContent
 * - Hold the ViewModel
 * - Manage permissions (RECORD_AUDIO, BLUETOOTH_CONNECT)
 * - Delegate lifecycle to ViewModel (onResume/onPause/onDestroy)
 * - Request notification listener permission
 *
 * Requirements: 2.7, 2.8, 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 9.1, 9.2, 9.3, 9.4
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
    }

    internal lateinit var viewModel: VoiceAssistantViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val factory = VoiceAssistantViewModelFactory(applicationContext)
        viewModel = ViewModelProvider(this, factory)[VoiceAssistantViewModel::class.java]

        setContent {
            SayuriScreen(viewModel = viewModel)
        }

        checkAndRequestPermissions()
        checkNotificationListenerPermission()
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestPermissions()
        if (hasRecordAudioPermission()) {
            viewModel.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onDestroy()
    }

    // ── Permission handling ───────────────────────────────────────────────────────

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

    internal fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_CODE_PERMISSIONS) return

        val recordAudioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (recordAudioIndex == -1) return

        val recordAudioGranted =
            grantResults[recordAudioIndex] == PackageManager.PERMISSION_GRANTED

        if (recordAudioGranted) {
            if (::viewModel.isInitialized) {
                viewModel.onResume()
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.permission_rationale_title))
                .setMessage(getString(R.string.permission_rationale_message))
                .setPositiveButton(getString(R.string.permission_rationale_ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    // ── Notification listener permission ─────────────────────────────────────────

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
}
