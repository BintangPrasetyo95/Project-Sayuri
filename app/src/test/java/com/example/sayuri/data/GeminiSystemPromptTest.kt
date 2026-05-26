package com.example.sayuri.data

import com.example.sayuri.domain.ConversationManager
import com.example.sayuri.model.GeminiCandidate
import com.example.sayuri.model.GeminiContent
import com.example.sayuri.model.GeminiPart
import com.example.sayuri.model.GeminiRequest
import com.example.sayuri.model.GeminiResponse
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking

/**
 * Property test for system prompt always present in every GeminiRequest.
 *
 * **Property 7: System Prompt Always Present**
 *
 * For any non-blank user text and any conversation history passed to
 * [GeminiRepositoryImpl.sendMessage], the resulting [GeminiRequest] sent to
 * [GeminiApiClient] always contains the [GeminiRepositoryImpl.SYSTEM_PROMPT] as
 * the `systemInstruction` field — it is never null and always contains the
 * expected persona text.
 *
 * **Validates: Requirements 4.1, 4.2**
 */
class GeminiSystemPromptTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Builds a minimal successful [GeminiResponse] for the mock to return. */
    fun successResponse() = GeminiResponse(
        candidates = listOf(
            GeminiCandidate(
                content = GeminiContent(
                    role = "model",
                    parts = listOf(GeminiPart(text = "OK"))
                ),
                finishReason = "STOP"
            )
        )
    )

    /** Arbitrary non-blank string of length 1..50. */
    val arbNonBlankText: Arb<String> = Arb.string(minSize = 1, maxSize = 50)
        .filter { it.isNotBlank() }

    /** Arbitrary non-blank string of length 1..30 for history entries. */
    val arbHistoryText: Arb<String> = Arb.string(minSize = 1, maxSize = 30)
        .filter { it.isNotBlank() }

    // ---------------------------------------------------------------------------
    // Property 7: System Prompt Always Present
    //
    // For any non-blank user text and any conversation history, the GeminiRequest
    // built by GeminiRepositoryImpl always has:
    //   - request.systemInstruction != null
    //   - request.systemInstruction contains the SYSTEM_PROMPT text
    //
    // Validates: Requirements 4.1, 4.2
    // ---------------------------------------------------------------------------

    "Property 7 - systemInstruction is always non-null and contains the system prompt for any history and user text" {
        // Arbitrary conversation history: list of (userText, assistantText) pairs
        val arbHistory = Arb.list(
            Arb.pair(arbHistoryText, arbHistoryText),
            range = 0..5
        )

        forAll(
            PropTestConfig(iterations = 20),
            arbHistory,
            arbNonBlankText
        ) { historyPairs, userText ->
            // Capture the GeminiRequest passed to generateContent
            val capturedRequest = slot<GeminiRequest>()
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(capture(capturedRequest)) } returns successResponse()

            val conversationManager = ConversationManager()

            // Pre-populate history with completed turns
            historyPairs.forEach { (uText, aText) ->
                conversationManager.addUserMessage(uText)
                conversationManager.addAssistantMessage(aText)
            }

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            // sendMessage appends userText and builds the request
            runBlocking { repository.sendMessage(userText) }

            val request = capturedRequest.captured

            // systemInstruction must be present
            val systemInstruction = request.systemInstruction
            if (systemInstruction == null) return@forAll false

            // systemInstruction must contain at least one part with the system prompt text
            val combinedText = systemInstruction.parts.joinToString("") { it.text }
            combinedText.contains(GeminiRepositoryImpl.SYSTEM_PROMPT)
        }
    }

    "Property 7 - systemInstruction is non-null when history is empty" {
        forAll(
            PropTestConfig(iterations = 20),
            arbNonBlankText
        ) { userText ->
            val capturedRequest = slot<GeminiRequest>()
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(capture(capturedRequest)) } returns successResponse()

            val conversationManager = ConversationManager()
            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            runBlocking { repository.sendMessage(userText) }

            val request = capturedRequest.captured
            val systemInstruction = request.systemInstruction
            if (systemInstruction == null) return@forAll false

            val combinedText = systemInstruction.parts.joinToString("") { it.text }
            combinedText.contains(GeminiRepositoryImpl.SYSTEM_PROMPT)
        }
    }

    "Property 7 - systemInstruction is non-null when history is at maximum capacity" {
        forAll(
            PropTestConfig(iterations = 20),
            arbNonBlankText
        ) { userText ->
            val capturedRequest = slot<GeminiRequest>()
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(capture(capturedRequest)) } returns successResponse()

            val conversationManager = ConversationManager()

            // Fill history to maximum capacity (10 turns = 20 messages)
            repeat(ConversationManager.MAX_TURNS) { i ->
                conversationManager.addUserMessage("user $i")
                conversationManager.addAssistantMessage("assistant $i")
            }

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            runBlocking { repository.sendMessage(userText) }

            val request = capturedRequest.captured
            val systemInstruction = request.systemInstruction
            if (systemInstruction == null) return@forAll false

            val combinedText = systemInstruction.parts.joinToString("") { it.text }
            combinedText.contains(GeminiRepositoryImpl.SYSTEM_PROMPT)
        }
    }
})
