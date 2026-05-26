package com.example.sayuri.audio

import com.example.sayuri.model.SpeechResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll

/**
 * Property-based test for Property 4: Transcript Non-Blank Invariant.
 *
 * **Validates: Requirements 3.3**
 *
 * For any recognition result returned by Android's SpeechRecognizer,
 * SpeechRecognizerWrapper never returns a SpeechResult.Success containing
 * a blank or empty transcript string.
 */
class SpeechRecognizerWrapperNonBlankTest : StringSpec({

    /**
     * Property 4: Transcript Non-Blank Invariant
     *
     * For any list of non-blank recognition result strings, when
     * selectBestTranscript returns a non-null result, wrapping it in
     * SpeechResult.Success always yields a non-blank transcript.
     *
     * **Validates: Requirements 3.3**
     */
    "Property 4 - SpeechResult.Success transcript is always non-blank for any non-blank input strings" {
        // Generator: non-blank strings (at least one non-whitespace character)
        val nonBlankStringArb = Arb.string(minSize = 1, maxSize = 100)
            .filter { it.isNotBlank() }

        forAll(
            PropTestConfig(iterations = 20),
            Arb.list(nonBlankStringArb, range = 1..5)
        ) { transcripts ->
            val result = SpeechRecognizerWrapper.selectBestTranscript(
                transcripts,
                confidences = null  // null → all default to 0f
            )

            // If a result is returned, it must be non-blank
            if (result != null) {
                val speechResult = SpeechResult.Success(result)
                speechResult.transcript.isNotBlank()
            } else {
                // null is acceptable (e.g. all inputs were only the wake word after stripping)
                true
            }
        }
    }
})
