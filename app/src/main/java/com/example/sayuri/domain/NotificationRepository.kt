package com.example.sayuri.domain

import com.example.sayuri.model.NotificationItem

/**
 * In-memory store for notifications captured by [com.example.sayuri.service.SayuriNotificationService].
 *
 * Singleton so both the NotificationListenerService and the ViewModel share the same instance.
 */
object NotificationRepository {

    private const val MAX_NOTIFICATIONS = 50

    private val _notifications = ArrayDeque<NotificationItem>()

    /** Returns an immutable snapshot of all stored notifications, newest first. */
    fun getAll(): List<NotificationItem> = _notifications.toList()

    /** Returns notifications from messaging apps only (WhatsApp, Telegram, SMS, etc.). */
    fun getMessages(): List<NotificationItem> =
        _notifications.filter { it.canReply || it.packageName in MESSAGING_PACKAGES }

    /** Returns the most recent [n] notifications. */
    fun getRecent(n: Int = 10): List<NotificationItem> = _notifications.take(n)

    /** Adds a notification. Evicts the oldest if over capacity. */
    fun add(item: NotificationItem) {
        _notifications.addFirst(item)
        while (_notifications.size > MAX_NOTIFICATIONS) {
            _notifications.removeLast()
        }
    }

    /** Removes a notification by its id (e.g. when it's dismissed). */
    fun remove(id: String) {
        val iterator = _notifications.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().id == id) iterator.remove()
        }
    }

    /** Clears all stored notifications. */
    fun clear() {
        _notifications.clear()
    }

    /** Known messaging app packages for filtering. */
    private val MESSAGING_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.facebook.orca",       // Messenger
        "com.instagram.android",
        "com.discord",
        "com.snapchat.android",
        "com.twitter.android",
        "com.linkedin.android"
    )
}
