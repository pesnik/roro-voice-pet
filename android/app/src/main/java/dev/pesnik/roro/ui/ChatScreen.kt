package dev.pesnik.roro.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pesnik.roro.ui.richtext.MarkdownBlock
import dev.pesnik.roro.voice.SpeechToText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit, viewModel: ChatViewModel = viewModel()) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
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
        // enableEdgeToEdge() means the window never actually "resizes" for
        // the IME the way windowSoftInputMode=adjustResize would on a
        // non-edge-to-edge app — Compose has to consume the inset itself,
        // or the bottom bar renders off-screen below the keyboard.
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("RoRo 🐾", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                actions = {
                    RoundIconButton(
                        emoji = if (viewModel.voiceReplyEnabled.value) "🔊" else "🔇",
                        onClick = { viewModel.voiceReplyEnabled.value = !viewModel.voiceReplyEnabled.value },
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(emoji = "⚙️", onClick = onOpenSettings)
                    Spacer(Modifier.width(4.dp))
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(Modifier.padding(12.dp)) {
                viewModel.errorText.value?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Message RoRo…", style = MaterialTheme.typography.bodyLarge) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(
                        emoji = "🎙️",
                        highlighted = isListening,
                        onClick = {
                            if (!micPermissionGranted) {
                                requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                isListening = true
                                speechToText.start()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(
                        emoji = "➤",
                        filled = true,
                        enabled = input.isNotBlank() && !viewModel.isSending.value,
                        onClick = {
                            val text = input
                            input = ""
                            viewModel.sendMessage(text)
                        },
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                // Tap anywhere in the message list to dismiss the keyboard —
                // there's otherwise no way to see the conversation while the
                // keyboard covers most of the screen.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(viewModel.messages) { msg ->
                MessageBubble(role = msg.role, content = msg.content, streaming = msg.isStreaming)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** Small circular emoji button used for the top-bar actions, mic, and send —
 * gives the flat emoji-as-TextButton look some actual shape/weight instead. */
@Composable
private fun RoundIconButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val background = when {
        filled && enabled -> MaterialTheme.colorScheme.primary
        highlighted -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        shape = CircleShape,
        color = background.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = modifier.size(44.dp),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(emoji, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun MessageBubble(role: String, content: String, streaming: Boolean) {
    val isUser = role == "user"
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 },
        ) {
            // A small tail-corner (instead of a fully symmetric pill) is the
            // detail that keeps this from reading as a generic chat bubble.
            val shape = if (isUser) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
            } else {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
            }
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                shape = shape,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                if (content.isBlank()) {
                    Text(
                        text = if (streaming) "…" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(14.dp),
                    )
                } else {
                    MarkdownBlock(
                        content = content,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}
