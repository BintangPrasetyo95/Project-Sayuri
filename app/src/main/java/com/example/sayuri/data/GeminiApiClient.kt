package com.example.sayuri.data

import android.util.Log
import com.example.sayuri.BuildConfig
import com.example.sayuri.model.ApiException
import com.example.sayuri.model.GeminiRequest
import com.example.sayuri.model.GeminiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Low-level HTTP client for the Gemini Flash REST API.
 *
 * Serializes [GeminiRequest] to JSON via `kotlinx.serialization`, POSTs to the
 * Gemini Flash endpoint, and deserializes the response body into a [GeminiResponse].
 *
 * All HTTP operations run on [Dispatchers.IO].
 * The API key is appended as a URL query parameter and is never written to logs.
 *
 * @param apiKey  The Gemini API key. Defaults to [BuildConfig.GEMINI_API_KEY].
 * @param baseUrl Override the base URL (used in tests to point at a [MockWebServer]).
 *                Defaults to the production Gemini Flash endpoint.
 */
class GeminiApiClient(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val baseUrl: String = PRODUCTION_BASE_URL
) {

    companion object {
        /** Single shared [OkHttpClient] instance — reused for connection pooling. */
        private val okHttpClient: OkHttpClient = OkHttpClient()

        internal const val PRODUCTION_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Lenient Json instance that ignores unknown keys from the API response. */
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false  // don't serialize null fields — Gemini rejects them
        }
    }

    /**
     * Sends [request] to the Gemini Flash API and returns the deserialized [GeminiResponse].
     *
     * @throws ApiException   if the server returns an HTTP 4xx or 5xx status code.
     * @throws IOException    if a network-level error occurs.
     */
    suspend fun generateContent(request: GeminiRequest): GeminiResponse =
        withContext(Dispatchers.IO) {
            val requestJson = json.encodeToString(request)
            Log.d("GeminiApiClient", "Request JSON: $requestJson")

            val requestBody = requestJson.toRequestBody(JSON_MEDIA_TYPE)

            // Append the API key as a query parameter — never log the key value.
            val url = "$baseUrl?key=$apiKey"

            val httpRequest = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(httpRequest).await()

            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = try { resp.body?.string() } catch (e: Exception) { null }
                    Log.e("GeminiApiClient", "HTTP ${resp.code}: $errorBody")
                    throw ApiException(resp.code, errorBody ?: resp.message)
                }

                val body = resp.body?.string()
                    ?: throw IOException("Empty response body from Gemini API")

                json.decodeFromString<GeminiResponse>(body)
            }
        }
}

/**
 * Bridges OkHttp's [Call.enqueue] callback into a [suspendCancellableCoroutine],
 * so the HTTP call can be awaited from a coroutine without blocking a thread.
 *
 * Cancelling the coroutine cancels the underlying OkHttp [Call].
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }

    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}
