package dev.pesnik.roro.network

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class ChatEvent {
    data class Content(val text: String) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
    data object Done : ChatEvent()
}

/**
 * Talks directly to Hermes's OpenAI-compatible `/chat/completions` SSE
 * endpoint — the same contract `sidecar/gateway/hermes_client.py` speaks.
 * No local model, no llama.cpp: this is a pure HTTP client.
 */
class HermesClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming response, no read timeout
        .build()

    fun streamChat(history: List<Pair<String, String>>): Flow<ChatEvent> = callbackFlow {
        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("max_tokens", 512)
            .put("temperature", 0.7)

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(ChatEvent.Done)
                    return
                }
                try {
                    val obj = JSONObject(data)
                    val choices = obj.optJSONArray("choices") ?: return
                    if (choices.length() == 0) return
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return
                    val text = delta.optString("content", "")
                    if (text.isNotEmpty()) {
                        trySend(ChatEvent.Content(text))
                    }
                } catch (_: Exception) {
                    // malformed / partial chunk — skip it, stream continues
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = t?.message
                    ?: response?.let { "HTTP ${it.code}: ${it.message}" }
                    ?: "unknown error"
                trySend(ChatEvent.Error(msg))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(ChatEvent.Done)
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { eventSource.cancel() }
    }
}
