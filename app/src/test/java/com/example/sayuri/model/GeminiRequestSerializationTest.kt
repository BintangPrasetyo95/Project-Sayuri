package com.example.sayuri.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Property test for GeminiRequest serialization round-trip.
 *
 * **Validates: Requirements 4.8**
 *
 * Property 12: GeminiRequest Serialization Round-Trip
 * For any valid GeminiRequest object, serializing it to JSON using kotlinx.serialization
 * and then deserializing the resulting JSON back produces an equivalent GeminiRequest object.
 */
class GeminiRequestSerializationTest : StringSpec({

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

    val arbGeminiSystemInstruction: Arb<GeminiSystemInstruction> = arbitrary {
        GeminiSystemInstruction(
            parts = Arb.list(arbGeminiPart, 1..3).bind()
        )
    }

    val arbGenerationConfig: Arb<GenerationConfig> = arbitrary {
        GenerationConfig(
            maxOutputTokens = Arb.int(1..4096).bind(),
            temperature = Arb.float(0.0f..2.0f).bind()
        )
    }

    val arbGeminiRequest: Arb<GeminiRequest> = arbitrary {
        GeminiRequest(
            contents = Arb.list(arbGeminiContent, 1..10).bind(),
            systemInstruction = arbGeminiSystemInstruction.orNull(0.3).bind(),
            generationConfig = arbGenerationConfig.orNull(0.3).bind()
        )
    }

    // ---------------------------------------------------------------------------
    // Property 12: GeminiRequest Serialization Round-Trip
    // ---------------------------------------------------------------------------

    "Property 12 - GeminiRequest serialization round-trip: decode(encode(request)) == request" {
        forAll(PropTestConfig(iterations = 20), arbGeminiRequest) { request ->
            val json = Json.encodeToString(request)
            val decoded = Json.decodeFromString<GeminiRequest>(json)
            decoded == request
        }
    }
})
