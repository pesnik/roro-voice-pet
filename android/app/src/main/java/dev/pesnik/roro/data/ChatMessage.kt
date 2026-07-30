package dev.pesnik.roro.data

data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val isStreaming: Boolean = false,
)
