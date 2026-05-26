package com.example.sayuri.audio

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll

/**
 * Property-based test for Property 5: Highest-Confidence Transcript Selection.
 *
 * **Validates: Requirements 3.2**
 *
 * For any list of (transcript, confidence) pairs, [SpeechRecognizerWrapper.selectBestTranscript]
 * must return the transcript that has the highest confidence score.
 */
class SpeechRecognizerWrapperHighestConfidenceTest : StringSpec({

    /**
     * Property 5: Highest-Confidence Transcript Selection
     *
     * For any non-empty list of (transcript, confidence) pairs where transcripts
     * do not start with "sayuri" (to avoid being stripped to blank), the result
     * of selectBestTranscript must equal the transcript paired with the maximum
     * confidence score.
     *
     * **Validates: Requirements 3.2**
     */
    "Property 5 - selectBestTranscript returns the transcript with the highest confidence score" {
        // Generator: transcripts that don't start with "sayuri" (case-insensitive)
        // so they won't be stripped to blank, ensuring a non-null result.
        val safeTranscriptArb = Arb.string(minSize = 1, maxSize = 50)
            .filter { it.isNotBlank() && !it.trim().lowercase().startsWith("sayuri") }

        // Generator: confidence score in [0f, 1f]
        val confidenceArb = Arb.float(min = 0f, max = 1f)
            .filter { it.isFinite() }

        // Generator: a single (transcript, confidence) pair
        val pairArb = Arb.bind(safeTranscriptArb, confidenceArb) { t, c -> Pair(t, c) }

        // Generator: non-empty list of pairs (1..5 entries)
        val pairsArb = Arb.list(pairArb, range = 1..5)

        forAll(
            PropTestConfig(iterations = 20),
            pairsArb
        ) { pairs ->
            val transcripts = pairs.map { it.first }
            val confidences = pairs.map { it.second }.toFloatArray()

            val result = SpeechRecognizerWrapper.selectBestTranscript(transcripts, confidences)

            // Find the expected best: the transcript paired with the maximum confidence.
            // maxByOrNull is stable — first maximum wins on ties, matching the implementation.
            val expectedBest = pairs.maxByOrNull { it.second }!!.first.trim()

            result == expectedBest
        }
    }
})
