package com.example.sayuri.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// Request models
// ---------------------------------------------------------------------------

/**
 * Top-level request payload sent to the Gemini Flash REST API.
 */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiSystemInstruction? = null,
    val generationConfig: GenerationConfig? = null,
    val tools: List<GeminiTool>? = null
)

/**
 * A single conversation turn in a [GeminiRequest] or [GeminiResponse].
 */
@Serializable
data class GeminiContent(
    val role: String = "",
    val parts: List<GeminiPart> = emptyList()
)

/**
 * A single part within a [GeminiContent] — either text, a function call, or a function result.
 */
@Serializable
data class GeminiPart(
    val text: String? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null,
    @SerialName("functionResponse") val functionResponse: GeminiFunctionResponse? = null
)

/**
 * A function call requested by the model.
 */
@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: Map<String, JsonElement> = emptyMap()
)

/**
 * The result of executing a function, sent back to the model.
 */
@Serializable
data class GeminiFunctionResponse(
    val name: String,
    val response: Map<String, JsonElement> = emptyMap()
)

/**
 * A tool definition containing function declarations the model can call.
 */
@Serializable
data class GeminiTool(
    @SerialName("functionDeclarations") val functionDeclarations: List<FunctionDeclaration>
)

/**
 * Declares a single callable function with its name, description, and parameter schema.
 */
@Serializable
data class FunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: FunctionParameters? = null
)

/**
 * JSON Schema-style parameter definition for a function declaration.
 */
@Serializable
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, FunctionProperty> = emptyMap(),
    val required: List<String>? = null  // null = omitted, empty list causes 400
)

/**
 * A single property in a function's parameter schema.
 */
@Serializable
data class FunctionProperty(
    val type: String,
    val description: String
)

/**
 * System-level instruction injected into every request to establish Sayuri's persona.
 * Uses a dedicated TextPart to avoid nullable fields in the system instruction.
 */
@Serializable
data class GeminiSystemInstruction(val parts: List<TextPart>)

/** A simple text-only part used in systemInstruction (no nullable fields). */
@Serializable
data class TextPart(val text: String)

/**
 * Optional generation parameters forwarded to the Gemini API.
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
 */
@Serializable
data class GeminiCandidate(
    val content: GeminiContent = GeminiContent(),
    val finishReason: String = ""
)
