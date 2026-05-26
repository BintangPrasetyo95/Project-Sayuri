package com.example.sayuri.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Property test for GeminiResponse serialization round-trip.
 *
 * **Validates: Requirements 4.8**
 *
 * Property 13: GeminiResponse Serialization Round-Trip
 * For any valid GeminiResponse object, serializing it to JSON using kotlinx.serialization
 * and then deserializing the resulting JSON back produces an equivalent GeminiResponse object.
 */
class GeminiResponseSerializationTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Arbitrary generators
    // ---------------------------------------------------------------------------

    val arbGeminiPart: Arb<GeminiPart> = arbitrary {
        GeminiPart(text = Arb.string(0..200).bind())
    }

    val arbGeminiContent: Arb<GeminiContent> = arbitrary {
        GeminiContent(
            role = Arb.string(1..10).bind(),
            parts = Arb.list(arbGeminiPart, 1..5).bind()
        )
    }

    val arbGeminiCandidate: Arb<GeminiCandidate> = arbitrary {
        GeminiCandidate(
            content = arbGeminiContent.bind(),
            finishReason = Arb.string(1..20).bind()
        )
    }

    val arbGeminiResponse: Arb<GeminiResponse> = arbitrary {
        GeminiResponse(
            candidates = Arb.list(arbGeminiCandidate, 0..5).bind()
        )
    }

    // ---------------------------------------------------------------------------
    // Property 13: GeminiResponse Serialization Round-Trip
    // ---------------------------------------------------------------------------

    "Property 13 - GeminiResponse serialization round-trip: decode(encode(response)) == response" {
        forAll(PropTestConfig(iterations = 20), arbGeminiResponse) { response ->
            val json = Json.encodeToString(response)
            val decoded = Json.decodeFromString<GeminiResponse>(json)
            decoded == response
        }
    }
})
