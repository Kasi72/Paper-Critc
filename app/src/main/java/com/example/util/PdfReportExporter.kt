package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.example.data.ai.epistemic.FlawSeverityTier
import com.example.data.ai.epistemic.ReportingStatus
import com.example.data.model.FindingStatus
import com.example.data.model.LedgerLoadState
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
 * Finding 14 (P2): Recorded bounding box for a rendered PDF layout block.
 */
data class PdfLayoutBlockBounds(
  val pageNumber: Int,
  val kind: String,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float
)

/**
 * Finding 14 (P2): Geometry-aware PDF layout verification summary checking margin containment,
 * header/footer safe-zone collisions, sequential vertical block overlaps, and table cell overflow.
 */
data class PdfGeometryVerificationReport(
  val totalBlocksChecked: Int = 0,
  val overlapDetected: Boolean = false,
  val outOfBoundsDetected: Boolean = false,
  val headerFooterCollisionDetected: Boolean = false,
  val tableCellOverflowDetected: Boolean = false,
  val issues: List<String> = emptyList()
) {
  val isGeometryClean: Boolean
    get() = !overlapDetected &&
      !outOfBoundsDetected &&
      !headerFooterCollisionDetected &&
      !tableCellOverflowDetected &&
      issues.isEmpty()
}

/**
 * Finding 14 (P2): Records rendered PDF geometry during canvas layout and runs deterministic
 * bounding-box verification across all pages.
 */
class PdfLayoutGeometryRecorder {
  private val bodyBlocks = mutableListOf<PdfLayoutBlockBounds>()
  private val headerBottomByPage = mutableMapOf<Int, Float>()
  private val footerTopByPage = mutableMapOf<Int, Float>()
  private var cellOverflowCount = 0
  private val recordedCellIssues = mutableListOf<String>()

  fun recordHeaderBand(pageNumber: Int, bottomY: Float) {
    val prev = headerBottomByPage[pageNumber]
    headerBottomByPage[pageNumber] = if (prev == null) bottomY else maxOf(prev, bottomY)
  }

  fun recordFooterBand(pageNumber: Int, topY: Float) {
    val prev = footerTopByPage[pageNumber]
    footerTopByPage[pageNumber] = if (prev == null) topY else minOf(prev, topY)
  }

  fun recordBodyBlock(
    pageNumber: Int,
    kind: String,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
  ) {
    if (bottom <= top || right <= left) return
    bodyBlocks.add(PdfLayoutBlockBounds(pageNumber, kind, left, top, right, bottom))
  }

  fun recordTableCell(
    pageNumber: Int,
    rowTop: Float,
    rowBottom: Float,
    cellLeft: Float,
    cellRight: Float,
    measuredTextHeight: Float,
    padV: Float,
    maxPageRight: Float
  ) {
    val rowHeight = rowBottom - rowTop
    if (measuredTextHeight + padV * 2f > rowHeight + 2.0f) {
      cellOverflowCount++
      if (recordedCellIssues.size < 8) {
        recordedCellIssues.add(
          "Page $pageNumber table cell vertical overflow (needed=${"%.1f".format(Locale.US, measuredTextHeight + padV * 2f)}pt > row=${"%.1f".format(Locale.US, rowHeight)}pt)"
        )
      }
    }
    if (cellRight > maxPageRight + 1.5f) {
      cellOverflowCount++
      if (recordedCellIssues.size < 8) {
        recordedCellIssues.add(
          "Page $pageNumber table cell horizontal overflow (right=${"%.1f".format(Locale.US, cellRight)}pt > limit=${"%.1f".format(Locale.US, maxPageRight)}pt)"
        )
      }
    }
  }

  fun verify(
    pageWidth: Float = 595f,
    pageHeight: Float = 842f,
    marginH: Float = 40f,
    marginBottom: Float = 46f
  ): PdfGeometryVerificationReport {
    val issues = mutableListOf<String>()
    issues.addAll(recordedCellIssues)
    var overlap = false
    var outOfBounds = false
    var headerFooterCollision = false

    val minLeft = marginH - 1.5f
    val maxRight = pageWidth - marginH + 1.5f
    val maxBodyBottom = pageHeight - marginBottom + 2.0f

    bodyBlocks.groupBy { it.pageNumber }.forEach { (pg, blocks) ->
      val headerBottom = headerBottomByPage[pg] ?: if (pg >= 2) 33f else 0f
      val footerTop = footerTopByPage[pg] ?: (pageHeight - 34f)

      blocks.forEachIndexed { idx, b ->
        if (b.left < minLeft || b.right > maxRight || b.top < 0f || b.bottom > maxBodyBottom) {
          outOfBounds = true
          if (issues.size < 12) {
            issues.add(
              "Page $pg out-of-bounds ${b.kind} [${"%.1f".format(Locale.US, b.left)}, ${"%.1f".format(Locale.US, b.top)}, ${"%.1f".format(Locale.US, b.right)}, ${"%.1f".format(Locale.US, b.bottom)}]"
            )
          }
        }
        if ((headerBottom > 0f && b.top < headerBottom - 0.5f) || b.bottom > footerTop + 0.5f) {
          headerFooterCollision = true
          if (issues.size < 12) {
            issues.add(
              "Page $pg header/footer collision on ${b.kind} (top=${"%.1f".format(Locale.US, b.top)}, bottom=${"%.1f".format(Locale.US, b.bottom)})"
            )
          }
        }
        if (idx > 0) {
          val prev = blocks[idx - 1]
          val horizontalOverlap = minOf(prev.right, b.right) - maxOf(prev.left, b.left) > 1.0f
          val verticalOverlap = prev.bottom - b.top > 1.5f
          if (horizontalOverlap && verticalOverlap) {
            overlap = true
            if (issues.size < 12) {
              issues.add(
                "Page $pg vertical overlap between ${prev.kind} (bottom=${"%.1f".format(Locale.US, prev.bottom)}) and ${b.kind} (top=${"%.1f".format(Locale.US, b.top)})"
              )
            }
          }
        }
      }
    }

    return PdfGeometryVerificationReport(
      totalBlocksChecked = bodyBlocks.size,
      overlapDetected = overlap,
      outOfBoundsDetected = outOfBounds,
      headerFooterCollisionDetected = headerFooterCollision,
      tableCellOverflowDetected = cellOverflowCount > 0,
      issues = issues
    )
  }
}

/**
 * P0.3 Raw-Markup & Finding 14 Geometry-Aware PDF Gate diagnostics returned by
 * [PdfReportExporter.inspectRenderedText] and [PdfReportExporter.renderAndInspectPdf].
 */
data class PdfRenderDiagnostics(
  val rawHeaderCount: Int,
  val rawMarkdownRowCount: Int,
  val internalEnumCount: Int,
  val clippedContentDetected: Boolean,
  val extractedFromPdfBytes: Boolean = false,
  val pageCount: Int = 1,
  val pdfByteIntegrityFailed: Boolean = false,
  val verificationFailureReason: String? = null,
  val overlapDetected: Boolean = false,
  val outOfBoundsDetected: Boolean = false,
  val headerFooterCollisionDetected: Boolean = false,
  val tableCellOverflowDetected: Boolean = false,
  val geometryIssues: List<String> = emptyList(),
  val geometryBlocksChecked: Int = 0
) {
  val geometryClean: Boolean
    get() = !overlapDetected &&
      !outOfBoundsDetected &&
      !headerFooterCollisionDetected &&
      !tableCellOverflowDetected &&
      geometryIssues.isEmpty()

  val isClean: Boolean
    get() = rawHeaderCount == 0 &&
      rawMarkdownRowCount == 0 &&
      internalEnumCount == 0 &&
      !clippedContentDetected &&
      !pdfByteIntegrityFailed &&
      geometryClean
}

/**
 * Generates publication-grade multi-page A4 PDF critical appraisal dossiers using native Android
 * [PdfDocument] with:
 * - Zero truncation of 6-axis rubric justifications (dynamically measured multi-line rows)
 * - Paragraph & widow/orphan protection + keep-with-next section/sub-header pagination
 * - Evidence-first layout featuring source-anchored [ReviewFinding] ledger cards, document
 *   coverage disclosure, separated Scientific vs. Extraction confidence metrics,
 *   direct [ReportBlock.AuditTable] rendering, Technical Provenance Appendix, and a
 *   raw-markup PDF gate ([PdfRenderDiagnostics]).
 */
object PdfReportExporter {

  private val rawHeaderScanRegex = Regex("""(?m)^\s*\[[A-Z][A-Z0-9 _&?—-]{4,}\]\s*$""")
  private val rawMarkdownRowScanRegex = Regex("""(?m)^\s*\|.*\|.*\|""")
  private val internalEnumScanRegex = Regex(
    """\b(REPORTED_BUT_INADEQUATE|UNSUPPORTED_TO_ASSESS|NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL|NOT_ASSESSABLE|REPORTED_AND_ADEQUATE|FATAL_TO_PRIMARY_CONCLUSION|MATERIALLY_WEAKENS_CONFIDENCE|REQUIRES_REPORTING_CORRECTION|WORTHWHILE_ENHANCEMENT|COSMETIC_OR_EDITORIAL|IMPRECISION_OR_HIGH_VARIANCE|ARITHMETIC_ERROR|INTERNAL_CONTRADICTION|MODEL_MISMATCH|ASSUMPTION_UNCHECKED|REPORTING_OMISSION)\b"""
  )
  private val disclosureExtConfRegex = Regex("""Extraction\s+Confidence:\s*\d+/5""", RegexOption.IGNORE_CASE)

  /**
   * Scans extracted or rendered PDF text for reader-unfriendly raw bracket headers,
   * raw Markdown pipe rows, and unhumanized internal enum tokens.
   */
  fun inspectRenderedText(
    extractedText: String,
    clippedContentDetected: Boolean = false,
    extractedFromPdfBytes: Boolean = false,
    pageCount: Int = 1
  ): PdfRenderDiagnostics {
    val rawHeaders = rawHeaderScanRegex.findAll(extractedText).count()
    val rawRows = rawMarkdownRowScanRegex.findAll(extractedText).count()
    val internalEnums = internalEnumScanRegex.findAll(extractedText).count()
    return PdfRenderDiagnostics(
      rawHeaderCount = rawHeaders,
      rawMarkdownRowCount = rawRows,
      internalEnumCount = internalEnums,
      clippedContentDetected = clippedContentDetected,
      extractedFromPdfBytes = extractedFromPdfBytes,
      pageCount = pageCount
    )
  }

  /**
   * Attempts real byte-level PDF text extraction via PDFBox from rendered PDF bytes.
   * Returns Pair(extractedText, pageCount) when [pdfBytes] is a standard PDF document with text,
   * or null when running in headless bitmap-fallback test mode.
   */
  internal fun extractTextFromPdfBytes(pdfBytes: ByteArray): Pair<String, Int>? {
    if (pdfBytes.size < 8) return null
    val header = String(pdfBytes, 0, 5, Charsets.US_ASCII)
    if (header != "%PDF-") return null
    return runCatching {
      PDDocument.load(ByteArrayInputStream(pdfBytes)).use { doc ->
        val pages = doc.numberOfPages
        if (pages <= 0) return@use null
        // Finding 14: verify every page has valid non-zero A4/mediaBox dimensions
        val validBoxes = (0 until pages).all { idx ->
          val box = doc.getPage(idx)?.mediaBox
          box != null && box.width > 100f && box.height > 100f
        }
        if (!validBoxes) return@use null
        val text = PDFTextStripper().getText(doc).orEmpty()
        if (text.isNotBlank()) text to pages else null
      }
    }.getOrNull()
  }

  /**
   * Renders [review] to PDF bytes and returns both the rendered PDF payload, the captured/extracted
   * rendered text stream, and the P0.3 + Finding 14 [PdfRenderDiagnostics] verification result.
   */
  data class RenderedPdfResult(
    val pdfBytes: ByteArray,
    val renderedText: String,
    val diagnostics: PdfRenderDiagnostics
  )

  private fun PdfRenderDiagnostics.withGeometry(geometry: PdfGeometryVerificationReport?): PdfRenderDiagnostics {
    if (geometry == null) return this
    val geomFailure = if (!geometry.isGeometryClean) {
      "PDF layout geometry violation: ${geometry.issues.firstOrNull() ?: "overlap or out-of-bounds content"}"
    } else null
    return copy(
      overlapDetected = geometry.overlapDetected,
      outOfBoundsDetected = geometry.outOfBoundsDetected,
      headerFooterCollisionDetected = geometry.headerFooterCollisionDetected,
      tableCellOverflowDetected = geometry.tableCellOverflowDetected,
      geometryIssues = geometry.issues,
      geometryBlocksChecked = geometry.totalBlocksChecked,
      verificationFailureReason = verificationFailureReason ?: geomFailure
    )
  }

  /**
   * Validates actual exported PDF bytes via PDFBox and attaches optional geometry diagnostics.
   * - If [pdfBytes] are corrupted / unreadable / non-PDF when [requireByteExtraction] is true
   *   (or whenever non-empty bytes fail PDFBox parsing), sets [PdfRenderDiagnostics.pdfByteIntegrityFailed] = true
   *   so [PdfRenderDiagnostics.isClean] is GUARANTEED to be false and sharing/saving is blocked!
   */
  fun validateExportedPdfBytes(
    pdfBytes: ByteArray,
    fallbackText: String = "",
    clippedContentDetected: Boolean = false,
    fallbackPageCount: Int = 1,
    requireByteExtraction: Boolean = true,
    geometryReport: PdfGeometryVerificationReport? = null
  ): PdfRenderDiagnostics {
    val pdfBoxExtracted = extractTextFromPdfBytes(pdfBytes)
    if (pdfBoxExtracted != null) {
      val (text, pages) = pdfBoxExtracted
      val base = inspectRenderedText(
        extractedText = text,
        clippedContentDetected = clippedContentDetected,
        extractedFromPdfBytes = true,
        pageCount = pages
      ).withGeometry(geometryReport)
      return base.copy(
        pdfByteIntegrityFailed = false,
        verificationFailureReason = base.verificationFailureReason
          ?: if (base.isClean) null else "Exported PDF contains raw markup, clipped content, or layout geometry issues."
      )
    }
    // Whenever byte extraction is required (all production exports & integrity checks),
    // or when no canvas fallback text exists, unreadable/empty bytes MUST fail the gate!
    if (requireByteExtraction || fallbackText.isBlank()) {
      val reason = if (pdfBytes.isEmpty()) {
        "Exported PDF payload is empty (0 bytes)."
      } else {
        "Exported PDF bytes failed PDFBox structural/text verification (corrupted or unreadable PDF)."
      }
      val base = inspectRenderedText(
        extractedText = fallbackText,
        clippedContentDetected = clippedContentDetected,
        extractedFromPdfBytes = false,
        pageCount = fallbackPageCount.coerceAtLeast(1)
      ).withGeometry(geometryReport)
      return base.copy(
        pdfByteIntegrityFailed = true,
        verificationFailureReason = reason
      )
    }
    // Headless Robolectric shadow PdfDocument fallback (only when requireByteExtraction = false)
    return inspectRenderedText(
      extractedText = fallbackText,
      clippedContentDetected = clippedContentDetected,
      extractedFromPdfBytes = false,
      pageCount = fallbackPageCount.coerceAtLeast(1)
    ).withGeometry(geometryReport)
  }

  fun renderAndInspectPdf(
    review: PaperReview,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
    precomputedQuantBlocks: List<ReportBlock>? = null,
    requireByteExtraction: Boolean = false
  ): RenderedPdfResult {
    val baos = ByteArrayOutputStream()
    val textSink = StringBuilder()
    val clipTracker = BooleanArray(1) { false }
    val pageCountTracker = IntArray(1) { 1 }
    val geometryRecorder = PdfLayoutGeometryRecorder()
    renderReviewToPdfStream(
      review = review,
      outputStream = baos,
      scope = scope,
      precomputedQuantBlocks = precomputedQuantBlocks,
      renderedTextSink = textSink,
      clippedContentTracker = clipTracker,
      pageCountTracker = pageCountTracker,
      geometryRecorder = geometryRecorder
    )
    val bytes = baos.toByteArray()
    val geometryReport = geometryRecorder.verify(
      pageWidth = PAGE_WIDTH.toFloat(),
      pageHeight = PAGE_HEIGHT.toFloat(),
      marginH = MARGIN_H,
      marginBottom = MARGIN_BOTTOM
    )
    val diag = validateExportedPdfBytes(
      pdfBytes = bytes,
      fallbackText = textSink.toString(),
      clippedContentDetected = clipTracker[0],
      fallbackPageCount = pageCountTracker[0].coerceAtLeast(1),
      requireByteExtraction = requireByteExtraction,
      geometryReport = geometryReport
    )
    val inspectedText = extractTextFromPdfBytes(bytes)?.first ?: textSink.toString()
    return RenderedPdfResult(
      pdfBytes = bytes,
      renderedText = inspectedText,
      diagnostics = diag
    )
  }

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

  private const val MAX_CACHED_EXPORT_FILES = 12
  private const val MAX_CACHED_EXPORT_AGE_MS = 24L * 60L * 60L * 1000L
  private const val MIN_ACTIVE_SHARE_RETENTION_MS = 60L * 60L * 1000L // Never evict PDFs shared within last 60 min

  internal fun generateUniqueExportFileName(
    title: String,
    reviewId: String,
    scope: PdfExportScope,
    uniqueSuffix: String = UUID.randomUUID().toString().replace("-", "").take(8)
  ): String {
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(title)
    val safeSlug = normalizedTitle
      .lowercase(Locale.US)
      .replace(Regex("""[^a-z0-9]+"""), "_")
      .trim('_')
      .take(32)
      .ifBlank { "paper_critic_review" }
    val safeId = reviewId
      .lowercase(Locale.US)
      .replace(Regex("""[^a-z0-9]+"""), "")
      .take(8)
      .ifBlank { "draft" }
    val safeSuffix = uniqueSuffix
      .lowercase(Locale.US)
      .replace(Regex("""[^a-z0-9]+"""), "")
      .take(8)
      .ifBlank { "0000" }
    return "${safeSlug}_${safeId}_${safeSuffix}_${scope.fileSuffix}.pdf"
  }

  private fun pruneOldExportCacheFiles(exportsDir: File, keepFile: File) {
    runCatching {
      val now = System.currentTimeMillis()
      val existing = exportsDir.listFiles { file ->
        file.isFile && (file.name.endsWith(".pdf", ignoreCase = true) || file.name.endsWith(".tmp", ignoreCase = true))
      }?.sortedByDescending { it.lastModified() } ?: return
      existing.forEachIndexed { index, file ->
        if (file.absolutePath == keepFile.absolutePath) return@forEachIndexed
        val ageMs = (now - file.lastModified()).coerceAtLeast(0L)
        if (file.name.endsWith(".tmp", ignoreCase = true)) {
          if (ageMs > 5L * 60L * 1000L) file.delete()
          return@forEachIndexed
        }
        // Conservative cleanup: never delete a PDF created within the last 60 minutes so
        // concurrent Android share-sheet intents never lose their FileProvider stream mid-share.
        if (ageMs < MIN_ACTIVE_SHARE_RETENTION_MS) return@forEachIndexed
        val isStale = ageMs > MAX_CACHED_EXPORT_AGE_MS
        if (index >= MAX_CACHED_EXPORT_FILES || isStale) {
          file.delete()
        }
      }
    }
  }

  suspend fun createShareablePdfUri(
    context: Context,
    review: PaperReview,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
  ): Uri = withContext(Dispatchers.IO) {
    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val finalName = generateUniqueExportFileName(
      title = review.title,
      reviewId = review.id,
      scope = scope
    )
    val outFile = File(exportsDir, finalName)
    val tempFile = File(exportsDir, "$finalName.tmp")
    try {
      runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
      val rendered = renderAndInspectPdf(
        review = review,
        scope = scope,
        requireByteExtraction = true
      )
      if (!rendered.diagnostics.isClean) {
        throw IllegalStateException(
          "PDF export blocked by integrity gate: ${rendered.diagnostics.verificationFailureReason ?: "raw markup or byte verification failure"} " +
            "(headers=${rendered.diagnostics.rawHeaderCount}, tables=${rendered.diagnostics.rawMarkdownRowCount}, enums=${rendered.diagnostics.internalEnumCount}, byteFailed=${rendered.diagnostics.pdfByteIntegrityFailed})"
        )
      }
      FileOutputStream(tempFile).use { stream ->
        stream.write(rendered.pdfBytes)
      }
      if (!tempFile.renameTo(outFile)) {
        tempFile.copyTo(outFile, overwrite = true)
        tempFile.delete()
      }
    } catch (e: Throwable) {
      tempFile.delete()
      throw e
    }
    pruneOldExportCacheFiles(exportsDir, outFile)
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
  ): Result<Uri> = try {
    val uri = createShareablePdfUri(context, review, scope)
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
    val rubric = review.toRubric()
    val scoreSummary = if (rubric.isAssessed) {
      "Weighted Score: ${rubric.overallCredibilityScore}/100"
    } else {
      "Score: Not Assessed"
    }
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "application/pdf"
      putExtra(Intent.EXTRA_SUBJECT, "Paper Critic ${scope.displayName}: $normalizedTitle")
      putExtra(
        Intent.EXTRA_TEXT,
        "Attached is the Paper Critic ${scope.displayName} for \"$normalizedTitle\" " +
          "($scoreSummary • Assessment: ${review.verdict.replace('_', ' ')})."
      )
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, "Share ${scope.displayName} (PDF)")
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    Result.success(uri)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Throwable) {
    Result.failure(e)
  }

  suspend fun writePdfToUri(
    context: Context,
    review: PaperReview,
    targetUri: Uri,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
  ): Result<Unit> = withContext(Dispatchers.IO) {
    try {
      runCatching { PDFBoxResourceLoader.init(context.applicationContext) }
      val rendered = renderAndInspectPdf(
        review = review,
        scope = scope,
        requireByteExtraction = true
      )
      if (!rendered.diagnostics.isClean) {
        throw IllegalStateException(
          "PDF save blocked by integrity gate: ${rendered.diagnostics.verificationFailureReason ?: "raw markup or byte verification failure"}"
        )
      }
      context.contentResolver.openOutputStream(targetUri)?.use { out ->
        out.write(rendered.pdfBytes)
      } ?: throw IllegalStateException("Could not open destination output stream for PDF.")
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }

  fun renderReviewToPdfStream(
    review: PaperReview,
    outputStream: OutputStream,
    scope: PdfExportScope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
    precomputedQuantBlocks: List<ReportBlock>? = null,
    renderedTextSink: StringBuilder? = null,
    clippedContentTracker: BooleanArray? = null,
    pageCountTracker: IntArray? = null,
    geometryRecorder: PdfLayoutGeometryRecorder? = null
  ) {
    val document = PdfDocument()
    try {
      val rubric = review.toRubric()
    val authoritativeScore = rubric.overallCredibilityScore
    val authoritativeExtractionConf = rubric.extractionConfidence
    val reviewMode = review.resolvedReviewMode()
    val findings = review.parsedFindings()
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
    val normalizedTitle = DossierFormattingEngine.normalizeTitle(review.title)
    val runningTitle = if (normalizedTitle.length > 58) {
      normalizedTitle.take(55).trimEnd() + "…"
    } else {
      normalizedTitle
    }
    val isProvisionalVerdict = review.verdict.contains("PROVISIONAL", ignoreCase = true) ||
      review.verdict.contains("INSUFFICIENT_EVIDENCE", ignoreCase = true) ||
      review.validationStatus.equals("PROVISIONAL", ignoreCase = true)
    val verdictUpper = when (review.verdict.uppercase(Locale.US)) {
      "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN" -> "PROVISIONAL APPRAISAL — DESIGN CLASSIFICATION UNCERTAIN"
      "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT" -> "INSUFFICIENT EVIDENCE FOR ASSESSMENT"
      "MINOR_REVISION_WITH_CAVEATS" -> "MINOR REVISION WITH CAVEATS"
      else -> review.verdict.replace('_', ' ').uppercase(Locale.US)
    }
    val verdictColor = verdictAccentColor(review.verdict)

    fun recordText(line: String) {
      if (renderedTextSink != null && line.isNotBlank()) {
        renderedTextSink.appendLine(line)
      }
    }

    fun cleanReaderText(raw: String): String =
      DossierFormattingEngine.humanizeInternalEnums(raw)

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
    var page: PdfDocument.Page? = null
    var fallbackBitmap: Bitmap? = null
    var useBitmapFallback = false
    var canvas: Canvas = try {
      val firstPage = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
      page = firstPage
      firstPage.canvas
    } catch (e: UnsatisfiedLinkError) {
      useBitmapFallback = true
      val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
      fallbackBitmap = bmp
      Canvas(bmp)
    } catch (e: IllegalStateException) {
      val isHeadlessTestRuntime = Build.FINGERPRINT.isNullOrBlank() ||
        Build.FINGERPRINT.contains("robolectric", ignoreCase = true) ||
        e.message?.contains("native", ignoreCase = true) == true
      if (!isHeadlessTestRuntime) throw e
      useBitmapFallback = true
      val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
      fallbackBitmap = bmp
      Canvas(bmp)
    }
    var cursorY = MARGIN_TOP_FIRST
    val maxPageBodyHeight = PAGE_HEIGHT - MARGIN_TOP_SUBSEQUENT - MARGIN_BOTTOM

    fun drawRunningHeader(targetCanvas: Canvas) {
      val headerY = 26f
      targetCanvas.drawText("${scope.shortLabel.uppercase(Locale.US)}  •  $runningTitle", MARGIN_H, headerY, headerFooterPaint)
      val scoreSuffix = if (rubric.isAssessed) "$authoritativeScore/100" else "Not Assessed"
      val verdictTag = "$verdictUpper ($scoreSuffix)"
      val vw = headerFooterPaint.measureText(verdictTag)
      targetCanvas.drawText(verdictTag, PAGE_WIDTH - MARGIN_H - vw, headerY, headerFooterPaint)
      targetCanvas.drawLine(MARGIN_H, headerY + 7f, PAGE_WIDTH - MARGIN_H, headerY + 7f, dividerPaint)
      geometryRecorder?.recordHeaderBand(pageNumber, headerY + 7f)
    }

    fun drawFooter(targetCanvas: Canvas, pgNum: Int) {
      val footerY = PAGE_HEIGHT - 22f
      targetCanvas.drawLine(MARGIN_H, footerY - 12f, PAGE_WIDTH - MARGIN_H, footerY - 12f, dividerPaint)
      geometryRecorder?.recordFooterBand(pgNum, footerY - 12f)
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
      if (!useBitmapFallback) {
        page?.let { document.finishPage(it) }
      }
      pageNumber++
      if (!useBitmapFallback) {
        val nextPage = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        page = nextPage
        canvas = nextPage.canvas
      } else {
        fallbackBitmap?.recycle()
        val bmp = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        fallbackBitmap = bmp
        canvas = Canvas(bmp)
      }
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
      val clean = cleanReaderText(text).trim()
      if (clean.isEmpty()) return
      recordText(clean)
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

      var slicePage = pageNumber
      var sliceTop = cursorY
      var sliceBottom = cursorY
      var lineIdx = 0
      while (lineIdx < lineCount) {
        val lineTop = layout.getLineTop(lineIdx)
        val lineBottom = layout.getLineBottom(lineIdx)
        val lineHeight = (lineBottom - lineTop).toFloat()

        // Widow protection: if 2 lines remain and only 1 fits on current page, push both to next page
        val remainingLines = lineCount - lineIdx
        val needsNewPage = if (remainingLines == 2 && lineIdx > 0) {
          val twoLinesH = (layout.getLineBottom(lineIdx + 1) - lineTop).toFloat()
          cursorY + lineHeight <= PAGE_HEIGHT - MARGIN_BOTTOM &&
            cursorY + twoLinesH > PAGE_HEIGHT - MARGIN_BOTTOM
        } else {
          cursorY + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM
        }
        if (needsNewPage) {
          if (sliceBottom > sliceTop) {
            geometryRecorder?.recordBodyBlock(slicePage, "paragraph", leftX, sliceTop, leftX + width, sliceBottom)
          }
          startNewPage()
          slicePage = pageNumber
          sliceTop = cursorY
          sliceBottom = cursorY
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
        sliceBottom = cursorY
        lineIdx++
      }
      if (sliceBottom > sliceTop) {
        geometryRecorder?.recordBodyBlock(slicePage, "paragraph", leftX, sliceTop, leftX + width, sliceBottom)
      }
    }

    fun drawBoxedCard(
      badgeText: String,
      bodyText: String,
      palette: BadgePalette,
      isCalloutTitle: Boolean = false
    ) {
      val cleanBadge = cleanReaderText(badgeText)
      val cleanBody = cleanReaderText(bodyText).trim()
      recordText(cleanBadge)
      recordText(cleanBody)
      val padH = 11f
      val padV = 8f
      val innerLeft = MARGIN_H + 3.5f + padH
      val innerWidth = CONTENT_WIDTH - 3.5f - (padH * 2)
      val bodyLayout = buildLayout(cleanBody, bodyPaint, innerWidth, 2.5f)
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
          canvas.drawText(cleanBadge.uppercase(Locale.US), innerLeft, boxTop + padV + 9f, titlePaint)
        } else {
          val pillTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 7.4f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
          }
          val pillW = pillTextPaint.measureText(cleanBadge) + 12f
          val pillRect = RectF(innerLeft, boxTop + padV - 1f, innerLeft + pillW, boxTop + padV + 12f)
          canvas.drawRoundRect(pillRect, 3.5f, 3.5f, accentPaint)
          canvas.drawText(cleanBadge, innerLeft + 6f, boxTop + padV + 8.5f, pillTextPaint)
        }

        val textStartY = boxTop + padV + headerHeight
        canvas.save()
        canvas.translate(innerLeft, textStartY)
        bodyLayout.draw(canvas)
        canvas.restore()

        geometryRecorder?.recordBodyBlock(pageNumber, "boxed_card", MARGIN_H, boxTop, PAGE_WIDTH - MARGIN_H, boxTop + totalHeight)
        cursorY = boxTop + totalHeight + 6f
      } else {
        ensureSpace(48f)
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = palette.textColor
          textSize = 8.8f
          typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(cleanBadge.uppercase(Locale.US), MARGIN_H + 9f, cursorY + 10f, headerPaint)
        cursorY += 15f
        drawLayoutPaginated(
          text = cleanBody,
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

      val cleanTitle = cleanReaderText("${finding.id}  •  ${finding.title}")
      val cleanFinding = cleanReaderText("Finding: ${finding.allegation}")
      val cleanWhy = finding.methodologicalJustification.trim()
        .takeIf { it.isNotBlank() }
        ?.let { cleanReaderText("Why it matters: $it") }
      val allegationBody = listOfNotNull(cleanFinding, cleanWhy).joinToString("\n")
      val titleLayout = buildLayout(cleanTitle, bodyBoldPaint, innerWidth, 2.0f)
      val allegationLayout = buildLayout(allegationBody, bodyPaint, innerWidth, 2.3f)

      val primaryAnchor = finding.supportingEvidence.firstOrNull()
      val quoteText = primaryAnchor?.exactQuote?.trim().orEmpty()
      val quotePrefix = buildString {
        append("Evidence")
        if (primaryAnchor != null && primaryAnchor.pageNumber > 0) append(" [Page ${primaryAnchor.pageNumber}]")
        if (!primaryAnchor?.section.isNullOrBlank()) append(" (${primaryAnchor?.section})")
        append(": ")
      }
      val quoteFull = if (quoteText.isNotBlank()) cleanReaderText("$quotePrefix\"$quoteText\"") else ""
      val quoteLayout = if (quoteFull.isNotBlank()) {
        buildLayout(quoteFull, quoteItalicPaint, innerWidth - 12f, 2.0f)
      } else null

      val counterAnchor = finding.counterEvidence.firstOrNull()
      val counterText = counterAnchor?.exactQuote?.trim().orEmpty()
      val counterFull = if (counterText.isNotBlank()) {
        val cPage = if (counterAnchor != null && counterAnchor.pageNumber > 0) " [Page ${counterAnchor.pageNumber}]" else ""
        cleanReaderText("Counterevidence$cPage: \"$counterText\"")
      } else ""
      val counterLayout = if (counterFull.isNotBlank()) {
        buildLayout(counterFull, smallMutedPaint, innerWidth - 12f, 2.0f)
      } else null

      // Streamlined main-report metadata: strictly human-readable labels (zero raw enums, zero technical offsets/rules clutter)
      val remedyText = buildString {
        if (finding.flawTier.isNotBlank() || finding.reportingStatus.isNotBlank()) {
          val metaParts = buildList {
            if (finding.flawTier.isNotBlank()) {
              add("Severity tier: ${FlawSeverityTier.fromNameOrDefault(finding.flawTier).displayLabel}")
            }
            if (finding.reportingStatus.isNotBlank()) {
              add("Reporting status: ${ReportingStatus.fromNameOrDefault(finding.reportingStatus).displayLabel}")
            }
          }
          append(metaParts.joinToString(" • "))
        }
        if (finding.recommendedCorrection.isNotBlank() && finding.status != FindingStatus.CONTRADICTED) {
          if (isNotEmpty()) append("\n")
          append("Required action: ${cleanReaderText(finding.recommendedCorrection)}")
        }
      }
      recordText(cleanTitle)
      recordText(allegationBody)
      if (quoteFull.isNotBlank()) recordText(quoteFull)
      if (counterFull.isNotBlank()) recordText(counterFull)
      if (remedyText.isNotBlank()) recordText(remedyText)

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

        // Severity + Status + NOT_ASSESSABLE pills
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
        px += stW + 6f

        if (finding.reportingStatus == "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL") {
          val naLabel = "NOT ASSESSABLE"
          val naPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B45309") }
          val naW = pillTextPaint.measureText(naLabel) + 11f
          canvas.drawRoundRect(RectF(px, py, px + naW, py + 12.5f), 3.5f, 3.5f, naPaint)
          canvas.drawText(naLabel, px + 5.5f, py + 8.8f, pillTextPaint)
        }

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

        geometryRecorder?.recordBodyBlock(pageNumber, "finding_card", MARGIN_H, top, PAGE_WIDTH - MARGIN_H, top + totalH)
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

    fun drawReportBlocks(blocks: List<ReportBlock>) {
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
            val cleanTag = cleanReaderText(block.tag).trim()
            val cleanTitle = cleanReaderText(block.title).trim().trimStart('[').trimEnd(']')
            recordText(if (cleanTag.isNotBlank()) "$cleanTag — $cleanTitle" else cleanTitle)
            if (cleanTag.isNotBlank()) {
              val tagUpper = cleanTag.uppercase(Locale.US)
              val tagW = subHeaderTagPaint.measureText(tagUpper) + 10f
              val tagRect = RectF(xOffset, cursorY, xOffset + tagW, cursorY + 14f)
              canvas.drawRoundRect(tagRect, 3.5f, 3.5f, subTagBgPaint)
              canvas.drawText(tagUpper, xOffset + 5f, cursorY + 10f, subHeaderTagPaint)
              xOffset += tagW + 7f
            }
            val availW = (PAGE_WIDTH - MARGIN_H - xOffset).coerceAtLeast(140f)
            val titleLayout = buildLayout(cleanTitle, subHeaderTitlePaint, availW, 1.5f)
            canvas.save()
            canvas.translate(xOffset, cursorY + 1f)
            titleLayout.draw(canvas)
            canvas.restore()
            val subBottom = cursorY + maxOf(16f, titleLayout.height.toFloat() + 3f)
            geometryRecorder?.recordBodyBlock(pageNumber, "subheader", MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, subBottom)
            cursorY = subBottom + 3f
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
            geometryRecorder?.recordBodyBlock(pageNumber, "equation", MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY + boxH)
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
              val safeHeaders = block.headers.map { cleanReaderText(DossierFormattingEngine.sanitizeTableCell(it)) }
              recordText(safeHeaders.joinToString("  "))
              val headerLayouts = safeHeaders.mapIndexed { c, h ->
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
              geometryRecorder?.recordBodyBlock(pageNumber, "table_header", MARGIN_H, top, PAGE_WIDTH - MARGIN_H, top + headerH)
              cursorY = top + headerH
            }

            drawTableHeaderRow()

            block.rows.forEachIndexed { rowIdx, rawRowCells ->
              val rowCells = List(cols) { idx ->
                cleanReaderText(DossierFormattingEngine.sanitizeTableCell(rawRowCells.getOrNull(idx).orEmpty()))
              }
              recordText(rowCells.joinToString("  "))
              val padV = 4.5f
              val cellLayouts = rowCells.mapIndexed { c, cellText ->
                val p = if (c == 0) tableCellBoldPaint else tableCellPaint
                buildLayout(cellText, p, (colWidths[c] - 8f).coerceAtLeast(24f), 1.6f)
              }
              val rowH = maxOf(18f, (cellLayouts.maxOfOrNull { it.height } ?: 10).toFloat() + padV * 2)

              // P1/P2 fix: If a table row is taller than a single printable page body, switch it to a
              // paginated stacked card with a fixed single-line banner and paginate EVERY cell
              // (including an arbitrarily long first cell) through drawLayoutPaginated so nothing can overflow.
              if (shouldFallbackTableRowToPaginatedCard(rowH, maxPageBodyHeight)) {
                ensureSpace(44f)
                val barTop = cursorY
                val bannerHeight = 17f
                canvas.drawRoundRect(
                  RectF(MARGIN_H, barTop, PAGE_WIDTH - MARGIN_H, barTop + bannerHeight),
                  4f,
                  4f,
                  zebraAltPaint
                )
                canvas.drawLine(MARGIN_H, barTop + bannerHeight, PAGE_WIDTH - MARGIN_H, barTop + bannerHeight, dividerPaint)
                canvas.drawText("AUDIT RECORD ${rowIdx + 1}", MARGIN_H + 6f, barTop + 11.5f, tableCellBoldPaint)
                cursorY = barTop + bannerHeight + 4f

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
                ensureSpace(10f)
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
                  geometryRecorder?.recordTableCell(
                    pageNumber = pageNumber,
                    rowTop = top,
                    rowBottom = top + rowH,
                    cellLeft = x,
                    cellRight = x + colWidths[c],
                    measuredTextHeight = cellLayouts[c].height.toFloat(),
                    padV = padV,
                    maxPageRight = PAGE_WIDTH - MARGIN_H
                  )
                  x += colWidths[c]
                }
                geometryRecorder?.recordBodyBlock(pageNumber, "table_row", MARGIN_H, top, PAGE_WIDTH - MARGIN_H, top + rowH)
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

    fun drawStructuredBlocks(rawContent: String, stripInternalTags: Boolean = true) {
      val blocks = DossierFormattingEngine.parseStructuredBlocks(
        raw = rawContent,
        stripInternalTags = stripInternalTags
      )
      drawReportBlocks(blocks)
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

      geometryRecorder?.recordBodyBlock(pageNumber, "section_header", MARGIN_H, barTop, PAGE_WIDTH - MARGIN_H, barTop + barH)
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

    val pillW = verdictPillTextPaint.measureText(verdictUpper).coerceAtMost(355f) + 18f
    val pillTop = cursorY + 44f
    canvas.drawRoundRect(
      RectF(MARGIN_H + 15f, pillTop, MARGIN_H + 15f + pillW, pillTop + 18.5f),
      4.5f,
      4.5f,
      verdictPillPaint
    )
    canvas.drawText(verdictUpper, MARGIN_H + 24f, pillTop + 12.4f, verdictPillTextPaint)
    recordText(verdictUpper)

    if (isProvisionalVerdict) {
      val provLabel = "PROVISIONAL"
      val provW = verdictPillTextPaint.measureText(provLabel) + 14f
      val provLeft = MARGIN_H + 15f + pillW + 8f
      if (provLeft + provW < PAGE_WIDTH - MARGIN_H - 104f) {
        val provPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(217, 119, 6) }
        canvas.drawRoundRect(
          RectF(provLeft, pillTop, provLeft + provW, pillTop + 18.5f),
          4.5f,
          4.5f,
          provPaint
        )
        canvas.drawText(provLabel, provLeft + 7f, pillTop + 12.4f, verdictPillTextPaint)
      }
      recordText("PROVISIONAL")
    }

    val bannerConfLine = "SCIENTIFIC CONF: ${rubric.refereeConfidence}/5  •  EXTRACTION CONF: $authoritativeExtractionConf/5"
    canvas.drawText(
      bannerConfLine,
      MARGIN_H + 15f,
      cursorY + 75f,
      bannerSubPaint
    )
    recordText(bannerConfLine)

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

    val scoreText = if (rubric.isAssessed) "$authoritativeScore" else "N/A"
    val scoreW = scoreBigPaint.measureText(scoreText)
    canvas.drawText(scoreText, scoreBoxLeft + (scoreBoxW - scoreW) / 2f, scoreBoxTop + 33f, scoreBigPaint)
    val subScoreText = when {
      !rubric.isAssessed -> "NOT ASSESSED"
      isProvisionalVerdict -> "/ 100 PROVISIONAL"
      else -> "/ 100 WEIGHTED"
    }
    val subW = scoreSubPaint.measureText(subScoreText)
    canvas.drawText(subScoreText, scoreBoxLeft + (scoreBoxW - subW) / 2f, scoreBoxTop + 49f, scoreSubPaint)
    recordText("Score: $scoreText $subScoreText")

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

    // Document Coverage & Validation Provenance Banner — strictly synchronized to authoritativeExtractionConf
    val reconciledCoverageDisclosure = if (review.coverageDisclosure.isNotBlank()) {
      val replaced = disclosureExtConfRegex.replace(
        review.coverageDisclosure,
        "Extraction Confidence: $authoritativeExtractionConf/5"
      )
      cleanReaderText(replaced)
    } else {
      "Extraction Confidence: $authoritativeExtractionConf/5"
    }
    val verifiedQuoteCount = findings.count { it.status == FindingStatus.VERIFIED && it.supportingEvidence.any { a -> a.exactQuote.isNotBlank() } }
    val quoteVerificationLabel = when {
      findings.isEmpty() -> "No structured quote ledger"
      verifiedQuoteCount == findings.size -> "All ${findings.size} quotes matched"
      else -> "$verifiedQuoteCount/${findings.size} quotes matched"
    }
    val scientificVerificationLabel = when {
      review.ledgerLoadState != LedgerLoadState.COMPLETE ->
        "Evidence Ledger ${review.ledgerLoadState.name.replace('_', ' ')}"
      review.validationStatus.equals("VERIFIED", ignoreCase = true) -> "Source-Anchored & Entailed"
      review.validationStatus.equals("ADJUDICATED_WITH_CAVEATS", ignoreCase = true) -> "Adjudicated with Caveats"
      review.validationStatus.equals("PARTIALLY_VALIDATED", ignoreCase = true) -> "Partially Validated"
      review.validationStatus.equals("PROVISIONAL", ignoreCase = true) -> "Provisional / Incomplete Coverage"
      else -> "Legacy Unvalidated"
    }
    val editorialValidationLabel = when (review.validationStatus.uppercase(Locale.US)) {
      "VERIFIED" -> if (review.ledgerLoadState == LedgerLoadState.COMPLETE) "Editorial Gate Passed" else "Evidence Incomplete"
      "LEGACY_UNVALIDATED" -> "Legacy Unvalidated"
      else -> review.validationStatus.replace('_', ' ')
    }
    val coverageText = buildString {
      append("Source Quote Verification: $quoteVerificationLabel")
      append("  |  Scientific Verification: $scientificVerificationLabel")
      append("  |  Editorial Validation: $editorialValidationLabel")
      append("  |  $reconciledCoverageDisclosure")
      append("  |  Generated: $dateStr")
    }
    recordText(coverageText)
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

    if (review.ledgerLoadState != LedgerLoadState.COMPLETE) {
      val degradedTitle = when (review.ledgerLoadState) {
        LedgerLoadState.CORRUPT -> "EVIDENCE LEDGER INTEGRITY WARNING — CORRUPT OR TAMPERED PAYLOAD"
        LedgerLoadState.INCOMPLETE -> "EVIDENCE LEDGER INCOMPLETE — MISSING CLOUD CHUNKS"
        LedgerLoadState.TEMPORARILY_UNAVAILABLE -> "EVIDENCE LEDGER TEMPORARILY UNAVAILABLE AT EXPORT TIME"
        LedgerLoadState.COMPLETE -> ""
      }
      val degradedDetail = "One or more chunked evidence-ledger or narrative payloads could not be verified when this PDF was generated. Do not treat this export as a complete verified dossier; reconnect and re-fetch cloud findings in Paper Critic before re-exporting."
      val warningText = "$degradedTitle: $degradedDetail"
      recordText(warningText)
      val warnLayout = buildLayout(warningText, bodyBoldPaint, CONTENT_WIDTH - 18f, 1.8f)
      val warnH = warnLayout.height + 14f
      ensureSpace(warnH + 6f)
      val isCorrupt = review.ledgerLoadState == LedgerLoadState.CORRUPT
      val warnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isCorrupt) Color.rgb(254, 242, 242) else Color.rgb(255, 251, 235)
      }
      val warnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f
        color = if (isCorrupt) Color.rgb(220, 38, 38) else Color.rgb(217, 119, 6)
      }
      val warnRect = RectF(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY + warnH)
      canvas.drawRoundRect(warnRect, 5f, 5f, warnBg)
      canvas.drawRoundRect(warnRect, 5f, 5f, warnStroke)
      canvas.save()
      canvas.translate(MARGIN_H + 9f, cursorY + 7f)
      warnLayout.draw(canvas)
      canvas.restore()
      cursorY += warnH + 9f
    }

    // =========================================================================
    // 3. STRUCTURED 6-AXIS WEIGHTED RUBRIC (DYNAMIC MULTI-LINE — ZERO TRUNCATION!)
    // =========================================================================
    data class PreparedRubricRow(
      val item: com.example.data.model.RubricCriterionEvaluation,
      val cleanJust: String,
      val justLayout: StaticLayout,
      val rowH: Float
    )

    val criteria = rubric.criteriaList()
    val rubricInnerW = CONTENT_WIDTH - 24f
    val preparedRows = criteria.map { item ->
      val cleanJust = cleanReaderText(
        DossierFormattingEngine.cleanAcademicProse(item.justification)
      )
        .replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
      val justLayout = buildLayout(cleanJust, smallMutedPaint, rubricInnerW, 1.8f)
      val rowH = 16f + justLayout.height.toFloat() + 7f
      PreparedRubricRow(item, cleanJust, justLayout, rowH)
    }

    val totalRubricH = 34f + preparedRows.sumOf { it.rowH.toDouble() }.toFloat() + 6f
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
    val confRight = "WEIGHTED: $authoritativeScore/100  •  SCI ${rubric.refereeConfidence}/5  •  EXT $authoritativeExtractionConf/5"
    recordText(confRight)
    val confRightW = sectionStagePaint.measureText(confRight)
    canvas.drawText(confRight, PAGE_WIDTH - MARGIN_H - confRightW - 12f, rubricTop + 17f, sectionStagePaint)
    canvas.drawLine(MARGIN_H + 12f, rubricTop + 24f, PAGE_WIDTH - MARGIN_H - 12f, rubricTop + 24f, dividerPaint)

    cursorY = rubricTop + 34f
    preparedRows.forEach { (item, cleanJust, justLayout, rowH) ->
      val exceedsSinglePage = rowH > maxPageBodyHeight - 24f
      val requiresLinePagination = !fitsSingleCard && (exceedsSinglePage || cursorY + rowH > PAGE_HEIGHT - MARGIN_BOTTOM)

      if (requiresLinePagination && !exceedsSinglePage && cursorY + rowH > PAGE_HEIGHT - MARGIN_BOTTOM) {
        startNewPage()
      } else if (requiresLinePagination) {
        ensureSpace(34f)
      }

      val rowBaseline = cursorY + 8f
      val rowHeaderLabel = "${item.criterion} (${item.weightPercent}%)"
      recordText("$rowHeaderLabel: ${item.score}/${item.maxScore}")
      canvas.drawText(rowHeaderLabel, MARGIN_H + 12f, rowBaseline, bodyBoldPaint)

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

      val scoreLabel = if (rubric.isAssessed) "${item.score}/${item.maxScore}" else "N/A"
      val scoreLabelPaint = TextPaint(monoPaint).apply {
        color = if (rubric.isAssessed) axisScoreColor(item.score) else Color.rgb(100, 116, 139)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
      }
      canvas.drawText(scoreLabel, barStartX + barW + 12f, rowBaseline, scoreLabelPaint)

      if (!fitsSingleCard && (exceedsSinglePage || (rowBaseline + 4.5f + justLayout.height.toFloat() > PAGE_HEIGHT - MARGIN_BOTTOM))) {
        // Line-by-line paginated justification so arbitrarily long rubric notes NEVER clip across page boundaries
        cursorY = rowBaseline + 4.5f
        drawLayoutPaginated(
          text = cleanJust,
          paint = smallMutedPaint,
          width = rubricInnerW,
          leftX = MARGIN_H + 12f,
          lineAdd = 1.8f
        )
        cursorY += 7f
      } else {
        recordText(cleanJust)
        canvas.save()
        canvas.translate(MARGIN_H + 12f, rowBaseline + 4.5f)
        justLayout.draw(canvas)
        canvas.restore()
        cursorY += rowH
      }
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

    // Section 03: Quantitative & Statistical Audit (direct typed ReportBlock rendering when supplied)
    if (!precomputedQuantBlocks.isNullOrEmpty() || review.mathAuditReport.isNotBlank()) {
      drawSectionHeader("03", "QUANTITATIVE, STATISTICAL & MATHEMATICAL AUDIT", "FORENSICS & STATS")
      if (!precomputedQuantBlocks.isNullOrEmpty()) {
        drawReportBlocks(precomputedQuantBlocks)
      }
      if (review.mathAuditReport.isNotBlank()) {
        drawStructuredBlocks(review.mathAuditReport, stripInternalTags = true)
      }
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
    val alignedEditorNote = com.example.data.ai.Reviewer2PipelineService.alignEditorNoteWithVerdictAndMode(
      rawEditorNote = review.editorNote,
      findings = findings,
      resolvedVerdict = review.verdict,
      reviewMode = reviewMode
    )
    drawStructuredBlocks(alignedEditorNote, stripInternalTags = true)

    // Author personal notes (always included when present)
    if (review.personalNotes.isNotBlank()) {
      drawSectionHeader("07", "RESEARCHER NOTES & REVISION SCRATCHPAD", "USER NOTES")
      drawStructuredBlocks(review.personalNotes, stripInternalTags = false)
    }

    // =========================================================================
    // 5. OPTIONAL FULL TECHNICAL APPENDIX (Multi-Agent Raw Transcripts + Technical Provenance A4)
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
      if (findings.isNotEmpty()) {
        drawSectionHeader("A4", "APPENDIX: TECHNICAL PROVENANCE & ADJUDICATION RECORD", "PROVENANCE")
        val provRows = findings.map { f ->
          val anchor = f.supportingEvidence.firstOrNull()
          val startOff = anchor?.startOffset
          val endOff = anchor?.endOffset
          val offsets = if (anchor != null && ((startOff != null && startOff > 0) || (endOff != null && endOff > 0))) {
            "P${anchor.pageNumber} [${startOff ?: 0}–${endOff ?: 0}]"
          } else if (anchor != null && anchor.pageNumber > 0) {
            "P${anchor.pageNumber}"
          } else {
            "Not anchored"
          }
          val provDetail = buildString {
            if (f.provenanceSummary.isNotBlank()) append(f.provenanceSummary)
            if (f.verificationNotes.isNotBlank()) {
              if (isNotEmpty()) append(" • ")
              append("Defense/Verification: ${f.verificationNotes}")
            }
            if (f.overturnEvidence.isNotBlank()) {
              if (isNotEmpty()) append(" • ")
              append("Minimum overturn evidence: ${f.overturnEvidence}")
            }
          }.ifBlank { "Deterministic audit record" }
          listOf(
            f.id,
            offsets,
            ReportingStatus.fromNameOrDefault(f.reportingStatus).displayLabel,
            DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(provDetail), 240)
          )
        }
        drawReportBlocks(
          listOf(
            ReportBlock.AuditTable(
              headers = listOf("Finding", "Source Offsets", "Reporting Status", "Provenance, Defense & Overturn Evidence"),
              rows = provRows
            )
          )
        )
      }
    }

    drawFooter(canvas, pageNumber)
    if (cursorY > PAGE_HEIGHT - MARGIN_BOTTOM + 4f) {
      clippedContentTracker?.set(0, true)
    }
    pageCountTracker?.set(0, pageNumber)
    if (!useBitmapFallback) {
      page?.let { document.finishPage(it) }
      document.writeTo(outputStream)
    } else {
      fallbackBitmap?.recycle()
      // Headless JVM test fallback: emit a minimal valid PDF container carrying the rendered text metadata
      val safePayload = (renderedTextSink?.toString() ?: normalizedTitle).toByteArray(Charsets.UTF_8)
      val header = "%PDF-1.4\n% Headless Robolectric canvas verification payload ($pageNumber page(s))\n".toByteArray(Charsets.UTF_8)
      outputStream.write(header)
      outputStream.write(safePayload)
      outputStream.write("\n%%EOF\n".toByteArray(Charsets.UTF_8))
    }
    } finally {
      runCatching { document.close() }
    }
  }

  private fun verdictAccentColor(verdict: String): Int {
    val upper = verdict.uppercase(Locale.US)
    return when {
      upper in setOf("ACCEPT", "HIGH_CREDIBILITY", "HIGH_CERTAINTY") -> Color.rgb(22, 163, 74)
      upper in setOf("MINOR_REVISION", "MINOR_REVISION_WITH_CAVEATS", "SOUND_WITH_CAVEATS", "MODERATE_CERTAINTY") -> Color.rgb(0, 98, 227)
      upper in setOf(
        "MAJOR_REVISION",
        "REQUIRES_CORRECTION",
        "LOW_CERTAINTY",
        "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN",
        "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      ) -> Color.rgb(217, 119, 6)
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

  internal data class PaginatedLinePlacement(
    val pageIndex: Int,
    val yTop: Float,
    val yBottom: Float,
    val text: String
  )

  internal data class StackedCardLayoutPlan(
    val totalPagesUsed: Int,
    val maxLineBottomY: Float,
    val pageBodyBottomLimit: Float,
    val placements: List<PaginatedLinePlacement>
  )

  /**
   * Deterministic coordinate-level pagination simulator matching the stacked-card renderer:
   * fixed 17pt single-line banner followed by line-by-line paginated fields for EVERY cell
   * (including arbitrarily long first-cell titles). Proves no line ever exceeds [pageBottomLimit].
   */
  internal fun planStackedCardPagination(
    rowIndex: Int,
    headers: List<String>,
    rowCells: List<String>,
    charsPerLine: Int = 78,
    lineHeightPt: Float = 11.5f,
    pageTopY: Float = MARGIN_TOP_SUBSEQUENT,
    pageBottomLimit: Float = PAGE_HEIGHT - MARGIN_BOTTOM,
    initialCursorY: Float = MARGIN_TOP_SUBSEQUENT
  ): StackedCardLayoutPlan {
    var pageIdx = 1
    var cursorY = initialCursorY
    val placements = mutableListOf<PaginatedLinePlacement>()
    val bannerH = 17f

    fun startNextPage() {
      pageIdx++
      cursorY = pageTopY
    }

    if (cursorY + 44f > pageBottomLimit) {
      startNextPage()
    }
    placements.add(
      PaginatedLinePlacement(
        pageIndex = pageIdx,
        yTop = cursorY,
        yBottom = cursorY + bannerH,
        text = "AUDIT RECORD ${rowIndex + 1}"
      )
    )
    cursorY += bannerH + 4f

    rowCells.forEachIndexed { c, cellText ->
      if (cellText.isBlank()) return@forEachIndexed
      val headerLabel = headers.getOrNull(c).orEmpty().ifBlank { "Column ${c + 1}" }
      val fullText = "${headerLabel.uppercase(Locale.US)}: $cellText"
      val wrappedLines = fullText.chunked(charsPerLine.coerceAtLeast(16))
      for (line in wrappedLines) {
        if (cursorY + lineHeightPt > pageBottomLimit) {
          startNextPage()
        }
        placements.add(
          PaginatedLinePlacement(
            pageIndex = pageIdx,
            yTop = cursorY,
            yBottom = cursorY + lineHeightPt,
            text = line
          )
        )
        cursorY += lineHeightPt
      }
      cursorY += 2.5f
    }

    return StackedCardLayoutPlan(
      totalPagesUsed = pageIdx,
      maxLineBottomY = placements.maxOfOrNull { it.yBottom } ?: pageTopY,
      pageBodyBottomLimit = pageBottomLimit,
      placements = placements
    )
  }
}
