package dev.pesnik.roro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pesnik.roro.ui.ChatScreen
import dev.pesnik.roro.ui.SettingsScreen
import dev.pesnik.roro.ui.theme.RoroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoroTheme {
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    ChatScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}
