package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CupertinoAmber
import com.example.ui.theme.CupertinoBlue
import com.example.ui.theme.CupertinoGreen
import com.example.ui.theme.CupertinoIndigo
import com.example.ui.theme.CupertinoRed
import com.example.ui.theme.JetBrainsMonoFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Continuous Superellipse Squircle used across sculpted ui-neumorphism cards, pills, and medallions.
 */
class SquircleShape(
  private val cornerRadius: Dp = 16.dp,
  private val smoothing: Float = 0.86f
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density
  ): Outline {
    val radiusPx = with(density) { cornerRadius.toPx() }
    val r = min(radiusPx, min(size.width, size.height) / 2f)
    val w = size.width
    val h = size.height
    val k = (1f - smoothing).coerceIn(0.08f, 0.45f)
    val cpOffset = r * k

    val path = Path().apply {
      moveTo(0f, r)
      cubicTo(0f, cpOffset, cpOffset, 0f, r, 0f)
      lineTo(w - r, 0f)
      cubicTo(w - cpOffset, 0f, w, cpOffset, w, r)
      lineTo(w, h - r)
      cubicTo(w, h - cpOffset, w - cpOffset, h, w - r, h)
      lineTo(r, h)
      cubicTo(cpOffset, h, 0f, h - cpOffset, 0f, h - r)
      close()
    }
    return Outline.Generic(path)
  }
}

/**
 * Sculpted ui-neumorphism Progress Bar modeled on deep carved tracks with muted steel-slate fill.
 */
@Composable
fun NeuProgressBar(
  progress: Float,
  modifier: Modifier = Modifier,
  height: Dp = 12.dp,
  fillColor: Color = CupertinoBlue
) {
  val palette = LocalNeumorphicPalette.current
  val clamped = progress.coerceIn(0.02f, 1f)
  val pillRadius = height / 2f

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(height)
      .neuRecessed(
        cornerRadius = pillRadius,
        depth = 3.dp,
        cavityColor = palette.surfaceRecessed
      )
      .padding(2.dp)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(clamped)
        .fillMaxHeight()
        .clip(RoundedCornerShape(pillRadius))
        .background(
          brush = Brush.horizontalGradient(
            colors = listOf(
              fillColor.copy(alpha = 0.88f),
              fillColor
            )
          )
        )
    )
  }
}

/**
 * ui-neumorphism Primary Action Button: sculpted steel-slate extruded capsule with opposing
 * soft light & cool shadow that physically depresses into a carved inner-shadow cavity on touch.
 */
@Composable
fun IosSquircleButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  containerColor: Color = MaterialTheme.colorScheme.primary,
  contentColor: Color = MaterialTheme.colorScheme.onPrimary,
  cornerRadius: Dp = 16.dp
) {
  val palette = LocalNeumorphicPalette.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (isPressed && enabled) 0.985f else 1f,
    animationSpec = spring(stiffness = Spring.StiffnessMedium),
    label = "neuPrimaryBtnScale"
  )
  val effectiveRadius = cornerRadius.coerceAtLeast(14.dp)
  val shape = SquircleShape(cornerRadius = effectiveRadius, smoothing = 0.86f)
  val effectiveFill = if (enabled) containerColor else palette.surfaceRecessed
  val effectiveContent = if (enabled) contentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .padding(vertical = 3.dp, horizontal = 2.dp)
      .scale(scale)
      .heightIn(min = 52.dp)
      .then(
        when {
          !enabled -> Modifier.neuRecessed(
            cornerRadius = effectiveRadius,
            depth = 3.dp,
            cavityColor = palette.surfaceRecessed
          )
          isPressed -> Modifier.neuRecessed(
            cornerRadius = effectiveRadius,
            depth = 4.5.dp,
            cavityColor = containerColor.copy(alpha = 0.90f)
          )
          else -> Modifier.neuRaised(
            cornerRadius = effectiveRadius,
            elevation = 6.dp,
            surfaceColor = effectiveFill,
            accentGlowColor = containerColor.copy(alpha = if (palette.isDark) 0.34f else 0.22f),
            drawBorderRim = true
          )
        }
      )
      .clip(shape)
      .clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
      )
      .padding(horizontal = 18.dp, vertical = 13.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = effectiveContent,
          modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
      }
      Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = effectiveContent,
        letterSpacing = (-0.15).sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * ui-neumorphism Monochromatic Raised Clay Button (#E0E5EC / #262A30) that depresses
 * cleanly into a carved inset cavity when pressed.
 */
@Composable
fun IosSecondarySquircleButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  tintColor: Color = MaterialTheme.colorScheme.primary,
  cornerRadius: Dp = 15.dp
) {
  val palette = LocalNeumorphicPalette.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (isPressed && enabled) 0.985f else 1f,
    animationSpec = spring(stiffness = Spring.StiffnessMedium),
    label = "neuSecondaryBtnScale"
  )
  val effectiveRadius = cornerRadius.coerceAtLeast(13.dp)
  val shape = SquircleShape(cornerRadius = effectiveRadius, smoothing = 0.86f)
  val fg = if (enabled) tintColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .padding(vertical = 3.dp, horizontal = 2.dp)
      .scale(scale)
      .heightIn(min = 48.dp)
      .then(
        when {
          !enabled -> Modifier.neuRecessed(cornerRadius = effectiveRadius, depth = 2.5.dp)
          isPressed -> Modifier.neuRecessed(
            cornerRadius = effectiveRadius,
            depth = 4.5.dp,
            cavityColor = palette.surfaceRecessed
          )
          else -> Modifier.neuRaised(
            cornerRadius = effectiveRadius,
            elevation = 5.5.dp,
            surfaceColor = palette.surfaceRaised
          )
        }
      )
      .clip(shape)
      .clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
      )
      .padding(horizontal = 14.dp, vertical = 11.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = fg,
          modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
      }
      Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = fg,
        letterSpacing = (-0.1).sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * Sculpted ui-neumorphism Extruded Clay Medallion with opposing soft light and cool shadow.
 */
@Composable
fun IosSquircleIconBadge(
  icon: ImageVector,
  tint: Color,
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  cornerRadius: Dp = 14.dp
) {
  val palette = LocalNeumorphicPalette.current
  val effectiveRadius = if (cornerRadius >= size / 2f) size / 2f else cornerRadius.coerceAtLeast(11.dp)
  val shape = SquircleShape(cornerRadius = effectiveRadius, smoothing = 0.86f)

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .neuRaised(
        cornerRadius = effectiveRadius,
        elevation = 4.5.dp,
        surfaceColor = palette.surfaceRaised
      )
      .clip(shape)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = tint,
      modifier = Modifier.size(size * 0.50f)
    )
  }
}

/**
 * ui-neumorphism Carved Segmented Control: deeply recessed stone groove housing an
 * extruded monochromatic clay active pill with steel-slate accent label.
 */
@Composable
fun IosSegmentedControl(
  items: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  val palette = LocalNeumorphicPalette.current
  val outerRadius = 18.dp
  val innerRadius = 13.dp

  Box(
    modifier = modifier
      .fillMaxWidth()
      .neuRecessed(
        cornerRadius = outerRadius,
        depth = 4.5.dp,
        cavityColor = palette.surfaceRecessed
      )
      .padding(5.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      items.forEachIndexed { index, label ->
        val isSelected = index == selectedIndex
        val textColor by animateColorAsState(
          targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          label = "neuSegmentText"
        )
        val thumbShape = SquircleShape(cornerRadius = innerRadius, smoothing = 0.86f)

        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 38.dp)
            .then(
              if (isSelected) {
                Modifier.neuRaised(
                  cornerRadius = innerRadius,
                  elevation = 4.dp,
                  surfaceColor = palette.surfaceRaised
                )
              } else {
                Modifier
              }
            )
            .clip(thumbShape)
            .clickable { onSelect(index) }
            .padding(horizontal = 6.dp, vertical = 7.dp)
        ) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = textColor,
            letterSpacing = (-0.1).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}

data class IosSubTabEntry(
  val title: String,
  val icon: ImageVector,
  val badge: String? = null
)

/**
 * Pure ui-neumorphism Tactile Sub-Tab Strip (ZERO harsh neon fills):
 * - Inactive tab = Extruded raised clay pill (#E0E5EC / #262A30)
 * - Selected tab = Deeply carved INSET clay cavity with steel-slate blue icon & bold label
 */
@Composable
fun IosSubTabBar(
  tabs: List<IosSubTabEntry>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
  tagPrefix: String = "sub_tab"
) {
  val palette = LocalNeumorphicPalette.current

  Row(
    modifier = modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 4.dp, vertical = 7.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    tabs.forEachIndexed { idx, tab ->
      val isSelected = idx == selectedIndex
      val tabRadius = 15.dp
      val tabShape = SquircleShape(cornerRadius = tabRadius, smoothing = 0.86f)
      val fgColor by animateColorAsState(
        targetValue = if (isSelected) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "neuSubTabFg"
      )

      Row(
        modifier = Modifier
          .heightIn(min = 42.dp)
          .then(
            if (isSelected) {
              Modifier.neuRecessed(
                cornerRadius = tabRadius,
                depth = 4.5.dp,
                cavityColor = palette.surfaceRecessed
              )
            } else {
              Modifier.neuRaised(
                cornerRadius = tabRadius,
                elevation = 5.dp,
                surfaceColor = palette.surfaceRaised
              )
            }
          )
          .clip(tabShape)
          .clickable { onSelect(idx) }
          .padding(horizontal = 13.dp, vertical = 9.dp)
          .testTag("${tagPrefix}_$idx"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Icon(
          imageVector = tab.icon,
          contentDescription = null,
          tint = fgColor,
          modifier = Modifier.size(15.dp)
        )
        Text(
          text = tab.title,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
          color = fgColor,
          letterSpacing = (-0.1).sp,
          maxLines = 1
        )
        if (!tab.badge.isNullOrBlank()) {
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
              )
              .padding(horizontal = 6.dp, vertical = 1.5.dp)
          ) {
            Text(
              text = tab.badge,
              style = MaterialTheme.typography.labelSmall,
              fontFamily = JetBrainsMonoFamily,
              fontSize = 9.5.sp,
              fontWeight = FontWeight.Bold,
              color = fgColor
            )
          }
        }
      }
    }
  }
}

/**
 * Authentic ui-neumorphism Sculpted Clay Card (#E0E5EC Light / #262A30 Dark)
 * with generous shadow breathing room so opposing bright & cool shadows never clip.
 */
@Composable
fun IosGroupedCard(
  modifier: Modifier = Modifier,
  header: String? = null,
  footer: String? = null,
  accentColor: Color = CupertinoBlue,
  content: @Composable ColumnScope.() -> Unit
) {
  val palette = LocalNeumorphicPalette.current
  val cardRadius = 22.dp
  val cardShape = SquircleShape(cornerRadius = cardRadius, smoothing = 0.86f)

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(9.dp)
  ) {
    if (!header.isNullOrBlank()) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 10.dp)
      ) {
        Box(
          modifier = Modifier
            .size(7.dp)
            .neuRaised(
              cornerRadius = 4.dp,
              elevation = 2.dp,
              surfaceColor = accentColor,
              drawBorderRim = false
            )
            .clip(CircleShape)
            .background(accentColor)
        )
        Text(
          text = header.uppercase(),
          style = MaterialTheme.typography.labelSmall,
          fontSize = 10.5.sp,
          letterSpacing = 1.1.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontWeight = FontWeight.Bold
        )
      }
    }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 5.dp, vertical = 5.dp)
        .neuRaised(
          cornerRadius = cardRadius,
          elevation = 7.dp,
          surfaceColor = palette.surfaceRaised
        )
        .clip(cardShape)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        content = content
      )
    }
    if (!footer.isNullOrBlank()) {
      Text(
        text = footer,
        style = MaterialTheme.typography.bodySmall,
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp)
      )
    }
  }
}

/**
 * Authentic ui-neumorphism Carved Toggle Switch: deeply recessed pill groove
 * with subtle active fill and an extruded spherical clay thumb.
 */
@Composable
fun IosCupertinoSwitch(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  activeTrackColor: Color = CupertinoBlue
) {
  val palette = LocalNeumorphicPalette.current
  val trackColor by animateColorAsState(
    targetValue = when {
      !enabled && checked -> activeTrackColor.copy(alpha = 0.40f)
      !enabled && !checked -> palette.surfaceRecessed.copy(alpha = 0.65f)
      checked -> activeTrackColor.copy(alpha = if (palette.isDark) 0.72f else 0.82f)
      else -> palette.surfaceRecessed
    },
    animationSpec = spring(stiffness = Spring.StiffnessMedium),
    label = "neuSwitchTrack"
  )
  val thumbOffset by animateDpAsState(
    targetValue = if (checked) 22.dp else 0.dp,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioNoBouncy,
      stiffness = Spring.StiffnessMedium
    ),
    label = "neuSwitchThumb"
  )

  Box(
    modifier = modifier
      .size(width = 56.dp, height = 32.dp)
      .neuRecessed(
        cornerRadius = 16.dp,
        depth = 4.dp,
        cavityColor = trackColor
      )
      .clip(CircleShape)
      .clickable(
        enabled = enabled,
        role = Role.Switch,
        interactionSource = remember { MutableInteractionSource() },
        indication = null
      ) {
        onCheckedChange(!checked)
      }
      .padding(3.dp),
    contentAlignment = Alignment.CenterStart
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .offset(x = thumbOffset)
        .size(26.dp)
        .neuRaised(
          cornerRadius = 13.dp,
          elevation = 3.5.dp,
          surfaceColor = palette.surfaceRaised
        )
        .clip(CircleShape)
    ) {
      Box(
        modifier = Modifier
          .size(6.dp)
          .clip(CircleShape)
          .background(
            if (checked) activeTrackColor
            else palette.innerDarkShadow.copy(alpha = 0.45f)
          )
      )
    }
  }
}

@Composable
fun IosSettingToggleRow(
  icon: ImageVector,
  iconTint: Color,
  title: String,
  subtitle: String? = null,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier
) {
  val rowShape = SquircleShape(cornerRadius = 12.dp, smoothing = 0.86f)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clip(rowShape)
      .clickable { onCheckedChange(!checked) }
      .padding(vertical = 8.dp, horizontal = 4.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.weight(1f)
    ) {
      IosSquircleIconBadge(icon = icon, tint = iconTint, size = 38.dp, cornerRadius = 12.dp)
      Column(modifier = Modifier.padding(end = 10.dp)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          letterSpacing = (-0.1).sp,
          color = MaterialTheme.colorScheme.onSurface
        )
        if (!subtitle.isNullOrBlank()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            lineHeight = 16.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
    IosCupertinoSwitch(
      checked = checked,
      onCheckedChange = onCheckedChange
    )
  }
}

/**
 * Embossed two-tone Neumorphic groove divider (1dp cool shadow over 1dp bright catch-light).
 */
@Composable
fun IosInsetDivider() {
  val palette = LocalNeumorphicPalette.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 50.dp, top = 3.dp, bottom = 3.dp)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(0.9.dp)
        .background(palette.hairlineShadow)
    )
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(0.9.dp)
        .background(palette.hairlineHighlight)
    )
  }
}

/**
 * Independent Sculpted Bottom Dock Tile — directly modeled on the reference Neumorphic dock:
 * - Unselected tab = Extruded raised clay square tile (#E0E5EC / #262A30)
 * - Selected tab = Deeply carved INSET clay cavity with steel-slate blue icon & bold label
 */
@Composable
fun RowScope.IosSquircleTabItem(
  selected: Boolean,
  label: String,
  icon: ImageVector,
  badgeText: String? = null,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val palette = LocalNeumorphicPalette.current
  val tint by animateColorAsState(
    targetValue = if (selected) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    },
    label = "neuTabTint"
  )
  val tileRadius = 16.dp
  val itemShape = SquircleShape(cornerRadius = tileRadius, smoothing = 0.86f)

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = modifier
      .weight(1f)
      .heightIn(min = 58.dp)
      .then(
        if (selected) {
          Modifier.neuRecessed(
            cornerRadius = tileRadius,
            depth = 5.dp,
            cavityColor = palette.surfaceRecessed
          )
        } else {
          Modifier.neuRaised(
            cornerRadius = tileRadius,
            elevation = 6.dp,
            surfaceColor = palette.surfaceRaised
          )
        }
      )
      .clip(itemShape)
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp, horizontal = 4.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = tint,
        modifier = Modifier.size(21.dp)
      )
      if (!badgeText.isNullOrBlank()) {
        val badgeShape = SquircleShape(cornerRadius = 6.dp, smoothing = 0.88f)
        Surface(
          color = MaterialTheme.colorScheme.primary,
          shape = badgeShape
        ) {
          Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(3.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontSize = 10.sp,
      letterSpacing = (-0.1).sp,
      fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
      color = tint,
      softWrap = false,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * Sculpted Tactile Rotary Credibility Dial Gauge — extruded circular clay bezel
 * surrounding a carved recessed dial basin with precision tick marks and muted score arc.
 */
@Composable
fun IosCredibilityArcGauge(
  score: Int,
  modifier: Modifier = Modifier,
  size: Dp = 84.dp,
  label: String = "CREDIBILITY"
) {
  val palette = LocalNeumorphicPalette.current
  val clampedScore = score.coerceIn(0, 100)
  val animatedSweep by animateFloatAsState(
    targetValue = (clampedScore / 100f) * 240f,
    animationSpec = spring(stiffness = Spring.StiffnessLow),
    label = "neuCredibilitySweep"
  )
  val arcColor = when {
    clampedScore >= 75 -> CupertinoGreen
    clampedScore >= 55 -> CupertinoBlue
    clampedScore >= 40 -> CupertinoAmber
    else -> CupertinoRed
  }
  val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)

  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .size(size)
      .neuRaised(
        cornerRadius = size / 2f,
        elevation = 6.dp,
        surfaceColor = palette.surfaceRaised
      )
      .padding(6.dp)
      .neuRecessed(
        cornerRadius = (size - 12.dp) / 2f,
        depth = 3.5.dp,
        cavityColor = palette.surfaceRecessed
      )
  ) {
    Canvas(modifier = Modifier.size(size - 12.dp)) {
      val strokePx = 6.dp.toPx()
      val diameter = min(this.size.width, this.size.height) - strokePx * 1.9f
      val topLeft = Offset(
        (this.size.width - diameter) / 2f,
        (this.size.height - diameter) / 2f
      )
      val arcSize = Size(diameter, diameter)

      val centerX = this.size.width / 2f
      val centerY = this.size.height / 2f
      val tickRadius = min(this.size.width, this.size.height) / 2f - 2.dp.toPx()
      for (i in 0..16) {
        val angleDeg = 150.0 + (240.0 * i / 16.0)
        val rad = angleDeg * PI / 180.0
        val dotX = centerX + (tickRadius * cos(rad)).toFloat()
        val dotY = centerY + (tickRadius * sin(rad)).toFloat()
        drawCircle(
          color = if (i * 100 / 16 <= clampedScore) arcColor.copy(alpha = 0.80f) else tickColor,
          radius = if (i % 4 == 0) 1.5.dp.toPx() else 0.9.dp.toPx(),
          center = Offset(dotX, dotY)
        )
      }

      drawArc(
        color = palette.innerDarkShadow.copy(alpha = 0.28f),
        startAngle = 150f,
        sweepAngle = 240f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokePx, cap = StrokeCap.Round)
      )

      drawArc(
        brush = Brush.sweepGradient(
          colors = listOf(arcColor.copy(alpha = 0.82f), arcColor, CupertinoIndigo)
        ),
        startAngle = 150f,
        sweepAngle = animatedSweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokePx, cap = StrokeCap.Round)
      )
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = "$clampedScore",
        style = MaterialTheme.typography.titleMedium,
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.ExtraBold,
        color = arcColor
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 7.sp,
        letterSpacing = 0.5.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
