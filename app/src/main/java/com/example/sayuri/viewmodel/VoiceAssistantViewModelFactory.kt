package com.example.sayuri.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.sayuri.audio.BluetoothAudioManager
import com.example.sayuri.audio.SpeechRecognizerWrapper
import com.example.sayuri.audio.TtsWrapper
import com.example.sayuri.audio.WakeWordDetector
import com.example.sayuri.data.GeminiApiClient
import com.example.sayuri.data.GeminiRepositoryImpl
import com.example.sayuri.domain.FunctionToolkit
import com.example.sayuri.domain.ConversationManager

/**
 * [ViewModelProvider.Factory] that constructs a [VoiceAssistantViewModel] with all
 * required dependencies injected.
 *
 * Dependencies created here:
 * - [WakeWordDetector] — passive wake word polling via Android SpeechRecognizer
 * - [SpeechRecognizerWrapper] — active speech recognition coroutine bridge
 * - [TtsWrapper] — Text-to-Speech coroutine bridge
 * - [GeminiRepositoryImpl] — Gemini API communication + conversation history
 * - [BluetoothAudioManager] — Bluetooth SCO lifecycle management
 *
 * Requirements: 14.1, 14.4, 14.5
 */
class VoiceAssistantViewModelFactory(private val context: Context) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VoiceAssistantViewModel::class.java)) {
            val tts = TtsWrapper(context)
            val viewModel = VoiceAssistantViewModel(
                wakeWordDetector = WakeWordDetector(context),
                speechRecognizer = SpeechRecognizerWrapper(context),
                tts = tts,
                geminiRepository = GeminiRepositoryImpl(
                    apiClient = GeminiApiClient(),
                    conversationManager = ConversationManager(),
                    functionToolkit = FunctionToolkit(context)
                ),
                bluetoothAudioManager = BluetoothAudioManager(context)
            )
            return viewModel as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
