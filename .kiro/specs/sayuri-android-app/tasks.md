# Implementation Plan: Sayuri Android App

## Overview

Implement the Sayuri voice-first AI assistant Android app in Kotlin using MVVM architecture. The implementation follows a bottom-up approach: data models and build configuration first, then the audio pipeline (WakeWordDetector, SpeechRecognizerWrapper, TtsWrapper, BluetoothAudioManager), then the API layer (GeminiApiClient, GeminiRepository, ConversationManager), then the ViewModel orchestrator, and finally the UI layer (MainActivity). Each phase is independently testable before the next begins.

---

## Tasks

- [x] 1. Project setup, build configuration, and data models
  - [x] 1.1 Configure build.gradle.kts with all required dependencies and BuildConfig field
    - Add `kotlinx-serialization-json`, `okhttp`, `mockwebserver`, `mockk`, `kotest-runner-junit5`, `kotest-property`, `lifecycle-viewmodel-ktx`, `kotlinx-coroutines-android` at pinned versions from the design dependency table
    - Apply `kotlin("plugin.serialization")` in the plugins block
    - Add `buildConfigField("String", "GEMINI_API_KEY", ...)` reading from `local.properties` via `project.findProperty`
    - Enable `buildConfig = true` in the `buildFeatures` block
    - Configure JUnit 5 test options (`useJUnitPlatform()`)
    - _Requirements: 11.1, 13.2_

  - [x] 1.2 Create AndroidManifest.xml with required permissions and network security config
    - Declare `RECORD_AUDIO`, `INTERNET`, `BLUETOOTH`, `BLUETOOTH_CONNECT` permissions (no others)
    - Reference `@xml/network_security_config` in the `<application>` tag
    - Create `res/xml/network_security_config.xml` that disables cleartext traffic
    - _Requirements: 9.5, 12.1, 12.2_

  - [x] 1.3 Define all sealed classes, data models, and enums
    - Create `AssistantState` sealed class: `Idle`, `WakeWordListening`, `ActiveListening`, `Processing`, `Speaking(text: String)`, `Error(message: String)`
    - Create `WakeWordResult` sealed class: `Detected`, `NotDetected`, `Error(code: Int, message: String)`
    - Create `SpeechResult` sealed class: `Success(transcript: String)`, `Error(code: Int, message: String)`
    - Create `TtsResult` sealed class: `Done`, `Error(message: String)`
    - Create `ConversationTurn` data class and `Role` enum (`USER`, `ASSISTANT`)
    - Create `@Serializable` data classes: `GeminiRequest`, `GeminiContent`, `GeminiPart`, `GeminiSystemInstruction`, `GenerationConfig`, `GeminiResponse`, `GeminiCandidate`
    - Create `ApiException(code: Int, message: String)` exception class
    - _Requirements: 1.7, 4.8_

  - [x] 1.4 Write property test for GeminiRequest serialization round-trip
    - **Property 12: GeminiRequest Serialization Round-Trip**
    - **Validates: Requirements 4.8**
    - Use `kotest-property` `forAll` with arbitrary `GeminiRequest` instances (generated via `Arb`)
    - Assert that `Json.decodeFromString<GeminiRequest>(Json.encodeToString(request)) == request`

  - [x] 1.5 Write property test for GeminiResponse serialization round-trip
    - **Property 13: GeminiResponse Serialization Round-Trip**
    - **Validates: Requirements 4.8**
    - Use `kotest-property` `forAll` with arbitrary `GeminiResponse` instances
    - Assert that `Json.decodeFromString<GeminiResponse>(Json.encodeToString(response)) == response`

- [x] 2. ConversationManager implementation
  - [x] 2.1 Implement ConversationManager with history cap and clear
    - Maintain an in-memory `MutableList<ConversationTurn>`
    - `addUserMessage(text)` appends `ConversationTurn(Role.USER, text)`
    - `addAssistantMessage(text)` appends `ConversationTurn(Role.ASSISTANT, text)`
    - `getHistory()` returns an immutable copy
    - `clear()` removes all entries
    - Enforce a cap of 10 turns (20 messages): evict the oldest pair when the cap is exceeded
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x] 2.2 Write property test for ConversationManager role assignment
    - **Property 14: ConversationManager Role Assignment**
    - **Validates: Requirements 7.2, 7.3**
    - Use `kotest-property` `forAll` with arbitrary sequences of `addUserMessage` / `addAssistantMessage` calls
    - Assert every user-added entry has `role == USER` and every assistant-added entry has `role == ASSISTANT`

  - [x] 2.3 Write property test for ConversationManager history cap
    - **Property 15: ConversationManager History Cap**
    - **Validates: Requirements 7.4, 13.3**
    - Use `kotest-property` `forAll` with `n` in `11..100` messages added
    - Assert `getHistory().size <= 10` after any number of additions

  - [x] 2.4 Write property test for ConversationManager clear idempotence
    - **Property 16: ConversationManager Clear Idempotence**
    - **Validates: Requirements 7.5**
    - Use `kotest-property` `forAll` with arbitrary pre-populated histories
    - Assert `getHistory().isEmpty() == true` after calling `clear()`

- [x] 3. GeminiApiClient implementation
  - [x] 3.1 Implement GeminiApiClient with OkHttp and kotlinx.serialization
    - Create a single shared `OkHttpClient` instance (companion object or singleton)
    - Construct the Gemini Flash endpoint URL with `?key=$apiKey` query parameter
    - Serialize `GeminiRequest` to JSON via `Json.encodeToString` and POST with `application/json` content type
    - Deserialize response body via `Json.decodeFromString<GeminiResponse>`
    - Throw `ApiException(response.code, response.message)` for any non-2xx HTTP status
    - Run all HTTP operations on `Dispatchers.IO`
    - Never log the API key value
    - _Requirements: 4.6, 4.7, 4.8, 11.2, 11.3, 12.1, 13.2, 13.5_

  - [x] 3.2 Write integration test for GeminiApiClient using MockWebServer
    - Use `MockWebServer` to simulate 200 OK, 400, 401, 500 responses
    - Assert correct URL construction (endpoint path + `?key=` parameter present)
    - Assert `GeminiResponse` is correctly deserialized on 2xx
    - Assert `ApiException.code` equals the HTTP status code on 4xx/5xx
    - **Property 11: API Error Code Propagation**
    - **Validates: Requirements 4.6, 4.7**

- [x] 4. GeminiRepository implementation
  - [x] 4.1 Implement GeminiRepositoryImpl with system prompt injection and history management
    - Define `SYSTEM_PROMPT` constant: `"You are Sayuri, a personal AI assistant. Be concise, smart, and be energetic. Address the user as 'Bintang' (Full Name), or 'tang'."`
    - `sendMessage(userText)`:
      1. Append `userText` to `ConversationManager` as a user message
      2. Build `GeminiRequest` from full history + system prompt (last content role must be `"user"`)
      3. Call `apiClient.generateContent(request)`
      4. On success: extract response text, append to history as `ASSISTANT`, return `Result.success(text)`
      5. On failure: roll back the user message appended in step 1, return `Result.failure(exception)`
    - Implement `extractResponseText(response)`: handle empty candidates, `finishReason == "SAFETY"`, empty parts, blank text
    - `clearHistory()` delegates to `ConversationManager.clear()`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.9, 4.10, 4.11, 4.12, 7.6_

  - [x] 4.2 Write property test for GeminiRequest last-role invariant
    - **Property 10: GeminiRequest Last Role Invariant**
    - **Validates: Requirements 4.9**
    - Use `kotest-property` `forAll` with arbitrary conversation histories and non-blank user texts
    - Assert `request.contents.last().role == "user"` for every generated request

  - [x] 4.3 Write property test for system prompt always present
    - **Property 7: System Prompt Always Present**
    - **Validates: Requirements 4.1, 4.2**
    - Use `kotest-property` `forAll` with arbitrary non-blank user texts and histories
    - Assert `request.systemInstruction != null` and contains the system prompt text

  - [x] 4.4 Write property test for history consistency after success
    - **Property 8: History Consistency After Success**
    - **Validates: Requirements 4.4**
    - Use `kotest-property` `forAll` with arbitrary successful API responses
    - Assert `conversationManager.getHistory().last().role == ASSISTANT` after a successful `sendMessage`

  - [x] 4.5 Write property test for history rollback after failure
    - **Property 9: History Rollback After Failure**
    - **Validates: Requirements 4.5**
    - Use `kotest-property` `forAll` with arbitrary histories and simulated API failures
    - Assert `conversationManager.getHistory().size` is unchanged from before the failed `sendMessage` call

- [x] 5. Checkpoint — Core data and API layer
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. WakeWordDetector implementation
  - [x] 6.1 Implement WakeWordDetector with SpeechRecognizer polling loop
    - Build `RecognizerIntent` with `LANGUAGE_MODEL_FREE_FORM` and a short recognition timeout
    - Bridge `RecognitionListener` callbacks to `suspendCancellableCoroutine`
    - In `onResults`: check all result strings for substring `"sayuri"` (case-insensitive); return `WakeWordResult.Detected` if found, `WakeWordResult.NotDetected` otherwise
    - In `onError`: return `WakeWordResult.Error(code, message)`
    - `cancel()` calls `recognizer.cancel()`; `destroy()` calls `recognizer.destroy()`
    - Register cancellation handler in `suspendCancellableCoroutine` to call `recognizer.cancel()` on coroutine cancellation
    - _Requirements: 1a.1, 1a.2, 1a.5, 1a.6_

  - [x] 6.2 Write unit tests for WakeWordDetector result classification
    - Mock `RecognitionListener` callbacks using MockK
    - Test: result containing "sayuri" → `WakeWordResult.Detected`
    - Test: result containing "SAYURI" (uppercase) → `WakeWordResult.Detected`
    - Test: result with no wake word → `WakeWordResult.NotDetected`
    - Test: `onError` callback → `WakeWordResult.Error`
    - _Requirements: 1a.2_

- [x] 7. SpeechRecognizerWrapper implementation
  - [x] 7.1 Implement SpeechRecognizerWrapper with highest-confidence selection and wake word stripping
    - Build `RecognizerIntent` with `LANGUAGE_MODEL_FREE_FORM`, partial results disabled
    - Bridge `RecognitionListener.onResults` to `suspendCancellableCoroutine`
    - Select the result with the highest confidence score from `SpeechRecognizer.RESULTS_RECOGNITION` + `CONFIDENCE_SCORES`
    - Strip wake word prefix ("sayuri" / "Sayuri") from the start of the transcript if present
    - Return `SpeechResult.Success(transcript)` only if the stripped transcript is non-blank
    - Return `SpeechResult.Error` for `ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT`, and permission-denied cases
    - `cancel()` calls `recognizer.cancel()`; `destroy()` calls `recognizer.destroy()`
    - Run on `Dispatchers.Main`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 13.6_

  - [x] 7.2 Write property test for transcript non-blank invariant
    - **Property 4: Transcript Non-Blank Invariant**
    - **Validates: Requirements 3.3**
    - Use `kotest-property` `forAll` with arbitrary non-blank recognition result strings
    - Assert `SpeechResult.Success.transcript.isNotBlank()` for every returned success result

  - [x] 7.3 Write property test for highest-confidence transcript selection
    - **Property 5: Highest-Confidence Transcript Selection**
    - **Validates: Requirements 3.2**
    - Use `kotest-property` `forAll` with arbitrary lists of (transcript, confidence) pairs
    - Assert the returned transcript matches the entry with the maximum confidence score

- [x] 8. TtsWrapper implementation
  - [x] 8.1 Implement TtsWrapper with Bluetooth SCO routing and chunked speaking
    - `initialize()`: init `TextToSpeech` with `Locale.US`; bridge `OnInitListener` to a `CompletableDeferred<Boolean>`; return `true` on success, `false` on error
    - `speak(text)`:
      1. If `text.length > 500`, split on sentence boundaries so no chunk exceeds 500 characters
      2. Route audio to Bluetooth SCO if available, otherwise speaker
      3. For each chunk, call `tts.speak(chunk, QUEUE_ADD, null, utteranceId)` and bridge `UtteranceProgressListener.onDone` / `onError` to `suspendCancellableCoroutine`
      4. Return `TtsResult.Done` when all chunks complete; `TtsResult.Error` on any failure
    - `stop()` calls `tts.stop()`; `shutdown()` calls `tts.shutdown()`
    - Register cancellation handler to call `tts.stop()` on coroutine cancellation
    - Run on `Dispatchers.Main`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 13.4, 13.6_

  - [x] 8.2 Write property test for long text TTS splitting
    - **Property 17: Long Text TTS Splitting**
    - **Validates: Requirements 5.6**
    - Use `kotest-property` `forAll` with arbitrary strings longer than 500 characters
    - Assert every chunk produced by the splitting function has `length <= 500`
    - Assert the concatenation of all chunks equals the original text (no content lost)

- [x] 9. BluetoothAudioManager implementation
  - [x] 9.1 Implement BluetoothAudioManager with SCO lifecycle and StateFlow
    - `initialize()`: register `BroadcastReceiver` for `ACTION_SCO_AUDIO_STATE_UPDATED`
    - `startBluetoothSco()`: call `AudioManager.startBluetoothSco()`; set a 3-second timeout; if `SCO_AUDIO_STATE_CONNECTED` is not received within 3 seconds, emit `isBluetoothScoAvailable = false`
    - `stopBluetoothSco()`: call `AudioManager.stopBluetoothSco()`
    - `release()`: stop SCO, unregister `BroadcastReceiver`, reset `StateFlow`
    - Expose `isBluetoothScoAvailable: StateFlow<Boolean>`
    - Check for `BLUETOOTH_CONNECT` permission on API 31+ before SCO operations
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

  - [x] 9.2 Write unit tests for BluetoothAudioManager SCO state transitions
    - Mock `AudioManager` and `BroadcastReceiver` using MockK
    - Test: `SCO_AUDIO_STATE_CONNECTED` broadcast → `isBluetoothScoAvailable == true`
    - Test: 3-second timeout without connection → `isBluetoothScoAvailable == false`
    - Test: `release()` unregisters receiver and stops SCO
    - _Requirements: 6.3, 6.4, 6.6_

- [x] 10. Checkpoint — Audio pipeline and API layer complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. VoiceAssistantViewModel implementation
  - [x] 11.1 Implement VoiceAssistantViewModel state machine and wake word loop
    - Expose `val state: StateFlow<AssistantState>` (initial value: `Idle`)
    - Implement `startWakeWordLoop()` as a `viewModelScope.launch` coroutine:
      - Loop: set state to `WakeWordListening`, call `wakeWordDetector.detect()`
      - On `Detected`: cancel detector, call `startActiveListening()`; loop resumes after it returns
      - On `NotDetected`: immediately loop back
      - On `Error`: set error state, `delay(≤1000ms)`, loop back
    - Implement `startActiveListening()`:
      - Set state to `ActiveListening`, call `speechRecognizer.listen()`
      - On `Success`: strip wake word prefix; if blank, return; else set `Processing`, call `handleTranscript`
      - On `Error`: set error state, `delay(≤1000ms)`
    - Implement `handleTranscript(text)`:
      - Call `geminiRepository.sendMessage(text)`
      - On success: set `Speaking(response)`, call `tts.speak(response)`, then loop resumes
      - On failure: set `Error` with user-friendly message
    - Wrap the entire loop body in `try/catch(Exception)` → transition to `Error` without crashing
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1a.3, 1a.4, 10.1, 10.2, 10.3, 10.5, 10.6_

  - [x] 11.2 Implement VoiceAssistantViewModel.onMicPressed() toggle logic
    - `WakeWordListening` or `ActiveListening`: cancel detector + recognizer, set `Idle`, cancel `listeningJob`
    - `Speaking`: call `tts.stop()`, set `Idle`, cancel `listeningJob`
    - `Idle`: call `startWakeWordLoop()`
    - `Processing`: ignore (no-op)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [x] 11.3 Implement VoiceAssistantViewModel lifecycle hooks (onDestroy, onPause, onResume)
    - `onDestroy()`: cancel `listeningJob`, call `destroy()` on detector and recognizer, call `tts.shutdown()`, call `bluetoothAudioManager.release()`
    - `onPause()`: pause wake word detection and any active listening
    - `onResume()`: if `RECORD_AUDIO` is granted, call `startWakeWordLoop()`
    - Handle TTS initialization failure: log warning, set `Error` state, retry on next `onResume`
    - _Requirements: 10.4, 14.1, 14.2, 14.3, 14.4, 14.5_

  - [x] 11.4 Write property test for state machine completeness
    - **Property 1: State Machine Completeness**
    - **Validates: Requirements 1.7**
    - Use `kotest-property` `forAll` with arbitrary `AssistantState` values
    - Assert that `onMicPressed()` and each interaction outcome produce a defined next state (no unhandled branch)

  - [x] 11.5 Write property test for auto-restart after terminal state
    - **Property 2: Auto-Restart After Terminal State**
    - **Validates: Requirements 1.2, 1.5, 1.6**
    - Use `kotest-property` `forAll` with arbitrary terminal outcomes (TTS done, speech error, network error)
    - Assert the ViewModel always transitions back to `WakeWordListening` after each terminal outcome

  - [x] 11.6 Write property test for TTS and STT mutual exclusion
    - **Property 3: TTS and STT Mutual Exclusion**
    - **Validates: Requirements 1.8**
    - Use `kotest-property` `forAll` with arbitrary event sequences
    - Assert that at no point in the state sequence are both `TtsWrapper.speak` and `SpeechRecognizerWrapper.listen` active simultaneously

  - [x] 11.7 Write property test for LISTENING to PROCESSING transition
    - **Property 6: LISTENING to PROCESSING Transition**
    - **Validates: Requirements 1.4**
    - Use `kotest-property` `forAll` with arbitrary non-blank transcripts
    - Assert the state passes through `Processing` before `GeminiRepository.sendMessage` is invoked

  - [x] 11.8 Write property test for ViewModel exception safety
    - **Property 18: ViewModel Exception Safety**
    - **Validates: Requirements 10.5**
    - Use `kotest-property` `forAll` with arbitrary exceptions thrown inside `viewModelScope`
    - Assert the ViewModel transitions to `AssistantState.Error` and does not propagate an uncaught exception

  - [x] 11.9 Write unit tests for VoiceAssistantViewModel state transitions
    - Mock all dependencies with MockK
    - Test: permission granted on start → `WakeWordListening`
    - Test: wake word detected → `ActiveListening`
    - Test: transcript received → `Processing` → `Speaking`
    - Test: TTS done → `WakeWordListening`
    - Test: speech error → `Error` → `WakeWordListening` (after delay)
    - Test: network error → `Error` → `WakeWordListening`
    - Test: mic press in each state → correct next state
    - _Requirements: 1.1–1.9, 2.1–2.6, 10.1–10.6_

- [x] 12. Checkpoint — ViewModel complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 13. MainActivity UI implementation
  - [x] 13.1 Implement MainActivity layout with dark theme, mic button, and status text
    - Create a single dark-themed screen (background `#121212` or `colorSurface` from Material dark theme)
    - Add a centered mic button (FAB or `ImageButton`) with active/muted visual states
    - Add a status text label below the mic button
    - Add a subtle pulse animation for `WakeWordListening` (dim) and a bright pulse for `ActiveListening`
    - Add a processing spinner indicator for `Processing`
    - Add a speaking waveform or indicator for `Speaking`
    - _Requirements: 8.1, 8.3, 8.4, 8.5, 8.6, 8.8_

  - [x] 13.2 Implement MainActivity state observation and rendering
    - Observe `VoiceAssistantViewModel.state` via `lifecycleScope.launch { repeatOnLifecycle(STARTED) }`
    - Call `renderState(state)` on every emission
    - `renderState`: map each `AssistantState` to the correct status text label and visual indicator per Requirement 8
    - Bind mic button click to `viewModel.onMicPressed()`
    - Update mic button appearance: active icon when in `WakeWordListening`/`ActiveListening`/`Processing`/`Speaking`; muted icon when `Idle`
    - _Requirements: 2.7, 2.8, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8_

  - [x] 13.3 Implement runtime permission handling in MainActivity
    - Request `RECORD_AUDIO` on `onCreate` / `onResume` using `ActivityCompat.requestPermissions`
    - Request `BLUETOOTH_CONNECT` on API 31+ before Bluetooth operations
    - In `onRequestPermissionsResult`: if `RECORD_AUDIO` denied, show rationale dialog and disable mic button; if granted, enable mic button and call `viewModel.onResume()`
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [x] 13.4 Wire VoiceAssistantViewModel lifecycle to MainActivity lifecycle callbacks
    - Call `viewModel.onResume()` in `onResume`
    - Call `viewModel.onPause()` in `onPause`
    - Call `viewModel.onDestroy()` in `onDestroy`
    - Instantiate ViewModel via `ViewModelProvider` with a factory that injects all dependencies
    - _Requirements: 14.1, 14.4, 14.5_

- [x] 14. Final checkpoint — Full integration
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP delivery
- Each task references specific requirements for traceability
- Checkpoints at tasks 5, 10, 12, and 14 ensure incremental validation at each architectural layer
- Property tests use `kotest-property` `forAll` / `checkAll` with `Arb` generators; unit tests use JUnit 5 + MockK
- `SpeechRecognizerWrapper` and `TtsWrapper` must run on `Dispatchers.Main`; `GeminiApiClient` must run on `Dispatchers.IO`
- The `GEMINI_API_KEY` value must never appear in logs, test output, or source code — reference it only via `BuildConfig.GEMINI_API_KEY`
- `local.properties` must be listed in `.gitignore` before the first commit

---

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["1.4", "1.5", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "2.4", "3.1"] },
    { "id": 3, "tasks": ["3.2", "4.1"] },
    { "id": 4, "tasks": ["4.2", "4.3", "4.4", "4.5", "6.1", "8.1", "9.1"] },
    { "id": 5, "tasks": ["6.2", "7.1", "8.2", "9.2"] },
    { "id": 6, "tasks": ["7.2", "7.3", "11.1"] },
    { "id": 7, "tasks": ["11.2", "11.3"] },
    { "id": 8, "tasks": ["11.4", "11.5", "11.6", "11.7", "11.8", "11.9"] },
    { "id": 9, "tasks": ["13.1"] },
    { "id": 10, "tasks": ["13.2", "13.3"] },
    { "id": 11, "tasks": ["13.4"] }
  ]
}
```
