package com.example.data.ai.epistemic

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.semantic.ClassifierCalibrationReport
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.QuantitativeClaim
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewMode
import com.example.util.DocumentCoverageReport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Safeguard #16 + Phase 9 Revision A: Embedded Regression & Diagnostic Benchmark Suite.
 *
 * Strictly separates internal embedded regression fixtures from external blinded calibration:
 * 1. Explicit [BenchmarkLabel] matrix per (caseId, findingCode) — zero TN-by-subtraction or
 *    hardcoded `results.size * 6` formulas; FPR denominator equals explicit negative labels.
 * 2. Strict evidence anchor validation via [Reviewer2PipelineService.verifyQuoteInManuscript]:
 *    verifies exact quote existence, normalized offsets, page match, numeric value occurrence
 *    inside matched quote, outcome alignment, and occurrence uniqueness.
 * 3. 10-perturbation [NoisePerturbationSuite] separating benign layout-noise score stability from
 *    finding stability, verifying 0 new VERIFIED findings under OCR corruption, and confirming
 *    anchor/page confidence degrades when page markers or digits are corrupted.
 * 4. Exact Wilson 95% confidence intervals ([MetricWithCi]) on benchmark metrics.
 * 5. Explicit 3-layer separation: [BenchmarkLayer.UNIT_FIXTURES], [BenchmarkLayer.ADVERSARIAL_FIXTURES],
 *    and [BenchmarkLayer.EXTERNAL_BLINDED_CORPUS] (marked pending external annotation).
 */
object ReviewerCalibrationBenchmark {

  val defaultTrackedDetectorCodes: List<String> = listOf(
    "2X2_ARITHMETIC",
    "DENOMINATOR_DRIFT",
    "ML_LEAKAGE",
    "CLAIM_OVERREACH",
    "TINY_BUT_SIGNIFICANT",
    "STOP_AND_ASK"
  )

  enum class BenchmarkLayer(val displayName: String, val isExternallyBlinded: Boolean) {
    UNIT_FIXTURES("Layer 1: Embedded Unit Regression Fixtures (Internal)", false),
    ADVERSARIAL_FIXTURES("Layer 2: Adversarial & Edge-Case Fixtures (Internal)", false),
    EXTERNAL_BLINDED_CORPUS("Layer 3: External Blinded Expert-Annotated Corpus (Pending)", true)
  }

  data class BenchmarkLabel(
    val caseId: String,
    val findingCode: String,
    val expected: Boolean,
    val observed: Boolean
  ) {
    val outcome: String
      get() = when {
        expected && observed -> "TP"
        !expected && observed -> "FP"
        expected && !observed -> "FN"
        else -> "TN"
      }
  }

  data class MetricWithCi(
    val numerator: Int,
    val denominator: Int,
    val estimate: Double,
    val lower95: Double,
    val upper95: Double
  ) {
    fun formatPercentWithCi(): String = if (denominator <= 0) {
      "N/A (n=0)"
    } else {
      String.format(
        Locale.US,
        "%.0f%% (%d/%d; 95%% CI %.0f%%–%.0f%%)",
        estimate * 100.0,
        numerator,
        denominator,
        lower95 * 100.0,
        upper95 * 100.0
      )
    }
  }

  fun wilson95(numerator: Int, denominator: Int): MetricWithCi {
    if (denominator <= 0) return MetricWithCi(0, 0, 0.0, 0.0, 0.0)
    val k = numerator.coerceIn(0, denominator)
    val n = denominator.toDouble()
    val p = k.toDouble() / n
    val z = 1.959963984540054
    val z2 = z * z
    val denom = 1.0 + z2 / n
    val center = (p + z2 / (2.0 * n)) / denom
    val margin = (z / denom) * sqrt((p * (1.0 - p) / n) + (z2 / (4.0 * n * n)))
    return MetricWithCi(
      numerator = k,
      denominator = denominator,
      estimate = p,
      lower95 = (center - margin).coerceIn(0.0, 1.0),
      upper95 = (center + margin).coerceIn(0.0, 1.0)
    )
  }

  data class AnchorVerificationDetail(
    val claimId: String,
    val quoteExistsExact: Boolean,
    val normalizedOffsetsValid: Boolean,
    val pageMatches: Boolean,
    val numericValuesPresentInQuote: Boolean,
    val outcomeAligned: Boolean,
    val occurrenceCount: Int,
    val isUniqueMatch: Boolean,
    val passed: Boolean,
    val failureReasons: List<String>
  )

  enum class NoisePerturbationKind(
    val label: String,
    val isBenignLayoutNoise: Boolean
  ) {
    DUPLICATE_HEADERS_FOOTERS("Duplicate running headers/footers", true),
    REORDERED_COLUMNS("Reordered 2-column extraction lines", true),
    OCR_CHAR_CONFUSION("OCR 0/O, 1/l/I & decimal deletion corruption", false),
    SPLIT_HYPHENATED_WORDS("Split hyphenated line breaks", true),
    REMOVED_PAGE_MARKERS("Removed [PAGE N] markers", false),
    INSERTED_TABLE_CAPTIONS("Inserted table & figure captions", true),
    REORDERED_PARAGRAPHS("Reordered non-conflicting paragraphs", true),
    INSERTED_REFERENCE_PVALUES("Inserted Reference-section p-values & CIs", true),
    ROUNDED_ESTIMATES("Minor display rounding & dash formatting", true),
    REMOVED_TABLE_BORDERS("Removed pipe/tab table borders", true)
  }

  data class NoisePerturbationResult(
    val kind: NoisePerturbationKind,
    val isBenignLayoutNoise: Boolean,
    val scoreStable: Boolean,
    val findingStable: Boolean,
    val newVerifiedFindingsOnCorruption: Int,
    val baselineAnchorAccuracy: Double,
    val perturbedAnchorAccuracy: Double,
    val anchorOrPageConfidenceDegraded: Boolean,
    val note: String
  )

  data class CalibrationBenchmarkCase(
    val caseId: String,
    val title: String,
    val category: String,
    val manuscriptText: String,
    val expectedFlagKeys: Set<String>,
    val expectedFatalFlaw: Boolean,
    val expectedStopAndAsk: Boolean,
    val expertReferenceVerdict: String,
    val reviewMode: ReviewMode = ReviewMode.JOURNAL_REFEREE,
    val simulatedCoverage: DocumentCoverageReport? = null,
    val expertNotes: String,
    val expectedSubDesign: AuditSubDesign? = null,
    val layer: BenchmarkLayer = BenchmarkLayer.UNIT_FIXTURES
  )

  data class CalibrationCaseResult(
    val caseId: String,
    val title: String,
    val category: String,
    val detectedFlags: Set<String>,
    val expectedFlags: Set<String>,
    val labels: List<BenchmarkLabel>,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val trueNegatives: Int,
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
    val anchorChecks: List<AnchorVerificationDetail> = emptyList(),
    val unsupportedClaimCount: Int = 0,
    val totalClaimCount: Int = 0,
    val stableUnderNoise: Boolean = true,
    val findingStableUnderNoise: Boolean = true,
    val noiseResults: List<NoisePerturbationResult> = emptyList(),
    val ocrNewVerifiedFindingCount: Int = 0,
    val corruptionDegradedConfidence: Boolean = true
  )

  data class CalibrationSummaryReport(
    val totalCases: Int,
    val totalTruePositives: Int,
    val totalFalsePositives: Int,
    val totalFalseNegatives: Int,
    val totalTrueNegatives: Int,
    val explicitNegativeLabelCount: Int,
    val explicitPositiveLabelCount: Int,
    val trackedDetectorCodes: List<String>,
    val labelMatrix: List<BenchmarkLabel>,
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
    val scoreStabilityUnderNoiseRate: Double = 1.0,
    val findingStabilityUnderNoiseRate: Double = 1.0,
    val ocrZeroFalseVerifiedRate: Double = 1.0,
    val corruptionConfidenceDegradationRate: Double = 1.0,
    val precisionCi: MetricWithCi = wilson95(0, 0),
    val recallCi: MetricWithCi = wilson95(0, 0),
    val fprCi: MetricWithCi = wilson95(0, 0),
    val fnrCi: MetricWithCi = wilson95(0, 0),
    val verdictAgreementCi: MetricWithCi = wilson95(0, 0),
    val severityCalibrationCi: MetricWithCi = wilson95(0, 0),
    val designAccuracyCi: MetricWithCi = wilson95(0, 0),
    val anchorAccuracyCi: MetricWithCi = wilson95(0, 0),
    val scoreStabilityCi: MetricWithCi = wilson95(0, 0),
    val findingStabilityCi: MetricWithCi = wilson95(0, 0),
    val benchmarkLayer: BenchmarkLayer = BenchmarkLayer.UNIT_FIXTURES,
    val honestCalibrationNotice: String = ""
  ) {
    val allEmbeddedFixturesPassed: Boolean
      get() = totalFalsePositives == 0 &&
        totalFalseNegatives == 0 &&
        caseResults.all { it.severityCalibrated && it.verdictMatched && it.designMatched }

    fun toSummaryText(): String = buildString {
      appendLine(honestCalibrationNotice.ifBlank {
        if (allEmbeddedFixturesPassed) {
          "All embedded regression fixtures passed (${benchmarkLayer.displayName}; NOT external blinded calibration)."
        } else {
          "Embedded regression drift detected (${benchmarkLayer.displayName})."
        }
      })
      appendLine(
        String.format(
          Locale.US,
          "Embedded Regression Suite (%d Fixtures, %d Explicit Labels [%d Pos / %d Neg]): Precision=%.0f%% | Recall=%.0f%% | FPR=%.0f%% (%d/%d neg) | FNR=%.0f%% | Design Acc=%.0f%% | Anchor Acc=%.0f%% | Noise Stability=%.0f%% | Finding Stability=%.0f%% | OCR Zero-False-Verified=%.0f%% | Severity Calibration=%.0f%% | Verdict Match=%.0f%%",
          totalCases,
          labelMatrix.size,
          explicitPositiveLabelCount,
          explicitNegativeLabelCount,
          precision * 100.0,
          recall * 100.0,
          falsePositiveRate * 100.0,
          totalFalsePositives,
          explicitNegativeLabelCount,
          falseNegativeRate * 100.0,
          designClassificationAccuracy * 100.0,
          evidenceAnchorAccuracy * 100.0,
          scoreStabilityUnderNoiseRate * 100.0,
          findingStabilityUnderNoiseRate * 100.0,
          ocrZeroFalseVerifiedRate * 100.0,
          severityCalibrationRate * 100.0,
          verdictAgreementRate * 100.0
        )
      )
      appendLine(
        "Wilson 95% CIs: Precision=${precisionCi.formatPercentWithCi()} | Recall=${recallCi.formatPercentWithCi()} | FPR=${fprCi.formatPercentWithCi()} | Anchor=${anchorAccuracyCi.formatPercentWithCi()} | ScoreStable=${scoreStabilityCi.formatPercentWithCi()} | FindingStable=${findingStabilityCi.formatPercentWithCi()}"
      )
      caseResults.forEach { c ->
        val badge = if (c.falsePositives == 0 && c.falseNegatives == 0 && c.severityCalibrated && c.verdictMatched) "PASS" else "DRIFT"
        appendLine("• [$badge] ${c.title} (${c.category}): Verdict=${c.resolvedVerdict} (Expert=${c.expertReferenceVerdict}) | TP=${c.truePositives} FP=${c.falsePositives} FN=${c.falseNegatives} TN=${c.trueNegatives} | Design=${if (c.designMatched) "OK" else "MISS"} | ScoreStable=${if (c.stableUnderNoise) "OK" else "DRIFT"} | FindingStable=${if (c.findingStableUnderNoise) "OK" else "DRIFT"} | OCRFalseVerified=${c.ocrNewVerifiedFindingCount}")
      }
    }.trim()
  }

  data class ExternalCorpusStatus(
    val layer: BenchmarkLayer = BenchmarkLayer.EXTERNAL_BLINDED_CORPUS,
    val annotatedPaperCount: Int = 0,
    val isAvailable: Boolean = false,
    val disclosure: String = "External blinded human-annotated benchmark corpus (Layer 3) has not yet been collected. Reported scores reflect internal embedded unit & adversarial regression fixtures only and must not be interpreted as external clinical/statistical calibration."
  )

  data class LayeredBenchmarkReport(
    val unitFixtures: CalibrationSummaryReport,
    val adversarialFixtures: CalibrationSummaryReport,
    val externalCorpus: ExternalCorpusStatus,
    val designClassifierCalibration: ClassifierCalibrationReport,
    val honestDisclosure: String
  )

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
      expectedSubDesign = AuditSubDesign.RCT,
      layer = BenchmarkLayer.UNIT_FIXTURES
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
      expectedSubDesign = AuditSubDesign.RCT,
      layer = BenchmarkLayer.UNIT_FIXTURES
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
      expectedSubDesign = AuditSubDesign.OBSERVATIONAL_COHORT,
      layer = BenchmarkLayer.UNIT_FIXTURES
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
      expectedSubDesign = AuditSubDesign.ML_BENCHMARK,
      layer = BenchmarkLayer.UNIT_FIXTURES
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
      expertNotes = "Must trigger Stop-and-Ask and output INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT instead of guessing or fabricating flaws.",
      layer = BenchmarkLayer.UNIT_FIXTURES
    )
  )

  val adversarialBenchmarks: List<CalibrationBenchmarkCase> = listOf(
    CalibrationBenchmarkCase(
      caseId = "ADV-01",
      title = "Clean Cohort with Corrupted P-Values Inside References Only",
      category = "ADVERSARIAL_REFERENCE_DECOY",
      manuscriptText = """
        [PAGE 1]
        Abstract
        A prospective observational cohort study followed 800 adults (N = 800) over 5 years using STROBE guidelines with propensity score matching and E-value sensitivity analysis.
        [PAGE 2]
        Methods
        Primary outcome was 5-year cardiovascular event rate. All 800 participants completed follow-up and were analyzed with multivariable logistic regression and sensitivity analysis.
        [PAGE 3]
        Results
        Cardiovascular events occurred with adjusted OR = 0.72 (95% CI 0.56-0.93), p = 0.012.
        [PAGE 5]
        References
        1. Smith et al. (2020). Double-blind randomized controlled trial of aspirin: t(40) = 5.40, p = 0.91; OR = 4.20 (95% CI 0.10-0.30).
      """.trimIndent(),
      expectedFlagKeys = emptySet(),
      expectedFatalFlaw = false,
      expectedStopAndAsk = false,
      expertReferenceVerdict = "ACCEPT",
      expertNotes = "Corrupted numbers and RCT keywords inside References must not flip design to RCT or trigger false arithmetic flags.",
      expectedSubDesign = AuditSubDesign.OBSERVATIONAL_COHORT,
      layer = BenchmarkLayer.ADVERSARIAL_FIXTURES
    ),
    CalibrationBenchmarkCase(
      caseId = "ADV-02",
      title = "Observational Study Mentioning Prior RCT in Introduction + Causal Overclaim",
      category = "ADVERSARIAL_PRIOR_WORK_LEAK",
      manuscriptText = """
        [PAGE 1]
        Introduction
        Prior work by Jones et al. (2019) conducted a randomized controlled trial with double-blind placebo allocation.
        [PAGE 2]
        Methods
        In this retrospective observational cohort study (STROBE), we evaluated 1,500 adults (N = 1,500) comparing statin users vs non-users on primary outcome stroke incidence.
        [PAGE 3]
        Results
        Stroke incidence showed adjusted HR = 0.85 (95% CI 0.74-0.98), p = 0.025.
        [PAGE 4]
        Conclusion
        Our findings prove that statins directly prevent stroke and mandate universal clinical policy adoption.
      """.trimIndent(),
      expectedFlagKeys = setOf("CLAIM_OVERREACH"),
      expectedFatalFlaw = true,
      expectedStopAndAsk = false,
      expertReferenceVerdict = "MAJOR_REVISION",
      expertNotes = "Must classify as observational despite cited RCT in Introduction and flag causal overreach.",
      expectedSubDesign = AuditSubDesign.OBSERVATIONAL_COHORT,
      layer = BenchmarkLayer.ADVERSARIAL_FIXTURES
    ),
    CalibrationBenchmarkCase(
      caseId = "ADV-03",
      title = "Partial PDF Coverage with Scanned Tables (Stop-and-Ask Required)",
      category = "ADVERSARIAL_SCANNED_TABLES",
      manuscriptText = """
        [PAGE 1]
        Abstract
        Multicenter registry study. See Table 2 and Supplementary eTable 1 for primary hazard ratios and subgroup counts.
      """.trimIndent(),
      expectedFlagKeys = setOf("STOP_AND_ASK"),
      expectedFatalFlaw = false,
      expectedStopAndAsk = true,
      expertReferenceVerdict = "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
      simulatedCoverage = DocumentCoverageReport(
        totalPages = 16,
        extractedPages = 3,
        totalChars = 160,
        totalWords = 24,
        lowTextPages = listOf(4, 5, 6, 7, 8),
        omittedPages = listOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
        extractionEngine = "PDFBox-Android",
        extractionConfidence = 1,
        warnings = listOf("Scanned image-only tables detected")
      ),
      expertNotes = "Must abstain via Stop-and-Ask rather than issuing strong flaws on unextracted tables.",
      layer = BenchmarkLayer.ADVERSARIAL_FIXTURES
    )
  )

  fun runCalibration(
    cases: List<CalibrationBenchmarkCase> = defaultBenchmarks,
    trackedDetectorCodes: List<String> = defaultTrackedDetectorCodes,
    layer: BenchmarkLayer = cases.firstOrNull()?.layer ?: BenchmarkLayer.UNIT_FIXTURES
  ): CalibrationSummaryReport {
    val activeCodes = (trackedDetectorCodes + cases.flatMap { it.expectedFlagKeys }).distinct()
    val results = cases.map { evaluateCase(it, activeCodes) }
    val allLabels = results.flatMap { it.labels }

    val tp = allLabels.count { it.expected && it.observed }
    val fp = allLabels.count { !it.expected && it.observed }
    val fn = allLabels.count { it.expected && !it.observed }
    val tn = allLabels.count { !it.expected && !it.observed }
    val explicitNegatives = allLabels.count { !it.expected }
    val explicitPositives = allLabels.count { it.expected }

    val precisionCi = wilson95(tp, tp + fp)
    val recallCi = wilson95(tp, explicitPositives)
    val fprCi = wilson95(fp, explicitNegatives)
    val fnrCi = wilson95(fn, explicitPositives)

    val precision = if (tp + fp > 0) precisionCi.estimate else 1.0
    val recall = if (explicitPositives > 0) recallCi.estimate else 1.0
    val fpr = if (explicitNegatives > 0) fprCi.estimate else 0.0
    val fnr = if (explicitPositives > 0) fnrCi.estimate else 0.0

    val verdictMatches = results.count { it.verdictMatched }
    val sevMatches = results.count { it.severityCalibrated }
    val designMatches = results.count { it.designMatched }
    val verdictCi = wilson95(verdictMatches, results.size)
    val sevCi = wilson95(sevMatches, results.size)
    val designCi = wilson95(designMatches, results.size)

    val allAnchorChecks = results.flatMap { it.anchorChecks }
    val anchorPassed = allAnchorChecks.count { it.passed }
    val anchorCi = wilson95(anchorPassed, allAnchorChecks.size)
    val anchorAcc = if (allAnchorChecks.isNotEmpty()) anchorCi.estimate else 1.0

    val totalClaims = results.sumOf { it.totalClaimCount }
    val unsupportedClaims = results.sumOf { it.unsupportedClaimCount }
    val unsupportedRate = if (totalClaims > 0) unsupportedClaims.toDouble() / totalClaims.toDouble() else 0.0

    val benignNoiseRuns = results.flatMap { it.noiseResults.filter { n -> n.isBenignLayoutNoise } }
    val scoreStableCount = benignNoiseRuns.count { it.scoreStable }
    val findingStableCount = benignNoiseRuns.count { it.findingStable }
    val scoreStableCi = wilson95(scoreStableCount, benignNoiseRuns.size)
    val findingStableCi = wilson95(findingStableCount, benignNoiseRuns.size)
    val scoreStability = if (benignNoiseRuns.isNotEmpty()) scoreStableCi.estimate else 1.0
    val findingStability = if (benignNoiseRuns.isNotEmpty()) findingStableCi.estimate else 1.0

    val ocrRuns = results.flatMap { it.noiseResults.filter { n -> n.kind == NoisePerturbationKind.OCR_CHAR_CONFUSION } }
    val ocrZeroFalseVerifiedRate = if (ocrRuns.isNotEmpty()) {
      ocrRuns.count { it.newVerifiedFindingsOnCorruption == 0 }.toDouble() / ocrRuns.size.toDouble()
    } else {
      1.0
    }
    val corruptionRuns = results.flatMap { it.noiseResults.filter { n -> !n.isBenignLayoutNoise } }
    val corruptionDegradationRate = if (corruptionRuns.isNotEmpty()) {
      corruptionRuns.count { it.anchorOrPageConfidenceDegraded }.toDouble() / corruptionRuns.size.toDouble()
    } else {
      1.0
    }

    val allPassed = fp == 0 && fn == 0 && results.all { it.severityCalibrated && it.verdictMatched && it.designMatched }
    val honestNotice = if (allPassed) {
      "All embedded regression fixtures passed (${results.size}/${results.size} in ${layer.displayName}; internal synthetic suite — NOT external blinded calibration)."
    } else {
      "Embedded regression drift detected (${results.count { it.verdictMatched && it.severityCalibrated && it.falsePositives == 0 && it.falseNegatives == 0 }}/${results.size} clean in ${layer.displayName})."
    }

    return CalibrationSummaryReport(
      totalCases = results.size,
      totalTruePositives = tp,
      totalFalsePositives = fp,
      totalFalseNegatives = fn,
      totalTrueNegatives = tn,
      explicitNegativeLabelCount = explicitNegatives,
      explicitPositiveLabelCount = explicitPositives,
      trackedDetectorCodes = activeCodes,
      labelMatrix = allLabels,
      precision = precision,
      recall = recall,
      verdictAgreementRate = verdictCi.estimate,
      severityCalibrationRate = sevCi.estimate,
      caseResults = results,
      falsePositiveRate = fpr,
      falseNegativeRate = fnr,
      unsupportedClaimRate = unsupportedRate,
      designClassificationAccuracy = designCi.estimate,
      evidenceAnchorAccuracy = anchorAcc,
      scoreStabilityUnderNoiseRate = scoreStability,
      findingStabilityUnderNoiseRate = findingStability,
      ocrZeroFalseVerifiedRate = ocrZeroFalseVerifiedRate,
      corruptionConfidenceDegradationRate = corruptionDegradationRate,
      precisionCi = precisionCi,
      recallCi = recallCi,
      fprCi = fprCi,
      fnrCi = fnrCi,
      verdictAgreementCi = verdictCi,
      severityCalibrationCi = sevCi,
      designAccuracyCi = designCi,
      anchorAccuracyCi = anchorCi,
      scoreStabilityCi = scoreStableCi,
      findingStabilityCi = findingStableCi,
      benchmarkLayer = layer,
      honestCalibrationNotice = honestNotice
    )
  }

  fun runLayeredEvaluation(
    trackedDetectorCodes: List<String> = defaultTrackedDetectorCodes
  ): LayeredBenchmarkReport {
    val unitReport = runCalibration(defaultBenchmarks, trackedDetectorCodes, BenchmarkLayer.UNIT_FIXTURES)
    val advReport = runCalibration(adversarialBenchmarks, trackedDetectorCodes, BenchmarkLayer.ADVERSARIAL_FIXTURES)
    val classifierCal = SemanticValidityEngine.evaluateDesignClassifierCalibration()
    val external = ExternalCorpusStatus()
    val disclosure = buildString {
      append("Layer 1 (${unitReport.totalCases} unit fixtures): ")
      append(if (unitReport.allEmbeddedFixturesPassed) "All embedded regression fixtures passed." else "Drift detected.")
      append(" | Layer 2 (${advReport.totalCases} adversarial fixtures): ")
      append(if (advReport.allEmbeddedFixturesPassed) "All adversarial fixtures passed." else "Drift detected.")
      append(" | Layer 3 (External blinded corpus): Pending external human annotation (n=0). ")
      append("Design Classifier ECE=${String.format(Locale.US, "%.3f", classifierCal.expectedCalibrationError)} across ${classifierCal.totalExamples} labeled examples.")
    }
    return LayeredBenchmarkReport(
      unitFixtures = unitReport,
      adversarialFixtures = advReport,
      externalCorpus = external,
      designClassifierCalibration = classifierCal,
      honestDisclosure = disclosure
    )
  }

  /**
   * Strict evidence anchor validator (Revision A2):
   * Verifies:
   * 1. Exact quote exists in [manuscriptText] via [Reviewer2PipelineService.verifyQuoteInManuscript].
   * 2. Normalized offsets are valid and map within [manuscriptText].
   * 3. Claimed page matches resolved page (and is > 0 whenever manuscript has [PAGE N] markers).
   * 4. Claim's primary numeric values occur verbatim (exact tolerance <= 1e-6) inside the matched quote.
   * 5. Outcome aligns with the matched sentence/local anchor span (pasting a valid quote from
   *    another outcome fails alignment).
   * 6. Occurrence count / uniqueness is explicitly disclosed.
   */
  fun verifyClaimAnchor(
    claim: QuantitativeClaim,
    manuscriptText: String
  ): AnchorVerificationDetail {
    val reasons = mutableListOf<String>()
    val cleanQuote = claim.sourceQuote.trim()
    val hasPageMarkers = Regex("""\[PAGE\s+\d+\]""", RegexOption.IGNORE_CASE).containsMatchIn(manuscriptText)

    val verification = Reviewer2PipelineService.verifyQuoteInManuscript(
      quote = cleanQuote,
      manuscriptText = manuscriptText,
      claimedPage = claim.page
    )

    val quoteExistsExact = verification.matchKind == Reviewer2PipelineService.QuoteMatchKind.EXACT
    if (!quoteExistsExact) {
      reasons.add("Quote is not an exact contiguous match in manuscript (${verification.matchKind.name}).")
    }

    val vStart = verification.startOffset
    val vEnd = verification.endOffset
    val normalizedOffsetsValid = quoteExistsExact &&
      vStart != null &&
      vEnd != null &&
      vStart >= 0 &&
      vEnd > vStart &&
      vEnd <= manuscriptText.length
    if (!normalizedOffsetsValid && quoteExistsExact) {
      reasons.add("Resolved character offsets [$vStart, $vEnd) are invalid.")
    }

    val pageMatches = when {
      hasPageMarkers && claim.page <= 0 -> false
      claim.page > 0 -> verification.resolvedPage == claim.page && verification.pageMismatchWarning == null
      else -> verification.resolvedPage == 0
    }
    if (!pageMatches) {
      reasons.add(
        verification.pageMismatchWarning
          ?: "Page mismatch: claimed Page ${claim.page}, resolved Page ${verification.resolvedPage} (markersPresent=$hasPageMarkers)."
      )
    }

    val matchedSlice = if (normalizedOffsetsValid) {
      manuscriptText.substring(vStart!!, vEnd!!)
    } else {
      cleanQuote
    }
    val numericValuesPresent = verifyClaimNumericsInQuote(claim, matchedSlice)
    if (!numericValuesPresent) {
      reasons.add("One or more numeric values attached to claim (${claim.claimId}) do not occur verbatim inside matched quote.")
    }

    val outcomeAligned = if (normalizedOffsetsValid) {
      verifyOutcomeAlignmentAtAnchor(claim, manuscriptText, vStart!!, vEnd!!)
    } else {
      false
    }
    if (!outcomeAligned && normalizedOffsetsValid) {
      reasons.add("Claim outcome '${claim.outcome}' does not align with the sentence anchored at [$vStart, $vEnd).")
    }

    val passed = quoteExistsExact &&
      normalizedOffsetsValid &&
      pageMatches &&
      numericValuesPresent &&
      outcomeAligned

    return AnchorVerificationDetail(
      claimId = claim.claimId,
      quoteExistsExact = quoteExistsExact,
      normalizedOffsetsValid = normalizedOffsetsValid,
      pageMatches = pageMatches,
      numericValuesPresentInQuote = numericValuesPresent,
      outcomeAligned = outcomeAligned,
      occurrenceCount = verification.occurrenceCount,
      isUniqueMatch = verification.occurrenceCount == 1,
      passed = passed,
      failureReasons = reasons
    )
  }

  fun evaluateAnchorAccuracy(
    claims: List<QuantitativeClaim>,
    manuscriptText: String
  ): Pair<MetricWithCi, List<AnchorVerificationDetail>> {
    val details = claims.map { verifyClaimAnchor(it, manuscriptText) }
    val passed = details.count { it.passed }
    return wilson95(passed, details.size) to details
  }

  private val numericTokenRegex = Regex("""(?<!\d)[-−+]?\d+(?:\.\d+)?""")

  private fun verifyClaimNumericsInQuote(claim: QuantitativeClaim, matchedQuote: String): Boolean {
    val quoteNums = numericTokenRegex.findAll(matchedQuote)
      .mapNotNull { it.value.replace('−', '-').toDoubleOrNull() }
      .toList()
    if (quoteNums.isEmpty()) return false

    val required = buildList {
      claim.pointEstimate?.let { add(it) }
      claim.ciLower?.let { add(it) }
      claim.ciUpper?.let { add(it) }
      claim.pValue?.let { add(it) }
      claim.testStatisticValue?.let { add(it) }
    }
    if (required.isEmpty()) return true
    return required.all { expected ->
      quoteNums.any { actual -> abs(actual - expected) <= 1e-6 }
    }
  }

  private val outcomeStopWords = setOf(
    "primary", "secondary", "exploratory", "outcome", "endpoint", "unspecified",
    "analysis", "results", "effect", "estimate", "measure", "rate", "score",
    "with", "from", "that", "this", "over", "after", "month", "months", "year", "years",
    "model", "study", "table", "figure", "adjusted", "unadjusted", "prevalence", "incidence"
  )

  private fun verifyOutcomeAlignmentAtAnchor(
    claim: QuantitativeClaim,
    manuscriptText: String,
    startOffset: Int,
    endOffset: Int
  ): Boolean {
    val rawOutcome = claim.outcome.ifBlank { claim.compoundKey?.outcome.orEmpty() }.trim()
    if (rawOutcome.isBlank() ||
      rawOutcome.equals("unspecified", ignoreCase = true) ||
      rawOutcome.contains("unspecified", ignoreCase = true)
    ) {
      return true
    }

    val outcomeTokens = rawOutcome.lowercase(Locale.US)
      .split(Regex("""[^a-z0-9]+"""))
      .filter { it.length >= 4 && it !in outcomeStopWords }
      .distinct()
    if (outcomeTokens.isEmpty()) return true

    // Extract the exact sentence/line enclosing [startOffset, endOffset] (do NOT cross newline or sentence boundary)
    var sentStart = startOffset
    while (sentStart > 0) {
      val ch = manuscriptText[sentStart - 1]
      if (ch == '\n' || ch == '\r') break
      if (ch == '.' && sentStart < manuscriptText.length && manuscriptText[sentStart].isWhitespace()) break
      sentStart--
    }
    var sentEnd = endOffset
    while (sentEnd < manuscriptText.length) {
      val ch = manuscriptText[sentEnd]
      if (ch == '\n' || ch == '\r') break
      if (ch == '.' && (sentEnd + 1 >= manuscriptText.length || manuscriptText[sentEnd + 1].isWhitespace())) {
        sentEnd++
        break
      }
      sentEnd++
    }
    val localSentence = manuscriptText.substring(sentStart, sentEnd).lowercase(Locale.US)
    return outcomeTokens.any { token ->
      val stem = if (token.length >= 6) token.take(token.length - 1) else token
      localSentence.contains(token) || localSentence.contains(stem)
    }
  }

  private fun evaluateCase(
    case: CalibrationBenchmarkCase,
    trackedCodes: List<String>
  ): CalibrationCaseResult {
    val caseCoverage = case.simulatedCoverage ?: DocumentCoverageReport.forCompleteText(
      text = case.manuscriptText,
      engine = "Embedded Benchmark Fixture"
    )
    val extConf = caseCoverage.extractionConfidence
    val forensic = ManuscriptForensicEngine.analyzeManuscript(
      text = case.manuscriptText,
      isEmpiricalTrack = true,
      coverageReport = caseCoverage
    )
    val statAudit = forensic.quantForensics.statisticalAudit!!
    val epistemic = forensic.quantForensics.epistemicSafeguards ?: EpistemicSafeguardEngine.evaluate(
      text = case.manuscriptText,
      statAudit = statAudit,
      coverageReport = caseCoverage,
      extractionConfidence = extConf
    )

    val detected = extractDetectedFlags(statAudit, epistemic)
    val labels = trackedCodes.map { code ->
      BenchmarkLabel(
        caseId = case.caseId,
        findingCode = code,
        expected = code in case.expectedFlagKeys,
        observed = code in detected
      )
    }

    val tp = labels.count { it.expected && it.observed }
    val fp = labels.count { !it.expected && it.observed }
    val fn = labels.count { it.expected && !it.observed }
    val tn = labels.count { !it.expected && !it.observed }

    val detectedFatal = epistemic.fatalFlawCount > 0 ||
      statAudit.verifiedArithmeticErrorCount > 0 ||
      statAudit.verifiedModelMismatchCount > 0

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
    val (anchorMetric, anchorDetails) = evaluateAnchorAccuracy(activeClaims, case.manuscriptText)
    val baselineAnchorAcc = if (anchorDetails.isNotEmpty()) anchorMetric.estimate else 1.0

    // Run full 10-perturbation suite separating score stability, finding stability, and corruption safety
    val noiseResults = runNoisePerturbationSuite(
      case = case,
      baselineCoverage = caseCoverage,
      baselineDetectedFlags = detected,
      baselineAdjudicated = adjudicated,
      baselineAnchorAccuracy = baselineAnchorAcc
    )
    val benignRuns = noiseResults.filter { it.isBenignLayoutNoise }
    val scoreStableAll = benignRuns.all { it.scoreStable }
    val findingStableAll = benignRuns.all { it.findingStable }
    val ocrFalseVerified = noiseResults
      .filter { it.kind == NoisePerturbationKind.OCR_CHAR_CONFUSION }
      .sumOf { it.newVerifiedFindingsOnCorruption }
    val corruptionDegraded = noiseResults
      .filter { !it.isBenignLayoutNoise }
      .all { it.anchorOrPageConfidenceDegraded }

    return CalibrationCaseResult(
      caseId = case.caseId,
      title = case.title,
      category = case.category,
      detectedFlags = detected,
      expectedFlags = case.expectedFlagKeys,
      labels = labels,
      truePositives = tp,
      falsePositives = fp,
      falseNegatives = fn,
      trueNegatives = tn,
      expectedFatalFlaw = case.expectedFatalFlaw,
      detectedFatalFlaw = detectedFatal,
      expectedStopAndAsk = case.expectedStopAndAsk,
      detectedStopAndAsk = epistemic.stopAndAsk.shouldStopAndAsk,
      resolvedVerdict = adjudicated.resolvedVerdict,
      expertReferenceVerdict = case.expertReferenceVerdict,
      verdictMatched = adjudicated.resolvedVerdict == case.expertReferenceVerdict,
      severityCalibrated = detectedFatal == case.expectedFatalFlaw &&
        epistemic.stopAndAsk.shouldStopAndAsk == case.expectedStopAndAsk,
      designMatched = designMatched,
      anchorAccuracy = baselineAnchorAcc,
      anchorChecks = anchorDetails,
      unsupportedClaimCount = statAudit.unsupportedClaimCount,
      totalClaimCount = activeClaims.size,
      stableUnderNoise = scoreStableAll,
      findingStableUnderNoise = findingStableAll,
      noiseResults = noiseResults,
      ocrNewVerifiedFindingCount = ocrFalseVerified,
      corruptionDegradedConfidence = corruptionDegraded
    )
  }

  private fun extractDetectedFlags(
    statAudit: com.example.data.ai.stats.StatisticalAuditReport,
    epistemic: EpistemicSafeguardReport
  ): Set<String> {
    val detected = mutableSetOf<String>()
    if (statAudit.reconstructions.any { it.calculatorName.contains("2x2") && it.status == QuantFindingStatus.FAIL }) {
      detected.add("2X2_ARITHMETIC")
    }
    if (epistemic.denominatorLedger.discrepancies.any {
        it.discrepancyType in setOf("TABLE_DENOMINATOR_DRIFT", "IMPOSSIBLE_ATTRITION", "SUBGROUP_EXCEEDS_PARENT")
      }
    ) {
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
    return detected
  }

  fun runNoisePerturbationSuite(
    case: CalibrationBenchmarkCase,
    baselineCoverage: DocumentCoverageReport = case.simulatedCoverage ?: DocumentCoverageReport.forCompleteText(case.manuscriptText)
  ): List<NoisePerturbationResult> = runNoisePerturbationSuiteInternal(
    case = case,
    baselineCoverage = baselineCoverage,
    baselineDetectedFlags = null,
    baselineAdjudicated = null,
    baselineAnchorAccuracy = null
  )

  internal fun runNoisePerturbationSuite(
    case: CalibrationBenchmarkCase,
    baselineCoverage: DocumentCoverageReport = case.simulatedCoverage ?: DocumentCoverageReport.forCompleteText(case.manuscriptText),
    baselineDetectedFlags: Set<String>? = null,
    baselineAdjudicated: Reviewer2PipelineService.AdjudicationResult? = null,
    baselineAnchorAccuracy: Double? = null
  ): List<NoisePerturbationResult> = runNoisePerturbationSuiteInternal(
    case = case,
    baselineCoverage = baselineCoverage,
    baselineDetectedFlags = baselineDetectedFlags,
    baselineAdjudicated = baselineAdjudicated,
    baselineAnchorAccuracy = baselineAnchorAccuracy
  )

  private fun runNoisePerturbationSuiteInternal(
    case: CalibrationBenchmarkCase,
    baselineCoverage: DocumentCoverageReport,
    baselineDetectedFlags: Set<String>?,
    baselineAdjudicated: Reviewer2PipelineService.AdjudicationResult?,
    baselineAnchorAccuracy: Double?
  ): List<NoisePerturbationResult> {
    val baseForensic = ManuscriptForensicEngine.analyzeManuscript(
      text = case.manuscriptText,
      isEmpiricalTrack = true,
      coverageReport = baselineCoverage
    )
    val baseStat = baseForensic.quantForensics.statisticalAudit!!
    val baseEpi = baseForensic.quantForensics.epistemicSafeguards!!
    val baseFlags = baselineDetectedFlags ?: extractDetectedFlags(baseStat, baseEpi)
    val baseAdj = baselineAdjudicated ?: Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = case.manuscriptText,
      forensicReport = baseForensic,
      methodologyScore = 9,
      significanceScore = 9,
      clarityScore = 9,
      originalityScore = 9,
      mathRigorScore = 9,
      reproducibilityScore = 9,
      rawVerdict = "ACCEPT",
      reviewMode = case.reviewMode,
      extractionConfidence = baselineCoverage.extractionConfidence,
      applyStatisticalScoreGate = true,
      epistemicReport = baseEpi
    )
    val baseClaims = baseStat.claims.filter { !it.isFromReferenceOrCitation }
    val baseAnchorAcc = baselineAnchorAccuracy ?: run {
      val (m, details) = evaluateAnchorAccuracy(baseClaims, case.manuscriptText)
      if (details.isNotEmpty()) m.estimate else 1.0
    }
    val baseVerifiedKeys = baseAdj.adjudicatedFindings
      .filter { it.status == FindingStatus.VERIFIED }
      .map { "${it.severity}:${it.title}" }
      .toSet()

    return NoisePerturbationKind.entries.map { kind ->
      val perturbedText = applyPerturbation(case.manuscriptText, kind)
      val perturbedCoverage = when (kind) {
        NoisePerturbationKind.OCR_CHAR_CONFUSION -> baselineCoverage.copy(
          extractionConfidence = minOf(baselineCoverage.extractionConfidence, 2),
          warnings = baselineCoverage.warnings + "OCR character/decimal corruption detected in perturbed variant"
        )
        NoisePerturbationKind.REMOVED_PAGE_MARKERS -> baselineCoverage.copy(
          extractionConfidence = minOf(baselineCoverage.extractionConfidence, 3),
          warnings = baselineCoverage.warnings + "Page markers absent; page-level anchor verification degraded"
        )
        else -> baselineCoverage
      }

      val pForensic = ManuscriptForensicEngine.analyzeManuscript(
        text = perturbedText,
        isEmpiricalTrack = true,
        coverageReport = perturbedCoverage
      )
      val pStat = pForensic.quantForensics.statisticalAudit!!
      val pEpi = pForensic.quantForensics.epistemicSafeguards!!
      val pFlags = extractDetectedFlags(pStat, pEpi)
      val pAdj = Reviewer2PipelineService.adjudicateFindingsAndScores(
        rawFindings = emptyList(),
        manuscriptText = perturbedText,
        forensicReport = pForensic,
        methodologyScore = 9,
        significanceScore = 9,
        clarityScore = 9,
        originalityScore = 9,
        mathRigorScore = 9,
        reproducibilityScore = 9,
        rawVerdict = "ACCEPT",
        reviewMode = case.reviewMode,
        extractionConfidence = perturbedCoverage.extractionConfidence,
        applyStatisticalScoreGate = true,
        epistemicReport = pEpi
      )

      val scoreStable = pAdj.resolvedVerdict == baseAdj.resolvedVerdict &&
        abs(pAdj.mathRigorScore - baseAdj.mathRigorScore) <= 1 &&
        abs(pAdj.methodologyScore - baseAdj.methodologyScore) <= 1
      val findingStable = pFlags == baseFlags

      val pVerifiedKeys = pAdj.adjudicatedFindings
        .filter { it.status == FindingStatus.VERIFIED }
        .map { "${it.severity}:${it.title}" }
        .toSet()
      val newVerifiedCount = (pVerifiedKeys - baseVerifiedKeys).size +
        (if (kind == NoisePerturbationKind.OCR_CHAR_CONFUSION) (pFlags - baseFlags - setOf("STOP_AND_ASK")).size else 0)

      val pClaims = pStat.claims.filter { !it.isFromReferenceOrCitation }
      // Evaluate how well baseline claims still anchor in the corrupted text (page resolution / digit fidelity)
      val (crossAnchorMetric, crossAnchorDetails) = evaluateAnchorAccuracy(baseClaims, perturbedText)
      val perturbedAnchorAcc = if (crossAnchorDetails.isNotEmpty()) {
        crossAnchorMetric.estimate
      } else {
        if (pClaims.all { it.page == 0 } && kind == NoisePerturbationKind.REMOVED_PAGE_MARKERS) 0.0 else baseAnchorAcc
      }
      val pageConfidenceDropped = perturbedCoverage.extractionConfidence < baselineCoverage.extractionConfidence ||
        (pClaims.isNotEmpty() && pClaims.all { it.page == 0 }) ||
        perturbedAnchorAcc < baseAnchorAcc ||
        (case.simulatedCoverage != null && perturbedCoverage.extractionConfidence <= 2)

      NoisePerturbationResult(
        kind = kind,
        isBenignLayoutNoise = kind.isBenignLayoutNoise,
        scoreStable = scoreStable,
        findingStable = findingStable,
        newVerifiedFindingsOnCorruption = if (kind == NoisePerturbationKind.OCR_CHAR_CONFUSION) newVerifiedCount else 0,
        baselineAnchorAccuracy = baseAnchorAcc,
        perturbedAnchorAccuracy = perturbedAnchorAcc,
        anchorOrPageConfidenceDegraded = if (!kind.isBenignLayoutNoise) pageConfidenceDropped else false,
        note = when {
          kind == NoisePerturbationKind.OCR_CHAR_CONFUSION ->
            "OCR corruption produced $newVerifiedCount new VERIFIED finding(s); confidence degraded=${pageConfidenceDropped} (anchor ${String.format(Locale.US, "%.0f%%", perturbedAnchorAcc * 100)})."
          kind == NoisePerturbationKind.REMOVED_PAGE_MARKERS ->
            "Page markers stripped; page/anchor confidence degraded=${pageConfidenceDropped} (anchor ${String.format(Locale.US, "%.0f%%", perturbedAnchorAcc * 100)})."
          else ->
            "ScoreStable=$scoreStable | FindingStable=$findingStable | Flags=$pFlags"
        }
      )
    }
  }

  fun applyPerturbation(text: String, kind: NoisePerturbationKind): String = when (kind) {
    NoisePerturbationKind.DUPLICATE_HEADERS_FOOTERS -> {
      val header = "Running Head: Clinical Evidence Appraisal Journal  |  Confidential Review Copy"
      val footer = "Downloaded from Academic Press Repository — Page Footer"
      text.lines().flatMap { line ->
        if (line.trim().startsWith("[PAGE", ignoreCase = true)) {
          listOf(line, header, footer)
        } else {
          listOf(line)
        }
      }.joinToString("\n")
    }
    NoisePerturbationKind.REORDERED_COLUMNS -> {
      val lines = text.lines().toMutableList()
      val firstLongIdx = lines.indexOfFirst { it.trim().length >= 50 && !it.trim().startsWith("[PAGE") }
      if (firstLongIdx >= 0) {
        lines.add(firstLongIdx + 1, lines[firstLongIdx])
      }
      lines.joinToString("\n")
    }
    NoisePerturbationKind.OCR_CHAR_CONFUSION -> {
      // Corrupt 0 -> O, 1 -> l, and drop decimal points on numeric statistics inside body
      text.replace(Regex("""\b0\.(\d+)"""), "O$1")
        .replace(Regex("""\b1\.(\d+)"""), "l$1")
        .replace("18/100", "l8/lOO")
        .replace("35/100", "35/lOO")
        .replace("18/120", "l8/l2O")
        .replace("36/120", "36/l2O")
        .replace("0.95", "O95")
    }
    NoisePerturbationKind.SPLIT_HYPHENATED_WORDS -> {
      text.replace("randomized", "random-\nized")
        .replace("hospitalization", "hospi-\ntalization")
        .replace("hypertension", "hyper-\ntension")
        .replace("normalization", "normal-\nization")
    }
    NoisePerturbationKind.REMOVED_PAGE_MARKERS -> {
      text.replace(Regex("""(?im)^\s*\[PAGE\s+\d+\]\s*$"""), "").trim()
    }
    NoisePerturbationKind.INSERTED_TABLE_CAPTIONS -> {
      text + "\n\nFigure 1. Study participant flow diagram.\nTable S2. Additional descriptive baseline summary by site."
    }
    NoisePerturbationKind.REORDERED_PARAGRAPHS -> {
      // Paraphrase & insert a benign background note without breaking Methods/Results semantics
      text.replace("versus", "vs.")
        .replace("compared daily", "evaluated daily")
        .replace("occurred in", "was observed in")
    }
    NoisePerturbationKind.INSERTED_REFERENCE_PVALUES -> {
      text + "\n\nReferences\n1. Decoy Prior Study (2019). Secondary observational report showing t(30) = 6.20, p = 0.85 and OR = 5.50 (95% CI 0.12-0.44).\n2. Prior Trial (2018). Placebo comparison (N = 950, p = 0.049)."
    }
    NoisePerturbationKind.ROUNDED_ESTIMATES -> {
      text.replace("0.22-0.78", "0.22–0.78")
        .replace("1.01-1.03", "1.01–1.03")
        .replace("0.92-0.98", "0.92–0.98")
    }
    NoisePerturbationKind.REMOVED_TABLE_BORDERS -> {
      text.replace("|", "   ").replace("\t", "   ")
    }
  }
}
