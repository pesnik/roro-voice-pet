package dev.pesnik.roro.ui.theme

import dev.pesnik.roro.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp

// Downloadable Google Fonts (fetched/cached via Google Play Services at
// runtime — no .ttf bundled in the APK). Baloo 2 is a rounded, characterful
// display face matching the pet's playful personality; Nunito is a warm,
// highly-readable rounded body face — neither is a generic system default.
private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Baloo2 = FontFamily(
    Font(googleFont = GoogleFont("Baloo 2"), fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Baloo 2"), fontProvider = googleFontProvider, weight = FontWeight.Bold),
)

private val Nunito = FontFamily(
    Font(googleFont = GoogleFont("Nunito"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Nunito"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Nunito"), fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = GoogleFont("Nunito"), fontProvider = googleFontProvider, weight = FontWeight.Bold),
)

val Typography = Typography(
    titleLarge = TextStyle(fontFamily = Baloo2, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = Baloo2, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)
