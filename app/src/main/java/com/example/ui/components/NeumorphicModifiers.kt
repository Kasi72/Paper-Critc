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
 * Semantic Neumorphic / Neo-Tactile design tokens adapting seamlessly between
 * Porcelain Soft Light Mode and Obsidian Tactile Dark Mode.
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
  canvas = Color(0xFFE7ECF3),
  surfaceRaised = Color(0xFFEDF1F7),
  surfaceElevated = Color(0xFFF3F6FB),
  surfaceRecessed = Color(0xFFDCE3EE),
  lightShadow = Color(0xEBFFFFFF),
  darkShadow = Color(0xCCBCC8DA),
  innerDarkShadow = Color(0xA6B5C2D6),
  innerLightHighlight = Color(0xE6FFFFFF),
  hairlineHighlight = Color(0xCCFFFFFF),
  hairlineShadow = Color(0x52B3C0D4),
  accentGlow = Color(0x552E7CF6),
  mintGlow = Color(0x5526C281)
)

val DarkNeumorphicPalette = NeumorphicPalette(
  isDark = true,
  canvas = Color(0xFF1A1F2A),
  surfaceRaised = Color(0xFF212735),
  surfaceElevated = Color(0xFF262D3D),
  surfaceRecessed = Color(0xFF141821),
  lightShadow = Color(0xB82F384C),
  darkShadow = Color(0xE60B0E15),
  innerDarkShadow = Color(0xCC090C12),
  innerLightHighlight = Color(0x88333D52),
  hairlineHighlight = Color(0x523C4760),
  hairlineShadow = Color(0x80090C12),
  accentGlow = Color(0x663B82F6),
  mintGlow = Color(0x662DD4BF)
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
 * Extruded (Raised) Neo-Tactile surface with soft dual directional shadows
 * (top-left bright light source + bottom-right cool ambient shadow), subtle convex sheen,
 * and delicate top-left specular rim.
 */
fun Modifier.neuRaised(
  cornerRadius: Dp = 16.dp,
  elevation: Dp = 6.dp,
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
    val blurPx = (elevation.toPx() * 1.85f).coerceAtLeast(1f)

    drawIntoCanvas { canvas ->
      val native = canvas.nativeCanvas

      // 1. Optional subtle accent ambient halo underneath active/primary controls
      if (accentGlowColor != null) {
        val glowPaint = AndroidPaint().apply {
          isAntiAlias = true
          color = accentGlowColor.toArgb()
          maskFilter = BlurMaskFilter(blurPx * 1.25f, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
          0f,
          offsetPx * 0.65f,
          size.width,
          size.height + offsetPx * 0.65f,
          radiusPx,
          radiusPx,
          glowPaint
        )
      }

      // 2. Top-left soft directional highlight
      val lightPaint = AndroidPaint().apply {
        isAntiAlias = true
        color = lightCol.toArgb()
        maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
      }
      native.drawRoundRect(
        -offsetPx * 0.72f,
        -offsetPx * 0.72f,
        size.width - offsetPx * 0.72f,
        size.height - offsetPx * 0.72f,
        radiusPx,
        radiusPx,
        lightPaint
      )

      // 3. Bottom-right soft ambient shadow
      val darkPaint = AndroidPaint().apply {
        isAntiAlias = true
        color = darkCol.toArgb()
        maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
      }
      native.drawRoundRect(
        offsetPx * 0.85f,
        offsetPx * 0.85f,
        size.width + offsetPx * 0.85f,
        size.height + offsetPx * 0.85f,
        radiusPx,
        radiusPx,
        darkPaint
      )
    }

    // 4. Sculpted convex surface fill (subtle top-left light to bottom-right calm tone)
    val topLightened = Color(
      red = (fill.red + if (palette.isDark) 0.025f else 0.028f).coerceAtMost(1f),
      green = (fill.green + if (palette.isDark) 0.025f else 0.028f).coerceAtMost(1f),
      blue = (fill.blue + if (palette.isDark) 0.028f else 0.026f).coerceAtMost(1f),
      alpha = fill.alpha
    )
    drawRoundRect(
      brush = Brush.linearGradient(
        colors = listOf(topLightened, fill),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
      ),
      cornerRadius = CornerRadius(radiusPx, radiusPx)
    )

    // 5. Delicate diagonal specular hairline rim
    if (drawBorderRim) {
      drawRoundRect(
        brush = Brush.linearGradient(
          colors = listOf(topRim, Color.Transparent, bottomRim),
          start = Offset.Zero,
          end = Offset(size.width, size.height)
        ),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = 1.dp.toPx())
      )
    }
  }
}

/**
 * Recessed (Pressed / Carved Inner-Shadow) Neo-Tactile cavity for segmented tracks,
 * toggle grooves, search wells, recessed icon buttons, and sunken states.
 */
fun Modifier.neuRecessed(
  cornerRadius: Dp = 14.dp,
  depth: Dp = 3.5.dp,
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

    // Optional outer glowing focus ring (as in Neo-Tactile active inputs & toggles)
    if (focusGlowColor != null) {
      drawIntoCanvas { canvas ->
        val glowPaint = AndroidPaint().apply {
          isAntiAlias = true
          color = focusGlowColor.copy(alpha = 0.42f).toArgb()
          maskFilter = BlurMaskFilter((depthPx * 2.4f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
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

    // Base recessed cavity fill
    drawRoundRect(
      color = fill,
      cornerRadius = CornerRadius(radiusPx, radiusPx)
    )

    // Draw child content inside the cavity
    drawContent()

    // Clipped inner top-left dark shadow & bottom-right catch light
    val clip = Path().apply {
      addRoundRect(
        RoundRect(
          rect = Rect(0f, 0f, size.width, size.height),
          cornerRadius = CornerRadius(radiusPx, radiusPx)
        )
      )
    }
    clipPath(clip) {
      val blurPx = (depthPx * 1.5f).coerceAtLeast(1f)
      drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas

        // Inner top-left shadow cast into cavity
        val shadowPaint = AndroidPaint().apply {
          isAntiAlias = true
          style = AndroidPaint.Style.STROKE
          strokeWidth = depthPx * 1.9f
          color = innerDark.toArgb()
          maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
          depthPx * 0.55f,
          depthPx * 0.55f,
          size.width + depthPx * 1.35f,
          size.height + depthPx * 1.35f,
          radiusPx,
          radiusPx,
          shadowPaint
        )

        // Inner bottom-right bright rim catching top-left light
        val highlightPaint = AndroidPaint().apply {
          isAntiAlias = true
          style = AndroidPaint.Style.STROKE
          strokeWidth = depthPx * 1.5f
          color = innerLight.toArgb()
          maskFilter = BlurMaskFilter(blurPx * 0.85f, BlurMaskFilter.Blur.NORMAL)
        }
        native.drawRoundRect(
          -depthPx * 1.25f,
          -depthPx * 1.25f,
          size.width - depthPx * 0.45f,
          size.height - depthPx * 0.45f,
          radiusPx,
          radiusPx,
          highlightPaint
        )
      }
    }

    // Optional crisp focus border when glowing
    if (focusGlowColor != null) {
      drawRoundRect(
        color = focusGlowColor.copy(alpha = 0.85f),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = 1.4.dp.toPx())
      )
    }
  }
}

/**
 * Convenience helper for subtle Neumorphic hairline separator grooves (embossed 1dp dark + 1dp light).
 */
@Composable
fun neuSectionLabelColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
