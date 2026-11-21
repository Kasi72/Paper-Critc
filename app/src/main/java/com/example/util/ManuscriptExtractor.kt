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

data class DocumentCoverageReport(
  val totalPages: Int,
  val extractedPages: Int,
  val totalChars: Int,
  val totalWords: Int,
  val lowTextPages: List<Int> = emptyList(),
  val visionPagesAttached: Int = 0,
  val visionPageNumbers: List<Int> = emptyList(),
  val extractionEngine: String = "PDFBox-Android",
  val extractionConfidence: Int = 5, // 1–5 scale reflecting fidelity of text & layout extraction
  val warnings: List<String> = emptyList()
) {
  val isHighCoverage: Boolean
    get() = extractionConfidence >= 4 && warnings.isEmpty()

  fun toDisclosureLine(): String = buildString {
    append("$extractedPages/$totalPages page(s) extracted")
    append(" • %,d words".format(Locale.US, totalWords))
    append(" • %,d chars".format(Locale.US, totalChars))
    if (visionPagesAttached > 0) {
      val pageList = visionPageNumbers.joinToString(",") { "P$it" }
      append(" • $visionPagesAttached visual page(s) [$pageList]")
    }
    append(" • Engine: $extractionEngine")
    append(" • Extraction Confidence: $extractionConfidence/5")
    if (lowTextPages.isNotEmpty()) {
      append(" • Low-text/figure pages: ${lowTextPages.joinToString(",") { "P$it" }}")
    }
    if (warnings.isNotEmpty()) {
      append(" • Warnings: ${warnings.joinToString("; ")}")
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

  private const val MAX_PDF_INPUT_BYTES = 25_000_000 // 25 MB full journal articles & supplements
  private const val MAX_EXTRACTED_CHARS = 250_000
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
    runCatching {
      val contentResolver = context.contentResolver
      val fileName = resolveFileName(context, uri) ?: "manuscript_document"
      val lowerName = fileName.lowercase(Locale.US)
      val mimeType = contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()

      when {
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
    val rawText = context.contentResolver.openInputStream(uri)?.use { stream ->
      BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
    } ?: throw IllegalStateException("Unable to read selected text file.")

    val cleanText = rawText.trim().take(MAX_EXTRACTED_CHARS)
    val words = countWords(cleanText)
    val pages = (words / 350).coerceAtLeast(1)
    val firstLineTitle = cleanText.lineSequence()
      .map { it.trim().removePrefix("#").trim() }
      .firstOrNull { it.length in 5..180 }
      ?: cleanBaseTitle(fileName)

    val coverage = DocumentCoverageReport(
      totalPages = pages,
      extractedPages = pages,
      totalChars = cleanText.length,
      totalWords = words,
      extractionEngine = "UTF-8 Text Reader",
      extractionConfidence = 5,
      warnings = if (rawText.length > MAX_EXTRACTED_CHARS) {
        listOf("Source exceeded $MAX_EXTRACTED_CHARS characters; tail truncated.")
      } else {
        emptyList()
      }
    )

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.TEXT,
      inferredTitle = firstLineTitle,
      pageCount = pages,
      wordCount = words,
      extractedText = cleanText,
      coverageReport = coverage,
      summaryNotice = "Loaded Text ($words words, ~$pages pages) from $fileName"
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

    val sb = StringBuilder()
    var foundDocumentXml = false

    ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        if (entry.name.equals("word/document.xml", ignoreCase = true)) {
          foundDocumentXml = true
          val parser = Xml.newPullParser()
          parser.setInput(zis, "UTF-8")
          var eventType = parser.eventType
          while (eventType != XmlPullParser.END_DOCUMENT) {
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
        entry = zis.nextEntry
      }
    }

    val warnings = mutableListOf<String>()
    val extractedText = if (foundDocumentXml && sb.isNotBlank()) {
      sb.toString()
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
        .take(MAX_EXTRACTED_CHARS)
    } else {
      warnings.add("word/document.xml not found; fell back to ASCII scan.")
      extractReadableAsciiRuns(rawBytes).take(MAX_EXTRACTED_CHARS)
    }

    val finalBody = extractedText.ifBlank {
      warnings.add("Extracted body was empty.")
      "WORD MANUSCRIPT (.docx): $fileName"
    }
    val words = countWords(finalBody)
    val pages = (words / 350).coerceAtLeast(1)
    val firstLineTitle = finalBody.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.length in 6..180 && !it.startsWith("WORD MANUSCRIPT") }
      ?: cleanBaseTitle(fileName)

    val coverage = DocumentCoverageReport(
      totalPages = pages,
      extractedPages = pages,
      totalChars = finalBody.length,
      totalWords = words,
      extractionEngine = "OOXML XML PullParser",
      extractionConfidence = if (warnings.isEmpty()) 5 else 2,
      warnings = warnings
    )

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.WORD,
      inferredTitle = firstLineTitle,
      pageCount = pages,
      wordCount = words,
      extractedText = finalBody,
      coverageReport = coverage,
      summaryNotice = "Loaded Word Document ($words words, ~$pages pages) from $fileName"
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
    }.take(MAX_EXTRACTED_CHARS)

    val finalBody = cleaned.ifBlank { "WORD MANUSCRIPT: $fileName" }
    val words = countWords(finalBody)
    val pages = (words / 350).coerceAtLeast(1)
    val coverage = DocumentCoverageReport(
      totalPages = pages,
      extractedPages = pages,
      totalChars = finalBody.length,
      totalWords = words,
      extractionEngine = if (isRtf) "RTF Text Stripper" else "Legacy Binary .doc Fallback",
      extractionConfidence = if (isRtf) 4 else 2,
      warnings = if (isRtf) emptyList() else listOf("Legacy binary .doc format; convert to .pdf or .docx for exact layout & table fidelity.")
    )

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.WORD,
      inferredTitle = cleanBaseTitle(fileName),
      pageCount = pages,
      wordCount = words,
      extractedText = finalBody,
      coverageReport = coverage,
      summaryNotice = "Loaded Word Document ($words words) from $fileName"
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

    val extractedPages = mutableListOf<ExtractedPage>()
    val warnings = mutableListOf<String>()
    var totalPageCount = 1
    var extractionEngine = "PDFBox-Android (Full ToUnicode)"

    if (rawBytes.size >= MAX_PDF_INPUT_BYTES) {
      warnings.add("PDF exceeded 25 MB safety limit; document may be partially read.")
    }

    // 1. Primary extraction via Apache PDFBox Android — page by page with spatial reading order
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

    // 3. Assemble complete page-anchored manuscript text
    val assembledPagesText = buildString {
      extractedPages.forEach { pg ->
        if (pg.text.isNotBlank()) {
          appendLine("[PAGE ${pg.pageNumber}]")
          appendLine(pg.text)
          appendLine()
        }
      }
    }.trim()

    val finalText: String
    val lowTextPageNums = extractedPages.filter { it.hasLowTextDensity }.map { it.pageNumber }
    val extractedNonEmptyCount = extractedPages.count { it.wordCount >= 10 }

    if (assembledPagesText.length >= 100) {
      finalText = assembledPagesText.take(MAX_EXTRACTED_CHARS)
      if (assembledPagesText.length > MAX_EXTRACTED_CHARS) {
        warnings.add("Document exceeded $MAX_EXTRACTED_CHARS chars; trailing bibliography clipped.")
      }
    } else {
      val fallbackText = extractReadableRunsFromPdfBytes(rawBytes)
      if (fallbackText.length >= 120) {
        extractionEngine = "Fallback Stream Scanner (Low Fidelity)"
        warnings.add("PDF appears scanned or uses non-standard font encoding; text extraction is partial.")
        finalText = fallbackText.take(MAX_EXTRACTED_CHARS)
      } else {
        extractionEngine = "Vision-Only PDF (Scanned / Image-Based)"
        warnings.add("No embedded text layer detected (scanned PDF). Analysis relies on ${renderedPages.size} rendered page image(s).")
        finalText = "PDF MANUSCRIPT (SCANNED / IMAGE-BASED): $fileName ($totalPageCount pages, ${renderedPages.size} page visuals attached).\n" +
          "Inferred Title: ${cleanBaseTitle(fileName)}"
      }
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
      isPdfBoxUsed = pdfBoxResult.isSuccess,
      warningCount = warnings.size
    )

    val coverage = DocumentCoverageReport(
      totalPages = totalPageCount,
      extractedPages = if (extractedPages.isNotEmpty()) extractedNonEmptyCount else if (totalWords > 80) totalPageCount else 0,
      totalChars = finalText.length,
      totalWords = totalWords,
      lowTextPages = lowTextPageNums,
      visionPagesAttached = renderedPages.size,
      visionPageNumbers = renderedPageNumbers,
      extractionEngine = extractionEngine,
      extractionConfidence = extractionConfidence,
      warnings = warnings.distinct()
    )

    val inferredTitle = inferTitleFromFirstPage(extractedPages.firstOrNull()?.text.orEmpty(), fileName)

    val notice = if (warnings.isEmpty()) {
      "Extracted $totalPageCount/$totalPageCount pages (%,d words, ${renderedPages.size} visual page(s)) via PDFBox".format(Locale.US, totalWords)
    } else {
      "Imported PDF ($totalPageCount pages, %,d words • Coverage ${extractionConfidence}/5 — ${warnings.first()})".format(Locale.US, totalWords)
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
    runCatching {
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
          ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null && sb.length < 40_000) {
              val entryName = entry.name
              if (!entry.isDirectory && codeExtensions.any { entryName.lowercase(Locale.US).endsWith(it) }) {
                val entryText = zis.readBytes().toString(Charsets.UTF_8)
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
      fileName to extractedCode
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
