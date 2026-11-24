package com.example.data.ai

import com.example.data.ai.epistemic.EpistemicSafeguardEngine
import com.example.data.ai.epistemic.EpistemicSafeguardReport
import com.example.data.ai.stats.StatisticalAuditOrchestrator
import com.example.data.ai.stats.StatisticalAuditReport
import com.example.util.DocumentCoverageReport
import com.example.util.DossierFormattingEngine
import com.example.util.ReportBlock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class QuantFindingStatus {
  PASS,
  WARN,
  FAIL,
  INFO
}

data class QuantDiagnosticFinding(
  val category: String,
  val metricOrCheck: String,
  val reportedEvidence: String,
  val status: QuantFindingStatus,
  val diagnosticDetail: String,
  val pageNumber: Int = 0,
  val isAbsenceHeuristic: Boolean = false
)

data class StatcheckResult(
  val rawClaim: String,
  val testType: String,
  val reportedP: String,
  val recomputedP: Double,
  val status: QuantFindingStatus,
  val note: String,
  val pageNumber: Int = 0
)

data class ConfidenceIntervalCheck(
  val rawClaim: String,
  val metricLabel: String,
  val pointEstimate: Double,
  val lower: Double,
  val upper: Double,
  val impliedSe: Double?,
  val status: QuantFindingStatus,
  val note: String,
  val pageNumber: Int = 0,
  val crossReferenceNote: String = ""
)

data class MedianIqrCheck(
  val rawClaim: String,
  val median: Double,
  val q1: Double,
  val q3: Double,
  val bowleySkewness: Double,
  val status: QuantFindingStatus,
  val note: String,
  val pageNumber: Int = 0
)

data class QuantitativeForensicReport(
  val statcheckResults: List<StatcheckResult>,
  val confidenceIntervalChecks: List<ConfidenceIntervalCheck>,
  val medianIqrChecks: List<MedianIqrCheck>,
  val heterogeneitySignals: List<String>,
  val powerAndMultiplicityAlerts: List<String>,
  val algebraicAndMetricAlerts: List<String>,
  val diagnosticTable: List<QuantDiagnosticFinding>,
  val extractedPrecisionMetrics: List<String>,
  val statisticalAudit: StatisticalAuditReport? = null,
  val epistemicSafeguards: EpistemicSafeguardReport? = null
) {
  val totalChecksExecuted: Int
    get() = statcheckResults.size + confidenceIntervalChecks.size + medianIqrChecks.size +
      heterogeneitySignals.size + powerAndMultiplicityAlerts.size + algebraicAndMetricAlerts.size

  val criticalDiscrepancyCount: Int
    get() = diagnosticTable.count { it.status == QuantFindingStatus.FAIL && !it.isAbsenceHeuristic }

  val warningCount: Int
    get() = diagnosticTable.count { it.status == QuantFindingStatus.WARN }

  fun toPromptBlock(): String = buildString {
    if (epistemicSafeguards != null) {
      appendLine(epistemicSafeguards.toPromptBlock())
      appendLine()
    }
    appendLine("[DETERMINISTIC QUANTITATIVE & STATISTICAL FORENSICS ENGINE (Exact Math Verification)]")
    appendLine(
      "Summary: ${statcheckResults.size} statcheck recomputation(s), " +
        "${confidenceIntervalChecks.size} CI geometry check(s), " +
        "${medianIqrChecks.size} Median/IQR skewness check(s), " +
        "$criticalDiscrepancyCount confirmed numerical FAIL flag(s), $warningCount WARN flag(s)."
    )
    appendLine(
      "IMPORTANT RULE: Confirmed numerical FAIL flags above (e.g., impossible CI bounds, statcheck decision conflict) are verified arithmetic errors. " +
        "Absence-of-keyword notes are ONLY prompts to verify in the complete manuscript, tables, and figures — NEVER treat a missing keyword as proof that an analysis was omitted!"
    )

    if (statcheckResults.isNotEmpty()) {
      appendLine("  Statcheck Test-Statistic Recalculations:")
      statcheckResults.take(12).forEach { r ->
        val pg = if (r.pageNumber > 0) " [Page ${r.pageNumber}]" else ""
        appendLine(
          String.format(
            Locale.US,
            "  * [%s]%s %s -> Recomputed two-tailed p = %.4f (%s)",
            r.status.name,
            pg,
            r.rawClaim,
            r.recomputedP,
            r.note
          )
        )
      }
    }

    if (confidenceIntervalChecks.isNotEmpty()) {
      appendLine("  Confidence Interval Geometry, Implied SE & Cross-Reference Checks:")
      confidenceIntervalChecks.take(12).forEach { c ->
        val pg = if (c.pageNumber > 0) " [Page ${c.pageNumber}]" else ""
        val seText = c.impliedSe?.let { String.format(Locale.US, "implied SE=%.4f", it) } ?: "SE n/a"
        val xref = if (c.crossReferenceNote.isNotBlank()) " | CROSS-REF: ${c.crossReferenceNote}" else ""
        appendLine("  * [${c.status.name}]$pg ${c.rawClaim} ($seText) -> ${c.note}$xref")
      }
    }

    if (medianIqrChecks.isNotEmpty()) {
      appendLine("  Non-Parametric Median / IQR & Bowley Skewness Diagnostics:")
      medianIqrChecks.take(8).forEach { m ->
        val pg = if (m.pageNumber > 0) " [Page ${m.pageNumber}]" else ""
        appendLine(
          String.format(
            Locale.US,
            "  * [%s]%s %s | Bowley skew = %.2f -> %s",
            m.status.name,
            pg,
            m.rawClaim,
            m.bowleySkewness,
            m.note
          )
        )
      }
    }

    if (heterogeneitySignals.isNotEmpty()) {
      appendLine("  Meta-Analysis & Heterogeneity Diagnostics:")
      heterogeneitySignals.forEach { appendLine("  * $it") }
    }

    if (powerAndMultiplicityAlerts.isNotEmpty()) {
      appendLine("  Power, Sample Size & Multiplicity Diagnostics:")
      powerAndMultiplicityAlerts.forEach { appendLine("  * $it") }
    }

    if (algebraicAndMetricAlerts.isNotEmpty()) {
      appendLine("  Algebraic, Range & Metric Plausibility Alerts:")
      algebraicAndMetricAlerts.forEach { appendLine("  * $it") }
    }

    if (statisticalAudit != null) {
      appendLine()
      appendLine(statisticalAudit.toPromptBlock())
    }
  }

  /**
   * Formats deterministic quantitative verification results into structured markdown table rows
   * and severity bullets that render cleanly in both the PDF and Compose UI.
   */
  fun toDeterministicAuditSummary(): String = buildString {
    if (diagnosticTable.isNotEmpty()) {
      appendLine("[PRE-FLIGHT DETERMINISTIC STATISTICAL FORENSICS (${diagnosticTable.size} Automated Checks)]")
      appendLine("| Check Domain | Reported Evidence | Status | Exact Mathematical Finding |")
      appendLine("|---|---|---|---|")
      diagnosticTable.take(12).forEach { row ->
        val pg = if (row.pageNumber > 0) " (P${row.pageNumber})" else ""
        val safeCat = DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(row.category + pg), 48)
        val safeEv = DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(row.reportedEvidence), 76)
        val safeDetail = DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(row.diagnosticDetail), 220)
        appendLine("| $safeCat | $safeEv | ${row.status.name} | $safeDetail |")
      }
    }
    if (epistemicSafeguards != null) {
      val epiMd = epistemicSafeguards.toAuditMarkdown()
      if (epiMd.isNotBlank()) {
        if (isNotEmpty()) appendLine()
        appendLine(epiMd)
      }
    }
    if (statisticalAudit != null) {
      val trail = statisticalAudit.toAuditTrailMarkdown()
      if (trail.isNotBlank()) {
        if (isNotEmpty()) appendLine()
        appendLine(trail)
      }
    }
  }.trim()

  /**
   * Direct typed [ReportBlock] builder for PDF export so deterministic audit tables never suffer
   * Markdown round-trip corruption from embedded newlines or pipe characters.
   */
  fun toDeterministicAuditBlocks(): List<ReportBlock> {
    val blocks = mutableListOf<ReportBlock>()
    if (diagnosticTable.isNotEmpty()) {
      blocks.add(
        ReportBlock.SubHeader(
          tag = "PRE-FLIGHT",
          title = "Deterministic Statistical Forensics (${diagnosticTable.size} Automated Checks)"
        )
      )
      blocks.add(
        ReportBlock.AuditTable(
          headers = listOf("Check Domain", "Reported Evidence", "Status", "Exact Mathematical Finding"),
          rows = diagnosticTable.take(12).map { row ->
            val pg = if (row.pageNumber > 0) " (P${row.pageNumber})" else ""
            listOf(
              DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(row.category + pg), 48),
              DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(row.reportedEvidence), 76),
              row.status.name,
              DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(row.diagnosticDetail), 220)
            )
          }
        )
      )
    }
    val secondaryMarkdown = buildString {
      if (epistemicSafeguards != null) {
        val epiMd = epistemicSafeguards.toAuditMarkdown()
        if (epiMd.isNotBlank()) appendLine(epiMd)
      }
      if (statisticalAudit != null) {
        val trail = statisticalAudit.toAuditTrailMarkdown()
        if (trail.isNotBlank()) {
          if (isNotEmpty()) appendLine()
          appendLine(trail)
        }
      }
    }.trim()
    if (secondaryMarkdown.isNotBlank()) {
      blocks.addAll(DossierFormattingEngine.parseStructuredBlocks(secondaryMarkdown))
    }
    return blocks
  }
}

/**
 * Pure-Kotlin deterministic statistical & mathematical forensics engine.
 * Implements exact CDF/survival functions (Normal, Student-t, Chi-Square, F) for statcheck
 * test-statistic recalculation, parenthesized & narrative confidence interval geometry checks
 * with cross-reference discrepancy linking (e.g., narrative vs. Figure 2), Bowley IQR skewness
 * diagnostics, context-aware heterogeneity audits (recognizing inequalities like I² > 99% and
 * text mentions like I2-statistic), FWER inflation checks, and algebraic metric bounds.
 */
object QuantitativeForensicAnalyzer {

  private val pageMarkerRegex = Regex("""\[PAGE\s+(\d+)\]""", RegexOption.IGNORE_CASE)

  // --- Regex Extractors ---
  private val tStatRegex = Regex(
    """\bt\s*\(\s*(\d+(?:\.\d+)?)\s*\)\s*=\s*([-+]?\d+(?:\.\d+)?)\s*[,;]\s*p\s*([=<>≤≥])\s*(0?\.\d+)""",
    RegexOption.IGNORE_CASE
  )
  private val zStatRegex = Regex(
    """\bz\s*=\s*([-+]?\d+(?:\.\d+)?)\s*[,;]\s*p\s*([=<>≤≥])\s*(0?\.\d+)""",
    RegexOption.IGNORE_CASE
  )
  private val chiSqRegex = Regex(
    """(?:chi\^?2|χ2|χ²)\s*\(\s*(\d+(?:\.\d+)?)\s*(?:,\s*N\s*=\s*\d+)?\s*\)\s*=\s*(\d+(?:\.\d+)?)\s*[,;]\s*p\s*([=<>≤≥])\s*(0?\.\d+)""",
    RegexOption.IGNORE_CASE
  )
  private val fStatRegex = Regex(
    """\bF\s*\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\)\s*=\s*(\d+(?:\.\d+)?)\s*[,;]\s*p\s*([=<>≤≥])\s*(0?\.\d+)""",
    RegexOption.IGNORE_CASE
  )
  private val rStatRegex = Regex(
    """\br\s*\(\s*(\d+)\s*\)\s*=\s*([-+]?0?\.\d+)\s*[,;]\s*p\s*([=<>≤≥])\s*(0?\.\d+)""",
    RegexOption.IGNORE_CASE
  )

  // Standard parenthesized CI: "OR = 2.40 (95% CI 0.90–1.80)" or "9 (95% CI 3–15)"
  private val ciParenRegex = Regex(
    """\b(OR|aOR|RR|aRR|HR|aHR|IRR|PR|SMD|MD|WMD|beta|β|mean|median|rate|estimate|pooled)?\s*(?:of\s*|was\s*|[=:]\s*)?([-+]?\d+(?:\.\d+)?)\s*\(\s*(?:95%\s*)?(?:CI|CrI|UI|confidence\s+interval)\s*(?:of\s*)?[:=]?\s*([-+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*([-+]?\d+(?:\.\d+)?)\s*\)""",
    RegexOption.IGNORE_CASE
  )

  // Narrative / comma-separated CI: "pooled long-stay bed estimate of 9, 95% CI 1.26 to 2.35"
  // or "PR = 2.21, 95% CI 0.00 to 4.50"
  private val ciNarrativeRegex = Regex(
    """\b(OR|aOR|RR|aRR|HR|aHR|IRR|PR|SMD|MD|WMD|beta|β|mean|median|rate|estimate|beds?)\s*(?:of\s*|was\s*|[=:]\s*)\s*([-+]?\d+(?:\.\d+)?)\s*[,;]\s*(?:with\s+(?:a\s+)?)?(?:95%\s*)?(?:CI|CrI|UI|confidence\s+interval)\s*(?:of\s*)?[:=]?\s*([-+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-)\s*([-+]?\d+(?:\.\d+)?)""",
    RegexOption.IGNORE_CASE
  )

  private val medianIqrRegex = Regex(
    """median\s*(?:of\s*|was\s*|[=:]\s*)?(\d+(?:\.\d+)?)\s*(?:per\s*[\d,]+)?\s*\(\s*(?:IQR|interquartile\s+range)\s*[:=]?\s*(\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*(\d+(?:\.\d+)?)\s*\)""",
    RegexOption.IGNORE_CASE
  )

  // Captures I² = 84%, I² > 99%, I2 >= 95%, I² above 99%, I2 exceeded 90%, I² of 88%
  private val iSquaredRegex = Regex(
    """\bI(?:\^2|²|2)(?:\s*-?\s*statistic)?\s*(?:[=≈<>≤≥]|>=|<=|above|over|exceeded|exceeding|of|was|were)\s*(\d+(?:\.\d+)?)\s*%""",
    RegexOption.IGNORE_CASE
  )

  private val kStudiesRegex = Regex(
    """\b(?:k\s*=\s*(\d+)|(\d+)\s+(?:included\s+)?(?:studies|publications|articles|trials))\b""",
    RegexOption.IGNORE_CASE
  )

  private val rSquaredRegex = Regex("""\bR(?:\^2|²)\s*=\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
  private val aucRegex = Regex("""\b(?:AUC|AUROC)\s*=\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
  private val pValueLooseRegex = Regex("""\bp\s*([=<>≤≥])\s*([-+]?\d*\.?\d+)""", RegexOption.IGNORE_CASE)
  private val clusterCountRegex = Regex("""(\d+)\s+(?:clusters|states|countries|clinics|hospitals|schools|firms)\b""", RegexOption.IGNORE_CASE)

  internal fun resolvePageNumberAtOffset(text: String, offset: Int): Int {
    if (offset <= 0 || text.isEmpty()) return 0
    val prefix = text.substring(0, offset.coerceAtMost(text.length))
    val lastMatch = pageMarkerRegex.findAll(prefix).lastOrNull()
    return lastMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
  }

  @Deprecated(
    message = "Provide explicit DocumentCoverageReport and extractionConfidence to prevent silent assumption of complete extraction.",
    replaceWith = ReplaceWith(
      "analyze(text, studyDesign, sampleSizes, DocumentCoverageReport.unknown(), DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN)"
    )
  )
  fun analyze(
    text: String,
    studyDesign: StudyDesignArchetype,
    sampleSizes: List<Int>
  ): QuantitativeForensicReport {
    val unknownCoverage = DocumentCoverageReport.unknown(
      reason = "QuantitativeForensicAnalyzer.analyze() invoked without explicit DocumentCoverageReport; coverage status is UNKNOWN and strong conclusions are disabled."
    )
    return analyze(
      text = text,
      studyDesign = studyDesign,
      sampleSizes = sampleSizes,
      coverageReport = unknownCoverage,
      extractionConfidence = DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN
    )
  }

  fun analyze(
    text: String,
    studyDesign: StudyDesignArchetype,
    sampleSizes: List<Int>,
    coverageReport: DocumentCoverageReport,
    extractionConfidence: Int = coverageReport.extractionConfidence
  ): QuantitativeForensicReport {
    val clean = text.trim()
    val refHeaderMatch = Regex("""(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(references|bibliography|works\s+cited|literature\s+cited)\s*$""").find(clean)
    val bodyWithoutRefs = if (refHeaderMatch != null && refHeaderMatch.range.first > 120) {
      clean.substring(0, refHeaderMatch.range.first).trimEnd()
    } else {
      clean
    }
    val lower = bodyWithoutRefs.lowercase(Locale.US)

    val statchecks = runStatcheckAudit(bodyWithoutRefs)
    val ciChecks = runConfidenceIntervalAudit(bodyWithoutRefs)
    val iqrChecks = runMedianIqrAudit(bodyWithoutRefs)
    val heterogeneitySignals = runHeterogeneityAndSynthesisAudit(bodyWithoutRefs, lower, studyDesign)
    val powerAlerts = runPowerAndMultiplicityAudit(bodyWithoutRefs, lower, studyDesign, sampleSizes)
    val metricAlerts = runAlgebraicAndMetricBoundsAudit(bodyWithoutRefs, lower)

    val diagnosticTable = mutableListOf<QuantDiagnosticFinding>()

    statchecks.forEach { s ->
      diagnosticTable.add(
        QuantDiagnosticFinding(
          category = "Statcheck (${s.testType})",
          metricOrCheck = s.testType,
          reportedEvidence = s.rawClaim,
          status = s.status,
          diagnosticDetail = String.format(Locale.US, "Recomputed p=%.4f. %s", s.recomputedP, s.note),
          pageNumber = s.pageNumber,
          isAbsenceHeuristic = false
        )
      )
    }

    ciChecks.forEach { c ->
      if (c.status != QuantFindingStatus.PASS || diagnosticTable.size < 6) {
        val fullDetail = if (c.crossReferenceNote.isNotBlank()) {
          "${c.note} ${c.crossReferenceNote}"
        } else {
          c.note
        }
        diagnosticTable.add(
          QuantDiagnosticFinding(
            category = if (c.crossReferenceNote.isNotBlank()) "CI Cross-Ref" else "CI Geometry",
            metricOrCheck = c.metricLabel.ifBlank { "95% CI" },
            reportedEvidence = c.rawClaim,
            status = c.status,
            diagnosticDetail = fullDetail,
            pageNumber = c.pageNumber,
            isAbsenceHeuristic = false
          )
        )
      }
    }

    iqrChecks.forEach { m ->
      diagnosticTable.add(
        QuantDiagnosticFinding(
          category = "Quantile / Skew",
          metricOrCheck = "Median (IQR)",
          reportedEvidence = m.rawClaim,
          status = m.status,
          diagnosticDetail = String.format(Locale.US, "Bowley skew=%.2f. %s", m.bowleySkewness, m.note),
          pageNumber = m.pageNumber,
          isAbsenceHeuristic = false
        )
      )
    }

    heterogeneitySignals.forEach { sig ->
      val isAbsence = sig.contains("VERIFY", ignoreCase = true) ||
        sig.contains("OMISSION", ignoreCase = true) ||
        sig.contains("MISSING", ignoreCase = true) ||
        sig.contains("UNWEIGHTED", ignoreCase = true) ||
        sig.contains("PUBLICATION BIAS", ignoreCase = true)
      val status = when {
        sig.contains("FAIL", ignoreCase = true) || sig.contains("MISMATCH", ignoreCase = true) -> QuantFindingStatus.FAIL
        sig.contains("WARN", ignoreCase = true) || sig.contains("HIGH", ignoreCase = true) || sig.contains("MISSING", ignoreCase = true) -> QuantFindingStatus.WARN
        else -> QuantFindingStatus.INFO
      }
      diagnosticTable.add(
        QuantDiagnosticFinding(
          category = "Meta-Synthesis",
          metricOrCheck = "Heterogeneity / Synthesis",
          reportedEvidence = studyDesign.displayName,
          status = status,
          diagnosticDetail = sig.replace(Regex("""^\[[^\]]+\]\s*"""), ""),
          isAbsenceHeuristic = isAbsence
        )
      )
    }

    powerAlerts.forEach { alert ->
      val status = if (alert.contains("CRITICAL", ignoreCase = true)) QuantFindingStatus.FAIL else QuantFindingStatus.WARN
      diagnosticTable.add(
        QuantDiagnosticFinding(
          category = "Power & Multiplicity",
          metricOrCheck = "Error Control",
          reportedEvidence = "Study inference",
          status = status,
          diagnosticDetail = alert.replace(Regex("""^\[[^\]]+\]\s*"""), ""),
          isAbsenceHeuristic = alert.contains("without", ignoreCase = true)
        )
      )
    }

    metricAlerts.forEach { alert ->
      diagnosticTable.add(
        QuantDiagnosticFinding(
          category = "Metric Bounds",
          metricOrCheck = "Range / Plausibility",
          reportedEvidence = "Reported statistic",
          status = QuantFindingStatus.FAIL,
          diagnosticDetail = alert,
          isAbsenceHeuristic = false
        )
      )
    }

    val precisionMetrics = buildList {
      statchecks.forEach { add("${it.testType}: ${it.rawClaim}") }
      ciChecks.take(6).forEach { add(it.rawClaim) }
      iqrChecks.take(4).forEach { add(it.rawClaim) }
    }

    val effectiveConfidence = if (coverageReport.isCoverageUnknown) {
      DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN
    } else {
      extractionConfidence.coerceIn(0, 5)
    }
    val statisticalAudit = StatisticalAuditOrchestrator.audit(
      text = clean,
      parentArchetype = studyDesign,
      coverageReport = coverageReport,
      extractionConfidence = effectiveConfidence
    )
    val epistemicSafeguards = EpistemicSafeguardEngine.evaluate(
      text = clean,
      statAudit = statisticalAudit,
      coverageReport = coverageReport,
      extractionConfidence = effectiveConfidence
    )

    return QuantitativeForensicReport(
      statcheckResults = statchecks,
      confidenceIntervalChecks = ciChecks,
      medianIqrChecks = iqrChecks,
      heterogeneitySignals = heterogeneitySignals,
      powerAndMultiplicityAlerts = powerAlerts,
      algebraicAndMetricAlerts = metricAlerts,
      diagnosticTable = diagnosticTable.sortedBy {
        when {
          it.status == QuantFindingStatus.FAIL && !it.isAbsenceHeuristic -> 0
          it.status == QuantFindingStatus.FAIL -> 1
          it.status == QuantFindingStatus.WARN && !it.isAbsenceHeuristic -> 2
          it.status == QuantFindingStatus.WARN -> 3
          it.status == QuantFindingStatus.INFO -> 4
          else -> 5
        }
      },
      extractedPrecisionMetrics = precisionMetrics,
      statisticalAudit = statisticalAudit,
      epistemicSafeguards = epistemicSafeguards
    )
  }

  // ============================================================================
  // 1. STATCHECK: EXACT TEST-STATISTIC-TO-P-VALUE RECALCULATION
  // ============================================================================
  private fun runStatcheckAudit(text: String): List<StatcheckResult> {
    val results = mutableListOf<StatcheckResult>()

    tStatRegex.findAll(text).take(10).forEach { m ->
      val df = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val tVal = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[3]
      val repP = m.groupValues[4].toDoubleOrNull() ?: return@forEach
      if (df > 0.0 && repP in 0.0..1.0) {
        val calcP = studentTTwoTailedP(abs(tVal), df)
        val pg = resolvePageNumberAtOffset(text, m.range.first)
        results.add(evaluateStatcheck(m.value.trim(), "t($df)", op, repP, calcP, pg))
      }
    }

    zStatRegex.findAll(text).take(10).forEach { m ->
      val zVal = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[2]
      val repP = m.groupValues[3].toDoubleOrNull() ?: return@forEach
      if (repP in 0.0..1.0) {
        val calcP = normalTwoTailedP(abs(zVal))
        val pg = resolvePageNumberAtOffset(text, m.range.first)
        results.add(evaluateStatcheck(m.value.trim(), "z", op, repP, calcP, pg))
      }
    }

    chiSqRegex.findAll(text).take(10).forEach { m ->
      val df = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val chiVal = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[3]
      val repP = m.groupValues[4].toDoubleOrNull() ?: return@forEach
      if (df > 0.0 && chiVal >= 0.0 && repP in 0.0..1.0) {
        val calcP = chiSquareUpperTailP(chiVal, df)
        val pg = resolvePageNumberAtOffset(text, m.range.first)
        results.add(evaluateStatcheck(m.value.trim(), "χ²($df)", op, repP, calcP, pg))
      }
    }

    fStatRegex.findAll(text).take(10).forEach { m ->
      val df1 = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val df2 = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val fVal = m.groupValues[3].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[4]
      val repP = m.groupValues[5].toDoubleOrNull() ?: return@forEach
      if (df1 > 0.0 && df2 > 0.0 && fVal >= 0.0 && repP in 0.0..1.0) {
        val calcP = fDistUpperTailP(fVal, df1, df2)
        val pg = resolvePageNumberAtOffset(text, m.range.first)
        results.add(evaluateStatcheck(m.value.trim(), "F($df1,$df2)", op, repP, calcP, pg))
      }
    }

    rStatRegex.findAll(text).take(10).forEach { m ->
      val df = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val rVal = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[3]
      val repP = m.groupValues[4].toDoubleOrNull() ?: return@forEach
      if (df > 0.0 && abs(rVal) < 1.0 && repP in 0.0..1.0) {
        val tEquiv = abs(rVal) * sqrt(df / (1.0 - rVal * rVal))
        val calcP = studentTTwoTailedP(tEquiv, df)
        val pg = resolvePageNumberAtOffset(text, m.range.first)
        results.add(evaluateStatcheck(m.value.trim(), "r($df)", op, repP, calcP, pg))
      }
    }

    return results.distinctBy { it.rawClaim }
  }

  private fun evaluateStatcheck(
    rawClaim: String,
    testType: String,
    op: String,
    reportedP: Double,
    recomputedP: Double,
    pageNumber: Int
  ): StatcheckResult {
    val reportedSig = when (op) {
      "<", "≤" -> reportedP <= 0.05
      "=", "≈" -> reportedP < 0.05
      else -> false
    }
    val recomputedSig = recomputedP < 0.05

    val isConsistent = when (op) {
      "<", "≤" -> recomputedP <= reportedP + 0.005
      ">", "≥" -> recomputedP >= reportedP - 0.005
      else -> abs(recomputedP - reportedP) <= 0.015
    }

    return when {
      !isConsistent && (reportedSig != recomputedSig) -> StatcheckResult(
        rawClaim = rawClaim,
        testType = testType,
        reportedP = "$op $reportedP",
        recomputedP = recomputedP,
        status = QuantFindingStatus.FAIL,
        note = "GROSS DECISION DISCREPANCY: Reported significance at α=0.05 conflicts with exact two-tailed recalculation (verify one-tailed test, rounding, or transcription error).",
        pageNumber = pageNumber
      )
      !isConsistent -> StatcheckResult(
        rawClaim = rawClaim,
        testType = testType,
        reportedP = "$op $reportedP",
        recomputedP = recomputedP,
        status = QuantFindingStatus.WARN,
        note = "Numerical p-value discrepancy between reported statistic and exact two-tailed p (check df, continuity correction, or one-sided test).",
        pageNumber = pageNumber
      )
      else -> StatcheckResult(
        rawClaim = rawClaim,
        testType = testType,
        reportedP = "$op $reportedP",
        recomputedP = recomputedP,
        status = QuantFindingStatus.PASS,
        note = "Test statistic and reported p-value are mathematically consistent.",
        pageNumber = pageNumber
      )
    }
  }

  // ============================================================================
  // 2. CONFIDENCE INTERVAL GEOMETRY, NARRATIVE CI & CROSS-REFERENCE LINKING
  // ============================================================================
  private data class RawCiCandidate(
    val raw: String,
    val label: String,
    val point: Double,
    val lower: Double,
    val upper: Double,
    val offset: Int,
    val pageNumber: Int,
    val contextSnippet: String
  )

  private fun runConfidenceIntervalAudit(text: String): List<ConfidenceIntervalCheck> {
    val ratioMetrics = setOf("or", "aor", "rr", "arr", "hr", "ahr", "irr", "pr")
    val candidates = mutableListOf<RawCiCandidate>()

    fun collectMatch(m: MatchResult) {
      val label = m.groupValues[1].trim()
      val pt = m.groupValues[2].toDoubleOrNull() ?: return
      val low = m.groupValues[3].toDoubleOrNull() ?: return
      val high = m.groupValues[4].toDoubleOrNull() ?: return
      val start = (m.range.first - 55).coerceAtLeast(0)
      val end = (m.range.last + 45).coerceAtMost(text.length)
      val snippet = text.substring(start, end).replace(Regex("""\s+"""), " ").trim()
      candidates.add(
        RawCiCandidate(
          raw = m.value.trim(),
          label = label,
          point = pt,
          lower = low,
          upper = high,
          offset = m.range.first,
          pageNumber = resolvePageNumberAtOffset(text, m.range.first),
          contextSnippet = snippet
        )
      )
    }

    ciParenRegex.findAll(text).take(20).forEach(::collectMatch)
    ciNarrativeRegex.findAll(text).take(20).forEach(::collectMatch)

    val deduped = candidates.distinctBy { "${it.point}|${it.lower}|${it.upper}|${it.pageNumber}" }
    val checks = mutableListOf<ConfidenceIntervalCheck>()

    for (cand in deduped) {
      val pt = cand.point
      val low = cand.lower
      val high = cand.upper
      val label = cand.label

      if (low > high) {
        checks.add(
          ConfidenceIntervalCheck(
            rawClaim = cand.raw,
            metricLabel = label,
            pointEstimate = pt,
            lower = low,
            upper = high,
            impliedSe = null,
            status = QuantFindingStatus.FAIL,
            note = "Inverted confidence interval: lower bound ($low) exceeds upper bound ($high).",
            pageNumber = cand.pageNumber
          )
        )
        continue
      }

      if (pt < low - 1e-6 || pt > high + 1e-6) {
        val matchingAlternate = deduped.firstOrNull { other ->
          other !== cand &&
            abs(other.point - pt) <= 0.05 &&
            other.point >= other.lower - 1e-6 &&
            other.point <= other.upper + 1e-6
        }
        val xrefNote = if (matchingAlternate != null) {
          val altPage = if (matchingAlternate.pageNumber > 0) "Page ${matchingAlternate.pageNumber}" else "another section/figure"
          val mentionsFigOrTable = Regex("""\b(?:figure|fig\.|table)\s*\d*\b""", RegexOption.IGNORE_CASE)
            .find(matchingAlternate.contextSnippet)?.value
          val locDesc = listOfNotNull(mentionsFigOrTable, altPage).joinToString(" / ")
          "Cross-reference discrepancy: $locDesc reports a compatible interval for the same point estimate (${matchingAlternate.raw}). Verify intended confidence limits and correct the transcription error if confirmed."
        } else {
          ""
        }

        checks.add(
          ConfidenceIntervalCheck(
            rawClaim = cand.raw,
            metricLabel = label,
            pointEstimate = pt,
            lower = low,
            upper = high,
            impliedSe = null,
            status = QuantFindingStatus.FAIL,
            note = "Impossible CI geometry: reported point estimate ($pt) falls OUTSIDE its own interval [$low, $high].",
            pageNumber = cand.pageNumber,
            crossReferenceNote = xrefNote
          )
        )
        continue
      }

      val isRatio = label.lowercase(Locale.US) in ratioMetrics
      if (isRatio && low == 0.0 && pt > 0.0 && high >= pt) {
        val width = high - low
        val impliedSe = width / (2.0 * 1.95996)
        checks.add(
          ConfidenceIntervalCheck(
            rawClaim = cand.raw,
            metricLabel = label,
            pointEstimate = pt,
            lower = low,
            upper = high,
            impliedSe = impliedSe,
            status = QuantFindingStatus.WARN,
            note = "Imprecision / high variance: geometrically valid interval spans 0.00 to $high around $pt (not a confirmed arithmetic error). Report prediction intervals, influence diagnostics, leave-one-out analysis, and sensitivity analyses under prespecified outlier rules.",
            pageNumber = cand.pageNumber
          )
        )
        continue
      }

      if (isRatio && low > 0.0 && pt > 0.0 && high > 0.0) {
        val lnPt = ln(pt)
        val lnLow = ln(low)
        val lnHigh = ln(high)
        val halfLow = lnPt - lnLow
        val halfHigh = lnHigh - lnPt
        val seLn = (lnHigh - lnLow) / (2.0 * 1.95996)
        val maxHalf = maxOf(halfLow, halfHigh, 1e-6)
        val asymRatio = abs(halfHigh - halfLow) / maxHalf
        val isExtremeWide = (high / low >= 10.0) || (high / pt >= 2.0 && (high - low) >= 2.0)

        val status = if (isExtremeWide || (asymRatio > 0.28 && (lnHigh - lnLow) > 0.05)) {
          QuantFindingStatus.WARN
        } else {
          QuantFindingStatus.PASS
        }
        val note = when {
          isExtremeWide -> String.format(
            Locale.US,
            "Imprecision / high variance in %s 95%% CI [%.2f, %.2f] (not an arithmetic error). Report prediction intervals, influence diagnostics, leave-one-out analysis, and sensitivity analyses under prespecified outlier rules.",
            label.uppercase(Locale.US),
            low,
            high
          )
          status == QuantFindingStatus.WARN -> String.format(
            Locale.US,
            "Log-scale asymmetry (%.0f%%) in %s 95%% CI; verify exact profile-likelihood method or rounding.",
            asymRatio * 100.0,
            label.uppercase(Locale.US)
          )
          else -> String.format(Locale.US, "Log-symmetric ratio CI (implied SE[ln]=%.3f).", seLn)
        }
        checks.add(ConfidenceIntervalCheck(cand.raw, label, pt, low, high, seLn, status, note, cand.pageNumber))
      } else {
        val halfLow = pt - low
        val halfHigh = high - pt
        val width = high - low
        val impliedSe = width / (2.0 * 1.95996)
        val maxHalf = maxOf(halfLow, halfHigh, 1e-6)
        val asymRatio = abs(halfHigh - halfLow) / maxHalf
        val status = if (asymRatio > 0.30 && width > 0.05 && !label.equals("median", ignoreCase = true)) {
          QuantFindingStatus.WARN
        } else {
          QuantFindingStatus.PASS
        }
        val note = if (status == QuantFindingStatus.WARN) {
          String.format(
            Locale.US,
            "Asymmetric interval around point estimate (%.0f%% asymmetry); verify bootstrap/profile CI or transcription.",
            asymRatio * 100.0
          )
        } else {
          String.format(Locale.US, "Consistent interval geometry (implied SE=%.3f).", impliedSe)
        }
        checks.add(ConfidenceIntervalCheck(cand.raw, label, pt, low, high, impliedSe, status, note, cand.pageNumber))
      }
    }

    return checks.distinctBy { "${it.rawClaim}|${it.pageNumber}" }
  }

  // ============================================================================
  // 3. NON-PARAMETRIC MEDIAN / IQR & BOWLEY SKEWNESS AUDIT
  // ============================================================================
  private fun runMedianIqrAudit(text: String): List<MedianIqrCheck> {
    val checks = mutableListOf<MedianIqrCheck>()
    medianIqrRegex.findAll(text).take(10).forEach { m ->
      val med = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val q1 = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val q3 = m.groupValues[3].toDoubleOrNull() ?: return@forEach
      val raw = m.value.trim()
      val pg = resolvePageNumberAtOffset(text, m.range.first)

      if (q1 > q3 || med < q1 || med > q3) {
        checks.add(
          MedianIqrCheck(
            rawClaim = raw,
            median = med,
            q1 = q1,
            q3 = q3,
            bowleySkewness = 0.0,
            status = QuantFindingStatus.FAIL,
            note = "Quantile monotonicity violated: median ($med) must lie within [Q1=$q1, Q3=$q3] (or verify if Q3–Q1 difference vs bounds is reported).",
            pageNumber = pg
          )
        )
      } else {
        val iqr = (q3 - q1).coerceAtLeast(1e-6)
        val bowley = ((q3 - med) - (med - q1)) / iqr
        val absSkew = abs(bowley)
        val status = if (absSkew >= 0.35) QuantFindingStatus.WARN else QuantFindingStatus.PASS
        val note = if (absSkew >= 0.35) {
          val dir = if (bowley > 0) "right-skewed" else "left-skewed"
          "Substantially $dir empirical distribution; parametric normal approximations risk bias without transformation."
        } else {
          "Quantiles ordered and reasonably symmetric across IQR."
        }
        checks.add(MedianIqrCheck(raw, med, q1, q3, bowley, status, note, pg))
      }
    }
    return checks.distinctBy { it.rawClaim }
  }

  // ============================================================================
  // 4. META-ANALYSIS, HETEROGENEITY & SYNTHESIS DIAGNOSTICS (PRECISION-GATED)
  // ============================================================================
  private fun runHeterogeneityAndSynthesisAudit(
    text: String,
    lower: String,
    studyDesign: StudyDesignArchetype
  ): List<String> {
    val signals = mutableListOf<String>()

    val i2Values = iSquaredRegex.findAll(text)
      .mapNotNull { it.groupValues[1].toDoubleOrNull() }
      .toList()
    val kValues = kStudiesRegex.findAll(text)
      .mapNotNull { (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }
      .filter { it in 2..5000 }
      .toList()
    val maxK = kValues.maxOrNull()

    val hasRandomEffects = lower.contains("random-effect") ||
      lower.contains("random effect") ||
      lower.contains("dersimonian") ||
      lower.contains("reml") ||
      lower.contains("paule-mandel") ||
      lower.contains("inverse-variance") ||
      lower.contains("meta-regression") ||
      lower.contains("meta regression")

    val hasFixedEffectOnly = (lower.contains("fixed-effect") || lower.contains("fixed effect")) && !hasRandomEffects

    val mentionsHeterogeneityStatistic = i2Values.isNotEmpty() ||
      lower.contains("i²") ||
      lower.contains("i^2") ||
      lower.contains("i2-statistic") ||
      lower.contains("i2 statistic") ||
      Regex("""\bi2\b""").containsMatchIn(lower) ||
      lower.contains("tau²") ||
      lower.contains("tau-squared") ||
      lower.contains("τ²") ||
      lower.contains("cochran") ||
      (lower.contains("heterogeneity") && (lower.contains("figure") || lower.contains("table") || lower.contains("forest plot") || lower.contains("meta-regression")))

    if (i2Values.isNotEmpty()) {
      val maxI2 = i2Values.maxOrNull() ?: 0.0
      if (maxI2 > 100.0) {
        signals.add("[FAIL] Impossible heterogeneity statistic: reported I² = $maxI2% exceeds theoretical upper bound of 100%.")
      } else if (maxI2 >= 75.0) {
        if (hasFixedEffectOnly) {
          signals.add("[MODEL MISMATCH FAIL] Extreme heterogeneity (I² ≥ ${maxI2.roundToInt()}%) paired with a Fixed-Effect pooling model; fixed-effect CIs severely understate uncertainty under τ² > 0.")
        } else {
          signals.add("[HIGH HETEROGENEITY INFO] Very high between-study heterogeneity quantified (I² ≥ ${maxI2.roundToInt()}%); interpret pooled point estimates cautiously and evaluate 95% Prediction Intervals and subgroup/meta-regression moderators.")
        }
      }
      if (maxI2 >= 50.0 && !lower.contains("prediction interval")) {
        signals.add("[MISSING PREDICTION INTERVAL WARN] Substantial heterogeneity (I² ≥ 50%) is reported without 95% Prediction Intervals conveying the expected range of values in new clinical/policy settings.")
      }
    } else if (studyDesign == StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS) {
      // Only raise UNWEIGHTED SYNTHESIS when median/IQR is explicitly used AND no random-effects/weighted pooling is reported
      if ((lower.contains("median") && (lower.contains("iqr") || lower.contains("interquartile"))) && !hasRandomEffects) {
        signals.add("[UNWEIGHTED SYNTHESIS WARN] Quantitative synthesis appears to rely on unweighted medians/IQRs without precision-weighted (random-effects / inverse-variance) pooling; verify in Methods/Tables whether weighted pooling was performed.")
      }
      // Only raise HETEROGENEITY OMISSION when NO heterogeneity metric or figure/table heterogeneity reference appears anywhere
      if (!mentionsHeterogeneityStatistic) {
        signals.add("[HETEROGENEITY VERIFY WARN] No explicit between-study variance metric (I², τ², Cochran's Q) matched in extracted text; inspect figures, forest plots, and supplementary tables before concluding heterogeneity was omitted.")
      }
    }

    // Precision-gated Publication Bias check: only warn about Egger/funnel plots when the review pools
    // comparative effect sizes or intervention estimates with standard errors (avoid false alarms on
    // descriptive consensus/normative bed-need surveys where funnel asymmetry tests may be inappropriate).
    val isComparativeEffectPooling = lower.contains("odds ratio") ||
      lower.contains("risk ratio") ||
      lower.contains("hazard ratio") ||
      lower.contains("standardized mean difference") ||
      lower.contains("standardised mean difference") ||
      lower.contains("hedges") ||
      lower.contains("cohen's d") ||
      lower.contains("randomized controlled") ||
      lower.contains("randomised controlled") ||
      lower.contains("effect size") ||
      lower.contains("fixed-effect") ||
      Regex("""\b(?:OR|RR|HR|SMD|WMD)\s*[=:]""").containsMatchIn(text)

    val isNormativeConsensusSurvey = lower.contains("bed need") ||
      lower.contains("psychiatric beds") ||
      lower.contains("expert consensus") ||
      lower.contains("delphi") ||
      lower.contains("normative need")

    if (studyDesign == StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS && maxK != null) {
      val hasEggerOrFunnel = lower.contains("egger") || lower.contains("funnel") || lower.contains("begg") || lower.contains("trim-and-fill") || lower.contains("publication bias")
      if (maxK >= 10 && !hasEggerOrFunnel && isComparativeEffectPooling && !isNormativeConsensusSurvey) {
        signals.add("[PUBLICATION BIAS WARN] Comparative synthesis includes k = $maxK ≥ 10 studies without a detected small-study / publication-bias assessment (funnel plot, Egger's regression, or Peters test); verify in Supplementary Materials.")
      } else if (maxK < 10 && (lower.contains("egger") || lower.contains("begg"))) {
        signals.add("[UNDERPOWERED BIAS TEST WARN] Egger's/Begg's asymmetry test referenced with k = $maxK < 10 studies (Cochrane Handbook cautions statistical power is too low to distinguish chance from real asymmetry).")
      }
    }

    return signals.distinct()
  }

  // ============================================================================
  // 5. POWER, SAMPLE SIZE, CLUSTER COUNT & MULTIPLICITY AUDIT
  // ============================================================================
  private fun runPowerAndMultiplicityAudit(
    text: String,
    lower: String,
    studyDesign: StudyDesignArchetype,
    sampleSizes: List<Int>
  ): List<String> {
    val alerts = mutableListOf<String>()

    val allPValues = pValueLooseRegex.findAll(text)
      .mapNotNull { it.groupValues[2].toDoubleOrNull() }
      .filter { it in 0.0..1.0 }
      .toList()

    val borderlinePs = allPValues.count { it in 0.040..0.050 }
    if (borderlinePs >= 2) {
      alerts.add("[SCREENING SIGNAL • P-CURVE FRAGILITY WARN] $borderlinePs p-values cluster narrowly in [0.040, 0.050]; screening signal for evidential fragility (not proof of p-hacking — verify robustness & pre-registration).")
    }

    if (allPValues.size >= 4) {
      val hasAdjustment = lower.contains("bonferroni") || lower.contains("holm") ||
        lower.contains("benjamini") || lower.contains("fdr") || lower.contains("false discovery") ||
        lower.contains("fwer") || lower.contains("q-value") || lower.contains("sidak")
      if (!hasAdjustment) {
        val m = allPValues.size.coerceAtMost(25)
        val fwerPct = ((1.0 - (0.95).pow(m.toDouble())) * 100.0).roundToInt()
        alerts.add("[SCREENING SIGNAL • FWER INFLATION WARN] $m unadjusted significance tests detected without FWER/FDR correction (illustrative nominal family-wise Type I error rate ~$fwerPct% under independence; verify distinct testing families).")
      }
    }

    if (studyDesign == StudyDesignArchetype.ECONOMETRICS_CAUSAL || lower.contains("clustered")) {
      val clusterCounts = clusterCountRegex.findAll(text)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .filter { it in 2..41 }
        .toList()
      if (clusterCounts.isNotEmpty() && lower.contains("cluster") && !lower.contains("wild") && !lower.contains("bootstrap")) {
        val minClusters = clusterCounts.minOrNull() ?: 0
        alerts.add("[SMALL-CLUSTER SE WARN] Clustered inference with G = $minClusters < 42 clusters without wild cluster bootstrap or Bell-McCaffrey degrees-of-freedom correction risks over-rejection.")
      }
    }

    if (studyDesign == StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL || studyDesign == StudyDesignArchetype.GENERAL_EMPIRICAL) {
      val minN = sampleSizes.filter { it >= 5 }.minOrNull()
      if (minN != null && minN < 50 && !lower.contains("power") && !lower.contains("sample size calculation")) {
        alerts.add("[STATISTICAL POWER WARN] Small analytical sample (N = $minN) without a priori power or Minimum Detectable Effect (MDE) calculation; elevated risk of Type M (exaggeration) and Type S (sign) errors.")
      }
    }

    return alerts.distinct()
  }

  // ============================================================================
  // 6. ALGEBRAIC & METRIC BOUND PLAUSIBILITY AUDIT
  // ============================================================================
  private fun runAlgebraicAndMetricBoundsAudit(text: String, lower: String): List<String> {
    val alerts = mutableListOf<String>()

    pValueLooseRegex.findAll(text).forEach { m ->
      val op = m.groupValues[1]
      val pVal = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      if (pVal < 0.0 || pVal > 1.0) {
        alerts.add("Impossible probability value (${m.value.trim()}): p-value must lie in [0, 1].")
      } else if (op == "=" && pVal == 0.0 && m.groupValues[2].contains(".")) {
        alerts.add("Improper exact-zero p-value reporting (${m.value.trim()}): continuous test statistics yield p < 0.001 rather than exact p = 0.000.")
      }
    }

    rSquaredRegex.findAll(text).forEach { m ->
      val r2 = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      if (r2 > 1.0 || r2 < -1.0) {
        alerts.add("Out-of-bounds coefficient of determination (${m.value.trim()}): R² cannot exceed 1.0.")
      }
    }

    aucRegex.findAll(text).forEach { m ->
      val auc = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      if (auc > 1.0 || (auc > 0.0 && auc < 0.5 && !lower.contains("inverted"))) {
        alerts.add("Anomalous ROC AUC value (${m.value.trim()}): verify scale (0–1 vs 0–100%) or inverted class polarity.")
      }
    }

    return alerts.distinct()
  }

  // ============================================================================
  // EXACT MATHEMATICAL DISTRIBUTION FUNCTIONS (Pure Kotlin, Zero Dependencies)
  // ============================================================================

  /** Two-tailed p-value for standard Normal Z = |z|. */
  fun normalTwoTailedP(absZ: Double): Double {
    if (absZ.isNaN() || absZ < 0.0) return 1.0
    return erfc(absZ / sqrt(2.0)).coerceIn(0.0, 1.0)
  }

  /** Two-tailed p-value for Student's t distribution with df > 0. */
  fun studentTTwoTailedP(absT: Double, df: Double): Double {
    if (absT.isNaN() || df <= 0.0) return 1.0
    if (absT == 0.0) return 1.0
    val x = df / (df + absT * absT)
    return regularizedIncompleteBeta(df / 2.0, 0.5, x).coerceIn(0.0, 1.0)
  }

  /** Upper-tail p-value for Chi-Square distribution with df > 0. */
  fun chiSquareUpperTailP(chiSq: Double, df: Double): Double {
    if (chiSq.isNaN() || chiSq <= 0.0 || df <= 0.0) return 1.0
    return regularizedGammaQ(df / 2.0, chiSq / 2.0).coerceIn(0.0, 1.0)
  }

  /** Upper-tail p-value for Snedecor's F distribution with df1 > 0, df2 > 0. */
  fun fDistUpperTailP(fVal: Double, df1: Double, df2: Double): Double {
    if (fVal.isNaN() || fVal <= 0.0 || df1 <= 0.0 || df2 <= 0.0) return 1.0
    val x = df2 / (df2 + df1 * fVal)
    return regularizedIncompleteBeta(df2 / 2.0, df1 / 2.0, x).coerceIn(0.0, 1.0)
  }

  private fun erfc(x: Double): Double {
    val z = abs(x)
    val t = 1.0 / (1.0 + 0.5 * z)
    val ans = t * exp(
      -z * z - 1.26551223 + t * (
        1.00002368 + t * (
          0.37409196 + t * (
            0.09678418 + t * (
              -0.18628806 + t * (
                0.27886807 + t * (
                  -1.13520398 + t * (
                    1.48851587 + t * (
                      -0.82215223 + t * 0.17087277
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
    return if (x >= 0.0) ans else 2.0 - ans
  }

  private fun logGamma(z: Double): Double {
    val coeffs = doubleArrayOf(
      76.18009172947146,
      -86.50532032941677,
      24.01409824083091,
      -1.231739572450155,
      0.1208650973866179e-2,
      -0.5395239384953e-5
    )
    var x = z
    var y = z
    var tmp = x + 5.5
    tmp -= (x + 0.5) * ln(tmp)
    var ser = 1.000000000190015
    for (c in coeffs) {
      y += 1.0
      ser += c / y
    }
    return -tmp + ln(2.5066282746310005 * ser / x)
  }

  private fun regularizedIncompleteBeta(a: Double, b: Double, x: Double): Double {
    if (x <= 0.0) return 0.0
    if (x >= 1.0) return 1.0
    val bt = exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * ln(x) + b * ln(1.0 - x))
    return if (x < (a + 1.0) / (a + b + 2.0)) {
      bt * betaContinuedFraction(a, b, x) / a
    } else {
      1.0 - bt * betaContinuedFraction(b, a, 1.0 - x) / b
    }
  }

  private fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
    val fpMin = 1.0e-30
    val eps = 3.0e-7
    val qab = a + b
    val qap = a + 1.0
    val qam = a - 1.0
    var c = 1.0
    var d = 1.0 - qab * x / qap
    if (abs(d) < fpMin) d = fpMin
    d = 1.0 / d
    var h = d
    for (m in 1..200) {
      val m2 = 2 * m
      var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
      d = 1.0 + aa * d
      if (abs(d) < fpMin) d = fpMin
      c = 1.0 + aa / c
      if (abs(c) < fpMin) c = fpMin
      d = 1.0 / d
      h *= d * c

      aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
      d = 1.0 + aa * d
      if (abs(d) < fpMin) d = fpMin
      c = 1.0 + aa / c
      if (abs(c) < fpMin) c = fpMin
      d = 1.0 / d
      val del = d * c
      h *= del
      if (abs(del - 1.0) < eps) break
    }
    return h
  }

  private fun regularizedGammaQ(a: Double, x: Double): Double {
    if (x < 0.0 || a <= 0.0) return 1.0
    if (x == 0.0) return 1.0
    val gln = logGamma(a)
    return if (x < a + 1.0) {
      var ap = a
      var sum = 1.0 / a
      var del = sum
      for (n in 1..200) {
        ap += 1.0
        del *= x / ap
        sum += del
        if (abs(del) < abs(sum) * 3.0e-7) break
      }
      val p = sum * exp(-x + a * ln(x) - gln)
      (1.0 - p).coerceIn(0.0, 1.0)
    } else {
      val fpMin = 1.0e-30
      var b = x + 1.0 - a
      var c = 1.0 / fpMin
      var d = 1.0 / b
      var h = d
      for (i in 1..200) {
        val an = -i * (i - a)
        b += 2.0
        d = an * d + b
        if (abs(d) < fpMin) d = fpMin
        c = b + an / c
        if (abs(c) < fpMin) c = fpMin
        d = 1.0 / d
        val del = d * c
        h *= del
        if (abs(del - 1.0) < 3.0e-7) break
      }
      (exp(-x + a * ln(x) - gln) * h).coerceIn(0.0, 1.0)
    }
  }
}
