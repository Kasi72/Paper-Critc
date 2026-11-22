package com.example

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantitativeForensicReport
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.epistemic.AnalysisProvenance
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.ClaimLevel
import com.example.data.ai.epistemic.DefenseOutcome
import com.example.data.ai.epistemic.EpistemicSafeguardEngine
import com.example.data.ai.epistemic.FlawSeverityTier
import com.example.data.ai.epistemic.ReportingStatus
import com.example.data.ai.epistemic.ReviewerCalibrationBenchmark
import com.example.data.ai.epistemic.UncertaintySource
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.data.repository.ReviewRepository
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
 * Comprehensive verification suite covering all 16 Common-Sense Epistemic Safeguards
 * and Reviewer Calibration Mode (Diagnostic Instrument).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EpistemicSafeguardsAndCalibrationTest {

  private fun analyzeQuant(text: String): QuantitativeForensicReport =
    ManuscriptForensicEngine.analyzeManuscript(text, isEmpiricalTrack = true).quantForensics

  @Test
  fun safeguard01_studyIdentityCard_extractsDesignUnitAndDisclosesUncertainFields() {
    val metaText = """
      [PAGE 1]
      Abstract
      A systematic review and meta-analysis of 18 randomized trials (k = 18 studies) comparing cognitive behavioral therapy versus usual care for adolescent depression over 12 weeks.
      [PAGE 2]
      Methods
      Primary outcome was symptom severity (SMD) pooled using DerSimonian-Laird random-effects meta-analysis.
    """.trimIndent()

    val report = analyzeQuant(metaText)
    val card = report.epistemicSafeguards!!.studyIdentityCard

    assertEquals(ReportingStatus.REPORTED_AND_ADEQUATE, card.studyDesign.status)
    assertTrue(card.unitOfAnalysis.value.contains("Study", ignoreCase = true))
    assertTrue(card.timeHorizon.value.contains("12 weeks", ignoreCase = true))
    assertTrue(card.primaryAnalysis.value.contains("meta-analysis", ignoreCase = true))
    assertTrue(report.epistemicSafeguards.toPromptBlock().contains("STUDY IDENTITY CARD"))
    assertTrue(report.epistemicSafeguards.toAuditMarkdown().contains("[STUDY IDENTITY CARD"))

    // Sparse / ambiguous excerpt discloses uncertain fields explicitly before critique
    val sparseText = "Preliminary observations showed elevated values across the cohort."
    val sparseCard = analyzeQuant(sparseText).epistemicSafeguards!!.studyIdentityCard
    assertTrue("Sparse manuscript must disclose uncertain fields", sparseCard.uncertainFields.size >= 4)
    assertTrue("Sparse manuscript must be marked ambiguous", sparseCard.isAmbiguous)
  }

  @Test
  fun safeguard02_fourStateReportingDiscipline_distinguishesNotReportedFromNotAssessable() {
    val text = """
      [PAGE 1]
      Methods
      A cohort of 300 patients (N = 300) was followed for 2 years. See Supplementary Table S2 for subgroup sensitivity checks.
      Results
      Primary outcome mortality had HR = 1.45 (95% CI 1.12-1.88, p = 0.004).
    """.trimIndent()

    val findingNotReported = ReviewFinding(
      id = "F-01",
      title = "Missing multiplicity plan",
      severity = "MAJOR",
      status = FindingStatus.PARTIALLY_SUPPORTED,
      allegation = "Adjustment for multiple testing was not reported in Methods."
    )
    val findingInSupplement = ReviewFinding(
      id = "F-02",
      title = "Omitted sensitivity breakdown",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED,
      allegation = "Subgroup sensitivity tables were omitted from the main text."
    )
    val findingInadequate = ReviewFinding(
      id = "F-03",
      title = "Improper dichotomization",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED,
      allegation = "Continuous blood pressure was dichotomized arbitrarily at the sample median."
    )

    val forensic = ManuscriptForensicEngine.analyzeManuscript(text, true)
    val adjudicated = BidirectionalAdjudicator.adjudicate(
      findings = listOf(findingNotReported, findingInSupplement, findingInadequate),
      manuscriptText = text,
      epistemicReport = forensic.quantForensics.epistemicSafeguards
    )

    val r1 = adjudicated.findings.first { it.id == "F-01" }
    val r2 = adjudicated.findings.first { it.id == "F-02" }
    val r3 = adjudicated.findings.first { it.id == "F-03" }

    assertEquals(ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL.name, r1.reportingStatus)
    assertEquals(ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL.name, r2.reportingStatus)
    assertEquals(ReportingStatus.REPORTED_BUT_INADEQUATE.name, r3.reportingStatus)
  }

  @Test
  fun safeguard03_denominatorLedger_detectsDriftSubgroupExceedsParentImpossibleAttritionAndProtocolMismatch() {
    val text = """
      [PAGE 1]
      Abstract
      A randomized controlled trial (CONSORT) enrolled 200 patients (N = 200) and randomized 200 patients.
      [PAGE 3]
      Methods
      Primary analysis followed intention-to-treat (ITT) principles, though we excluded non-adherent patients in the per-protocol analysis.
      Table 1 baseline included N = 200.
      [PAGE 4]
      Results
      In Table 2 (N = 140), primary events occurred in 25/100 (40.0%) of treatment patients.
      In the subgroup of older adults (n = 230), response rate remained elevated.
      A total of 215 patients were followed up at 12 months.
    """.trimIndent()

    val report = analyzeQuant(text)
    val ledger = report.epistemicSafeguards!!.denominatorLedger

    assertTrue("Should track multiple denominator entries", ledger.entries.size >= 4)
    val types = ledger.discrepancies.map { it.discrepancyType }.toSet()
    assertTrue("Expected PERCENTAGE_MISMATCH (25/100 != 40%), got $types", "PERCENTAGE_MISMATCH" in types)
    assertTrue("Expected SUBGROUP_EXCEEDS_PARENT (230 > 200), got $types", "SUBGROUP_EXCEEDS_PARENT" in types)
    assertTrue("Expected TABLE_DENOMINATOR_DRIFT (200 -> 140), got $types", "TABLE_DENOMINATOR_DRIFT" in types)
    assertTrue("Expected IMPOSSIBLE_ATTRITION (215 followed > 200 enrolled), got $types", "IMPOSSIBLE_ATTRITION" in types)
    assertTrue("Expected PROTOCOL_POPULATION_MISMATCH (ITT vs per-protocol exclusion), got $types", "PROTOCOL_POPULATION_MISMATCH" in types)
  }

  @Test
  fun safeguard04_claimHierarchy_catchesCausalOverclaimAndRespectsNegatedCaveats() {
    val overclaimed = """
      [PAGE 1]
      A cross-sectional survey of 4,200 adults examined sleep duration and obesity.
      [PAGE 4]
      Conclusion: Short sleep causes obesity and clinicians should recommend mandatory 9-hour sleep targets.
    """.trimIndent()
    val overAudit = analyzeQuant(overclaimed).epistemicSafeguards!!.claimHierarchyAudit
    assertEquals(ClaimLevel.ASSOCIATIONAL, overAudit.maxDesignSupportedLevel)
    assertEquals(ClaimLevel.POLICY_OR_CLINICAL_RECOMMENDATION, overAudit.highestClaimedLevel)
    assertTrue(overAudit.isOverclaimed)
    assertEquals(FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION, overAudit.severityTier)

    // Negated causal language ("cannot prove causality") must NOT trigger false positive overclaim
    val proportionate = """
      [PAGE 1]
      A cross-sectional survey of 4,200 adults examined sleep duration and obesity.
      [PAGE 4]
      Conclusion: Short sleep was associated with higher BMI; however, because of the cross-sectional design we cannot prove causality and do not establish causal effect.
    """.trimIndent()
    val propAudit = analyzeQuant(proportionate).epistemicSafeguards!!.claimHierarchyAudit
    assertFalse("Negated causal caveats should not trigger overclaim false positive", propAudit.isOverclaimed)
    assertEquals(ClaimLevel.ASSOCIATIONAL, propAudit.highestClaimedLevel)
  }

  @Test
  fun safeguard05_primaryAnalysisDeclaration_flagsSelectiveEmphasisWhenManyOutcomesWithoutPrimary() {
    val exploratorySoup = """
      [PAGE 2]
      Methods
      We evaluated mortality, hospitalization, blood pressure, cholesterol, HbA1c, quality of life, and readmission across age and sex subgroups.
      [PAGE 4]
      Results
      Significant differences were observed for HbA1c (p = 0.03) and cholesterol (p = 0.04).
    """.trimIndent()

    val decl = analyzeQuant(exploratorySoup).epistemicSafeguards!!.primaryAnalysisDeclaration
    assertEquals(ReportingStatus.NOT_REPORTED, decl.primaryOutcome.status)
    assertTrue("Many outcomes with no prespecified primary should flag selective emphasis risk", decl.selectiveEmphasisRisk)
  }

  @Test
  fun safeguard06_whatWouldChangeConclusion_generatesStructuredFragilityTable() {
    val obsText = """
      [PAGE 1]
      A retrospective cohort study of 1,500 patients evaluated statin exposure vs non-users on 5-year cardiovascular events using logistic regression (aOR = 0.72, 95% CI 0.58-0.89, p = 0.002).
    """.trimIndent()
    val epistemic = analyzeQuant(obsText).epistemicSafeguards!!
    assertTrue("Should generate fragility rows", epistemic.conclusionFragility.isNotEmpty())
    assertTrue(
      "Observational cohort fragility should inspect unmeasured confounding / E-value",
      epistemic.conclusionFragility.any {
        it.keyAssumption.contains("confounding", ignoreCase = true) &&
          it.minimumSensitivityAnalysis.contains("E-value", ignoreCase = true)
      }
    )
    assertTrue(epistemic.toAuditMarkdown().contains("[WHAT WOULD CHANGE THE CONCLUSION?]"))
  }

  @Test
  fun safeguard07_contradictionMap_linksSectionsAndFlagsOutcomeSwitchingSampleDriftAndSubgroupSpin() {
    val text = """
      [PAGE 1]
      Abstract
      In this trial of N = 500 patients, Drug X demonstrated clear benefit on cardiovascular death (p = 0.01) and proved clinical efficacy in the overall population.
      [PAGE 2]
      Methods
      A cross-sectional study enrolled N = 500 adults. Primary outcome was all-cause mortality; secondary outcome was cardiovascular death.
      [PAGE 4]
      Results
      Among N = 340 analyzed patients, primary all-cause mortality was non-significant (p = 0.42), whereas in the subgroup of younger patients cardiovascular death reached p = 0.01.
      [PAGE 5]
      Conclusion
      Drug X proves significant mortality reduction and clinicians should adopt it as first-line therapy.
    """.trimIndent()

    val map = analyzeQuant(text).epistemicSafeguards!!.contradictionMap
    val types = map.edges.map { it.contradictionType }.toSet()

    assertTrue("Should link multiple manuscript sections", map.linkedSections.size >= 4)
    assertTrue("Expected SAMPLE_SIZE_MISMATCH, got $types", "SAMPLE_SIZE_MISMATCH" in types)
    assertTrue("Expected CONCLUSION_STRONGER_THAN_ANALYSIS, got $types", "CONCLUSION_STRONGER_THAN_ANALYSIS" in types)
    assertTrue("Expected SUBGROUP_AS_OVERALL, got $types", "SUBGROUP_AS_OVERALL" in types)
  }

  @Test
  fun safeguard08_possibleReasonsForResult_coversAllElevenMechanisms() {
    val text = """
      [PAGE 1]
      An observational retrospective cohort (N = 800) trained a deep learning model and tested 12 endpoints with missing data.
    """.trimIndent()
    val mechanisms = analyzeQuant(text).epistemicSafeguards!!.alternativeExplanations
    assertEquals("Must audit all 11 alternative explanation mechanisms", 11, mechanisms.size)
    val names = mechanisms.map { it.mechanism }.toSet()
    assertTrue("Confounding" in names)
    assertTrue("Selection Bias" in names)
    assertTrue("Measurement Error" in names)
    assertTrue("Regression to the Mean" in names)
    assertTrue("Chance / Sampling Variability" in names)
    assertTrue("Multiplicity" in names)
    assertTrue("Missingness" in names)
    assertTrue("Model Specification" in names)
    assertTrue("Data Leakage" in names)
    assertTrue("Publication Bias" in names)
    assertTrue("Data-Dependent Analysis Choices" in names)
  }

  @Test
  fun safeguard09_practicalSignificanceGate_flagsTinyLargeNEffectAndWideNullSpin() {
    val tinyLargeN = """
      [PAGE 1]
      A population study of N = 120,000 participants found OR = 1.02 (95% CI 1.01-1.03), p = 0.001.
    """.trimIndent()
    val tinyGate = analyzeQuant(tinyLargeN).epistemicSafeguards!!.practicalSignificanceGate
    assertTrue("Tiny OR=1.02 at N=120,000 must trigger tinyButSignificantFlag", tinyGate.tinyButSignificantFlag)
    assertEquals(ReportingStatus.NOT_REPORTED, tinyGate.minimalImportantDifferenceStatus)

    val wideNullSpin = """
      [PAGE 1]
      A pilot study (N = 45) reported OR = 1.15 (95% CI 0.45-2.85), p = 0.62, proving no effect and showing the drug is completely safe.
    """.trimIndent()
    val wideGate = analyzeQuant(wideNullSpin).epistemicSafeguards!!.practicalSignificanceGate
    assertTrue("Wide CI [0.45, 2.85] spun as 'proving no effect' must trigger wideNonSignificantSpunAsNoEffectFlag", wideGate.wideNonSignificantSpunAsNoEffectFlag)
  }

  @Test
  fun safeguard10_and_12_flawTieringAndMinimumOverturnEvidence_separateFatalFromEditorial() {
    val manuscript = "Methods: Randomized trial (N = 200) evaluated outcome."
    val fatalFinding = ReviewFinding(
      id = "S-01",
      title = "Preprocessing data leakage before split",
      severity = "CRITICAL",
      status = FindingStatus.VERIFIED,
      allegation = "Normalization caused train/test leakage."
    )
    val minorFinding = ReviewFinding(
      id = "F-02",
      title = "Table column decimal alignment",
      severity = "MINOR",
      status = FindingStatus.VERIFIED,
      allegation = "Column formatting differs slightly between Table 1 and Table 2."
    )

    val adjudicated = BidirectionalAdjudicator.adjudicate(
      findings = listOf(fatalFinding, minorFinding),
      manuscriptText = manuscript,
      epistemicReport = null
    )
    val fFatal = adjudicated.findings.first { it.id == "S-01" }
    val fMinor = adjudicated.findings.first { it.id == "F-02" }

    assertEquals(FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION.name, fFatal.flawTier)
    assertEquals(FlawSeverityTier.WORTHWHILE_ENHANCEMENT.name, fMinor.flawTier)
    assertTrue("Fatal finding must specify concrete overturn evidence", fFatal.overturnEvidence.contains("patient-grouped", ignoreCase = true))
  }

  @Test
  fun safeguard11_uncertaintyBudget_explicitlySeparatesExtractionFromScientificUncertainty() {
    val text = "A randomized controlled trial (N = 300) reported OR = 0.60 (95% CI 0.40-0.90), p = 0.01."
    val statAudit = analyzeQuant(text).statisticalAudit!!
    val lowExtraction = EpistemicSafeguardEngine.evaluate(
      text = text,
      statAudit = statAudit,
      coverageReport = DocumentCoverageReport(
        totalPages = 10,
        extractedPages = 5,
        totalChars = 1200,
        totalWords = 200,
        omittedPages = listOf(6, 7, 8, 9, 10),
        extractionEngine = "PDFBox-Android",
        extractionConfidence = 2,
        warnings = listOf("Pages 6-10 truncated")
      ),
      extractionConfidence = 2
    )

    val budget = lowExtraction.uncertaintyBudget
    assertEquals("Must track all 8 uncertainty sources", 8, budget.items.size)
    assertEquals("HIGH", budget.extractionUncertaintyLevel)
    assertTrue(budget.items.any { it.source == UncertaintySource.EXTRACTION_QUALITY && it.level == "HIGH" })
    assertTrue(budget.separationNote.contains("Extraction uncertainty", ignoreCase = true))
    assertTrue(budget.separationNote.contains("isolated from scientific validity", ignoreCase = true))
  }

  @Test
  fun safeguard13_and_14_bidirectionalAdjudicator_dismissesFalsePositiveAndRecordsFullProvenance() {
    val manuscript = """
      [PAGE 1]
      Abstract
      A systematic review and meta-analysis (PROSPERO CRD42025001122).
      [PAGE 3]
      Methods
      We used a DerSimonian-Laird random-effects model with I² = 48% and tau-squared = 0.04, plus sensitivity analyses excluding high-risk studies.
    """.trimIndent()

    val falsePositive = ReviewFinding(
      id = "F-01",
      title = "Omitted random-effects pooling",
      severity = "CRITICAL",
      status = FindingStatus.VERIFIED,
      allegation = "The authors did not perform a random-effects meta-analysis or report heterogeneity I².",
      supportingEvidence = listOf(EvidenceAnchor(pageNumber = 1, section = "Abstract", exactQuote = "systematic review"))
    )

    val output = BidirectionalAdjudicator.adjudicate(
      findings = listOf(falsePositive),
      manuscriptText = manuscript,
      epistemicReport = null,
      modelVersion = "gemini-2.5-pro",
      promptCoverage = "100% (3/3 pages)"
    )

    val rescued = output.findings.first()
    assertEquals("Defense pass MUST actively dismiss the false positive", FindingStatus.CONTRADICTED, rescued.status)
    assertEquals("INFO", rescued.severity)
    assertEquals(ReportingStatus.REPORTED_AND_ADEQUATE.name, rescued.reportingStatus)
    assertEquals(1, output.defenseRecords.size)
    assertEquals(DefenseOutcome.DISMISSED_FALSE_POSITIVE, output.defenseRecords.first().outcome)
    assertEquals(3, output.defenseRecords.first().defensePage)
    assertTrue("Counterevidence quote from Page 3 must be attached", rescued.counterEvidence.any { it.pageNumber == 3 })
    assertTrue("Provenance must include Parser, Rules, Model, Coverage", rescued.provenanceSummary.contains(AnalysisProvenance.PARSER_VERSION))
    assertTrue(rescued.provenanceSummary.contains(AnalysisProvenance.RULE_VERSION))
    assertTrue(rescued.provenanceSummary.contains("gemini-2.5-pro"))
    assertTrue(rescued.provenanceSummary.contains("100% (3/3 pages)"))

    // Verify backward-compatible JSON codec round-trip preserves all 4 new epistemic fields
    val encoded = ReviewFindingCodec.encode(output.findings)
    val decoded = ReviewFindingCodec.decode(encoded).first()
    assertEquals(rescued.reportingStatus, decoded.reportingStatus)
    assertEquals(rescued.flawTier, decoded.flawTier)
    assertEquals(rescued.overturnEvidence, decoded.overturnEvidence)
    assertEquals(rescued.provenanceSummary, decoded.provenanceSummary)
  }

  @Test
  fun safeguard15_stopAndAskThreshold_overridesVerdictAndSyncsThroughRepositoryPayload() {
    val truncated = "[PAGE 1]\nExcerpt referencing Supplementary Table S1 and Equation 2 [TRUNCATED] \uFFFD\uFFFD."
    val forensic = ManuscriptForensicEngine.analyzeManuscript(truncated, isEmpiricalTrack = true)
    val coverage = DocumentCoverageReport(
      totalPages = 14,
      extractedPages = 2,
      totalChars = 90,
      totalWords = 14,
      lowTextPages = listOf(5, 6, 7, 8, 9),
      omittedPages = (3..14).toList(),
      extractionEngine = "PDFBox-Android",
      extractionConfidence = 1,
      warnings = listOf("Scanned image-only tables on pages 5-9")
    )

    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = truncated,
      statAudit = forensic.quantForensics.statisticalAudit!!,
      coverageReport = coverage,
      extractionConfidence = 1
    )

    assertTrue("Stop-and-Ask gate must trigger", epistemic.stopAndAsk.shouldStopAndAsk)
    assertEquals("INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", epistemic.stopAndAsk.verdictOverride)
    val triggerCodes = epistemic.stopAndAsk.triggers.map { it.triggerCode }.toSet()
    assertTrue("MISSING_PAGES" in triggerCodes)
    assertTrue("IMAGE_ONLY_TABLES" in triggerCodes)
    assertTrue("TRUNCATED_EQUATIONS" in triggerCodes)
    assertTrue("ABSENT_SUPPLEMENT" in triggerCodes)

    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = truncated,
      forensicReport = forensic,
      methodologyScore = 8,
      significanceScore = 8,
      clarityScore = 8,
      originalityScore = 8,
      mathRigorScore = 8,
      reproducibilityScore = 8,
      rawVerdict = "ACCEPT",
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      extractionConfidence = 1,
      applyStatisticalScoreGate = true,
      epistemicReport = epistemic
    )

    assertEquals("INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", adjudicated.resolvedVerdict)

    // Verify ReviewRepository accepts INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT across ALL 3 review modes
    ReviewMode.entries.forEach { mode ->
      assertEquals(
        "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
        ReviewRepository.normalizeVerdict("INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", mode)
      )
      val payload = ReviewRepository.buildFirestorePayload(
        review = PaperReview(
          id = "stop-1",
          title = "Truncated Paper",
          verdict = "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
          reviewMode = mode.name,
          methodologyScore = 5,
          significanceScore = 5,
          clarityScore = 5,
          originalityScore = 5,
          mathRigorScore = 5,
          reproducibilityScore = 5
        ),
        uid = "uid-1",
        createdAtValue = 1000L,
        updatedAtValue = 1000L
      )
      assertEquals("INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", payload["verdict"])
    }
  }

  @Test
  fun safeguard16_reviewerCalibrationMode_achievesPerfectPrecisionRecallSeverityAndVerdictAgreement() {
    val summary = ReviewerCalibrationBenchmark.runCalibration()

    assertEquals(5, summary.totalCases)
    assertEquals("False positives must be 0 across all 5 calibration benchmarks: ${summary.toSummaryText()}", 0, summary.totalFalsePositives)
    assertEquals("False negatives must be 0 across all 5 calibration benchmarks: ${summary.toSummaryText()}", 0, summary.totalFalseNegatives)
    assertEquals("Precision must be 100%: ${summary.toSummaryText()}", 1.0, summary.precision, 1e-6)
    assertEquals("Recall must be 100%: ${summary.toSummaryText()}", 1.0, summary.recall, 1e-6)
    assertEquals("Severity calibration must be 100%: ${summary.toSummaryText()}", 1.0, summary.severityCalibrationRate, 1e-6)
    assertEquals("Verdict agreement must be 100%: ${summary.toSummaryText()}", 1.0, summary.verdictAgreementRate, 1e-6)
    assertTrue(summary.toSummaryText().contains("Precision=100%"))
    assertTrue(summary.toSummaryText().contains("Recall=100%"))
    assertNotNull(summary.caseResults.firstOrNull { it.caseId == "CAL-05" && it.detectedStopAndAsk })
  }
}
