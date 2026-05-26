package com.example.sayuri.model

/**
 * Thrown by [com.example.sayuri.data.GeminiApiClient] when the Gemini API returns
 * an HTTP 4xx or 5xx status code.
 *
 * @param code    The HTTP status code (e.g. 400, 401, 500).
 * @param message A short description of the HTTP error.
 */
class ApiException(val code: Int, override val message: String) :
    Exception("Gemini API error $code: $message")
