package dev.pesnik.roro.ui.richtext

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Adapted from RikkaHub's `me.rerere.rikkahub.utils.toDp` (AGPL-3.0) — see NOTICE.md. */
@Composable
fun TextUnit.toDp(): Dp = with(LocalDensity.current) {
    // Density.toDp(TextUnit) only supports Sp; Em/Unspecified throw "Only Sp can convert to Px".
    if (this@toDp.isSp) this@toDp.toDp() else 0.dp
}
