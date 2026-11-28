package com.example.data.ai.referee

import com.example.data.ai.StudyDesignArchetype
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import com.example.util.DocumentCoverageReport
import kotlin.math.abs

data class BenchmarkFixtureCase(
  val caseId: String,
  val label: String,
  val expectedPositiveConcern: Boolean,
  val expectedAbstention: Boolean,
  val predictedPositiveProbability: Double,
  val input: RefereeAdjudicationInput
)

data class BenchmarkMetricsReport(
  val totalFixtures: Int,
  val precision: Double,
  val recall: Double,
  val falsePositiveRate: Double,
  val falseNegativeRate: Double,
  val abstentionRate: Double,
  val brierScore: Double,
  val expectedCalibrationError: Double,
  val interPassAgreementRate: Double,
  val reportReproducibilityRate: Double,
  val externallyValidated: Boolean = false,
  val validationDisclaimer: String =
    "Internal deterministic fixture evaluation only (externallyValidated=false); does not substitute for validation on unseen expert-labeled manuscripts."
)

/**
 * Phase 11 Benchmark Evaluation Harness.
 * Evaluates precision, recall, FPR, FNR, abstention rate, calibration (Brier + ECE),
 * inter-pass agreement, and report determinism/reproducibility on curated fixtures.
 * Explicitly reports [externallyValidated] = false until evaluated on external unseen benchmarks.
 */
object RefereeBenchmarkHarness {

  fun defaultInternalFixtures(): List<BenchmarkFixtureCase> {
    val cleanMethodsText = """
      Methods
      Randomized controlled trial enrolling 200 adult ICU patients with concealed 1:1 allocation.
      Results
      Table 1 summarizes primary mortality: HR 0.82 (95% CI 0.70 to 0.96, p = 0.014).
      Supplementary Appendix
      Table S1 shows sensitivity analysis.
      References
      1. Author A. Trial Design. NEJM 2024.
    """.trimIndent()
    val cleanDomain = DomainAppraisalAdapters.appraise(cleanMethodsText, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val cleanReadiness = DocumentReadinessEvaluator.assess(
      manuscriptText = cleanMethodsText,
      coverageReport = DocumentCoverageReport.forCompleteText(cleanMethodsText),
      attachedBitmapCount = 0
    )

    val verifiedCleanFinding = ReviewFinding(
      id = "B-CLEAN-1",
      title = "Primary endpoint and allocation reported",
      allegation = "Concealed allocation and primary mortality HR 0.82 reported in Table 1.",
      severity = "MINOR",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(
        EvidenceAnchor(
          documentId = "fx-1",
          pageNumber = 1,
          section = "Results",
          exactQuote = "Table 1 summarizes primary mortality: HR 0.82 (95% CI 0.70 to 0.96, p = 0.014).",
          startOffset = 0,
          endOffset = 79,
          extractionMethod = "TextCoordinateMatch",
          sourceChecksum = "chk-clean"
        )
      )
    )

    val caseCleanPass = BenchmarkFixtureCase(
      caseId = "FX-01-CLEAN-RCT",
      label = "Complete verified RCT with no major concerns",
      expectedPositiveConcern = false,
      expectedAbstention = false,
      predictedPositiveProbability = 0.15,
      input = RefereeAdjudicationInput(
        analysisId = "fx-01",
        manuscriptText = cleanMethodsText,
        providerRoute = "Firebase AI Logic (Tier 1)",
        modelId = "gemini-3.8-flash",
        startedAtMs = 1_700_000_000_000L,
        completedAtMs = 1_700_000_001_000L,
        readiness = cleanReadiness,
        extractionConfidence = 5,
        totalPages = 1,
        pagesExtracted = 1,
        pagesAnalyzed = 1,
        claimsDiscovered = 1,
        claimsRetained = 1,
        claimsReconstructed = 1,
        deterministicFindings = emptyList(),
        llmFindings = listOf(verifiedCleanFinding),
        passComparisons = listOf(
          PassComparisonInput("Pass1", "RCT", "Mortality", "VERIFIED_WITH_MINOR_CONCERNS"),
          PassComparisonInput("Pass2", "RCT", "Mortality", "VERIFIED_WITH_MINOR_CONCERNS")
        ),
        quantitativeStatuses = listOf(QuantitativeFindingStatus.PASS),
        crossCheckChecklist = DiscrepancyCrossCheckChecklist(
          primaryTablesChecked = true,
          figuresChecked = true,
          supplementsChecked = true,
          scaleTransformationsChecked = true,
          roundingToleranceChecked = true,
          adjustedModelsChecked = true,
          subgroupScopeChecked = true,
          alternativeMethodsChecked = true
        ),
        domainAppraisal = cleanDomain
      )
    )

    val flawFinding = ReviewFinding(
      id = "Q-01",
      title = "Confirmed CI Arithmetic Error",
      allegation = "Reported HR 0.85 lies outside reported 95% CI (0.90 to 1.25) on same scale.",
      severity = "CRITICAL",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(
        EvidenceAnchor(
          documentId = "fx-2",
          pageNumber = 1,
          section = "Results",
          exactQuote = "Table 1 summarizes primary mortality: HR 0.82 (95% CI 0.70 to 0.96, p = 0.014).",
          startOffset = 0,
          endOffset = 79,
          extractionMethod = "DeterministicStats",
          sourceChecksum = "chk-err"
        )
      )
    )
    val caseMajorConcern = caseCleanPass.copy(
      caseId = "FX-02-CONFIRMED-CI-ERROR",
      label = "Confirmed statistical error escalates to MATERIAL/MAJOR_UNRESOLVED_CONCERNS",
      expectedPositiveConcern = true,
      expectedAbstention = false,
      predictedPositiveProbability = 0.92,
      input = caseCleanPass.input.copy(
        analysisId = "fx-02",
        deterministicFindings = listOf(flawFinding),
        llmFindings = emptyList(),
        quantitativeStatuses = listOf(QuantitativeFindingStatus.CONFIRMED_ERROR),
        passComparisons = listOf(
          PassComparisonInput("Pass1", "RCT", "Mortality", "MAJOR_UNRESOLVED_CONCERNS"),
          PassComparisonInput("Pass2", "RCT", "Mortality", "MAJOR_UNRESOLVED_CONCERNS")
        )
      )
    )

    val incompleteText = "Abstract\nThis short commentary discusses future trials without methods or tables."
    val incompleteReadiness = DocumentReadinessEvaluator.assess(
      manuscriptText = incompleteText,
      coverageReport = DocumentCoverageReport.forCompleteText(incompleteText),
      attachedBitmapCount = 0
    )
    val caseAbstainIncomplete = BenchmarkFixtureCase(
      caseId = "FX-03-INCOMPLETE-ABSTAIN",
      label = "Incomplete manuscript triggers AbstentionGate",
      expectedPositiveConcern = false,
      expectedAbstention = true,
      predictedPositiveProbability = 0.25,
      input = caseCleanPass.input.copy(
        analysisId = "fx-03",
        manuscriptText = incompleteText,
        readiness = incompleteReadiness,
        extractionConfidence = 3,
        deterministicFindings = emptyList(),
        llmFindings = emptyList(),
        quantitativeStatuses = emptyList(),
        passComparisons = listOf(
          PassComparisonInput("Pass1", "Uncertain", null, "PROVISIONAL_APPRAISAL")
        ),
        domainAppraisal = DomainAppraisalAdapters.appraise(incompleteText, StudyDesignArchetype.GENERAL_EMPIRICAL)
      )
    )

    val caseAbstainConflict = caseCleanPass.copy(
      caseId = "FX-04-INTERPASS-CONFLICT",
      label = "Conflicting pass endpoints trigger AbstentionGate",
      expectedPositiveConcern = false,
      expectedAbstention = true,
      predictedPositiveProbability = 0.45,
      input = caseCleanPass.input.copy(
        analysisId = "fx-04",
        passComparisons = listOf(
          PassComparisonInput("Pass1", "RCT", "All-Cause Mortality", "VERIFIED_NO_MAJOR_CONCERNS"),
          PassComparisonInput("Pass2", "RCT", "Hospital Readmission", "MATERIAL_CONCERNS")
        )
      )
    )

    return listOf(caseCleanPass, caseMajorConcern, caseAbstainIncomplete, caseAbstainConflict)
  }

  fun evaluate(fixtures: List<BenchmarkFixtureCase> = defaultInternalFixtures()): BenchmarkMetricsReport {
    require(fixtures.isNotEmpty()) { "At least one benchmark fixture is required." }

    var tp = 0
    var fp = 0
    var tn = 0
    var fn = 0
    var abstainedCount = 0
    var brierSum = 0.0
    var agreementCount = 0
    var reproducibleCount = 0

    val calibSamples = mutableListOf<Pair<Double, Double>>()

    for (fixture in fixtures) {
      val run1 = AutonomousRefereeOrchestrator.adjudicate(fixture.input)
      val run2 = AutonomousRefereeOrchestrator.adjudicate(fixture.input)

      val reproducible = run1.decision == run2.decision &&
        run1.abstentionResult.triggeredReasons == run2.abstentionResult.triggeredReasons &&
        run1.auditLogJson == run2.auditLogJson &&
        run1.manifest.manuscriptSha256 == run2.manifest.manuscriptSha256
      if (reproducible) reproducibleCount++

      if (run1.abstentionResult.shouldAbstainFromVerifiedVerdict) {
        abstainedCount++
      }
      if (run1.interPassDisagreements.isEmpty()) {
        agreementCount++
      }

      val predictedConcern = run1.decision == RefereeDecision.MATERIAL_CONCERNS ||
        run1.decision == RefereeDecision.MAJOR_UNRESOLVED_CONCERNS
      val actualConcern = fixture.expectedPositiveConcern

      when {
        predictedConcern && actualConcern -> tp++
        predictedConcern && !actualConcern -> fp++
        !predictedConcern && !actualConcern -> tn++
        else -> fn++
      }

      val y = if (actualConcern) 1.0 else 0.0
      val p = fixture.predictedPositiveProbability.coerceIn(0.0, 1.0)
      brierSum += (p - y) * (p - y)
      calibSamples.add(p to y)
    }

    val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp).toDouble()
    val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn).toDouble()
    val fpr = if (fp + tn == 0) 0.0 else fp.toDouble() / (fp + tn).toDouble()
    val fnr = if (fn + tp == 0) 0.0 else fn.toDouble() / (fn + tp).toDouble()
    val ece = computeExpectedCalibrationError(calibSamples, bins = 5)

    return BenchmarkMetricsReport(
      totalFixtures = fixtures.size,
      precision = precision,
      recall = recall,
      falsePositiveRate = fpr,
      falseNegativeRate = fnr,
      abstentionRate = abstainedCount.toDouble() / fixtures.size.toDouble(),
      brierScore = brierSum / fixtures.size.toDouble(),
      expectedCalibrationError = ece,
      interPassAgreementRate = agreementCount.toDouble() / fixtures.size.toDouble(),
      reportReproducibilityRate = reproducibleCount.toDouble() / fixtures.size.toDouble(),
      externallyValidated = false
    )
  }

  private fun computeExpectedCalibrationError(
    samples: List<Pair<Double, Double>>,
    bins: Int = 5
  ): Double {
    if (samples.isEmpty()) return 0.0
    var ece = 0.0
    val n = samples.size.toDouble()
    for (b in 0 until bins) {
      val lo = b.toDouble() / bins.toDouble()
      val hi = (b + 1).toDouble() / bins.toDouble()
      val bucket = samples.filter { (p, _) ->
        if (b == bins - 1) p in lo..hi else p >= lo && p < hi
      }
      if (bucket.isNotEmpty()) {
        val avgConf = bucket.sumOf { it.first } / bucket.size.toDouble()
        val avgAcc = bucket.sumOf { it.second } / bucket.size.toDouble()
        ece += (bucket.size.toDouble() / n) * abs(avgConf - avgAcc)
      }
    }
    return ece
  }
}
