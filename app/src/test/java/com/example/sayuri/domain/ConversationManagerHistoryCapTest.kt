package com.example.sayuri.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll

/**
 * Property test for ConversationManager history cap.
 *
 * **Validates: Requirements 7.4, 13.3**
 *
 * Property 15: ConversationManager History Cap
 * For any number of messages added to ConversationManager exceeding 10 turns,
 * the history length never exceeds 10 turns (20 messages) — older entries are
 * evicted to enforce the cap.
 */
class ConversationManagerHistoryCapTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Property 15: ConversationManager History Cap
    // ---------------------------------------------------------------------------

    "Property 15 - history size never exceeds 20 messages after adding n messages (n in 11..100)" {
        forAll(
            PropTestConfig(iterations = 20),
            Arb.int(11..100)
        ) { n ->
            val manager = ConversationManager()

            // Add n messages, alternating between user and assistant
            repeat(n) { i ->
                if (i % 2 == 0) {
                    manager.addUserMessage("user message $i")
                } else {
                    manager.addAssistantMessage("assistant message $i")
                }
            }

            manager.getHistory().size <= ConversationManager.MAX_TURNS * 2
        }
    }

    "Property 15 - history size never exceeds 20 messages when only user messages are added (n in 11..100)" {
        forAll(
            PropTestConfig(iterations = 20),
            Arb.int(11..100)
        ) { n ->
            val manager = ConversationManager()

            repeat(n) {
                manager.addUserMessage("user message")
            }

            manager.getHistory().size <= ConversationManager.MAX_TURNS * 2
        }
    }

    "Property 15 - history size never exceeds 20 messages when only assistant messages are added (n in 11..100)" {
        forAll(
            PropTestConfig(iterations = 20),
            Arb.int(11..100)
        ) { n ->
            val manager = ConversationManager()

            repeat(n) {
                manager.addAssistantMessage("assistant message")
            }

            manager.getHistory().size <= ConversationManager.MAX_TURNS * 2
        }
    }
})
