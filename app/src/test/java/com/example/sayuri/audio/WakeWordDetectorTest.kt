package com.example.sayuri.audio

import com.example.sayuri.model.WakeWordResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [WakeWordDetector.classifyResults].
 *
 * Because [WakeWordDetector] relies on Android's [android.speech.SpeechRecognizer], which
 * requires a real device context, the result-classification logic is extracted into the
 * companion function [WakeWordDetector.classifyResults] so it can be exercised here as a
 * pure JVM unit test — no Android context, no MockK mocking of framework classes needed.
 *
 * Covers requirement 1a.2:
 *   "WHEN a recognition result is returned during wake word polling, THE WakeWordDetector
 *    SHALL check whether any result string contains the substring 'sayuri'
 *    (case-insensitive) and return WakeWordResult.Detected if found, or
 *    WakeWordResult.NotDetected otherwise."
 */
class WakeWordDetectorTest : StringSpec({

    // -------------------------------------------------------------------------
    // Requirement 1a.2 — lowercase "sayuri" → Detected
    // -------------------------------------------------------------------------

    "classifyResults returns Detected when a result contains lowercase 'sayuri'" {
        val result = WakeWordDetector.classifyResults(listOf("hey sayuri what's the time"))
        result shouldBe WakeWordResult.Detected
    }

    // -------------------------------------------------------------------------
    // Requirement 1a.2 — uppercase "SAYURI" → Detected (case-insensitive)
    // -------------------------------------------------------------------------

    "classifyResults returns Detected when a result contains uppercase 'SAYURI'" {
        val result = WakeWordDetector.classifyResults(listOf("SAYURI play some music"))
        result shouldBe WakeWordResult.Detected
    }

    // -------------------------------------------------------------------------
    // Requirement 1a.2 — mixed case → Detected
    // -------------------------------------------------------------------------

    "classifyResults returns Detected when a result contains mixed-case 'Sayuri'" {
        val result = WakeWordDetector.classifyResults(listOf("Sayuri set a timer"))
        result shouldBe WakeWordResult.Detected
    }

    // -------------------------------------------------------------------------
    // Requirement 1a.2 — wake word in any result in the list → Detected
    // -------------------------------------------------------------------------

    "classifyResults returns Detected when only one of multiple results contains the wake word" {
        val result = WakeWordDetector.classifyResults(
            listOf("hello there", "sayuri turn off the lights", "random noise")
        )
        result shouldBe WakeWordResult.Detected
    }

    // -------------------------------------------------------------------------
    // Requirement 1a.2 — no wake word → NotDetected
    // -------------------------------------------------------------------------

    "classifyResults returns NotDetected when no result contains the wake word" {
        val result = WakeWordDetector.classifyResults(listOf("hello world", "good morning"))
        result shouldBe WakeWordResult.NotDetected
    }

    "classifyResults returns NotDetected for an empty result list" {
        val result = WakeWordDetector.classifyResults(emptyList())
        result shouldBe WakeWordResult.NotDetected
    }

    "classifyResults returns NotDetected when results contain unrelated words" {
        val result = WakeWordDetector.classifyResults(listOf("siri", "alexa", "cortana", "bixby"))
        result shouldBe WakeWordResult.NotDetected
    }

    // -------------------------------------------------------------------------
    // onError path — WakeWordResult.Error
    //
    // The onError callback in WakeWordDetector.detect() directly resumes the
    // coroutine with WakeWordResult.Error. We verify the data class shape here.
    // -------------------------------------------------------------------------

    "WakeWordResult.Error carries the error code and message" {
        val error = WakeWordResult.Error(code = 7, message = "No speech match")
        error.shouldBeInstanceOf<WakeWordResult.Error>()
        error.code shouldBe 7
        error.message shouldBe "No speech match"
    }

    "WakeWordResult.Error with different codes are not equal" {
        val error1 = WakeWordResult.Error(code = 1, message = "Audio recording error")
        val error2 = WakeWordResult.Error(code = 2, message = "Client-side error")
        (error1 == error2) shouldBe false
    }
})
