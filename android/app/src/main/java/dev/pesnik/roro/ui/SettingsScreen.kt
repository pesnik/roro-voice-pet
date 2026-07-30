package dev.pesnik.roro.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pesnik.roro.data.SettingsRepository
import dev.pesnik.roro.pet.PetOverlayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf(SettingsRepository.DEFAULT_BASE_URL) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(SettingsRepository.DEFAULT_MODEL) }
    var saved by remember { mutableStateOf(false) }

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var petRunning by remember { mutableStateOf(PetOverlayService.isRunning) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way — the overlay still works without it, just the persistent notification won't show */ }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted) {
            ContextCompat.startForegroundService(context, Intent(context, PetOverlayService::class.java))
            petRunning = true
        }
    }

    LaunchedEffect(Unit) {
        val current = repo.settings.first()
        baseUrl = current.baseUrl
        apiKey = current.apiKey
        model = current.model
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hermes backend", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; saved = false },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saved = false },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = {
                scope.launch {
                    repo.update(baseUrl.trim(), apiKey.trim(), model.trim())
                    saved = true
                }
            }) {
                Text("Save")
            }
            if (saved) {
                Text("Saved", color = MaterialTheme.colorScheme.primary)
            }

            Text("Floating pet", style = MaterialTheme.typography.titleMedium)
            if (!overlayGranted) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }) {
                    Text("Grant overlay permission")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show floating pet", modifier = Modifier.weight(1f))
                    Switch(
                        checked = petRunning,
                        onCheckedChange = { checked ->
                            petRunning = checked
                            val intent = Intent(context, PetOverlayService::class.java)
                            if (checked) {
                                ContextCompat.startForegroundService(context, intent)
                            } else {
                                context.stopService(intent)
                            }
                        },
                    )
                }
            }
        }
    }
}
