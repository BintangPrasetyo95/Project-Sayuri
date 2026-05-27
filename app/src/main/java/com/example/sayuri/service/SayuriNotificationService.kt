package com.example.sayuri.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.sayuri.domain.NotificationRepository
import com.example.sayuri.model.NotificationItem

/**
 * Listens to all system notifications and stores messaging notifications in
 * [NotificationRepository] so Sayuri can read and reply to them via voice.
 *
 * Requires the user to grant "Notification Access" in Android Settings once.
 */
class SayuriNotificationService : NotificationListenerService() {

    // ── NotificationListenerService callbacks ─────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val key: String = sbn.key ?: return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val sender: String = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: "Unknown"

        val text: String = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return

        if (text.isBlank() || sender.isBlank()) return

        val canReply = notification.actions?.any { action ->
            action.remoteInputs?.isNotEmpty() == true
        } == true

        val appName: String = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: Exception) {
            sbn.packageName ?: "Unknown App"
        }

        val item = NotificationItem(
            id = key,
            appName = appName,
            packageName = sbn.packageName ?: "",
            sender = sender,
            text = text,
            timestamp = sbn.postTime,
            canReply = canReply
        )

        NotificationRepository.add(item)
        sbnCache[key] = sbn

        Log.d(TAG, "Captured: [$appName] $sender: $text (canReply=$canReply)")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val key: String = sbn.key ?: return
        NotificationRepository.remove(key)
        sbnCache.remove(key)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")

        // Populate cache with notifications that arrived before the service started
        try {
            val active = activeNotifications  // from NotificationListenerService
            active?.forEach { sbn ->
                val key = sbn.key ?: return@forEach
                sbnCache[key] = sbn

                // Also add to NotificationRepository if it has readable content
                val extras = sbn.notification?.extras ?: return@forEach
                val sender = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
                    ?.toString() ?: return@forEach
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                    ?.toString() ?: return@forEach
                if (sender.isBlank() || text.isBlank()) return@forEach

                val canReply = sbn.notification?.actions?.any { a ->
                    a.remoteInputs?.isNotEmpty() == true
                } == true

                val appName = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(sbn.packageName ?: "", 0)
                    ).toString()
                } catch (e: Exception) { sbn.packageName ?: "Unknown" }

                NotificationRepository.add(
                    com.example.sayuri.model.NotificationItem(
                        id = key,
                        appName = appName,
                        packageName = sbn.packageName ?: "",
                        sender = sender,
                        text = text,
                        timestamp = sbn.postTime,
                        canReply = canReply
                    )
                )
            }
            Log.d(TAG, "Pre-loaded ${active?.size ?: 0} existing notifications into cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pre-load notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }

    companion object {
        private const val TAG = "SayuriNotifService"

        /** Cache of active StatusBarNotifications keyed by notification key string. */
        private val sbnCache = HashMap<String, StatusBarNotification>()

        /** Returns the cached StatusBarNotification for reply, or null if gone. */
        fun getActiveSbn(id: String): StatusBarNotification? = sbnCache[id]

        /**
         * Fallback lookup: scans live active notifications from the system.
         * Used when the cache doesn't have the notification (e.g. service restarted
         * after the notification arrived).
         *
         * @param idOrSender notification key or partial sender name to match
         */
        fun findActiveSbnByIdOrSender(idOrSender: String): StatusBarNotification? {
            // First try exact key match in cache
            sbnCache[idOrSender]?.let { return it }

            // Then scan all cached SBNs for a partial sender/package match
            return sbnCache.values.firstOrNull { sbn ->
                val extras = sbn.notification?.extras
                val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                    ?.toString()?.lowercase() ?: ""
                title.contains(idOrSender.lowercase()) ||
                sbn.key.contains(idOrSender, ignoreCase = true)
            }
        }

        /**
         * Sends a reply to a notification that has a RemoteInput reply action.
         * @return true if the reply was dispatched successfully.
         */
        fun replyToNotification(sbn: StatusBarNotification, message: String): Boolean {
            val actions = sbn.notification?.actions ?: return false

            val replyAction = actions.firstOrNull { action ->
                action.remoteInputs?.isNotEmpty() == true
            } ?: return false

            val remoteInputs = replyAction.remoteInputs ?: return false
            val remoteInput = remoteInputs.firstOrNull() ?: return false

            return try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(remoteInput.resultKey, message)
                RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
                replyAction.actionIntent.send(null, 0, intent)
                Log.d(TAG, "Reply sent to ${sbn.packageName}: $message")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply", e)
                false
            }
        }
    }
}
