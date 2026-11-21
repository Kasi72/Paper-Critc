package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.data.model.PaperReview
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates publication-grade multi-page A4 PDF referee dossiers using native Android PdfDocument
 * and exports/shares them via FileProvider or Storage Access Framework without storage permissions.
 *
 * Uses [DossierFormattingEngine] to parse raw stage text into semantic blocks (SubHeaders,
 * color-coded SeverityCards, Callouts, hanging-indented Bullets, and shaded Equation strips)
 * so both new and previously saved dossiers render with clean academic typography.
 */
object PdfReportExporter {

  private const val PAGE_WIDTH = 595 // A4 width in PostScript points (72 dpi)
  private const val PAGE_HEIGHT = 842 // A4 height in PostScript points
  private const val MARGIN_H = 40f
  private const val MARGIN_TOP_FIRST = 38f
  private const val MARGIN_TOP_SUBSEQUENT = 48f
  private const val MARGIN_BOTTOM = 46f
  private val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_H * 2)

  private data class BadgePalette(
    val bgColor: Int,
    val borderColor: Int,
    val accentColor: Int,
    val textColor: Int
  )

  suspend fun createShareablePdfUri(context: Context, review: PaperReview): Uri =
    withContext(Dispatchers.IO) {
      val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
      val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
      val safeSlug = normalizedTitle
        .lowercase(Locale.US)
        .replace(Regex("""[^a-z0-9]+"""), "_")
        .trim('_')
        .take(40)
        .ifBlank { "paper_critic_review" }
      val outFile = File(exportsDir, "${safeSlug}_referee_report.pdf")
      FileOutputStream(outFile).use { stream ->
        renderReviewToPdfStream(review, stream)
      }
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        outFile
      )
    }

  suspend fun exportAndSharePdf(context: Context, review: PaperReview): Result<Uri> = runCatching {
    val uri = createShareablePdfUri(context, review)
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_SUBJECT, "Paper Critic Referee Report: $normalizedTitle")
      putExtra(
        Intent.EXTRA_TEXT,
        "Attached is the 30-stage adversarial referee dossier for \"$normalizedTitle\" " +
          "(Credibility: ${review.credibilityScore}/100 • Verdict: ${review.verdict.replace('_', ' ')})."
      )
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "Share Referee Report (PDF)")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    uri
  }

  suspend fun writePdfToUri(context: Context, review: PaperReview, targetUri: Uri): Result<Unit> =
    withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openOutputStream(targetUri)?.use { out ->
          renderReviewToPdfStream(review, out)
        } ?: throw IllegalStateException("Could not open destination output stream for PDF.")
        Unit
      }
    }

  fun renderReviewToPdfStream(review: PaperReview, outputStream: OutputStream) {
    val document = PdfDocument()
    val rubric = review.toRubric()
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
    val runningTitle = if (normalizedTitle.length > 64) {
      normalizedTitle.take(61).trimEnd() + "…"
    } else {
      normalizedTitle
    }
    val verdictUpper = review.verdict.replace('_', ' ').uppercase(Locale.US)
    val verdictColor = verdictAccentColor(review.verdict)

    // --- Reusable Paints ---
    val navyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42) }
    val scoreBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 41, 59) }
    val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 98, 227) }
    val verdictPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = verdictColor }
    val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 252) }
    val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(218, 225, 237)
      style = Paint.Style.STROKE
      strokeWidth = 0.85f
    }
    val trackBarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 232, 240) }
    val eqBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 249) }
    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(214, 222, 234)
      strokeWidth = 0.75f
    }
    val sectionTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 246, 255) }
    val subTagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 235, 248) }

    val bannerEyebrowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(148, 163, 184)
      textSize = 8.2f
      letterSpacing = 0.08f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val bannerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 14f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val bannerSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(203, 213, 225)
      textSize = 8.5f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    val verdictPillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 8.8f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val scoreBigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 21f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val scoreSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(148, 163, 184)
      textSize = 7.5f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val paperTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 14.5f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(71, 85, 105)
      textSize = 9.2f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val sectionNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 8.5f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val sectionHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 10.5f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val sectionStagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 98, 227)
      textSize = 7.8f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val subHeaderTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 82, 204)
      textSize = 7.8f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val subHeaderTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 9.6f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      textSize = 9.2f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val bodyBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 9.2f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val smallMutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(100, 116, 139)
      textSize = 8.0f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val bulletMarkerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 98, 227)
      textSize = 8.6f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      textSize = 8.4f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    val headerFooterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(100, 116, 139)
      textSize = 7.8f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
    var canvas: Canvas = page.canvas
    var cursorY = MARGIN_TOP_FIRST

    fun drawRunningHeader(targetCanvas: Canvas) {
      val headerY = 26f
      targetCanvas.drawText("REFEREE DOSSIER  •  $runningTitle", MARGIN_H, headerY, headerFooterPaint)
      val verdictTag = "$verdictUpper (${review.credibilityScore}/100)"
      val vw = headerFooterPaint.measureText(verdictTag)
      targetCanvas.drawText(verdictTag, PAGE_WIDTH - MARGIN_H - vw, headerY, headerFooterPaint)
      targetCanvas.drawLine(MARGIN_H, headerY + 7f, PAGE_WIDTH - MARGIN_H, headerY + 7f, dividerPaint)
    }

    fun drawFooter(targetCanvas: Canvas, pgNum: Int) {
      val footerY = PAGE_HEIGHT - 22f
      targetCanvas.drawLine(MARGIN_H, footerY - 12f, PAGE_WIDTH - MARGIN_H, footerY - 12f, dividerPaint)
      targetCanvas.drawText(
        "Paper Critic 30-Stage Peer-Review Engine  •  $dateStr",
        MARGIN_H,
        footerY,
        headerFooterPaint
      )
      val pageLabel = "Page $pgNum"
      val w = headerFooterPaint.measureText(pageLabel)
      targetCanvas.drawText(pageLabel, PAGE_WIDTH - MARGIN_H - w, footerY, headerFooterPaint)
    }

    fun startNewPage() {
      drawFooter(canvas, pageNumber)
      document.finishPage(page)
      pageNumber++
      page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
      canvas = page.canvas
      drawRunningHeader(canvas)
      cursorY = MARGIN_TOP_SUBSEQUENT
    }

    fun ensureSpace(neededHeight: Float) {
      if (cursorY + neededHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
        startNewPage()
      }
    }

    fun buildLayout(text: String, paint: TextPaint, width: Float, lineAdd: Float = 2.6f): StaticLayout {
      val safeText = text.ifBlank { " " }
      return StaticLayout.Builder
        .obtain(safeText, 0, safeText.length, paint, width.toInt().coerceAtLeast(80))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(lineAdd, 1.0f)
        .build()
    }

    fun drawLayoutPaginated(
      text: String,
      paint: TextPaint,
      width: Float = CONTENT_WIDTH,
      leftX: Float = MARGIN_H,
      lineAdd: Float = 2.6f,
      leftAccentColor: Int? = null,
      accentX: Float = MARGIN_H
    ) {
      val clean = text.trim()
      if (clean.isEmpty()) return
      val layout = buildLayout(clean, paint, width, lineAdd)
      val accentPaint = leftAccentColor?.let { c ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
      }
      for (lineIdx in 0 until layout.lineCount) {
        val lineTop = layout.getLineTop(lineIdx)
        val lineBottom = layout.getLineBottom(lineIdx)
        val lineHeight = (lineBottom - lineTop).toFloat()
        if (cursorY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
          startNewPage()
        }
        val lineStart = layout.getLineStart(lineIdx)
        val lineEnd = layout.getLineEnd(lineIdx)
        val lineStr = clean.substring(lineStart, lineEnd).trimEnd('\n')
        val baselineOffset = (layout.getLineBaseline(lineIdx) - lineTop).toFloat()
        if (accentPaint != null) {
          canvas.drawRect(accentX, cursorY, accentX + 3f, cursorY + lineHeight, accentPaint)
        }
        canvas.drawText(lineStr, leftX, cursorY + baselineOffset, paint)
        cursorY += lineHeight
      }
    }

    fun drawBoxedCard(
      badgeText: String,
      bodyText: String,
      palette: BadgePalette,
      isCalloutTitle: Boolean = false
    ) {
      val padH = 11f
      val padV = 8f
      val innerLeft = MARGIN_H + 3.5f + padH
      val innerWidth = CONTENT_WIDTH - 3.5f - (padH * 2)
      val bodyLayout = buildLayout(bodyText.trim(), bodyPaint, innerWidth, 2.5f)
      val headerHeight = if (isCalloutTitle) 14f else 17f
      val totalHeight = padV + headerHeight + bodyLayout.height + padV
      val maxSinglePageHeight = PAGE_HEIGHT - MARGIN_TOP_SUBSEQUENT - MARGIN_BOTTOM - 24f

      if (totalHeight <= maxSinglePageHeight) {
        ensureSpace(totalHeight + 4f)
        val boxTop = cursorY
        val boxRect = RectF(MARGIN_H, boxTop, PAGE_WIDTH - MARGIN_H, boxTop + totalHeight)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.bgColor }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = palette.borderColor
          style = Paint.Style.STROKE
          strokeWidth = 0.75f
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentColor }

        canvas.drawRoundRect(boxRect, 6f, 6f, bgPaint)
        canvas.drawRoundRect(boxRect, 6f, 6f, borderPaint)
        canvas.drawRoundRect(
          RectF(MARGIN_H, boxTop, MARGIN_H + 3.8f, boxTop + totalHeight),
          2f,
          2f,
          accentPaint
        )

        if (isCalloutTitle) {
          val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textColor
            textSize = 8.4f
            letterSpacing = 0.04f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
          }
          canvas.drawText(badgeText.uppercase(Locale.US), innerLeft, boxTop + padV + 9f, titlePaint)
        } else {
          val pillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 7.4f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
          }
          val pillW = pillTextPaint.measureText(badgeText) + 12f
          val pillRect = RectF(innerLeft, boxTop + padV - 1f, innerLeft + pillW, boxTop + padV + 12f)
          canvas.drawRoundRect(pillRect, 3.5f, 3.5f, accentPaint)
          canvas.drawText(badgeText, innerLeft + 6f, boxTop + padV + 8.5f, pillTextPaint)
        }

        val textStartY = boxTop + padV + headerHeight
        canvas.save()
        canvas.translate(innerLeft, textStartY)
        bodyLayout.draw(canvas)
        canvas.restore()

        cursorY = boxTop + totalHeight + 6f
      } else {
        // Fallback for extraordinarily long items (>1 page): paginated with left accent bar
        ensureSpace(34f)
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = palette.textColor
          textSize = 8.8f
          typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(badgeText.uppercase(Locale.US), MARGIN_H + 9f, cursorY + 10f, headerPaint)
        cursorY += 15f
        drawLayoutPaginated(
          text = bodyText,
          paint = bodyPaint,
          width = CONTENT_WIDTH - 10f,
          leftX = MARGIN_H + 10f,
          leftAccentColor = palette.accentColor,
          accentX = MARGIN_H
        )
        cursorY += 6f
      }
    }

    fun drawStructuredBlocks(rawContent: String, stripInternalTags: Boolean = true) {
      val blocks = DossierFormattingEngine.parseStructuredBlocks(
        raw = rawContent,
        stripInternalTags = stripInternalTags
      )
      if (blocks.isEmpty()) {
        drawLayoutPaginated("No additional findings recorded for this stage.", smallMutedPaint)
        cursorY += 4f
        return
      }

      for (block in blocks) {
        when (block) {
          is ReportBlock.SubHeader -> {
            ensureSpace(34f)
            cursorY += 4f
            var xOffset = MARGIN_H
            if (block.tag.isNotBlank()) {
              val tagUpper = block.tag.uppercase(Locale.US)
              val tagW = subHeaderTagPaint.measureText(tagUpper) + 10f
              val tagRect = RectF(xOffset, cursorY, xOffset + tagW, cursorY + 14f)
              canvas.drawRoundRect(tagRect, 3.5f, 3.5f, subTagBgPaint)
              canvas.drawText(tagUpper, xOffset + 5f, cursorY + 10f, subHeaderTagPaint)
              xOffset += tagW + 7f
            }
            val availW = (PAGE_WIDTH - MARGIN_H - xOffset).coerceAtLeast(140f)
            val titleLayout = buildLayout(block.title, subHeaderTitlePaint, availW, 1.5f)
            canvas.save()
            canvas.translate(xOffset, cursorY + 1f)
            titleLayout.draw(canvas)
            canvas.restore()
            cursorY += maxOf(16f, titleLayout.height.toFloat() + 3f) + 3f
          }

          is ReportBlock.SeverityCard -> {
            drawBoxedCard(
              badgeText = block.label,
              bodyText = block.body,
              palette = severityPalette(block.level),
              isCalloutTitle = false
            )
          }

          is ReportBlock.Callout -> {
            drawBoxedCard(
              badgeText = block.title,
              bodyText = block.body,
              palette = calloutPalette(block.tone),
              isCalloutTitle = true
            )
          }

          is ReportBlock.Bullet -> {
            ensureSpace(18f)
            val gutterW = if (block.marker.length > 3) 34f else 20f
            val firstLineBaselineOffset = 9.5f
            canvas.drawText(block.marker, MARGIN_H + 2f, cursorY + firstLineBaselineOffset, bulletMarkerPaint)
            drawLayoutPaginated(
              text = block.body,
              paint = bodyPaint,
              width = CONTENT_WIDTH - gutterW,
              leftX = MARGIN_H + gutterW,
              lineAdd = 2.5f
            )
            cursorY += 4.5f
          }

          is ReportBlock.Equation -> {
            val innerW = CONTENT_WIDTH - 18f
            val eqLayout = buildLayout(block.expression, monoPaint, innerW, 2.0f)
            val boxH = eqLayout.height + 12f
            ensureSpace(boxH + 4f)
            val rect = RectF(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY + boxH)
            canvas.drawRoundRect(rect, 4.5f, 4.5f, eqBgPaint)
            canvas.drawRoundRect(rect, 4.5f, 4.5f, cardBorderPaint)
            canvas.save()
            canvas.translate(MARGIN_H + 9f, cursorY + 6f)
            eqLayout.draw(canvas)
            canvas.restore()
            cursorY += boxH + 5f
          }

          is ReportBlock.AuditTable -> {
            val cols = block.headers.size.coerceAtLeast(1)
            val colRatios = when (cols) {
              4 -> floatArrayOf(0.20f, 0.24f, 0.11f, 0.45f)
              3 -> floatArrayOf(0.26f, 0.14f, 0.60f)
              2 -> floatArrayOf(0.32f, 0.68f)
              else -> FloatArray(cols) { 1f / cols.toFloat() }
            }
            val colWidths = FloatArray(cols) { idx -> CONTENT_WIDTH * colRatios[idx] }
            val tableHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.WHITE
              textSize = 7.6f
              typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val tableCellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.rgb(30, 41, 59)
              textSize = 8.1f
              typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val tableCellBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.rgb(15, 23, 42)
              textSize = 8.1f
              typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val zebraLightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val zebraAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 250, 252) }
            val statusTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.WHITE
              textSize = 7.0f
              typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }

            fun drawTableHeaderRow() {
              val headerPadV = 5f
              val headerLayouts = block.headers.mapIndexed { c, h ->
                buildLayout(h.uppercase(Locale.US), tableHeaderPaint, (colWidths[c] - 8f).coerceAtLeast(24f), 1.2f)
              }
              val headerH = (headerLayouts.maxOfOrNull { it.height } ?: 10).toFloat() + headerPadV * 2
              ensureSpace(headerH + 22f)
              val top = cursorY
              canvas.drawRoundRect(
                RectF(MARGIN_H, top, PAGE_WIDTH - MARGIN_H, top + headerH),
                4f,
                4f,
                navyBgPaint
              )
              var x = MARGIN_H
              headerLayouts.forEachIndexed { c, layout ->
                canvas.save()
                canvas.translate(x + 4f, top + headerPadV)
                layout.draw(canvas)
                canvas.restore()
                x += colWidths[c]
              }
              cursorY = top + headerH
            }

            drawTableHeaderRow()

            block.rows.forEachIndexed { rowIdx, rowCells ->
              val padV = 4.5f
              val cellLayouts = rowCells.mapIndexed { c, cellText ->
                val p = if (c == 0) tableCellBoldPaint else tableCellPaint
                buildLayout(cellText, p, (colWidths[c] - 8f).coerceAtLeast(24f), 1.6f)
              }
              val rowH = maxOf(18f, (cellLayouts.maxOfOrNull { it.height } ?: 10).toFloat() + padV * 2)
              if (cursorY + rowH > PAGE_HEIGHT - MARGIN_BOTTOM) {
                startNewPage()
                drawTableHeaderRow()
              }

              val top = cursorY
              val rowRect = RectF(MARGIN_H, top, PAGE_WIDTH - MARGIN_H, top + rowH)
              canvas.drawRect(rowRect, if (rowIdx % 2 == 0) zebraLightPaint else zebraAltPaint)
              canvas.drawLine(MARGIN_H, top + rowH, PAGE_WIDTH - MARGIN_H, top + rowH, dividerPaint)

              var x = MARGIN_H
              rowCells.forEachIndexed { c, cellText ->
                val upperCell = cellText.trim().uppercase(Locale.US)
                val isStatusCell = upperCell in setOf("PASS", "WARN", "FAIL", "INFO", "CRITICAL", "MAJOR", "MINOR")
                if (isStatusCell) {
                  val pillColor = when (upperCell) {
                    "PASS" -> Color.rgb(22, 163, 74)
                    "WARN", "MAJOR" -> Color.rgb(217, 119, 6)
                    "FAIL", "CRITICAL" -> Color.rgb(220, 38, 38)
                    else -> Color.rgb(79, 70, 229)
                  }
                  val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillColor }
                  val pw = (statusTextPaint.measureText(upperCell) + 9f).coerceAtMost(colWidths[c] - 6f)
                  val pillTop = top + padV
                  canvas.drawRoundRect(RectF(x + 3f, pillTop, x + 3f + pw, pillTop + 12.5f), 3f, 3f, pillPaint)
                  canvas.drawText(upperCell, x + 7.5f, pillTop + 8.8f, statusTextPaint)
                } else {
                  canvas.save()
                  canvas.translate(x + 4f, top + padV)
                  cellLayouts[c].draw(canvas)
                  canvas.restore()
                }
                x += colWidths[c]
              }
              cursorY = top + rowH
            }
            cursorY += 7f
          }

          is ReportBlock.Paragraph -> {
            drawLayoutPaginated(
              text = block.text,
              paint = bodyPaint,
              width = CONTENT_WIDTH,
              leftX = MARGIN_H,
              lineAdd = 2.6f
            )
            cursorY += 5.5f
          }
        }
      }
    }

    fun drawSectionHeader(sectionNum: String, title: String, stageTag: String) {
      ensureSpace(48f)
      cursorY += 6f
      val barTop = cursorY
      val barH = 24f
      val barRect = RectF(MARGIN_H, barTop, PAGE_WIDTH - MARGIN_H, barTop + barH)
      canvas.drawRoundRect(barRect, 5f, 5f, sectionTintPaint)

      // Number badge on left
      val badgeRect = RectF(MARGIN_H, barTop, MARGIN_H + 26f, barTop + barH)
      canvas.drawRoundRect(badgeRect, 5f, 5f, bluePaint)
      val numW = sectionNumPaint.measureText(sectionNum)
      canvas.drawText(sectionNum, MARGIN_H + (26f - numW) / 2f, barTop + 15.5f, sectionNumPaint)

      // Section title
      canvas.drawText(title, MARGIN_H + 34f, barTop + 15.5f, sectionHeaderPaint)

      // Right-aligned stage code
      if (stageTag.isNotBlank()) {
        val stW = sectionStagePaint.measureText(stageTag)
        canvas.drawText(stageTag, PAGE_WIDTH - MARGIN_H - stW - 8f, barTop + 15f, sectionStagePaint)
      }

      cursorY = barTop + barH + 9f
    }

    // =========================================================================
    // 1. EXECUTIVE MASTHEAD BANNER (Page 1)
    // =========================================================================
    val bannerH = 78f
    val bannerRect = RectF(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY + bannerH)
    canvas.drawRoundRect(bannerRect, 9f, 9f, navyBgPaint)

    // Left masthead typography + color-coded verdict pill
    canvas.drawText("PAPER CRITIC  •  30-STAGE PEER-REVIEW DOSSIER", MARGIN_H + 15f, cursorY + 19f, bannerEyebrowPaint)
    canvas.drawText("IS IT CREDIBLE?  REFEREE REPORT", MARGIN_H + 15f, cursorY + 37f, bannerTitlePaint)

    val pillW = verdictPillTextPaint.measureText(verdictUpper) + 18f
    val pillTop = cursorY + 46f
    canvas.drawRoundRect(
      RectF(MARGIN_H + 15f, pillTop, MARGIN_H + 15f + pillW, pillTop + 19f),
      4.5f,
      4.5f,
      verdictPillPaint
    )
    canvas.drawText(verdictUpper, MARGIN_H + 24f, pillTop + 12.8f, verdictPillTextPaint)

    val trackText = if (review.isEmpirical) "EMPIRICAL TRACK" else "THEORETICAL TRACK"
    canvas.drawText(
      "$trackText  •  CONFIDENCE ${rubric.refereeConfidence}/5  •  $dateStr",
      MARGIN_H + 15f + pillW + 10f,
      pillTop + 12.5f,
      bannerSubPaint
    )

    // Right-aligned Credibility Score Badge Box
    val scoreBoxW = 84f
    val scoreBoxLeft = PAGE_WIDTH - MARGIN_H - scoreBoxW - 12f
    val scoreBoxTop = cursorY + 11f
    val scoreBoxRect = RectF(scoreBoxLeft, scoreBoxTop, scoreBoxLeft + scoreBoxW, cursorY + bannerH - 11f)
    canvas.drawRoundRect(scoreBoxRect, 7f, 7f, scoreBoxPaint)
    val scoreAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scoreColor(review.credibilityScore.toInt()) }
    canvas.drawRoundRect(
      RectF(scoreBoxLeft, scoreBoxTop, scoreBoxLeft + scoreBoxW, scoreBoxTop + 3.5f),
      2f,
      2f,
      scoreAccentPaint
    )

    val scoreText = "${review.credibilityScore}"
    val scoreW = scoreBigPaint.measureText(scoreText)
    canvas.drawText(scoreText, scoreBoxLeft + (scoreBoxW - scoreW) / 2f, scoreBoxTop + 31f, scoreBigPaint)
    val subScoreText = "/ 100 CREDIBILITY"
    val subW = scoreSubPaint.measureText(subScoreText)
    canvas.drawText(subScoreText, scoreBoxLeft + (scoreBoxW - subW) / 2f, scoreBoxTop + 46f, scoreSubPaint)

    cursorY += bannerH + 14f

    // =========================================================================
    // 2. MANUSCRIPT TITLE & CLEAN CITATION METADATA
    // =========================================================================
    drawLayoutPaginated(normalizedTitle, paperTitlePaint, CONTENT_WIDTH, MARGIN_H, 2.2f)
    cursorY += 3f

    val metaParts = buildList {
      val auth = review.authors.trim()
      val yr = review.publicationYear.trim()
      if (auth.isNotBlank() && yr.isNotBlank()) add("$auth ($yr)")
      else if (auth.isNotBlank()) add(auth)
      else if (yr.isNotBlank()) add("($yr)")

      val venue = review.venueOrCitation.trim()
      if (venue.isNotBlank()) add(venue)
    }
    val metaLine = if (metaParts.isNotEmpty()) {
      metaParts.joinToString("  •  ")
    } else {
      "Uploaded Academic Manuscript  •  Independent Referee Evaluation"
    }
    drawLayoutPaginated(metaLine, metaPaint, CONTENT_WIDTH, MARGIN_H, 1.8f)
    cursorY += 8f

    // =========================================================================
    // 3. STRUCTURED 6-AXIS WEIGHTED REVIEW RUBRIC CARD
    // =========================================================================
    val criteria = rubric.criteriaList()
    val rowHeight = 29f
    val rubricBoxH = 34f + criteria.size * rowHeight + 6f
    ensureSpace(rubricBoxH + 8f)

    val rubricTop = cursorY
    val rubricRect = RectF(MARGIN_H, rubricTop, PAGE_WIDTH - MARGIN_H, rubricTop + rubricBoxH)
    canvas.drawRoundRect(rubricRect, 7f, 7f, cardBgPaint)
    canvas.drawRoundRect(rubricRect, 7f, 7f, cardBorderPaint)

    canvas.drawText(
      "6-AXIS WEIGHTED PEER-REVIEW RUBRIC",
      MARGIN_H + 12f,
      rubricTop + 18f,
      subHeaderTitlePaint
    )
    val confRight = "WEIGHTED SCORE: ${review.credibilityScore}/100  •  CONFIDENCE: ${rubric.refereeConfidence}/5"
    val confRightW = sectionStagePaint.measureText(confRight)
    canvas.drawText(confRight, PAGE_WIDTH - MARGIN_H - confRightW - 12f, rubricTop + 17.5f, sectionStagePaint)
    canvas.drawLine(MARGIN_H + 12f, rubricTop + 25f, PAGE_WIDTH - MARGIN_H - 12f, rubricTop + 25f, dividerPaint)

    var rowY = rubricTop + 38f
    criteria.forEach { item ->
      canvas.drawText("${item.criterion} (${item.weightPercent}%)", MARGIN_H + 12f, rowY, bodyBoldPaint)

      val barStartX = MARGIN_H + 168f
      val barW = 286f
      canvas.drawRoundRect(
        RectF(barStartX, rowY - 7.5f, barStartX + barW, rowY - 1.5f),
        3f,
        3f,
        trackBarBgPaint
      )
      val fraction = (item.score.toFloat() / item.maxScore.toFloat()).coerceIn(0f, 1f)
      val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = axisScoreColor(item.score) }
      canvas.drawRoundRect(
        RectF(barStartX, rowY - 7.5f, barStartX + (barW * fraction).coerceAtLeast(4f), rowY - 1.5f),
        3f,
        3f,
        barFillPaint
      )

      val scoreLabel = "${item.score}/${item.maxScore}"
      val scoreLabelPaint = TextPaint(monoPaint).apply {
        color = axisScoreColor(item.score)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      }
      canvas.drawText(scoreLabel, barStartX + barW + 12f, rowY, scoreLabelPaint)

      // Clean 1-line rationale snippet beneath each rubric row
      val cleanJust = DossierFormattingEngine.cleanAcademicProse(item.justification)
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
      val truncatedJust = if (cleanJust.length > 106) cleanJust.take(103).trimEnd() + "…" else cleanJust
      canvas.drawText(truncatedJust, MARGIN_H + 12f, rowY + 11.5f, smallMutedPaint)

      rowY += rowHeight
    }
    cursorY = rubricTop + rubricBoxH + 10f

    // =========================================================================
    // 4. LOGICAL, COHERENT 9-SECTION DOSSIER FLOW
    // =========================================================================

    // Section 01: Manuscript Profile & Claimed Contributions
    drawSectionHeader("01", "MANUSCRIPT PROFILE & CLAIMED CONTRIBUTIONS", "STAGE 00a–00c")
    val profileContent = buildString {
      if (review.abstractSummary.isNotBlank()) {
        appendLine("Executive Assessment: ${review.abstractSummary.trim()}")
        appendLine()
      }
      if (review.researchQuestion.isNotBlank()) {
        appendLine("[STAGE 00b — RESEARCH QUESTION]")
        appendLine(review.researchQuestion.trim())
        appendLine()
      }
      if (review.keyMethodology.isNotBlank()) {
        appendLine("[STAGE 00b — CORE METHODOLOGY & DESIGN]")
        appendLine(review.keyMethodology.trim())
        appendLine()
      }
      if (review.coreContributions.isNotBlank()) {
        appendLine("[STAGE 00c — CLAIMED CONTRIBUTIONS]")
        appendLine(review.coreContributions.trim())
      }
    }
    drawStructuredBlocks(profileContent, stripInternalTags = true)

    // Section 02: Editorial Decision, Credibility Assessment & Revision Roadmap
    drawSectionHeader("02", "EDITORIAL DECISION, CREDIBILITY & REVISION ROADMAP", "STAGE 04a / 06 / 08a–08b")
    drawStructuredBlocks(review.editorNote, stripInternalTags = true)

    // Section 03: Adjudicated Findings Matrix & Blue Team Verification
    drawSectionHeader("03", "ADJUDICATED FINDINGS MATRIX & BLUE TEAM VERIFICATION", "STAGE 02a–03c")
    drawStructuredBlocks(review.blueTeamVerification, stripInternalTags = true)

    // Section 04: Quantitative, Statistical & Mathematical Audit (Proportional text + shaded Equation strips!)
    if (review.mathAuditReport.isNotBlank()) {
      drawSectionHeader("04", "QUANTITATIVE, STATISTICAL & MATHEMATICAL AUDIT", "STAGE 01e–01fd")
      drawStructuredBlocks(review.mathAuditReport, stripInternalTags = true)
    }

    // Section 05: Reproducibility, Provenance & Methodology X-Ray
    if (review.codeAuditReport.isNotBlank()) {
      drawSectionHeader("05", "REPRODUCIBILITY, PROVENANCE & METHODOLOGY X-RAY", "STAGE 01i–01n & 07b")
      drawStructuredBlocks(review.codeAuditReport, stripInternalTags = true)
    }

    // Section 06: Red Team Adversarial Transcript
    if (review.redTeamCritique.isNotBlank()) {
      drawSectionHeader("06", "RED TEAM ADVERSARIAL TRANSCRIPT", "STAGE 01a–01g")
      drawStructuredBlocks(review.redTeamCritique, stripInternalTags = true)
    }

    // Section 07: Anticipated Reviewer 2 Interrogation & Robustness Checks
    if (review.reviewerQuestions.isNotBlank()) {
      drawSectionHeader("07", "ANTICIPATED REVIEWER 2 INTERROGATION & ABLATIONS", "PHD-SKILLS FORTIFY")
      drawStructuredBlocks(review.reviewerQuestions, stripInternalTags = true)
    }

    // Section 08: Proofreading & Academic Copyedit Report
    if (review.copyeditSuggestions.isNotBlank()) {
      drawSectionHeader("08", "PROOFREADING & ACADEMIC COPYEDIT REPORT", "STAGE 09a–09c")
      drawStructuredBlocks(review.copyeditSuggestions, stripInternalTags = true)
    }

    // Section 09: Author Revision & Rebuttal Notes (if added by user)
    if (review.personalNotes.isNotBlank()) {
      drawSectionHeader("09", "AUTHOR REVISION & REBUTTAL NOTES", "PERSONAL SCRATCHPAD")
      drawStructuredBlocks(review.personalNotes, stripInternalTags = false)
    }

    drawFooter(canvas, pageNumber)
    document.finishPage(page)

    document.writeTo(outputStream)
    document.close()
  }

  private fun verdictAccentColor(verdict: String): Int {
    val upper = verdict.uppercase(Locale.US)
    return when {
      upper.contains("ACCEPT") && !upper.contains("REJECT") -> Color.rgb(22, 163, 74)
      upper.contains("MINOR") -> Color.rgb(0, 98, 227)
      upper.contains("MAJOR") -> Color.rgb(217, 119, 6)
      else -> Color.rgb(220, 38, 38)
    }
  }

  private fun scoreColor(score: Int): Int = when {
    score >= 80 -> Color.rgb(22, 163, 74)
    score >= 65 -> Color.rgb(0, 98, 227)
    score >= 45 -> Color.rgb(217, 119, 6)
    else -> Color.rgb(220, 38, 38)
  }

  private fun axisScoreColor(score: Int): Int = when {
    score >= 8 -> Color.rgb(22, 163, 74)
    score >= 6 -> Color.rgb(0, 98, 227)
    score >= 4 -> Color.rgb(217, 119, 6)
    else -> Color.rgb(220, 38, 38)
  }

  private fun severityPalette(level: SeverityLevel): BadgePalette = when (level) {
    SeverityLevel.CRITICAL, SeverityLevel.MUST_FIX, SeverityLevel.FAIL -> BadgePalette(
      bgColor = Color.rgb(254, 242, 242),
      borderColor = Color.rgb(254, 202, 202),
      accentColor = Color.rgb(220, 38, 38),
      textColor = Color.rgb(153, 27, 27)
    )
    SeverityLevel.MAJOR, SeverityLevel.SHOULD_FIX, SeverityLevel.WARN -> BadgePalette(
      bgColor = Color.rgb(255, 251, 235),
      borderColor = Color.rgb(253, 230, 138),
      accentColor = Color.rgb(217, 119, 6),
      textColor = Color.rgb(146, 64, 14)
    )
    SeverityLevel.MINOR -> BadgePalette(
      bgColor = Color.rgb(238, 242, 255),
      borderColor = Color.rgb(199, 210, 254),
      accentColor = Color.rgb(79, 70, 229),
      textColor = Color.rgb(55, 48, 163)
    )
    SeverityLevel.DISMISSED, SeverityLevel.PASS -> BadgePalette(
      bgColor = Color.rgb(240, 253, 244),
      borderColor = Color.rgb(187, 247, 208),
      accentColor = Color.rgb(22, 163, 74),
      textColor = Color.rgb(22, 101, 52)
    )
    SeverityLevel.RECOMMENDED -> BadgePalette(
      bgColor = Color.rgb(239, 246, 255),
      borderColor = Color.rgb(191, 219, 254),
      accentColor = Color.rgb(0, 98, 227),
      textColor = Color.rgb(30, 64, 175)
    )
  }

  private fun calloutPalette(tone: CalloutTone): BadgePalette = when (tone) {
    CalloutTone.BOTTOM_LINE -> BadgePalette(
      bgColor = Color.rgb(239, 246, 255),
      borderColor = Color.rgb(191, 219, 254),
      accentColor = Color.rgb(0, 98, 227),
      textColor = Color.rgb(30, 64, 175)
    )
    CalloutTone.FUTURE_RESEARCH -> BadgePalette(
      bgColor = Color.rgb(238, 242, 255),
      borderColor = Color.rgb(199, 210, 254),
      accentColor = Color.rgb(79, 70, 229),
      textColor = Color.rgb(55, 48, 163)
    )
    CalloutTone.ALERT -> BadgePalette(
      bgColor = Color.rgb(255, 251, 235),
      borderColor = Color.rgb(253, 230, 138),
      accentColor = Color.rgb(217, 119, 6),
      textColor = Color.rgb(146, 64, 14)
    )
    CalloutTone.INFO -> BadgePalette(
      bgColor = Color.rgb(248, 250, 252),
      borderColor = Color.rgb(203, 213, 225),
      accentColor = Color.rgb(71, 85, 105),
      textColor = Color.rgb(30, 41, 59)
    )
  }
}
