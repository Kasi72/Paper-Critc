package com.example

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.epistemic.ReviewerCalibrationBenchmark
import com.example.data.ai.semantic.AnalysisPopulationType
import com.example.data.ai.semantic.CompoundClaimKey
import com.example.data.ai.semantic.DenominatorUnitLevel
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.data.ai.semantic.UnsupportedCause
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.StatisticalAuditOrchestrator
import com.example.data.ai.stats.StatisticalFindingType
import com.example.util.DocumentCoverageReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8 — Trust, Abstention & Semantic Validity Verification Suite.
 *
 * Covers all 12 semantic-validity upgrades, all 15 adversarial test cases,
 * and the expanded diagnostic calibration benchmark metrics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase8TrustAbstentionSemanticTest {

  // ============================================================================
  // UPGRADE #1 + ADVERSARIAL CASE #5 & #14:
  // Separate extraction failure from manuscript omission (OCR, scanned tables, supplement)
  // ============================================================================
  @Test
  fun upgrade01_and_adversarial05_14_separatesExtractionFailureFromManuscriptOmission() {
    // Case A: OCR-confused digits (p = O.04, OR = l.45) + replacement glyphs
    val ocrCorrupted = """
      [PAGE 1]
      Methods
      A prospective cohort study evaluated mortality in 200 patients.
      Results
      Primary estimate showed OR = l.45 (95% CI 1.05-2.01), p = O.04, with corrupted symbol \uFFFD\uFFFD.
    """.trimIndent()
    val ocrAudit = StatisticalAuditOrchestrator.audit(ocrCorrupted, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val ocrSv = ocrAudit.semanticValidity!!
    assertTrue("OCR digit confusion must be detected", ocrSv.ocrNoiseDetected)
    assertTrue(
      "OCR noise must be classified as PRESENT_BUT_UNREADABLE extraction failure",
      ocrSv.structuredUnsupported.any { it.cause == UnsupportedCause.PRESENT_BUT_UNREADABLE && it.cause.isExtractionFailure }
    )
    assertEquals(
      "OCR-corrupted digits must NEVER fabricate a verified arithmetic error",
      0,
      ocrAudit.verifiedArithmeticErrorCount
    )
    assertTrue(
      "Abstention policy must trigger on unreadable/OCR-corrupted numeric values",
      ocrSv.abstentionAssessment.shouldAbstainFromStrongConclusions
    )

    // Case B: Intentional omission deferred to Supplementary Table / abstract-only
    val deferredSupplement = """
      [PAGE 1]
      Abstract
      We examined 500 adults in a retrospective cohort study. Odds ratio for hospitalization was 1.40 (95% CI 1.10-1.78).
      Detailed 2x2 counts and regression standard errors are reported in Supplementary Table S2.
    """.trimIndent()
    val suppAudit = StatisticalAuditOrchestrator.audit(deferredSupplement, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val suppSv = suppAudit.semanticValidity!!
    assertTrue(
      "External supplement omission must be tagged OUTSIDE_COVERAGE",
      suppSv.structuredUnsupported.any { it.cause == UnsupportedCause.OUTSIDE_COVERAGE }
    )
    assertTrue(
      "Abstract-only excerpt must trigger abstention policy",
      suppSv.abstentionAssessment.shouldAbstainFromStrongConclusions &&
        suppSv.abstentionAssessment.reasons.any { it.code == "ABSTRACT_ONLY" }
    )

    // Case C: Full text omits regression SEs with no supplement/scan
    val absentSeDoc = """
      [PAGE 1]
      Methods
      A retrospective cohort of 300 patients used multivariable logistic regression for 30-day readmission.
      Results
      Exposure was associated with readmission (p = 0.02).
    """.trimIndent()
    val absentAudit = StatisticalAuditOrchestrator.audit(absentSeDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertTrue(
      "Genuine manuscript omission of regression SEs must be tagged ABSENT_FROM_MANUSCRIPT",
      absentAudit.semanticValidity!!.structuredUnsupported.any { it.cause == UnsupportedCause.ABSENT_FROM_MANUSCRIPT && !it.cause.isExtractionFailure }
    )
  }

  // ============================================================================
  // UPGRADE #2 + ADVERSARIAL CASE #1:
  // Classifier-confidence gate abstains on ambiguous/wrong-cue designs and suppresses FAILs
  // ============================================================================
  @Test
  fun upgrade02_and_adversarial01_classifierConfidenceGateSuppressesDesignSensitiveFails() {
    // Deliberately mixed / ambiguous study cues (mentions randomized per-protocol + cohort + machine learning benchmark lightly)
    val ambiguousDoc = """
      [PAGE 1]
      Methods
      This hybrid evaluation combined a retrospective cohort with a randomized per-protocol subset and a benchmark classifier.
      Primary analysis used per-protocol exclusions.
      Results
      Observed association was modest across 180 participants.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(ambiguousDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val cls = audit.designClassification!!
    assertTrue("Ambiguous mixed-cue manuscript must trigger classifier abstention", cls.abstained)
    assertTrue("Alternative design candidates must be reported", cls.alternatives.isNotEmpty())
    assertTrue("Suppressed checks must be listed", cls.suppressedChecks.isNotEmpty())
    assertEquals(
      "Design-sensitive MODEL_MISMATCH FAILs must be suppressed (downgraded to WARN) when classifier abstains",
      0,
      audit.domainFindings.count { it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.MODEL_MISMATCH }
    )
  }

  // ============================================================================
  // UPGRADE #3 + ADVERSARIAL CASE #13:
  // Non-dogmatic estimand-first checks & valid uncommon statistical methods
  // ============================================================================
  @Test
  fun upgrade03_and_adversarial13_antiDogmaEstimandAndValidUncommonMethods() {
    // Etiologic cause-specific Cox + RMST + Firth penalized likelihood + Barnard exact test
    val uncommonRobustDoc = """
      [PAGE 1]
      Abstract
      A prospective time-to-event cohort study of 320 patients evaluated an etiologic cause-specific hazard estimand for cardiac death, treating non-cardiac death via cause-specific hazard modeling alongside restricted mean survival time (RMST).
      [PAGE 3]
      Methods
      Proportional hazards assumption was checked with Schoenfeld residuals. Sparse binary outcomes were estimated via Firth penalized likelihood and Barnard's exact test. Median follow-up via reverse Kaplan-Meier was 3.4 years.
      [PAGE 4]
      Results
      Cause-specific HR = 0.72 (95% CI 0.56-0.93), p = 0.012. RMST difference at 3 years was +4.2 months (95% CI 1.1 to 7.3).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(uncommonRobustDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val sv = audit.semanticValidity!!
    assertEquals(
      "Valid etiologic cause-specific hazard + RMST + Firth + Barnard must incur ZERO model-mismatch FAILs",
      0,
      audit.verifiedModelMismatchCount
    )
    assertTrue(
      "Anti-dogma estimand assessment must explicitly distinguish cause-specific vs Fine-Gray questions",
      sv.estimandAssessments.any { !it.alternativeAnswersSameQuestion && it.methodTopic.contains("Competing Risks") }
    )

    // Ordinary finite integral in formal math must NOT trigger DCT/MCT dogma
    val elementaryMathDoc = """
      Theorem 1. Let f(x) = x^2 be continuous on [0, 1].
      Proof. The elementary finite integral of f(x) from 0 to 1 equals 1/3. Q.E.D.
    """.trimIndent()
    val mathAudit = StatisticalAuditOrchestrator.audit(elementaryMathDoc, StudyDesignArchetype.THEORETICAL_FORMAL)
    assertEquals(
      "Ordinary finite integral without limit/sum interchange must NOT fail for missing DCT/MCT",
      0,
      mathAudit.domainFindings.count { it.status == QuantFindingStatus.FAIL }
    )
    assertTrue(
      "Estimand check should note ordinary integrals do not require DCT/MCT/Fubini",
      mathAudit.semanticValidity!!.estimandAssessments.any { it.verdictNote.contains("Ordinary integrals", true) }
    )
  }

  // ============================================================================
  // UPGRADE #4 + ADVERSARIAL CASES #8, #9, #10, #11:
  // Assumption-explicit calculators, zero-event 2x2, NNT crossing zero, negative/infinite ratios, non-independent meta effects
  // ============================================================================
  @Test
  fun upgrade04_and_adversarial08_09_10_11_calculatorContractsAndEdgeCases() {
    // Adversarial #8a: Double-zero events (0/50 vs 0/50) -> UNSUPPORTED_TO_ASSESS (no fabricated OR)
    val doubleZeroDoc = """
      [PAGE 1]
      Results
      Severe anaphylaxis occurred in 0/50 vs 0/50 patients (OR = 1.00).
    """.trimIndent()
    val dzAudit = StatisticalAuditOrchestrator.audit(doubleZeroDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val dzRecon = dzAudit.reconstructions.first { it.calculatorName.contains("Double-Zero") }
    assertEquals(StatisticalFindingType.UNSUPPORTED_TO_ASSESS, dzRecon.findingType)
    assertNotNull("Every reconstruction must carry an explicit assumption contract", dzRecon.assumptionContract)
    assertEquals(0, dzAudit.verifiedArithmeticErrorCount)

    // Adversarial #8b: Single zero cell (0/50 vs 8/50) -> continuity-correction warning, NOT false arithmetic error
    val singleZeroDoc = """
      [PAGE 1]
      Results
      Adverse events occurred in 0/50 vs 8/50 patients (OR = 0.05, p = 0.006).
    """.trimIndent()
    val szAudit = StatisticalAuditOrchestrator.audit(singleZeroDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(0, szAudit.verifiedArithmeticErrorCount)
    assertTrue(
      "Single zero-cell contract must disclose exact MLE / Peto / Firth alternatives",
      szAudit.reconstructions.any { r ->
        r.assumptionContract?.alternativeValidInterpretations?.any { it.contains("Peto", true) || it.contains("Firth", true) } == true
      }
    )

    // Adversarial #9: NNT crossing zero (10/100 vs 12/100 -> ARR = 2%, 95% CI spans 0)
    val nntCrossZeroDoc = """
      [PAGE 1]
      Results
      Stroke occurred in 10/100 vs 12/100 patients (NNT = 50, p = 0.65).
    """.trimIndent()
    val nntAudit = StatisticalAuditOrchestrator.audit(nntCrossZeroDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val nntRecon = nntAudit.reconstructions.first { it.calculatorName.contains("NNT") }
    assertTrue(
      "NNT crossing zero must disclose NNTB to ∞ to NNTH discontinuity",
      nntRecon.recomputedValue.contains("∞") && nntRecon.detail.contains("crosses zero", true)
    )
    assertEquals(0, nntAudit.verifiedArithmeticErrorCount)

    // Adversarial #10: Negative or zero ratio estimate (OR = -0.50, HR = 0.00)
    val negRatioDoc = """
      [PAGE 1]
      Results
      Primary analysis reported OR = -0.50 (95% CI -1.10 to 0.20) and HR = 0.00 (95% CI 0.00 to 0.40).
    """.trimIndent()
    val negAudit = StatisticalAuditOrchestrator.audit(negRatioDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertTrue("Negative/zero multiplicative ratios must be caught as ARITHMETIC_ERROR", negAudit.verifiedArithmeticErrorCount >= 1)
    assertTrue(
      "Positivity failure must include assumption contract noting log-scale confusion / separation",
      negAudit.reconstructions.any { it.assumptionContract?.assumptionsUnknown?.any { a -> a.contains("log-scale", true) } == true }
    )

    // Adversarial #11: Non-independent meta-analysis effects (multiple effect sizes per study)
    val nonIndepMetaDoc = """
      [PAGE 1]
      Methods
      This random-effects meta-analysis extracted multiple effect sizes per study with correlated outcomes across 4 reports.
      Results
      Alpha 2020: OR = 1.45 (1.10-1.91)
      Alpha 2020b: OR = 1.38 (1.04-1.83)
      Beta 2021: OR = 1.62 (1.20-2.18)
      Gamma 2022: OR = 1.25 (0.94-1.66)
    """.trimIndent()
    val metaAudit = StatisticalAuditOrchestrator.audit(nonIndepMetaDoc, StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS)
    assertTrue(
      "Non-independent meta-analysis effects must trigger Effect-Size Independence Guard",
      metaAudit.reconstructions.any { it.calculatorName.contains("Independence Guard") && it.findingType == StatisticalFindingType.ASSUMPTION_UNCHECKED }
    )
  }

  // ============================================================================
  // UPGRADE #5 + ADVERSARIAL CASE #2:
  // Compound evidence-to-claim key prevents false contradictions across populations/subgroups/time points
  // ============================================================================
  @Test
  fun upgrade05_and_adversarial02_compoundClaimKeyPreventsFalseContradictions() {
    // Identically named outcome ("mortality") with identical CI bounds in two DIFFERENT populations (pediatric vs older adults)
    val diffPopDoc = """
      [PAGE 1]
      Abstract
      In the pediatric cohort, 30-day mortality had OR = 1.50 (95% CI 1.10-2.10).
      [PAGE 4]
      Results
      In the older adults cohort, 30-day mortality had OR = 1.85 (95% CI 1.10-2.10).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(diffPopDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(
      "Two outcomes with identical name in different populations (pediatric vs older adults) must NOT trigger CROSS_SECTION_CONTRADICTION",
      0,
      audit.crossLinks.count { it.linkType == "CROSS_SECTION_CONTRADICTION" }
    )

    // Verify CompoundClaimKey compatibility semantics directly
    val kPed = CompoundClaimKey(population = "pediatric", outcome = "mortality", timePoint = "30-day", estimator = "or", subgroup = "overall", adjustmentStatus = AdjustmentStatus.ADJUSTED)
    val kOld = kPed.copy(population = "older_adults")
    val kUnadj = kPed.copy(adjustmentStatus = AdjustmentStatus.UNADJUSTED)
    val k1Yr = kPed.copy(timePoint = "1-year")
    assertFalse("Different populations must be incompatible", kPed.isCompatibleForComparison(kOld))
    assertFalse("Adjusted vs unadjusted must be incompatible", kPed.isCompatibleForComparison(kUnadj))
    assertFalse("Different time points must be incompatible", kPed.isCompatibleForComparison(k1Yr))
    assertTrue("Same compound key must be compatible", kPed.isCompatibleForComparison(kPed))
  }

  // ============================================================================
  // UPGRADE #6 + ADVERSARIAL CASE #3:
  // Denominator provenance graph with multiple denominators & mixed units (patients vs eyes/lesions)
  // ============================================================================
  @Test
  fun upgrade06_and_adversarial03_denominatorProvenanceGraphTracksMixedUnitsAndTransitions() {
    val mixedUnitsDoc = """
      [PAGE 1]
      Methods
      500 patients were screened for eligibility; 300 patients were enrolled and 280 patients were randomized in an intention-to-treat trial.
      Overall, 270 patients received treatment and 250 patients were analyzed after 20 patients were lost to follow-up.
      Results
      Ophthalmic imaging evaluated 480 eyes and 620 lesions across the analyzed cohort.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(mixedUnitsDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val graph = audit.semanticValidity!!.denominatorGraph
    assertEquals(AnalysisPopulationType.INTENTION_TO_TREAT, graph.populationType)
    assertTrue("Must detect PERSON + EYE + LESION units", graph.hasMixedUnits)
    assertTrue(DenominatorUnitLevel.PERSON in graph.unitLevelsDetected)
    assertTrue(DenominatorUnitLevel.EYE in graph.unitLevelsDetected)
    assertTrue(DenominatorUnitLevel.LESION in graph.unitLevelsDetected)
    assertNotNull("Must emit explicit unit-of-analysis conflict warning", graph.unitLevelConflictWarning)
    assertTrue("Must reconstruct transition edges (eligible->enrolled->randomized->treated->analyzed)", graph.transitions.size >= 3)
  }

  // ============================================================================
  // UPGRADE #7 + ADVERSARIAL CASES #4, #6, #7, #12:
  // Multiplicity-family uncertainty, citation p-values, duplicated PDF columns, one-sided & adjusted p-values
  // ============================================================================
  @Test
  fun upgrade07_and_adversarial04_06_07_12_multiplicityPdfDedupCitationsAndAdjustedPValues() {
    // Adversarial #12: Ambiguous ungrouped p-values without declared family -> refuse to fabricate Bonferroni/FDR!
    val ambiguousFamilyDoc = """
      [PAGE 1]
      Results
      Exploratory measurements showed p = 0.012, p = 0.024, p = 0.038, p = 0.041, and p = 0.19 across miscellaneous tests.
    """.trimIndent()
    val ambAudit = StatisticalAuditOrchestrator.audit(ambiguousFamilyDoc, StudyDesignArchetype.GENERAL_EMPIRICAL)
    val multAssess = ambAudit.semanticValidity!!.multiplicityAssessment
    assertFalse("Ambiguous family must NOT be marked reconstructable", multAssess.familyReconstructable)
    assertTrue("Must report potential inflation risk", multAssess.potentialInflationRisk)
    assertFalse("Must refuse to compute fabricated corrected p-values", multAssess.correctedPValuesComputable)
    assertTrue(
      "Reconstruction must emit UNSUPPORTED_TO_ASSESS non-fabrication notice",
      ambAudit.reconstructions.any {
        it.findingType == StatisticalFindingType.UNSUPPORTED_TO_ASSESS &&
          it.detail.contains("Corrected p-values: not computable", true)
      }
    )

    // Adversarial #4: Citations with misleading p-values are isolated
    val citationDoc = """
      [PAGE 1]
      Introduction
      Smith et al. (2020) previously reported t(28) = 1.10, p = 0.001, and Jones et al. (2021) found p = 0.002, p = 0.003, p = 0.004.
      [PAGE 2]
      Results
      Our primary outcome showed t(40) = 2.42, p = 0.020.
    """.trimIndent()
    val citeAudit = StatisticalAuditOrchestrator.audit(citationDoc, StudyDesignArchetype.GENERAL_EMPIRICAL)
    assertTrue("Cited prior p-values must be isolated", citeAudit.excludedReferenceClaimCount >= 1)
    assertEquals("Corrupted p-value inside a prior citation must NOT trigger an arithmetic error", 0, citeAudit.verifiedArithmeticErrorCount)

    // Adversarial #6: Duplicated text from 2-column PDF extraction -> deduplicated, zero double-counting!
    val dupLine = "The primary clinical outcome in the randomized arm showed t(48) = 2.50, p = 0.016 across 50 patients."
    val duplicatedColumnDoc = """
      [PAGE 1]
      Results
      $dupLine
      $dupLine
    """.trimIndent()
    val dupAudit = StatisticalAuditOrchestrator.audit(duplicatedColumnDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertTrue("Duplicated PDF column line must be removed", dupAudit.semanticValidity!!.duplicatedColumnsRemoved >= 1)
    assertEquals(
      "Deduplicated t-test must only be counted once",
      1,
      dupAudit.claims.count { it.testStatisticName == "t" }
    )

    // Adversarial #7: One-sided AND multiplicity-adjusted p-values honored without false ARITHMETIC_ERROR!
    val oneSidedAndAdjustedDoc = """
      [PAGE 1]
      Methods
      Primary hypothesis used a prespecified one-sided test; secondary biomarkers used Bonferroni-adjusted p-values.
      [PAGE 2]
      Results
      Primary one-sided t(28) = 1.75, p = 0.045.
      Secondary Bonferroni-adjusted p-value: z = 2.58, p = 0.049.
    """.trimIndent()
    val osaAudit = StatisticalAuditOrchestrator.audit(oneSidedAndAdjustedDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(
      "Valid one-sided p (0.045) and Bonferroni-adjusted p (0.049 >= raw 0.0099) must produce ZERO arithmetic FAILs",
      0,
      osaAudit.verifiedArithmeticErrorCount
    )
  }

  // ============================================================================
  // UPGRADES #8, #9, #10, #11:
  // Analyst DoF (11 dimensions), Substantive Missing-Data Audit, Falsification Checks, 8 Effect-Scale Sanity Checks
  // ============================================================================
  @Test
  fun upgrades08_09_10_11_dofMissingDataFalsificationAndAllEightEffectScaleChecks() {
    val richAuditDoc = """
      [PAGE 1]
      Methods
      A retrospective cohort study used stepwise backward elimination for covariate selection, dichotomized at the median split,
      removed outliers ("outliers were removed"), and applied multiple imputation for missing data without reporting auxiliary variables or MNAR sensitivity.
      Negative control outcomes were not assessed.
      [PAGE 2]
      Results
      1. Odds ratio = 2.20 gave an additive increase of 20 percentage points.
      2. Odds ratio = 2.40 meant patients were 2.4 times more likely to experience hospitalization.
      3. Treatment achieved a relative risk reduction of 35%.
      4. For stroke, HR = 1.05 (95% CI 0.50-2.10), proving no association between drug and stroke.
      5. Because p = 0.42, the two regimens are equivalent in efficacy.
      6. Effect was significant in men (p = 0.02) and not significant in women (p = 0.28).
      7. Age showed effect modification with a stronger effect in older patients.
      8. Biomarker A had a stronger association due to its smaller p-value (p = 0.001 vs p = 0.03).
    """.trimIndent()

    val audit = StatisticalAuditOrchestrator.audit(richAuditDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val sv = audit.semanticValidity!!

    // #8 Analyst DoF
    assertEquals(11, sv.researcherDofReport.dimensions.size)
    assertTrue("Must flag elevated flexibility dimensions (covariates, outliers, thresholds, subgroups)", sv.researcherDofReport.elevatedCount >= 3)

    // #9 Substantive Missing-Data Audit: bare "multiple imputation" is NOT automatically adequate!
    assertTrue(sv.missingDataAudit.multipleImputationUsed)
    assertEquals("PARTIAL_MI_UNDERSPECIFIED", sv.missingDataAudit.substantiveAdequacy)
    assertTrue(sv.missingDataAudit.summary.contains("NOT automatically adequate", true))

    // #10 Negative-control & falsification checks
    assertTrue(sv.falsificationChecks.applicableToDesign)
    assertEquals(0, sv.falsificationChecks.totalPresent)

    // #11 All 8 Effect-Direction & Scale Sanity Checks
    val codes = sv.effectScaleFindings.map { it.checkCode }.toSet()
    val expected8 = setOf(
      "RATIO_AS_ADDITIVE",
      "OR_AS_RR",
      "RRR_WITHOUT_BASELINE",
      "WIDE_CI_NO_ASSOCIATION",
      "NONSIG_AS_EQUIVALENCE",
      "SUBGROUP_SEPARATE_P",
      "INTERACTION_WITHOUT_TEST",
      "P_ORDERING_AS_MAGNITUDE"
    )
    assertEquals("All 8 effect-direction & scale sanity failure modes must fire", expected8, codes)
  }

  // ============================================================================
  // ADVERSARIAL CASE #15:
  // Papers with robust methods described in unusual language -> ZERO false positives!
  // ============================================================================
  @Test
  fun adversarial15_robustMethodsInUnusualLanguageTriggerZeroFalsePositives() {
    val unusualRobustMlAndClinical = """
      [PAGE 1]
      Abstract
      A deep learning neural network benchmark evaluated 400 subjects with multiple scans per patient.
      [PAGE 2]
      Methods
      To prevent leakage, we split subjects rather than scans into disjoint training and test sets, fitting all scalers inside each fold.
      Uncertainty was quantified via 1,000 non-parametric resampling iterations across hospital units, and missingness sensitivity under non-ignorable dropout was performed.
      Probability calibration (Brier score = 0.08) and external validation on an independent cohort were conducted.
      [PAGE 3]
      Results
      Holdout AUROC = 0.88 (95% CI 0.84-0.92).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(unusualRobustMlAndClinical, StudyDesignArchetype.MACHINE_LEARNING_AI)
    assertEquals("Robust methods phrased in unusual language must incur ZERO model-mismatch FAILs", 0, audit.verifiedModelMismatchCount)
    assertEquals("Zero arithmetic errors on clean AUROC CI", 0, audit.verifiedArithmeticErrorCount)
  }

  // ============================================================================
  // EXPANDED BENCHMARK METRICS VERIFICATION
  // ============================================================================
  @Test
  fun expandedBenchmarkMetrics_verifyFprFnrDesignAnchorAndNoiseStability() {
    val report = ReviewerCalibrationBenchmark.runCalibration()
    assertEquals("False Positive Rate must be 0%", 0.0, report.falsePositiveRate, 1e-6)
    assertEquals("False Negative Rate must be 0%", 0.0, report.falseNegativeRate, 1e-6)
    assertEquals("Design Classification Accuracy must be 100%", 1.0, report.designClassificationAccuracy, 1e-6)
    assertEquals("Evidence Anchor Accuracy must be 100%", 1.0, report.evidenceAnchorAccuracy, 1e-6)
    assertEquals("Severity Calibration Rate must be 100%", 1.0, report.severityCalibrationRate, 1e-6)
    assertEquals("Score & Verdict Stability under paraphrasing + PDF column noise must be 100%", 1.0, report.scoreStabilityUnderNoiseRate, 1e-6)
    assertTrue("Summary text must expose FPR, Design Acc, Anchor Acc, and Noise Stability", report.toSummaryText().contains("Noise Stability=100%"))
  }
}
