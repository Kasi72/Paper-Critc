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
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controls PDF export depth:
 * - [SCIENTIFIC_APPRAISAL_REPORT]: Default, concise, evidence-first publication-grade report
 *   organized around source-anchored findings, quantitative forensics, applicability, and rubric.
 * - [FULL_TECHNICAL_APPENDIX]: Appends raw multi-agent transcripts (Stage 01 Red Team,
 *   Anticipated Reviewer 2 Q&A, Copyedit notes) for deep technical inspection.
 */
enum class PdfExportScope(
  val shortLabel: String,
  val displayName: String,
  val fileSuffix: String
) {
  SCIENTIFIC_APPRAISAL_REPORT(
    shortLabel = "Appraisal Report",
    displayName = "Scientific Appraisal Report",
    fileSuffix = "appraisal_report"
  ),
  FULL_TECHNICAL_APPENDIX(
    shortLabel = "Full Appendix",
    displayName = "Full Technical Appendix",
    fileSuffix = "full_technical_dossier"
  )
}

/**
 * Generates publication-grade multi-page A4 PDF critical appraisal dossiers using native Android
 * [PdfDocument] with:
 * - Zero truncation of 6-axis rubric justifications (dynamically measured multi-line rows)
 * - Paragraph & widow/orphan protection + keep-with-next section/sub-header pagination
 * - Evidence-first layout featuring source-anchored [ReviewFinding] ledger cards, document
 *   coverage disclosure, and separated Scientific vs. Extraction confidence metrics.
 */
object PdfReportExporter {

  private const val PAGE_WIDTH = 595 // A4 width in PostScript points (72 dpi)
  private const val PAGE_HEIGHT = 842 // A4 height in PostScript points
  private const val MARGIN_H = 40f
  private const val MARGIN_TOP_FIRST = 36f
  private const val MARGIN_TOP_SUBSEQUENT = 48f
  private const val MARGIN_BOTTOM = 46f
  private val CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_H * 2)

  private data class BadgePalette(
    val bgColor: Int,
    val borderColor: Int,
    val accentColor: Int,
    val textColor: Int
  )

  suspend fun createShareablePdfUri(
    context: Context,
    review: PaperReview,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
  ): Uri = withContext(Dispatchers.IO) {
    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
    val safeSlug = normalizedTitle
      .lowercase(Locale.US)
      .replace(Regex("""[^a-z0-9]+"""), "_")
      .trim('_')
      .take(38)
      .ifBlank { "paper_critic_review" }
    val outFile = File(exportsDir, "${safeSlug}_${scope.fileSuffix}.pdf")
    FileOutputStream(outFile).use { stream ->
      renderReviewToPdfStream(review, stream, scope)
    }
    FileProvider.getUriForFile(
      context,
      "${context.packageName}.fileprovider",
      outFile
    )
  }

  suspend fun exportAndSharePdf(
    context: Context,
    review: PaperReview,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
  ): Result<Uri> = runCatching {
    val uri = createShareablePdfUri(context, review, scope)
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
    val rubric = review.toRubric()
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_SUBJECT, "Paper Critic ${scope.displayName}: $normalizedTitle")
      putExtra(
        Intent.EXTRA_TEXT,
        "Attached is the Paper Critic ${scope.displayName} for \"$normalizedTitle\" " +
          "(Weighted Score: ${rubric.overallCredibilityScore}/100 • Assessment: ${review.verdict.replace('_', ' ')})."
      )
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "Share ${scope.displayName} (PDF)")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    uri
  }

  suspend fun writePdfToUri(
    context: Context,
    review: PaperReview,
    targetUri: Uri,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
  ): Result<Unit> = withContext(Dispatchers.IO) {
    runCatching {
      context.contentResolver.openOutputStream(targetUri)?.use { out ->
        renderReviewToPdfStream(review, out, scope)
      } ?: throw IllegalStateException("Could not open destination output stream for PDF.")
      Unit
    }
  }

  fun renderReviewToPdfStream(
    review: PaperReview,
    outputStream: OutputStream,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
  ) {
    val document = PdfDocument()
    try {
      val rubric = review.toRubric()
    val authoritativeScore = rubric.overallCredibilityScore
    val reviewMode = review.resolvedReviewMode()
    val findings = review.parsedFindings()
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
    val runningTitle = if (normalizedTitle.length > 58) {
      normalizedTitle.take(55).trimEnd() + "…"
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
    val quoteBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 245, 249) }
    val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(214, 222, 234)
      strokeWidth = 0.75f
    }
    val sectionTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(239, 246, 255) }
    val subTagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 235, 248) }

    val bannerEyebrowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(148, 163, 184)
      textSize = 7.9f
      letterSpacing = 0.08f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val bannerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 13.5f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val bannerSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(203, 213, 225)
      textSize = 8.0f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    val verdictPillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 8.4f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val scoreBigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 21f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val scoreSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(148, 163, 184)
      textSize = 7.3f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val paperTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 14.2f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(71, 85, 105)
      textSize = 9.0f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val sectionNumPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      textSize = 8.4f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val sectionHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 10.2f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val sectionStagePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 98, 227)
      textSize = 7.6f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val subHeaderTagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 82, 204)
      textSize = 7.6f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val subHeaderTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 9.5f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      textSize = 9.1f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val bodyBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(15, 23, 42)
      textSize = 9.1f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val smallMutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(71, 85, 105)
      textSize = 8.1f
      typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val quoteItalicPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(51, 65, 85)
      textSize = 8.3f
      typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    val bulletMarkerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(0, 98, 227)
      textSize = 8.5f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(30, 41, 59)
      textSize = 8.3f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    val headerFooterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(100, 116, 139)
      textSize = 7.7f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
    var canvas: Canvas = page.canvas
    var cursorY = MARGIN_TOP_FIRST
    val maxPageBodyHeight = PAGE_HEIGHT - MARGIN_TOP_SUBSEQUENT - MARGIN_BOTTOM

    fun drawRunningHeader(targetCanvas: Canvas) {
      val headerY = 26f
      targetCanvas.drawText("${scope.shortLabel.uppercase(Locale.US)}  •  $runningTitle", MARGIN_H, headerY, headerFooterPaint)
      val verdictTag = "$verdictUpper ($authoritativeScore/100)"
      val vw = headerFooterPaint.measureText(verdictTag)
      targetCanvas.drawText(verdictTag, PAGE_WIDTH - MARGIN_H - vw, headerY, headerFooterPaint)
      targetCanvas.drawLine(MARGIN_H, headerY + 7f, PAGE_WIDTH - MARGIN_H, headerY + 7f, dividerPaint)
    }

    fun drawFooter(targetCanvas: Canvas, pgNum: Int) {
      val footerY = PAGE_HEIGHT - 22f
      targetCanvas.drawLine(MARGIN_H, footerY - 12f, PAGE_WIDTH - MARGIN_H, footerY - 12f, dividerPaint)
      targetCanvas.drawText(
        "Paper Critic Critical Appraisal Engine  •  ${reviewMode.shortLabel}  •  $dateStr",
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

    /**
     * Paragraph-aware pagination with widow & orphan protection:
     * - Short paragraphs (≤ 4 lines) are kept intact on one page.
     * - Longer paragraphs never leave a single orphan line at page bottom or single widow line at top.
     */
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
      val lineCount = layout.lineCount
      if (lineCount == 0) return

      val accentPaint = leftAccentColor?.let { c ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
      }

      // Keep short paragraphs intact on one page
      if (lineCount <= 4 && layout.height <= maxPageBodyHeight) {
        ensureSpace(layout.height.toFloat() + 2f)
      } else if (lineCount >= 2) {
        // Orphan protection: ensure at least the first 2 lines fit before starting on current page
        val firstTwoHeight = (layout.getLineBottom(1) - layout.getLineTop(0)).toFloat()
        if (cursorY + firstTwoHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
          startNewPage()
        }
      }

      var lineIdx = 0
      while (lineIdx < lineCount) {
        val lineTop = layout.getLineTop(lineIdx)
        val lineBottom = layout.getLineBottom(lineIdx)
        val lineHeight = (lineBottom - lineTop).toFloat()

        // Widow protection: if 2 lines remain and only 1 fits on current page, push both to next page
        val remainingLines = lineCount - lineIdx
        if (remainingLines == 2 && lineIdx > 0) {
          val twoLinesH = (layout.getLineBottom(lineIdx + 1) - lineTop).toFloat()
          if (cursorY + lineHeight <= PAGE_HEIGHT - MARGIN_BOTTOM &&
            cursorY + twoLinesH > PAGE_HEIGHT - MARGIN_BOTTOM
          ) {
            startNewPage()
          }
        } else if (cursorY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
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
        lineIdx++
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
      val maxSinglePageHeight = maxPageBodyHeight - 24f

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
        ensureSpace(48f)
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

    fun drawFindingLedgerCard(finding: ReviewFinding) {
      val palette = findingPalette(finding)
      val padH = 11f
      val padV = 8.5f
      val innerLeft = MARGIN_H + 4f + padH
      val innerWidth = CONTENT_WIDTH - 4f - (padH * 2)

      val titleLayout = buildLayout("${finding.id}  •  ${finding.title}", bodyBoldPaint, innerWidth, 2.0f)
      val allegationLayout = buildLayout(finding.allegation, bodyPaint, innerWidth, 2.3f)

      val primaryAnchor = finding.supportingEvidence.firstOrNull()
      val quoteText = primaryAnchor?.exactQuote?.trim().orEmpty()
      val quotePrefix = buildString {
        append("Source")
        if (primaryAnchor != null && primaryAnchor.pageNumber > 0) append(" [Page ${primaryAnchor.pageNumber}]")
        if (!primaryAnchor?.section.isNullOrBlank()) append(" (${primaryAnchor?.section})")
        append(": ")
      }
      val quoteLayout = if (quoteText.isNotBlank()) {
        buildLayout("$quotePrefix\"$quoteText\"", quoteItalicPaint, innerWidth - 12f, 2.0f)
      } else null

      val counterAnchor = finding.counterEvidence.firstOrNull()
      val counterText = counterAnchor?.exactQuote?.trim().orEmpty()
      val counterLayout = if (counterText.isNotBlank()) {
        val cPage = if (counterAnchor != null && counterAnchor.pageNumber > 0) " [Page ${counterAnchor.pageNumber}]" else ""
        buildLayout("Counterevidence$cPage: \"$counterText\"", smallMutedPaint, innerWidth - 12f, 2.0f)
      } else null

      val remedyText = buildString {
        if (finding.recommendedCorrection.isNotBlank() && finding.status != FindingStatus.CONTRADICTED) {
          append("Recommended Action: ${finding.recommendedCorrection}")
        }
        if (finding.verificationNotes.isNotBlank()) {
          if (isNotEmpty()) append("\n")
          append("Verification Note: ${finding.verificationNotes}")
        }
      }
      val remedyLayout = if (remedyText.isNotBlank()) {
        buildLayout(remedyText, smallMutedPaint, innerWidth, 2.0f)
      } else null

      val pillsRowH = 16f
      val quoteBlockH = quoteLayout?.let { it.height + 10f } ?: 0f
      val counterBlockH = counterLayout?.let { it.height + 10f } ?: 0f
      val remedyBlockH = remedyLayout?.let { it.height + 6f } ?: 0f
      val totalH = padV + pillsRowH + 4f + titleLayout.height + 4f + allegationLayout.height +
        (if (quoteBlockH > 0) 6f + quoteBlockH else 0f) +
        (if (counterBlockH > 0) 5f + counterBlockH else 0f) +
        (if (remedyBlockH > 0) 5f + remedyBlockH else 0f) + padV

      if (totalH <= maxPageBodyHeight - 20f) {
        ensureSpace(totalH + 5f)
        val top = cursorY
        val boxRect = RectF(MARGIN_H, top, PAGE_WIDTH - MARGIN_H, top + totalH)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.bgColor }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = palette.borderColor
          style = Paint.Style.STROKE
          strokeWidth = 0.8f
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accentColor }

        canvas.drawRoundRect(boxRect, 6f, 6f, bgPaint)
        canvas.drawRoundRect(boxRect, 6f, 6f, borderPaint)
        canvas.drawRoundRect(RectF(MARGIN_H, top, MARGIN_H + 4f, top + totalH), 2f, 2f, accentPaint)

        // Severity + Status pills
        val pillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.WHITE
          textSize = 7.2f
          typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        var px = innerLeft
        val py = top + padV
        val sevLabel = finding.severity.uppercase(Locale.US)
        val sevW = pillTextPaint.measureText(sevLabel) + 11f
        canvas.drawRoundRect(RectF(px, py, px + sevW, py + 12.5f), 3.5f, 3.5f, accentPaint)
        canvas.drawText(sevLabel, px + 5.5f, py + 8.8f, pillTextPaint)
        px += sevW + 6f

        val statusLabel = finding.status.displayBadge
        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusPillColor(finding.status) }
        val stW = pillTextPaint.measureText(statusLabel) + 11f
        canvas.drawRoundRect(RectF(px, py, px + stW, py + 12.5f), 3.5f, 3.5f, statusPaint)
        canvas.drawText(statusLabel, px + 5.5f, py + 8.8f, pillTextPaint)

        if (primaryAnchor != null && primaryAnchor.pageNumber > 0) {
          val pgLabel = "PAGE ${primaryAnchor.pageNumber}"
          val pgW = sectionStagePaint.measureText(pgLabel)
          canvas.drawText(pgLabel, PAGE_WIDTH - MARGIN_H - padH - pgW, py + 9f, sectionStagePaint)
        }

        var y = py + pillsRowH + 4f
        canvas.save()
        canvas.translate(innerLeft, y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + 4f

        canvas.save()
        canvas.translate(innerLeft, y)
        allegationLayout.draw(canvas)
        canvas.restore()
        y += allegationLayout.height

        if (quoteLayout != null) {
          y += 6f
          val qRect = RectF(innerLeft, y, innerLeft + innerWidth, y + quoteLayout.height + 10f)
          canvas.drawRoundRect(qRect, 4f, 4f, quoteBgPaint)
          canvas.drawRoundRect(qRect, 4f, 4f, cardBorderPaint)
          canvas.save()
          canvas.translate(innerLeft + 6f, y + 5f)
          quoteLayout.draw(canvas)
          canvas.restore()
          y += quoteLayout.height + 10f
        }

        if (counterLayout != null) {
          y += 5f
          val cRect = RectF(innerLeft, y, innerLeft + innerWidth, y + counterLayout.height + 10f)
          val cBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 253, 244) }
          canvas.drawRoundRect(cRect, 4f, 4f, cBg)
          canvas.drawRoundRect(cRect, 4f, 4f, cardBorderPaint)
          canvas.save()
          canvas.translate(innerLeft + 6f, y + 5f)
          counterLayout.draw(canvas)
          canvas.restore()
          y += counterLayout.height + 10f
        }

        if (remedyLayout != null) {
          y += 5f
          canvas.save()
          canvas.translate(innerLeft, y)
          remedyLayout.draw(canvas)
          canvas.restore()
        }

        cursorY = top + totalH + 6.5f
      } else {
        drawBoxedCard(
          badgeText = "${finding.id} • ${finding.severity} • ${finding.status.displayBadge}",
          bodyText = "${finding.title}\n${finding.allegation}\n$remedyText",
          palette = palette,
          isCalloutTitle = false
        )
      }
    }

    fun drawStructuredBlocks(rawContent: String, stripInternalTags: Boolean = true) {
      val blocks = DossierFormattingEngine.parseStructuredBlocks(
        raw = rawContent,
        stripInternalTags = stripInternalTags
      )
      if (blocks.isEmpty()) {
        drawLayoutPaginated("No additional findings recorded for this section.", smallMutedPaint)
        cursorY += 4f
        return
      }

      for (block in blocks) {
        when (block) {
          is ReportBlock.SubHeader -> {
            // Keep-with-next: subheader + at least 40pt of following text stay together
            ensureSpace(58f)
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
            // Ensure bullet marker + first 2 lines stay together
            ensureSpace(26f)
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
              textSize = 8.0f
              typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            val tableCellBoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
              color = Color.rgb(15, 23, 42)
              textSize = 8.0f
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
              ensureSpace(headerH + 26f)
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

              // P1 fix: If a table row is taller than a single printable page body, switch it to a
              // paginated stacked card so long audit findings NEVER clip or overlap the footer.
              if (shouldFallbackTableRowToPaginatedCard(rowH, maxPageBodyHeight)) {
                ensureSpace(56f)
                val rowTitle = rowCells.firstOrNull().orEmpty().ifBlank { "Audit Row ${rowIdx + 1}" }
                val barTop = cursorY
                canvas.drawRoundRect(
                  RectF(MARGIN_H, barTop, PAGE_WIDTH - MARGIN_H, barTop + 17f),
                  4f,
                  4f,
                  zebraAltPaint
                )
                canvas.drawLine(MARGIN_H, barTop + 17f, PAGE_WIDTH - MARGIN_H, barTop + 17f, dividerPaint)
                val titleLayout = buildLayout(rowTitle, tableCellBoldPaint, CONTENT_WIDTH - 12f, 1.2f)
                canvas.save()
                canvas.translate(MARGIN_H + 6f, barTop + 3f)
                titleLayout.draw(canvas)
                canvas.restore()
                cursorY = barTop + maxOf(19f, titleLayout.height + 6f)

                rowCells.forEachIndexed { c, cellText ->
                  val headerLabel = block.headers.getOrNull(c).orEmpty().ifBlank { "Column ${c + 1}" }
                  if (cellText.isNotBlank()) {
                    drawLayoutPaginated(
                      text = "${headerLabel.uppercase(Locale.US)}: $cellText",
                      paint = if (c == 0) tableCellBoldPaint else tableCellPaint,
                      width = CONTENT_WIDTH - 14f,
                      leftX = MARGIN_H + 8f,
                      lineAdd = 1.8f
                    )
                    cursorY += 2.5f
                  }
                }
                canvas.drawLine(MARGIN_H, cursorY + 2f, PAGE_WIDTH - MARGIN_H, cursorY + 2f, dividerPaint)
                cursorY += 7f
              } else {
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
      val measuredTagW = if (stageTag.isNotBlank()) sectionStagePaint.measureText(stageTag) else 0f
      val headerMetrics = computeSectionHeaderMetrics(
        contentWidth = CONTENT_WIDTH,
        stageTagWidth = measuredTagW
      )
      val titleLayout = buildLayout(title, sectionHeaderPaint, headerMetrics.titleMaxWidth, 1.5f)
      val tagLayout = if (stageTag.isNotBlank()) {
        buildLayout(stageTag, sectionStagePaint, headerMetrics.tagMaxWidth, 1.2f)
      } else null
      val barH = maxOf(
        24f,
        maxOf(titleLayout.height, tagLayout?.height ?: 0).toFloat() + 11f
      )
      // Keep-with-next: section header + at least 60pt of body stay on the same page
      ensureSpace(barH + 68f)
      cursorY += 6f
      val barTop = cursorY
      val barRect = RectF(MARGIN_H, barTop, PAGE_WIDTH - MARGIN_H, barTop + barH)
      canvas.drawRoundRect(barRect, 5f, 5f, sectionTintPaint)

      val badgeRect = RectF(MARGIN_H, barTop, MARGIN_H + 26f, barTop + barH)
      canvas.drawRoundRect(badgeRect, 5f, 5f, bluePaint)
      val numW = sectionNumPaint.measureText(sectionNum)
      canvas.drawText(sectionNum, MARGIN_H + (26f - numW) / 2f, barTop + (barH / 2f) + 3.5f, sectionNumPaint)

      canvas.save()
      canvas.translate(MARGIN_H + 34f, barTop + (barH - titleLayout.height) / 2f)
      titleLayout.draw(canvas)
      canvas.restore()

      if (tagLayout != null) {
        val actualTagW = (0 until tagLayout.lineCount).maxOfOrNull { tagLayout.getLineWidth(it) } ?: headerMetrics.tagMaxWidth
        val tagX = (PAGE_WIDTH - MARGIN_H - actualTagW - 8f).coerceAtLeast(MARGIN_H + 34f + headerMetrics.titleMaxWidth + 6f)
        canvas.save()
        canvas.translate(tagX, barTop + (barH - tagLayout.height) / 2f)
        tagLayout.draw(canvas)
        canvas.restore()
      }

      cursorY = barTop + barH + 9f
    }

    // =========================================================================
    // 1. EXECUTIVE MASTHEAD BANNER (Page 1)
    // =========================================================================
    val bannerH = 84f
    val bannerRect = RectF(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY + bannerH)
    canvas.drawRoundRect(bannerRect, 9f, 9f, navyBgPaint)

    canvas.drawText(
      "PAPER CRITIC  •  ${scope.displayName.uppercase(Locale.US)}",
      MARGIN_H + 15f,
      cursorY + 18f,
      bannerEyebrowPaint
    )
    canvas.drawText(
      reviewMode.displayTitle.uppercase(Locale.US),
      MARGIN_H + 15f,
      cursorY + 35f,
      bannerTitlePaint
    )

    val pillW = verdictPillTextPaint.measureText(verdictUpper) + 18f
    val pillTop = cursorY + 44f
    canvas.drawRoundRect(
      RectF(MARGIN_H + 15f, pillTop, MARGIN_H + 15f + pillW, pillTop + 18.5f),
      4.5f,
      4.5f,
      verdictPillPaint
    )
    canvas.drawText(verdictUpper, MARGIN_H + 24f, pillTop + 12.4f, verdictPillTextPaint)

    canvas.drawText(
      "SCIENTIFIC CONF: ${rubric.refereeConfidence}/5  •  EXTRACTION CONF: ${rubric.extractionConfidence}/5",
      MARGIN_H + 15f,
      cursorY + 75f,
      bannerSubPaint
    )

    // Right-aligned Weighted Score Box (100% synchronized with 6-axis weighted rubric)
    val scoreBoxW = 86f
    val scoreBoxLeft = PAGE_WIDTH - MARGIN_H - scoreBoxW - 12f
    val scoreBoxTop = cursorY + 11f
    val scoreBoxRect = RectF(scoreBoxLeft, scoreBoxTop, scoreBoxLeft + scoreBoxW, cursorY + bannerH - 11f)
    canvas.drawRoundRect(scoreBoxRect, 7f, 7f, scoreBoxPaint)
    val scoreAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = scoreColor(authoritativeScore) }
    canvas.drawRoundRect(
      RectF(scoreBoxLeft, scoreBoxTop, scoreBoxLeft + scoreBoxW, scoreBoxTop + 3.5f),
      2f,
      2f,
      scoreAccentPaint
    )

    val scoreText = "$authoritativeScore"
    val scoreW = scoreBigPaint.measureText(scoreText)
    canvas.drawText(scoreText, scoreBoxLeft + (scoreBoxW - scoreW) / 2f, scoreBoxTop + 33f, scoreBigPaint)
    val subScoreText = "/ 100 WEIGHTED"
    val subW = scoreSubPaint.measureText(subScoreText)
    canvas.drawText(subScoreText, scoreBoxLeft + (scoreBoxW - subW) / 2f, scoreBoxTop + 49f, scoreSubPaint)

    cursorY += bannerH + 12f

    // =========================================================================
    // 2. MANUSCRIPT TITLE, CITATION METADATA & COVERAGE DISCLOSURE
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
      "Uploaded Academic Manuscript  •  Independent Critical Appraisal"
    }
    drawLayoutPaginated(metaLine, metaPaint, CONTENT_WIDTH, MARGIN_H, 1.8f)
    cursorY += 6f

    // Document Coverage & Validation Provenance Banner
    val coverageText = buildString {
      append("Validation Status: ${review.validationStatus.replace('_', ' ')}")
      if (review.coverageDisclosure.isNotBlank()) {
        append("  |  ${review.coverageDisclosure}")
      }
      append("  |  Generated: $dateStr")
    }
    val covLayout = buildLayout(coverageText, smallMutedPaint, CONTENT_WIDTH - 16f, 1.8f)
    val covH = covLayout.height + 11f
    ensureSpace(covH + 6f)
    val covRect = RectF(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY + covH)
    canvas.drawRoundRect(covRect, 5f, 5f, eqBgPaint)
    canvas.drawRoundRect(covRect, 5f, 5f, cardBorderPaint)
    canvas.save()
    canvas.translate(MARGIN_H + 8f, cursorY + 5.5f)
    covLayout.draw(canvas)
    canvas.restore()
    cursorY += covH + 9f

    // =========================================================================
    // 3. STRUCTURED 6-AXIS WEIGHTED RUBRIC (DYNAMIC MULTI-LINE — ZERO TRUNCATION!)
    // =========================================================================
    val criteria = rubric.criteriaList()
    val rubricInnerW = CONTENT_WIDTH - 24f
    val preparedRows = criteria.map { item ->
      val cleanJust = DossierFormattingEngine.cleanAcademicProse(item.justification)
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
      val justLayout = buildLayout(cleanJust, smallMutedPaint, rubricInnerW, 1.8f)
      val rowH = 16f + justLayout.height.toFloat() + 7f
      Triple(item, justLayout, rowH)
    }

    val totalRubricH = 34f + preparedRows.sumOf { it.third.toDouble() }.toFloat() + 6f
    if (totalRubricH <= maxPageBodyHeight) {
      ensureSpace(totalRubricH + 8f)
    } else {
      ensureSpace(90f)
    }

    val rubricTop = cursorY
    val fitsSingleCard = cursorY + totalRubricH <= PAGE_HEIGHT - MARGIN_BOTTOM
    if (fitsSingleCard) {
      val rubricRect = RectF(MARGIN_H, rubricTop, PAGE_WIDTH - MARGIN_H, rubricTop + totalRubricH)
      canvas.drawRoundRect(rubricRect, 7f, 7f, cardBgPaint)
      canvas.drawRoundRect(rubricRect, 7f, 7f, cardBorderPaint)
    }

    canvas.drawText(
      "6-AXIS WEIGHTED APPRAISAL RUBRIC (EXACT COMPOSITE)",
      MARGIN_H + 12f,
      rubricTop + 17.5f,
      subHeaderTitlePaint
    )
    val confRight = "WEIGHTED: $authoritativeScore/100  •  SCI ${rubric.refereeConfidence}/5  •  EXT ${rubric.extractionConfidence}/5"
    val confRightW = sectionStagePaint.measureText(confRight)
    canvas.drawText(confRight, PAGE_WIDTH - MARGIN_H - confRightW - 12f, rubricTop + 17f, sectionStagePaint)
    canvas.drawLine(MARGIN_H + 12f, rubricTop + 24f, PAGE_WIDTH - MARGIN_H - 12f, rubricTop + 24f, dividerPaint)

    cursorY = rubricTop + 34f
    preparedRows.forEach { (item, justLayout, rowH) ->
      if (!fitsSingleCard && cursorY + rowH > PAGE_HEIGHT - MARGIN_BOTTOM) {
        startNewPage()
      }
      val rowBaseline = cursorY + 8f
      canvas.drawText("${item.criterion} (${item.weightPercent}%)", MARGIN_H + 12f, rowBaseline, bodyBoldPaint)

      val barStartX = MARGIN_H + 168f
      val barW = 282f
      canvas.drawRoundRect(
        RectF(barStartX, rowBaseline - 7.5f, barStartX + barW, rowBaseline - 1.5f),
        3f,
        3f,
        trackBarBgPaint
      )
      val fraction = (item.score.toFloat() / item.maxScore.toFloat()).coerceIn(0f, 1f)
      val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = axisScoreColor(item.score) }
      canvas.drawRoundRect(
        RectF(barStartX, rowBaseline - 7.5f, barStartX + (barW * fraction).coerceAtLeast(4f), rowBaseline - 1.5f),
        3f,
        3f,
        barFillPaint
      )

      val scoreLabel = "${item.score}/${item.maxScore}"
      val scoreLabelPaint = TextPaint(monoPaint).apply {
        color = axisScoreColor(item.score)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      }
      canvas.drawText(scoreLabel, barStartX + barW + 12f, rowBaseline, scoreLabelPaint)

      // Full multi-line rationale — NEVER clipped or truncated!
      canvas.save()
      canvas.translate(MARGIN_H + 12f, rowBaseline + 4.5f)
      justLayout.draw(canvas)
      canvas.restore()

      cursorY += rowH
    }
    cursorY = if (fitsSingleCard) rubricTop + totalRubricH + 10f else cursorY + 8f

    // =========================================================================
    // 4. EVIDENCE-FIRST SCIENTIFIC APPRAISAL SECTIONS
    // =========================================================================

    // Section 01: Executive Summary & Article Profile
    drawSectionHeader("01", "EXECUTIVE SUMMARY & ARTICLE PROFILE", "SCOPE & DESIGN")
    val profileContent = buildString {
      if (review.abstractSummary.isNotBlank()) {
        appendLine("Executive Assessment: ${review.abstractSummary.trim()}")
        appendLine()
      }
      if (review.researchQuestion.isNotBlank()) {
        appendLine("[STAGE 00b — PRIMARY RESEARCH QUESTION]")
        appendLine(review.researchQuestion.trim())
        appendLine()
      }
      if (review.keyMethodology.isNotBlank()) {
        appendLine("[STAGE 00b — METHODOLOGY & STUDY DESIGN]")
        appendLine(review.keyMethodology.trim())
        appendLine()
      }
      if (review.coreContributions.isNotBlank()) {
        appendLine("[STAGE 00c — CLAIMED CONTRIBUTIONS]")
        appendLine(review.coreContributions.trim())
      }
    }
    drawStructuredBlocks(profileContent, stripInternalTags = true)

    // Section 02: Prioritized Source-Anchored Findings Ledger
    drawSectionHeader("02", "PRIORITIZED SOURCE-ANCHORED FINDINGS LEDGER", "EVIDENCE AUDIT")
    if (findings.isNotEmpty()) {
      val verifiedCount = findings.count { it.status == FindingStatus.VERIFIED }
      val partialCount = findings.count { it.status == FindingStatus.PARTIALLY_SUPPORTED }
      val contradictedCount = findings.count { it.status == FindingStatus.CONTRADICTED }
      val unverifiedCount = findings.count { it.status == FindingStatus.UNVERIFIED }
      drawLayoutPaginated(
        "Ledger Summary: ${findings.size} structured finding(s) — $verifiedCount Verified, " +
          "$partialCount Partially Supported, $unverifiedCount Unverified, $contradictedCount Contradicted/Dismissed.",
        smallMutedPaint
      )
      cursorY += 5f
      findings.forEach { finding ->
        drawFindingLedgerCard(finding)
      }
    } else {
      drawStructuredBlocks(review.blueTeamVerification, stripInternalTags = true)
    }

    // Section 03: Quantitative & Statistical Audit
    if (review.mathAuditReport.isNotBlank()) {
      drawSectionHeader("03", "QUANTITATIVE, STATISTICAL & MATHEMATICAL AUDIT", "FORENSICS & STATS")
      drawStructuredBlocks(review.mathAuditReport, stripInternalTags = true)
    }

    // Section 04: Methodological Verification, Counterarguments & Numerical Check
    if (findings.isNotEmpty() && review.blueTeamVerification.isNotBlank()) {
      drawSectionHeader("04", "VERIFICATION SYNTHESIS & BLUE TEAM COUNTERARGUMENTS", "CROSS-CHECK")
      drawStructuredBlocks(review.blueTeamVerification, stripInternalTags = true)
    }

    // Section 05: Reproducibility, Provenance & Clinical/Policy Applicability
    if (review.codeAuditReport.isNotBlank()) {
      drawSectionHeader("05", "REPRODUCIBILITY, PROVENANCE & CLINICAL APPLICABILITY", "TRANSFERABILITY")
      drawStructuredBlocks(review.codeAuditReport, stripInternalTags = true)
    }

    // Section 06: Balanced Final Assessment, Decision & Action Roadmap
    drawSectionHeader("06", "FINAL ASSESSMENT, VERDICT & PRIORITIZED ACTIONS", reviewMode.shortLabel.uppercase(Locale.US))
    drawStructuredBlocks(review.editorNote, stripInternalTags = true)

    // Author personal notes (always included when present)
    if (review.personalNotes.isNotBlank()) {
      drawSectionHeader("07", "RESEARCHER NOTES & REVISION SCRATCHPAD", "USER NOTES")
      drawStructuredBlocks(review.personalNotes, stripInternalTags = false)
    }

    // =========================================================================
    // 5. OPTIONAL FULL TECHNICAL APPENDIX (Multi-Agent Raw Transcripts)
    // =========================================================================
    if (scope == PdfExportScope.FULL_TECHNICAL_APPENDIX) {
      if (review.redTeamCritique.isNotBlank()) {
        drawSectionHeader("A1", "APPENDIX: CANDIDATE ADVERSARIAL CRITIQUE TRANSCRIPT", "STAGE 01a–01g")
        drawStructuredBlocks(review.redTeamCritique, stripInternalTags = true)
      }
      if (review.reviewerQuestions.isNotBlank()) {
        drawSectionHeader("A2", "APPENDIX: ANTICIPATED PEER-REVIEW QUESTIONS & CHECKS", "FORTIFY Q&A")
        drawStructuredBlocks(review.reviewerQuestions, stripInternalTags = true)
      }
      if (review.copyeditSuggestions.isNotBlank()) {
        drawSectionHeader("A3", "APPENDIX: PROOFREADING & ACADEMIC COPYEDIT NOTES", "STAGE 09a–09c")
        drawStructuredBlocks(review.copyeditSuggestions, stripInternalTags = true)
      }
    }

    drawFooter(canvas, pageNumber)
    document.finishPage(page)

      document.writeTo(outputStream)
    } finally {
      document.close()
    }
  }

  private fun verdictAccentColor(verdict: String): Int {
    val upper = verdict.uppercase(Locale.US)
    return when {
      upper in setOf("ACCEPT", "HIGH_CREDIBILITY", "HIGH_CERTAINTY") -> Color.rgb(22, 163, 74)
      upper in setOf("MINOR_REVISION", "SOUND_WITH_CAVEATS", "MODERATE_CERTAINTY") -> Color.rgb(0, 98, 227)
      upper in setOf("MAJOR_REVISION", "REQUIRES_CORRECTION", "LOW_CERTAINTY") -> Color.rgb(217, 119, 6)
      else -> Color.rgb(220, 38, 38)
    }
  }

  private fun statusPillColor(status: FindingStatus): Int = when (status) {
    FindingStatus.VERIFIED -> Color.rgb(22, 163, 74)
    FindingStatus.PARTIALLY_SUPPORTED -> Color.rgb(0, 98, 227)
    FindingStatus.UNVERIFIED -> Color.rgb(217, 119, 6)
    FindingStatus.CONTRADICTED -> Color.rgb(100, 116, 139)
    FindingStatus.NOT_APPLICABLE -> Color.rgb(100, 116, 139)
  }

  private fun findingPalette(finding: ReviewFinding): BadgePalette {
    if (finding.status == FindingStatus.CONTRADICTED || finding.status == FindingStatus.NOT_APPLICABLE) {
      return BadgePalette(
        bgColor = Color.rgb(248, 250, 252),
        borderColor = Color.rgb(203, 213, 225),
        accentColor = Color.rgb(100, 116, 139),
        textColor = Color.rgb(71, 85, 105)
      )
    }
    return when (finding.severity.uppercase(Locale.US)) {
      "CRITICAL" -> severityPalette(SeverityLevel.CRITICAL)
      "MAJOR" -> severityPalette(SeverityLevel.MAJOR)
      "MINOR" -> severityPalette(SeverityLevel.MINOR)
      else -> severityPalette(SeverityLevel.RECOMMENDED)
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

  internal data class SectionHeaderMetrics(
    val titleMaxWidth: Float,
    val tagMaxWidth: Float
  )

  /**
   * Computes non-overlapping wrapped column widths for the section header title and stage tag.
   */
  internal fun computeSectionHeaderMetrics(
    contentWidth: Float,
    stageTagWidth: Float
  ): SectionHeaderMetrics {
    val reservedLeft = 34f // 26pt badge + 8pt gutter
    val reservedRightPad = 8f
    val gapBetween = 10f
    val maxTagAllowed = (contentWidth * 0.28f).coerceAtLeast(64f)
    val effectiveTagW = if (stageTagWidth <= 0f) 0f else stageTagWidth.coerceAtMost(maxTagAllowed)
    val titleW = (contentWidth - reservedLeft - reservedRightPad - if (effectiveTagW > 0f) effectiveTagW + gapBetween else 0f)
      .coerceAtLeast(160f)
    return SectionHeaderMetrics(titleMaxWidth = titleW, tagMaxWidth = effectiveTagW)
  }

  /**
   * Returns true when a rendered table row is too tall to fit safely on a single A4 body page
   * without clipping or colliding with the footer, triggering paginated stacked-card rendering.
   */
  internal fun shouldFallbackTableRowToPaginatedCard(
    measuredRowHeight: Float,
    maxPageBodyHeight: Float
  ): Boolean = measuredRowHeight > (maxPageBodyHeight - 40f)
}
