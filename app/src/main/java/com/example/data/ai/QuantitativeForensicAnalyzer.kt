package com.example.data.ai

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
  val diagnosticDetail: String
)

data class StatcheckResult(
  val rawClaim: String,
  val testType: String,
  val reportedP: String,
  val recomputedP: Double,
  val status: QuantFindingStatus,
  val note: String
)

data class ConfidenceIntervalCheck(
  val rawClaim: String,
  val metricLabel: String,
  val pointEstimate: Double,
  val lower: Double,
  val upper: Double,
  val impliedSe: Double?,
  val status: QuantFindingStatus,
  val note: String
)

data class MedianIqrCheck(
  val rawClaim: String,
  val median: Double,
  val q1: Double,
  val q3: Double,
  val bowleySkewness: Double,
  val status: QuantFindingStatus,
  val note: String
)

data class QuantitativeForensicReport(
  val statcheckResults: List<StatcheckResult>,
  val confidenceIntervalChecks: List<ConfidenceIntervalCheck>,
  val medianIqrChecks: List<MedianIqrCheck>,
  val heterogeneitySignals: List<String>,
  val powerAndMultiplicityAlerts: List<String>,
  val algebraicAndMetricAlerts: List<String>,
  val diagnosticTable: List<QuantDiagnosticFinding>,
  val extractedPrecisionMetrics: List<String>
) {
  val totalChecksExecuted: Int
    get() = statcheckResults.size + confidenceIntervalChecks.size + medianIqrChecks.size +
      heterogeneitySignals.size + powerAndMultiplicityAlerts.size + algebraicAndMetricAlerts.size

  val criticalDiscrepancyCount: Int
    get() = diagnosticTable.count { it.status == QuantFindingStatus.FAIL }

  val warningCount: Int
    get() = diagnosticTable.count { it.status == QuantFindingStatus.WARN }

  fun toPromptBlock(): String = buildString {
    appendLine("[DETERMINISTIC QUANTITATIVE & STATISTICAL FORENSICS ENGINE (Exact Math Verification)]")
    appendLine(
      "Summary: ${statcheckResults.size} statcheck recomputation(s), " +
        "${confidenceIntervalChecks.size} CI geometry check(s), " +
        "${medianIqrChecks.size} Median/IQR skewness check(s), " +
        "$criticalDiscrepancyCount FAIL flag(s), $warningCount WARN flag(s)."
    )

    if (statcheckResults.isNotEmpty()) {
      appendLine("  Statcheck Test-Statistic Recalculations:")
      statcheckResults.take(10).forEach { r ->
        appendLine(
          String.format(
            Locale.US,
            "  * [%s] %s -> Recomputed two-tailed p = %.4f (%s)",
            r.status.name,
            r.rawClaim,
            r.recomputedP,
            r.note
          )
        )
      }
    }

    if (confidenceIntervalChecks.isNotEmpty()) {
      appendLine("  Confidence Interval Geometry & Implied SE Checks:")
      confidenceIntervalChecks.take(10).forEach { c ->
        val seText = c.impliedSe?.let { String.format(Locale.US, "implied SE=%.4f", it) } ?: "SE n/a"
        appendLine("  * [${c.status.name}] ${c.rawClaim} ($seText) -> ${c.note}")
      }
    }

    if (medianIqrChecks.isNotEmpty()) {
      appendLine("  Non-Parametric Median / IQR & Bowley Skewness Diagnostics:")
      medianIqrChecks.take(8).forEach { m ->
        appendLine(
          String.format(
            Locale.US,
            "  * [%s] %s | Bowley skew = %.2f -> %s",
            m.status.name,
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
  }

  /**
   * Formats deterministic quantitative verification results into structured markdown table rows
   * and severity bullets that render cleanly in both the PDF and Compose UI.
   */
  fun toDeterministicAuditSummary(): String = buildString {
    if (diagnosticTable.isEmpty()) return@buildString
    appendLine("[PRE-FLIGHT DETERMINISTIC STATISTICAL FORENSICS (${diagnosticTable.size} Automated Checks)]")
    appendLine("| Check Domain | Reported Evidence | Status | Exact Mathematical Finding |")
    appendLine("|---|---|---|---|")
    diagnosticTable.take(10).forEach { row ->
      val safeCat = row.category.replace("|", "/")
      val safeEv = row.reportedEvidence.replace("|", "/").take(68)
      val safeDetail = row.diagnosticDetail.replace("|", "/").take(140)
      appendLine("| $safeCat | $safeEv | ${row.status.name} | $safeDetail |")
    }
  }.trim()
}

/**
 * Pure-Kotlin deterministic statistical & mathematical forensics engine.
 * Implements exact CDF/survival functions (Normal, Student-t, Chi-Square, F) for statcheck
 * test-statistic recalculation, confidence interval symmetry/log-symmetry geometry checks,
 * Bowley IQR skewness diagnostics, meta-analysis heterogeneity/Egger audits, FWER inflation
 * checks, and algebraic metric plausibility bounds.
 */
object QuantitativeForensicAnalyzer {

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

  private val ciRegex = Regex(
    """\b(OR|aOR|RR|aRR|HR|aHR|IRR|SMD|MD|WMD|beta|β|mean|median|rate|estimate)?\s*[=:]?\s*([-+]?\d+(?:\.\d+)?)\s*\(\s*(?:95%\s*)?(?:CI|CrI|UI)\s*[:=]?\s*([-+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*([-+]?\d+(?:\.\d+)?)\s*\)""",
    RegexOption.IGNORE_CASE
  )

  private val medianIqrRegex = Regex(
    """median\s*(?:of\s*|was\s*|[=:]\s*)?(\d+(?:\.\d+)?)\s*(?:per\s*[\d,]+)?\s*\(\s*(?:IQR|interquartile\s+range)\s*[:=]?\s*(\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*(\d+(?:\.\d+)?)\s*\)""",
    RegexOption.IGNORE_CASE
  )

  private val iSquaredRegex = Regex(
    """\bI(?:\^2|²|2)\s*[=≈]\s*(\d+(?:\.\d+)?)\s*%""",
    RegexOption.IGNORE_CASE
  )

  private val kStudiesRegex = Regex(
    """\b(?:k\s*=\s*(\d+)|(\d+)\s+(?:included\s+)?studies)\b""",
    RegexOption.IGNORE_CASE
  )

  private val rSquaredRegex = Regex("""\bR(?:\^2|²)\s*=\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
  private val aucRegex = Regex("""\b(?:AUC|AUROC)\s*=\s*([-+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
  private val pValueLooseRegex = Regex("""\bp\s*([=<>≤≥])\s*([-+]?\d*\.?\d+)""", RegexOption.IGNORE_CASE)
  private val clusterCountRegex = Regex("""(\d+)\s+(?:clusters|states|countries|clinics|hospitals|schools|firms)\b""", RegexOption.IGNORE_CASE)

  fun analyze(
    text: String,
    studyDesign: StudyDesignArchetype,
    sampleSizes: List<Int>
  ): QuantitativeForensicReport {
    val clean = text.trim()
    val lower = clean.lowercase(Locale.US)

    val statchecks = runStatcheckAudit(clean)
    val ciChecks = runConfidenceIntervalAudit(clean)
    val iqrChecks = runMedianIqrAudit(clean)
    val heterogeneitySignals = runHeterogeneityAndSynthesisAudit(clean, lower, studyDesign)
    val powerAlerts = runPowerAndMultiplicityAudit(clean, lower, studyDesign, sampleSizes)
    val metricAlerts = runAlgebraicAndMetricBoundsAudit(clean, lower)

    val diagnosticTable = mutableListOf<QuantDiagnosticFinding>()

    statchecks.forEach { s ->
      diagnosticTable.add(
        QuantDiagnosticFinding(
          category = "Statcheck (${s.testType})",
          metricOrCheck = s.testType,
          reportedEvidence = s.rawClaim,
          status = s.status,
          diagnosticDetail = String.format(Locale.US, "Recomputed p=%.4f. %s", s.recomputedP, s.note)
        )
      )
    }

    ciChecks.forEach { c ->
      if (c.status != QuantFindingStatus.PASS || diagnosticTable.size < 6) {
        diagnosticTable.add(
          QuantDiagnosticFinding(
            category = "CI Geometry",
            metricOrCheck = c.metricLabel.ifBlank { "95% CI" },
            reportedEvidence = c.rawClaim,
            status = c.status,
            diagnosticDetail = c.note
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
          diagnosticDetail = String.format(Locale.US, "Bowley skew=%.2f. %s", m.bowleySkewness, m.note)
        )
      )
    }

    heterogeneitySignals.forEach { sig ->
      val status = when {
        sig.contains("FAIL", ignoreCase = true) || sig.contains("MISMATCH", ignoreCase = true) -> QuantFindingStatus.FAIL
        sig.contains("WARN", ignoreCase = true) || sig.contains("HIGH", ignoreCase = true) || sig.contains("MISSING", ignoreCase = true) -> QuantFindingStatus.WARN
        else -> QuantFindingStatus.INFO
      }
      diagnosticTable.add(
        QuantDiagnosticFinding(
          category = "Meta-Synthesis",
          metricOrCheck = "Heterogeneity / Bias",
          reportedEvidence = studyDesign.displayName,
          status = status,
          diagnosticDetail = sig.replace(Regex("""^\[[^\]]+\]\s*"""), "")
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
          diagnosticDetail = alert.replace(Regex("""^\[[^\]]+\]\s*"""), "")
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
          diagnosticDetail = alert
        )
      )
    }

    val precisionMetrics = buildList {
      statchecks.forEach { add("${it.testType}: ${it.rawClaim}") }
      ciChecks.take(4).forEach { add(it.rawClaim) }
      iqrChecks.take(4).forEach { add(it.rawClaim) }
    }

    return QuantitativeForensicReport(
      statcheckResults = statchecks,
      confidenceIntervalChecks = ciChecks,
      medianIqrChecks = iqrChecks,
      heterogeneitySignals = heterogeneitySignals,
      powerAndMultiplicityAlerts = powerAlerts,
      algebraicAndMetricAlerts = metricAlerts,
      diagnosticTable = diagnosticTable.sortedBy {
        when (it.status) {
          QuantFindingStatus.FAIL -> 0
          QuantFindingStatus.WARN -> 1
          QuantFindingStatus.INFO -> 2
          QuantFindingStatus.PASS -> 3
        }
      },
      extractedPrecisionMetrics = precisionMetrics
    )
  }

  // ============================================================================
  // 1. STATCHECK: EXACT TEST-STATISTIC-TO-P-VALUE RECALCULATION
  // ============================================================================
  private fun runStatcheckAudit(text: String): List<StatcheckResult> {
    val results = mutableListOf<StatcheckResult>()

    // Student's t(df) = val, p ...
    tStatRegex.findAll(text).take(8).forEach { m ->
      val df = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val tVal = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[3]
      val repP = m.groupValues[4].toDoubleOrNull() ?: return@forEach
      if (df > 0.0 && repP in 0.0..1.0) {
        val calcP = studentTTwoTailedP(abs(tVal), df)
        results.add(evaluateStatcheck(m.value.trim(), "t($df)", op, repP, calcP))
      }
    }

    // Standard Normal z = val, p ...
    zStatRegex.findAll(text).take(8).forEach { m ->
      val zVal = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[2]
      val repP = m.groupValues[3].toDoubleOrNull() ?: return@forEach
      if (repP in 0.0..1.0) {
        val calcP = normalTwoTailedP(abs(zVal))
        results.add(evaluateStatcheck(m.value.trim(), "z", op, repP, calcP))
      }
    }

    // Chi-Square chi²(df) = val, p ...
    chiSqRegex.findAll(text).take(8).forEach { m ->
      val df = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val chiVal = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[3]
      val repP = m.groupValues[4].toDoubleOrNull() ?: return@forEach
      if (df > 0.0 && chiVal >= 0.0 && repP in 0.0..1.0) {
        val calcP = chiSquareUpperTailP(chiVal, df)
        results.add(evaluateStatcheck(m.value.trim(), "χ²($df)", op, repP, calcP))
      }
    }

    // F(df1, df2) = val, p ...
    fStatRegex.findAll(text).take(8).forEach { m ->
      val df1 = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val df2 = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val fVal = m.groupValues[3].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[4]
      val repP = m.groupValues[5].toDoubleOrNull() ?: return@forEach
      if (df1 > 0.0 && df2 > 0.0 && fVal >= 0.0 && repP in 0.0..1.0) {
        val calcP = fDistUpperTailP(fVal, df1, df2)
        results.add(evaluateStatcheck(m.value.trim(), "F($df1,$df2)", op, repP, calcP))
      }
    }

    // Pearson r(df) = val, p ...
    rStatRegex.findAll(text).take(8).forEach { m ->
      val df = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val rVal = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val op = m.groupValues[3]
      val repP = m.groupValues[4].toDoubleOrNull() ?: return@forEach
      if (df > 0.0 && abs(rVal) < 1.0 && repP in 0.0..1.0) {
        val tEquiv = abs(rVal) * sqrt(df / (1.0 - rVal * rVal))
        val calcP = studentTTwoTailedP(tEquiv, df)
        results.add(evaluateStatcheck(m.value.trim(), "r($df)", op, repP, calcP))
      }
    }

    return results.distinctBy { it.rawClaim }
  }

  private fun evaluateStatcheck(
    rawClaim: String,
    testType: String,
    op: String,
    reportedP: Double,
    recomputedP: Double
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
        note = "GROSS DECISION DISCREPANCY: Reported significance at α=0.05 conflicts with exact two-tailed recalculation (verify one-tailed test, rounding, or transcription error)."
      )
      !isConsistent -> StatcheckResult(
        rawClaim = rawClaim,
        testType = testType,
        reportedP = "$op $reportedP",
        recomputedP = recomputedP,
        status = QuantFindingStatus.WARN,
        note = "Numerical p-value discrepancy between reported statistic and exact two-tailed p (check df, continuity correction, or one-sided test)."
      )
      else -> StatcheckResult(
        rawClaim = rawClaim,
        testType = testType,
        reportedP = "$op $reportedP",
        recomputedP = recomputedP,
        status = QuantFindingStatus.PASS,
        note = "Test statistic and reported p-value are mathematically consistent."
      )
    }
  }

  // ============================================================================
  // 2. CONFIDENCE INTERVAL GEOMETRY, SYMMETRY & IMPLIED SE AUDIT
  // ============================================================================
  private fun runConfidenceIntervalAudit(text: String): List<ConfidenceIntervalCheck> {
    val checks = mutableListOf<ConfidenceIntervalCheck>()
    val ratioMetrics = setOf("or", "aor", "rr", "arr", "hr", "ahr", "irr")

    ciRegex.findAll(text).take(10).forEach { m ->
      val label = m.groupValues[1].trim()
      val pt = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val low = m.groupValues[3].toDoubleOrNull() ?: return@forEach
      val high = m.groupValues[4].toDoubleOrNull() ?: return@forEach
      val raw = m.value.trim()

      if (low > high) {
        checks.add(
          ConfidenceIntervalCheck(
            rawClaim = raw,
            metricLabel = label,
            pointEstimate = pt,
            lower = low,
            upper = high,
            impliedSe = null,
            status = QuantFindingStatus.FAIL,
            note = "Inverted confidence interval: lower bound ($low) exceeds upper bound ($high)."
          )
        )
        return@forEach
      }

      if (pt < low - 1e-6 || pt > high + 1e-6) {
        checks.add(
          ConfidenceIntervalCheck(
            rawClaim = raw,
            metricLabel = label,
            pointEstimate = pt,
            lower = low,
            upper = high,
            impliedSe = null,
            status = QuantFindingStatus.FAIL,
            note = "Impossible CI geometry: point estimate ($pt) falls OUTSIDE its own reported interval [$low, $high]."
          )
        )
        return@forEach
      }

      val isRatio = label.lowercase(Locale.US) in ratioMetrics
      if (isRatio && low > 0.0 && pt > 0.0 && high > 0.0) {
        val lnPt = ln(pt)
        val lnLow = ln(low)
        val lnHigh = ln(high)
        val halfLow = lnPt - lnLow
        val halfHigh = lnHigh - lnPt
        val seLn = (lnHigh - lnLow) / (2.0 * 1.95996)
        val maxHalf = maxOf(halfLow, halfHigh, 1e-6)
        val asymRatio = abs(halfHigh - halfLow) / maxHalf

        val status = if (asymRatio > 0.28 && (lnHigh - lnLow) > 0.05) {
          QuantFindingStatus.WARN
        } else {
          QuantFindingStatus.PASS
        }
        val note = if (status == QuantFindingStatus.WARN) {
          String.format(
            Locale.US,
            "Log-scale asymmetry (%.0f%%) in %s 95%% CI; check exact profile-likelihood method or rounding/typo.",
            asymRatio * 100.0,
            label.uppercase(Locale.US)
          )
        } else {
          String.format(Locale.US, "Log-symmetric ratio CI (implied SE[ln]=%.3f).", seLn)
        }
        checks.add(ConfidenceIntervalCheck(raw, label, pt, low, high, seLn, status, note))
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
        checks.add(ConfidenceIntervalCheck(raw, label, pt, low, high, impliedSe, status, note))
      }
    }

    return checks.distinctBy { it.rawClaim }
  }

  // ============================================================================
  // 3. NON-PARAMETRIC MEDIAN / IQR & BOWLEY SKEWNESS AUDIT
  // ============================================================================
  private fun runMedianIqrAudit(text: String): List<MedianIqrCheck> {
    val checks = mutableListOf<MedianIqrCheck>()
    medianIqrRegex.findAll(text).take(8).forEach { m ->
      val med = m.groupValues[1].toDoubleOrNull() ?: return@forEach
      val q1 = m.groupValues[2].toDoubleOrNull() ?: return@forEach
      val q3 = m.groupValues[3].toDoubleOrNull() ?: return@forEach
      val raw = m.value.trim()

      if (q1 > q3 || med < q1 || med > q3) {
        checks.add(
          MedianIqrCheck(
            rawClaim = raw,
            median = med,
            q1 = q1,
            q3 = q3,
            bowleySkewness = 0.0,
            status = QuantFindingStatus.FAIL,
            note = "Quantile monotonicity violated: median ($med) must lie within [Q1=$q1, Q3=$q3] (or verify if Q3–Q1 difference vs bounds is reported)."
          )
        )
      } else {
        val iqr = (q3 - q1).coerceAtLeast(1e-6)
        val bowley = ((q3 - med) - (med - q1)) / iqr
        val absSkew = abs(bowley)
        val status = if (absSkew >= 0.35) QuantFindingStatus.WARN else QuantFindingStatus.PASS
        val note = if (absSkew >= 0.35) {
          val dir = if (bowley > 0) "right-skewed" else "left-skewed"
          "Substantially $dir empirical distribution; parametric normal approximations (mean/SD or Wan-Luo conversion) risk severe bias without log-transformation."
        } else {
          "Quantiles ordered and reasonably symmetric across IQR."
        }
        checks.add(MedianIqrCheck(raw, med, q1, q3, bowley, status, note))
      }
    }
    return checks.distinctBy { it.rawClaim }
  }

  // ============================================================================
  // 4. META-ANALYSIS, HETEROGENEITY & SYNTHESIS DIAGNOSTICS
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

    if (i2Values.isNotEmpty()) {
      val maxI2 = i2Values.maxOrNull() ?: 0.0
      if (maxI2 > 100.0) {
        signals.add("[FAIL] Impossible heterogeneity statistic: reported I² = $maxI2% exceeds theoretical upper bound of 100%.")
      } else if (maxI2 >= 75.0) {
        val usesFixed = lower.contains("fixed-effect") || lower.contains("fixed effect")
        val usesRandom = lower.contains("random-effect") || lower.contains("random effect") || lower.contains("dersimonian") || lower.contains("reml")
        if (usesFixed && !usesRandom) {
          signals.add("[MODEL MISMATCH FAIL] Extreme heterogeneity (I² = ${maxI2.roundToInt()}%) paired with a Fixed-Effect pooling model; fixed-effect CIs severely understate uncertainty under τ² > 0.")
        } else {
          signals.add("[HIGH HETEROGENEITY WARN] Substantial cross-study heterogeneity detected (I² = ${maxI2.roundToInt()}%); verify whether 95% Prediction Intervals, τ² estimator (REML/HKSJ), and subgroup meta-regression explain variance.")
        }
      }
      if (maxI2 >= 50.0 && !lower.contains("prediction interval")) {
        signals.add("[MISSING PREDICTION INTERVAL WARN] Moderate-to-high heterogeneity (I² ≥ 50%) reported without 95% Prediction Intervals to convey true range of expected effects in new settings.")
      }
    } else if (studyDesign == StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS) {
      if ((lower.contains("median") && lower.contains("iqr")) &&
        !lower.contains("inverse-variance") && !lower.contains("random-effect") && !lower.contains("dersimonian") && !lower.contains("reml")
      ) {
        signals.add("[UNWEIGHTED SYNTHESIS WARN] Quantitative synthesis relies on unweighted medians/IQRs rather than precision-weighted (inverse-variance / REML) or quality-weighted meta-analytic estimators; small, low-rigor estimates receive equal weight to large national studies.")
      }
      if (!lower.contains("tau") && !lower.contains("τ") && !lower.contains("i²") && !lower.contains("i2") && !lower.contains("cochran")) {
        signals.add("[HETEROGENEITY OMISSION WARN] No formal between-study variance quantification (I², τ², Cochran's Q) detected in this systematic review / meta-analysis.")
      }
    }

    if (studyDesign == StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS && maxK != null) {
      val hasEggerOrFunnel = lower.contains("egger") || lower.contains("funnel") || lower.contains("begg") || lower.contains("trim-and-fill") || lower.contains("publication bias")
      if (maxK >= 10 && !hasEggerOrFunnel) {
        signals.add("[PUBLICATION BIAS WARN] Sufficient study count detected (k = $maxK ≥ 10), yet no small-study / publication bias diagnostic (funnel plot, Egger's regression, Peters test, or trim-and-fill) was detected.")
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
      alerts.add("[P-CURVE FRAGILITY WARN] $borderlinePs p-values cluster narrowly in [0.040, 0.050]; high evidential fragility to minor covariate changes, outlier removal, or multiplicity control.")
    }

    if (allPValues.size >= 4) {
      val hasAdjustment = lower.contains("bonferroni") || lower.contains("holm") ||
        lower.contains("benjamini") || lower.contains("fdr") || lower.contains("false discovery") ||
        lower.contains("fwer") || lower.contains("q-value") || lower.contains("sidak")
      if (!hasAdjustment) {
        val m = allPValues.size.coerceAtMost(25)
        val fwerPct = ((1.0 - (0.95).pow(m.toDouble())) * 100.0).roundToInt()
        alerts.add("[FWER INFLATION WARN] $m unadjusted significance tests detected without FWER/FDR correction (nominal family-wise Type I error rate inflates to ~$fwerPct% under independence).")
      }
    }

    if (studyDesign == StudyDesignArchetype.ECONOMETRICS_CAUSAL || lower.contains("clustered")) {
      val clusterCounts = clusterCountRegex.findAll(text)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .filter { it in 2..41 }
        .toList()
      if (clusterCounts.isNotEmpty() && lower.contains("cluster") && !lower.contains("wild") && !lower.contains("bootstrap")) {
        val minClusters = clusterCounts.minOrNull() ?: 0
        alerts.add("[SMALL-CLUSTER SE WARN] Clustered inference with G = $minClusters < 42 clusters without wild cluster bootstrap or Bell-McCaffrey degrees-of-freedom correction risks severe over-rejection.")
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

  /** Complementary error function erfc(x) via Abramowitz & Stegun 7.1.26 (max error < 1.5e-7). */
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

  /** Lanczos approximation for ln(Gamma(z)). */
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

  /** Regularized incomplete beta function I_x(a, b) via Lentz's continued fraction. */
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

  /** Upper regularized incomplete gamma function Q(a, x) = 1 - P(a, x). */
  private fun regularizedGammaQ(a: Double, x: Double): Double {
    if (x < 0.0 || a <= 0.0) return 1.0
    if (x == 0.0) return 1.0
    val gln = logGamma(a)
    return if (x < a + 1.0) {
      // Series representation for P(a, x)
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
      // Continued fraction for Q(a, x)
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
