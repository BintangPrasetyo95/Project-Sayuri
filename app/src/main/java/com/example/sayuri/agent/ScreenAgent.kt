/**
 * ScreenAgent.kt
 *
 * AI agent that sees your screen and controls it autonomously.
 */

package com.example.sayuri.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.example.sayuri.BuildConfig
import com.example.sayuri.data.GeminiApiClient
import com.example.sayuri.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@RequiresApi(Build.VERSION_CODES.R)
class ScreenAgent(
    private val context: Context,
    apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val maxSteps: Int = 20
) {
    private val apiClient = GeminiApiClient(apiKey)

    private val tools = listOf(
        GeminiTool(
            functionDeclarations = listOf(
                FunctionDeclaration(
                    name = "tap",
                    description = "Tap (click) at a specific pixel coordinate on the screen.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "x" to FunctionProperty("number", "X coordinate in pixels from the left edge."),
                            "y" to FunctionProperty("number", "Y coordinate in pixels from the top edge.")
                        ),
                        required = listOf("x", "y")
                    )
                ),
                FunctionDeclaration(
                    name = "type_text",
                    description = "Type text into the currently focused input field.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "text" to FunctionProperty("string", "The text to type.")
                        ),
                        required = listOf("text")
                    )
                ),
                FunctionDeclaration(
                    name = "scroll",
                    description = "Scroll the screen up or down.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "direction" to FunctionProperty("string", "Either 'up' or 'down'.")
                        ),
                        required = listOf("direction")
                    )
                ),
                FunctionDeclaration(
                    name = "press_back",
                    description = "Press the Android Back button.",
                    parameters = FunctionParameters(type = "object")
                ),
                FunctionDeclaration(
                    name = "press_home",
                    description = "Press the Android Home button to go to the launcher.",
                    parameters = FunctionParameters(type = "object")
                ),
                FunctionDeclaration(
                    name = "open_app",
                    description = "Launch any installed app by package name.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "package_name" to FunctionProperty("string", "Android package name, e.g. com.whatsapp, com.google.android.youtube")
                        ),
                        required = listOf("package_name")
                    )
                ),
                FunctionDeclaration(
                    name = "done",
                    description = "Call this when the task is fully complete.",
                    parameters = FunctionParameters(
                        type = "object",
                        properties = mapOf(
                            "summary" to FunctionProperty("string", "One-sentence summary of what was accomplished.")
                        ),
                        required = listOf("summary")
                    )
                )
            )
        )
    )

    private val systemInstruction = GeminiSystemInstruction(
        parts = listOf(
            TextPart(
                text = """
                    You are an Android screen-control agent.
                    You can perform taps, type text, scroll, press Back/Home, and open apps.
                    Use the available tool functions only.
                    If the task is complete, call the done() function with a short summary.
                    Always wait for the updated screen state before taking the next action.
                """.trimIndent()
            )
        )
    )

    suspend fun run(instruction: String): String {
        return withContext(Dispatchers.IO) {
            val svc = AgentAccessibilityService.instance
                ?: return@withContext buildAccessibilityError()

            val history = mutableListOf<GeminiContent>()
            history.add(GeminiContent(role = "user", parts = listOf(GeminiPart(text = "Task: $instruction"))))
            history.add(
                GeminiContent(
                    role = "user",
                    parts = listOf(
                        GeminiPart(
                            text = "The device screen is available via the accessibility service. " +
                                "Capture the screen and choose the next action carefully."
                        )
                    )
                )
            )

            var response = sendRequest(history)
            var stepCount = 0

            while (stepCount < maxSteps) {
                val part = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()
                val functionCall = part?.functionCall

                if (functionCall == null) {
                    return@withContext part?.text ?: "No action returned from Gemini."
                }

                if (functionCall.name == "done") {
                    return@withContext functionCall.args["summary"]?.jsonPrimitive?.content
                        ?: "Task completed."
                }

                val actionResult = executeAction(svc, functionCall.name, functionCall.args)
                stepCount++

                history.add(
                    GeminiContent(
                        role = "assistant",
                        parts = listOf(
                            GeminiPart(
                                functionResponse = GeminiFunctionResponse(
                                    name = functionCall.name,
                                    response = mapOf("result" to JsonPrimitive(actionResult))
                                )
                            )
                        )
                    )
                )

                val screenshot = svc.captureScreen()
                val updateText = if (screenshot != null) {
                    "Updated screenshot captured after action."
                } else {
                    "Unable to capture screenshot after action. Continue based on visible app state."
                }

                history.add(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = updateText))
                    )
                )

                response = sendRequest(history)
            }

            "Reached max steps ($maxSteps) without completing."
        }
    }

    private suspend fun sendRequest(history: List<GeminiContent>): GeminiResponse {
        return apiClient.generateContent(
            GeminiRequest(
                contents = history,
                systemInstruction = systemInstruction,
                generationConfig = GenerationConfig(maxOutputTokens = 1024, temperature = 0.1f),
                tools = tools
            )
        )
    }

    private fun executeAction(
        svc: AgentAccessibilityService,
        name: String,
        args: Map<String, JsonElement>
    ): String {
        return try {
            when (name) {
                "tap" -> {
                    val x = args["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                    val y = args["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                    val ok = svc.tap(x, y)
                    if (ok) "Tapped ($x, $y)" else "Tap failed at ($x, $y)"
                }
                "type_text" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: ""
                    val ok = svc.typeText(text)
                    if (ok) "Typed: $text" else "Type failed — no focused input field?"
                }
                "scroll" -> {
                    val dir = args["direction"]?.jsonPrimitive?.content ?: "down"
                    val ok = svc.scroll(dir)
                    if (ok) "Scrolled $dir" else "Scroll failed"
                }
                "press_back" -> {
                    svc.pressBack()
                    "Pressed Back"
                }
                "press_home" -> {
                    svc.pressHome()
                    "Pressed Home"
                }
                "open_app" -> {
                    val pkg = args["package_name"]?.jsonPrimitive?.content ?: ""
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: return "App not found: $pkg"
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Thread.sleep(1500)
                    "Launched $pkg"
                }
                else -> "Unknown action: $name"
            }
        } catch (e: Exception) {
            "Error in $name: ${e.message}"
        }
    }

    private fun buildAccessibilityError(): String {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Accessibility Service is not running. " +
            "Opening Settings → please enable Sayuri under Accessibility, then try again."
    }
}
