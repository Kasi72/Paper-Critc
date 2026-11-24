package com.example

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.DefenseOutcome
import com.example.data.ai.epistemic.ReportingStatus
import com.example.data.ai.epistemic.ReviewerCalibrationBenchmark
import com.example.data.ai.epistemic.UncertaintySource
import com.example.data.ai.semantic.CalibratedStudyDesign
import com.example.data.ai.semantic.ClaimCompatibilityStatus
import com.example.data.ai.semantic.CompoundClaimKey
import com.example.data.ai.semantic.MethodologicalAssessmentStatus
import com.example.data.ai.semantic.PdfLineLayoutMetadata
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.QuantitativeClaim
import com.example.data.ai.stats.StatisticalAuditOrchestrator
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.util.CoverageStatus
import com.example.util.DocumentCoverageReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase9TrustValidationRevisionTest {

  // ============================================================================
  // REVISION A: METRIC VALIDITY
  // ============================================================================

  @Test
  fun revisionA1_explicitLabelMatrixAndDynamicSchema_zeroHardcodedSlots() {
    val defaultReport = ReviewerCalibrationBenchmark.runCalibration()
    val expectedDefaultSlots = defaultReport.totalCases * defaultReport.trackedDetectorCodes.size
    assertEquals(expectedDefaultSlots, defaultReport.labelMatrix.size)
    assertEquals(
      defaultReport.labelMatrix.count { !it.expected },
      defaultReport.explicitNegativeLabelCount
    )
    assertEquals(
      defaultReport.totalFalsePositives + defaultReport.totalTrueNegatives,
      defaultReport.explicitNegativeLabelCount
    )
    assertEquals(0, defaultReport.totalFalsePositives)
    assertEquals(0, defaultReport.totalFalseNegatives)
    assertEquals(0.0, defaultReport.falsePositiveRate, 1e-9)

    // Adding a new detector code MUST change the explicit label schema and negative denominator
    val expandedDetectors = ReviewerCalibrationBenchmark.defaultTrackedDetectorCodes + "CUSTOM_SEVENTH_DETECTOR"
    val expandedReport = ReviewerCalibrationBenchmark.runCalibration(
      trackedDetectorCodes = expandedDetectors
    )
    assertEquals(defaultReport.trackedDetectorCodes.size + 1, expandedReport.trackedDetectorCodes.size)
    assertEquals(
      defaultReport.labelMatrix.size + defaultReport.totalCases,
      expandedReport.labelMatrix.size
    )
    assertEquals(
      defaultReport.explicitNegativeLabelCount + defaultReport.totalCases,
      expandedReport.explicitNegativeLabelCount
    )
  }

  @Test
  fun revisionA2_strictEvidenceAnchorValidation_rejectsAlteredPageDigitAndWrongOutcome() {
    val manuscript = """
      [PAGE 1]
      Abstract
      A randomized controlled trial enrolled 200 patients (N = 200).
      [PAGE 2]
      Methods
      Primary outcome was 12-month mortality. Secondary outcome was headache incidence.
      [PAGE 4]
      Results
      Primary mortality occurred in 18/100 vs 35/100 patients (OR = 2.15, 95% CI 1.18-3.92, p = 0.008).
      Secondary headache occurred in 12/100 vs 11/100 patients (OR = 1.10, 95% CI 0.45-2.68, p = 0.72).
    """.trimIndent()

    val validClaim = QuantitativeClaim(
      claimId = "C-001",
      page = 4,
      section = "Results",
      outcome = "12-month mortality",
      effectMeasure = "OR",
      pointEstimate = 2.15,
      ciLower = 1.18,
      ciUpper = 3.92,
      pValue = 0.008,
      sourceQuote = "OR = 2.15, 95% CI 1.18-3.92, p = 0.008"
    )

    val validCheck = ReviewerCalibrationBenchmark.verifyClaimAnchor(validClaim, manuscript)
    assertTrue("Valid claim must pass strict anchor check: ${validCheck.failureReasons}", validCheck.passed)
    assertTrue(validCheck.quoteExistsExact)
    assertTrue(validCheck.pageMatches)
    assertTrue(validCheck.numericValuesPresentInQuote)
    assertTrue(validCheck.outcomeAligned)
    assertEquals(1, validCheck.occurrenceCount)
    assertTrue(validCheck.isUniqueMatch)

    // 1. Altering page number MUST drop anchor accuracy
    val wrongPageClaim = validClaim.copy(page = 2)
    val wrongPageCheck = ReviewerCalibrationBenchmark.verifyClaimAnchor(wrongPageClaim, manuscript)
    assertFalse("Altered page number must fail strict anchor check", wrongPageCheck.passed)
    assertFalse(wrongPageCheck.pageMatches)

    // 2. Altering 1 digit in estimate OR quote MUST drop anchor accuracy
    val wrongDigitClaim = validClaim.copy(pointEstimate = 2.16)
    val wrongDigitCheck = ReviewerCalibrationBenchmark.verifyClaimAnchor(wrongDigitClaim, manuscript)
    assertFalse("Altering 1 digit in numeric estimate must fail anchor check", wrongDigitCheck.passed)
    assertFalse(wrongDigitCheck.numericValuesPresentInQuote)

    val alteredQuoteClaim = validClaim.copy(sourceQuote = "OR = 2.16, 95% CI 1.18-3.92, p = 0.008")
    val alteredQuoteCheck = ReviewerCalibrationBenchmark.verifyClaimAnchor(alteredQuoteClaim, manuscript)
    assertFalse("Altering 1 digit in source quote must fail exact match", alteredQuoteCheck.passed)
    assertFalse(alteredQuoteCheck.quoteExistsExact)

    // 3. Pasting a valid exact quote from ANOTHER outcome MUST drop anchor accuracy
    val wrongOutcomeQuoteClaim = validClaim.copy(
      outcome = "12-month mortality",
      pointEstimate = 1.10,
      ciLower = 0.45,
      ciUpper = 2.68,
      pValue = 0.72,
      sourceQuote = "OR = 1.10, 95% CI 0.45-2.68, p = 0.72"
    )
    val wrongOutcomeCheck = ReviewerCalibrationBenchmark.verifyClaimAnchor(wrongOutcomeQuoteClaim, manuscript)
    assertTrue("The quote itself exists verbatim on Page 4", wrongOutcomeCheck.quoteExistsExact)
    assertFalse("Valid quote from headache sentence must fail mortality outcome alignment", wrongOutcomeCheck.outcomeAligned)
    assertFalse("Wrong-outcome quote must fail strict anchor check", wrongOutcomeCheck.passed)

    val (accMetric, _) = ReviewerCalibrationBenchmark.evaluateAnchorAccuracy(
      listOf(validClaim, wrongPageClaim, wrongDigitClaim, wrongOutcomeQuoteClaim),
      manuscript
    )
    assertEquals(1, accMetric.numerator)
    assertEquals(4, accMetric.denominator)
    assertEquals(0.25, accMetric.estimate, 1e-9)
  }

  @Test
  fun revisionA3_tenPerturbationNoiseSuite_separatesScoreAndFindingStabilityAndBlocksOcrFalseVerified() {
    val cal01 = ReviewerCalibrationBenchmark.defaultBenchmarks.first { it.caseId == "CAL-01" }
    val suiteResults = ReviewerCalibrationBenchmark.runNoisePerturbationSuite(cal01)

    // Must execute all 10 explicit perturbations
    assertEquals(10, suiteResults.size)
    assertEquals(ReviewerCalibrationBenchmark.NoisePerturbationKind.entries.toSet(), suiteResults.map { it.kind }.toSet())

    // Benign layout perturbations: critical findings and scores survive
    val benign = suiteResults.filter { it.isBenignLayoutNoise }
    assertEquals(8, benign.size)
    assertTrue(
      "All benign layout perturbations should preserve score stability: ${benign.filter { !it.scoreStable }}",
      benign.all { it.scoreStable }
    )
    assertTrue(
      "All benign layout perturbations should preserve finding stability: ${benign.filter { !it.findingStable }}",
      benign.all { it.findingStable }
    )

    // OCR corruption: ZERO new VERIFIED findings fabricated from OCR noise
    val ocrResult = suiteResults.first { it.kind == ReviewerCalibrationBenchmark.NoisePerturbationKind.OCR_CHAR_CONFUSION }
    assertEquals("OCR corruption must never fabricate new VERIFIED findings", 0, ocrResult.newVerifiedFindingsOnCorruption)
    assertTrue("OCR corruption must degrade anchor or page confidence", ocrResult.anchorOrPageConfidenceDegraded)

    // Removed page markers: anchor/page confidence must degrade
    val markerLoss = suiteResults.first { it.kind == ReviewerCalibrationBenchmark.NoisePerturbationKind.REMOVED_PAGE_MARKERS }
    assertTrue("Removing [PAGE N] markers must degrade anchor/page confidence", markerLoss.anchorOrPageConfidenceDegraded)

    // Summary must track score stability separately from finding stability
    val summary = ReviewerCalibrationBenchmark.runCalibration()
    assertEquals(1.0, summary.scoreStabilityUnderNoiseRate, 1e-9)
    assertEquals(1.0, summary.findingStabilityUnderNoiseRate, 1e-9)
    assertEquals(1.0, summary.ocrZeroFalseVerifiedRate, 1e-9)
  }

  @Test
  fun revisionA4A5_wilsonCIsThreeBenchmarkLayersAndHonestFraming() {
    val layered = ReviewerCalibrationBenchmark.runLayeredEvaluation()

    // Wilson 95% CIs valid and non-degenerate
    val pCi = layered.unitFixtures.precisionCi
    assertTrue(pCi.denominator > 0)
    assertTrue(pCi.lower95 in 0.0..pCi.estimate)
    assertTrue(pCi.upper95 in pCi.estimate..1.0)
    assertTrue("Finite-sample Wilson 95% CI should have width > 0", pCi.upper95 > pCi.lower95)

    // 3 explicit benchmark layers
    assertEquals(ReviewerCalibrationBenchmark.BenchmarkLayer.UNIT_FIXTURES, layered.unitFixtures.benchmarkLayer)
    assertEquals(ReviewerCalibrationBenchmark.BenchmarkLayer.ADVERSARIAL_FIXTURES, layered.adversarialFixtures.benchmarkLayer)
    assertEquals(ReviewerCalibrationBenchmark.BenchmarkLayer.EXTERNAL_BLINDED_CORPUS, layered.externalCorpus.layer)
    assertFalse("External blinded corpus must be explicitly marked unavailable/pending", layered.externalCorpus.isAvailable)
    assertEquals(0, layered.externalCorpus.annotatedPaperCount)

    // Honest framing: "All embedded regression fixtures passed", NOT "100% calibrated"
    assertTrue(
      "Unit fixtures should pass:\n${layered.unitFixtures.toSummaryText()}",
      layered.unitFixtures.allEmbeddedFixturesPassed
    )
    assertTrue(
      "Adversarial fixtures should pass:\n${layered.adversarialFixtures.toSummaryText()}",
      layered.adversarialFixtures.allEmbeddedFixturesPassed
    )
    assertTrue(layered.unitFixtures.honestCalibrationNotice.contains("All embedded regression fixtures passed"))
    assertTrue(layered.unitFixtures.honestCalibrationNotice.contains("NOT external blinded calibration"))
  }

  // ============================================================================
  // REVISION B: STRICT SEMANTIC MATCHING & CLAIM-SCOPED ADJUDICATION
  // ============================================================================

  @Test
  fun revisionB1_compoundClaimKey_unspecifiedIsAmbiguousNeverCompatible() {
    // 1. Two unspecified outcomes MUST be AMBIGUOUS, never COMPATIBLE
    val unspec1 = CompoundClaimKey(
      population = "adults",
      outcome = "unspecified",
      timePoint = "12-month",
      estimator = "OR",
      subgroup = "overall",
      adjustmentStatus = AdjustmentStatus.ADJUSTED
    )
    val unspec2 = unspec1.copy()
    assertEquals(ClaimCompatibilityStatus.AMBIGUOUS, unspec1.evaluateCompatibility(unspec2))
    assertFalse(unspec1.isCompatibleForComparison(unspec2))

    // 2. Same outcome + unspecified time point MUST be AMBIGUOUS
    val unknownTime1 = CompoundClaimKey(
      population = "adults",
      outcome = "mortality",
      timePoint = "unspecified",
      estimator = "OR",
      subgroup = "overall",
      adjustmentStatus = AdjustmentStatus.ADJUSTED
    )
    val unknownTime2 = unknownTime1.copy(timePoint = "12-month")
    assertEquals(ClaimCompatibilityStatus.AMBIGUOUS, unknownTime1.evaluateCompatibility(unknownTime2))
    assertEquals(ClaimCompatibilityStatus.AMBIGUOUS, unknownTime1.evaluateCompatibility(unknownTime1))

    // 3. Different adjustment contexts MUST be INCOMPATIBLE
    val adj = CompoundClaimKey(
      population = "adults",
      outcome = "mortality",
      timePoint = "12-month",
      estimator = "OR",
      subgroup = "overall",
      adjustmentStatus = AdjustmentStatus.ADJUSTED
    )
    val unadj = adj.copy(adjustmentStatus = AdjustmentStatus.UNADJUSTED)
    assertEquals(ClaimCompatibilityStatus.INCOMPATIBLE, adj.evaluateCompatibility(unadj))

    // 4. All explicitly observed matching fields MUST be COMPATIBLE
    assertEquals(ClaimCompatibilityStatus.COMPATIBLE, adj.evaluateCompatibility(adj.copy()))
    assertTrue(adj.isCompatibleForComparison(adj.copy()))

    // 5. End-to-end parser check: Abstract vs Results with unspecified time/adjustment must NOT emit CROSS_SECTION_CONTRADICTION FAIL
    val ambiguousCrossSectionDoc = """
      [PAGE 1]
      Abstract
      Primary mortality showed OR = 1.45 (95% CI 1.10-1.91, p = 0.009).
      [PAGE 4]
      Results
      Primary mortality showed OR = 2.10 (95% CI 1.50-2.94, p = 0.001).
    """.trimIndent()
    val coverage = DocumentCoverageReport.forCompleteText(ambiguousCrossSectionDoc)
    val audit = StatisticalAuditOrchestrator.audit(
      text = ambiguousCrossSectionDoc,
      parentArchetype = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
      coverageReport = coverage
    )
    val hardCrossFails = audit.crossLinks.filter {
      it.linkType == "CROSS_SECTION_CONTRADICTION" && it.status == QuantFindingStatus.FAIL
    }
    assertTrue(
      "Unspecified time-point/adjustment cross-section shift must NOT trigger hard CROSS_SECTION_CONTRADICTION FAIL",
      hardCrossFails.isEmpty()
    )
  }

  @Test
  fun revisionB2_bidirectionalAdjudicator_claimScopedDefenseDoesNotDismissPrimaryWithSecondaryOrWrongPhase() {
    // 1. Secondary endpoint sensitivity analysis MUST NOT dismiss primary mortality finding
    val secondaryDefenseManuscript = """
      [PAGE 2]
      Methods
      Primary outcome was 12-month mortality. Secondary outcome was headache score.
      [PAGE 4]
      Results
      For the secondary endpoint headache, we performed a sensitivity analysis with multiple imputation confirming stability.
      No sensitivity analysis was described for primary mortality.
    """.trimIndent()

    val primaryFinding = ReviewFinding(
      id = "F-PRIM-01",
      severity = "MAJOR",
      status = FindingStatus.PARTIALLY_SUPPORTED,
      title = "No sensitivity analysis for primary mortality outcome",
      allegation = "The study did not perform a sensitivity analysis for primary outcome 12-month mortality.",
      supportingEvidence = listOf(
        EvidenceAnchor(pageNumber = 2, section = "Methods", exactQuote = "Primary outcome was 12-month mortality.")
      )
    )
    val secondaryOut = BidirectionalAdjudicator.adjudicate(
      findings = listOf(primaryFinding),
      manuscriptText = secondaryDefenseManuscript,
      epistemicReport = null
    )
    val secondaryRec = secondaryOut.defenseRecords.firstOrNull()
    assertNotEquals(
      "Secondary-endpoint sensitivity analysis must NEVER dismiss a primary-endpoint finding",
      DefenseOutcome.DISMISSED_FALSE_POSITIVE,
      secondaryRec?.outcome
    )
    assertNotEquals(FindingStatus.CONTRADICTED, secondaryOut.findings.first().status)

    // 2. Phase 1 allocation concealment MUST NOT dismiss Phase 2 allocation finding
    val phaseMismatchManuscript = """
      [PAGE 2]
      Methods
      In our earlier Phase 1 pilot cohort, allocation concealment used opaque sealed envelopes.
      In Phase 2, participants were assigned by clinic day without concealment details.
    """.trimIndent()
    val phase2Finding = ReviewFinding(
      id = "F-PH2-01",
      severity = "MAJOR",
      status = FindingStatus.PARTIALLY_SUPPORTED,
      title = "Missing allocation concealment in Phase 2 trial",
      allegation = "Phase 2 did not report allocation concealment.",
      supportingEvidence = listOf(
        EvidenceAnchor(pageNumber = 2, section = "Methods", exactQuote = "In Phase 2, participants were assigned by clinic day")
      )
    )
    val phaseOut = BidirectionalAdjudicator.adjudicate(
      findings = listOf(phase2Finding),
      manuscriptText = phaseMismatchManuscript,
      epistemicReport = null
    )
    assertNotEquals(
      "Phase 1 defense must not dismiss Phase 2 finding",
      DefenseOutcome.DISMISSED_FALSE_POSITIVE,
      phaseOut.defenseRecords.firstOrNull()?.outcome
    )
    assertNotEquals(FindingStatus.CONTRADICTED, phaseOut.findings.first().status)

    // 3. Defense inside References MUST NOT dismiss a body finding
    val refOnlyDefenseDoc = """
      [PAGE 2]
      Methods
      Primary outcome was mortality in 200 adults.
      [PAGE 6]
      References
      1. Prior Study (2022). Sensitivity analysis using multiple imputation for primary mortality.
    """.trimIndent()
    val refOut = BidirectionalAdjudicator.adjudicate(
      findings = listOf(primaryFinding),
      manuscriptText = refOnlyDefenseDoc,
      epistemicReport = null
    )
    assertNotEquals(
      "Reference-section text must never dismiss a body finding",
      DefenseOutcome.DISMISSED_FALSE_POSITIVE,
      refOut.defenseRecords.firstOrNull()?.outcome
    )
    assertNotEquals(FindingStatus.CONTRADICTED, refOut.findings.first().status)

    // 4. Matching primary-endpoint defense in body DOES dismiss false absence finding
    val matchingPrimaryDefenseDoc = """
      [PAGE 2]
      Methods
      Primary outcome was 12-month mortality. For primary 12-month mortality we conducted a sensitivity analysis using multiple imputation.
    """.trimIndent()
    val matchOut = BidirectionalAdjudicator.adjudicate(
      findings = listOf(primaryFinding),
      manuscriptText = matchingPrimaryDefenseDoc,
      epistemicReport = null
    )
    assertEquals(DefenseOutcome.DISMISSED_FALSE_POSITIVE, matchOut.defenseRecords.first().outcome)
    assertEquals(FindingStatus.CONTRADICTED, matchOut.findings.first().status)
  }

  // ============================================================================
  // REVISION C: MANDATORY COVERAGE PROPAGATION
  // ============================================================================

  @Test
  @Suppress("DEPRECATION")
  fun revisionC_mandatoryCoveragePropagation_unknownCoverageNeverDefaultsToFive() {
    val cleanRct = ReviewerCalibrationBenchmark.defaultBenchmarks.first { it.caseId == "CAL-02" }.manuscriptText

    // Calling without explicit DocumentCoverageReport MUST resolve to UNKNOWN / 0 / strongConclusionsDisabled = true
    val unknownReport = QuantitativeForensicAnalyzer.analyze(
      text = cleanRct,
      studyDesign = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
      sampleSizes = listOf(240)
    )
    val unknownEpi = unknownReport.epistemicSafeguards!!
    assertEquals(CoverageStatus.UNKNOWN, unknownEpi.stopAndAsk.coverageStatus)
    assertTrue("Strong conclusions must be disabled when coverage is UNKNOWN", unknownEpi.stopAndAsk.strongConclusionsDisabled)

    val extBudgetItem = unknownEpi.uncertaintyBudget.items.first { it.source == UncertaintySource.EXTRACTION_QUALITY }
    assertEquals("UNASSESSABLE", extBudgetItem.level)
    assertEquals(ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL, extBudgetItem.reportingStatus)
    assertFalse("Must never silently claim 5/5 when coverage is unknown", extBudgetItem.note.contains("5/5"))

    // Explicit complete coverage propagates COMPLETE and enables normal appraisal
    val completeCov = DocumentCoverageReport.forCompleteText(cleanRct, engine = "Unit Test Complete Text")
    val completeReport = QuantitativeForensicAnalyzer.analyze(
      text = cleanRct,
      studyDesign = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
      sampleSizes = listOf(240),
      coverageReport = completeCov,
      extractionConfidence = completeCov.extractionConfidence
    )
    val completeEpi = completeReport.epistemicSafeguards!!
    assertEquals(CoverageStatus.COMPLETE, completeEpi.stopAndAsk.coverageStatus)
    assertFalse(completeEpi.stopAndAsk.strongConclusionsDisabled)
  }

  // ============================================================================
  // REVISION D: CALIBRATED DESIGN CLASSIFIER
  // ============================================================================

  @Test
  fun revisionD_calibratedDesignClassifier_stripsReferencesSeparatesSubDesignsAndMeasuresEce() {
    // 1. Cohort paper mentioning RCT in Introduction prior work & References must NOT classify as RCT
    val cohortWithRctRefs = """
      [PAGE 1]
      Introduction
      Prior work by Smith et al. (2020) conducted a double-blind randomized controlled trial with placebo allocation.
      [PAGE 2]
      Methods
      In this prospective cohort study following STROBE guidelines, 1,200 adults were followed longitudinally over 5 years using propensity score matching to estimate adjusted hazard ratios.
      [PAGE 5]
      References
      1. Jones et al. (2019). CONSORT randomized controlled trial of double-blind placebo intervention with intention-to-treat analysis.
    """.trimIndent()
    val cohortCls = SemanticValidityEngine.classifyDesignWithConfidence(
      cohortWithRctRefs,
      StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL
    )
    assertEquals(CalibratedStudyDesign.PROSPECTIVE_COHORT, cohortCls.calibratedDesign)
    assertNotEquals(CalibratedStudyDesign.RCT, cohortCls.calibratedDesign)
    assertTrue("Calibrated probability must be in (0, 1]", cohortCls.primaryConfidence in 0.01..1.0)
    assertTrue("Margin must be non-negative", cohortCls.margin >= 0.0)

    // 2. Isolated "benchmark" or "proof of concept" without positive design bundle must NOT classify as ML or Formal Math
    val isolatedBuzzwords = """
      [PAGE 1]
      Overview
      This policy commentary establishes a hospital benchmark for staffing ratios as a proof of concept for regional planning.
    """.trimIndent()
    val buzzCls = SemanticValidityEngine.classifyDesignWithConfidence(
      isolatedBuzzwords,
      StudyDesignArchetype.GENERAL_EMPIRICAL
    )
    assertTrue("Isolated buzzwords must abstain or classify UNKNOWN", buzzCls.abstained || buzzCls.calibratedDesign == CalibratedStudyDesign.UNKNOWN)
    assertNotEquals(CalibratedStudyDesign.ML_PREDICTION_BENCHMARK, buzzCls.calibratedDesign)
    assertNotEquals(CalibratedStudyDesign.FORMAL_MATHEMATICAL_PROOF, buzzCls.calibratedDesign)

    // 3. Mutually exclusive CASE_CONTROL vs CROSS_SECTIONAL vs PROSPECTIVE_COHORT
    val caseControlDoc = """
      [PAGE 1]
      Methods
      A matched case-control study (STROBE) compared 250 cases with pancreatic cancer against 500 age-matched controls to estimate conditional logistic odds ratios.
    """.trimIndent()
    val crossSecDoc = """
      [PAGE 1]
      Methods
      A nationally representative cross-sectional survey (STROBE) evaluated point prevalence of hypertension in 8,000 adults at a single time point.
    """.trimIndent()
    assertEquals(
      CalibratedStudyDesign.CASE_CONTROL,
      SemanticValidityEngine.classifyDesignWithConfidence(caseControlDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL).calibratedDesign
    )
    assertEquals(
      CalibratedStudyDesign.CROSS_SECTIONAL,
      SemanticValidityEngine.classifyDesignWithConfidence(crossSecDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL).calibratedDesign
    )

    // 4. Classifier calibration evaluation measures overallAccuracy, highConfidenceErrorRate, and ECE
    val calReport = SemanticValidityEngine.evaluateDesignClassifierCalibration()
    assertTrue(calReport.totalExamples >= 10)
    assertTrue("Calibration accuracy should be >= 0.85, got ${calReport.overallAccuracy}", calReport.overallAccuracy >= 0.85)
    assertTrue("High-confidence error rate should be <= 0.10, got ${calReport.highConfidenceErrorRate}", calReport.highConfidenceErrorRate <= 0.10)
    assertTrue("ECE must be bounded in [0, 1]", calReport.expectedCalibrationError in 0.0..1.0)
  }

  // ============================================================================
  // REVISION E: 6-STATE METHOD CHECKS, NON-GENERIC PRACTICAL SIGNIFICANCE & LAYOUT-AWARE PDF DEDUP
  // ============================================================================

  @Test
  fun revisionE1_structuredSixStateMethodChecksAndNonGenericPracticalSignificance() {
    // 1. Negative controls must be NOT_APPLICABLE for RCT and ML benchmarks
    val rctText = ReviewerCalibrationBenchmark.defaultBenchmarks.first { it.caseId == "CAL-02" }.manuscriptText
    val rctCov = DocumentCoverageReport.forCompleteText(rctText)
    val rctAudit = StatisticalAuditOrchestrator.audit(rctText, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL, rctCov)
    val negCtrlRct = rctAudit.semanticValidity!!.structuredMethodChecks.first { it.checkId == "SMC-FALSIF" }
    assertEquals(MethodologicalAssessmentStatus.NOT_APPLICABLE, negCtrlRct.assessmentStatus)

    val mlText = ReviewerCalibrationBenchmark.defaultBenchmarks.first { it.caseId == "CAL-04" }.manuscriptText
    val mlCov = DocumentCoverageReport.forCompleteText(mlText)
    val mlAudit = StatisticalAuditOrchestrator.audit(mlText, StudyDesignArchetype.MACHINE_LEARNING_AI, mlCov)
    val negCtrlMl = mlAudit.semanticValidity!!.structuredMethodChecks.first { it.checkId == "SMC-FALSIF" }
    assertEquals(MethodologicalAssessmentStatus.NOT_APPLICABLE, negCtrlMl.assessmentStatus)

    // 2. Non-generic practical significance gate: OR = 1.02 without baseline risk/MID -> NOT_ASSESSABLE + required context message
    val cal03Text = ReviewerCalibrationBenchmark.defaultBenchmarks.first { it.caseId == "CAL-03" }.manuscriptText
    val cal03Cov = DocumentCoverageReport.forCompleteText(cal03Text)
    val cal03Forensic = ManuscriptForensicEngine.analyzeManuscript(cal03Text, true, coverageReport = cal03Cov)
    val practicalGate = cal03Forensic.quantForensics.epistemicSafeguards!!.practicalSignificanceGate
    assertTrue(practicalGate.tinyButSignificantFlag)
    assertEquals(MethodologicalAssessmentStatus.NOT_ASSESSABLE, practicalGate.practicalImportanceStatus)
    assertTrue(
      "Must emit non-generic baseline risk/MID disclosure instead of calling effect 'trivial'",
      practicalGate.requiredContextNote.contains("Small relative effect detected; absolute and domain-specific importance cannot be assessed without baseline risk/MID.")
    )
    assertFalse("Must not label small effect generically 'trivial'", practicalGate.clinicalOrPolicyUtilitySummary.contains("trivial", ignoreCase = true))

    // 3. NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL preserved through JSON codec & never triggers hard VERIFIED verdict downgrade
    val naFinding = ReviewFinding(
      id = "F-NA-01",
      severity = "CRITICAL",
      status = FindingStatus.VERIFIED,
      title = "Unextractable supplementary regression table",
      allegation = "Table S4 standard errors were not included in the supplied PDF.",
      reportingStatus = "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL",
      supportingEvidence = listOf(
        EvidenceAnchor(pageNumber = 1, section = "Abstract", exactQuote = "A preregistered double-blind randomized controlled trial")
      )
    )
    val roundTripped = ReviewFindingCodec.decode(ReviewFindingCodec.encode(listOf(naFinding)))
    assertEquals("NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL", roundTripped.first().reportingStatus)

    val adj = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = listOf(naFinding),
      manuscriptText = rctText,
      forensicReport = ManuscriptForensicEngine.analyzeManuscript(rctText, true, coverageReport = rctCov),
      methodologyScore = 9,
      significanceScore = 9,
      clarityScore = 9,
      originalityScore = 9,
      mathRigorScore = 9,
      reproducibilityScore = 9,
      rawVerdict = "ACCEPT",
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      extractionConfidence = 5,
      applyStatisticalScoreGate = false
    )
    assertEquals(
      "A NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL finding must never silently collapse into a hard FAIL/MAJOR_REVISION downgrade",
      "ACCEPT",
      adj.resolvedVerdict
    )
  }

  @Test
  fun revisionE2_layoutAwarePdfDedup_preservesNonAdjacentPlainTextAndDeletesLayoutConfirmedDuplicates() {
    // 1. Non-adjacent identical lines in plain text (without layout confirmation) MUST be preserved & flagged
    val repeatedLine = "All models adjusted for age, sex, body mass index, and baseline systolic blood pressure."
    val plainNonAdjacent = """
      [PAGE 2]
      Methods
      $repeatedLine
      Table 1 summarizes cohort characteristics at baseline.
      $repeatedLine
    """.trimIndent()
    val plainSanitized = SemanticValidityEngine.sanitizePdfExtractionNoise(plainNonAdjacent)
    assertEquals(
      "Plain-text non-adjacent repeated line must NOT be silently deleted",
      0,
      plainSanitized.duplicatedColumnsRemoved
    )
    assertEquals(
      2,
      plainSanitized.text.lines().count { it.trim() == repeatedLine }
    )
    assertTrue(
      "Non-adjacent repeated plain-text line must be tagged in suspectedDuplications",
      plainSanitized.suspectedDuplications.any { it.excerpt.contains("All models adjusted") && !it.wasRemoved }
    )

    // 2. Immediately consecutive duplicate line on same page IS deduplicated
    val consecutiveDup = """
      [PAGE 2]
      $repeatedLine
      $repeatedLine
    """.trimIndent()
    val consecSanitized = SemanticValidityEngine.sanitizePdfExtractionNoise(consecutiveDup)
    assertEquals(1, consecSanitized.duplicatedColumnsRemoved)
    assertEquals(1, consecSanitized.text.lines().count { it.trim() == repeatedLine })

    // 3. Layout-metadata-confirmed same-page & same-column duplicate IS deduplicated
    val layoutMeta = listOf(
      PdfLineLayoutMetadata(pageNumber = 2, columnIndex = 0, x = 48f, y = 120f, width = 460f, height = 12f),
      PdfLineLayoutMetadata(pageNumber = 2, columnIndex = 0, x = 48f, y = 136f, width = 460f, height = 12f),
      PdfLineLayoutMetadata(pageNumber = 2, columnIndex = 0, x = 48f, y = 152f, width = 460f, height = 12f),
      PdfLineLayoutMetadata(pageNumber = 2, columnIndex = 0, x = 48f, y = 168f, width = 460f, height = 12f),
      PdfLineLayoutMetadata(pageNumber = 2, columnIndex = 0, x = 48f, y = 184f, width = 460f, height = 12f)
    )
    val layoutSanitized = SemanticValidityEngine.sanitizePdfExtractionNoise(plainNonAdjacent, layoutMeta)
    assertEquals(
      "Layout-confirmed same-page/same-column duplicate should be deduplicated",
      1,
      layoutSanitized.duplicatedColumnsRemoved
    )
    assertEquals(1, layoutSanitized.text.lines().count { it.trim() == repeatedLine })
  }
}
