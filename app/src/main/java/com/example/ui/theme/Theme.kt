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
  onPrimary = Color(0xFF161B22),
  primaryContainer = Color(0xFF2E3A4D),
  onPrimaryContainer = Color(0xFFDCE7F5),
  secondary = CupertinoIndigo,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFF2D3145),
  onSecondaryContainer = Color(0xFFE0E2F5),
  tertiary = CupertinoGreen,
  onTertiary = Color.White,
  background = IosGroupedBgDark,
  onBackground = Color(0xFFE8EDF3),
  surface = IosSurfaceDark,
  onSurface = Color(0xFFE8EDF3),
  surfaceVariant = IosSecondarySurfaceDark,
  onSurfaceVariant = Color(0xFF9BA6B6),
  outline = Color(0xFF3E4552),
  outlineVariant = Color(0xFF323842),
  error = CupertinoRed
)

private val LightColorScheme = lightColorScheme(
  primary = CupertinoBlue,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFD4DEE9),
  onPrimaryContainer = Color(0xFF273F61),
  secondary = CupertinoIndigo,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFD9DDEA),
  onSecondaryContainer = Color(0xFF2E3663),
  tertiary = CupertinoGreen,
  onTertiary = Color.White,
  background = IosGroupedBgLight,
  onBackground = Color(0xFF2D3748),
  surface = IosSurfaceLight,
  onSurface = Color(0xFF2D3748),
  surfaceVariant = IosSecondarySurfaceLight,
  onSurfaceVariant = Color(0xFF5D6B7E),
  outline = Color(0xFFAAB6C8),
  outlineVariant = Color(0xFFC2CCD9),
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
