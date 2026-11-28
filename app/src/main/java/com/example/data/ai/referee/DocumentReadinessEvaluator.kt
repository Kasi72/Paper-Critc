package com.example.data.ai.referee

import com.example.util.DocumentCoverageReport
import java.util.Locale

/**
 * Phase 2 Document Completeness & Readiness Gate.
 * Determines whether the uploaded manuscript is sufficiently complete and verified before
 * allowing a full referee verdict vs restricting output to a preliminary appraisal or abstention.
 */
object DocumentReadinessEvaluator {

  private val SUPPORTED_FORMATS = setOf("PDF", "DOCX", "DOC", "TEXT", "TXT", "MD", "DIRECT_TEXT")
  private val METHODS_REGEX = Regex(
    """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(?:methods?|materials?\s+and\s+methods?|methodology|study\s+design|patients?\s+and\s+methods?|experimental\s+setup|search\s+strategy)\b"""
  )
  private val RESULTS_REGEX = Regex(
    """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(?:results?|findings?|outcomes?|empirical\s+results?|statistical\s+analysis|primary\s+outcomes?)\b"""
  )
  private val TABLE_OR_FIGURE_REGEX = Regex(
    """(?i)\b(?:table\s*\d+|figure\s*\d+|fig\.\s*\d+|eTable\s*\d+|sTable\s*\d+)\b"""
  )
  private val REFERENCES_HEADER_REGEX = Regex(
    """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(?:references|bibliography|works\s+cited|literature\s+cited)\s*$"""
  )
  private val SUPPLEMENT_REFERENCE_REGEX = Regex(
    """(?i)\b(?:supplement(?:ary)?(?:\s+(?:material|appendix|table|figure|file|data))?|online\s+appendix|eTable|eFigure|Table\s+S\d+|Figure\s+S\d+)\b"""
  )
  private val SUPPLEMENT_CONTENT_HEADER_REGEX = Regex(
    """(?im)^\s*(?:supplementary\s+(?:material|appendix|methods|tables?|results)|appendix\s+[a-z0-9])\b"""
  )

  fun assess(
    manuscriptText: String,
    coverageReport: DocumentCoverageReport?,
    attachedBitmapCount: Int,
    pdfVisionDigestSha256: String = "",
    useExternalTextOnlyProvider: Boolean = false,
    supplementAttachedText: String = "",
    sourceFormat: String = ""
  ): DocumentReadinessAssessment {
    val trimmed = manuscriptText.trim()
    val words = if (trimmed.isBlank()) 0 else trimmed.split(Regex("""\s+""")).size
    val formatUpper = sourceFormat.trim().uppercase(Locale.US)
    val engineLabel = coverageReport?.extractionEngine.orEmpty()

    if (engineLabel.contains("Unsupported", ignoreCase = true) ||
      (formatUpper.isNotBlank() && formatUpper !in SUPPORTED_FORMATS)
    ) {
      return DocumentReadinessAssessment(
        readiness = DocumentReadiness.UNSUPPORTED_FORMAT,
        searchableTextExists = false,
        pageCountPlausible = false,
        methodsPresent = false,
        resultsPresent = false,
        tablesAndFiguresDetectable = false,
        referencesSeparated = false,
        extractionQualityAcceptable = false,
        visionPagesBound = false,
        supplementStatus = SupplementStatus.UNKNOWN,
        reasons = listOf("Unsupported document format '${sourceFormat.ifBlank { engineLabel.ifBlank { "unknown" } }}'."),
        requestedActions = listOf("Convert and upload the manuscript as a text-searchable PDF, Word (.docx), or plain text file.")
      )
    }

    val isScannedVisionOnly = engineLabel.contains("Vision-Only", ignoreCase = true) ||
      trimmed.startsWith("PDF MANUSCRIPT (SCANNED / IMAGE-BASED)")
    val searchableTextExists = !isScannedVisionOnly && words >= 15
    val totalPages = coverageReport?.totalPages ?: 1
    val extractedPages = coverageReport?.extractedPages ?: if (trimmed.isNotBlank()) 1 else 0
    val pageCountPlausible = totalPages >= 1 &&
      ((isScannedVisionOnly && attachedBitmapCount >= totalPages) ||
        (extractedPages >= 1 && (isScannedVisionOnly || words >= maxOf(10, extractedPages * 12))))

    // Detect garbled/replacement-char text corruption
    val replacementCharCount = trimmed.count { it == '\uFFFD' }
    val nonReadableRatio = if (trimmed.isEmpty()) 1.0 else replacementCharCount.toDouble() / trimmed.length.toDouble()
    val lowTextPenalty = coverageReport?.lowTextPages?.size ?: 0
    val extractionQualityAcceptable = when {
      trimmed.isBlank() && attachedBitmapCount == 0 -> false
      nonReadableRatio > 0.02 -> false
      isScannedVisionOnly && useExternalTextOnlyProvider -> false
      isScannedVisionOnly && attachedBitmapCount < totalPages -> false
      !isScannedVisionOnly && totalPages >= 4 && words < 60 && attachedBitmapCount == 0 -> false
      !isScannedVisionOnly && coverageReport != null && coverageReport.omittedPages.isNotEmpty() -> false
      !isScannedVisionOnly && totalPages > 0 && lowTextPenalty > totalPages / 2 && words < 120 -> false
      else -> true
    }

    val methodsPresent = METHODS_REGEX.containsMatchIn(trimmed) ||
      Regex("""(?i)\b(?:randomized\s+to|study\s+design|inclusion\s+criteria|regression\s+model|systematic\s+review)\b""").containsMatchIn(trimmed)
    val resultsPresent = RESULTS_REGEX.containsMatchIn(trimmed) ||
      Regex("""(?i)\b(?:95%\s*ci|p\s*[=<>≤≥]\s*0?\.\d+|hazard\s+ratio|odds\s+ratio|risk\s+ratio|primary\s+outcome)\b""").containsMatchIn(trimmed)
    val tablesAndFiguresDetectable = TABLE_OR_FIGURE_REGEX.containsMatchIn(trimmed)
    val refMatch = REFERENCES_HEADER_REGEX.findAll(trimmed).lastOrNull()
    val referencesSeparated = refMatch != null && refMatch.range.first >= (trimmed.length * 0.25).toInt()

    // PDF vision pages must be cryptographically bound to the exact uploaded bitmap content when attached
    val visionPagesBound = if (attachedBitmapCount <= 0) {
      true
    } else {
      pdfVisionDigestSha256.isNotBlank() &&
        (coverageReport == null || coverageReport.visionPageNumbers.isEmpty() || coverageReport.visionPageNumbers.size == attachedBitmapCount)
    }

    val supplementReferenced = SUPPLEMENT_REFERENCE_REGEX.containsMatchIn(trimmed)
    val supplementInline = SUPPLEMENT_CONTENT_HEADER_REGEX.containsMatchIn(trimmed)
    val supplementStatus = when {
      supplementAttachedText.isNotBlank() || supplementInline -> SupplementStatus.PRESENT
      supplementReferenced -> SupplementStatus.ABSENT
      else -> SupplementStatus.UNKNOWN
    }

    val reasons = mutableListOf<String>()
    val actions = mutableListOf<String>()

    if (trimmed.isBlank() && attachedBitmapCount == 0) {
      reasons.add("No searchable manuscript text or rendered document pages were extracted.")
      actions.add("Upload a text-searchable PDF, Word (.docx), or paste the full manuscript.")
      return DocumentReadinessAssessment(
        readiness = DocumentReadiness.EXTRACTION_FAILURE,
        searchableTextExists = false,
        pageCountPlausible = false,
        methodsPresent = false,
        resultsPresent = false,
        tablesAndFiguresDetectable = false,
        referencesSeparated = false,
        extractionQualityAcceptable = false,
        visionPagesBound = visionPagesBound,
        supplementStatus = supplementStatus,
        reasons = reasons,
        requestedActions = actions
      )
    }

    if (nonReadableRatio > 0.02 || (!isScannedVisionOnly && totalPages >= 4 && words < 60 && attachedBitmapCount == 0)) {
      reasons.add("Text extraction appears corrupted or lacked a valid Unicode font map ($words words across $totalPages page(s)).")
      actions.add("Run OCR or export a clean text-searchable PDF / Word (.docx) copy.")
      return DocumentReadinessAssessment(
        readiness = DocumentReadiness.EXTRACTION_FAILURE,
        searchableTextExists = false,
        pageCountPlausible = pageCountPlausible,
        methodsPresent = methodsPresent,
        resultsPresent = resultsPresent,
        tablesAndFiguresDetectable = tablesAndFiguresDetectable,
        referencesSeparated = referencesSeparated,
        extractionQualityAcceptable = false,
        visionPagesBound = visionPagesBound,
        supplementStatus = supplementStatus,
        reasons = reasons,
        requestedActions = actions
      )
    }

    if (!visionPagesBound) {
      reasons.add("Attached PDF vision pages ($attachedBitmapCount) are not bound to a verified content digest.")
      actions.add("Re-render and bind PDF page bitmaps with a verified SHA-256 vision digest before audit.")
    }
    if (isScannedVisionOnly && attachedBitmapCount < totalPages) {
      reasons.add("Scanned PDF requires full-page coverage ($attachedBitmapCount/$totalPages page images attached).")
      actions.add("Provide OCR text or attach all rendered pages.")
    }
    if (isScannedVisionOnly && useExternalTextOnlyProvider) {
      reasons.add("Scanned image-only PDF cannot be inspected by a text-only external API provider.")
      actions.add("Provide OCR text or switch to a multimodal vision provider.")
    }
    if (!methodsPresent) {
      reasons.add("Methods section could not be reliably identified.")
      actions.add("Include the complete Methods section before requesting a verified referee verdict.")
    }
    if (!resultsPresent) {
      reasons.add("Results section or quantitative outcome reporting could not be reliably identified.")
      actions.add("Include the complete Results section and primary outcome tables.")
    }
    if (coverageReport != null && coverageReport.omittedPages.isNotEmpty()) {
      reasons.add("Extraction omitted ${coverageReport.omittedPages.size} page(s): ${coverageReport.omittedPages.joinToString(",")}.")
      actions.add("Ensure 100% of manuscript pages are extracted before final adjudication.")
    }

    val isIncomplete = !visionPagesBound ||
      !extractionQualityAcceptable ||
      !pageCountPlausible ||
      (!isScannedVisionOnly && (!methodsPresent || !resultsPresent))

    if (isIncomplete) {
      return DocumentReadinessAssessment(
        readiness = DocumentReadiness.INCOMPLETE,
        searchableTextExists = searchableTextExists,
        pageCountPlausible = pageCountPlausible,
        methodsPresent = methodsPresent,
        resultsPresent = resultsPresent,
        tablesAndFiguresDetectable = tablesAndFiguresDetectable,
        referencesSeparated = referencesSeparated,
        extractionQualityAcceptable = extractionQualityAcceptable,
        visionPagesBound = visionPagesBound,
        supplementStatus = supplementStatus,
        reasons = reasons,
        requestedActions = actions.ifEmpty { listOf("Upload the complete manuscript with Methods, Results, and tables.") },
        lowTextPageCount = lowTextPenalty
      )
    }

    val limitationReasons = mutableListOf<String>()
    val limitationActions = mutableListOf<String>()
    if (isScannedVisionOnly) {
      limitationReasons.add("Scanned image-only PDF lacks a searchable text layer; deterministic text/table checks cannot verify quotes without OCR.")
      limitationActions.add("Provide an OCR-processed PDF so exact source anchors and numerical tables can be verified.")
    }
    if (supplementStatus == SupplementStatus.ABSENT) {
      limitationReasons.add("Manuscript references supplementary material that is not attached.")
      limitationActions.add("Attach supplementary tables/appendices if verification depends on supplement-only models.")
    }
    if (!tablesAndFiguresDetectable) {
      limitationReasons.add("No explicit Table/Figure markers were detected in extracted text.")
      limitationActions.add("Verify primary numerical tables directly if figures/tables were embedded as images.")
    }
    if (!referencesSeparated) {
      limitationReasons.add("References/Bibliography boundary could not be clearly separated from body text.")
      limitationActions.add("Verify citation separation manually so reference items do not affect claim extraction.")
    }
    if (lowTextPenalty > 0) {
      limitationReasons.add("$lowTextPenalty page(s) had sparse text (possibly figure-heavy).")
    }

    val finalReadiness = if (limitationReasons.isNotEmpty()) {
      DocumentReadiness.READY_WITH_LIMITATIONS
    } else {
      DocumentReadiness.READY
    }

    return DocumentReadinessAssessment(
      readiness = finalReadiness,
      searchableTextExists = searchableTextExists,
      pageCountPlausible = pageCountPlausible,
      methodsPresent = methodsPresent,
      resultsPresent = resultsPresent,
      tablesAndFiguresDetectable = tablesAndFiguresDetectable,
      referencesSeparated = referencesSeparated,
      extractionQualityAcceptable = extractionQualityAcceptable,
      visionPagesBound = visionPagesBound,
      supplementStatus = supplementStatus,
      reasons = limitationReasons,
      requestedActions = limitationActions,
      lowTextPageCount = lowTextPenalty
    )
  }
}
