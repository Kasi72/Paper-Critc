package com.example.data.ai.epistemic

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.EvidenceDerivedScoreGate
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewMode
import com.example.util.DocumentCoverageReport
import java.util.Locale
import kotlin.math.abs

/**
 * Safeguard #16: Reviewer Calibration Mode.
 *
 * Evaluates the statistical & epistemic auditor like a diagnostic instrument against
 * expert-curated reference benchmarks (known flawed paper, known robust paper, borderline/overclaimed
 * study, ML leakage benchmark, and incomplete Stop-and-Ask manuscript), tracking True Positives,
 * False Positives, False Negatives, Precision, Recall, Severity Calibration, and Verdict Agreement.
 */
object ReviewerCalibrationBenchmark {

  data class CalibrationBenchmarkCase(
    val caseId: String,
    val title: String,
    val category: String, // KNOWN_FLAWED, KNOWN_ROBUST, BORDERLINE_OVERCLAIMED, KNOWN_ML_LEAKAGE, INCOMPLETE_STOP_AND_ASK
    val manuscriptText: String,
    val expectedFlagKeys: Set<String>, // Expected flaw detectors (e.g. "2X2_ARITHMETIC", "DENOMINATOR_DRIFT", "ML_LEAKAGE", "CLAIM_OVERREACH", "STOP_AND_ASK")
    val expectedFatalFlaw: Boolean,
    val expectedStopAndAsk: Boolean,
    val expertReferenceVerdict: String,
    val reviewMode: ReviewMode = ReviewMode.JOURNAL_REFEREE,
    val simulatedCoverage: DocumentCoverageReport? = null,
    val expertNotes: String,
    val expectedSubDesign: AuditSubDesign? = null
  )

  data class CalibrationCaseResult(
    val caseId: String,
    val title: String,
    val category: String,
    val detectedFlags: Set<String>,
    val expectedFlags: Set<String>,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val expectedFatalFlaw: Boolean,
    val detectedFatalFlaw: Boolean,
    val expectedStopAndAsk: Boolean,
    val detectedStopAndAsk: Boolean,
    val resolvedVerdict: String,
    val expertReferenceVerdict: String,
    val verdictMatched: Boolean,
    val severityCalibrated: Boolean,
    val designMatched: Boolean = true,
    val anchorAccuracy: Double = 1.0,
    val unsupportedClaimCount: Int = 0,
    val totalClaimCount: Int = 0,
    val stableUnderNoise: Boolean = true
  )

  data class CalibrationSummaryReport(
    val totalCases: Int,
    val totalTruePositives: Int,
    val totalFalsePositives: Int,
    val totalFalseNegatives: Int,
    val precision: Double,
    val recall: Double,
    val verdictAgreementRate: Double,
    val severityCalibrationRate: Double,
    val caseResults: List<CalibrationCaseResult>,
    val falsePositiveRate: Double = 0.0,
    val falseNegativeRate: Double = 0.0,
    val unsupportedClaimRate: Double = 0.0,
    val designClassificationAccuracy: Double = 1.0,
    val evidenceAnchorAccuracy: Double = 1.0,
    val scoreStabilityUnderNoiseRate: Double = 1.0
  ) {
    fun toSummaryText(): String = buildString {
      appendLine(
        String.format(
          Locale.US,
          "Calibration Instrument Score (%d Benchmarks): Precision=%.0f%% | Recall=%.0f%% | FPR=%.0f%% | FNR=%.0f%% | Design Acc=%.0f%% | Anchor Acc=%.0f%% | Noise Stability=%.0f%% | Severity Calibration=%.0f%% | Verdict Match=%.0f%%",
          totalCases,
          precision * 100.0,
          recall * 100.0,
          falsePositiveRate * 100.0,
          falseNegativeRate * 100.0,
          designClassificationAccuracy * 100.0,
          evidenceAnchorAccuracy * 100.0,
          scoreStabilityUnderNoiseRate * 100.0,
          severityCalibrationRate * 100.0,
          verdictAgreementRate * 100.0
        )
      )
      caseResults.forEach { c ->
        val badge = if (c.falsePositives == 0 && c.falseNegatives == 0 && c.severityCalibrated && c.verdictMatched) "PASS" else "DRIFT"
        appendLine("• [$badge] ${c.title} (${c.category}): Verdict=${c.resolvedVerdict} (Expert=${c.expertReferenceVerdict}) | TP=${c.truePositives} FP=${c.falsePositives} FN=${c.falseNegatives} | Design=${if (c.designMatched) "OK" else "MISS"} | NoiseStable=${if (c.stableUnderNoise) "OK" else "DRIFT"}")
      }
    }.trim()
  }

  val defaultBenchmarks: List<CalibrationBenchmarkCase> = listOf(
    CalibrationBenchmarkCase(
      caseId = "CAL-01",
      title = "Known Flawed RCT (Corrupted 2x2 + Denominator Drift)",
      category = "KNOWN_FLAWED",
      manuscriptText = """
        [PAGE 1]
        Abstract
        A double-blind randomized controlled trial (CONSORT) enrolled 420 patients (N = 420) comparing Drug A versus placebo over 12 months.
        [PAGE 3]
        Methods
        Primary outcome was 12-month mortality. Primary analysis used logistic regression. Table 1 baseline included N = 420.
        [PAGE 4]
        Results
        In Table 2 (N = 355), primary mortality occurred in 18/100 vs 35/100 patients (OR = 2.15, p = 0.008).
      """.trimIndent(),
      expectedFlagKeys = setOf("2X2_ARITHMETIC", "DENOMINATOR_DRIFT"),
      expectedFatalFlaw = true,
      expectedStopAndAsk = false,
      expertReferenceVerdict = "MAJOR_REVISION",
      expertNotes = "Must catch both corrupted OR=2.15 (true OR=0.41) and unexplained Table 1->Table 2 denominator drop (420->355) and block ACCEPT.",
      expectedSubDesign = AuditSubDesign.RCT
    ),
    CalibrationBenchmarkCase(
      caseId = "CAL-02",
      title = "Known Robust RCT (Exact 2x2 + ITT + Sensitivity + Protocol)",
      category = "KNOWN_ROBUST",
      manuscriptText = """
        [PAGE 1]
        Abstract
        A preregistered double-blind randomized controlled trial (NCT05123456, CONSORT) with centralized randomization and allocation concealment enrolled 240 patients (N = 240) comparing Drug B versus placebo over 6 months.
        [PAGE 3]
        Methods
        Primary outcome was 6-month hospitalization. Primary analysis used intention-to-treat (ITT) regression with multiple imputation and sensitivity analysis; minimal important difference (MID) was 5%.
        [PAGE 4]
        Results
        All 240 patients were analyzed in primary intention-to-treat analysis. Primary hospitalization occurred in 18/120 vs 36/120 patients (OR = 0.41, 95% CI 0.22-0.78; RR = 0.50; ARR = 15%; NNT = 7; p = 0.005).
      """.trimIndent(),
      expectedFlagKeys = emptySet(),
      expectedFatalFlaw = false,
      expectedStopAndAsk = false,
      expertReferenceVerdict = "ACCEPT",
      expertNotes = "Zero false positives allowed on a clean, preregistered, arithmetically exact RCT.",
      expectedSubDesign = AuditSubDesign.RCT
    ),
    CalibrationBenchmarkCase(
      caseId = "CAL-03",
      title = "Borderline Cross-Sectional Study (Causal Overclaim + Tiny Effect)",
      category = "BORDERLINE_OVERCLAIMED",
      manuscriptText = """
        [PAGE 1]
        Abstract
        A cross-sectional survey of 60,000 adults (N = 60,000) examined coffee intake and blood pressure.
        [PAGE 3]
        Methods
        Cross-sectional observational regression compared daily coffee drinkers vs non-drinkers on primary outcome hypertension prevalence.
        [PAGE 4]
        Results
        Hypertension was associated with OR = 1.02 (95% CI 1.01-1.03), p = 0.004.
        [PAGE 5]
        Conclusion
        This proves that coffee directly reduces hypertension risk and clinicians should recommend routine daily intake.
      """.trimIndent(),
      expectedFlagKeys = setOf("CLAIM_OVERREACH", "TINY_BUT_SIGNIFICANT"),
      expectedFatalFlaw = true,
      expectedStopAndAsk = false,
      expertReferenceVerdict = "MAJOR_REVISION",
      expertNotes = "Must catch cross-sectional causal/policy overclaiming AND tiny-but-significant large-N effect without firing false arithmetic errors.",
      expectedSubDesign = AuditSubDesign.OBSERVATIONAL_COHORT
    ),
    CalibrationBenchmarkCase(
      caseId = "CAL-04",
      title = "Known Flawed ML Paper (Preprocessing & Patient-Split Leakage)",
      category = "KNOWN_ML_LEAKAGE",
      manuscriptText = """
        [PAGE 1]
        Abstract
        A deep learning Transformer benchmark trained on 500 patients (N = 500) with multiple images per patient.
        [PAGE 3]
        Methods
        Primary outcome was lesion detection AUROC. Z-score normalization and SMOTE was applied on the entire dataset before train/test splitting, and images were randomly split 80/20 at the image-level split.
        [PAGE 4]
        Results
        The model achieved AUROC = 0.95 (95% CI 0.92-0.98) on the holdout set.
      """.trimIndent(),
      expectedFlagKeys = setOf("ML_LEAKAGE"),
      expectedFatalFlaw = true,
      expectedStopAndAsk = false,
      expertReferenceVerdict = "MAJOR_REVISION",
      expertNotes = "Must catch preprocessing and subject-level leakage as fatal model mismatches.",
      expectedSubDesign = AuditSubDesign.ML_BENCHMARK
    ),
    CalibrationBenchmarkCase(
      caseId = "CAL-05",
      title = "Incomplete / Truncated Manuscript (Stop-and-Ask Gate)",
      category = "INCOMPLETE_STOP_AND_ASK",
      manuscriptText = """
        [PAGE 1]
        Brief excerpt: Preliminary observations are shown in Supplementary Table S1 and see Table 2. Equation 1 [TRUNCATED] \uFFFD\uFFFD\uFFFD.
      """.trimIndent(),
      expectedFlagKeys = setOf("STOP_AND_ASK"),
      expectedFatalFlaw = false,
      expectedStopAndAsk = true,
      expertReferenceVerdict = "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
      simulatedCoverage = DocumentCoverageReport(
        totalPages = 12,
        extractedPages = 2,
        totalChars = 120,
        totalWords = 20,
        lowTextPages = listOf(4, 5, 6, 7),
        omittedPages = listOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
        extractionEngine = "PDFBox-Android",
        extractionConfidence = 1,
        warnings = listOf("Scanned image-only tables detected on pages 4-7")
      ),
      expertNotes = "Must trigger Stop-and-Ask and output INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT instead of guessing or fabricating flaws."
    )
  )

  fun runCalibration(
    cases: List<CalibrationBenchmarkCase> = defaultBenchmarks
  ): CalibrationSummaryReport {
    val results = cases.map { evaluateCase(it) }
    val tp = results.sumOf { it.truePositives }
    val fp = results.sumOf { it.falsePositives }
    val fn = results.sumOf { it.falseNegatives }
    val totalFlagSlots = results.size * 6 // 6 tracked diagnostic flag channels per case
    val tn = (totalFlagSlots - tp - fp - fn).coerceAtLeast(0)
    val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp).toDouble() else 1.0
    val recall = if (tp + fn > 0) tp.toDouble() / (tp + fn).toDouble() else 1.0
    val fpr = if (fp + tn > 0) fp.toDouble() / (fp + tn).toDouble() else 0.0
    val fnr = if (fn + tp > 0) fn.toDouble() / (fn + tp).toDouble() else 0.0
    val verdictRate = results.count { it.verdictMatched }.toDouble() / results.size.coerceAtLeast(1)
    val sevRate = results.count { it.severityCalibrated }.toDouble() / results.size.coerceAtLeast(1)
    val designAcc = results.count { it.designMatched }.toDouble() / results.size.coerceAtLeast(1)
    val anchorAcc = results.map { it.anchorAccuracy }.average().takeIf { !it.isNaN() } ?: 1.0
    val totalClaims = results.sumOf { it.totalClaimCount }
    val unsupportedClaims = results.sumOf { it.unsupportedClaimCount }
    val unsupportedRate = if (totalClaims > 0) unsupportedClaims.toDouble() / totalClaims.toDouble() else 0.0
    val noiseStability = results.count { it.stableUnderNoise }.toDouble() / results.size.coerceAtLeast(1)

    return CalibrationSummaryReport(
      totalCases = results.size,
      totalTruePositives = tp,
      totalFalsePositives = fp,
      totalFalseNegatives = fn,
      precision = precision,
      recall = recall,
      verdictAgreementRate = verdictRate,
      severityCalibrationRate = sevRate,
      caseResults = results,
      falsePositiveRate = fpr,
      falseNegativeRate = fnr,
      unsupportedClaimRate = unsupportedRate,
      designClassificationAccuracy = designAcc,
      evidenceAnchorAccuracy = anchorAcc,
      scoreStabilityUnderNoiseRate = noiseStability
    )
  }

  private fun evaluateCase(case: CalibrationBenchmarkCase): CalibrationCaseResult {
    val forensic = ManuscriptForensicEngine.analyzeManuscript(case.manuscriptText, isEmpiricalTrack = true)
    val statAudit = forensic.quantForensics.statisticalAudit!!
    val extConf = case.simulatedCoverage?.extractionConfidence ?: 5
    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = case.manuscriptText,
      statAudit = statAudit,
      coverageReport = case.simulatedCoverage,
      extractionConfidence = extConf
    )

    val detected = mutableSetOf<String>()
    if (statAudit.reconstructions.any { it.calculatorName.contains("2x2") && it.status == QuantFindingStatus.FAIL }) {
      detected.add("2X2_ARITHMETIC")
    }
    if (epistemic.denominatorLedger.discrepancies.any { it.discrepancyType in setOf("TABLE_DENOMINATOR_DRIFT", "IMPOSSIBLE_ATTRITION", "SUBGROUP_EXCEEDS_PARENT") }) {
      detected.add("DENOMINATOR_DRIFT")
    }
    if (statAudit.domainFindings.any { it.domainModule == "ML/Leakage" && it.status == QuantFindingStatus.FAIL }) {
      detected.add("ML_LEAKAGE")
    }
    if (epistemic.claimHierarchyAudit.isOverclaimed) {
      detected.add("CLAIM_OVERREACH")
    }
    if (epistemic.practicalSignificanceGate.tinyButSignificantFlag) {
      detected.add("TINY_BUT_SIGNIFICANT")
    }
    if (epistemic.stopAndAsk.shouldStopAndAsk) {
      detected.add("STOP_AND_ASK")
    }

    val tp = detected.intersect(case.expectedFlagKeys).size
    val fp = (detected - case.expectedFlagKeys).size
    val fn = (case.expectedFlagKeys - detected).size

    val detectedFatal = epistemic.fatalFlawCount > 0 ||
      statAudit.verifiedArithmeticErrorCount > 0 ||
      statAudit.verifiedModelMismatchCount > 0

    // Run full adjudication with high base scores (9/10) to test whether evidence-derived gating & Stop-and-Ask yield expert verdict!
    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = case.manuscriptText,
      forensicReport = forensic,
      methodologyScore = 9,
      significanceScore = 9,
      clarityScore = 9,
      originalityScore = 9,
      mathRigorScore = 9,
      reproducibilityScore = 9,
      rawVerdict = "ACCEPT",
      reviewMode = case.reviewMode,
      extractionConfidence = extConf,
      applyStatisticalScoreGate = true,
      epistemicReport = epistemic
    )

    val designMatched = case.expectedSubDesign == null || statAudit.subDesign == case.expectedSubDesign
    val activeClaims = statAudit.claims.filter { !it.isFromReferenceOrCitation }
    val anchoredCount = activeClaims.count { it.page > 0 && it.sourceQuote.isNotBlank() }
    val anchorAcc = if (activeClaims.isNotEmpty()) anchoredCount.toDouble() / activeClaims.size.toDouble() else 1.0

    // Score & verdict stability under paraphrasing + duplicated PDF-column noise
    val noisyVariant = perturbTextWithParaphraseAndPdfNoise(case.manuscriptText)
    val noisyForensic = ManuscriptForensicEngine.analyzeManuscript(noisyVariant, isEmpiricalTrack = true)
    val noisyStatAudit = noisyForensic.quantForensics.statisticalAudit!!
    val noisyEpistemic = EpistemicSafeguardEngine.evaluate(
      text = noisyVariant,
      statAudit = noisyStatAudit,
      coverageReport = case.simulatedCoverage,
      extractionConfidence = extConf
    )
    val noisyAdjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = noisyVariant,
      forensicReport = noisyForensic,
      methodologyScore = 9,
      significanceScore = 9,
      clarityScore = 9,
      originalityScore = 9,
      mathRigorScore = 9,
      reproducibilityScore = 9,
      rawVerdict = "ACCEPT",
      reviewMode = case.reviewMode,
      extractionConfidence = extConf,
      applyStatisticalScoreGate = true,
      epistemicReport = noisyEpistemic
    )
    val stableUnderNoise = noisyAdjudicated.resolvedVerdict == adjudicated.resolvedVerdict &&
      abs(noisyAdjudicated.mathRigorScore - adjudicated.mathRigorScore) <= 1 &&
      abs(noisyAdjudicated.methodologyScore - adjudicated.methodologyScore) <= 1

    return CalibrationCaseResult(
      caseId = case.caseId,
      title = case.title,
      category = case.category,
      detectedFlags = detected,
      expectedFlags = case.expectedFlagKeys,
      truePositives = tp,
      falsePositives = fp,
      falseNegatives = fn,
      expectedFatalFlaw = case.expectedFatalFlaw,
      detectedFatalFlaw = detectedFatal,
      expectedStopAndAsk = case.expectedStopAndAsk,
      detectedStopAndAsk = epistemic.stopAndAsk.shouldStopAndAsk,
      resolvedVerdict = adjudicated.resolvedVerdict,
      expertReferenceVerdict = case.expertReferenceVerdict,
      verdictMatched = adjudicated.resolvedVerdict == case.expertReferenceVerdict,
      severityCalibrated = detectedFatal == case.expectedFatalFlaw && epistemic.stopAndAsk.shouldStopAndAsk == case.expectedStopAndAsk,
      designMatched = designMatched,
      anchorAccuracy = anchorAcc,
      unsupportedClaimCount = statAudit.unsupportedClaimCount,
      totalClaimCount = activeClaims.size,
      stableUnderNoise = stableUnderNoise
    )
  }

  private fun perturbTextWithParaphraseAndPdfNoise(text: String): String {
    val paraphrased = text
      .replace("versus", "vs.")
      .replace("compared daily", "evaluated daily")
      .replace("occurred in", "was observed in")
    val lines = paraphrased.lines().toMutableList()
    val firstLongIdx = lines.indexOfFirst { it.trim().length >= 50 && !it.trim().startsWith("[PAGE") }
    if (firstLongIdx >= 0) {
      // Simulate a duplicated 2-column PDF extraction line
      lines.add(firstLongIdx + 1, lines[firstLongIdx])
    }
    return lines.joinToString("\n")
  }
}
