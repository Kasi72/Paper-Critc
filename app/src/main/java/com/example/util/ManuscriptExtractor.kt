package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

enum class ManuscriptInputFormat(val displayBadge: String) {
  PDF("PDF Document"),
  WORD("Word Document (.docx / .doc)"),
  TEXT("Plain Text / LaTeX / Markdown")
}

data class ExtractedManuscriptFile(
  val fileName: String,
  val format: ManuscriptInputFormat,
  val inferredTitle: String,
  val pageCount: Int,
  val wordCount: Int,
  val extractedText: String,
  val pdfPageBitmaps: List<Bitmap> = emptyList(),
  val summaryNotice: String
)

object ManuscriptExtractor {

  suspend fun extractFromUri(
    context: Context,
    uri: Uri,
    renderPdfBitmaps: Boolean = true
  ): Result<ExtractedManuscriptFile> = withContext(Dispatchers.IO) {
    runCatching {
      val contentResolver = context.contentResolver
      val fileName = resolveFileName(context, uri) ?: "manuscript_document"
      val lowerName = fileName.lowercase()
      val mimeType = contentResolver.getType(uri)?.lowercase().orEmpty()

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

    val cleanText = rawText.trim().take(30_000)
    val words = countWords(cleanText)
    val pages = (words / 350).coerceAtLeast(1)
    val firstLineTitle = cleanText.lineSequence()
      .map { it.trim().removePrefix("#").trim() }
      .firstOrNull { it.length in 5..180 }
      ?: cleanBaseTitle(fileName)

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.TEXT,
      inferredTitle = firstLineTitle,
      pageCount = pages,
      wordCount = words,
      extractedText = cleanText,
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
      readBytesBounded(it, 10_000_000)
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

    val extractedText = if (foundDocumentXml && sb.isNotBlank()) {
      sb.toString()
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
        .take(30_000)
    } else {
      extractReadableAsciiRuns(rawBytes).take(30_000)
    }

    val finalBody = extractedText.ifBlank {
      "WORD MANUSCRIPT (.docx): $fileName\n(Document parsed; add or edit any supplementary sections below.)"
    }
    val words = countWords(finalBody)
    val pages = (words / 350).coerceAtLeast(1)
    val firstLineTitle = finalBody.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.length in 6..180 && !it.startsWith("WORD MANUSCRIPT") }
      ?: cleanBaseTitle(fileName)

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.WORD,
      inferredTitle = firstLineTitle,
      pageCount = pages,
      wordCount = words,
      extractedText = finalBody,
      summaryNotice = "Loaded Word Document ($words words, ~$pages pages) from $fileName"
    )
  }

  private fun extractLegacyDocOrRtfContent(
    context: Context,
    uri: Uri,
    fileName: String
  ): ExtractedManuscriptFile {
    val rawBytes = context.contentResolver.openInputStream(uri)?.use {
      readBytesBounded(it, 6_000_000)
    } ?: throw IllegalStateException("Unable to read Word document.")

    val rawString = String(rawBytes, Charsets.ISO_8859_1)
    val cleaned = if (rawString.trimStart().startsWith("{\\rtf")) {
      // Strip RTF control words while preserving body text
      rawString
        .replace(Regex("""\\[a-zA-Z]+\d*\s?"""), " ")
        .replace(Regex("""[{}]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    } else {
      extractReadableAsciiRuns(rawBytes)
    }.take(30_000)

    val finalBody = cleaned.ifBlank {
      "WORD MANUSCRIPT: $fileName"
    }
    val words = countWords(finalBody)
    val pages = (words / 350).coerceAtLeast(1)

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.WORD,
      inferredTitle = cleanBaseTitle(fileName),
      pageCount = pages,
      wordCount = words,
      extractedText = finalBody,
      summaryNotice = "Loaded Word Document ($words words) from $fileName"
    )
  }

  private fun extractPdfContent(
    context: Context,
    uri: Uri,
    fileName: String,
    renderPdfBitmaps: Boolean
  ): ExtractedManuscriptFile {
    var pageCount = 1
    val renderedPages = mutableListOf<Bitmap>()

    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
      PdfRenderer(pfd).use { renderer ->
        pageCount = renderer.pageCount.coerceAtLeast(1)
        if (renderPdfBitmaps) {
          val maxPagesToRender = pageCount.coerceAtMost(3)
          for (i in 0 until maxPagesToRender) {
            renderer.openPage(i).use { page ->
              // Lightweight 720px width keeps inline payload compact & prevents Vision timeouts
              val targetWidth = 720
              val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
              val targetHeight = (page.height * scale).toInt().coerceIn(360, 1280)
              val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
              bitmap.eraseColor(Color.WHITE)
              page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
              renderedPages.add(bitmap)
            }
          }
        }
      }
    }

    val rawBytes = context.contentResolver.openInputStream(uri)?.use {
      readBytesBounded(it, 6_000_000)
    } ?: ByteArray(0)
    val extractedLiterals = extractReadableRunsFromPdfBytes(rawBytes)
    val baseTitle = cleanBaseTitle(fileName)

    val contentBody = if (extractedLiterals.length >= 120) {
      "PDF MANUSCRIPT: $fileName ($pageCount pages, ${renderedPages.size} page visuals attached for Gemini Multimodal Vision)\n\n" +
        "EXTRACTED TEXT STREAM:\n$extractedLiterals"
    } else {
      "PDF MANUSCRIPT: $fileName ($pageCount pages, ${renderedPages.size} high-resolution page visuals attached for Gemini Multimodal Vision).\n" +
        "Inferred Title: $baseTitle\n" +
        "Gemini Vision will directly read the rendered PDF pages (text, equations, tables, and figures) during analysis."
    }.take(24_000)

    val words = countWords(extractedLiterals).coerceAtLeast(pageCount * 300)

    return ExtractedManuscriptFile(
      fileName = fileName,
      format = ManuscriptInputFormat.PDF,
      inferredTitle = baseTitle,
      pageCount = pageCount,
      wordCount = words,
      extractedText = contentBody,
      pdfPageBitmaps = renderedPages,
      summaryNotice = "Imported PDF ($pageCount pages + ${renderedPages.size} Vision pages) from $fileName"
    )
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

    // 1. Decompress zlib/FlateDecode PDF content streams for modern LaTeX/Word PDFs
    val rawLatin = String(bytes, Charsets.ISO_8859_1)
    var searchStart = 0
    var streamCount = 0
    while (streamCount < 45 && combinedText.length < 22_000) {
      val streamIdx = rawLatin.indexOf("stream", searchStart)
      if (streamIdx < 0) break
      var dataStart = streamIdx + 6
      if (dataStart < rawLatin.length && rawLatin[dataStart] == '\r') dataStart++
      if (dataStart < rawLatin.length && rawLatin[dataStart] == '\n') dataStart++
      val endStreamIdx = rawLatin.indexOf("endstream", dataStart)
      if (endStreamIdx <= dataStart) break
      searchStart = endStreamIdx + 9
      val streamLen = endStreamIdx - dataStart
      if (streamLen in 8..250_000) {
        val slice = bytes.copyOfRange(dataStart, endStreamIdx)
        val inflated = inflateZlibSlice(slice)
        val candidateTextSource = if (inflated != null) String(inflated, Charsets.ISO_8859_1) else rawLatin.substring(dataStart, endStreamIdx)
        extractPdfTextOperators(candidateTextSource, combinedText)
      }
      streamCount++
    }

    // 2. Fallback scan of literal strings across the raw PDF if few streams decoded
    if (combinedText.length < 150) {
      extractPdfTextOperators(rawLatin, combinedText)
    }

    return combinedText.toString().replace(Regex("""\s+"""), " ").trim().take(22_000)
  }

  private fun inflateZlibSlice(slice: ByteArray): ByteArray? {
    return runCatching {
      val inflater = java.util.zip.Inflater()
      inflater.setInput(slice)
      val out = java.io.ByteArrayOutputStream()
      val buf = ByteArray(2048)
      while (!inflater.finished() && out.size() < 200_000) {
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
      if (dest.length >= 22_000) break
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
      .take(400)
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
      val lower = fileName.lowercase()
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
            while (entry != null && sb.length < 25_000) {
              val entryName = entry.name
              if (!entry.isDirectory && codeExtensions.any { entryName.lowercase().endsWith(it) }) {
                val entryText = zis.readBytes().toString(Charsets.UTF_8)
                sb.appendLine("=== REPLICATION FILE: $entryName ===")
                sb.appendLine(entryText.take(8_000))
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
          sb.toString().trim().take(25_000)
        }
        else -> {
          val text = rawBytes.toString(Charsets.UTF_8).trim().take(25_000)
          "=== REPLICATION FILE: $fileName ===\n$text"
        }
      }
      fileName to extractedCode
    }
  }

  private fun readBytesBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
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
