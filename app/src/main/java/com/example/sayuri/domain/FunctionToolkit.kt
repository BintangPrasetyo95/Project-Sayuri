package com.example.sayuri.domain

import android.content.Context
import android.util.Log
import com.example.sayuri.model.FunctionDeclaration
import com.example.sayuri.model.FunctionParameters
import com.example.sayuri.model.FunctionProperty
import com.example.sayuri.model.GeminiTool
import com.example.sayuri.service.SayuriNotificationService
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Defines the function tools Gemini can call, and executes them when requested.
 *
 * Tools available:
 * - `read_notifications` — reads recent messages from messaging apps
 * - `reply_to_notification` — replies to a specific notification by sender name
 */
class FunctionToolkit(private val context: Context) {

    companion object {
        private const val TAG = "FunctionToolkit"

        // ── Tool definitions ──────────────────────────────────────────────────────

        val TOOLS = listOf(
            GeminiTool(
                functionDeclarations = listOf(
                    FunctionDeclaration(
                        name = "read_notifications",
                        description = "Reads recent unread messages and notifications from messaging apps like WhatsApp, Telegram, SMS, etc. Use this when the user asks to read their messages, notifications, or what someone said.",
                        parameters = FunctionParameters(
                            type = "object",
                            properties = mapOf(
                                "app_filter" to FunctionProperty(
                                    type = "string",
                                    description = "Optional: filter by app name (e.g. 'WhatsApp', 'Telegram'). Leave empty to read all."
                                )
                            ),
                            required = null
                        )
                    ),
                    FunctionDeclaration(
                        name = "reply_to_notification",
                        description = "Replies to a message notification from a specific sender. Use this when the user asks to reply to someone's message. The reply is sent silently without opening the app.",
                        parameters = FunctionParameters(
                            type = "object",
                            properties = mapOf(
                                "sender_name" to FunctionProperty(
                                    type = "string",
                                    description = "The name of the person to reply to (as shown in the notification)."
                                ),
                                "message" to FunctionProperty(
                                    type = "string",
                                    description = "The reply message text to send."
                                )
                            ),
                            required = listOf("sender_name", "message")
                        )
                    )
                )
            )
        )
    }

    // ── Function execution ────────────────────────────────────────────────────────

    /**
     * Executes a function call requested by Gemini and returns the result as a string
     * that will be sent back to the model.
     *
     * @param name The function name from [GeminiFunctionCall.name].
     * @param args The arguments from [GeminiFunctionCall.args].
     * @return A human-readable result string to feed back to Gemini.
     */
    fun execute(name: String, args: Map<String, JsonElement>): String {
        Log.d(TAG, "Executing function: $name with args: $args")
        return when (name) {
            "read_notifications" -> executeReadNotifications(args)
            "reply_to_notification" -> executeReplyToNotification(args)
            else -> "Unknown function: $name"
        }
    }

    // ── read_notifications ────────────────────────────────────────────────────────

    private fun executeReadNotifications(args: Map<String, JsonElement>): String {
        val appFilter = args["app_filter"]?.jsonPrimitive?.content?.lowercase()?.trim()

        val notifications = NotificationRepository.getMessages()

        if (notifications.isEmpty()) {
            return "No new messages found."
        }

        val filtered = if (appFilter.isNullOrBlank()) {
            notifications
        } else {
            notifications.filter {
                it.appName.lowercase().contains(appFilter) ||
                it.packageName.lowercase().contains(appFilter)
            }
        }

        if (filtered.isEmpty()) {
            return "No messages found from $appFilter."
        }

        // Format as a readable list for Gemini to summarise
        val sb = StringBuilder()
        sb.appendLine("Found ${filtered.size} message(s):")
        filtered.take(10).forEachIndexed { index, item ->
            sb.appendLine("${index + 1}. [${item.appName}] From ${item.sender}: \"${item.text}\"")
        }
        return sb.toString().trim()
    }

    // ── reply_to_notification ─────────────────────────────────────────────────────

    private fun executeReplyToNotification(args: Map<String, JsonElement>): String {
        val senderName = args["sender_name"]?.jsonPrimitive?.content?.trim()
            ?: return "Error: sender_name is required."
        val message = args["message"]?.jsonPrimitive?.content?.trim()
            ?: return "Error: message is required."

        if (senderName.isBlank()) return "Error: sender_name cannot be empty."
        if (message.isBlank()) return "Error: message cannot be empty."

        // Find the most recent notification from this sender that can be replied to
        val notifications = NotificationRepository.getMessages()
        val target = notifications.firstOrNull { item ->
            item.canReply && item.sender.lowercase().contains(senderName.lowercase())
        }

        if (target == null) {
            return "No replyable notification found from '$senderName'. " +
                   "They may not have sent a recent message, or the notification was dismissed."
        }

        val sbn = SayuriNotificationService.getActiveSbn(target.id)
            ?: return "The notification from '${target.sender}' is no longer active. " +
                      "It may have been dismissed."

        val success = SayuriNotificationService.replyToNotification(sbn, message)

        return if (success) {
            "Reply sent to ${target.sender} on ${target.appName}: \"$message\""
        } else {
            "Failed to send reply to ${target.sender}. The app may not support direct replies."
        }
    }
}
