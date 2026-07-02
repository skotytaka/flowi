package com.example.budget_planner

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Theme ─────────────────────────────────────────────────────────────────────
// AppThemeData bundles all colors for the active theme in one place.
// The root of the app animates between themes, so every color here can be
// smoothly cross-faded when the user switches themes in Settings.

data class AppThemeData(
    val bg: Color,      // screen background
    val fg: Color,      // primary text
    val cardBg: Color,  // card / surface background
    val accent: Color   // brand accent
)

val kThemes = mapOf(
    "black"       to AppThemeData(Color(0xFF0A0A0F), Color(0xFFEAEAEA), Color(0xFF1C1C26), Color(0xFF7B8FF7)),
    "white"       to AppThemeData(Color(0xFFF2F2F7), Color(0xFF1C1C1E), Color(0xFFFFFFFF), Color(0xFF0A84FF)),
    "sakura"      to AppThemeData(Color(0xFFFFF0F3), Color(0xFFAD1457), Color(0xFFFFFFFF), Color(0xFFE91E8C)),
    "cruel_angel" to AppThemeData(Color(0xFF080812), Color(0xFFFF4444), Color(0xFF1A0020), Color(0xFFFF2255)),
    "hacker"      to AppThemeData(Color(0xFF060F06), Color(0xFF33FF33), Color(0xFF0A160A), Color(0xFF00E676)),
    "mandarin"    to AppThemeData(Color(0xFFFFF8F0), Color(0xFFBF5700), Color(0xFFFFFFFF), Color(0xFFFF6D00))
)

val kThemeKeys = listOf("black", "white", "sakura", "cruel_angel", "hacker", "mandarin")

// Semantic colors shared by every theme
val kGood = Color(0xFF34C759)   // income / positive
val kBad  = Color(0xFFFF3B30)   // expense / negative / destructive

// Palette used by charts and category color dots
val kChartColors = listOf(
    Color(0xFF4E79A7), Color(0xFFF28E2B), Color(0xFFE15759), Color(0xFF76B7B2),
    Color(0xFF59A14F), Color(0xFFEDC948), Color(0xFFB07AA1), Color(0xFFFF9DA7),
    Color(0xFF9C755F), Color(0xFFBAB0AC)
)

// Themed colors for OutlinedTextField, used by every dialog
@Composable
fun otfColors(th: AppThemeData): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor       = th.fg,
    unfocusedTextColor     = th.fg,
    focusedBorderColor     = th.accent,
    unfocusedBorderColor   = th.fg.copy(alpha = 0.3f),
    focusedLabelColor      = th.accent,
    unfocusedLabelColor    = th.fg.copy(alpha = 0.55f),
    cursorColor            = th.accent
)
