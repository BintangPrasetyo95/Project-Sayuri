package com.example.sayuri.audio

import com.example.sayuri.model.SpeechResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * Unit tests for [SpeechRecognizerWrapper.selectBestTranscript].
 *
 * Because [SpeechRecognizerWrapper] relies on Android's [android.speech.SpeechRecognizer],
 * which requires a real device context, the transcript-selection and wake-word-stripping
 * logic is extracted into the companion function [SpeechRecognizerWrapper.selectBestTranscript]
 * so it can be exercised here as a pure JVM unit test — no Android context needed.
 *
 * Covers requirements:
 *   3.2 — Highest-confidence transcript selection
 *   3.3 — Wake word prefix stripping and non-blank invariant
 */
class SpeechRecognizerWrapperTest : StringSpec({

    // -------------------------------------------------------------------------
    // Requirement 3.2 — Highest-confidence transcript selection
    // -------------------------------------------------------------------------

    "selectBestTranscript returns the transcript with the highest confidence score" {
        val transcripts = listOf("hello world", "hello there", "hi there")
        val confidences = floatArrayOf(0.5f, 0.9f, 0.3f)
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, confidences)
        result shouldBe "hello there"
    }

    "selectBestTranscript returns the first transcript when all confidence scores are equal" {
        val transcripts = listOf("option one", "option two", "option three")
        val confidences = floatArrayOf(0.7f, 0.7f, 0.7f)
        // maxByOrNull is stable for equal values — first maximum wins
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, confidences)
        result shouldBe "option one"
    }

    "selectBestTranscript returns the single transcript when only one result is provided" {
        val transcripts = listOf("what is the weather today")
        val confidences = floatArrayOf(0.85f)
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, confidences)
        result shouldBe "what is the weather today"
    }

    "selectBestTranscript defaults to 0f confidence when confidences array is null" {
        // With null confidences all entries default to 0f, so the first entry wins
        val transcripts = listOf("first result", "second result")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result shouldBe "first result"
    }

    "selectBestTranscript defaults to 0f for missing confidence entries" {
        // confidences array shorter than transcripts — missing entries default to 0f
        val transcripts = listOf("low confidence", "high confidence", "no score")
        val confidences = floatArrayOf(0.2f, 0.8f) // index 2 missing → 0f
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, confidences)
        result shouldBe "high confidence"
    }

    // -------------------------------------------------------------------------
    // Requirement 3.3 — Wake word prefix stripping
    // -------------------------------------------------------------------------

    "selectBestTranscript strips lowercase 'sayuri ' prefix from the transcript" {
        val transcripts = listOf("sayuri what time is it")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result shouldBe "what time is it"
    }

    "selectBestTranscript strips title-case 'Sayuri ' prefix from the transcript" {
        val transcripts = listOf("Sayuri set a timer for five minutes")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result shouldBe "set a timer for five minutes"
    }

    "selectBestTranscript strips 'SAYURI ' prefix (case-insensitive)" {
        val transcripts = listOf("SAYURI play some music")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result shouldBe "play some music"
    }

    "selectBestTranscript strips standalone 'sayuri' with no trailing content and returns null" {
        // Only the wake word was spoken — stripped result is blank → null
        val transcripts = listOf("sayuri")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result.shouldBeNull()
    }

    "selectBestTranscript does not strip 'sayuri' when it appears mid-sentence" {
        // Wake word stripping only applies to the start of the transcript
        val transcripts = listOf("I said sayuri earlier")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result shouldBe "I said sayuri earlier"
    }

    "selectBestTranscript does not modify transcripts that do not start with the wake word" {
        val transcripts = listOf("what is the capital of France")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result shouldBe "what is the capital of France"
    }

    // -------------------------------------------------------------------------
    // Requirement 3.3 — Non-blank invariant
    // -------------------------------------------------------------------------

    "selectBestTranscript returns null for an empty transcript list" {
        val result = SpeechRecognizerWrapper.selectBestTranscript(emptyList(), null)
        result.shouldBeNull()
    }

    "selectBestTranscript returns null when the best transcript is blank after stripping" {
        // Transcript is only whitespace after stripping the wake word
        val transcripts = listOf("sayuri   ")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result.shouldBeNull()
    }

    "selectBestTranscript result is always non-blank when non-null" {
        val transcripts = listOf("sayuri tell me a joke")
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, null)
        result?.shouldNotBeBlank()
    }

    // -------------------------------------------------------------------------
    // Requirement 3.2 + 3.3 — Combined: highest confidence + wake word stripping
    // -------------------------------------------------------------------------

    "selectBestTranscript picks highest-confidence result and then strips wake word" {
        val transcripts = listOf(
            "hello world",          // confidence 0.4
            "sayuri what is 2+2",   // confidence 0.9 — highest
            "sayuri"                // confidence 0.6
        )
        val confidences = floatArrayOf(0.4f, 0.9f, 0.6f)
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, confidences)
        result shouldBe "what is 2+2"
    }

    "selectBestTranscript returns null when highest-confidence result is only the wake word" {
        val transcripts = listOf(
            "some other text",  // confidence 0.3
            "sayuri"            // confidence 0.95 — highest, but blank after stripping
        )
        val confidences = floatArrayOf(0.3f, 0.95f)
        val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, confidences)
        result.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // SpeechResult model shape
    // -------------------------------------------------------------------------

    "SpeechResult.Success carries a non-blank transcript" {
        val success = SpeechResult.Success("tell me a joke")
        success.transcript shouldBe "tell me a joke"
        success.transcript.shouldNotBeBlank()
    }

    "SpeechResult.Error carries the error code and message" {
        val error = SpeechResult.Error(code = 7, message = "No speech match")
        error.code shouldBe 7
        error.message shouldBe "No speech match"
    }
})
