package com.example.sayuri.data

import com.example.sayuri.domain.ConversationManager
import com.example.sayuri.model.GeminiCandidate
import com.example.sayuri.model.GeminiContent
import com.example.sayuri.model.GeminiPart
import com.example.sayuri.model.GeminiResponse
import com.example.sayuri.model.Role
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
import kotlinx.coroutines.runBlocking

/**
 * Property test for history consistency after a successful API response.
 *
 * **Property 8: History Consistency After Success**
 *
 * For any conversation history and any non-blank user text, after a successful
 * [GeminiRepositoryImpl.sendMessage] call the last entry in
 * [ConversationManager.getHistory] always has `role == ASSISTANT`.
 *
 * **Validates: Requirements 4.4**
 */
class GeminiHistoryConsistencyTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Arbitrary non-blank response text of length 1..80. */
    val arbResponseText: Arb<String> = Arb.string(minSize = 1, maxSize = 80)
        .filter { it.isNotBlank() }

    /** Arbitrary non-blank user text of length 1..50. */
    val arbUserText: Arb<String> = Arb.string(minSize = 1, maxSize = 50)
        .filter { it.isNotBlank() }

    /** Arbitrary non-blank string of length 1..30 for history entries. */
    val arbHistoryText: Arb<String> = Arb.string(minSize = 1, maxSize = 30)
        .filter { it.isNotBlank() }

    /** Builds a successful [GeminiResponse] with the given response text. */
    fun successResponse(responseText: String) = GeminiResponse(
        candidates = listOf(
            GeminiCandidate(
                content = GeminiContent(
                    role = "model",
                    parts = listOf(GeminiPart(text = responseText))
                ),
                finishReason = "STOP"
            )
        )
    )

    // ---------------------------------------------------------------------------
    // Property 8: History Consistency After Success
    //
    // After a successful sendMessage(), the last entry in getHistory() must have
    // role == ASSISTANT, regardless of the prior conversation history or the
    // content of the API response.
    //
    // Validates: Requirements 4.4
    // ---------------------------------------------------------------------------

    "Property 8 - last history entry has role ASSISTANT after a successful sendMessage for any history and response" {
        val arbHistory = Arb.list(
            Arb.pair(arbHistoryText, arbHistoryText),
            range = 0..5
        )

        forAll(
            PropTestConfig(iterations = 20),
            arbHistory,
            arbUserText,
            arbResponseText
        ) { historyPairs, userText, responseText ->
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(any()) } returns successResponse(responseText)

            val conversationManager = ConversationManager()

            // Pre-populate history with completed turns
            historyPairs.forEach { (uText, aText) ->
                conversationManager.addUserMessage(uText)
                conversationManager.addAssistantMessage(aText)
            }

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            val result = runBlocking { repository.sendMessage(userText) }

            // The call must have succeeded
            if (result.isFailure) return@forAll false

            // The last history entry must be an ASSISTANT turn
            val history = conversationManager.getHistory()
            history.isNotEmpty() && history.last().role == Role.ASSISTANT
        }
    }

    "Property 8 - last history entry has role ASSISTANT when starting from empty history" {
        forAll(
            PropTestConfig(iterations = 20),
            arbUserText,
            arbResponseText
        ) { userText, responseText ->
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(any()) } returns successResponse(responseText)

            val conversationManager = ConversationManager()
            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            val result = runBlocking { repository.sendMessage(userText) }

            if (result.isFailure) return@forAll false

            val history = conversationManager.getHistory()
            history.isNotEmpty() && history.last().role == Role.ASSISTANT
        }
    }

    "Property 8 - last history entry has role ASSISTANT when history is at maximum capacity" {
        forAll(
            PropTestConfig(iterations = 20),
            arbUserText,
            arbResponseText
        ) { userText, responseText ->
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(any()) } returns successResponse(responseText)

            val conversationManager = ConversationManager()

            // Fill history to maximum capacity (10 turns = 20 messages)
            repeat(ConversationManager.MAX_TURNS) { i ->
                conversationManager.addUserMessage("user $i")
                conversationManager.addAssistantMessage("assistant $i")
            }

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            val result = runBlocking { repository.sendMessage(userText) }

            if (result.isFailure) return@forAll false

            val history = conversationManager.getHistory()
            history.isNotEmpty() && history.last().role == Role.ASSISTANT
        }
    }
})
