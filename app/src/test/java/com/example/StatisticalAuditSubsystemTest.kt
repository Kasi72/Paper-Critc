package com.example

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.ClaimParseStatus
import com.example.data.ai.stats.EvidenceDerivedScoreGate
import com.example.data.ai.stats.MetaStudyEffectClaim
import com.example.data.ai.stats.NumericalReconstructionEngine
import com.example.data.ai.stats.StatisticalAuditOrchestrator
import com.example.data.ai.stats.StatisticalClaimParser
import com.example.data.ai.stats.StatisticalFindingType
import com.example.data.ai.stats.TailSpecification
import com.example.data.model.ReviewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 7 Comprehensive Verification Suite for the Design-Aware Statistical Audit Subsystem:
 *  - All 19 Fixture Tests (calculators, parser notations, cross-linking, taxonomy, score gating)
 *  - All 9 Golden-Paper Benchmarks (RCT, Cohort, Diagnostic, Survival, Systematic Review,
 *    Meta-Analysis, Econometric Panel, ML Benchmark, Formal Theorem Paper)
 */
class StatisticalAuditSubsystemTest {

  // ============================================================================
  // FIXTURE 1: VALID & INVALID 2x2 TABLES + EXACT OR/RR/ARR/NNT/FISHER
  // ============================================================================
  @Test
  fun fixture01_twoByTwoTable_validAndInvalidOrRrArrNntAndExactFisher() {
    // Exact unit verification: Treatment 18/100 (a=18,b=82) vs Control 35/100 (c=35,d=65)
    val exact = NumericalReconstructionEngine.computeTwoByTwo(18, 82, 35, 65)!!
    assertEquals(0.4077, exact.or, 0.005)
    assertEquals(0.5143, exact.rr, 0.005)
    assertEquals(0.17, exact.arr, 0.001)
    assertEquals(5.882, exact.nnt!!, 0.02)
    assertTrue("Fisher exact two-sided p should be ~0.0099", exact.fisherExactTwoSidedP in 0.005..0.016)

    val validDoc = """
      [PAGE 3]
      Results
      Primary outcome occurred in 18/100 vs 35/100 patients (OR = 0.41, 95% CI 0.21-0.79; RR = 0.51; NNT = 6; p = 0.009).
    """.trimIndent()
    val validAudit = StatisticalAuditOrchestrator.audit(validDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertTrue("Valid 2x2 should produce zero ARITHMETIC_ERROR FAILs", validAudit.verifiedArithmeticErrorCount == 0)
    assertTrue(validAudit.reconstructions.any { it.calculatorName.contains("2x2") && it.status == QuantFindingStatus.PASS })

    val invalidDoc = """
      [PAGE 3]
      Results
      Primary outcome occurred in 18/100 vs 35/100 patients (OR = 1.85, RR = 1.45, NNT = 25, p = 0.009).
    """.trimIndent()
    val invalidAudit = StatisticalAuditOrchestrator.audit(invalidDoc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertTrue("Corrupted 2x2 OR/RR/NNT must trigger verified ARITHMETIC_ERROR", invalidAudit.verifiedArithmeticErrorCount >= 2)
    assertTrue(invalidAudit.reconstructions.any { it.findingType == StatisticalFindingType.ARITHMETIC_ERROR && it.status == QuantFindingStatus.FAIL })
  }

  // ============================================================================
  // FIXTURE 2: OR / RR / HR CONFIDENCE INTERVALS, LOG-SYMMETRY & CI-vs-p CONFLICT
  // ============================================================================
  @Test
  fun fixture02_ratioAndSurvivalCIs_detectBoundsErrorAndCiVsPContradiction() {
    val doc = """
      [PAGE 4]
      Results
      Cox model showed aHR = 0.72 (0.58-0.89), p = 0.280 for mortality.
      Secondary analysis reported OR = 2.40 (95% CI 0.90-1.80) for hospitalization.
      Tertiary analysis reported HR = 1.35 (95% CI 1.05-1.74), p = 0.019.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertTrue(
      "Must detect CI-vs-p INTERNAL_CONTRADICTION on aHR=0.72 (0.58-0.89), p=0.280",
      audit.reconstructions.any { it.findingType == StatisticalFindingType.INTERNAL_CONTRADICTION && it.status == QuantFindingStatus.FAIL }
    )
    assertTrue(
      "Must detect impossible bounds ARITHMETIC_ERROR on OR=2.40 (0.90-1.80)",
      audit.reconstructions.any { it.findingType == StatisticalFindingType.ARITHMETIC_ERROR && it.status == QuantFindingStatus.FAIL }
    )
    assertTrue(
      "Must verify clean HR=1.35 (1.05-1.74) as PASS",
      audit.reconstructions.any { it.status == QuantFindingStatus.PASS && it.sourceEvidence.contains("1.35") }
    )
  }

  // ============================================================================
  // FIXTURE 3: DENOMINATOR DRIFT ACROSS TABLES / SECTIONS
  // ============================================================================
  @Test
  fun fixture03_denominatorDriftAcrossTables_flaggedAsInternalContradiction() {
    val doc = """
      [PAGE 1]
      Abstract
      We enrolled 420 patients in a prospective multicenter study (N = 420).
      [PAGE 4]
      Results
      Table 1 summarizes baseline demographics (N = 420).
      In Table 2, the primary regression included N = 355 patients with aHR = 0.80 (95% CI 0.65-0.98).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val drift = audit.crossLinks.firstOrNull { it.linkType == "DENOMINATOR_DRIFT" }
    assertNotNull("Must detect unexplained denominator drift (420 -> 355)", drift)
    assertEquals(StatisticalFindingType.INTERNAL_CONTRADICTION, drift!!.findingType)
    assertEquals(QuantFindingStatus.FAIL, drift.status)
  }

  // ============================================================================
  // FIXTURE 4: SUBGROUP TOTALS EXCEEDING FULL N
  // ============================================================================
  @Test
  fun fixture04_subgroupTotalsExceedingFullN_flaggedAsInternalContradiction() {
    val doc = """
      [PAGE 3]
      Methods
      A total of 200 participants were randomized (N = 200).
      [PAGE 5]
      Results
      In sex-stratified subgroups, male participants (n = 130) and female participants (n = 115) were evaluated.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val subLink = audit.crossLinks.firstOrNull { it.linkType == "SUBGROUP_EXCEEDS_TOTAL" }
    assertNotNull("Must detect subgroup sum 130+115=245 > N=200", subLink)
    assertEquals(StatisticalFindingType.INTERNAL_CONTRADICTION, subLink!!.findingType)
    assertEquals(QuantFindingStatus.FAIL, subLink.status)
  }

  // ============================================================================
  // FIXTURE 5: DUPLICATE COHORT / OVERLAPPING SAMPLE DETECTION
  // ============================================================================
  @Test
  fun fixture05_duplicateCohortInMetaAnalysis_flagged() {
    val doc = """
      [PAGE 4]
      Results
      Our meta-analysis pooled 5 included studies across separate studies:
      | Study 2019 (UK Biobank) | OR = 1.42 (1.15-1.75) |
      | Study 2021 (UK Biobank) | OR = 1.38 (1.12-1.70) |
      Both reports from UK Biobank were entered as independent observations.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS)
    val dup = audit.crossLinks.firstOrNull { it.linkType == "DUPLICATE_COHORT_OR_ANALYSIS" }
    assertNotNull("Must detect overlapping UK Biobank cohort entered twice", dup)
    assertEquals(QuantFindingStatus.FAIL, dup!!.status)
  }

  // ============================================================================
  // FIXTURE 6: MISSING-DATA FLOW ARITHMETIC MISMATCH
  // ============================================================================
  @Test
  fun fixture06_missingDataFlowMismatch_enrolledMinusLostNotEqualAnalyzed() {
    val doc = """
      [PAGE 3]
      Results
      Overall, 500 patients were enrolled at baseline. During follow-up, 40 patients were lost to follow-up.
      Ultimately, 410 patients were analyzed in the final primary analysis.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val flowLink = audit.crossLinks.firstOrNull { it.linkType == "MISSING_DATA_FLOW_MISMATCH" }
    assertNotNull("Must detect 500 - 40 = 460 != 410 sample-flow gap", flowLink)
    assertEquals(StatisticalFindingType.INTERNAL_CONTRADICTION, flowLink!!.findingType)
    assertEquals(QuantFindingStatus.FAIL, flowLink.status)
  }

  // ============================================================================
  // FIXTURE 7: UNADJUSTED VS ADJUSTED ESTIMATE REVERSAL
  // ============================================================================
  @Test
  fun fixture07_unadjustedVsAdjustedReversal_flaggedAsScreeningSignal() {
    val doc = """
      [PAGE 4]
      Results
      In unadjusted analysis, exposure increased mortality with OR = 1.65 (95% CI 1.20-2.26).
      In multivariable adjusted analysis, aOR = 0.72 (95% CI 0.55-0.94).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val shift = audit.crossLinks.firstOrNull { it.linkType == "ADJUSTED_VS_UNADJUSTED_SHIFT" }
    assertNotNull("Must detect unadjusted-to-adjusted sign reversal across null", shift)
    assertEquals(StatisticalFindingType.POSSIBLE_BIAS, shift!!.findingType)
    assertTrue("Shift must be flagged as screening signal rather than arithmetic FAIL", shift.isScreeningSignal)
  }

  // ============================================================================
  // FIXTURE 8 & 17: MULTIPLE TESTING FAMILIES + CITATION/REFERENCE P-VALUE EXCLUSION
  // ============================================================================
  @Test
  fun fixture08And17_multipleTestingFamiliesAndReferencePValueExclusion() {
    val doc = """
      [PAGE 2]
      Introduction
      Smith et al. (2020) previously reported a significant effect (p = 0.012) and Jones et al. (2021) found p = 0.031.
      [PAGE 4]
      Results
      In secondary outcome testing, we found p = 0.038, p = 0.041, p = 0.044, p = 0.048, and p = 0.210 across endpoints.
      [PAGE 9]
      References
      1. Doe J. Prior cohort findings (t(40) = 2.50, p = 0.016). Journal of Medicine 2019.
      2. Roe A. Earlier trial result (p = 0.003). Lancet 2018.
    """.trimIndent()
    val ledger = StatisticalClaimParser.parse(doc)
    assertTrue("Reference & prior-study inline p-values must be isolated", ledger.claims.count { it.isFromReferenceOrCitation } >= 3)
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val mtRecon = audit.reconstructions.firstOrNull { it.calculatorName.startsWith("Multiple-Testing Family") }
    assertNotNull("Must run family-scoped BH-FDR / Holm / Bonferroni adjustment on Secondary family", mtRecon)
    assertTrue("BH-FDR should drop borderline nominal hits", mtRecon!!.recomputedValue.contains("BH-FDR=0/5"))
    assertTrue("Multiple testing alert must be a screening signal, not ARITHMETIC_ERROR", mtRecon.isScreeningSignal)

    // Direct calculator check
    val mt = NumericalReconstructionEngine.computeMultipleTestingAdjustments(
      familyName = "Secondary",
      namedPValues = listOf("a" to 0.005, "b" to 0.025, "c" to 0.045, "d" to 0.40, "e" to 0.80),
      alpha = 0.05
    )
    assertEquals(3, mt.rawSignificantCount)
    assertEquals(1, mt.bonferroniSignificantCount)
    assertEquals(1, mt.holmSignificantCount)
    assertEquals(1, mt.bhFdrSignificantCount)
  }

  // ============================================================================
  // FIXTURE 9: CLUSTER-ROBUST VS NAIVE SE & SMALL-CLUSTER INFERENCE
  // ============================================================================
  @Test
  fun fixture09_clusterRobustVsNaiveSeAndSmallClusterInference() {
    val naivePanelDoc = """
      [PAGE 4]
      Methods
      We estimate a difference-in-differences panel data specification across 50 states using homoskedastic OLS standard errors.
    """.trimIndent()
    val naiveAudit = StatisticalAuditOrchestrator.audit(naivePanelDoc, StudyDesignArchetype.ECONOMETRICS_CAUSAL)
    assertTrue(
      "Panel with naive homoskedastic SEs must trigger MODEL_MISMATCH FAIL",
      naiveAudit.domainFindings.any { it.criterion.contains("Cluster-Robust") && it.status == QuantFindingStatus.FAIL }
    )

    val smallClusterDoc = """
      [PAGE 4]
      Methods
      We estimate a difference-in-differences panel data model across 18 states with clustered standard errors at the state level.
    """.trimIndent()
    val smallAudit = StatisticalAuditOrchestrator.audit(smallClusterDoc, StudyDesignArchetype.ECONOMETRICS_CAUSAL)
    assertTrue(
      "Clustered inference with G=18 < 42 without wild bootstrap must trigger WARN",
      smallAudit.domainFindings.any { it.criterion.contains("Small-Cluster") && it.status == QuantFindingStatus.WARN }
    )
  }

  // ============================================================================
  // FIXTURE 10 & 11: SURVIVAL CENSORING & COMPETING RISKS
  // ============================================================================
  @Test
  fun fixture10And11_survivalCensoringAndCompetingRisksDistortion() {
    val doc = """
      [PAGE 3]
      Methods
      We analyzed time-to-event cancer-specific mortality using Kaplan-Meier curves and Cox proportional hazards regression.
      Patients who experienced non-cancer death were censored at death in the Kaplan-Meier estimator.
      Results
      Treatment was associated with HR = 0.68 (95% CI 0.51-0.91).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AuditSubDesign.SURVIVAL_ANALYSIS, audit.subDesign)
    assertTrue(
      "Censoring competing deaths in KM without Fine-Gray/Aalen-Johansen must trigger MODEL_MISMATCH FAIL",
      audit.domainFindings.any { it.criterion.contains("Competing Risks") && it.status == QuantFindingStatus.FAIL }
    )
    assertTrue(
      "Unchecked proportional hazards assumption must be flagged as ASSUMPTION_UNCHECKED",
      audit.domainFindings.any { it.criterion.contains("Proportional Hazards") && it.findingType == StatisticalFindingType.ASSUMPTION_UNCHECKED }
    )
  }

  // ============================================================================
  // FIXTURE 12: META-ANALYSIS τ² (DL & PAULE-MANDEL), Q, I², HK CI, 95% PI & LOO
  // ============================================================================
  @Test
  fun fixture12_metaAnalysisPooling_tau2PredictionIntervalAndLooInfluence() {
    val studies = listOf(
      MetaStudyEffectClaim("Alpha 2019", effect = 0.15, standardError = 0.18),
      MetaStudyEffectClaim("Beta 2020", effect = 0.22, standardError = 0.16),
      MetaStudyEffectClaim("Gamma 2021", effect = 0.85, standardError = 0.14),
      MetaStudyEffectClaim("Delta 2022", effect = 0.10, standardError = 0.17)
    )
    val summary = NumericalReconstructionEngine.computeMetaAnalysis(studies)!!
    assertEquals(4, summary.k)
    assertTrue("High heterogeneity Q must yield I² > 50%", summary.iSquaredPct > 50.0)
    assertTrue("DL tau^2 must be positive", summary.tauSquaredDL > 0.0)
    assertTrue("Paule-Mandel tau^2 must be positive", summary.tauSquaredPM > 0.0)
    assertNotNull("95% Prediction Interval must be computed for k >= 3", summary.predictionIntervalLower)
    assertTrue("PI must be wider than RE Wald CI", summary.predictionIntervalUpper!! > summary.randomUpperDL)
    assertEquals("Gamma 2021", summary.mostInfluentialStudy)

    // End-to-end text extraction & Fixed-Effect mismatch detection
    val metaDoc = """
      [PAGE 4]
      Methods
      We performed a fixed-effect meta-analysis across 4 trials.
      Results
      Alpha 2019: d = 0.15, SE = 0.18
      Beta 2020: d = 0.22, SE = 0.16
      Gamma 2021: d = 0.85, SE = 0.14
      Delta 2022: d = 0.10, SE = 0.17
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(metaDoc, StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS)
    assertEquals(4, audit.metaStudyEffects.size)
    assertTrue(
      "Fixed-effect pooling with reconstructed high I² must flag MODEL_MISMATCH FAIL",
      audit.reconstructions.any { it.calculatorName.contains("Meta-Analysis Pooling") && it.findingType == StatisticalFindingType.MODEL_MISMATCH && it.status == QuantFindingStatus.FAIL }
    )
  }

  // ============================================================================
  // FIXTURE 13: ML LEAKAGE PATTERNS (PREPROCESSING, PATIENT-SPLIT, TEST-SET TUNING)
  // ============================================================================
  @Test
  fun fixture13_mlLeakagePatterns_allThreeDetectedAsModelMismatch() {
    val doc = """
      [PAGE 3]
      Methods
      Our deep learning classifier used multiple scans per patient from a cohort with prevalence of 2.5% (imbalanced).
      Feature normalization and SMOTE was performed on the entire dataset before train/test splitting.
      Images were randomly split 80/20 at the image-level split.
      Hyperparameters and the decision threshold were tuned on the test set to maximize AUROC = 0.96.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.MACHINE_LEARNING_AI)
    assertEquals(AuditSubDesign.ML_BENCHMARK, audit.subDesign)
    val leakageFails = audit.domainFindings.filter { it.domainModule == "ML/Leakage" && it.status == QuantFindingStatus.FAIL }
    assertEquals("All 3 ML leakage mechanisms must trigger confirmed MODEL_MISMATCH FAIL", 3, leakageFails.size)
    assertTrue("Must flag missing PR-AUC under severe imbalance", audit.domainFindings.any { it.criterion.contains("PR-AUC") })
    assertTrue("Must flag missing calibration metrics", audit.domainFindings.any { it.criterion.contains("Calibration") })
  }

  // ============================================================================
  // FIXTURE 14: TABLE-ONLY STATISTICS (PIPE & TAB ROWS WITHOUT NARRATIVE PROSE)
  // ============================================================================
  @Test
  fun fixture14_tableOnlyPipeAndTabStatistics_extractedAndAudited() {
    val doc = """
      [PAGE 5]
      Results
      Table 2. Primary outcomes
      | Mortality | 18/100 | 35/100 | OR 1.95 (1.10-3.45) | p = 0.012 |
      | LengthOfStay | Beta | SE | p |
      Readmission	0.50	0.10	0.420
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertTrue("Pipe table 2x2 must be parsed", audit.twoByTwoTables.isNotEmpty())
    assertTrue("Corrupted OR=1.95 inside pipe table must trigger ARITHMETIC_ERROR FAIL", audit.verifiedArithmeticErrorCount >= 1)
    assertTrue("Tab-delimited regression row (beta=0.50, SE=0.10, p=0.420) must be parsed", audit.claims.any { it.analysisType == "Table-only regression" })
  }

  // ============================================================================
  // FIXTURE 15 & 16: SCIENTIFIC NOTATION, ALTERNATIVE SYNTAX, ONE-SIDED & CUSTOM ALPHA
  // ============================================================================
  @Test
  fun fixture15And16_scientificNotationAlternativeSyntaxOneSidedAndCustomAlpha() {
    val doc = """
      [PAGE 4]
      Methods
      All tests used a one-sided hypothesis test with significance level α = 0.01.
      Results
      Primary test yielded t28 = 1.75; P = 0.045.
      Secondary chi-square test yielded χ²(3) = 12.2; P = 6.7 × 10^-3.
      High-precision z-test yielded z = 3.85, p = 1.2e-4.
    """.trimIndent()
    val ledger = StatisticalClaimParser.parse(doc)
    assertEquals(TailSpecification.ONE_SIDED, ledger.globalTailSpec)
    assertEquals(0.01, ledger.globalAlpha, 1e-6)

    val tClaim = ledger.claims.firstOrNull { it.testStatisticName == "t" }
    assertNotNull("Must parse alternative notation t28 = 1.75; P = 0.045", tClaim)
    assertEquals(28.0, tClaim!!.degreesOfFreedom!!, 1e-6)
    assertEquals(0.045, tClaim.pValue!!, 1e-6)

    val chiClaim = ledger.claims.firstOrNull { it.testStatisticName == "chi2" }
    assertNotNull("Must parse χ²(3) = 12.2; P = 6.7 × 10^-3", chiClaim)
    assertEquals(0.0067, chiClaim!!.pValue!!, 1e-5)

    val zClaim = ledger.claims.firstOrNull { it.testStatisticName == "z" }
    assertNotNull("Must parse scientific notation p = 1.2e-4", zClaim)
    assertEquals(0.00012, zClaim!!.pValue!!, 1e-6)

    // With one-sided t(28)=1.75 -> exact one-sided p ≈ 0.0455, which MATCHES P = 0.045 (PASS, NOT false FAIL!)
    val audit = StatisticalAuditOrchestrator.audit(doc, StudyDesignArchetype.GENERAL_EMPIRICAL)
    val tRecon = audit.reconstructions.first { it.calculatorName.contains("t, one-sided") }
    assertEquals("One-sided t28=1.75, P=0.045 must PASS under one-sided spec", QuantFindingStatus.PASS, tRecon.status)
    val chiRecon = audit.reconstructions.first { it.calculatorName.contains("chi2") }
    assertEquals("χ²(3)=12.2, P=6.7e-3 must PASS", QuantFindingStatus.PASS, chiRecon.status)
  }

  // ============================================================================
  // FIXTURE 18 & 19: AMBIGUITY CLASSIFICATION, UNSUPPORTED DISCLOSURE & SCORE GATE
  // ============================================================================
  @Test
  fun fixture18And19_ambiguityUnsupportedDisclosureAndPhase6EvidenceDerivedScoreGate() {
    // 1. Ambiguous parenthesized range + missing raw tables -> UNSUPPORTED_TO_ASSESS lowers extractionConfidence, NOT scientific score!
    val unsupportedDoc = """
      [PAGE 2]
      Results
      The cohort mean 42.5 (31.0-54.0) was recorded alongside a regression analysis with odds ratio OR = 1.45 (95% CI 1.10-1.91).
      Additional comparisons gave p = 0.02, p = 0.03, and p = 0.04 without test statistics in the text (see Supplementary Table S1).
    """.trimIndent()
    val forensicUnsupported = ManuscriptForensicEngine.analyzeManuscript(unsupportedDoc, isEmpiricalTrack = true)
    val statUnsupported = forensicUnsupported.quantForensics.statisticalAudit!!
    assertTrue("Unlabeled mean (31.0-54.0) must be tagged AMBIGUOUS", statUnsupported.ambiguousClaimCount >= 1)
    assertTrue("Missing 2x2/regression/statcheck inputs must produce UNSUPPORTED disclosures", statUnsupported.unsupportedDisclosures.size >= 2)
    assertEquals("Missing inputs must NEVER emit false ARITHMETIC_ERROR FAIL", 0, statUnsupported.verifiedArithmeticErrorCount)

    val breakdownUnsupported = EvidenceDerivedScoreGate.evaluate(statUnsupported, baseMathScore = 8, baseMethodologyScore = 8, manuscriptText = unsupportedDoc)
    assertEquals("Unsupported inputs must NOT cap methodology score", null, breakdownUnsupported.methodologyScoreCap)
    assertTrue("Unsupported inputs MUST lower extractionConfidenceAdjustment (< 0)", breakdownUnsupported.extractionConfidenceAdjustment < 0)

    val confWithoutUnsupported = Reviewer2PipelineService.computeCalibratedExtractionConfidence(
      manuscriptText = "Clean manuscript text. ".repeat(40),
      coverageReport = null,
      omittedPromptPages = emptyList(),
      wasPromptTrimmed = false,
      adjudicatedFindings = emptyList(),
      statisticalAudit = null
    )
    val confWithUnsupported = Reviewer2PipelineService.computeCalibratedExtractionConfidence(
      manuscriptText = "Clean manuscript text. ".repeat(40),
      coverageReport = null,
      omittedPromptPages = emptyList(),
      wasPromptTrimmed = false,
      adjudicatedFindings = emptyList(),
      statisticalAudit = statUnsupported
    )
    assertTrue("Unsupported disclosures must lower calibrated extraction confidence", confWithUnsupported < confWithoutUnsupported)

    // 2. Verified ARITHMETIC_ERROR must cap mathRigorScore <= 4 and block ACCEPT / HIGH_CREDIBILITY!
    val broken2x2Doc = """
      [PAGE 3]
      Results
      Primary event occurred in 18/100 vs 35/100 patients (OR = 2.15, p = 0.008).
    """.trimIndent()
    val forensicBroken = ManuscriptForensicEngine.analyzeManuscript(broken2x2Doc, isEmpiricalTrack = true)
    val gated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = broken2x2Doc,
      forensicReport = forensicBroken,
      methodologyScore = 9,
      significanceScore = 9,
      clarityScore = 9,
      originalityScore = 9,
      mathRigorScore = 9,
      reproducibilityScore = 9,
      rawVerdict = "ACCEPT",
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      extractionConfidence = 5,
      applyStatisticalScoreGate = true
    )
    assertNotNull("ScoreCalculationBreakdown must be populated", gated.scoreBreakdown)
    assertTrue("Verified arithmetic error must hard-cap mathRigorScore <= 4 (got ${gated.mathRigorScore})", gated.mathRigorScore <= 4)
    assertFalse("Verified arithmetic error must block ACCEPT verdict", gated.resolvedVerdict == "ACCEPT")
    assertEquals("MAJOR_REVISION", gated.resolvedVerdict)
    assertTrue(
      "Breakdown markdown must show transparent formula and penalties",
      gated.scoreBreakdown!!.toMarkdownSummary().contains("VERIFIED_CRITICAL")
    )
  }

  // ============================================================================
  // ADDITIONAL CALCULATOR VERIFICATION: WILSON, POISSON, ANOVA F, SMD (COHEN/HEDGES)
  // ============================================================================
  @Test
  fun calculators_wilsonPoissonAnovaAndSmdExactness() {
    val (wLo, wHi) = NumericalReconstructionEngine.wilsonBinomialCi(18, 100)
    assertTrue("Wilson 95% CI for 18/100 should be ~[0.117, 0.267]", wLo in 0.11..0.125 && wHi in 0.26..0.275)

    val (pLo, pHi) = NumericalReconstructionEngine.poissonCountCi(10)
    assertTrue("Poisson 95% CI for k=10 should be ~[4.79, 18.39]", pLo in 4.5..5.1 && pHi in 18.0..18.8)

    val (fVal, fP) = NumericalReconstructionEngine.computeAnovaF(ssBetween = 120.0, dfBetween = 2.0, ssWithin = 300.0, dfWithin = 30.0)!!
    assertEquals(6.0, fVal, 1e-6)
    assertTrue("F(2,30)=6.0 has exact p ≈ 0.0064", abs(fP - 0.00643) < 0.001)

    val smd = NumericalReconstructionEngine.computeSmd(mean1 = 15.0, sd1 = 4.0, n1 = 30, mean2 = 12.0, sd2 = 4.0, n2 = 30)!!
    assertEquals(0.75, smd.cohensD, 1e-4)
    assertTrue("Hedges g slightly smaller than Cohen's d", smd.hedgesG in 0.73..0.749)
    assertTrue("95% CI excludes 0", smd.lower95 > 0.1 && smd.upper95 > smd.hedgesG)
  }

  // ============================================================================
  // 9 GOLDEN-PAPER BENCHMARKS (END-TO-END DESIGN-AWARE AUDITING)
  // ============================================================================

  @Test
  fun goldenBenchmark01_rctPaper() {
    val rctPaper = """
      [PAGE 1]
      Abstract
      A double-blind randomized controlled trial (CONSORT) randomly assigned 240 patients (N = 240) to Drug X vs placebo.
      [PAGE 3]
      Methods
      Primary analysis used per-protocol exclusions only. Table 1 baseline age comparison yielded p = 0.042.
      [PAGE 4]
      Results
      Primary mortality occurred in 12/120 vs 24/120 patients (RR = 0.50, 95% CI 0.26-0.96, p = 0.032).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(rctPaper, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AuditSubDesign.RCT, audit.subDesign)
    assertTrue("Must flag per-protocol-only without ITT as MODEL_MISMATCH FAIL", audit.domainFindings.any { it.criterion.contains("Intention-to-Treat") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag baseline Table 1 p-value testing antipattern", audit.domainFindings.any { it.criterion.contains("Baseline Balance") })
    assertTrue("Must verify valid RR = 0.50 from 12/120 vs 24/120", audit.reconstructions.any { it.calculatorName.contains("2x2") && it.status == QuantFindingStatus.PASS })
  }

  @Test
  fun goldenBenchmark02_observationalCohortPaper() {
    val cohortPaper = """
      [PAGE 1]
      Abstract
      A retrospective cohort study (STROBE) evaluated 1,200 patients (N = 1,200) for statin initiation and stroke.
      [PAGE 3]
      Methods
      Exposed patients initiated treatment during follow-up and were classified as exposed from baseline without landmarking.
      Complete-case analysis was performed despite 22% missing covariate data; multivariable logistic regression included 40 events and 12 predictors.
      [PAGE 4]
      Results
      Unadjusted OR = 1.55 (95% CI 1.10-2.18); adjusted aOR = 0.68 (95% CI 0.48-0.96).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(cohortPaper, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AuditSubDesign.OBSERVATIONAL_COHORT, audit.subDesign)
    assertTrue("Must flag immortal-time bias FAIL", audit.domainFindings.any { it.criterion.contains("Immortal-Time") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag low EPV (< 10)", audit.domainFindings.any { it.criterion.contains("EPV") && it.findingType == StatisticalFindingType.LOW_POWER })
    assertTrue("Must flag >=15% missing complete-case only", audit.domainFindings.any { it.criterion.contains("Missing-Data") })
  }

  @Test
  fun goldenBenchmark03_diagnosticAccuracyPaper() {
    val diagPaper = """
      [PAGE 1]
      Abstract
      A diagnostic accuracy study (STARD, QUADAS-2) using a case-control design evaluated a novel biomarker against biopsy reference standard.
      [PAGE 4]
      Results
      Sensitivity was 92% and specificity was 88%, yielding a positive predictive value (PPV) of 89% and NPV of 91%.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(diagPaper, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AuditSubDesign.DIAGNOSTIC_ACCURACY, audit.subDesign)
    assertTrue("Must flag case-control PPV/NPV spectrum bias FAIL", audit.domainFindings.any { it.criterion.contains("Spectrum Bias") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag omitted 95% CIs for sensitivity/specificity", audit.domainFindings.any { it.criterion.contains("Diagnostic Precision") })
  }

  @Test
  fun goldenBenchmark04_survivalAnalysisPaper() {
    val survPaper = """
      [PAGE 2]
      Methods
      Time-to-event cardiovascular death was modeled via Kaplan-Meier and Cox proportional hazards regression; patients with non-cardiovascular death were censored at death.
      [PAGE 4]
      Results
      Intervention yielded aHR = 0.76 (95% CI 0.62-0.93), p = 0.008.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(survPaper, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AuditSubDesign.SURVIVAL_ANALYSIS, audit.subDesign)
    assertTrue("Must flag competing-risk censoring FAIL", audit.domainFindings.any { it.criterion.contains("Competing Risks") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must verify valid aHR=0.76 (0.62-0.93) CI", audit.reconstructions.any { it.calculatorName.contains("Ratio / Survival") && it.status == QuantFindingStatus.PASS })
  }

  @Test
  fun goldenBenchmark05_systematicReviewPaper() {
    val sysRevPaper = """
      [PAGE 1]
      Abstract
      A PRISMA systematic review registered on PROSPERO synthesized 18 qualitative and descriptive studies of community psychiatric care.
      [PAGE 4]
      Results
      Subgroup analysis found the intervention was significant in urban clinics but not in rural clinics, proving urban superiority.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(sysRevPaper, StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS)
    assertEquals(AuditSubDesign.SYSTEMATIC_REVIEW, audit.subDesign)
    assertTrue("Must flag Altman-Bland subgroup interaction fallacy FAIL", audit.domainFindings.any { it.criterion.contains("Subgroup Interaction") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag missing GRADE certainty appraisal", audit.domainFindings.any { it.criterion.contains("GRADE") })
  }

  @Test
  fun goldenBenchmark06_metaAnalysisPaper() {
    val metaPaper = """
      [PAGE 2]
      Methods
      We conducted a random-effects meta-analysis using the DerSimonian-Laird estimator across k = 7 trials including multi-arm trials with a shared control group.
      Meta-regression examined study-level mean age across the 7 trials.
      [PAGE 4]
      Results
      Heterogeneity was high (I² = 78%).
      Trial A 2018: OR = 1.20 (0.95-1.52)
      Trial B 2019: OR = 1.85 (1.35-2.54)
      Trial C 2020: OR = 1.05 (0.80-1.38)
      Trial D 2021: OR = 2.40 (1.70-3.39)
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(metaPaper, StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS)
    assertEquals(AuditSubDesign.META_ANALYSIS, audit.subDesign)
    assertTrue("Must flag shared-control double counting FAIL", audit.domainFindings.any { it.criterion.contains("Shared Control") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag DL small-k without Hartung-Knapp/REML", audit.domainFindings.any { it.criterion.contains("Hartung-Knapp") })
    assertTrue("Must flag k < 10 meta-regression overfitting", audit.domainFindings.any { it.criterion.contains("Meta-Regression") })
    assertTrue("Must flag ecological fallacy", audit.domainFindings.any { it.criterion.contains("Ecological Fallacy") })
    assertTrue("Must run executable meta-analysis reconstruction on 4 trials", audit.reconstructions.any { it.calculatorName.contains("Meta-Analysis Pooling") })
  }

  @Test
  fun goldenBenchmark07_econometricPanelPaper() {
    val econPaper = """
      [PAGE 1]
      Abstract
      We evaluate state minimum-wage staggered adoption across 25 states using two-way fixed effects (TWFE) difference-in-differences panel data regressions.
      [PAGE 3]
      Methods
      Our instrumental variable 2SLS specification reports a first-stage F = 6.2. We also controlled for post-treatment employment status.
      Standard errors are clustered across 25 states.
      [PAGE 5]
      Results
      Main coefficient β = 0.42 (SE = 0.11, 95% CI 0.20 to 0.64, p = 0.0001).
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(econPaper, StudyDesignArchetype.ECONOMETRICS_CAUSAL)
    assertEquals(AuditSubDesign.ECONOMETRIC_PANEL, audit.subDesign)
    assertTrue("Must flag staggered TWFE negative-weighting bias FAIL", audit.domainFindings.any { it.criterion.contains("TWFE") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag weak IV first-stage F = 6.2 < 10 FAIL", audit.domainFindings.any { it.criterion.contains("Weak Instrument") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag post-treatment bad control FAIL", audit.domainFindings.any { it.criterion.contains("Post-Treatment") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag small-cluster G=25 < 42", audit.domainFindings.any { it.criterion.contains("Small-Cluster") })
    assertTrue("Must verify exact regression β/SE/CI check PASS", audit.reconstructions.any { it.calculatorName.contains("Regression") && it.status == QuantFindingStatus.PASS })
  }

  @Test
  fun goldenBenchmark08_mlBenchmarkPaper() {
    val mlPaper = """
      [PAGE 2]
      Methods
      We trained a Transformer neural network on a medical imaging dataset containing multiple images per patient with rare event prevalence of 3%.
      Z-score normalization was applied on the entire dataset before train/test splitting, and images were randomly split 80/20.
      Results
      Our model achieved AUROC = 0.94 on the internal holdout set.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(mlPaper, StudyDesignArchetype.MACHINE_LEARNING_AI)
    assertEquals(AuditSubDesign.ML_BENCHMARK, audit.subDesign)
    assertTrue("Must flag preprocessing leakage FAIL", audit.domainFindings.any { it.criterion.contains("Preprocessing") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag subject-level split leakage FAIL", audit.domainFindings.any { it.criterion.contains("Subject-Level") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag missing external validation", audit.domainFindings.any { it.criterion.contains("External Validation") })
  }

  @Test
  fun goldenBenchmark09_formalTheoremPaper() {
    val mathPaper = """
      [PAGE 2]
      Theorem 1. Let M be a matrix of dimension d and later let M denote a scalar constant.
      Proof. The sequence f_n converges to f, and by interchanging the limit and integral we compute log(x - a) + 1/(b - c).
      Therefore the solution exhibits uniqueness for all epsilon > 0 there exists delta > 0 establishing a uniform bound. Q.E.D.
    """.trimIndent()
    val audit = StatisticalAuditOrchestrator.audit(mathPaper, StudyDesignArchetype.THEORETICAL_FORMAL)
    assertEquals(AuditSubDesign.FORMAL_THEOREM, audit.subDesign)
    assertTrue("Must flag unjustified limit/integral interchange FAIL", audit.domainFindings.any { it.criterion.contains("Interchange") && it.status == QuantFindingStatus.FAIL })
    assertTrue("Must flag unspecified convergence mode", audit.domainFindings.any { it.criterion.contains("Convergence Mode") })
    assertTrue("Must flag missing log/division domain guards", audit.domainFindings.any { it.criterion.contains("Domain Guards") })
    assertTrue("Must flag notation collision on M", audit.domainFindings.any { it.criterion.contains("Notation Collision") })
  }
}
