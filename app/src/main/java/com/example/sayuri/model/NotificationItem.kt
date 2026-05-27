package com.example.sayuri.model

/**
 * Represents a captured notification that has a reply action.
 *
 * @param id          Unique identifier (notification key from the system).
 * @param appName     Human-readable app name (e.g. "WhatsApp", "Telegram").
 * @param packageName Package name of the source app (e.g. "com.whatsapp").
 * @param sender      Name of the person who sent the message.
 * @param text        The notification body text (the message content).
 * @param timestamp   When the notification arrived (epoch millis).
 * @param canReply    Whether this notification has a RemoteInput reply action.
 */
data class NotificationItem(
    val id: String,
    val appName: String,
    val packageName: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val canReply: Boolean
)
