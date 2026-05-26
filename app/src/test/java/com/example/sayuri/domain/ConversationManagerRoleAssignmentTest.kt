package com.example.sayuri.domain

import com.example.sayuri.model.Role
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll

/**
 * Property test for ConversationManager role assignment.
 *
 * **Validates: Requirements 7.2, 7.3**
 *
 * Property 14: ConversationManager Role Assignment
 * For any sequence of addUserMessage() and addAssistantMessage() calls with any string
 * values, every entry added via addUserMessage() has role = USER and every entry added
 * via addAssistantMessage() has role = ASSISTANT.
 */
class ConversationManagerRoleAssignmentTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Arbitrary generator
    // ---------------------------------------------------------------------------

    /**
     * Generates an arbitrary sequence of (isUser: Boolean, text: String) pairs,
     * representing a mix of user and assistant messages to add to the manager.
     * Each pair encodes whether the call should be addUserMessage (true) or
     * addAssistantMessage (false), along with the message text.
     */
    val arbMessageSequence: Arb<List<Pair<Boolean, String>>> = arbitrary {
        Arb.list(
            arbitrary {
                Pair(
                    Arb.boolean().bind(),
                    Arb.string(0..100).bind()
                )
            },
            1..20
        ).bind()
    }

    // ---------------------------------------------------------------------------
    // Property 14: ConversationManager Role Assignment
    // ---------------------------------------------------------------------------

    "Property 14 - role assignment: every user-added entry has role USER and every assistant-added entry has role ASSISTANT" {
        forAll(PropTestConfig(iterations = 20), arbMessageSequence) { messages ->
            val manager = ConversationManager()

            // Track which indices correspond to user vs assistant messages
            val expectedRoles = mutableListOf<Role>()

            for ((isUser, text) in messages) {
                if (isUser) {
                    manager.addUserMessage(text)
                    expectedRoles.add(Role.USER)
                } else {
                    manager.addAssistantMessage(text)
                    expectedRoles.add(Role.ASSISTANT)
                }
            }

            val history = manager.getHistory()

            // The history may be shorter than expectedRoles due to the cap evicting
            // oldest entries. We align from the end of expectedRoles to match history.
            val offset = expectedRoles.size - history.size

            history.indices.all { i ->
                history[i].role == expectedRoles[offset + i]
            }
        }
    }
})
