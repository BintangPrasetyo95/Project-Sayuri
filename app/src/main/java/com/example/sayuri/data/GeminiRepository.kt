package com.example.sayuri.data

import android.util.Log
import com.example.sayuri.domain.ConversationManager
import com.example.sayuri.domain.FunctionToolkit
import com.example.sayuri.model.ConversationTurn
import com.example.sayuri.model.GeminiContent
import com.example.sayuri.model.GeminiFunctionResponse
import com.example.sayuri.model.GeminiPart
import com.example.sayuri.model.GeminiRequest
import com.example.sayuri.model.GeminiSystemInstruction
import com.example.sayuri.model.GenerationConfig
import com.example.sayuri.model.Role
import com.example.sayuri.model.TextPart
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// Interface
// ---------------------------------------------------------------------------

interface GeminiRepository {
    suspend fun sendMessage(userText: String): Result<String>
    fun clearHistory()
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

class GeminiRepositoryImpl(
    private val apiClient: GeminiApiClient,
    private val conversationManager: ConversationManager,
    private val functionToolkit: FunctionToolkit
) : GeminiRepository {

    companion object {
        private const val TAG = "GeminiRepository"
        private const val MAX_FUNCTION_ROUNDS = 5

        const val SYSTEM_PROMPT =
            "You are Sayuri, a personal AI assistant. Be concise, smart, and be energetic. " +
            "Address the user as 'Bintang' (Full Name), or 'tang'. " +
            "When you see [System: Current notification messages: ...] in the user's message, " +
            "use that data to answer questions about messages. " +
            "When the IMPORTANT reply instruction is present, match the sender by phonetic similarity " +
            "(speech recognition often mishears names — 'redo'='Ridho', 'read out'='Ridho', etc.) " +
            "and respond ONLY with the SEND_REPLY or REPLY_FAILED format as instructed. " +
            "For all other questions, keep responses short and conversational."
    }

    override suspend fun sendMessage(userText: String): Result<String> {
        val historySnapshot = conversationManager.getHistory()

        // Enrich the message with notification context if the user is asking about messages
        val enrichedText = enrichWithNotifications(userText)

        conversationManager.addUserMessage(enrichedText)

        return try {
            val finalText = runFunctionCallingLoop(buildRequest())
            conversationManager.addAssistantMessage(finalText)
            Result.success(finalText)
        } catch (e: Exception) {
            restoreSnapshot(historySnapshot)
            Result.failure(e)
        }
    }

    /**
     * If the user is asking about notifications/messages, prepend the actual
     * notification data to the message so Gemini can answer without function calling.
     * If the user is asking to reply, let Gemini extract the intent and we execute it.
     */
    private fun enrichWithNotifications(userText: String): String {
        val lower = userText.lowercase()

        val isAskingAboutMessages = lower.contains("notif") ||
            lower.contains("message") ||
            lower.contains("whatsapp") ||
            lower.contains("telegram") ||
            lower.contains("inbox") ||
            lower.contains("unread") ||
            (lower.contains("read") && (lower.contains("text") || lower.contains("chat")))

        val isAskingToReply = lower.contains("reply") || lower.contains("respond to") ||
            lower.contains("tell ") ||
            (lower.contains("send") && lower.contains("message"))

        if (!isAskingAboutMessages && !isAskingToReply) return userText

        val notifications = com.example.sayuri.domain.NotificationRepository.getMessages()

        if (notifications.isEmpty()) {
            return "$userText\n\n[System: No messages in notification history]"
        }

        val sb = StringBuilder(userText)
        sb.append("\n\n[System: Current notification messages:\n")
        notifications.take(10).forEachIndexed { i, n ->
            sb.append("${i + 1}. [${n.appName}] From ${n.sender}: \"${n.text}\" (id=${n.id}, canReply=${n.canReply})\n")
        }

        if (isAskingToReply) {
            sb.append(
                "\nIMPORTANT: The user wants to reply. Speech recognition may have misheard the name. " +
                "Match the intended sender by sound/phonetics even if spelling differs (e.g. 'redo'='Ridho', 'read out'='Ridho'). " +
                "Respond ONLY with this exact format on one line:\n" +
                "SEND_REPLY|<notification_id>|<reply_message>\n" +
                "If you cannot determine the sender or message, respond with:\n" +
                "REPLY_FAILED|<reason>"
            )
        }

        sb.append("]")
        return sb.toString()
    }

    override fun clearHistory() = conversationManager.clear()

    // ── Function calling loop ─────────────────────────────────────────────────────

    private suspend fun runFunctionCallingLoop(initialRequest: GeminiRequest): String {
        var request = initialRequest
        var rounds = 0

        while (rounds < MAX_FUNCTION_ROUNDS) {
            val response = apiClient.generateContent(request)
            val candidate = response.candidates.firstOrNull()
                ?: throw Exception("No response from Gemini")

            if (candidate.finishReason == "SAFETY") {
                throw Exception("Response blocked by safety filter")
            }

            val parts = candidate.content.parts
            val functionCallPart = parts.firstOrNull { it.functionCall != null }

            if (functionCallPart != null) {
                val fc = functionCallPart.functionCall!!
                Log.d(TAG, "Function call: ${fc.name}(${fc.args})")

                val result = functionToolkit.execute(fc.name, fc.args)
                Log.d(TAG, "Function result: $result")

                val updatedContents = request.contents.toMutableList()

                // Append model's function call turn
                updatedContents.add(
                    GeminiContent(
                        role = "model",
                        parts = listOf(functionCallPart)
                    )
                )

                // Append function result turn
                updatedContents.add(
                    GeminiContent(
                        role = "user",
                        parts = listOf(
                            GeminiPart(
                                functionResponse = GeminiFunctionResponse(
                                    name = fc.name,
                                    response = mapOf("result" to JsonPrimitive(result))
                                )
                            )
                        )
                    )
                )

                request = request.copy(contents = updatedContents)
                rounds++
                continue
            }

            // No function call — return the text response
            val text = parts.firstOrNull { !it.text.isNullOrBlank() }?.text?.trim()
                ?: throw Exception("Empty response from Gemini")

            // Check if Gemini returned a structured reply command
            if (text.startsWith("SEND_REPLY|")) {
                val parts2 = text.split("|")
                if (parts2.size >= 3) {
                    val notifId = parts2[1].trim()
                    val replyMessage = parts2.drop(2).joinToString("|").trim()
                    return executeReply(notifId, replyMessage)
                }
            }
            if (text.startsWith("REPLY_FAILED|")) {
                val reason = text.removePrefix("REPLY_FAILED|").trim()
                return "I couldn't send the reply, tang. $reason"
            }

            return text
        }

        throw Exception("Too many function call rounds")
    }

    // ── Reply execution ───────────────────────────────────────────────────────────

    private fun executeReply(notifId: String, message: String): String {
        // First try the cache
        var sbn = com.example.sayuri.service.SayuriNotificationService.getActiveSbn(notifId)

        // If not in cache, scan live active notifications from the service
        if (sbn == null) {
            sbn = com.example.sayuri.service.SayuriNotificationService
                .findActiveSbnByIdOrSender(notifId)
        }

        if (sbn == null) {
            return "The notification is no longer active, tang. It may have been dismissed."
        }

        val notif = com.example.sayuri.domain.NotificationRepository.getMessages()
            .firstOrNull { it.id == notifId }

        val senderName = notif?.sender ?: "them"
        val appName = notif?.appName ?: "the app"

        val success = com.example.sayuri.service.SayuriNotificationService
            .replyToNotification(sbn, message)

        return if (success) {
            "Done! Replied to $senderName on $appName: \"$message\""
        } else {
            "Couldn't send the reply to $senderName, tang. The app may not support direct replies."
        }
    }

    // ── Request builder ───────────────────────────────────────────────────────────

    private fun buildRequest(): GeminiRequest {
        val contents = conversationManager.getHistory().map { turn ->
            val role = if (turn.role == Role.USER) "user" else "model"
            GeminiContent(role = role, parts = listOf(GeminiPart(text = turn.text)))
        }

        return GeminiRequest(
            contents = contents,
            systemInstruction = GeminiSystemInstruction(
                parts = listOf(TextPart(text = SYSTEM_PROMPT))
            ),
            generationConfig = GenerationConfig(maxOutputTokens = 1024, temperature = 0.9f),
            tools = null
        )
    }

    // ── History rollback ──────────────────────────────────────────────────────────

    private fun restoreSnapshot(snapshot: List<ConversationTurn>) {
        conversationManager.clear()
        snapshot.forEach { turn ->
            when (turn.role) {
                Role.USER -> conversationManager.addUserMessage(turn.text)
                Role.ASSISTANT -> conversationManager.addAssistantMessage(turn.text)
            }
        }
    }
}
