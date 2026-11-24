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

  private enum class InternalHeaderType(val defaultTag: String) {
    STAGE("STAGE"),
    AUDIT("AUDIT"),
    IDENTITY("IDENTITY"),
    CLAIM_HIERARCHY("CLAIMS"),
    PRACTICAL_SIGNIFICANCE("SIGNIFICANCE"),
    FRAGILITY("FRAGILITY"),
    UNCERTAINTY("UNCERTAINTY"),
    UNSUPPORTED_EVIDENCE("EVIDENCE GAP"),
    PHASE_REPORT("PHASE 8"),
    UNKNOWN("REPORT")
  }

  private val wholeLineBracketHeaderRegex = Regex("""^\s*\[([^\[\]\n]{4,160})\]\s*$""")
  private val findingIdOnlyRegex = Regex("""^(?:R|C|RT|F|EQ|Q|S|E|STAT|M)-?\d+[a-z]?$""", RegexOption.IGNORE_CASE)

  private fun classifyInternalHeader(raw: String): InternalHeaderType? {
    val trimmed = raw.trim()
    val match = wholeLineBracketHeaderRegex.matchEntire(trimmed) ?: return null
    val inner = match.groupValues[1].trim()
    if (findingIdOnlyRegex.matches(inner)) return null
    val upper = inner.uppercase(Locale.US)
    return when {
      upper.startsWith("STAGE") || upper.startsWith("PRE-FLIGHT") ||
        upper.startsWith("PHD-SKILLS") || upper.startsWith("PILLAR") -> InternalHeaderType.STAGE
      upper.startsWith("STUDY IDENTITY CARD") || upper.startsWith("REVIEW IDENTITY CARD") -> InternalHeaderType.IDENTITY
      upper.startsWith("CLAIM HIERARCHY") || upper.startsWith("PRIMARY-ANALYSIS") -> InternalHeaderType.CLAIM_HIERARCHY
      upper.startsWith("CLINICAL & PRACTICAL SIGNIFICANCE") ||
        upper.startsWith("PRACTICAL SIGNIFICANCE") ||
        upper.startsWith("CLINICAL & POLICY APPLICABILITY") -> InternalHeaderType.PRACTICAL_SIGNIFICANCE
      upper.startsWith("WHAT WOULD CHANGE") ||
        upper.startsWith("POSSIBLE REASONS FOR THE RESULT") ||
        upper.startsWith("MINIMUM EVIDENCE TO OVERTURN") -> InternalHeaderType.FRAGILITY
      upper.startsWith("EXPLICIT UNCERTAINTY BUDGET") ||
        upper.startsWith("MULTIPLICITY-FAMILY UNCERTAINTY") ||
        upper.startsWith("ABSTENTION POLICY") ||
        upper.startsWith("STOP-AND-ASK") -> InternalHeaderType.UNCERTAINTY
      upper.startsWith("UNSUPPORTED-TO-ASSESS") ||
        upper.startsWith("EXTRACTION FAILURE VS") -> InternalHeaderType.UNSUPPORTED_EVIDENCE
      upper.startsWith("PHASE 8") || upper.startsWith("PHASE 9") -> InternalHeaderType.PHASE_REPORT
      upper.startsWith("DESIGN-AWARE STATISTICAL") ||
        upper.startsWith("DOMAIN-SPECIFIC STATISTICAL") ||
        upper.startsWith("EXECUTABLE NUMERICAL RECONSTRUCTION") ||
        upper.startsWith("CROSS-SECTION CONSISTENCY") ||
        upper.startsWith("CROSS-SECTION CONTRADICTION") ||
        upper.startsWith("DENOMINATOR LEDGER") ||
        upper.startsWith("DENOMINATOR PROVENANCE") ||
        upper.startsWith("EVIDENCE-DERIVED SCORE") ||
        upper.startsWith("STATISTICAL") -> InternalHeaderType.AUDIT
      inner.any { it.isLetter() } && inner == inner.uppercase(Locale.US) ||
        inner.contains(" — ") || inner.contains(" - ") ||
        inner.endsWith("?") || inner.endsWith(")") -> InternalHeaderType.UNKNOWN
      else -> null
    }
  }

  private fun buildSubHeaderFromInternalBracket(raw: String, type: InternalHeaderType): ReportBlock.SubHeader {
    val inner = raw.trim().removePrefix("[").removeSuffix("]").trim()
    val cleanedInner = humanizeInternalEnums(inner)
    val splitParts = cleanedInner.split(Regex("""\s+[—–-]\s+|:\s+"""), limit = 2)
    return when {
      type == InternalHeaderType.PHASE_REPORT && splitParts.size == 2 -> {
        ReportBlock.SubHeader(
          tag = splitParts[0].trim().uppercase(Locale.US),
          title = toReadableHeaderTitle(splitParts[1].trim())
        )
      }
      type == InternalHeaderType.STAGE && splitParts.size == 2 -> {
        ReportBlock.SubHeader(
          tag = splitParts[0].trim(),
          title = splitParts[1].trim()
        )
      }
      splitParts.size == 2 && splitParts[0].trim().length <= 24 -> {
        ReportBlock.SubHeader(
          tag = splitParts[0].trim().uppercase(Locale.US),
          title = toReadableHeaderTitle(splitParts[1].trim())
        )
      }
      else -> {
        ReportBlock.SubHeader(
          tag = type.defaultTag,
          title = toReadableHeaderTitle(cleanedInner)
        )
      }
    }
  }

  private fun toReadableHeaderTitle(raw: String): String {
    val clean = raw.trim().removePrefix("[").removeSuffix("]").trim()
    val letters = clean.filter { it.isLetter() }
    if (letters.isEmpty()) return clean
    val upperRatio = letters.count { it.isUpperCase() }.toFloat() / letters.length.toFloat()
    return if (upperRatio >= 0.80f && clean.length > 8) {
      normalizeTitle(clean.lowercase(Locale.US))
    } else {
      clean
    }
  }

  fun sanitizeTableCell(value: String): String =
    humanizeInternalEnums(value)
      .replace('\r', ' ')
      .replace('\n', ' ')
      .replace(Regex("""\s+"""), " ")
      .replace("|", "/")
      .trim()

  fun clampCell(value: String, maxChars: Int): String {
    val sanitized = sanitizeTableCell(value)
    if (sanitized.length <= maxChars || maxChars <= 2) return sanitized
    val cut = sanitized.take(maxChars - 1)
    val lastSpace = cut.lastIndexOf(' ')
    val bounded = if (lastSpace >= (maxChars * 0.65).toInt()) {
      cut.substring(0, lastSpace)
    } else {
      cut
    }
    return bounded.trimEnd(' ', ',', ';', ':') + "…"
  }

  fun humanizeInternalEnums(raw: String): String {
    if (raw.isBlank()) return raw
    val replacements = listOf(
      "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL" to "Not assessable from supplied material",
      "REPORTED_BUT_INADEQUATE" to "Reported but inadequate",
      "REPORTED_AND_ADEQUATE" to "Reported and adequate",
      "DOCUMENTED_AND_ADEQUATE" to "Documented and adequate",
      "DOCUMENTED_BUT_INADEQUATE" to "Documented but inadequate",
      "UNSUPPORTED_TO_ASSESS" to "Unsupported to assess (missing inputs)",
      "IMPRECISION_OR_HIGH_VARIANCE" to "Imprecision / high variance",
      "FATAL_TO_PRIMARY_CONCLUSION" to "Fatal to primary conclusion",
      "MATERIALLY_WEAKENS_CONFIDENCE" to "Materially weakens confidence",
      "REQUIRES_REPORTING_CORRECTION" to "Requires reporting correction",
      "WORTHWHILE_ENHANCEMENT" to "Worthwhile enhancement",
      "COSMETIC_OR_EDITORIAL" to "Cosmetic or editorial",
      "INTERNAL_CONTRADICTION" to "Internal contradiction",
      "ASSUMPTION_UNCHECKED" to "Assumption unchecked",
      "REPORTING_OMISSION" to "Reporting omission",
      "EXTERNAL_VALIDITY_LIMIT" to "External validity limit",
      "ARITHMETIC_ERROR" to "Arithmetic error",
      "MODEL_MISMATCH" to "Model / design mismatch",
      "POSSIBLE_BIAS" to "Possible bias / screening signal",
      "POTENTIAL_RISK" to "Potential risk",
      "NOT_APPLICABLE" to "Not applicable",
      "NOT_ASSESSABLE" to "Not assessable",
      "NOT_REPORTED" to "Not reported"
    )
    var out = raw
    for ((token, label) in replacements) {
      if (out.contains(token)) {
        out = out.replace(Regex("""\b${Regex.escape(token)}\b"""), label)
      }
    }
    return out
  }

  /**
   * Parses sanitized report text into typed semantic blocks for rich rendering in both PDF and Compose UI.
   */
  fun parseStructuredBlocks(raw: String, stripInternalTags: Boolean = true): List<ReportBlock> {
    val clean = cleanAcademicProse(raw, stripInternalTags = stripInternalTags)
    if (clean.isBlank()) return emptyList()

    val blocks = mutableListOf<ReportBlock>()
    val lines = clean.lines()

    val severityRegex = Regex(
      """^(?:[-•*]\s*)?\[(CRITICAL|MAJOR|MINOR|DISMISSED|MUST FIX|SHOULD FIX|RECOMMENDED|PASS|WARN|FAIL)(?:[^\]]*)\]\s*(.+)$""",
      RegexOption.IGNORE_CASE
    )
    val bulletContribRegex = Regex("""^(\[(?:C|RT|F|EQ|Q|R|S|E|STAT|M)-?\d+\])\s*(.+)$""")
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

      // 0. Pipe-delimited Structured Audit Table (resilient to wrapped multi-line cells)
      if (line.startsWith("|") && line.count { it == '|' } >= 2) {
        val rawTableLines = mutableListOf<String>()
        var expectedPipes = line.count { it == '|' }
        var openRow = StringBuilder(line)
        idx++

        fun flushOpenRow() {
          val built = openRow.toString().trim()
          if (built.isNotBlank()) {
            rawTableLines.add(built)
          }
          openRow = StringBuilder()
        }

        while (idx < lines.size) {
          val candidate = lines[idx].trim()
          if (candidate.isBlank()) {
            // Look ahead: if current row is clearly incomplete (doesn't end with '|'), swallow single blank inside cell only if next line finishes row
            if (!openRow.toString().trimEnd().endsWith("|") && idx + 1 < lines.size) {
              val nextLine = lines[idx + 1].trim()
              if (nextLine.isNotBlank() && !nextLine.startsWith("|") && nextLine.contains("|")) {
                idx++
                continue
              }
            }
            break
          }
          val openStr = openRow.toString().trimEnd()
          val openIsIncomplete = !openStr.endsWith("|") ||
            (!tableSeparatorRegex.matches(openStr) && openStr.count { it == '|' } < expectedPipes)

          if (candidate.startsWith("|")) {
            flushOpenRow()
            openRow.append(candidate)
            if (rawTableLines.size == 1 && !tableSeparatorRegex.matches(candidate)) {
              expectedPipes = maxOf(expectedPipes, candidate.count { it == '|' })
            }
            idx++
          } else if (openIsIncomplete && classifyInternalHeader(candidate) == null) {
            // Wrapped continuation line belonging to the previous table cell
            openRow.append(' ').append(candidate)
            idx++
          } else {
            break
          }
        }
        flushOpenRow()

        val parsedRows = rawTableLines
          .filterNot { tableSeparatorRegex.matches(it) }
          .map { rowLine ->
            val trimmedRow = rowLine.trim().removePrefix("|").removeSuffix("|")
            trimmedRow.split("|").map { sanitizeTableCell(it) }
          }
          .filter { cells -> cells.any { it.isNotBlank() } }

        if (parsedRows.size >= 2) {
          val headers = parsedRows.first()
          val colCount = headers.size
          val normalizedBody = parsedRows.drop(1).map { cells ->
            when {
              cells.size == colCount -> cells
              cells.size < colCount -> List(colCount) { c -> cells.getOrElse(c) { "" } }
              else -> {
                val head = cells.take(colCount - 1)
                val mergedTail = cells.drop(colCount - 1).joinToString(" / ")
                head + mergedTail
              }
            }
          }
          blocks.add(ReportBlock.AuditTable(headers = headers, rows = normalizedBody))
        } else if (parsedRows.size == 1) {
          blocks.add(ReportBlock.Paragraph(parsedRows.first().joinToString(" • ")))
        }
        continue
      }

      // 1. Centralized Internal Bracket Header Classifier (NEVER renders outer '[' or ']')
      val internalHeaderType = classifyInternalHeader(line)
      if (internalHeaderType != null) {
        blocks.add(buildSubHeaderFromInternalBracket(line, internalHeaderType))
        idx++
        continue
      }

      // 2. Severity / Priority / Diagnostic item: [MAJOR] ..., [CRITICAL] ..., [FAIL] ...
      val sevMatch = severityRegex.matchEntire(line)
      if (sevMatch != null) {
        val tagRaw = sevMatch.groupValues[1].uppercase(Locale.US)
        val body = humanizeInternalEnums(sevMatch.groupValues[2].trim())
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
        val inlineBody = humanizeInternalEnums(calloutMatch.groupValues[2].trim())
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

      // 4. Tagged contribution or finding bullet ([C1], [RT-1], [R-01], [EQ1])
      val contribMatch = bulletContribRegex.matchEntire(line)
      if (contribMatch != null) {
        blocks.add(
          ReportBlock.Bullet(
            marker = contribMatch.groupValues[1],
            body = humanizeInternalEnums(contribMatch.groupValues[2].trim())
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
            body = humanizeInternalEnums(numMatch.groupValues[2].trim())
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
        val innerAlert = Regex("""^\[([A-Z][A-Z0-9 /_|\-•]{2,48})\]\s*(.+)$""").matchEntire(bulletBody)
        if (innerAlert != null) {
          val alertLabel = humanizeInternalEnums(innerAlert.groupValues[1].trim())
          val alertBody = humanizeInternalEnums(innerAlert.groupValues[2].trim())
          val upperLabel = innerAlert.groupValues[1].uppercase(Locale.US)
          val level = when {
            upperLabel.contains("CRITICAL") || upperLabel.contains("LEAKAGE") || upperLabel.contains("FATAL") || upperLabel.contains("ARITHMETIC") -> SeverityLevel.CRITICAL
            upperLabel.contains("GAP") || upperLabel.contains("RISK") || upperLabel.contains("BIAS") || upperLabel.contains("HIGH") || upperLabel.contains("WARN") || upperLabel.contains("UNSUPPORTED") -> SeverityLevel.MAJOR
            else -> SeverityLevel.MINOR
          }
          blocks.add(ReportBlock.SeverityCard(level = level, label = alertLabel, body = alertBody))
        } else {
          blocks.add(ReportBlock.Bullet(marker = "•", body = humanizeInternalEnums(bulletBody)))
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
      blocks.add(ReportBlock.Paragraph(humanizeInternalEnums(line)))
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

