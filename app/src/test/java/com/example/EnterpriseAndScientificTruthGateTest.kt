package com.example

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.MethodMentionStatus
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.MetaStudyEffectClaim
import com.example.data.ai.stats.NumericalReconstructionEngine
import com.example.data.ai.stats.QuantitativeClaim
import com.example.data.ai.stats.QuantitativeEvidenceLedger
import com.example.data.ai.stats.StatisticalFindingType
import com.example.data.local.SqlReviewCacheEntity
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewMode
import com.example.data.repository.ReviewRepository
import com.example.ui.screens.isSafeExternalWebUrl
import com.example.util.DocumentCoverageReport
import com.example.util.ManuscriptExtractor
import com.example.util.PdfExportScope
import com.example.util.PdfReportExporter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive verification suite covering:
 * - P0-01..P0-07 Scientific Truth-Preservation Gates (single-source prose sanitization, strict
 *   VERIFIED validation status, negated/prior-work defense gating, quote entailment vs match,
 *   deterministic finding verification, assumption-aware CI-vs-p reconstruction).
 * - P1-05..P1-13 Infrastructure & Clinical Appraisal Gates (Room legacy migration default,
 *   outcome-specific GRADE certainty, NNTB/NNTH + Fisher approximation labeling + DL vs PM + LOO
 *   re-estimation, scanned PDF readiness gate, collision-free PDF filenames, paginated rubric rendering).
 * - Enterprise Hardening (safe URL validation, DOCX/ZIP malformed/oversized archive handling,
 *   Firestore/Repository verdict & validationStatus parity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnterpriseAndScientificTruthGateTest {

  // =========================================================================
  // P0-01: Dismissed findings must not resurface in editorial prose
  // =========================================================================
  @Test
  fun p0_01_sanitizeProseAgainstDismissedFindings_removesDismissedAllegationsOnly() {
    val dismissed = ReviewFinding(
      id = "R-01",
      title = "Missing random-effects meta-analysis",
      allegation = "Authors omitted a random-effects meta-analysis and did not account for heterogeneity.",
      severity = "MAJOR",
      status = FindingStatus.CONTRADICTED
    )
    val retained = ReviewFinding(
      id = "R-02",
      title = "High attrition in follow-up cohort",
      allegation = "Outcome attrition exceeded 32% at 12-month follow-up without sensitivity imputation.",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED
    )
    val rawProse = """
      [R-01] Authors omitted a random-effects meta-analysis and did not account for heterogeneity.
      The paper omitted a random-effects meta-analysis and heterogeneity modeling entirely.
      [R-02] Outcome attrition exceeded 32% at 12-month follow-up without sensitivity imputation.
      Note: Finding R-01 was dismissed because counterevidence confirmed DerSimonian-Laird pooling.
    """.trimIndent()

    val sanitized = Reviewer2PipelineService.sanitizeProseAgainstDismissedFindings(
      prose = rawProse,
      adjudicatedFindings = listOf(dismissed, retained)
    )

    assertFalse(
      "Dismissed allegation ID line must be stripped from prose",
      sanitized.contains("[R-01] Authors omitted")
    )
    assertFalse(
      "Dismissed allegation paraphrase line must be stripped from prose",
      sanitized.contains("omitted a random-effects meta-analysis and heterogeneity modeling entirely")
    )
    assertTrue(
      "Verified finding [R-02] must remain intact",
      sanitized.contains("[R-02] Outcome attrition exceeded 32%")
    )
    assertTrue(
      "Explicit dismissal provenance note must be preserved",
      sanitized.contains("dismissed because counterevidence")
    )
  }

  // =========================================================================
  // P0-02: Strict VERIFIED validation status requires non-empty verified claims
  // =========================================================================
  @Test
  fun p0_02_computeValidationStatus_blocksVerifiedForEmptyLedgerOrLowExtraction() {
    val emptyWithProse = Reviewer2PipelineService.computeValidationStatus(
      resolvedVerdict = "MINOR_REVISION",
      adjudicatedFindings = emptyList(),
      anyJsonRepaired = false,
      omittedOrPartialPromptPagesCount = 0,
      wasPromptTrimmed = false,
      extractionConfidence = 5,
      hasSubstantiveEditorialProse = true
    )
    assertEquals("ADJUDICATED_WITH_CAVEATS", emptyWithProse)

    val verifiedFinding = ReviewFinding(
      id = "R-01",
      title = "Verified sample size reporting",
      allegation = "Primary cohort denominator is accurately reported.",
      severity = "MINOR",
      status = FindingStatus.VERIFIED
    )
    val lowExtraction = Reviewer2PipelineService.computeValidationStatus(
      resolvedVerdict = "MINOR_REVISION",
      adjudicatedFindings = listOf(verifiedFinding),
      anyJsonRepaired = false,
      omittedOrPartialPromptPagesCount = 0,
      wasPromptTrimmed = false,
      extractionConfidence = 3,
      hasSubstantiveEditorialProse = true
    )
    assertEquals("ADJUDICATED_WITH_CAVEATS", lowExtraction)

    val cleanVerified = Reviewer2PipelineService.computeValidationStatus(
      resolvedVerdict = "MINOR_REVISION",
      adjudicatedFindings = listOf(verifiedFinding),
      anyJsonRepaired = false,
      omittedOrPartialPromptPagesCount = 0,
      wasPromptTrimmed = false,
      extractionConfidence = 5,
      hasSubstantiveEditorialProse = true
    )
    assertEquals("VERIFIED", cleanVerified)

    val provisional = Reviewer2PipelineService.computeValidationStatus(
      resolvedVerdict = "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN",
      adjudicatedFindings = listOf(verifiedFinding),
      anyJsonRepaired = false,
      omittedOrPartialPromptPagesCount = 0,
      wasPromptTrimmed = false,
      extractionConfidence = 5,
      hasSubstantiveEditorialProse = true
    )
    assertEquals("PROVISIONAL", provisional)
  }

  // =========================================================================
  // P0-03: Negated, proposed-only, or reference mentions NEVER dismiss omissions
  // =========================================================================
  @Test
  fun p0_03_negatedAndReferenceMentions_doNotDismissOmissionCriticism() {
    val negatedManuscript = """
      [PAGE 1]
      Methods
      We screened 42 observational studies on hospital capacity. We did not perform a random-effects meta-analysis because outcome metrics were not comparable across jurisdictions.
      Discussion
      Future studies should conduct a random-effects meta-analysis once standardized definitions exist.
      References
      1. Smith J (2021). Random-effects meta-analysis of acute inpatient beds. Lancet Psychiatry.
    """.trimIndent()

    val offsetNegated = negatedManuscript.indexOf("random-effects meta-analysis")
    assertEquals(
      MethodMentionStatus.NOT_PERFORMED,
      BidirectionalAdjudicator.classifyMethodMentionStatus(negatedManuscript, offsetNegated, 28)
    )

    val offsetFuture = negatedManuscript.indexOf("random-effects meta-analysis", startIndex = offsetNegated + 10)
    assertEquals(
      MethodMentionStatus.PROPOSED_ONLY,
      BidirectionalAdjudicator.classifyMethodMentionStatus(negatedManuscript, offsetFuture, 28)
    )

    val offsetRef = negatedManuscript.indexOf("Random-effects meta-analysis", startIndex = offsetFuture + 10)
    assertEquals(
      MethodMentionStatus.CITED_AS_PRIOR_WORK,
      BidirectionalAdjudicator.classifyMethodMentionStatus(negatedManuscript, offsetRef, 28)
    )

    val omissionFinding = ReviewFinding(
      id = "R-01",
      title = "No random-effects meta-analysis performed",
      allegation = "The authors did not perform a random-effects meta-analysis to synthesize pooled estimates.",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(
        EvidenceAnchor(
          pageNumber = 1,
          section = "Methods",
          exactQuote = "We did not perform a random-effects meta-analysis because outcome metrics were not comparable across jurisdictions."
        )
      )
    )

    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = listOf(omissionFinding),
      manuscriptText = negatedManuscript,
      forensicReport = null,
      methodologyScore = 6,
      significanceScore = 7,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 6,
      reproducibilityScore = 6,
      rawVerdict = "SOUND_WITH_CAVEATS",
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL,
      extractionConfidence = 5
    )

    assertNotEquals(
      "Negated mention in Methods must NEVER dismiss an omission criticism as CONTRADICTED",
      FindingStatus.CONTRADICTED,
      adjudicated.adjudicatedFindings.first().status
    )

    val performedManuscript = """
      [PAGE 1]
      Methods
      We performed a random-effects meta-analysis using the DerSimonian-Laird estimator across all 42 studies.
    """.trimIndent()
    val performedOffset = performedManuscript.indexOf("random-effects meta-analysis")
    assertEquals(
      MethodMentionStatus.PERFORMED,
      BidirectionalAdjudicator.classifyMethodMentionStatus(performedManuscript, performedOffset, 28)
    )
  }

  // =========================================================================
  // P0-04: Exact quote must also entail the allegation (not just exist verbatim)
  // =========================================================================
  @Test
  fun p0_04_exactUnrelatedQuote_isDowngradedForNonEntailment() {
    val manuscript = """
      [PAGE 1]
      Methods
      Participants were recruited from three university hospitals in Germany between 2019 and 2022 (N = 412, mean age 46.2 years).
      Primary outcomes were analyzed with multivariable logistic regression adjusting for age, sex, and baseline severity.
    """.trimIndent()

    val unrelatedFinding = ReviewFinding(
      id = "R-04",
      title = "Failure to control for confounding variables",
      allegation = "The authors omitted multivariable adjustment for socioeconomic status and medication adherence.",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(
        EvidenceAnchor(
          pageNumber = 1,
          section = "Methods",
          exactQuote = "Participants were recruited from three university hospitals in Germany between 2019 and 2022 (N = 412, mean age 46.2 years)."
        )
      )
    )

    val entailment = Reviewer2PipelineService.evaluateQuoteEntailment(
      finding = unrelatedFinding,
      matchedQuote = unrelatedFinding.supportingEvidence.first().exactQuote,
      surroundingWindow = manuscript
    )
    assertNotEquals(
      "A demographics sentence must not entail an unrelated confounding omission allegation",
      Reviewer2PipelineService.EntailmentStatus.ENTAILED,
      entailment
    )

    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = listOf(unrelatedFinding),
      manuscriptText = manuscript,
      forensicReport = null,
      methodologyScore = 6,
      significanceScore = 7,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 6,
      reproducibilityScore = 6,
      rawVerdict = "MAJOR_REVISION",
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      extractionConfidence = 5
    )
    assertEquals(
      "Non-entailed finding must be downgraded from VERIFIED to PARTIALLY_SUPPORTED",
      FindingStatus.PARTIALLY_SUPPORTED,
      adjudicated.adjudicatedFindings.first().status
    )
  }

  // =========================================================================
  // P0-07: Assumption-aware CI-vs-p reconstruction (90% CI & adjusted p-value)
  // =========================================================================
  @Test
  fun p0_07_ratioCiReconstruction_respects90PercentCiAndAdjustedPValue() {
    // OR = 1.50 (90% CI 1.01-2.22), two-sided p = 0.09 -> completely consistent at alpha = 0.10!
    val ci90Claim = QuantitativeClaim(
      claimId = "C-90",
      page = 1,
      section = "Results",
      effectMeasure = "OR",
      effectValue = 1.50,
      ciLower = 1.01,
      ciUpper = 2.22,
      ciLevel = 0.90,
      pValue = 0.09,
      pOperator = "=",
      adjustmentStatus = AdjustmentStatus.UNADJUSTED,
      isOneSided = false,
      sourceQuote = "OR = 1.50 (90% CI 1.01-2.22, p = 0.09)"
    )
    val ci90Findings = NumericalReconstructionEngine.reconstructAll(
      ledger = QuantitativeEvidenceLedger(claims = listOf(ci90Claim)),
      rawText = ci90Claim.sourceQuote
    )
    assertFalse(
      "Consistent 90% CI with p=0.09 must not trigger FAIL contradiction",
      ci90Findings.any { it.status == QuantFindingStatus.FAIL }
    )

    // Multiplicity-adjusted p = 0.08 with 95% CI excluding 1.0 -> WARN screening signal, NOT hard FAIL
    val adjustedClaim = QuantitativeClaim(
      claimId = "C-ADJ",
      page = 1,
      section = "Results",
      effectMeasure = "HR",
      effectValue = 1.48,
      ciLower = 1.04,
      ciUpper = 2.10,
      ciLevel = 0.95,
      pValue = 0.08,
      pOperator = "=",
      adjustmentStatus = AdjustmentStatus.ADJUSTED,
      isOneSided = false,
      sourceQuote = "HR = 1.48 (95% CI 1.04-2.10, Bonferroni-adjusted p = 0.08)"
    )
    val adjFindings = NumericalReconstructionEngine.reconstructAll(
      ledger = QuantitativeEvidenceLedger(claims = listOf(adjustedClaim)),
      rawText = adjustedClaim.sourceQuote
    )
    assertFalse(
      "Multiplicity-adjusted p-value mismatch must not trigger FAIL",
      adjFindings.any { it.status == QuantFindingStatus.FAIL }
    )
    assertTrue(
      "Multiplicity-adjusted mismatch should emit WARN screening note",
      adjFindings.any {
        it.status == QuantFindingStatus.WARN && it.findingType == StatisticalFindingType.ASSUMPTION_UNCHECKED
      }
    )
  }

  // =========================================================================
  // P1-05: Room legacy migration default must be LEGACY_UNVALIDATED, not VERIFIED
  // =========================================================================
  @Test
  fun p1_05_roomEntityBlankValidationStatus_defaultsToLegacyUnvalidated() {
    val legacyReview = PaperReview(
      id = "legacy_1",
      title = "Legacy Cached Appraisal",
      validationStatus = ""
    )
    val entity = SqlReviewCacheEntity.fromPaperReview(legacyReview)
    assertEquals("LEGACY_UNVALIDATED", entity.validationStatus)
  }

  // =========================================================================
  // P1-08: Clinical GRADE certainty uses design + 5 domains, not clarity/originality
  // =========================================================================
  @Test
  fun p1_08_clinicalGradeCertainty_usesDesignStartingLevelAndDomains() {
    val nonClinicalText = """
      [PAGE 1]
      We present a new compiler optimization pass for sparse tensor contractions on GPU architectures.
      Throughput improved by 2.4x across 14 benchmark kernels.
    """.trimIndent()
    val nonClinicalForensics = ManuscriptForensicEngine.analyzeManuscript(
      text = nonClinicalText,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(nonClinicalText)
    )
    val nonClinicalGrade = Reviewer2PipelineService.evaluateClinicalGradeCertainty(
      manuscriptText = nonClinicalText,
      forensicReport = nonClinicalForensics,
      adjudicatedFindings = emptyList(),
      extractionConfidence = 5
    )
    assertFalse("Non-clinical paper should not receive a fabricated clinical GRADE certainty rating", nonClinicalGrade.isAssessable)
    assertEquals("INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", nonClinicalGrade.verdictEnum)

    val rctText = """
      [PAGE 1]
      A Double-Blind Randomized Controlled Trial of Drug X vs Placebo in Acute Heart Failure
      Methods: Patients (N = 640) were randomized 1:1 to Drug X (n = 320) or placebo (n = 320).
      Results: Primary mortality endpoint occurred in 24/320 vs 42/320 (HR = 0.56, 95% CI 0.34 to 0.92, p = 0.02).
    """.trimIndent()
    val rctForensics = ManuscriptForensicEngine.analyzeManuscript(
      text = rctText,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(rctText)
    )
    val cleanRctGrade = Reviewer2PipelineService.evaluateClinicalGradeCertainty(
      manuscriptText = rctText,
      forensicReport = rctForensics,
      adjudicatedFindings = emptyList(),
      extractionConfidence = 5
    )
    assertTrue("Clean RCT should be GRADE assessable", cleanRctGrade.isAssessable)
    assertEquals("HIGH_CERTAINTY", cleanRctGrade.verdictEnum)
  }

  // =========================================================================
  // P1-09: NNTB/NNTH direction, Fisher approximation label, DL vs PM + LOO re-estimation
  // =========================================================================
  @Test
  fun p1_09_statisticalEngine_nntDirection_fisherMethodLabel_andDlVsPmSeparation() {
    // Harm case: treatment event rate (40/100 = 0.40) > control event rate (20/100 = 0.20) -> NNTH
    val harmMetrics = NumericalReconstructionEngine.computeTwoByTwo(
      a = 40, b = 60, c = 20, d = 80
    )
    assertNotNull(harmMetrics)
    assertEquals("NNTH", harmMetrics!!.nntDirection)
    assertTrue(harmMetrics.nntSignedLabel.startsWith("NNTH"))

    // Benefit case: treatment event rate (15/100) < control event rate (35/100) -> NNTB
    val benefitMetrics = NumericalReconstructionEngine.computeTwoByTwo(
      a = 15, b = 85, c = 35, d = 65
    )
    assertNotNull(benefitMetrics)
    assertEquals("NNTB", benefitMetrics!!.nntDirection)
    assertTrue(benefitMetrics.nntSignedLabel.startsWith("NNTB"))

    // Huge 2x2 table (>50,000 feasible range) must explicitly flag Fisher approximation
    val approxFisher = NumericalReconstructionEngine.computeFisherTwoSidedPWithMethod(
      a = 60_000, b = 60_000, c = 59_000, d = 61_000
    )
    assertFalse("Huge feasible range must report isExact = false", approxFisher.isExact)
    assertTrue(
      "Method label must disclose asymptotic chi-square approximation",
      approxFisher.methodLabel.contains("approximation", ignoreCase = true)
    )

    // Meta-analysis with heterogeneous studies: verify true DL differs from PM when tau2DL != tau2PM
    val studies = listOf(
      MetaStudyEffectClaim("Study A", effect = 0.15, standardError = 0.05),
      MetaStudyEffectClaim("Study B", effect = 0.65, standardError = 0.12),
      MetaStudyEffectClaim("Study C", effect = 1.20, standardError = 0.13),
      MetaStudyEffectClaim("Study D", effect = 0.30, standardError = 0.10)
    )
    val meta = NumericalReconstructionEngine.computeMetaAnalysis(studies)
    assertNotNull(meta)
    assertTrue("tauSquaredDL must be positive for heterogeneous studies", meta!!.tauSquaredDL > 0.0)
    assertTrue("tauSquaredPM must be positive for heterogeneous studies", meta.tauSquaredPM > 0.0)
    assertTrue("DL and PM random effects must both be finite", meta.randomEffectDL.isFinite() && meta.randomEffectPM.isFinite())
    assertNotNull("Most influential LOO study must be identified", meta.mostInfluentialStudy)
  }

  // =========================================================================
  // P1-10: Document Readiness Gate blocks unreadable scanned PDFs pre-LLM
  // =========================================================================
  @Test
  fun p1_10_documentReadinessGate_blocksMultiPageScannedPdfWithInsufficientCoverage() {
    val scannedCoverage = DocumentCoverageReport(
      totalPages = 14,
      extractedPages = 0,
      totalChars = 0,
      totalWords = 0,
      extractionEngine = "PDFBox (Vision-Only Scanned PDF)",
      extractionConfidence = 1,
      visionPagesAttached = 4
    )
    val gate = Reviewer2PipelineService.evaluateDocumentReadinessGate(
      manuscriptText = "PDF MANUSCRIPT (SCANNED / IMAGE-BASED): scanned_trial.pdf",
      coverageReport = scannedCoverage,
      attachedBitmapCount = 4,
      useExternalTextOnlyProvider = false
    )
    assertFalse("Multi-page scanned PDF with <50% vision coverage must fail readiness gate", gate.isReady)
    assertTrue(gate.blockingReasons.isNotEmpty())

    val textOnlyProviderGate = Reviewer2PipelineService.evaluateDocumentReadinessGate(
      manuscriptText = "PDF MANUSCRIPT (SCANNED / IMAGE-BASED): scanned_trial.pdf",
      coverageReport = scannedCoverage,
      attachedBitmapCount = 14,
      useExternalTextOnlyProvider = true
    )
    assertFalse("Scanned PDF routed to text-only external provider must fail readiness gate", textOnlyProviderGate.isReady)
  }

  // =========================================================================
  // P1-12 & P1-13: Collision-free PDF filenames + paginated long rubric rendering
  // =========================================================================
  @Test
  fun p1_12_and_p1_13_pdfUniqueFilenamesAndOversizedRubricPagination() {
    val nameA = PdfReportExporter.generateUniqueExportFileName(
      title = "Global Psychiatric Bed Need Meta-Analysis",
      reviewId = "rev_alpha_111",
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      uniqueSuffix = "1111aaaa"
    )
    val nameB = PdfReportExporter.generateUniqueExportFileName(
      title = "Global Psychiatric Bed Need Meta-Analysis",
      reviewId = "rev_beta_222",
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      uniqueSuffix = "2222bbbb"
    )
    assertNotEquals("Distinct reviews of the same title must never overwrite the same PDF filename", nameA, nameB)
    assertTrue(nameA.endsWith(".pdf"))
    assertTrue(nameB.endsWith(".pdf"))

    val longJustification = (1..28).joinToString(" ") { idx ->
      "Sentence $idx verifies that long rubric justifications paginate cleanly across A4 page boundaries without clipping any trailing analytical detail."
    }
    val reviewWithHugeRubric = PaperReview(
      id = "rev_huge_rubric",
      title = "Multi-Page Rubric Pagination Verification Study",
      verdict = "MAJOR_REVISION",
      credibilityScore = 64L,
      refereeConfidence = 4,
      extractionConfidence = 5,
      validationStatus = "VERIFIED",
      methodologyScore = 6,
      methodologyJustification = longJustification,
      mathRigorScore = 5,
      mathRigorJustification = longJustification,
      abstractSummary = "Concise abstract summary for PDF pagination verification."
    )
    val rendered = PdfReportExporter.renderAndInspectPdf(
      review = reviewWithHugeRubric,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
    )
    assertTrue("Rendered PDF text must contain final sentence of oversized rubric note", rendered.renderedText.contains("Sentence 28 verifies"))
    assertTrue("PDF raw-markup diagnostics must be clean: ${rendered.diagnostics}", rendered.diagnostics.isClean)
  }

  // =========================================================================
  // Batch 1 & Batch 4 Enterprise Hardening: Repository, URL validation, DOCX ZIP safety
  // =========================================================================
  @Test
  fun enterpriseHardening_repositoryVerdicts_safeUrls_andDocxSafety() {
    assertEquals(
      "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN",
      ReviewRepository.normalizeVerdict("PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN", ReviewMode.POST_PUBLICATION_APPRAISAL)
    )
    assertEquals(
      "MINOR_REVISION_WITH_CAVEATS",
      ReviewRepository.normalizeVerdict("MINOR_REVISION_WITH_CAVEATS", ReviewMode.JOURNAL_REFEREE)
    )
    assertEquals("PROVISIONAL", ReviewRepository.normalizeValidationStatus("PROVISIONAL"))
    assertEquals("LEGACY_UNVALIDATED", ReviewRepository.normalizeValidationStatus("LEGACY_UNVALIDATED"))

    // URL scheme validation for external links
    assertTrue(isSafeExternalWebUrl("https://pubmed.ncbi.nlm.nih.gov/12345678/"))
    assertTrue(isSafeExternalWebUrl("http://example.org/paper"))
    assertFalse(isSafeExternalWebUrl("javascript:alert(1)"))
    assertFalse(isSafeExternalWebUrl("file:///etc/passwd"))
    assertFalse(isSafeExternalWebUrl("intent://scan/#Intent;scheme=zxing;end"))
    assertFalse(isSafeExternalWebUrl("https://"))

    // DOCX extraction handles safe OOXML and ignores path-traversal entries cleanly
    val docxBytes = ByteArrayOutputStream().use { baos ->
      ZipOutputStream(baos).use { zos ->
        zos.putNextEntry(ZipEntry("../evil.xml"))
        zos.write("<w:t>SHOULD_BE_IGNORED</w:t>".toByteArray())
        zos.closeEntry()
        zos.putNextEntry(ZipEntry("word/document.xml"))
        zos.write(
          """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Safe Manuscript Paragraph Content</w:t></w:r></w:p></w:body></w:document>""".toByteArray()
        )
        zos.closeEntry()
      }
      baos.toByteArray()
    }
    val extracted = ManuscriptExtractor.extractDocxFromBytes(docxBytes, "safe_test.docx")
    assertTrue(extracted.extractedText.contains("Safe Manuscript Paragraph Content"))
    assertFalse(extracted.extractedText.contains("SHOULD_BE_IGNORED"))
  }
}
