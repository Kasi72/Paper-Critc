package com.example.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Authentic ui-neumorphism (AKAspanion) design tokens for Jetpack Compose.
 *
 * Golden Rule of Neumorphism:
 * Extruded surfaces share the EXACT same base clay hue as the canvas (#E0E5EC light / #262A30 dark),
 * so physical 3D relief emerges purely from opposing top-left bright light and bottom-right cool shadow.
 */
@Immutable
data class NeumorphicPalette(
  val isDark: Boolean,
  val canvas: Color,
  val surfaceRaised: Color,
  val surfaceElevated: Color,
  val surfaceRecessed: Color,
  val lightShadow: Color,
  val darkShadow: Color,
  val innerDarkShadow: Color,
  val innerLightHighlight: Color,
  val hairlineHighlight: Color,
  val hairlineShadow: Color,
  val accentGlow: Color,
  val mintGlow: Color
)

val LightNeumorphicPalette = NeumorphicPalette(
  isDark = false,
  canvas = Color(0xFFE0E5EC),
  surfaceRaised = Color(0xFFE0E5EC),
  surfaceElevated = Color(0xFFE3E8EF),
  surfaceRecessed = Color(0xFFDCE2EB),
  lightShadow = Color(0xFFFFFFFF),
  darkShadow = Color(0xFFA3B1C6),
  innerDarkShadow = Color(0xFF9AA8BE),
  innerLightHighlight = Color(0xFFFFFFFF),
  hairlineHighlight = Color(0xD9FFFFFF),
  hairlineShadow = Color(0x669AA8BE),
  accentGlow = Color(0x454E6E9A),
  mintGlow = Color(0x452F8F6B)
)

val DarkNeumorphicPalette = NeumorphicPalette(
  isDark = true,
  canvas = Color(0xFF262A30),
  surfaceRaised = Color(0xFF262A30),
  surfaceElevated = Color(0xFF292E35),
  surfaceRecessed = Color(0xFF20242A),
  lightShadow = Color(0xFF363C46),
  darkShadow = Color(0xFF14171C),
  innerDarkShadow = Color(0xFF111318),
  innerLightHighlight = Color(0xFF3C434F),
  hairlineHighlight = Color(0x70414855),
  hairlineShadow = Color(0x99111318),
  accentGlow = Color(0x527FA4D6),
  mintGlow = Color(0x522F8F6B)
)

val LocalNeumorphicPalette = staticCompositionLocalOf { LightNeumorphicPalette }

@Composable
fun ProvideNeumorphicPalette(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit
) {
  val palette = if (darkTheme) DarkNeumorphicPalette else LightNeumorphicPalette
  CompositionLocalProvider(LocalNeumorphicPalette provides palette, content = content)
}

/**
 * Extruded (Raised / Outset) ui-neumorphism surface with crisp dual directional shadows:
 * - Top-left bright light halo (-offset, -offset)
 * - Bottom-right deep ambient shadow (+offset, +offset)
 * - Subtle convex directional clay gradient
 * - Delicate diagonal specular rim separating light & shadow faces
 */
fun Modifier.neuRaised(
  cornerRadius: Dp = 18.dp,
  elevation: Dp = 6.5.dp,
  surfaceColor: Color? = null,
  accentGlowColor: Color? = null,
  drawBorderRim: Boolean = true
): Modifier = composed {
  val palette = LocalNeumorphicPalette.current
  val fill = surfaceColor ?: palette.surfaceRaised
  val lightCol = palette.lightShadow
  val darkCol = palette.darkShadow
  val topRim = palette.hairlineHighlight
  val bottomRim = palette.hairlineShadow

  this.drawBehind {
    val radiusPx = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
    val offsetPx = elevation.toPx()
    val blurPx = (elevation.toPx() * 2.0f).coerceAtLeast(2f)

    drawIntoCanvas { canvas ->
      val native = canvas.nativeCanvas

      // 1. Optional subtle accent ambient halo underneath colored active controls
      if (accentGlowColor != null) {
        val glowPaint = AndroidPaint().apply {
          isAntiAlias = true
          color = accentGlowColor.toArgb()
          maskFilter = BlurMaskFilter(blurPx * 1.25f, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
          0f,
          offsetPx * 0.7f,
          size.width,
          size.height + offsetPx * 0.7f,
          radiusPx,
          radiusPx,
          glowPaint
        )
      }

      // 2. Top-left crisp bright light source cast
      val lightPaint = AndroidPaint().apply {
        isAntiAlias = true
        color = lightCol.toArgb()
        maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
      }
      native.drawRoundRect(
        -offsetPx * 0.85f,
        -offsetPx * 0.85f,
        size.width - offsetPx * 0.85f,
        size.height - offsetPx * 0.85f,
        radiusPx,
        radiusPx,
        lightPaint
      )

      // 3. Bottom-right sculpted ambient shadow cast
      val darkPaint = AndroidPaint().apply {
        isAntiAlias = true
        color = darkCol.toArgb()
        maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
      }
      native.drawRoundRect(
        offsetPx * 0.95f,
        offsetPx * 0.95f,
        size.width + offsetPx * 0.95f,
        size.height + offsetPx * 0.95f,
        radiusPx,
        radiusPx,
        darkPaint
      )
    }

    // 4. Monochromatic sculpted clay face (very subtle top-left convex catch light)
    val topLightened = Color(
      red = (fill.red + if (palette.isDark) 0.022f else 0.025f).coerceAtMost(1f),
      green = (fill.green + if (palette.isDark) 0.022f else 0.025f).coerceAtMost(1f),
      blue = (fill.blue + if (palette.isDark) 0.024f else 0.022f).coerceAtMost(1f),
      alpha = fill.alpha
    )
    val bottomShaded = Color(
      red = (fill.red - if (palette.isDark) 0.015f else 0.018f).coerceAtLeast(0f),
      green = (fill.green - if (palette.isDark) 0.015f else 0.018f).coerceAtLeast(0f),
      blue = (fill.blue - if (palette.isDark) 0.014f else 0.015f).coerceAtLeast(0f),
      alpha = fill.alpha
    )
    drawRoundRect(
      brush = Brush.linearGradient(
        colors = listOf(topLightened, fill, bottomShaded),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
      ),
      cornerRadius = CornerRadius(radiusPx, radiusPx)
    )

    // 5. Crisp diagonal specular rim for unmistakable tactile edges
    if (drawBorderRim) {
      drawRoundRect(
        brush = Brush.linearGradient(
          colors = listOf(topRim, Color.Transparent, bottomRim),
          start = Offset.Zero,
          end = Offset(size.width, size.height)
        ),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = 1.15.dp.toPx())
      )
    }
  }
}

/**
 * Carved / Inset (Pressed) ui-neumorphism cavity.
 *
 * Uses BOTH GPU-safe multi-stop directional inner-shadow linear gradients (top/left dark wall,
 * bottom/right bright light wall) AND clipped blurred inner strokes, ensuring unmistakable
 * carved 3D depth across all Android devices and streaming emulators.
 */
fun Modifier.neuRecessed(
  cornerRadius: Dp = 16.dp,
  depth: Dp = 4.5.dp,
  cavityColor: Color? = null,
  focusGlowColor: Color? = null
): Modifier = composed {
  val palette = LocalNeumorphicPalette.current
  val fill = cavityColor ?: palette.surfaceRecessed
  val innerDark = palette.innerDarkShadow
  val innerLight = palette.innerLightHighlight

  this.drawWithContent {
    val radiusPx = cornerRadius.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
    val depthPx = depth.toPx()

    // Optional outer glowing focus ring for active switches/progress
    if (focusGlowColor != null) {
      drawIntoCanvas { canvas ->
        val glowPaint = AndroidPaint().apply {
          isAntiAlias = true
          color = focusGlowColor.copy(alpha = 0.36f).toArgb()
          maskFilter = BlurMaskFilter((depthPx * 2.2f).coerceAtLeast(2f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawRoundRect(
          0f,
          0f,
          size.width,
          size.height,
          radiusPx,
          radiusPx,
          glowPaint
        )
      }
    }

    // 1. Base carved cavity fill (subtle concave gradient: slightly darker top-left, brighter bottom-right)
    val topConcave = Color(
      red = (fill.red - if (palette.isDark) 0.025f else 0.032f).coerceAtLeast(0f),
      green = (fill.green - if (palette.isDark) 0.025f else 0.030f).coerceAtLeast(0f),
      blue = (fill.blue - if (palette.isDark) 0.022f else 0.026f).coerceAtLeast(0f),
      alpha = fill.alpha
    )
    val bottomConcave = Color(
      red = (fill.red + if (palette.isDark) 0.025f else 0.035f).coerceAtMost(1f),
      green = (fill.green + if (palette.isDark) 0.025f else 0.035f).coerceAtMost(1f),
      blue = (fill.blue + if (palette.isDark) 0.028f else 0.032f).coerceAtMost(1f),
      alpha = fill.alpha
    )
    drawRoundRect(
      brush = Brush.linearGradient(
        colors = listOf(topConcave, fill, bottomConcave),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
      ),
      cornerRadius = CornerRadius(radiusPx, radiusPx)
    )

    // 2. Clipped directional inner shadow gradients BEFORE content so text/icons stay razor sharp
    val clip = Path().apply {
      addRoundRect(
        RoundRect(
          rect = Rect(0f, 0f, size.width, size.height),
          cornerRadius = CornerRadius(radiusPx, radiusPx)
        )
      )
    }
    val shadowBand = (depthPx * 2.6f).coerceAtMost(minOf(size.width, size.height) * 0.42f)
    val lightBand = (depthPx * 2.3f).coerceAtMost(minOf(size.width, size.height) * 0.38f)

    clipPath(clip) {
      // Top inner shadow wall
      drawRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            innerDark.copy(alpha = if (palette.isDark) 0.80f else 0.72f),
            innerDark.copy(alpha = if (palette.isDark) 0.28f else 0.22f),
            Color.Transparent
          ),
          startY = 0f,
          endY = shadowBand
        )
      )
      // Left inner shadow wall
      drawRect(
        brush = Brush.horizontalGradient(
          colors = listOf(
            innerDark.copy(alpha = if (palette.isDark) 0.76f else 0.68f),
            innerDark.copy(alpha = if (palette.isDark) 0.24f else 0.20f),
            Color.Transparent
          ),
          startX = 0f,
          endX = shadowBand
        )
      )
      // Bottom inner bright catch-light wall
      drawRect(
        brush = Brush.verticalGradient(
          colors = listOf(
            Color.Transparent,
            innerLight.copy(alpha = if (palette.isDark) 0.28f else 0.42f),
            innerLight.copy(alpha = if (palette.isDark) 0.78f else 0.96f)
          ),
          startY = (size.height - lightBand).coerceAtLeast(0f),
          endY = size.height
        )
      )
      // Right inner bright catch-light wall
      drawRect(
        brush = Brush.horizontalGradient(
          colors = listOf(
            Color.Transparent,
            innerLight.copy(alpha = if (palette.isDark) 0.26f else 0.38f),
            innerLight.copy(alpha = if (palette.isDark) 0.72f else 0.92f)
          ),
          startX = (size.width - lightBand).coerceAtLeast(0f),
          endX = size.width
        )
      )

      // Soft curved corner shadows via blurred inner offset strokes
      val blurPx = (depthPx * 1.6f).coerceAtLeast(2f)
      drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas

        val shadowPaint = AndroidPaint().apply {
          isAntiAlias = true
          style = AndroidPaint.Style.STROKE
          strokeWidth = depthPx * 2.1f
          color = innerDark.copy(alpha = if (palette.isDark) 0.85f else 0.75f).toArgb()
          maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
          depthPx * 0.45f,
          depthPx * 0.45f,
          size.width + depthPx * 1.45f,
          size.height + depthPx * 1.45f,
          radiusPx,
          radiusPx,
          shadowPaint
        )

        val highlightPaint = AndroidPaint().apply {
          isAntiAlias = true
          style = AndroidPaint.Style.STROKE
          strokeWidth = depthPx * 1.8f
          color = innerLight.copy(alpha = if (palette.isDark) 0.72f else 0.92f).toArgb()
          maskFilter = BlurMaskFilter(blurPx * 0.9f, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
          -depthPx * 1.35f,
          -depthPx * 1.35f,
          size.width - depthPx * 0.35f,
          size.height - depthPx * 0.35f,
          radiusPx,
          radiusPx,
          highlightPaint
        )
      }
    }

    // 3. Draw child content crisply inside the carved basin
    drawContent()

    // 4. Crisp beveled lip around the outer rim of the carved basin
    drawRoundRect(
      brush = Brush.linearGradient(
        colors = listOf(
          innerDark.copy(alpha = if (palette.isDark) 0.70f else 0.55f),
          Color.Transparent,
          innerLight.copy(alpha = if (palette.isDark) 0.65f else 0.95f)
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
      ),
      cornerRadius = CornerRadius(radiusPx, radiusPx),
      style = Stroke(width = 1.1.dp.toPx())
    )

    // Optional crisp focus border when glowing
    if (focusGlowColor != null) {
      drawRoundRect(
        color = focusGlowColor.copy(alpha = 0.80f),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = 1.4.dp.toPx())
      )
    }
  }
}

/**
 * Convenience helper for subtle Neumorphic section micro-headers.
 */
@Composable
fun neuSectionLabelColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.90f)
