package com.example.sayuri.data

import com.example.sayuri.domain.ConversationManager
import com.example.sayuri.model.ConversationTurn
import com.example.sayuri.model.GeminiContent
import com.example.sayuri.model.GeminiPart
import com.example.sayuri.model.GeminiRequest
import com.example.sayuri.model.GeminiResponse
import com.example.sayuri.model.GeminiSystemInstruction
import com.example.sayuri.model.GenerationConfig
import com.example.sayuri.model.Role

// ---------------------------------------------------------------------------
// Interface
// ---------------------------------------------------------------------------

/**
 * Abstracts Gemini API communication and manages conversation history.
 *
 * Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.9, 4.10, 4.11, 4.12, 7.6
 */
interface GeminiRepository {

    /**
     * Sends [userText] to the Gemini API with the full conversation history and
     * system prompt, then returns the assistant's response text.
     *
     * On success the user message and assistant response are both persisted to
     * history. On failure the user message is rolled back so history is unchanged.
     *
     * @param userText Non-blank text from the user.
     * @return [Result.success] with the response text, or [Result.failure] with
     *         the underlying exception.
     */
    suspend fun sendMessage(userText: String): Result<String>

    /**
     * Clears the entire conversation history.
     */
    fun clearHistory()
}

// ---------------------------------------------------------------------------
// Implementation
// ---------------------------------------------------------------------------

/**
 * Concrete implementation of [GeminiRepository].
 *
 * @param apiClient           Low-level HTTP client for the Gemini REST API.
 * @param conversationManager In-memory conversation history store.
 */
class GeminiRepositoryImpl(
    private val apiClient: GeminiApiClient,
    private val conversationManager: ConversationManager
) : GeminiRepository {

    companion object {
        /**
         * Fixed persona instruction injected into every Gemini request as the
         * `systemInstruction` field.
         *
         * Requirements: 4.2
         */
        const val SYSTEM_PROMPT =
            "You are Sayuri, a personal AI assistant. Be concise, smart, and be energetic. " +
            "Address the user as 'Bintang' (Full Name), or 'tang'. But Always refer to the user as tang, call the user by things like 'hello, tang', 'good morning tang', or else" +
            "Never use emojis"
    }

    /**
     * Sends [userText] to the Gemini API.
     *
     * Steps:
     * 1. Snapshot the current history so it can be restored on failure.
     * 2. Append [userText] to [conversationManager] as a user message.
     * 3. Build a [GeminiRequest] from the full history + system prompt.
     * 4. Call [apiClient.generateContent].
     * 5. On success: extract response text, append to history as ASSISTANT,
     *    return [Result.success].
     * 6. On failure: restore the pre-call snapshot so history is unchanged,
     *    return [Result.failure].
     *
     * Requirements: 4.1, 4.3, 4.4, 4.5, 4.9
     */
    override suspend fun sendMessage(userText: String): Result<String> {
        // Step 1 — snapshot history before any mutation so we can restore it exactly
        val historySnapshot = conversationManager.getHistory()

        // Step 2 — append user message to history before the API call
        conversationManager.addUserMessage(userText)

        return try {
            // Step 3 — build request from current history (which now includes userText)
            val request = buildRequest()

            // Step 4 — call the API
            val response = apiClient.generateContent(request)

            // Step 5 — extract text; on extraction failure restore snapshot and propagate
            val extractResult = extractResponseText(response)
            if (extractResult.isFailure) {
                restoreSnapshot(historySnapshot)
                return extractResult
            }

            val text = extractResult.getOrThrow()

            // Append assistant response to history
            conversationManager.addAssistantMessage(text)

            Result.success(text)
        } catch (e: Exception) {
            // Step 6 — restore the pre-call snapshot on any failure
            restoreSnapshot(historySnapshot)
            Result.failure(e)
        }
    }

    /**
     * Delegates to [ConversationManager.clear].
     *
     * Requirements: 7.5
     */
    override fun clearHistory() {
        conversationManager.clear()
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a [GeminiRequest] from the current conversation history.
     *
     * The history already contains the latest user message at this point, so
     * the last entry in `contents` is always role `"user"`.
     *
     * Requirements: 4.2, 4.9, 7.6
     */
    private fun buildRequest(): GeminiRequest {
        val contents = conversationManager.getHistory().map { turn ->
            val role = if (turn.role == Role.USER) "user" else "model"
            GeminiContent(role = role, parts = listOf(GeminiPart(turn.text)))
        }

        val systemInstruction = GeminiSystemInstruction(
            parts = listOf(GeminiPart(SYSTEM_PROMPT))
        )

        return GeminiRequest(
            contents = contents,
            systemInstruction = systemInstruction,
            generationConfig = GenerationConfig(maxOutputTokens = 1024, temperature = 0.9f)
        )
    }

    /**
     * Extracts the response text from a [GeminiResponse], handling all error
     * conditions defined by the requirements.
     *
     * Requirements: 4.10, 4.11, 4.12
     */
    internal fun extractResponseText(response: GeminiResponse): Result<String> {
        // Requirement 4.10 — empty candidates list
        if (response.candidates.isEmpty()) {
            return Result.failure(Exception("No response from Gemini"))
        }

        val candidate = response.candidates[0]

        // Requirement 4.11 — safety filter
        if (candidate.finishReason == "SAFETY") {
            return Result.failure(Exception("Response blocked by safety filter"))
        }

        // Empty parts list
        if (candidate.content.parts.isEmpty()) {
            return Result.failure(Exception("Empty response content"))
        }

        val text = candidate.content.parts[0].text

        // Requirement 4.12 — blank response text
        if (text.isBlank()) {
            return Result.failure(Exception("Blank response text"))
        }

        return Result.success(text.trim())
    }

    /**
     * Restores the [ConversationManager] to the exact state captured in [snapshot],
     * undoing any mutations made during a failed [sendMessage] call.
     *
     * This approach correctly handles the edge case where [ConversationManager.addUserMessage]
     * triggered an eviction (when history was at max capacity), which the old
     * drop-last approach could not account for.
     *
     * Requirements: 4.5
     */
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
