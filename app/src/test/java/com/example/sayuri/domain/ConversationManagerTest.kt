package com.example.sayuri.domain

import com.example.sayuri.model.Role
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [ConversationManager].
 *
 * Covers requirements 7.1, 7.2, 7.3, 7.4, 7.5.
 */
class ConversationManagerTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Requirement 7.1 — in-memory list
    // ---------------------------------------------------------------------------

    "getHistory returns empty list when no messages have been added" {
        val manager = ConversationManager()
        manager.getHistory().shouldBeEmpty()
    }

    // ---------------------------------------------------------------------------
    // Requirement 7.2 — addUserMessage role
    // ---------------------------------------------------------------------------

    "addUserMessage appends a turn with role USER" {
        val manager = ConversationManager()
        manager.addUserMessage("Hello")
        val history = manager.getHistory()
        history shouldHaveSize 1
        history[0].role shouldBe Role.USER
        history[0].text shouldBe "Hello"
    }

    // ---------------------------------------------------------------------------
    // Requirement 7.3 — addAssistantMessage role
    // ---------------------------------------------------------------------------

    "addAssistantMessage appends a turn with role ASSISTANT" {
        val manager = ConversationManager()
        manager.addAssistantMessage("Hi there")
        val history = manager.getHistory()
        history shouldHaveSize 1
        history[0].role shouldBe Role.ASSISTANT
        history[0].text shouldBe "Hi there"
    }

    // ---------------------------------------------------------------------------
    // Requirement 7.1 — getHistory returns immutable copy
    // ---------------------------------------------------------------------------

    "getHistory returns an immutable copy — mutating the returned list does not affect internal state" {
        val manager = ConversationManager()
        manager.addUserMessage("first")
        val snapshot = manager.getHistory().toMutableList()
        snapshot.clear()
        // Internal history should still have 1 entry
        manager.getHistory() shouldHaveSize 1
    }

    // ---------------------------------------------------------------------------
    // Requirement 7.5 — clear
    // ---------------------------------------------------------------------------

    "clear removes all entries from history" {
        val manager = ConversationManager()
        manager.addUserMessage("msg1")
        manager.addAssistantMessage("msg2")
        manager.clear()
        manager.getHistory().shouldBeEmpty()
    }

    "clear on an already-empty manager leaves history empty" {
        val manager = ConversationManager()
        manager.clear()
        manager.getHistory().shouldBeEmpty()
    }

    // ---------------------------------------------------------------------------
    // Requirement 7.4 — history cap (10 turns = 20 messages)
    // ---------------------------------------------------------------------------

    "history never exceeds 20 messages after adding 21 messages" {
        val manager = ConversationManager()
        // Add 10 full turns (20 messages)
        repeat(10) { i ->
            manager.addUserMessage("user $i")
            manager.addAssistantMessage("assistant $i")
        }
        manager.getHistory() shouldHaveSize 20

        // Adding one more user message should evict the oldest pair
        manager.addUserMessage("user 10")
        manager.getHistory().size shouldBe 19 // 18 remaining + 1 new
    }

    "oldest pair is evicted when cap is exceeded" {
        val manager = ConversationManager()
        repeat(10) { i ->
            manager.addUserMessage("user $i")
            manager.addAssistantMessage("assistant $i")
        }
        // The oldest messages are "user 0" and "assistant 0"
        manager.addUserMessage("user 10")
        val history = manager.getHistory()
        // "user 0" and "assistant 0" should have been evicted
        history.none { it.text == "user 0" }.shouldBeTrue()
        history.none { it.text == "assistant 0" }.shouldBeTrue()
        // "user 1" should now be the first entry
        history[0].text shouldBe "user 1"
    }

    "history size stays at or below 20 after many additions" {
        val manager = ConversationManager()
        repeat(50) { i ->
            if (i % 2 == 0) manager.addUserMessage("user $i")
            else manager.addAssistantMessage("assistant $i")
        }
        (manager.getHistory().size <= 20).shouldBeTrue()
    }

    // ---------------------------------------------------------------------------
    // Ordering — messages appear in insertion order
    // ---------------------------------------------------------------------------

    "messages appear in insertion order" {
        val manager = ConversationManager()
        manager.addUserMessage("first")
        manager.addAssistantMessage("second")
        manager.addUserMessage("third")
        val history = manager.getHistory()
        history[0].text shouldBe "first"
        history[1].text shouldBe "second"
        history[2].text shouldBe "third"
    }
})
