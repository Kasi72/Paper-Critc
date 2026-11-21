package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.ui.components.ProvideNeumorphicPalette

private val DarkColorScheme = darkColorScheme(
  primary = CupertinoBlueDark,
  onPrimary = Color.White,
  primaryContainer = Color(0xFF1E3256),
  onPrimaryContainer = Color(0xFFDCEAFF),
  secondary = CupertinoIndigo,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFF272852),
  onSecondaryContainer = Color(0xFFE3E2FF),
  tertiary = CupertinoGreen,
  onTertiary = Color.White,
  background = IosGroupedBgDark,
  onBackground = Color(0xFFEDF2F8),
  surface = IosSurfaceDark,
  onSurface = Color(0xFFEDF2F8),
  surfaceVariant = IosSecondarySurfaceDark,
  onSurfaceVariant = Color(0xFF94A3B8),
  outline = Color(0xFF39445C),
  outlineVariant = Color(0xFF2C3548),
  error = CupertinoRed
)

private val LightColorScheme = lightColorScheme(
  primary = CupertinoBlue,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFDCE9FC),
  onPrimaryContainer = Color(0xFF133A7C),
  secondary = CupertinoIndigo,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFE5E6FA),
  onSecondaryContainer = Color(0xFF26276B),
  tertiary = CupertinoGreen,
  onTertiary = Color.White,
  background = IosGroupedBgLight,
  onBackground = Color(0xFF1F2B38),
  surface = IosSurfaceLight,
  onSurface = Color(0xFF1F2B38),
  surfaceVariant = IosSecondarySurfaceLight,
  onSurfaceVariant = Color(0xFF5A697D),
  outline = Color(0xFFBDC8D8),
  outlineVariant = Color(0xFFCCD6E4),
  error = CupertinoRed
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  ProvideNeumorphicPalette(darkTheme = darkTheme) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
  }
}
