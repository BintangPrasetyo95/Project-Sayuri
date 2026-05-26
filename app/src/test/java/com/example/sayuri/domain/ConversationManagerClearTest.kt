package com.example.sayuri.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll

/**
 * Property test for ConversationManager clear idempotence.
 *
 * **Validates: Requirements 7.5**
 *
 * Property 16: ConversationManager Clear Idempotence
 * For any conversation history state (empty or non-empty), calling
 * ConversationManager.clear() always results in an empty history list —
 * getHistory().isEmpty() == true.
 */
class ConversationManagerClearTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Arbitrary generator: a pre-populated ConversationManager
    // ---------------------------------------------------------------------------

    /**
     * Generates a ConversationManager with an arbitrary number of messages (0..30).
     * Each message is added as either a user or assistant message, chosen randomly.
     */
    val arbPopulatedManager: Arb<ConversationManager> = arbitrary {
        val manager = ConversationManager()
        val messageCount = Arb.int(0..30).bind()
        repeat(messageCount) {
            val isUser = Arb.boolean().bind()
            val text = Arb.string(0..50).bind()
            if (isUser) {
                manager.addUserMessage(text)
            } else {
                manager.addAssistantMessage(text)
            }
        }
        manager
    }

    // ---------------------------------------------------------------------------
    // Property 16: ConversationManager Clear Idempotence
    // ---------------------------------------------------------------------------

    "Property 16 - ConversationManager clear idempotence: getHistory().isEmpty() == true after clear()" {
        forAll(PropTestConfig(iterations = 20), arbPopulatedManager) { manager ->
            manager.clear()
            manager.getHistory().isEmpty()
        }
    }

    // ---------------------------------------------------------------------------
    // Additional: calling clear() twice is also idempotent
    // ---------------------------------------------------------------------------

    "Property 16 - ConversationManager clear is idempotent when called multiple times" {
        forAll(PropTestConfig(iterations = 20), arbPopulatedManager) { manager ->
            manager.clear()
            manager.clear() // second call should be a no-op
            manager.getHistory().isEmpty()
        }
    }
})
