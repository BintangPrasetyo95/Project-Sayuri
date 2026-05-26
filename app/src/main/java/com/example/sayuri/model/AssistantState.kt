package com.example.sayuri.model

/**
 * Represents the current state of the voice assistant.
 * Drives the UI and orchestrates the interaction lifecycle.
 */
sealed class AssistantState {
    /** The assistant is idle / muted. No listening is active. */
    object Idle : AssistantState()

    /** The assistant is passively monitoring audio for the wake word "Sayuri". */
    object WakeWordListening : AssistantState()

    /** The wake word was detected; the assistant is now capturing the user's full command. */
    object ActiveListening : AssistantState()

    /** A transcript has been received and is being sent to the Gemini API. */
    object Processing : AssistantState()

    /** The assistant is reading the Gemini response aloud via TTS. */
    data class Speaking(val text: String) : AssistantState()

    /** An error occurred. The message is displayed to the user. */
    data class Error(val message: String) : AssistantState()
}
