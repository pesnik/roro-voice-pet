package dev.pesnik.roro.ui.richtext

/*
 * Loosely modeled on the chrome (language label + copy button) of RikkaHub's
 * app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HighlightCodeBlock.kt
 * (https://github.com/rikkahub/rikkahub, AGPL-3.0) — see NOTICE.md. Written fresh
 * and much smaller: no WebView preview, no fullscreen navigation, no file download,
 * no syntax coloring (that engine depends on a JNI-wrapped QuickJS/Prism.js runtime
 * we deliberately don't carry — see the plan). Uses a plain text "Copy" affordance
 * rather than a Material icon, matching this app's existing emoji/text-button style
 * (see ChatScreen.kt) instead of pulling in material-icons-extended for one glyph.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun CodeBlock(code: String, language: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var justCopied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language.ifBlank { "text" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (justCopied) "Copied" else "Copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(code))
                        justCopied = true
                    },
                )
            }
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(text = code, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
