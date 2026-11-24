package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

private const val EXTRACTOR_TAG = "ManuscriptExtractor"

enum class ManuscriptInputFormat(val displayBadge: String) {
  PDF("PDF Document"),
  WORD("Word Document (.docx / .doc)"),
  TEXT("Plain Text / LaTeX / Markdown")
}

data class ExtractedPage(
  val pageNumber: Int,
  val text: String,
  val charCount: Int,
  val wordCount: Int,
  val hasLowTextDensity: Boolean,
  val mentionsFiguresOrTables: Boolean
)

enum class CoverageStatus(val label: String) {
  COMPLETE("Complete"),
  PARTIAL("Partial"),
  UNKNOWN("Unknown")
}

enum class CoverageLevel(val label: String) {
  COMPLETE("Complete"),
  PARTIAL("Partial"),
  LIMITED("Limited"),
  NOT_SUPPLIED("Not supplied"),
  UNKNOWN("Unknown")
}

data class CoverageMetric(
  val label: String,
  val level: CoverageLevel,
  val detail: String
)

data class CoverageSummary(
  val totalPages: Int,
  val extractedPages: Int,
  val omittedPages: List<Int>,
  val textExtractionCoverage: CoverageLevel = CoverageLevel.COMPLETE,
  val tableFigureCoverage: CoverageLevel = CoverageLevel.COMPLETE,
  val quantitativeClaimCoverage: CoverageLevel = CoverageLevel.COMPLETE,
  val supplementCoverage: CoverageLevel = CoverageLevel.NOT_SUPPLIED
) {
  fun toFormattedLines(): String = buildString {
    append("Page coverage: $extractedPages/$totalPages")
    append(" • Text extraction: ${textExtractionCoverage.label}")
    append(" • Tables/figures parseability: ${tableFigureCoverage.label}")
    append(" • Quantitative reconstruction: ${quantitativeClaimCoverage.label}")
    append(" • Supplementary material: ${supplementCoverage.label}")
    if (omittedPages.isNotEmpty()) {
      append(" • Omitted pages: ${omittedPages.joinToString(",") { "P$it" }}")
    }
  }
}

data class ConfidenceSummary(
  val scientificConfidence: Int,
  val extractionConfidence: Int,
  val pageCoverage: CoverageMetric,
  val textCoverage: CoverageMetric,
  val quantitativeCoverage: CoverageMetric,
  val reasons: List<String> = emptyList(),
  val coverageSummary: CoverageSummary? = null
)

data class DocumentCoverageReport(
  val totalPages: Int,
  val extractedPages: Int,
  val totalChars: Int,
  val totalWords: Int,
  val lowTextPages: List<Int> = emptyList(),
  val omittedPages: List<Int> = emptyList(),
  val visionPagesAttached: Int = 0,
  val visionPageNumbers: List<Int> = emptyList(),
  val extractionEngine: String = "PDFBox-Android",
  val extractionConfidence: Int = 5, // 1–5 scale reflecting fidelity of text & layout extraction; 0 = UNKNOWN
  val warnings: List<String> = emptyList(),
  val coverageStatus: CoverageStatus = when {
    extractionConfidence <= 0 -> CoverageStatus.UNKNOWN
    omittedPages.isEmpty() && extractionConfidence >= 4 && warnings.isEmpty() -> CoverageStatus.COMPLETE
    else -> CoverageStatus.PARTIAL
  }
) {
  val isHighCoverage: Boolean
    get() = coverageStatus == CoverageStatus.COMPLETE && extractionConfidence >= 4 && warnings.isEmpty() && omittedPages.isEmpty()

  val isCoverageUnknown: Boolean
    get() = coverageStatus == CoverageStatus.UNKNOWN || extractionConfidence == EXTRACTION_CONFIDENCE_UNKNOWN

  fun toCoverageSummary(
    unsupportedInputCount: Int = 0,
    reconstructedClaimCount: Int = 0,
    hasSupplementReference: Boolean = false
  ): CoverageSummary {
    if (isCoverageUnknown) {
      return CoverageSummary(
        totalPages = totalPages,
        extractedPages = extractedPages,
        omittedPages = omittedPages,
        textExtractionCoverage = CoverageLevel.UNKNOWN,
        tableFigureCoverage = CoverageLevel.UNKNOWN,
        quantitativeClaimCoverage = CoverageLevel.UNKNOWN,
        supplementCoverage = CoverageLevel.UNKNOWN
      )
    }
    val textCov = when {
      omittedPages.isEmpty() && extractionConfidence >= 4 -> CoverageLevel.COMPLETE
      extractedPages > 0 -> CoverageLevel.PARTIAL
      else -> CoverageLevel.LIMITED
    }
    val tableFigCov = when {
      lowTextPages.isNotEmpty() || warnings.any {
        it.contains("scanned", true) || it.contains("image", true) || it.contains("table", true) || it.contains("figure", true)
      } -> CoverageLevel.PARTIAL
      omittedPages.isNotEmpty() -> CoverageLevel.PARTIAL
      else -> CoverageLevel.COMPLETE
    }
    val quantCov = when {
      unsupportedInputCount > 0 && reconstructedClaimCount == 0 -> CoverageLevel.LIMITED
      unsupportedInputCount > 0 -> CoverageLevel.PARTIAL
      omittedPages.isNotEmpty() || lowTextPages.isNotEmpty() -> CoverageLevel.PARTIAL
      else -> CoverageLevel.COMPLETE
    }
    val suppCov = if (hasSupplementReference) CoverageLevel.NOT_SUPPLIED else CoverageLevel.NOT_SUPPLIED
    return CoverageSummary(
      totalPages = totalPages,
      extractedPages = extractedPages,
      omittedPages = omittedPages,
      textExtractionCoverage = textCov,
      tableFigureCoverage = tableFigCov,
      quantitativeClaimCoverage = quantCov,
      supplementCoverage = suppCov
    )
  }

  fun toDisclosureLine(
    overrideExtractionConfidence: Int? = null,
    coverageSummary: CoverageSummary? = null
  ): String = buildString {
    val effectiveConf = overrideExtractionConfidence ?: extractionConfidence
    if (isCoverageUnknown && overrideExtractionConfidence == null) {
      append("Coverage: Unknown • Extraction Confidence: Unknown (strong conclusions disabled until document coverage is supplied)")
      if (warnings.isNotEmpty()) append(" • Warnings: ${warnings.joinToString("; ")}")
      return@buildString
    }
    append("$extractedPages/$totalPages page(s) extracted")
    append(" • %,d words".format(Locale.US, totalWords))
    append(" • %,d chars".format(Locale.US, totalChars))
    if (visionPagesAttached > 0) {
      val pageList = visionPageNumbers.joinToString(",") { "P$it" }
      append(" • $visionPagesAttached visual page(s) [$pageList]")
    }
    append(" • Engine: $extractionEngine")
    append(" • Coverage: ${coverageStatus.label}")
    append(" • Extraction Confidence: $effectiveConf/5")
    if (coverageSummary != null) {
      append(" • Page coverage: ${coverageSummary.extractedPages}/${coverageSummary.totalPages}")
      append(" • Text extraction: ${coverageSummary.textExtractionCoverage.label}")
      append(" • Tables/figures parseability: ${coverageSummary.tableFigureCoverage.label}")
      append(" • Quantitative reconstruction: ${coverageSummary.quantitativeClaimCoverage.label}")
      append(" • Supplementary material: ${coverageSummary.supplementCoverage.label}")
    }
    if (omittedPages.isNotEmpty()) {
      append(" • Omitted pages: ${omittedPages.joinToString(",") { "P$it" }}")
    }
    if (lowTextPages.isNotEmpty()) {
      append(" • Low-text/figure pages: ${lowTextPages.joinToString(",") { "P$it" }}")
    }
    if (warnings.isNotEmpty()) {
      append(" • Warnings: ${warnings.joinToString("; ")}")
    }
  }

  companion object {
    const val EXTRACTION_CONFIDENCE_UNKNOWN: Int = 0

    fun unknown(reason: String = "Coverage metadata was not supplied; strong conclusions disabled."): DocumentCoverageReport =
      DocumentCoverageReport(
        totalPages = 0,
        extractedPages = 0,
        totalChars = 0,
        totalWords = 0,
        extractionEngine = "Unknown",
        extractionConfidence = EXTRACTION_CONFIDENCE_UNKNOWN,
        warnings = listOf(reason),
        coverageStatus = CoverageStatus.UNKNOWN
      )

    fun forCompleteText(
      text: String,
      engine: String = "Direct Text Input"
    ): DocumentCoverageReport {
      val pageMarkers = Regex("""\[PAGE\s+(\d+)\]""", RegexOption.IGNORE_CASE).findAll(text).mapNotNull {
        it.groupValues[1].toIntOrNull()
      }.toList()
      val pages = (pageMarkers.maxOrNull() ?: ((text.length / 2200) + 1)).coerceAtLeast(1)
      val words = if (text.isBlank()) 0 else text.trim().split(Regex("""\s+""")).size
      return DocumentCoverageReport(
        totalPages = pages,
        extractedPages = pages,
        totalChars = text.length,
        totalWords = words,
        extractionEngine = engine,
        extractionConfidence = 5,
        warnings = emptyList(),
        coverageStatus = CoverageStatus.COMPLETE
      )
    }
  }
}

data class ExtractedManuscriptFile(
  val fileName: String,
  val format: ManuscriptInputFormat,
  val inferredTitle: String,
  val pageCount: Int,
  val wordCount: Int,
  val extractedText: String,
  val pages: List<ExtractedPage> = emptyList(),
  val pdfPageBitmaps: List<Bitmap> = emptyList(),
  val visionPageNumbers: List<Int> = emptyList(),
  val coverageReport: DocumentCoverageReport = DocumentCoverageReport(
    totalPages = pageCount,
    extractedPages = pageCount,
    totalChars = extractedText.length,
    totalWords = wordCount,
    extractionEngine = format.displayBadge,
    extractionConfidence = 5
  ),
  val summaryNotice: String
)

object ManuscriptExtractor {

  private const val MAX_PDF_INPUT_BYTES = 40_000_000 // 40 MB full journal articles & supplements
  private const val MAX_TEXT_INPUT_BYTES = 10_000_000 // 10 MB plain text / LaTeX / Markdown cap
  private const val MAX_ZIP_ENTRIES = 512
  private const val MAX_DOCX_XML_BYTES = 16_000_000
  private const val MAX_ZIP_CODE_ENTRY_BYTES = 512_000
  private const val MAX_ZIP_CODE_TOTAL_BYTES = 4_000_000
  private const val MAX_EXTRACTED_CHARS = 400_000
  private const val MAX_VISION_PAGES = 6

  @Volatile
  private var pdfBoxInitialized = false

  private fun ensurePdfBoxInitialized(context: Context) {
    if (!pdfBoxInitialized) {
      synchronized(this) {
        if (!pdfBoxInitialized) {
          runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
          }.onFailure { err ->
            Log.w(EXTRACTOR_TAG, "PDFBoxResourceLoader init warning", err)
          }
        }
      }
    }
  }

  suspend fun extractFromUri(
    context: Context,
    uri: Uri,
    renderPdfBitmaps: Boolean = true
  ): Result<ExtractedManuscriptFile> = withContext(Dispatchers.IO) {
    try {
      val contentResolver = context.contentResolver
      val fileName = resolveFileName(context, uri) ?: "manuscript_document"
      val lowerName = fileName.lowercase(Locale.US)
      val mimeType = contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()

      val extracted = when {
        mimeType.contains("pdf") || lowerName.endsWith(".pdf") -> {
          extractPdfContent(context, uri, fileName, renderPdfBitmaps)
        }
        mimeType.contains("officedocument.wordprocessingml") || lowerName.endsWith(".docx") -> {
          extractDocxContent(context, uri, fileName)
        }
        mimeType.contains("msword") || mimeType.contains("rtf") ||
          lowerName.endsWith(".doc") || lowerName.endsWith(".rtf") -> {
          extractLegacyDocOrRtfContent(context, uri, fileName)
        }
        else -> {
          extractPlainTextContent(context, uri, fileName)
        }
      }
      Result.success(extracted)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }

  private fun resolveFileName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (nameIndex >= 0 && cursor.moveToFirst()) {
        cursor.getString(nameIndex)
      } else {
        null
      }
    }
  }

  private fun extractPlainTextContent(
    context: Context,
    uri: Uri,
    fileName: String
  ): ExtractedManuscriptFile {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
      readBytesBounded(stream, MAX_TEXT_INPUT_BYTES)
    } ?: throw IllegalStateException("Unable to read selected text file.")
    val rawText = rawBytes.toString(Charsets.UTF_8)

    return extractPlainTextFromString(
      rawText = rawText,
      fileName = fileName,
      maxChars = MAX_EXTRACTED_CHARS
    )
  }

  /**
   * Pure testable plain-text extractor using synthetic page anchors (`[PAGE N]`), high-signal
   * page selection when exceeding [maxChars], explicit omitted-page tracking, and calibrated confidence.
   */
  internal fun extractPlainTextFromString(
    rawText: String,
    fileName: String,
    maxChars: Int = MAX_EXTRACTED_CHARS
  ): ExtractedManuscriptFile {
    val cleanRaw = rawText.trim()
    val assembled = assemblePaginatedUnstructuredText(
      rawText = cleanRaw,
      maxChars = maxChars,
      baseConfidence = 4, // Unstructured plain text has no native page/figure layout metadata
      engineLabel = "UTF-8 Text Reader (Synthetic Page-Anchored)",
      initialWarnings = emptyList()
    )
    val firstLineTitle = cleanRaw.lineSequence()
      .map { it.trim().removePrefix("#").trim() }
      .firstOrNull { it.length in 5..180 }
      ?: cleanBaseTitle(fileName)

    val omissionNote = if (assembled.omittedPages.isNotEmpty()) {
      " (${assembled.omittedPages.size} synthetic page(s) omitted)"
    } else ""

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.TEXT,
      inferredTitle = firstLineTitle,
      pageCount = assembled.totalPages,
      wordCount = assembled.totalWords,
      extractedText = assembled.extractedText,
      pages = assembled.allPages,
      coverageReport = assembled.coverageReport,
      summaryNotice = "Loaded Text (${assembled.totalWords} words, ~${assembled.totalPages} pages$omissionNote) from $fileName"
    )
  }

  /**
   * Extracts structured paragraphs, tables, and OMML math runs from a Microsoft Word (.docx) ZIP archive.
   */
  private fun extractDocxContent(
    context: Context,
    uri: Uri,
    fileName: String
  ): ExtractedManuscriptFile {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use {
      readBytesBounded(it, 20_000_000)
    } ?: throw IllegalStateException("Unable to open Word .docx document.")

    return extractDocxFromBytes(
      rawBytes = rawBytes,
      fileName = fileName,
      maxChars = MAX_EXTRACTED_CHARS
    )
  }

  /**
   * Pure byte-level OOXML (.docx) extractor with synthetic page-anchored retention, explicit
   * omitted-page accounting when oversized, and calibrated extraction confidence.
   */
  internal fun extractDocxFromBytes(
    rawBytes: ByteArray,
    fileName: String,
    maxChars: Int = MAX_EXTRACTED_CHARS
  ): ExtractedManuscriptFile {
    val sb = StringBuilder()
    var foundDocumentXml = false
    val maxExtractedBuffer = maxOf(maxChars * 4, 1_600_000)

    runCatching {
      ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
        var entry = zis.nextEntry
        var entryCount = 0
        while (entry != null && entryCount < MAX_ZIP_ENTRIES) {
          entryCount++
          val entryName = entry.name.orEmpty()
          val isSafePath = !entryName.contains("..") && !entryName.startsWith("/") && !entryName.startsWith("\\")
          if (isSafePath && entryName.equals("word/document.xml", ignoreCase = true)) {
            foundDocumentXml = true
            val xmlBytes = readBytesBounded(zis, MAX_DOCX_XML_BYTES)
            val parser = Xml.newPullParser()
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            runCatching { parser.setFeature(XmlPullParser.FEATURE_VALIDATION, false) }
            parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT && sb.length < maxExtractedBuffer) {
              when (eventType) {
                XmlPullParser.START_TAG -> {
                  val tag = parser.name.substringAfterLast(':')
                  when (tag) {
                    "t" -> {
                      val text = parser.nextText()
                      if (text.isNotEmpty()) sb.append(text)
                    }
                    "tab" -> sb.append("\t")
                    "br", "cr" -> sb.append("\n")
                  }
                }
                XmlPullParser.END_TAG -> {
                  val tag = parser.name.substringAfterLast(':')
                  if (tag == "p" || tag == "tr") {
                    sb.append("\n")
                  } else if (tag == "tc") {
                    sb.append(" | ")
                  }
                }
              }
              eventType = parser.next()
            }
            break
          }
          zis.closeEntry()
          entry = zis.nextEntry
        }
      }
    }

    val warnings = mutableListOf<String>()
    val rawBody = if (foundDocumentXml && sb.isNotBlank()) {
      sb.toString()
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    } else {
      warnings.add("word/document.xml not found; fell back to ASCII scan (degraded extraction).")
      extractReadableAsciiRuns(rawBytes)
    }

    val nonEmptyBody = rawBody.ifBlank {
      warnings.add("Extracted body was empty.")
      "WORD MANUSCRIPT (.docx): $fileName"
    }

    val assembled = assemblePaginatedUnstructuredText(
      rawText = nonEmptyBody,
      maxChars = maxChars,
      baseConfidence = if (warnings.isEmpty()) 4 else 2,
      engineLabel = "OOXML XML PullParser (Synthetic Page-Anchored)",
      initialWarnings = warnings
    )
    val firstLineTitle = nonEmptyBody.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.length in 6..180 && !it.startsWith("WORD MANUSCRIPT") }
      ?: cleanBaseTitle(fileName)

    val omissionNote = if (assembled.omittedPages.isNotEmpty()) {
      " (${assembled.omittedPages.size} synthetic page(s) omitted)"
    } else ""

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.WORD,
      inferredTitle = firstLineTitle,
      pageCount = assembled.totalPages,
      wordCount = assembled.totalWords,
      extractedText = assembled.extractedText,
      pages = assembled.allPages,
      coverageReport = assembled.coverageReport,
      summaryNotice = "Loaded Word Document (${assembled.totalWords} words, ~${assembled.totalPages} pages$omissionNote) from $fileName"
    )
  }

  private fun extractLegacyDocOrRtfContent(
    context: Context,
    uri: Uri,
    fileName: String
  ): ExtractedManuscriptFile {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use {
      readBytesBounded(it, 12_000_000)
    } ?: throw IllegalStateException("Unable to read Word document.")

    val rawString = String(rawBytes, Charsets.ISO_8859_1)
    val isRtf = rawString.trimStart().startsWith("{\\rtf")
    val cleaned = if (isRtf) {
      rawString
        .replace(Regex("""\\[a-zA-Z]+\d*\s?"""), " ")
        .replace(Regex("""[{}]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    } else {
      extractReadableAsciiRuns(rawBytes)
    }

    val finalBody = cleaned.ifBlank { "WORD MANUSCRIPT: $fileName" }
    val initialWarnings = if (isRtf) {
      emptyList()
    } else {
      listOf("Legacy binary .doc format; convert to .pdf or .docx for exact layout & table fidelity.")
    }
    val assembled = assemblePaginatedUnstructuredText(
      rawText = finalBody,
      maxChars = MAX_EXTRACTED_CHARS,
      baseConfidence = if (isRtf) 3 else 2,
      engineLabel = if (isRtf) "RTF Text Stripper (Synthetic Page-Anchored)" else "Legacy Binary .doc Fallback",
      initialWarnings = initialWarnings
    )

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.WORD,
      inferredTitle = cleanBaseTitle(fileName),
      pageCount = assembled.totalPages,
      wordCount = assembled.totalWords,
      extractedText = assembled.extractedText,
      pages = assembled.allPages,
      coverageReport = assembled.coverageReport,
      summaryNotice = "Loaded Word Document (${assembled.totalWords} words, ~${assembled.totalPages} pages) from $fileName"
    )
  }

  private data class UnstructuredAssemblyResult(
    val extractedText: String,
    val allPages: List<ExtractedPage>,
    val omittedPages: List<Int>,
    val totalPages: Int,
    val totalWords: Int,
    val coverageReport: DocumentCoverageReport
  )

  private fun assemblePaginatedUnstructuredText(
    rawText: String,
    maxChars: Int,
    baseConfidence: Int,
    engineLabel: String,
    initialWarnings: List<String>
  ): UnstructuredAssemblyResult {
    val figureOrTableRegex = Regex(
      """\b(?:figure|fig\.|table|forest\s+plot|funnel\s+plot|prisma\s+flow)\s*\d*\b""",
      RegexOption.IGNORE_CASE
    )
    // Partition into synthetic ~2,400-char (~350-word) pages on paragraph/line boundaries
    val syntheticPageTargetChars = 2_400
    val chunks = mutableListOf<String>()
    if (rawText.length <= syntheticPageTargetChars) {
      chunks.add(rawText)
    } else {
      var cursor = 0
      while (cursor < rawText.length) {
        val idealEnd = (cursor + syntheticPageTargetChars).coerceAtMost(rawText.length)
        val splitEnd = if (idealEnd >= rawText.length) {
          rawText.length
        } else {
          val paraBreak = rawText.lastIndexOf("\n\n", idealEnd)
          val lineBreak = rawText.lastIndexOf('\n', idealEnd)
          val spaceBreak = rawText.lastIndexOf(' ', idealEnd)
          when {
            paraBreak > cursor + syntheticPageTargetChars / 2 -> paraBreak + 2
            lineBreak > cursor + syntheticPageTargetChars / 2 -> lineBreak + 1
            spaceBreak > cursor + syntheticPageTargetChars / 2 -> spaceBreak + 1
            else -> idealEnd
          }
        }
        chunks.add(rawText.substring(cursor, splitEnd).trim())
        cursor = splitEnd
      }
    }

    val allPages = chunks.mapIndexed { idx, chunk ->
      val words = countWords(chunk)
      ExtractedPage(
        pageNumber = idx + 1,
        text = chunk,
        charCount = chunk.length,
        wordCount = words,
        hasLowTextDensity = words < 35,
        mentionsFiguresOrTables = figureOrTableRegex.containsMatchIn(chunk)
      )
    }

    val selection = selectPagesWithinCharBudget(allPages, maxChars)
    val warnings = initialWarnings.toMutableList()
    if (selection.omittedPages.isNotEmpty()) {
      warnings.add(
        "Source exceeded %,d-character extraction ceiling; synthetic page(s) %s omitted after preserving opening, high-signal Results/Methods/Tables, and closing pages.".format(
          Locale.US,
          maxChars,
          selection.omittedPages.joinToString(",") { "P$it" }
        )
      )
    }

    val replacementChars = rawText.count { it == '\uFFFD' }
    val alphaNumRatio = rawText.count { it.isLetterOrDigit() }.toDouble() / rawText.length.coerceAtLeast(1).toDouble()
    var confidence = baseConfidence.coerceIn(1, 5)
    if (replacementChars > 5 || alphaNumRatio < 0.55) {
      confidence = minOf(confidence, 2)
      warnings.add("Detected encoding artifacts or non-alphanumeric noise in extracted text.")
    }
    if (selection.omittedPages.isNotEmpty()) {
      confidence = minOf(confidence, 3)
    }
    if (selection.partiallyRetainedPages.isNotEmpty()) {
      confidence = minOf(confidence, 3)
      warnings.add(
        "Synthetic page(s) ${selection.partiallyRetainedPages.joinToString(",") { "P$it" }} partially retained to fit character ceiling."
      )
    }

    val totalWords = countWords(selection.assembledText)
    val extractedCount = (allPages.size - selection.omittedPages.size).coerceAtLeast(0)
    val coverage = DocumentCoverageReport(
      totalPages = allPages.size.coerceAtLeast(1),
      extractedPages = extractedCount,
      omittedPages = selection.omittedPages,
      totalChars = selection.assembledText.length,
      totalWords = totalWords,
      extractionEngine = engineLabel,
      extractionConfidence = confidence,
      warnings = warnings
    )
    return UnstructuredAssemblyResult(
      extractedText = selection.assembledText,
      allPages = allPages,
      omittedPages = selection.omittedPages,
      totalPages = allPages.size.coerceAtLeast(1),
      totalWords = totalWords,
      coverageReport = coverage
    )
  }

  /**
   * Full-document PDF extraction using Apache PDFBox Android (`PDFTextStripper` with CMap / ToUnicode
   * decoding and spatial sorting) preserving every page with explicit `[PAGE N]` coordinate markers,
   * paired with smart `PdfRenderer` page-bitmap selection prioritizing Page 1 + Figure/Table/Low-Text pages.
   */
  private fun extractPdfContent(
    context: Context,
    uri: Uri,
    fileName: String,
    renderPdfBitmaps: Boolean
  ): ExtractedManuscriptFile {
    ensurePdfBoxInitialized(context)

    val rawBytes = context.contentResolver.openInputStream(uri)?.use {
      readBytesBounded(it, MAX_PDF_INPUT_BYTES)
    } ?: ByteArray(0)

    val byteExtraction = extractPdfPagesFromBytes(
      rawBytes = rawBytes,
      fileName = fileName,
      maxChars = MAX_EXTRACTED_CHARS
    )
    val extractedPages = byteExtraction.extractedPages
    val warnings = byteExtraction.warnings.toMutableList()
    var totalPageCount = byteExtraction.totalPageCount
    var extractionEngine = byteExtraction.extractionEngine
    val omittedPages = byteExtraction.omittedPages.toMutableList()

    if (rawBytes.size >= MAX_PDF_INPUT_BYTES) {
      warnings.add("PDF exceeded 40 MB input ceiling; trailing bytes were not read.")
    }

    // 2. Smart visual page rendering via Android PdfRenderer:
    //    Prioritize Page 1 (title/abstract) + pages containing Figures/Tables/Results + low-text-density pages
    //    (up to MAX_VISION_PAGES = 6) so charts like Figure 2 on Page 9 are inspected visually!
    val renderedPages = mutableListOf<Bitmap>()
    val renderedPageNumbers = mutableListOf<Int>()

    runCatching {
      context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        PdfRenderer(pfd).use { renderer ->
          totalPageCount = maxOf(totalPageCount, renderer.pageCount.coerceAtLeast(1))
          if (renderPdfBitmaps) {
            val targetPageIndices = selectHighValueVisionPages(
              totalPages = renderer.pageCount,
              extractedPages = extractedPages,
              maxPages = MAX_VISION_PAGES
            )
            for (pageIdx in targetPageIndices) {
              renderer.openPage(pageIdx).use { page ->
                val targetWidth = 900
                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                val targetHeight = (page.height * scale).toInt().coerceIn(420, 1450)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                renderedPages.add(bitmap)
                renderedPageNumbers.add(pageIdx + 1)
              }
            }
          }
        }
      }
    }.onFailure { err ->
      Log.w(EXTRACTOR_TAG, "PdfRenderer failed for $fileName", err)
      warnings.add("Visual page renderer unavailable for this PDF; figure graphics could not be visually inspected.")
    }

    val lowTextPageNums = extractedPages.filter { it.hasLowTextDensity }.map { it.pageNumber }
    val extractedNonEmptyCount = extractedPages.count { it.wordCount >= 10 && it.pageNumber !in omittedPages }

    val finalText = if (byteExtraction.isScannedVisionOnly) {
      extractionEngine = "Vision-Only PDF (Scanned / Image-Based)"
      warnings.removeAll { it.contains("No embedded text layer", ignoreCase = true) }
      warnings.add("No embedded text layer detected (scanned PDF). Analysis relies on ${renderedPages.size} rendered page image(s).")
      "PDF MANUSCRIPT (SCANNED / IMAGE-BASED): $fileName ($totalPageCount pages, ${renderedPages.size} page visuals attached).\n" +
        "Inferred Title: ${cleanBaseTitle(fileName)}"
    } else {
      byteExtraction.extractedText
    }

    if (lowTextPageNums.isNotEmpty() && lowTextPageNums.size < totalPageCount) {
      val uninspectedLowText = lowTextPageNums.filter { it !in renderedPageNumbers }
      if (uninspectedLowText.isNotEmpty()) {
        warnings.add("Low-text / graphic-heavy page(s) ${uninspectedLowText.joinToString(",") { "P$it" }} were not visually rendered.")
      }
    }

    val totalWords = countWords(finalText)
    val extractionConfidence = computePdfExtractionConfidence(
      totalPages = totalPageCount,
      extractedNonEmptyPages = extractedNonEmptyCount,
      totalWords = totalWords,
      isPdfBoxUsed = byteExtraction.pdfBoxSucceeded,
      warningCount = warnings.size + if (omittedPages.isNotEmpty()) 1 else 0
    )

    val coverage = DocumentCoverageReport(
      totalPages = totalPageCount,
      extractedPages = if (extractedPages.isNotEmpty()) extractedNonEmptyCount else if (totalWords > 80) totalPageCount else 0,
      totalChars = finalText.length,
      totalWords = totalWords,
      lowTextPages = lowTextPageNums,
      omittedPages = omittedPages.distinct().sorted(),
      visionPagesAttached = renderedPages.size,
      visionPageNumbers = renderedPageNumbers,
      extractionEngine = extractionEngine,
      extractionConfidence = extractionConfidence,
      warnings = warnings.distinct()
    )

    val inferredTitle = inferTitleFromFirstPage(extractedPages.firstOrNull()?.text.orEmpty(), fileName)

    val notice = if (warnings.isEmpty() && omittedPages.isEmpty()) {
      "Extracted $totalPageCount/$totalPageCount pages (%,d words, ${renderedPages.size} visual page(s)) via PDFBox".format(Locale.US, totalWords)
    } else {
      val primaryNote = warnings.firstOrNull()
        ?: "Omitted pages: ${omittedPages.joinToString(",") { "P$it" }}"
      "Imported PDF ($totalPageCount pages, %,d words • Coverage ${extractionConfidence}/5 — $primaryNote)".format(Locale.US, totalWords)
    }

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.PDF,
      inferredTitle = inferredTitle,
      pageCount = totalPageCount,
      wordCount = totalWords,
      extractedText = finalText,
      pages = extractedPages,
      pdfPageBitmaps = renderedPages,
      visionPageNumbers = renderedPageNumbers,
      coverageReport = coverage,
      summaryNotice = notice
    )
  }

  internal data class PdfByteExtractionResult(
    val extractedText: String,
    val extractedPages: List<ExtractedPage>,
    val totalPageCount: Int,
    val omittedPages: List<Int>,
    val extractionEngine: String,
    val pdfBoxSucceeded: Boolean,
    val isScannedVisionOnly: Boolean,
    val warnings: List<String>
  )

  /**
   * Pure byte-level Apache PDFBox extraction with page-anchored assembly and explicit omitted-page
   * tracking (usable directly in unit/Robolectric tests with real PDFBox PDF fixtures).
   */
  internal fun extractPdfPagesFromBytes(
    rawBytes: ByteArray,
    fileName: String,
    maxChars: Int = MAX_EXTRACTED_CHARS
  ): PdfByteExtractionResult {
    val extractedPages = mutableListOf<ExtractedPage>()
    val warnings = mutableListOf<String>()
    val omittedPages = mutableListOf<Int>()
    var totalPageCount = 1
    var extractionEngine = "PDFBox-Android (Full ToUnicode)"

    val pdfBoxResult = runCatching {
      PDDocument.load(rawBytes).use { doc ->
        totalPageCount = doc.numberOfPages.coerceAtLeast(1)
        if (doc.isEncrypted) {
          warnings.add("PDF is encrypted; text extraction may be restricted.")
        }
        val stripper = PDFTextStripper().apply {
          sortByPosition = true
          addMoreFormatting = false
        }
        val figureOrTableRegex = Regex(
          """\b(?:figure|fig\.|table|forest\s+plot|funnel\s+plot|prisma\s+flow)\s*\d*\b""",
          RegexOption.IGNORE_CASE
        )
        for (pageNum in 1..totalPageCount) {
          stripper.startPage = pageNum
          stripper.endPage = pageNum
          val rawPageText = runCatching { stripper.getText(doc) }.getOrElse { "" }
          val cleanedPage = normalizeExtractedPdfPageText(rawPageText)
          val words = countWords(cleanedPage)
          val lowDensity = words < 35
          val hasFigOrTable = figureOrTableRegex.containsMatchIn(cleanedPage)
          extractedPages.add(
            ExtractedPage(
              pageNumber = pageNum,
              text = cleanedPage,
              charCount = cleanedPage.length,
              wordCount = words,
              hasLowTextDensity = lowDensity,
              mentionsFiguresOrTables = hasFigOrTable
            )
          )
        }
      }
    }

    if (pdfBoxResult.isFailure) {
      Log.w(EXTRACTOR_TAG, "PDFBox extraction failed for $fileName; using fallback", pdfBoxResult.exceptionOrNull())
      extractionEngine = "Fallback Stream Scanner (Degraded)"
      warnings.add(
        "Primary PDF layout parser failed (${pdfBoxResult.exceptionOrNull()?.javaClass?.simpleName ?: "error"}); " +
          "fell back to stream scan. Symbols, tables, or columns may be incomplete."
      )
    }

    // High-signal page selection BEFORE applying maxChars so late Results, Figures, Tables,
    // Limitations, Supplements, and Discussion pages are NEVER permanently dropped behind early bloat or references.
    val selection = selectPagesWithinCharBudget(extractedPages, maxChars)
    omittedPages.addAll(selection.omittedPages)
    val assembledPagesText = selection.assembledText

    if (omittedPages.isNotEmpty()) {
      warnings.add(
        "Extraction ceiling (%,d chars) reached; high-signal opening/results/figure/discussion pages prioritized while page(s) %s were omitted from extracted text.".format(
          Locale.US,
          maxChars,
          omittedPages.joinToString(",") { "P$it" }
        )
      )
    }
    if (selection.partiallyRetainedPages.isNotEmpty()) {
      warnings.add(
        "Page(s) ${selection.partiallyRetainedPages.joinToString(",") { "P$it" }} partially retained to fit extraction ceiling."
      )
    }

    var isScannedVisionOnly = false
    val finalText = if (assembledPagesText.length >= 100) {
      assembledPagesText
    } else {
      val fallbackText = extractReadableRunsFromPdfBytes(rawBytes)
      if (fallbackText.length >= 120) {
        extractionEngine = "Fallback Stream Scanner (Low Fidelity)"
        warnings.add("PDF appears scanned or uses non-standard font encoding; text extraction is partial.")
        fallbackText.take(maxChars)
      } else {
        isScannedVisionOnly = true
        extractionEngine = "Vision-Only PDF (Scanned / Image-Based)"
        warnings.add("No embedded text layer detected (scanned PDF). OCR or visual inspection is required.")
        "PDF MANUSCRIPT (SCANNED / IMAGE-BASED): $fileName ($totalPageCount pages).\n" +
          "Inferred Title: ${cleanBaseTitle(fileName)}"
      }
    }

    return PdfByteExtractionResult(
      extractedText = finalText,
      extractedPages = extractedPages,
      totalPageCount = totalPageCount,
      omittedPages = omittedPages,
      extractionEngine = extractionEngine,
      pdfBoxSucceeded = pdfBoxResult.isSuccess,
      isScannedVisionOnly = isScannedVisionOnly,
      warnings = warnings
    )
  }

  private data class PageBudgetSelection(
    val assembledText: String,
    val omittedPages: List<Int>,
    val partiallyRetainedPages: List<Int>
  )

  /**
   * Selects pages BEFORE applying [maxChars] so late high-signal pages (Results, Tables, Figures,
   * Limitations, Discussion, Conclusions, Supplements) are retained ahead of bibliography/reference
   * pages or repetitive narrative filler, then assembles incrementally in ascending page order.
   */
  private fun selectPagesWithinCharBudget(
    pages: List<ExtractedPage>,
    maxChars: Int
  ): PageBudgetSelection {
    val nonBlank = pages.filter { it.text.isNotBlank() }
    if (nonBlank.isEmpty()) {
      return PageBudgetSelection(assembledText = "", omittedPages = emptyList(), partiallyRetainedPages = emptyList())
    }

    val fullBlocks = nonBlank.map { pg -> pg.pageNumber to "[PAGE ${pg.pageNumber}]\n${pg.text}" }
    val fullTotalLen = fullBlocks.sumOf { it.second.length + 2 }
    if (fullTotalLen <= maxChars) {
      return PageBudgetSelection(
        assembledText = fullBlocks.joinToString("\n\n") { it.second }.trim(),
        omittedPages = emptyList(),
        partiallyRetainedPages = emptyList()
      )
    }

    val highSignalRegex = Regex(
      """\b(?:results?|methods?|discussion|conclusion|limitations?|supplement(?:ary)?|appendix|table\s*\d+|figure\s*\d+|fig\.\s*\d+|95%\s*ci|meta-?analysis|meta-?regression|p\s*[=<>≤]|i²|i2|errat(?:um|a))\b""",
      RegexOption.IGNORE_CASE
    )
    val refHeaderRegex = Regex("""(?im)^\s*(?:references|bibliography|works\s+cited)\s*$""")
    val citationLineRegex = Regex("""(?m)^\s*(?:\[\d+\]|\d+\.\s+[A-Z][a-z]+,?\s+[A-Z])""")
    val inlineBracketCitationRegex = Regex("""\[\d{1,3}\]\s+[A-Z]""")

    var referencesStarted = false
    val scored = nonBlank.mapIndexed { idx, pg ->
      if (idx >= nonBlank.size / 3 && refHeaderRegex.containsMatchIn(pg.text)) {
        referencesStarted = true
      }
      val signalHits = highSignalRegex.findAll(pg.text).count()
      val citationHits = maxOf(
        citationLineRegex.findAll(pg.text).count(),
        inlineBracketCitationRegex.findAll(pg.text).count()
      )
      val looksLikeRefOnly = (referencesStarted && signalHits == 0) || (citationHits >= 6 && signalHits <= 1)

      val priority = when {
        idx == 0 -> 100_000
        looksLikeRefOnly -> -5_000 - idx
        idx == nonBlank.lastIndex && !referencesStarted -> 20_000 + signalHits * 200
        pg.mentionsFiguresOrTables -> 12_000 + signalHits * 250
        else -> (if (idx == 1) 8_000 else 0) + signalHits * 300
      }
      Triple(pg.pageNumber, "[PAGE ${pg.pageNumber}]\n${pg.text}", priority)
    }

    val selectedPageNums = linkedSetOf<Int>()
    var used = 0
    for ((pageNum, block, _) in scored.sortedByDescending { it.third }) {
      val needed = block.length + 2
      if (used + needed <= maxChars) {
        selectedPageNums.add(pageNum)
        used += needed
      }
    }

    // Incrementally assemble in ascending page order; if even the highest-priority single page
    // exceeds maxChars, explicitly retain a marked partial slice rather than dropping everything.
    val assembled = StringBuilder()
    val retainedFull = linkedSetOf<Int>()
    val partiallyRetained = mutableListOf<Int>()

    if (selectedPageNums.isNotEmpty()) {
      for ((pageNum, block) in fullBlocks) {
        if (pageNum !in selectedPageNums) continue
        val sep = if (assembled.isEmpty()) "" else "\n\n"
        if (assembled.length + sep.length + block.length <= maxChars) {
          assembled.append(sep).append(block)
          retainedFull.add(pageNum)
        }
      }
    } else {
      scored.maxByOrNull { it.third }?.let { topPage ->
        val header = "[PAGE ${topPage.first} — PARTIAL]\n"
        val remaining = (maxChars - header.length - 4).coerceAtLeast(40)
        val rawBody = topPage.second.substringAfter('\n')
        assembled.append(header).append(rawBody.take(remaining)).append("...")
        partiallyRetained.add(topPage.first)
      }
    }

    val omitted = nonBlank.map { it.pageNumber }
      .filter { it !in retainedFull && it !in partiallyRetained }
      .sorted()

    return PageBudgetSelection(
      assembledText = assembled.toString().trim(),
      omittedPages = omitted,
      partiallyRetainedPages = partiallyRetained
    )
  }

  /**
   * Selects up to [maxPages] 0-based page indices for multimodal Vision attachment:
   * Always includes Page 1 (title/abstract), then prioritizes pages containing Figure/Table captions
   * or low-text-density visual diagrams/forest plots (such as Figure 2 on Page 9), then fills with
   * Results/Methods pages.
   */
  internal fun selectHighValueVisionPages(
    totalPages: Int,
    extractedPages: List<ExtractedPage>,
    maxPages: Int
  ): List<Int> {
    if (totalPages <= 0 || maxPages <= 0) return emptyList()
    if (totalPages <= maxPages) return (0 until totalPages).toList()

    val chosen = linkedSetOf<Int>()
    chosen.add(0) // Always include Page 1

    // 1. Prioritize pages explicitly mentioning Figure / Forest plot / Funnel plot (often containing charts & I²/CI graphics)
    val figureRegex = Regex("""\b(?:figure|fig\.|forest\s+plot|funnel\s+plot)\s*\d*\b""", RegexOption.IGNORE_CASE)
    extractedPages
      .filter { it.pageNumber > 1 && figureRegex.containsMatchIn(it.text) }
      .forEach { pg ->
        if (chosen.size < maxPages) chosen.add((pg.pageNumber - 1).coerceIn(0, totalPages - 1))
      }

    // 2. Prioritize low-text-density pages (likely full-page figures, flow diagrams, or scanned tables)
    extractedPages
      .filter { it.pageNumber > 1 && it.hasLowTextDensity }
      .forEach { pg ->
        if (chosen.size < maxPages) chosen.add((pg.pageNumber - 1).coerceIn(0, totalPages - 1))
      }

    // 3. Prioritize pages with Table captions
    val tableRegex = Regex("""\btable\s*\d+\b""", RegexOption.IGNORE_CASE)
    extractedPages
      .filter { it.pageNumber > 1 && tableRegex.containsMatchIn(it.text) }
      .forEach { pg ->
        if (chosen.size < maxPages) chosen.add((pg.pageNumber - 1).coerceIn(0, totalPages - 1))
      }

    // 4. Fill remaining slots with Page 2 (Methods) and mid-document Results pages
    for (candidate in listOf(1, totalPages / 2, (totalPages * 2) / 3)) {
      if (chosen.size < maxPages && candidate in 0 until totalPages) {
        chosen.add(candidate)
      }
    }

    return chosen.sorted().take(maxPages)
  }

  private fun computePdfExtractionConfidence(
    totalPages: Int,
    extractedNonEmptyPages: Int,
    totalWords: Int,
    isPdfBoxUsed: Boolean,
    warningCount: Int
  ): Int {
    if (!isPdfBoxUsed) return if (totalWords >= 200) 2 else 1
    if (totalWords < 50) return 1
    val ratio = extractedNonEmptyPages.toFloat() / totalPages.coerceAtLeast(1).toFloat()
    return when {
      ratio >= 0.90f && warningCount == 0 && totalWords >= 300 -> 5
      ratio >= 0.75f && warningCount <= 1 -> 4
      ratio >= 0.50f -> 3
      ratio >= 0.25f -> 2
      else -> 1
    }
  }

  private fun normalizeExtractedPdfPageText(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
      .replace("\r\n", "\n")
      .replace("\r", "\n")
      // Rejoin hyphenated line-break words (e.g., "meta-\nanalysis" -> "meta-analysis" or "psy-\nchiatric" -> "psychiatric")
      .replace(Regex("""([A-Za-z])-\n([a-z])"""), "$1$2")
      .replace(Regex("""[ \t]+\n"""), "\n")
      .replace(Regex("""\n{3,}"""), "\n\n")
      .trim()
  }

  private fun inferTitleFromFirstPage(firstPageText: String, fileName: String): String {
    val lines = firstPageText.lineSequence()
      .map { it.trim() }
      .filter { it.length in 14..210 }
      .filterNot { line ->
        val l = line.lowercase(Locale.US)
        l.startsWith("http") || l.startsWith("doi") || l.startsWith("copyright") ||
          l.startsWith("published") || l.startsWith("original article") ||
          l.startsWith("review article") || l.startsWith("psychological medicine") ||
          l.contains("cambridge.org") || l.contains("creativecommons")
      }
      .toList()
    val best = lines.firstOrNull { it.count { ch -> ch.isLetter() } >= 12 }
    return if (best != null) DossierFormattingEngine.normalizeTitle(best) else cleanBaseTitle(fileName)
  }

  private fun cleanBaseTitle(fileName: String): String {
    val raw = fileName.substringBeforeLast(".")
      .replace("_", " ")
      .replace("-", " ")
      .trim()
      .ifEmpty { "Imported Academic Manuscript" }
    return DossierFormattingEngine.normalizeTitle(raw)
  }

  private fun countWords(text: String): Int {
    if (text.isBlank()) return 0
    return text.trim().split(Regex("""\s+""")).size
  }

  private fun extractReadableRunsFromPdfBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val combinedText = StringBuilder()
    val rawLatin = String(bytes, Charsets.ISO_8859_1)
    var searchStart = 0
    var streamCount = 0
    while (streamCount < 250 && combinedText.length < MAX_EXTRACTED_CHARS) {
      val streamIdx = rawLatin.indexOf("stream", searchStart)
      if (streamIdx < 0) break
      var dataStart = streamIdx + 6
      if (dataStart < rawLatin.length && rawLatin[dataStart] == '\r') dataStart++
      if (dataStart < rawLatin.length && rawLatin[dataStart] == '\n') dataStart++
      val endStreamIdx = rawLatin.indexOf("endstream", dataStart)
      if (endStreamIdx <= dataStart) break
      searchStart = endStreamIdx + 9
      val streamLen = endStreamIdx - dataStart
      if (streamLen in 8..500_000) {
        val slice = bytes.copyOfRange(dataStart, endStreamIdx)
        val inflated = inflateZlibSlice(slice)
        val candidateTextSource = if (inflated != null) String(inflated, Charsets.ISO_8859_1) else rawLatin.substring(dataStart, endStreamIdx)
        extractPdfTextOperators(candidateTextSource, combinedText)
      }
      streamCount++
    }
    if (combinedText.length < 150) {
      extractPdfTextOperators(rawLatin, combinedText)
    }
    return combinedText.toString().replace(Regex("""\s+"""), " ").trim().take(MAX_EXTRACTED_CHARS)
  }

  private fun inflateZlibSlice(slice: ByteArray): ByteArray? {
    return runCatching {
      val inflater = java.util.zip.Inflater()
      inflater.setInput(slice)
      val out = ByteArrayOutputStream()
      val buf = ByteArray(4096)
      while (!inflater.finished() && out.size() < 500_000) {
        val count = inflater.inflate(buf)
        if (count == 0) break
        out.write(buf, 0, count)
      }
      inflater.end()
      if (out.size() > 0) out.toByteArray() else null
    }.getOrNull()
  }

  private fun extractPdfTextOperators(source: String, dest: StringBuilder) {
    val parenRegex = Regex("""\(([^()\\]{2,180}(?:\\.[^()\\]{0,60})*)\)""")
    for (match in parenRegex.findAll(source)) {
      if (dest.length >= MAX_EXTRACTED_CHARS) break
      val token = match.groupValues[1]
        .replace("\\(", "(")
        .replace("\\)", ")")
        .replace("\\n", " ")
        .trim()
      val alphaCount = token.count { it.isLetter() }
      if (alphaCount >= 2 && !token.startsWith("PDF-") && !token.contains("FontDescriptor") && !token.contains("FlateDecode")) {
        dest.append(token).append(' ')
      }
    }
  }

  private fun extractReadableAsciiRuns(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val rawLatin = String(bytes, Charsets.ISO_8859_1)
    val runRegex = Regex("""[A-Za-z0-9 ,.;:'"\-?!()/%=+\n]{12,300}""")
    return runRegex.findAll(rawLatin)
      .map { it.value.trim() }
      .filter { it.count { ch -> ch.isLetter() } >= 8 }
      .take(1500)
      .joinToString("\n")
  }

  /**
   * Stage 00_prep_code: Extracts replication code from a single script (.py, .R, .do, .jl, .m, .sql, .sh),
   * a Jupyter Notebook (.ipynb), or a multi-script .zip replication archive.
   */
  suspend fun extractReplicationCodeFromUri(
    context: Context,
    uri: Uri
  ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
    try {
      val fileName = resolveFileName(context, uri) ?: "replication_code.py"
      val lower = fileName.lowercase(Locale.US)
      val rawBytes = context.contentResolver.openInputStream(uri)?.use {
        readBytesBounded(it, 10_000_000)
      } ?: throw IllegalStateException("Unable to read replication code file.")

      val codeExtensions = listOf(".py", ".r", ".do", ".ado", ".jl", ".m", ".sql", ".sh", ".cpp", ".c", ".ipynb", ".txt", ".md")

      val extractedCode = when {
        lower.endsWith(".zip") -> {
          val sb = StringBuilder()
          var fileCount = 0
          var scannedEntries = 0
          var totalDecompressedBytes = 0
          ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && sb.length < 40_000 && scannedEntries < MAX_ZIP_ENTRIES && totalDecompressedBytes < MAX_ZIP_CODE_TOTAL_BYTES) {
              scannedEntries++
              val entryName = entry.name.orEmpty()
              val isSafeEntry = !entry.isDirectory &&
                !entryName.contains("..") &&
                !entryName.startsWith("/") &&
                !entryName.startsWith("\\") &&
                !entryName.startsWith("__MACOSX/")
              if (isSafeEntry && codeExtensions.any { entryName.lowercase(Locale.US).endsWith(it) }) {
                val remainingBudget = (MAX_ZIP_CODE_TOTAL_BYTES - totalDecompressedBytes).coerceAtLeast(0)
                val entryBytes = readBytesBounded(zis, minOf(MAX_ZIP_CODE_ENTRY_BYTES, remainingBudget))
                totalDecompressedBytes += entryBytes.size
                val entryText = entryBytes.toString(Charsets.UTF_8)
                sb.appendLine("=== REPLICATION FILE: $entryName ===")
                sb.appendLine(entryText.take(12_000))
                sb.appendLine()
                fileCount++
              }
              zis.closeEntry()
              entry = zis.nextEntry
            }
          }
          if (fileCount == 0) {
            throw IllegalStateException("No code scripts (.py, .R, .do, .ipynb, .jl, .m, .sql) found in ZIP archive.")
          }
          sb.toString().trim().take(40_000)
        }
        else -> {
          val text = rawBytes.toString(Charsets.UTF_8).trim().take(40_000)
          "=== REPLICATION FILE: $fileName ===\n$text"
        }
      }
      Result.success(fileName to extractedCode)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      Result.failure(e)
    }
  }

  private fun readBytesBounded(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = maxBytes
    while (remaining > 0) {
      val read = input.read(buffer, 0, minOf(buffer.size, remaining))
      if (read <= 0) break
      out.write(buffer, 0, read)
      remaining -= read
    }
    return out.toByteArray()
  }
}
