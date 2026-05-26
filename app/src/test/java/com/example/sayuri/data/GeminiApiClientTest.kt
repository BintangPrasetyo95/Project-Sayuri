package com.example.sayuri.data

import com.example.sayuri.model.ApiException
import com.example.sayuri.model.GeminiCandidate
import com.example.sayuri.model.GeminiContent
import com.example.sayuri.model.GeminiPart
import com.example.sayuri.model.GeminiRequest
import com.example.sayuri.model.GeminiResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Integration tests for [GeminiApiClient] using [MockWebServer].
 *
 * Covers:
 * - Correct URL construction (path + `?key=` query parameter)
 * - Successful 2xx response deserialization into [GeminiResponse]
 * - [ApiException] thrown with the correct HTTP status code on 4xx/5xx responses
 *
 * **Property 11: API Error Code Propagation**
 * For any HTTP status code in the 4xx or 5xx range returned by the Gemini API,
 * [GeminiApiClient] throws an [ApiException] whose `code` field equals the HTTP status code.
 *
 * **Validates: Requirements 4.6, 4.7**
 */
class GeminiApiClientTest : StringSpec({

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    val json = Json { ignoreUnknownKeys = true }

    /** Minimal valid [GeminiRequest] used across tests. */
    fun minimalRequest() = GeminiRequest(
        contents = listOf(
            GeminiContent(role = "user", parts = listOf(GeminiPart(text = "Hello")))
        )
    )

    /** Builds a well-formed [GeminiResponse] JSON body string. */
    fun successResponseBody(text: String = "Hi there!"): String = json.encodeToString(
        GeminiResponse(
            candidates = listOf(
                GeminiCandidate(
                    content = GeminiContent(
                        role = "model",
                        parts = listOf(GeminiPart(text = text))
                    ),
                    finishReason = "STOP"
                )
            )
        )
    )

    // ---------------------------------------------------------------------------
    // URL construction
    // ---------------------------------------------------------------------------

    "URL contains the endpoint path and the ?key= query parameter" {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(successResponseBody())
                    .addHeader("Content-Type", "application/json")
            )

            val client = GeminiApiClient(
                apiKey = "test-api-key",
                baseUrl = server.url("/v1beta/models/gemini-1.5-flash:generateContent").toString()
                    .removeSuffix("?") // strip trailing ? if any
            )

            runBlocking { client.generateContent(minimalRequest()) }

            val recordedRequest = server.takeRequest()
            val requestPath = recordedRequest.path ?: ""

            requestPath shouldContain "/v1beta/models/gemini-1.5-flash:generateContent"
            requestPath shouldContain "key=test-api-key"
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------------
    // 200 OK — successful deserialization
    // ---------------------------------------------------------------------------

    "200 OK response is deserialized into GeminiResponse" {
        val server = MockWebServer()
        server.start()
        try {
            val expectedText = "Hello from Gemini!"
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(successResponseBody(expectedText))
                    .addHeader("Content-Type", "application/json")
            )

            val client = GeminiApiClient(
                apiKey = "test-key",
                baseUrl = server.url("/generateContent").toString()
            )

            val response = runBlocking { client.generateContent(minimalRequest()) }

            response.candidates.size shouldBe 1
            response.candidates[0].content.parts[0].text shouldBe expectedText
            response.candidates[0].finishReason shouldBe "STOP"
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------------
    // 400 Bad Request
    // ---------------------------------------------------------------------------

    "400 response throws ApiException with code 400" {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

            val client = GeminiApiClient(
                apiKey = "test-key",
                baseUrl = server.url("/generateContent").toString()
            )

            val ex = shouldThrow<ApiException> {
                runBlocking { client.generateContent(minimalRequest()) }
            }
            ex.code shouldBe 400
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------------
    // 401 Unauthorized
    // ---------------------------------------------------------------------------

    "401 response throws ApiException with code 401" {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

            val client = GeminiApiClient(
                apiKey = "bad-key",
                baseUrl = server.url("/generateContent").toString()
            )

            val ex = shouldThrow<ApiException> {
                runBlocking { client.generateContent(minimalRequest()) }
            }
            ex.code shouldBe 401
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------------
    // 500 Internal Server Error
    // ---------------------------------------------------------------------------

    "500 response throws ApiException with code 500" {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            val client = GeminiApiClient(
                apiKey = "test-key",
                baseUrl = server.url("/generateContent").toString()
            )

            val ex = shouldThrow<ApiException> {
                runBlocking { client.generateContent(minimalRequest()) }
            }
            ex.code shouldBe 500
        } finally {
            server.shutdown()
        }
    }

    // ---------------------------------------------------------------------------
    // Property 11: API Error Code Propagation
    //
    // For any HTTP status code in the 4xx or 5xx range, GeminiApiClient throws
    // an ApiException whose `code` field equals the HTTP status code.
    //
    // Validates: Requirements 4.6, 4.7
    // ---------------------------------------------------------------------------

    "Property 11 - API Error Code Propagation: ApiException.code equals HTTP status for any 4xx/5xx" {
        // Combine 4xx (400–499) and 5xx (500–599) ranges
        val arb4xx = Arb.int(400..499)
        val arb5xx = Arb.int(500..599)

        // Test 4xx range
        forAll(PropTestConfig(iterations = 20), arb4xx) { statusCode ->
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(statusCode).setBody("Error"))

                val client = GeminiApiClient(
                    apiKey = "test-key",
                    baseUrl = server.url("/generateContent").toString()
                )

                var caughtCode: Int? = null
                try {
                    runBlocking { client.generateContent(minimalRequest()) }
                } catch (e: ApiException) {
                    caughtCode = e.code
                }

                caughtCode == statusCode
            } finally {
                server.shutdown()
            }
        }

        // Test 5xx range
        forAll(PropTestConfig(iterations = 20), arb5xx) { statusCode ->
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(statusCode).setBody("Error"))

                val client = GeminiApiClient(
                    apiKey = "test-key",
                    baseUrl = server.url("/generateContent").toString()
                )

                var caughtCode: Int? = null
                try {
                    runBlocking { client.generateContent(minimalRequest()) }
                } catch (e: ApiException) {
                    caughtCode = e.code
                }

                caughtCode == statusCode
            } finally {
                server.shutdown()
            }
        }
    }
})
