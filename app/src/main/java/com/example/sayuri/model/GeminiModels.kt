package com.example.sayuri.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Request models
// ---------------------------------------------------------------------------

/**
 * Top-level request payload sent to the Gemini Flash REST API.
 *
 * @param contents          Ordered list of conversation turns (must be non-empty;
 *                          last entry must have role "user").
 * @param systemInstruction Optional system-level persona instruction.
 * @param generationConfig  Optional generation parameters (tokens, temperature, …).
 */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiSystemInstruction? = null,
    val generationConfig: GenerationConfig? = null
)

/**
 * A single conversation turn in a [GeminiRequest] or [GeminiResponse].
 *
 * @param role  Either `"user"` or `"model"`.
 * @param parts The text parts that make up this turn.
 */
@Serializable
data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>
)

/**
 * A single text fragment within a [GeminiContent].
 */
@Serializable
data class GeminiPart(val text: String)

/**
 * System-level instruction injected into every request to establish Sayuri's persona.
 */
@Serializable
data class GeminiSystemInstruction(val parts: List<GeminiPart>)

/**
 * Optional generation parameters forwarded to the Gemini API.
 *
 * @param maxOutputTokens Maximum number of tokens in the generated response.
 * @param temperature     Sampling temperature (higher = more creative).
 */
@Serializable
data class GenerationConfig(
    val maxOutputTokens: Int = 256,
    val temperature: Float = 0.9f
)

// ---------------------------------------------------------------------------
// Response models
// ---------------------------------------------------------------------------

/**
 * Top-level response payload received from the Gemini Flash REST API.
 */
@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>
)

/**
 * A single candidate response returned by the Gemini API.
 *
 * @param content      The generated content for this candidate.
 * @param finishReason Why generation stopped (e.g. `"STOP"`, `"SAFETY"`, …).
 */
@Serializable
data class GeminiCandidate(
    val content: GeminiContent,
    val finishReason: String
)
