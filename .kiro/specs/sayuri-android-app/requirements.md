# Requirements Document

## Introduction

Sayuri is a voice-first personal AI assistant Android app. The app operates in a two-phase listening model: it passively monitors audio for the wake word "Sayuri", and only after detecting the wake word does it activate full speech recognition, send the transcript to Google's Gemini Flash model, and read the response aloud. Interaction is entirely hands-free and seamless over Bluetooth headsets or the device speaker. The UI is intentionally minimal: a single dark-themed screen. The mic button serves as a mute/pause toggle rather than a trigger. The Gemini API key is injected at build time via `BuildConfig`.

Core interaction loop: **[Wake word detection] → "Sayuri" detected → Speak → Transcribe → Send → Receive → Speak back → [Resume wake word detection]**.

## Glossary

- **App**: The Sayuri Android application
- **System**: The Sayuri Android application and all its components collectively
- **MainActivity**: The single-screen entry point Activity that renders the UI and observes ViewModel state
- **VoiceAssistantViewModel**: The central orchestrator ViewModel that manages the full interaction lifecycle and exposes UI state
- **SpeechRecognizerWrapper**: The component that wraps Android's `SpeechRecognizer` with a coroutine bridge for always-on speech recognition
- **TtsWrapper**: The component that wraps Android's `TextToSpeech` engine with coroutine support and Bluetooth audio routing
- **GeminiRepository**: The component that abstracts Gemini API communication and manages conversation history
- **GeminiApiClient**: The low-level HTTP client for the Gemini REST API
- **BluetoothAudioManager**: The component that manages Bluetooth SCO audio connections for headset input/output
- **ConversationManager**: The component that maintains in-memory conversation history for multi-turn context
- **AssistantState**: The sealed class representing the current state of the assistant (`Idle`, `WakeWordListening`, `ActiveListening`, `Processing`, `Speaking`, `Error`)
- **WakeWordDetector**: The component responsible for passively monitoring audio for the wake word "Sayuri" using Android's `SpeechRecognizer` in a low-power continuous loop
- **Wake_Word**: The trigger phrase "Sayuri" (case-insensitive) that activates full speech recognition
- **WakeWordListening**: The passive state in which the app monitors audio only for the Wake_Word, without sending any audio to Gemini
- **ActiveListening**: The active state entered after Wake_Word detection, in which the app performs full speech recognition to capture the user's command
- **System_Prompt**: The fixed persona instruction string: "You are Sayuri, a personal AI assistant. Be concise, smart, and be energetic. Address the user as 'Bintang' (Full Name), or 'tang'."
- **SCO**: Synchronous Connection-Oriented — the Bluetooth audio profile used for headset microphone and speaker routing
- **STT**: Speech-to-Text — the Android `SpeechRecognizer` service
- **TTS**: Text-to-Speech — the Android `TextToSpeech` engine
- **Gemini_API**: Google's Gemini Flash REST API at `generativelanguage.googleapis.com`
- **BuildConfig**: The Android build-generated class that exposes compile-time constants including `GEMINI_API_KEY`
- **ConversationTurn**: A single entry in conversation history with a role (`USER` or `ASSISTANT`) and text content
- **GeminiRequest**: The serializable data class representing a request payload to the Gemini_API
- **GeminiResponse**: The serializable data class representing a response payload from the Gemini_API

---

## Requirements

### Requirement 1: Wake Word Detection and Core Interaction Loop

**User Story:** As a user, I want the app to only activate when I say "Sayuri", so that background noise and unintended speech are never sent to the AI.

#### Acceptance Criteria

1. WHEN the App enters the foreground and the `RECORD_AUDIO` permission is granted, THE VoiceAssistantViewModel SHALL automatically start the wake word detection phase without any user interaction, transitioning to the `WakeWordListening` state.
2. WHILE the App is in the `WakeWordListening` state, THE WakeWordDetector SHALL continuously monitor audio for the Wake_Word "Sayuri" (case-insensitive) without sending any audio to the Gemini_API.
3. WHEN the WakeWordDetector detects the Wake_Word in the audio stream, THE VoiceAssistantViewModel SHALL transition from `WakeWordListening` to `ActiveListening` and start a full speech recognition session to capture the user's command.
4. WHEN the SpeechRecognizerWrapper returns a successful transcript during `ActiveListening`, THE VoiceAssistantViewModel SHALL transition the state from `ActiveListening` to `Processing`.
5. WHEN the VoiceAssistantViewModel completes the `Speaking` state, THE VoiceAssistantViewModel SHALL automatically transition back to the `WakeWordListening` state and resume wake word detection.
6. WHEN the VoiceAssistantViewModel is in the `Error` state, THE VoiceAssistantViewModel SHALL automatically attempt to restart wake word detection after a recovery delay of no more than 1 second.
7. THE VoiceAssistantViewModel SHALL enforce the state transition sequence: `Idle` → `WakeWordListening` → `ActiveListening` → `Processing` → `Speaking` → `WakeWordListening`, with `Error` states recoverable back to `WakeWordListening`.
8. WHEN the VoiceAssistantViewModel transitions to `ActiveListening`, THE WakeWordDetector SHALL be stopped before the full SpeechRecognizerWrapper session begins, so that both are never active simultaneously.
9. WHEN the Wake_Word is detected but the transcript captured during `ActiveListening` contains only the Wake_Word and no additional content, THE VoiceAssistantViewModel SHALL NOT send the transcript to the Gemini_API and SHALL return to `WakeWordListening`.

---

### Requirement 1a: Wake Word Detection Implementation

**User Story:** As a developer, I want the wake word detection to use Android's built-in SpeechRecognizer in a lightweight loop, so that no third-party wake word SDK is required.

#### Acceptance Criteria

1. THE WakeWordDetector SHALL use Android's `SpeechRecognizer` with `LANGUAGE_MODEL_FREE_FORM` and a short recognition timeout to continuously poll for the Wake_Word.
2. WHEN a recognition result is returned during wake word polling, THE WakeWordDetector SHALL check whether any result string contains the substring "sayuri" (case-insensitive) and return `WakeWordResult.Detected` if found, or `WakeWordResult.NotDetected` otherwise.
3. WHEN `WakeWordResult.NotDetected` is returned, THE VoiceAssistantViewModel SHALL immediately restart the wake word detection loop without transitioning out of `WakeWordListening`.
4. WHEN `WakeWordResult.Detected` is returned, THE VoiceAssistantViewModel SHALL transition to `ActiveListening` and start a new full SpeechRecognizerWrapper session to capture the complete user command.
5. THE WakeWordDetector SHALL expose a `suspend fun detect(): WakeWordResult` function that bridges `RecognitionListener` callbacks to a `suspendCancellableCoroutine`.
6. WHEN the WakeWordDetector coroutine is cancelled, THE WakeWordDetector SHALL call `recognizer.cancel()` to release the underlying Android resource.

---

### Requirement 2: Mic Button as Mute/Pause Toggle

**User Story:** As a user, I want a mic button on screen that lets me mute or pause the app entirely, so that I can prevent it from listening for the wake word or processing speech during private moments.

#### Acceptance Criteria

1. THE MainActivity SHALL display a mic button that acts as a mute/pause toggle for the entire listening pipeline (both wake word detection and active listening).
2. WHEN the user presses the mic button while the App is in the `WakeWordListening` state, THE VoiceAssistantViewModel SHALL pause wake word detection and transition to the `Idle` state.
3. WHEN the user presses the mic button while the App is in the `ActiveListening` state, THE VoiceAssistantViewModel SHALL cancel the active speech recognition session and transition to the `Idle` state.
4. WHEN the user presses the mic button while the App is in the `Idle` state (muted), THE VoiceAssistantViewModel SHALL resume wake word detection and transition to the `WakeWordListening` state.
5. WHEN the user presses the mic button while the App is in the `Speaking` state, THE VoiceAssistantViewModel SHALL stop TTS playback and transition to the `Idle` state.
6. WHEN the user presses the mic button while the App is in the `Processing` state, THE VoiceAssistantViewModel SHALL ignore the press and remain in the `Processing` state.
7. THE MainActivity SHALL update the mic button's visual appearance to reflect whether the App is currently active (WakeWordListening, ActiveListening, Processing, or Speaking) or muted/paused (Idle).
8. THE MainActivity SHALL display a status text label that reflects the current AssistantState to the user using the following labels: `Idle` → "Paused", `WakeWordListening` → "Listening for 'Sayuri'…", `ActiveListening` → "Listening…", `Processing` → "Thinking…", `Speaking` → "Speaking…", `Error` → the error message string.

---

### Requirement 3: Active Speech Recognition

**User Story:** As a user, I want the app to accurately transcribe what I say after I call Sayuri's name, so that my command is correctly captured and sent to the AI.

#### Acceptance Criteria

1. WHEN the VoiceAssistantViewModel transitions to the `ActiveListening` state, THE SpeechRecognizerWrapper SHALL start a new `SpeechRecognizer` session using `LANGUAGE_MODEL_FREE_FORM` to capture the user's full command.
2. WHEN Android's `SpeechRecognizer` returns multiple recognition results with varying confidence scores during `ActiveListening`, THE SpeechRecognizerWrapper SHALL select and return the result with the highest confidence score.
3. WHEN Android's `SpeechRecognizer` returns a recognition result during `ActiveListening`, THE SpeechRecognizerWrapper SHALL only return a `SpeechResult.Success` if the transcript string is non-blank after stripping the Wake_Word prefix (if present at the start of the transcript).
4. WHEN Android's `SpeechRecognizer` returns `ERROR_NO_MATCH` or `ERROR_SPEECH_TIMEOUT` during `ActiveListening`, THE SpeechRecognizerWrapper SHALL return a `SpeechResult.Error` with the corresponding error code.
5. WHEN the SpeechRecognizerWrapper coroutine is cancelled, THE SpeechRecognizerWrapper SHALL call `recognizer.cancel()` to release the underlying Android resource.
6. IF the `RECORD_AUDIO` permission is not granted, THEN THE SpeechRecognizerWrapper SHALL NOT attempt to start a listening session and SHALL return `SpeechResult.Error` with a permission-denied error code.

---

### Requirement 4: Gemini API Integration

**User Story:** As a user, I want my transcribed speech to be sent to Gemini and receive an intelligent response, so that I can have a meaningful conversation with Sayuri.

#### Acceptance Criteria

1. WHEN the VoiceAssistantViewModel receives a non-blank transcript, THE GeminiRepository SHALL send the transcript to the Gemini_API along with the full conversation history and the System_Prompt.
2. THE GeminiRepository SHALL prepend the System_Prompt as the `systemInstruction` field in every GeminiRequest sent to the Gemini_API.
3. WHEN GeminiRepository.sendMessage() is called with a non-blank user text, THE GeminiRepository SHALL append the user text to the ConversationManager history before making the API call.
4. WHEN the Gemini_API returns a successful response, THE GeminiRepository SHALL append the assistant response text to the ConversationManager history with role `ASSISTANT`.
5. IF the Gemini_API call fails for any reason, THEN THE GeminiRepository SHALL NOT modify the ConversationManager history — the user message appended before the call SHALL be rolled back.
6. WHEN the Gemini_API returns an HTTP status code in the 4xx or 5xx range, THE GeminiApiClient SHALL throw an `ApiException` whose `code` field equals the HTTP status code.
7. WHEN the Gemini_API returns an HTTP status code in the 2xx range, THE GeminiApiClient SHALL deserialize the response body into a `GeminiResponse` object.
8. THE GeminiApiClient SHALL serialize GeminiRequest objects to JSON and deserialize GeminiResponse objects from JSON using `kotlinx.serialization`.
9. THE GeminiRepository SHALL construct every GeminiRequest such that the last entry in the `contents` list always has role `"user"`.
10. WHEN the Gemini_API returns a response where `candidates` is empty, THE GeminiRepository SHALL return `Result.failure` with the message "No response from Gemini".
11. WHEN the Gemini_API returns a response where `finishReason` is `"SAFETY"`, THE GeminiRepository SHALL return `Result.failure` with the message "Response blocked by safety filter".
12. WHEN the Gemini_API returns a response where the response text is blank, THE GeminiRepository SHALL return `Result.failure` with the message "Blank response text".

---

### Requirement 5: Text-to-Speech Output

**User Story:** As a user, I want the app to read Gemini's response aloud, so that I can receive answers without looking at the screen.

#### Acceptance Criteria

1. WHEN the VoiceAssistantViewModel receives a successful Gemini response, THE TtsWrapper SHALL speak the response text aloud using Android's `TextToSpeech` engine.
2. THE TtsWrapper SHALL initialize the `TextToSpeech` engine with `Locale.US` as the language.
3. WHEN the TtsWrapper coroutine is cancelled, THE TtsWrapper SHALL call `tts.stop()` to halt audio playback immediately.
4. WHEN the `TextToSpeech` engine completes an utterance, THE TtsWrapper SHALL return `TtsResult.Done`.
5. WHEN the `TextToSpeech` engine reports a failure, THE TtsWrapper SHALL return `TtsResult.Error` with a descriptive message.
6. WHEN the response text exceeds 500 characters, THE TtsWrapper SHALL split the text into multiple chunks on sentence boundaries such that no individual chunk passed to the `TextToSpeech` engine exceeds 500 characters.
7. WHEN TtsWrapper.speak() is called, THE TtsWrapper SHALL route audio to Bluetooth SCO if a SCO connection is available, otherwise route audio to the device speaker.

---

### Requirement 6: Bluetooth Audio Support

**User Story:** As a user, I want the app to work seamlessly with my Bluetooth headset for both microphone input and audio output, so that I can use Sayuri hands-free with my headset.

#### Acceptance Criteria

1. THE BluetoothAudioManager SHALL register a `BroadcastReceiver` for `ACTION_SCO_AUDIO_STATE_UPDATED` to monitor Bluetooth SCO connection state changes.
2. WHEN the App starts a listening or speaking session, THE BluetoothAudioManager SHALL attempt to start a Bluetooth SCO connection.
3. WHEN the Bluetooth SCO connection is established, THE BluetoothAudioManager SHALL expose `isBluetoothScoAvailable = true` via its `StateFlow`.
4. WHEN the Bluetooth SCO connection does not establish within 3 seconds, THE BluetoothAudioManager SHALL emit `isBluetoothScoAvailable = false` and the App SHALL fall back to the device speaker and microphone transparently.
5. WHEN the App finishes a listening or speaking session, THE BluetoothAudioManager SHALL stop the Bluetooth SCO connection.
6. WHEN the App is destroyed, THE BluetoothAudioManager SHALL release all Bluetooth resources and unregister the `BroadcastReceiver`.
7. WHERE the `BLUETOOTH_CONNECT` permission is required (Android API 31+), THE App SHALL request the `BLUETOOTH_CONNECT` permission at runtime before attempting Bluetooth SCO operations.

---

### Requirement 7: Conversation History Management

**User Story:** As a user, I want the app to remember the context of our conversation across multiple turns, so that Sayuri can give contextually relevant responses.

#### Acceptance Criteria

1. THE ConversationManager SHALL maintain an in-memory list of ConversationTurn entries representing the conversation history.
2. WHEN ConversationManager.addUserMessage() is called with any string, THE ConversationManager SHALL append a ConversationTurn with `role = USER` and the provided text to the history.
3. WHEN ConversationManager.addAssistantMessage() is called with any string, THE ConversationManager SHALL append a ConversationTurn with `role = ASSISTANT` and the provided text to the history.
4. THE ConversationManager SHALL cap the conversation history at 10 turns (20 messages total — 10 user and 10 assistant) to bound token usage.
5. WHEN ConversationManager.clear() is called, THE ConversationManager SHALL remove all entries from the history such that `getHistory()` returns an empty list.
6. THE GeminiRepository SHALL include the full conversation history from ConversationManager in every GeminiRequest sent to the Gemini_API.

---

### Requirement 8: UI and State Rendering

**User Story:** As a user, I want a clean, minimal dark-themed interface that clearly shows what the app is doing, so that I can understand the current state at a glance.

#### Acceptance Criteria

1. THE MainActivity SHALL render a single dark-themed screen as the sole UI surface of the App.
2. THE MainActivity SHALL observe the `AssistantState` StateFlow from VoiceAssistantViewModel and update the UI on every state change.
3. WHEN the AssistantState is `WakeWordListening`, THE MainActivity SHALL display the status text "Listening for 'Sayuri'…" and a subtle passive visual indicator (e.g., dim pulse animation).
4. WHEN the AssistantState is `ActiveListening`, THE MainActivity SHALL display the status text "Listening…" and a prominent active visual indicator (e.g., bright pulse animation) distinct from the WakeWordListening indicator.
5. WHEN the AssistantState is `Processing`, THE MainActivity SHALL display the status text "Thinking…" and a processing visual indicator.
6. WHEN the AssistantState is `Speaking`, THE MainActivity SHALL display the status text "Speaking…" and a speaking visual indicator.
7. WHEN the AssistantState is `Error`, THE MainActivity SHALL display the error message string to the user.
8. WHEN the AssistantState is `Idle` (muted/paused), THE MainActivity SHALL display the status text "Paused" and a visually distinct inactive indicator.

---

### Requirement 9: Runtime Permissions

**User Story:** As a user, I want the app to request only the permissions it needs and explain why, so that I can make an informed decision about granting access.

#### Acceptance Criteria

1. THE MainActivity SHALL request the `RECORD_AUDIO` permission at runtime before starting any speech recognition session.
2. THE MainActivity SHALL request the `BLUETOOTH_CONNECT` permission at runtime on devices running Android API 31 or higher before performing Bluetooth operations.
3. IF the `RECORD_AUDIO` permission is denied, THEN THE MainActivity SHALL display a permission rationale dialog explaining why the permission is needed and disable the mic button.
4. IF the `RECORD_AUDIO` permission is granted, THEN THE MainActivity SHALL enable the mic button and allow the always-on listening to start automatically.
5. THE App SHALL declare only the following permissions in its manifest: `RECORD_AUDIO`, `INTERNET`, `BLUETOOTH`, and `BLUETOOTH_CONNECT`.

---

### Requirement 10: Error Handling and Recovery

**User Story:** As a user, I want the app to handle errors gracefully and recover automatically, so that I don't need to manually restart the app when something goes wrong.

#### Acceptance Criteria

1. WHEN the SpeechRecognizerWrapper returns `SpeechResult.Error` with `ERROR_NO_MATCH` or `ERROR_SPEECH_TIMEOUT` during `ActiveListening`, THE VoiceAssistantViewModel SHALL transition to `AssistantState.Error` with the message "Couldn't hear you. Try again." and then automatically restart wake word detection.
2. WHEN the GeminiRepository returns `Result.failure` due to a network `IOException`, THE VoiceAssistantViewModel SHALL transition to `AssistantState.Error` with the message "No internet connection" and then automatically restart wake word detection.
3. WHEN the GeminiRepository returns `Result.failure` due to an `ApiException` (HTTP 4xx/5xx), THE VoiceAssistantViewModel SHALL transition to `AssistantState.Error` with the message "Service error. Try again." and then automatically restart wake word detection.
4. WHEN TtsWrapper.initialize() returns `false`, THE VoiceAssistantViewModel SHALL log a warning and transition to `AssistantState.Error`, and THE App SHALL retry TTS initialization on the next `onResume` lifecycle event.
5. WHEN any unexpected exception is thrown within a `viewModelScope` coroutine, THE VoiceAssistantViewModel SHALL catch the exception and transition to `AssistantState.Error` without propagating an uncaught exception that would crash the App.
6. WHEN the VoiceAssistantViewModel transitions to `AssistantState.Error`, THE VoiceAssistantViewModel SHALL automatically attempt to restart wake word detection after a recovery delay of no more than 1 second.

---

### Requirement 11: API Key and Build Configuration

**User Story:** As a developer, I want the Gemini API key to be injected at build time and never stored in source code, so that secrets are kept out of version control.

#### Acceptance Criteria

1. THE App SHALL read the Gemini API key exclusively from `BuildConfig.GEMINI_API_KEY`, which is injected at compile time from `local.properties`.
2. THE GeminiApiClient SHALL include the API key as a URL query parameter (`?key=`) in every request to the Gemini_API, never as an HTTP header or in the request body.
3. THE App SHALL never log the value of `BuildConfig.GEMINI_API_KEY` to Logcat or any other output.
4. THE `local.properties` file containing the API key SHALL be excluded from version control via `.gitignore`.

---

### Requirement 12: Network Security and Data Privacy

**User Story:** As a user, I want my voice data and conversations to be handled securely and privately, so that my personal information is not exposed or persisted without my knowledge.

#### Acceptance Criteria

1. THE GeminiApiClient SHALL communicate with the Gemini_API exclusively over HTTPS.
2. THE App SHALL include a `network_security_config.xml` that disables cleartext (HTTP) traffic.
3. THE App SHALL NOT write any conversation transcripts, user speech, or Gemini responses to persistent storage (disk, SharedPreferences, or database).
4. THE ConversationManager SHALL store conversation history in memory only, and all history SHALL be discarded when the App process is terminated.
5. THE App SHALL NOT request location, contacts, camera, or storage permissions.

---

### Requirement 13: Performance

**User Story:** As a user, I want the app to respond quickly so that conversations feel natural and fluid.

#### Acceptance Criteria

1. THE System SHALL target a total latency of less than 3 seconds from the end of the user's speech to the start of TTS playback, under normal network conditions.
2. THE GeminiApiClient SHALL use a single shared `OkHttpClient` instance across the App lifetime to benefit from connection pooling and avoid repeated TCP handshake overhead.
3. THE GeminiRepository SHALL cap the conversation history at 10 turns to bound the size of each GeminiRequest and limit token usage.
4. WHEN a Gemini response exceeds 500 characters, THE TtsWrapper SHALL begin speaking the first chunk before all chunks are processed to reduce time-to-first-audio.
5. THE GeminiApiClient SHALL use `Dispatchers.IO` for all HTTP operations to avoid blocking the main thread.
6. THE SpeechRecognizerWrapper and TtsWrapper SHALL use `Dispatchers.Main` for all Android audio API calls that require the main thread.

---

### Requirement 14: Lifecycle Management

**User Story:** As a developer, I want all audio and Bluetooth resources to be properly acquired and released with the Android lifecycle, so that the app does not leak resources or interfere with other apps.

#### Acceptance Criteria

1. WHEN the MainActivity is destroyed, THE VoiceAssistantViewModel SHALL call `onDestroy()` to release WakeWordDetector, SpeechRecognizerWrapper, TtsWrapper, and BluetoothAudioManager resources.
2. WHEN the SpeechRecognizerWrapper is destroyed, THE SpeechRecognizerWrapper SHALL call `recognizer.destroy()` to release the underlying Android `SpeechRecognizer`.
3. WHEN the TtsWrapper is shut down, THE TtsWrapper SHALL call `tts.shutdown()` to release the underlying Android `TextToSpeech` engine.
4. WHEN the App moves to the background (onPause), THE VoiceAssistantViewModel SHALL pause both wake word detection and any active listening session to conserve resources and respect user privacy.
5. WHEN the App returns to the foreground (onResume) and the `RECORD_AUDIO` permission is granted, THE VoiceAssistantViewModel SHALL resume wake word detection automatically, transitioning to `WakeWordListening`.
6. WHEN the BluetoothAudioManager is released, THE BluetoothAudioManager SHALL unregister its `BroadcastReceiver` and stop any active SCO connection.
