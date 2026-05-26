package com.example.sayuri.audio

import android.content.Context
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import io.mockk.mockk

/**
 * Property test for TtsWrapper long-text splitting.
 *
 * **Validates: Requirements 5.6**
 *
 * Property 17: Long Text TTS Splitting
 * For any response text exceeding 500 characters, TtsWrapper splits the text into
 * multiple chunks on sentence boundaries such that no individual chunk passed to the
 * TextToSpeech engine exceeds 500 characters, and no content is lost.
 *
 * The [TtsWrapper.splitIntoChunks] function is `internal` and performs pure string
 * manipulation — it does not use the Android [Context]. A MockK stub is used solely
 * to satisfy the constructor parameter.
 */
class TtsWrapperSplitTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Arbitrary generator
    // ---------------------------------------------------------------------------

    /**
     * Generates a string that is guaranteed to exceed [TtsWrapper.MAX_CHUNK_LENGTH]
     * (500 characters) by concatenating short sentences.
     *
     * Each sentence is a word of 10–30 lowercase letters followed by a period.
     * Sentences are joined with a single space, producing a normalized string
     * (no leading/trailing whitespace, single spaces between sentences).
     *
     * Using 10–30 sentences of 10–30 chars each guarantees a minimum length of
     * 10 * (10 + 1) = 110 chars and a maximum of 30 * (30 + 1) = 930 chars.
     * To ensure we always exceed 500 chars we use at least 20 sentences of at
     * least 10 chars: 20 * (10 + 1) = 220 chars minimum — still not enough.
     * We therefore use 30–60 sentences of 10–20 chars: minimum 30 * 11 = 330,
     * maximum 60 * 21 = 1260. To guarantee > 500 we use 50–80 sentences of
     * 10–15 chars: minimum 50 * 11 = 550 > 500. ✓
     */
    val arbLongText: Arb<String> = arbitrary {
        val wordLengths = Arb.int(10..15)
        val sentenceCount = Arb.int(50..80).bind()
        val sentences = (1..sentenceCount).map {
            val len = wordLengths.bind()
            val word = (1..len).map { ('a' + Arb.int(0..25).bind()) }.joinToString("")
            "$word."
        }
        sentences.joinToString(separator = " ")
    }

    // ---------------------------------------------------------------------------
    // Property 17: Long Text TTS Splitting — chunk size invariant
    // ---------------------------------------------------------------------------

    "Property 17 - every chunk produced for long text has length <= 500" {
        val context = mockk<Context>(relaxed = true)
        val wrapper = TtsWrapper(context)

        forAll(PropTestConfig(iterations = 20), arbLongText) { text ->
            val chunks = wrapper.splitIntoChunks(text)
            chunks.all { chunk -> chunk.length <= TtsWrapper.MAX_CHUNK_LENGTH }
        }
    }

    // ---------------------------------------------------------------------------
    // Property 17: Long Text TTS Splitting — no content lost
    // ---------------------------------------------------------------------------

    "Property 17 - concatenation of all chunks equals the original text (no content lost)" {
        val context = mockk<Context>(relaxed = true)
        val wrapper = TtsWrapper(context)

        forAll(PropTestConfig(iterations = 20), arbLongText) { text ->
            val chunks = wrapper.splitIntoChunks(text)

            // splitIntoChunks trims each sentence and joins them with a single space.
            // Our generator already produces normalized text (single spaces, no
            // leading/trailing whitespace), so the round-trip is exact.
            val reconstructed = chunks.joinToString(separator = " ")
            reconstructed == text.trim()
        }
    }

    // ---------------------------------------------------------------------------
    // Property 17: Long Text TTS Splitting — chunks list is non-empty
    // ---------------------------------------------------------------------------

    "Property 17 - chunks list is non-empty for any non-empty long text" {
        val context = mockk<Context>(relaxed = true)
        val wrapper = TtsWrapper(context)

        forAll(PropTestConfig(iterations = 20), arbLongText) { text ->
            val chunks = wrapper.splitIntoChunks(text)
            chunks.isNotEmpty()
        }
    }
})
