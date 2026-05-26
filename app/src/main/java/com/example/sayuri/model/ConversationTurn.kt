package com.example.sayuri.model

/**
 * A single entry in the in-memory conversation history.
 *
 * @param role    Whether this turn was produced by the user or the assistant.
 * @param text    The spoken / generated text for this turn.
 */
data class ConversationTurn(
    val role: Role,
    val text: String
)

/**
 * Identifies the author of a [ConversationTurn].
 */
enum class Role {
    USER,
    ASSISTANT
}
