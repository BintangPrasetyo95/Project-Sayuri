package com.example.sayuri.ui

import android.animation.ObjectAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.sayuri.model.AssistantState
import com.example.sayuri.viewmodel.VoiceAssistantViewModel
import kotlinx.coroutines.launch

@Composable
fun SayuriScreen(viewModel: VoiceAssistantViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var messageText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Mic button + indicator area ────────────────────────────────────────
        Spacer(modifier = Modifier.height(64.dp))

        MicButtonWithIndicator(state = state, viewModel = viewModel)

        // ── Status text ────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(32.dp))

        StatusTextView(state = state)

        // ── Subtitle (speaking state) ──────────────────────────────────────────
        AnimatedVisibility(
            visible = state is AssistantState.Speaking,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            if (state is AssistantState.Speaking) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = (state as AssistantState.Speaking).text,
                        color = Color(0xCCFFFFFF),
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Bottom controls (voice selector, screen agent) ─────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { /* TODO: Implement screen agent */ },
                colors = ButtonDefaults.textButtonColors(),
                modifier = Modifier.height(40.dp)
            ) {
                Text("Run Screen Agent", fontSize = 12.sp, color = Color(0xFFB3B3B3))
            }

            Button(
                onClick = { /* TODO: Cycle TTS locales */ },
                colors = ButtonDefaults.textButtonColors(),
                modifier = Modifier.height(40.dp)
            ) {
                Text("🗣 EN-US", fontSize = 12.sp, color = Color(0xFFB3B3B3))
            }
        }

        // ── Message input box ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                placeholder = { Text("Type a message", fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (messageText.trim().isNotEmpty()) {
                            scope.launch {
                                viewModel.handleTranscript(messageText.trim())
                                messageText = ""
                            }
                        }
                    }
                ),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFFFFFFF),
                    unfocusedTextColor = Color(0xFFFFFFFF),
                    focusedContainerColor = Color(0xFF2A2A2A),
                    unfocusedContainerColor = Color(0xFF2A2A2A),
                    focusedIndicatorColor = Color(0xFF6200EE),
                    unfocusedIndicatorColor = Color(0xFF555555)
                )
            )

            Button(
                onClick = {
                    if (messageText.trim().isNotEmpty()) {
                        scope.launch {
                            viewModel.handleTranscript(messageText.trim())
                            messageText = ""
                        }
                    }
                },
                modifier = Modifier
                    .height(56.dp)
                    .width(80.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
            ) {
                Text("Send", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun MicButtonWithIndicator(
    state: AssistantState,
    viewModel: VoiceAssistantViewModel
) {
    Box(
        modifier = Modifier
            .size(96.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulse ring (only in listening states)
        if (state is AssistantState.WakeWordListening || state is AssistantState.ActiveListening) {
            val color = if (state is AssistantState.ActiveListening) {
                Color(0xFF00BCD4)
            } else {
                Color(0xFF00BCD4).copy(alpha = 0.5f)
            }
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(color, shape = androidx.compose.foundation.shape.CircleShape)
            )
        }

        // Mic button
        FloatingActionButton(
            onClick = { viewModel.onMicPressed() },
            modifier = Modifier.size(80.dp),
            containerColor = when (state) {
                is AssistantState.Idle, is AssistantState.Error -> Color(0xFF424242)
                else -> Color(0xFF6200EE)
            },
            contentColor = Color.White
        ) {
            Icon(
                imageVector = when (state) {
                    is AssistantState.Idle, is AssistantState.Error -> Icons.Default.MicOff
                    else -> Icons.Default.Mic
                },
                contentDescription = "Microphone",
                modifier = Modifier.size(40.dp)
            )
        }

        // Processing spinner (in Processing state)
        if (state is AssistantState.Processing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.BottomCenter),
                color = Color(0xFF00BCD4),
                strokeWidth = 4.dp
            )
        }
    }
}

@Composable
private fun StatusTextView(state: AssistantState) {
    val (text, color) = when (state) {
        is AssistantState.Idle -> "Tap mic to speak" to Color(0xFFB3B3B3)
        is AssistantState.WakeWordListening -> "Listening for 'Sayuri'…" to Color(0xFFB3B3B3)
        is AssistantState.ActiveListening -> "Listening…" to Color(0xFF00BCD4)
        is AssistantState.Processing -> "Thinking…" to Color(0xFFB3B3B3)
        is AssistantState.Speaking -> "Speaking…" to Color(0xFF00BCD4)
        is AssistantState.Error -> (state as AssistantState.Error).message to Color(0xFFFF5252)
    }

    Text(
        text = text,
        color = color,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    )
}
