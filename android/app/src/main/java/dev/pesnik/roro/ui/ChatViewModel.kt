package dev.pesnik.roro.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pesnik.roro.data.ChatMessage
import dev.pesnik.roro.data.SettingsRepository
import dev.pesnik.roro.network.ChatEvent
import dev.pesnik.roro.network.HermesClient
import dev.pesnik.roro.pet.PetState
import dev.pesnik.roro.pet.PetStateController
import dev.pesnik.roro.voice.TextToSpeechHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val tts = TextToSpeechHelper(app)

    val messages = mutableStateListOf<ChatMessage>()
    val isSending = mutableStateOf(false)
    val voiceReplyEnabled = mutableStateOf(true)
    val errorText = mutableStateOf<String?>(null)

    fun sendMessage(text: String) {
        if (text.isBlank() || isSending.value) return
        errorText.value = null
        messages.add(ChatMessage(role = "user", content = text))
        val assistantIndex = messages.size
        messages.add(ChatMessage(role = "assistant", content = "", isStreaming = true))
        isSending.value = true

        // Mirrors sidecar/gateway/server.py's _stream_chat pet-state lifecycle
        // (bridge.post("thinking"/"working"/"attention"/"error")), just driven
        // client-side here since there's no Python sidecar on Android.
        PetStateController.request(PetState.Thinking)

        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            if (settings.apiKey.isBlank()) {
                errorText.value = "Set your Hermes API key in Settings first"
                messages.removeAt(assistantIndex)
                isSending.value = false
                PetStateController.request(PetState.Error)
                return@launch
            }

            val client = HermesClient(settings.baseUrl, settings.apiKey, settings.model)
            val history = messages.subList(0, assistantIndex).map { it.role to it.content }
            val builder = StringBuilder()
            var hasStartedWorking = false
            var lastPetPing = 0L

            client.streamChat(history).collect { event ->
                when (event) {
                    is ChatEvent.Content -> {
                        builder.append(event.text)
                        messages[assistantIndex] = messages[assistantIndex].copy(content = builder.toString())

                        val now = System.currentTimeMillis()
                        if (!hasStartedWorking) {
                            hasStartedWorking = true
                            lastPetPing = now
                            PetStateController.request(PetState.Working)
                        } else if (now - lastPetPing > 6000) {
                            lastPetPing = now
                            PetStateController.request(PetState.Working)
                        }
                    }

                    is ChatEvent.Error -> {
                        errorText.value = event.message
                        messages[assistantIndex] = messages[assistantIndex].copy(
                            content = builder.toString().ifBlank { "⚠️ ${event.message}" },
                            isStreaming = false,
                        )
                        isSending.value = false
                        PetStateController.request(PetState.Error)
                    }

                    ChatEvent.Done -> {
                        messages[assistantIndex] = messages[assistantIndex].copy(isStreaming = false)
                        isSending.value = false
                        PetStateController.request(PetState.Attention)
                        if (voiceReplyEnabled.value && builder.isNotBlank()) {
                            tts.speak(builder.toString())
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        tts.shutdown()
        super.onCleared()
    }
}
