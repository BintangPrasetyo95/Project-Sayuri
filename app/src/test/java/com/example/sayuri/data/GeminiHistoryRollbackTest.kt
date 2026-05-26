package com.example.sayuri.data

import com.example.sayuri.domain.ConversationManager
import com.example.sayuri.model.ApiException
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking

/**
 * Property test for history rollback after a failed API call.
 *
 * **Property 9: History Rollback After Failure**
 *
 * For any conversation history and any non-blank user text, when the
 * [GeminiApiClient] throws an exception during [GeminiRepositoryImpl.sendMessage],
 * the [ConversationManager] history size must be exactly the same as it was
 * before the call — the optimistic user-message append is rolled back.
 *
 * **Validates: Requirements 4.5**
 */
class GeminiHistoryRollbackTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Arbitrary non-blank string of length 1..50 for user messages. */
    val arbUserText: Arb<String> = Arb.string(minSize = 1, maxSize = 50)
        .filter { it.isNotBlank() }

    /** Arbitrary non-blank string of length 1..30 for pre-populated history entries. */
    val arbHistoryText: Arb<String> = Arb.string(minSize = 1, maxSize = 30)
        .filter { it.isNotBlank() }

    /** Arbitrary HTTP error codes in the 4xx–5xx range. */
    val arbHttpErrorCode: Arb<Int> = Arb.int(400..599)

    // ---------------------------------------------------------------------------
    // Property 9: History Rollback After Failure
    //
    // After a failed sendMessage() (API throws ApiException), the history size
    // must be unchanged from before the call.
    //
    // Validates: Requirements 4.5
    // ---------------------------------------------------------------------------

    "Property 9 - history size is unchanged after a failed sendMessage for any pre-populated history" {
        val arbHistory = Arb.list(
            Arb.pair(arbHistoryText, arbHistoryText),
            range = 0..5
        )

        forAll(
            PropTestConfig(iterations = 20),
            arbHistory,
            arbUserText,
            arbHttpErrorCode
        ) { historyPairs, userText, errorCode ->
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(any()) } throws
                ApiException(errorCode, "Simulated API failure $errorCode")

            val conversationManager = ConversationManager()

            // Pre-populate history with completed turns
            historyPairs.forEach { (uText, aText) ->
                conversationManager.addUserMessage(uText)
                conversationManager.addAssistantMessage(aText)
            }

            // Record history size before the failing call
            val sizeBefore = conversationManager.getHistory().size

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            val result = runBlocking { repository.sendMessage(userText) }

            // The call must have failed
            if (result.isSuccess) return@forAll false

            // History size must be exactly the same as before the call
            conversationManager.getHistory().size == sizeBefore
        }
    }

    "Property 9 - history size is unchanged after a failed sendMessage starting from empty history" {
        forAll(
            PropTestConfig(iterations = 20),
            arbUserText,
            arbHttpErrorCode
        ) { userText, errorCode ->
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(any()) } throws
                ApiException(errorCode, "Simulated API failure $errorCode")

            val conversationManager = ConversationManager()

            // Empty history — size before is 0
            val sizeBefore = conversationManager.getHistory().size

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            val result = runBlocking { repository.sendMessage(userText) }

            if (result.isSuccess) return@forAll false

            conversationManager.getHistory().size == sizeBefore
        }
    }

    "Property 9 - history size is unchanged after a failed sendMessage when history is at maximum capacity" {
        forAll(
            PropTestConfig(iterations = 20),
            arbUserText,
            arbHttpErrorCode
        ) { userText, errorCode ->
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(any()) } throws
                ApiException(errorCode, "Simulated API failure $errorCode")

            val conversationManager = ConversationManager()

            // Fill history to maximum capacity (10 turns = 20 messages)
            repeat(ConversationManager.MAX_TURNS) { i ->
                conversationManager.addUserMessage("user $i")
                conversationManager.addAssistantMessage("assistant $i")
            }

            val sizeBefore = conversationManager.getHistory().size

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            val result = runBlocking { repository.sendMessage(userText) }

            if (result.isSuccess) return@forAll false

            conversationManager.getHistory().size == sizeBefore
        }
    }

    "Property 9 - history size is unchanged after a failed sendMessage due to a generic IOException" {
        val arbHistory = Arb.list(
            Arb.pair(arbHistoryText, arbHistoryText),
            range = 0..5
        )

        forAll(
            PropTestConfig(iterations = 20),
            arbHistory,
            arbUserText
        ) { historyPairs, userText ->
            val mockApiClient = mockk<GeminiApiClient>()
            coEvery { mockApiClient.generateContent(any()) } throws
                java.io.IOException("Simulated network failure")

            val conversationManager = ConversationManager()

            historyPairs.forEach { (uText, aText) ->
                conversationManager.addUserMessage(uText)
                conversationManager.addAssistantMessage(aText)
            }

            val sizeBefore = conversationManager.getHistory().size

            val repository = GeminiRepositoryImpl(mockApiClient, conversationManager)

            val result = runBlocking { repository.sendMessage(userText) }

            if (result.isSuccess) return@forAll false

            conversationManager.getHistory().size == sizeBefore
        }
    }
})
