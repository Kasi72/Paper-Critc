package com.example.util

import java.util.Locale

/**
 * Semantic blocks parsed from any Paper Critic dossier section so both the Compose Output UI
 * and the native Android PdfReportExporter render crisp, structured, publication-grade layouts.
 */
sealed interface ReportBlock {
  data class SubHeader(val tag: String, val title: String) : ReportBlock
  data class SeverityCard(
    val level: SeverityLevel,
    val label: String,
    val body: String
  ) : ReportBlock
  data class Callout(val title: String, val body: String, val tone: CalloutTone) : ReportBlock
  data class Bullet(val marker: String, val body: String) : ReportBlock
  data class Equation(val expression: String) : ReportBlock
  data class AuditTable(
    val headers: List<String>,
    val rows: List<List<String>>
  ) : ReportBlock
  data class Paragraph(val text: String) : ReportBlock
}

enum class SeverityLevel {
  CRITICAL,
  MAJOR,
  MINOR,
  DISMISSED,
  MUST_FIX,
  SHOULD_FIX,
  RECOMMENDED,
  PASS,
  WARN,
  FAIL
}

enum class CalloutTone {
  BOTTOM_LINE,
  FUTURE_RESEARCH,
  ALERT,
  INFO
}

object DossierFormattingEngine {

  private val minorTitleWords = setOf(
    "a", "an", "the", "and", "or", "but", "for", "nor", "of", "in", "on", "at",
    "to", "by", "from", "with", "as", "per", "via", "vs", "into", "over"
  )

  private val preservedAcronyms = setOf(
    "AI", "ML", "LLM", "LLMs", "NLP", "OCR", "RCT", "RCTs", "PRISMA", "PROSPERO",
    "IQR", "IQRs", "CI", "SD", "SE", "OR", "RR", "HR", "HIC", "HICs", "LMIC", "LMICs",
    "WHO", "ICD", "DSM", "MRI", "fMRI", "EEG", "DNA", "RNA", "COVID", "SARS", "HIV",
    "GDP", "CPI", "ATR", "CPR", "NSE", "SOTA", "CRNN", "CTC", "CER", "WER", "AUC",
    "AUROC", "RMSE", "MAE", "GRIM", "FWER", "FDR", "IV", "DiD", "OLS", "GLS", "MCMC",
    "GHPU", "NOS", "JBI", "CONSORT", "STROBE", "AMSTAR"
  )

  /**
   * Converts raw lowercase filename slugs into publication-grade Title Case while preserving
   * properly capitalized academic titles and domain acronyms.
   */
  fun normalizeTitle(rawTitle: String): String {
    val trimmed = rawTitle.trim().replace(Regex("""\s+"""), " ")
    if (trimmed.isBlank()) return "Untitled Academic Manuscript"
    val letters = trimmed.filter { it.isLetter() }
    val upperRatio = if (letters.isEmpty()) 0f else letters.count { it.isUpperCase() }.toFloat() / letters.length
    // Preserve already properly cased titles (mixed case with meaningful capitals)
    if (upperRatio in 0.08f..0.75f && trimmed.first().isUpperCase()) {
      return trimmed
    }

    val words = trimmed.split(" ")
    val formatted = words.mapIndexed { idx, token ->
      val cleanWord = token.trim()
      val upperToken = cleanWord.uppercase(Locale.US)
      val matchedAcronym = preservedAcronyms.firstOrNull { it.equals(cleanWord, ignoreCase = true) }
      when {
        matchedAcronym != null -> matchedAcronym
        cleanWord.contains("-") -> {
          cleanWord.split("-").joinToString("-") { part ->
            part.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
          }
        }
        idx > 0 && idx < words.lastIndex && cleanWord.lowercase(Locale.US) in minorTitleWords -> {
          cleanWord.lowercase(Locale.US)
        }
        upperToken.length <= 4 && cleanWord == upperToken && cleanWord.any { it.isLetter() } -> {
          cleanWord
        }
        else -> {
          cleanWord.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }
      }
    }.joinToString(" ")

    // Clean common compound phrasing like "Systematic Review and Meta Analysis" -> "Meta-Analysis"
    return formatted
      .replace(Regex("""\bMeta Analysis\b""", RegexOption.IGNORE_CASE), "Meta-Analysis")
      .replace(Regex("""\bMeta Synthesis\b""", RegexOption.IGNORE_CASE), "Meta-Synthesis")
      .replace(Regex("""\bCross Sectional\b""", RegexOption.IGNORE_CASE), "Cross-Sectional")
      .replace(Regex("""\bLong Term\b""", RegexOption.IGNORE_CASE), "Long-Term")
      .replace(Regex("""\bShort Stay\b""", RegexOption.IGNORE_CASE), "Short-Stay")
      .replace(Regex("""\bLong Stay\b""", RegexOption.IGNORE_CASE), "Long-Stay")
  }

  /**
   * Sanitizes raw AI prose by converting stray LaTeX dollar wrappers and TeX commands into clean
   * Unicode typography, removing internal scorecard metadata tags, and splitting jammed inline
   * lists onto structured lines.
   */
  fun cleanAcademicProse(raw: String, stripInternalTags: Boolean = true): String {
    if (raw.isBlank()) return ""
    var text = raw.replace("\r\n", "\n").replace("\r", "\n")

    if (stripInternalTags) {
      // Remove duplicate internal metadata lines parsed by PaperReviewRubric
      text = text.replace(Regex("""(?m)^\[6-AXIS CALIBRATED SCORECARD:[^\n]*\]\s*\n?"""), "")
      text = text.replace(Regex("""(?m)^\[RUBRIC:[A-Za-z]+\][^\n]*\n?"""), "")
      text = text.replace(Regex("""(?m)^\[CONFIDENCE:\d/5\]\s*\n?"""), "")
    }

    // Replace common LaTeX commands with clean Unicode mathematical symbols
    val latexReplacements = listOf(
      "\\leq" to "≤",
      "\\le " to "≤ ",
      "\\geq" to "≥",
      "\\ge " to "≥ ",
      "\\neq" to "≠",
      "\\approx" to "≈",
      "\\pm" to "±",
      "\\times" to "×",
      "\\cdot" to "·",
      "\\infty" to "∞",
      "\\alpha" to "α",
      "\\beta" to "β",
      "\\gamma" to "γ",
      "\\delta" to "δ",
      "\\varepsilon" to "ε",
      "\\epsilon" to "ε",
      "\\lambda" to "λ",
      "\\mu" to "μ",
      "\\sigma" to "σ",
      "\\tau" to "τ",
      "\\kappa" to "κ",
      "\\theta" to "θ",
      "\\chi" to "χ",
      "\\sum" to "Σ",
      "\\rightarrow" to "→",
      "\\to " to "→ ",
      "\\in " to "∈ ",
      "I^2" to "I²",
      "\\tau^2" to "τ²",
      "R^2" to "R²",
      "\\chi^2" to "χ²"
    )
    for ((from, to) in latexReplacements) {
      text = text.replace(from, to)
    }

    // Strip redundant inline $...$ wrappers around simple variables, acronyms, and stats
    // e.g. ($IQR$) -> (IQR), ($IQRs$) -> (IQRs), $p < 0.05$ -> p < 0.05, $N = 65$ -> N = 65
    text = text.replace(Regex("""\$\$([^$\n]{1,120})\$\$""")) { m -> m.groupValues[1].trim() }
    text = text.replace(Regex("""\$([^$\n]{1,64})\$""")) { m ->
      val inner = m.groupValues[1].trim()
      inner
        .replace(Regex("""\\text\{([^}]*)\}"""), "$1")
        .replace(Regex("""\\mathrm\{([^}]*)\}"""), "$1")
        .replace("{", "")
        .replace("}", "")
    }

    // Clean markdown bold markers (**text**) so PDF/plain text don't show raw asterisks
    text = text.replace(Regex("""\*\*([^*\n]{1,140})\*\*"""), "$1")

    // Split jammed inline contributions: "... [C2] ..." -> newline before [C2]
    text = text.replace(Regex("""([^\n])\s+(\[C\d+\])"""), "$1\n$2")

    // Split jammed inline severity items: "... [MAJOR] 2." -> newline before [MAJOR]
    text = text.replace(
      Regex("""([^\n])\s+(\[(?:CRITICAL|MAJOR|MINOR|DISMISSED|MUST FIX|SHOULD FIX|RECOMMENDED)\])"""),
      "$1\n$2"
    )

    // Split jammed inline Red Team tags: "... [RT-2]" -> newline
    text = text.replace(Regex("""([^\n])\s+(\[(?:RT|F|Q)-?\d+\])"""), "$1\n$2")

    // Split jammed numbered questions/items when preceded by sentence punctuation (? or .)
    // e.g., "... during screening? 2. Can the authors..." -> newline before "2. "
    text = text.replace(Regex("""([.?!])\s+(\d{1,2}\.\s+[A-Z])"""), "$1\n$2")

    // Split jammed editorial sub-sections ("The Bottom Line:", "Future Research:", "Dismissed False Positives:")
    text = text.replace(
      Regex("""([.?!])\s+(The Bottom Line:|Bottom Line:|Future Research(?: Agenda)?:|Dismissed False Positives?:|Prioritized Revision Roadmap:|Recommended Sensitivity[^\n:]*:|Executive Assessment:|Substantive Prose Improvements:|Extraction / Typesetting Artifacts:)"""),
      "$1\n\n$2"
    )

    // Normalize excessive blank lines
    return text.replace(Regex("""\n{3,}"""), "\n\n").trim()
  }

  /**
   * Parses sanitized report text into typed semantic blocks for rich rendering in both PDF and Compose UI.
   */
  fun parseStructuredBlocks(raw: String, stripInternalTags: Boolean = true): List<ReportBlock> {
    val clean = cleanAcademicProse(raw, stripInternalTags = stripInternalTags)
    if (clean.isBlank()) return emptyList()

    val blocks = mutableListOf<ReportBlock>()
    val lines = clean.lines()

    val stageHeaderRegex = Regex("""^\[((?:STAGE|PRE-FLIGHT|PHD-SKILLS|PILLAR)[^\]]*)\]$""", RegexOption.IGNORE_CASE)
    val severityRegex = Regex(
      """^(?:[-•*]\s*)?\[(CRITICAL|MAJOR|MINOR|DISMISSED|MUST FIX|SHOULD FIX|RECOMMENDED|PASS|WARN|FAIL)(?:[^\]]*)\]\s*(.+)$""",
      RegexOption.IGNORE_CASE
    )
    val bulletContribRegex = Regex("""^(\[(?:C|RT|F|EQ|Q|STAT|M)-?\d+\])\s*(.+)$""")
    val numberedItemRegex = Regex("""^(\d{1,2}\.)\s+(.+)$""")
    val dashBulletRegex = Regex("""^(?:•|-|\*)\s+(.+)$""")
    val calloutRegex = Regex(
      """^(The Bottom Line|Bottom Line|Quantitative Bottom Line|Sober Verification Verdict|Estimator & Distributional Verdict|Statistical Rigor Summary|Future Research(?: Agenda)?|Dismissed False Positives?|Executive Assessment|Editorial Decision Rationale|Prioritized Revision Roadmap|Recommended Sensitivity[^\n:]*|Typesetting / Extraction Artifacts|Extraction / Typesetting Artifacts|Substantive Prose Improvements)\s*:\s*(.*)$""",
      RegexOption.IGNORE_CASE
    )
    val tableSeparatorRegex = Regex("""^\|?[\s:\-]+\|[\s:\-|]+$""")

    var idx = 0
    while (idx < lines.size) {
      val line = lines[idx].trim()
      if (line.isBlank()) {
        idx++
        continue
      }

      // 0. Pipe-delimited Structured Audit Table (| Col1 | Col2 | Status | Detail |)
      if (line.startsWith("|") && line.count { it == '|' } >= 3) {
        val tableLines = mutableListOf<String>()
        while (idx < lines.size) {
          val candidate = lines[idx].trim()
          if (candidate.startsWith("|") && candidate.count { it == '|' } >= 3) {
            tableLines.add(candidate)
            idx++
          } else {
            break
          }
        }
        val parsedRows = tableLines
          .filterNot { tableSeparatorRegex.matches(it) }
          .map { rowLine ->
            rowLine.trim('|').split("|").map { it.trim() }
          }
          .filter { cells -> cells.any { it.isNotBlank() } }

        if (parsedRows.size >= 2) {
          val headers = parsedRows.first()
          val colCount = headers.size
          val normalizedBody = parsedRows.drop(1).map { cells ->
            List(colCount) { c -> cells.getOrElse(c) { "" } }
          }
          blocks.add(ReportBlock.AuditTable(headers = headers, rows = normalizedBody))
        } else if (parsedRows.size == 1) {
          blocks.add(ReportBlock.Paragraph(parsedRows.first().joinToString(" • ")))
        }
        continue
      }

      // 1. Stage / Sub-section header: [STAGE 01a — THE BREAKER: ...] or [PILLAR 1 — ...]
      val stageMatch = stageHeaderRegex.matchEntire(line)
      if (stageMatch != null) {
        val inner = stageMatch.groupValues[1].trim()
        val splitParts = inner.split(Regex("""\s+[—–-]\s+|:\s+"""), limit = 2)
        if (splitParts.size == 2) {
          blocks.add(ReportBlock.SubHeader(tag = splitParts[0].trim(), title = splitParts[1].trim()))
        } else {
          blocks.add(ReportBlock.SubHeader(tag = "", title = inner))
        }
        idx++
        continue
      }

      // 2. Severity / Priority / Diagnostic item: [MAJOR] ..., [CRITICAL] ..., [FAIL] ...
      val sevMatch = severityRegex.matchEntire(line)
      if (sevMatch != null) {
        val tagRaw = sevMatch.groupValues[1].uppercase(Locale.US)
        val body = sevMatch.groupValues[2].trim()
        val level = when (tagRaw) {
          "CRITICAL" -> SeverityLevel.CRITICAL
          "MAJOR" -> SeverityLevel.MAJOR
          "MINOR" -> SeverityLevel.MINOR
          "DISMISSED" -> SeverityLevel.DISMISSED
          "MUST FIX" -> SeverityLevel.MUST_FIX
          "SHOULD FIX" -> SeverityLevel.SHOULD_FIX
          "PASS" -> SeverityLevel.PASS
          "WARN" -> SeverityLevel.WARN
          "FAIL" -> SeverityLevel.FAIL
          else -> SeverityLevel.RECOMMENDED
        }
        blocks.add(ReportBlock.SeverityCard(level = level, label = tagRaw, body = body))
        idx++
        continue
      }

      // 3. Callout headers (The Bottom Line:, Future Research:, Dismissed False Positives:, etc.)
      val calloutMatch = calloutRegex.matchEntire(line)
      if (calloutMatch != null) {
        val title = calloutMatch.groupValues[1].trim()
        val inlineBody = calloutMatch.groupValues[2].trim()
        val lowerTitle = title.lowercase(Locale.US)
        val tone = when {
          lowerTitle.contains("bottom line") -> CalloutTone.BOTTOM_LINE
          lowerTitle.contains("future research") -> CalloutTone.FUTURE_RESEARCH
          lowerTitle.contains("dismissed") -> CalloutTone.INFO
          lowerTitle.contains("artifact") || lowerTitle.contains("alert") -> CalloutTone.ALERT
          else -> CalloutTone.INFO
        }
        if (inlineBody.isNotBlank()) {
          if (lowerTitle.contains("dismissed")) {
            blocks.add(
              ReportBlock.SeverityCard(
                level = SeverityLevel.DISMISSED,
                label = "DISMISSED",
                body = inlineBody
              )
            )
          } else {
            blocks.add(ReportBlock.Callout(title = title, body = inlineBody, tone = tone))
          }
        } else {
          blocks.add(ReportBlock.SubHeader(tag = "", title = title))
        }
        idx++
        continue
      }

      // 4. Tagged contribution or finding bullet ([C1], [RT-1], [EQ1])
      val contribMatch = bulletContribRegex.matchEntire(line)
      if (contribMatch != null) {
        blocks.add(
          ReportBlock.Bullet(
            marker = contribMatch.groupValues[1],
            body = contribMatch.groupValues[2].trim()
          )
        )
        idx++
        continue
      }

      // 5. Numbered item (1. ..., 2. ...)
      val numMatch = numberedItemRegex.matchEntire(line)
      if (numMatch != null) {
        blocks.add(
          ReportBlock.Bullet(
            marker = numMatch.groupValues[1],
            body = numMatch.groupValues[2].trim()
          )
        )
        idx++
        continue
      }

      // 6. Standard bullet (• / - / *)
      val dashMatch = dashBulletRegex.matchEntire(line)
      if (dashMatch != null) {
        val bulletBody = dashMatch.groupValues[1].trim()
        // Check if bullet itself starts with a severity alert like [PRISMA GAP] or [REPRODUCIBILITY GAP]
        val innerAlert = Regex("""^\[([A-Z][A-Z0-9 /\-]{2,28})\]\s*(.+)$""").matchEntire(bulletBody)
        if (innerAlert != null) {
          val alertLabel = innerAlert.groupValues[1].trim()
          val alertBody = innerAlert.groupValues[2].trim()
          val level = when {
            alertLabel.contains("CRITICAL") || alertLabel.contains("LEAKAGE") -> SeverityLevel.CRITICAL
            alertLabel.contains("GAP") || alertLabel.contains("RISK") || alertLabel.contains("BIAS") -> SeverityLevel.MAJOR
            else -> SeverityLevel.MINOR
          }
          blocks.add(ReportBlock.SeverityCard(level = level, label = alertLabel, body = alertBody))
        } else {
          blocks.add(ReportBlock.Bullet(marker = "•", body = bulletBody))
        }
        idx++
        continue
      }

      // 7. Standalone symbolic equation line (keep monospace ONLY for true math formulas)
      if (isStandaloneEquationLine(line)) {
        blocks.add(ReportBlock.Equation(line))
        idx++
        continue
      }

      // 8. Standard prose paragraph
      blocks.add(ReportBlock.Paragraph(line))
      idx++
    }

    return blocks
  }

  /**
   * Produces a clean, publication-ready citation subtitle that gracefully omits blank authors,
   * missing years, or empty venue strings without stray punctuation like " () • ".
   */
  fun formatCleanCitation(
    authors: String,
    year: String,
    venue: String,
    fallback: String = "Uploaded Academic Manuscript"
  ): String {
    val cleanAuthors = authors.trim()
    val cleanYear = year.trim()
    val cleanVenue = venue.trim()
    val parts = buildList {
      when {
        cleanAuthors.isNotBlank() && cleanYear.isNotBlank() -> add("$cleanAuthors ($cleanYear)")
        cleanAuthors.isNotBlank() -> add(cleanAuthors)
        cleanYear.isNotBlank() -> add("($cleanYear)")
      }
      if (cleanVenue.isNotBlank()) add(cleanVenue)
    }
    return parts.joinToString(" • ").ifBlank { fallback }
  }

  /**
   * Cross-section keyword search across an entire PaperReview dossier.
   * Returns exact section tab/sub-segment coordinates and a cleaned context snippet so users can
   * jump directly to any finding, metric, or equation across all 30 referee stages.
   */
  fun searchDossier(
    review: com.example.data.model.PaperReview,
    rawQuery: String
  ): List<DossierSearchMatch> {
    val query = rawQuery.trim()
    if (query.length < 2) return emptyList()

    val sections = listOf(
      Triple(
        "Executive Summary & RQ",
        0 to 1,
        listOf(
          review.abstractSummary,
          review.researchQuestion,
          review.keyMethodology,
          review.coreContributions
        ).filter { it.isNotBlank() }.joinToString("\n")
      ),
      Triple("Senior Editor Memo", 0 to 2, review.editorNote),
      Triple("My Revision Notes", 0 to 3, review.personalNotes),
      Triple("Red Team Critique (01a–01g)", 1 to 0, review.redTeamCritique),
      Triple("Blue Team Verification (02c–03c)", 1 to 1, review.blueTeamVerification),
      Triple("Quantitative & Statistical Audit", 2 to 0, review.mathAuditReport),
      Triple("Reproducibility & Leakage Audit", 2 to 1, review.codeAuditReport),
      Triple("Copyedit & Revision Actions", 3 to 0, review.copyeditSuggestions),
      Triple("Mandatory Author Questions", 3 to 1, review.reviewerQuestions)
    )

    val results = mutableListOf<DossierSearchMatch>()
    for ((label, coords, rawBody) in sections) {
      if (rawBody.isBlank()) continue
      val cleaned = cleanAcademicProse(rawBody, stripInternalTags = true)
      val lower = cleaned.lowercase(Locale.US)
      val needle = query.lowercase(Locale.US)
      val firstIdx = lower.indexOf(needle)
      if (firstIdx < 0) continue

      var count = 0
      var scanIdx = firstIdx
      while (scanIdx >= 0) {
        count++
        scanIdx = lower.indexOf(needle, scanIdx + needle.length)
      }

      val snippetStart = (firstIdx - 52).coerceAtLeast(0)
      val snippetEnd = (firstIdx + needle.length + 88).coerceAtMost(cleaned.length)
      val prefix = if (snippetStart > 0) "…" else ""
      val suffix = if (snippetEnd < cleaned.length) "…" else ""
      val snippet = prefix + cleaned.substring(snippetStart, snippetEnd)
        .replace(Regex("""\s+"""), " ")
        .trim() + suffix

      results.add(
        DossierSearchMatch(
          sectionTitle = label,
          sectionTabIndex = coords.first,
          subSegmentIndex = coords.second,
          matchCount = count,
          snippet = snippet
        )
      )
    }
    return results.sortedByDescending { it.matchCount }
  }

  private fun isStandaloneEquationLine(line: String): Boolean {
    if (line.length > 140 || line.length < 5) return false
    val hasEquals = line.contains("=") || line.contains("≈") || line.contains("≤") || line.contains("≥")
    val hasMathOps = line.contains("Σ") || line.contains("\\frac") || line.contains("_") ||
      line.contains("^") || line.contains("arg max") || line.contains("∑")
    val wordCount = line.split(Regex("""\s+""")).size
    return hasEquals && hasMathOps && wordCount <= 14
  }
}

data class DossierSearchMatch(
  val sectionTitle: String,
  val sectionTabIndex: Int,
  val subSegmentIndex: Int,
  val matchCount: Int,
  val snippet: String
)

