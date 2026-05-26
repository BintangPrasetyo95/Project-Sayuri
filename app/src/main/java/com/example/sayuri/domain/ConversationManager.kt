package com.example.sayuri.domain

import com.example.sayuri.model.ConversationTurn
import com.example.sayuri.model.Role

/**
 * Maintains an in-memory conversation history for multi-turn context.
 *
 * History is capped at [MAX_TURNS] turns (user + assistant pairs). When the cap is
 * exceeded the oldest pair (user turn + assistant turn) is evicted together so the
 * history always starts with a user message.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5
 */
class ConversationManager {

    companion object {
        /** Maximum number of turns (user/assistant pairs) retained in history. */
        const val MAX_TURNS = 10

        /** Maximum number of individual messages (2 per turn). */
        private const val MAX_MESSAGES = MAX_TURNS * 2
    }

    private val history: MutableList<ConversationTurn> = mutableListOf()

    /**
     * Appends a user message to the history.
     * If the cap would be exceeded after this addition, the oldest pair is evicted first.
     */
    fun addUserMessage(text: String) {
        evictIfNeeded()
        history.add(ConversationTurn(Role.USER, text))
    }

    /**
     * Appends an assistant message to the history.
     * If the cap would be exceeded after this addition, the oldest pair is evicted first.
     */
    fun addAssistantMessage(text: String) {
        evictIfNeeded()
        history.add(ConversationTurn(Role.ASSISTANT, text))
    }

    /**
     * Returns an immutable snapshot of the current conversation history.
     */
    fun getHistory(): List<ConversationTurn> = history.toList()

    /**
     * Removes all entries from the history.
     */
    fun clear() {
        history.clear()
    }

    /**
     * Evicts the oldest pair of messages (indices 0 and 1) when adding one more message
     * would exceed [MAX_MESSAGES].
     */
    private fun evictIfNeeded() {
        if (history.size >= MAX_MESSAGES) {
            // Remove the oldest pair (user + assistant) to keep history balanced
            repeat(2) {
                if (history.isNotEmpty()) history.removeAt(0)
            }
        }
    }
}
