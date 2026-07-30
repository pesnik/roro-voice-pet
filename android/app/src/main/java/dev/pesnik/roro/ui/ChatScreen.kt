package dev.pesnik.roro.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pesnik.roro.ui.richtext.MarkdownBlock
import dev.pesnik.roro.voice.SpeechToText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit, viewModel: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micPermissionGranted = granted }

    val speechToText = remember {
        SpeechToText(
            context = context,
            onResult = { text ->
                isListening = false
                viewModel.sendMessage(text)
            },
            onError = { isListening = false },
        )
    }
    DisposableEffect(Unit) {
        onDispose { speechToText.destroy() }
    }

    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RoRo") },
                actions = {
                    TextButton(onClick = { viewModel.voiceReplyEnabled.value = !viewModel.voiceReplyEnabled.value }) {
                        Text(if (viewModel.voiceReplyEnabled.value) "🔊" else "🔇")
                    }
                    TextButton(onClick = onOpenSettings) { Text("⚙️") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(8.dp)) {
                viewModel.errorText.value?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message RoRo…") },
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (!micPermissionGranted) {
                            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            isListening = true
                            speechToText.start()
                        }
                    }) {
                        Text(if (isListening) "🎙️…" else "🎙️")
                    }
                    TextButton(
                        onClick = {
                            val text = input
                            input = ""
                            viewModel.sendMessage(text)
                        },
                        enabled = input.isNotBlank() && !viewModel.isSending.value,
                    ) { Text("➤") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(viewModel.messages) { msg ->
                MessageBubble(role = msg.role, content = msg.content, streaming = msg.isStreaming)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun MessageBubble(role: String, content: String, streaming: Boolean) {
    val isUser = role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (content.isBlank()) {
                Text(
                    text = if (streaming) "…" else "",
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                MarkdownBlock(content = content, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
