package com.example.sayuri.data

import com.example.sayuri.model.FunctionDeclaration
import com.example.sayuri.model.FunctionParameters
import com.example.sayuri.model.FunctionProperty
import com.example.sayuri.model.GeminiRequest
import com.example.sayuri.model.GeminiTool
import com.example.sayuri.model.GeminiContent
import com.example.sayuri.model.GeminiPart
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

class GeminiGenerateContentSerializationTest : StringSpec({
    "function tool parameters JSON includes type object when properties are present" {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(role = "user", parts = listOf(GeminiPart(text = "Test")))
            ),
            tools = listOf(
                GeminiTool(
                    functionDeclarations = listOf(
                        FunctionDeclaration(
                            name = "tap",
                            description = "Tap the screen.",
                            parameters = FunctionParameters(
                                type = "object",
                                properties = mapOf(
                                    "x" to FunctionProperty("number", "x coordinate"),
                                    "y" to FunctionProperty("number", "y coordinate")
                                ),
                                required = listOf("x", "y")
                            )
                        )
                    )
                )
            )
        )

        val jsonString = json.encodeToString(GeminiRequest.serializer(), request)

        jsonString shouldContain "\"type\":\"object\""
        jsonString shouldContain "\"properties\":"
        jsonString shouldContain "\"required\":[\"x\",\"y\"]"
    }
})
