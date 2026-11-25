package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.EpistemicSafeguardEngine
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.EvidenceDerivedScoreGate
import com.example.data.ai.stats.IntervalType
import com.example.data.ai.stats.MetaStudyEffectClaim
import com.example.data.ai.stats.NumericalReconstructionEngine
import com.example.data.ai.stats.QuantitativeClaim
import com.example.data.ai.stats.StatisticalAuditOrchestrator
import com.example.data.ai.stats.StatisticalClaimParser
import com.example.data.ai.stats.StatisticalFindingType
import com.example.data.ai.stats.TailSpecification
import com.example.data.local.ANONYMOUS_OWNER_SCOPE
import com.example.data.local.ApiKeyVault
import com.example.data.local.PaperCriticSqlDatabase
import com.example.data.local.SqlChatMessageEntity
import com.example.data.local.SqlReviewCacheEntity
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.data.repository.LocalReviewRepository
import com.example.data.repository.ReviewRepository
import com.example.ui.screens.isSafeExternalWebUrl
import com.example.ui.viewmodel.AiProviderRoute
import com.example.ui.viewmodel.AppSettingsState
import com.example.ui.viewmodel.PaperCriticViewModel
import com.example.util.DocumentCoverageReport
import com.example.util.DossierFormattingEngine
import com.example.util.ManuscriptExtractor
import com.example.util.PdfExportScope
import com.example.util.PdfReportExporter
import com.example.util.ReportBlock
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    assertEquals(
      BidirectionalAdjudicator.MethodMentionStatus.NOT_PERFORMED,
      BidirectionalAdjudicator.classifyMethodMentionStatus(
        sentence = "We did not perform a random-effects meta-analysis because outcome metrics were not comparable across jurisdictions.",
        matchedValue = "random-effects meta-analysis",
        sectionName = "Methods"
      )
    )

    assertEquals(
      BidirectionalAdjudicator.MethodMentionStatus.PROPOSED_ONLY,
      BidirectionalAdjudicator.classifyMethodMentionStatus(
        sentence = "Future studies should conduct a random-effects meta-analysis once standardized definitions exist.",
        matchedValue = "random-effects meta-analysis",
        sectionName = "Discussion"
      )
    )

    assertEquals(
      BidirectionalAdjudicator.MethodMentionStatus.CITED_AS_PRIOR_WORK,
      BidirectionalAdjudicator.classifyMethodMentionStatus(
        sentence = "1. Smith J (2021). Random-effects meta-analysis of acute inpatient beds. Lancet Psychiatry.",
        matchedValue = "Random-effects meta-analysis",
        sectionName = "References"
      )
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

    assertEquals(
      BidirectionalAdjudicator.MethodMentionStatus.PERFORMED,
      BidirectionalAdjudicator.classifyMethodMentionStatus(
        sentence = "We performed a random-effects meta-analysis using the DerSimonian-Laird estimator across all 42 studies.",
        matchedValue = "random-effects meta-analysis",
        sectionName = "Methods"
      )
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
      Reviewer2PipelineService.Companion.EntailmentStatus.ENTAILED,
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
      pointEstimate = 1.50,
      ciLower = 1.01,
      ciUpper = 2.22,
      ciLevel = 0.90,
      intervalType = IntervalType.CONFIDENCE_INTERVAL,
      pValue = 0.09,
      pValueOperator = "=",
      adjustedOrUnadjusted = AdjustmentStatus.UNADJUSTED,
      tailSpec = TailSpecification.TWO_SIDED,
      sourceQuote = "OR = 1.50 (90% CI 1.01-2.22, p = 0.09)"
    )
    val ci90Findings = NumericalReconstructionEngine.runAllReconstructions(
      ledger = StatisticalClaimParser.ParsedManuscriptLedger(
        claims = listOf(ci90Claim),
        twoByTwoTables = emptyList(),
        metaStudyEffects = emptyList(),
        sampleFlowRecords = emptyList(),
        crossLinks = emptyList(),
        unsupportedDisclosures = emptyList(),
        globalTailSpec = TailSpecification.TWO_SIDED,
        globalAlpha = 0.05
      ),
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
      pointEstimate = 1.48,
      ciLower = 1.04,
      ciUpper = 2.10,
      ciLevel = 0.95,
      intervalType = IntervalType.CONFIDENCE_INTERVAL,
      pValue = 0.08,
      pValueOperator = "=",
      adjustedOrUnadjusted = AdjustmentStatus.ADJUSTED,
      tailSpec = TailSpecification.TWO_SIDED,
      sourceQuote = "HR = 1.48 (95% CI 1.04-2.10, Bonferroni-adjusted p = 0.08)"
    )
    val adjFindings = NumericalReconstructionEngine.runAllReconstructions(
      ledger = StatisticalClaimParser.ParsedManuscriptLedger(
        claims = listOf(adjustedClaim),
        twoByTwoTables = emptyList(),
        metaStudyEffects = emptyList(),
        sampleFlowRecords = emptyList(),
        crossLinks = emptyList(),
        unsupportedDisclosures = emptyList(),
        globalTailSpec = TailSpecification.TWO_SIDED,
        globalAlpha = 0.05
      ),
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
      significanceScore = 7,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 5,
      mathRigorJustification = longJustification,
      reproducibilityScore = 6,
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

  // =========================================================================
  // SECTION 5 AUDIT REGRESSION SUITE (ALL 12 RELEASE-BLOCKER REGRESSION TESTS)
  // =========================================================================

  @Test
  fun audit01_accountIsolation_userBSeesZeroOfUserAReviewsChatsAndProviders() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
    ApiKeyVault.keyProviderOverride = { keyGen.generateKey() }
    try {
      val repo = LocalReviewRepository(db.sqlDao())
      val reviewA = PaperReview(
        id = "rev_user_a_01",
        userId = "uid_user_a",
        title = "Confidential Trial By User A",
        abstractSummary = "Private pre-publication clinical manuscript."
      )
      repo.cacheReviewOffline(reviewA, ownerScope = "uid_user_a")
      repo.insertChatMessage(
        SqlChatMessageEntity(
          reviewId = "rev_user_a_01",
          ownerScope = "uid_user_a",
          role = "user",
          personaId = "methodologist",
          modelTierId = "flash",
          content = "Private methodological question from User A"
        )
      )
      repo.upsertApiProvider(
        AiApiProviderEntry(
          id = "custom_openai",
          serviceName = "OpenAI User A",
          protocolType = ApiProtocolType.OPENAI_COMPATIBLE_REST,
          baseUrl = "https://api.openai.com/v1/chat/completions",
          modelId = "gpt-4o",
          apiKey = "sk-user-a-secret-key",
          isFreeTierPreset = false,
          enabled = true,
          notes = "User A personal key"
        ),
        ownerScope = "uid_user_a"
      )

      // User B signs in on same device: must see ZERO of User A's data
      assertTrue(repo.getCachedReviewsOnce("uid_user_b").isEmpty())
      assertTrue(repo.observeChatMessages("rev_user_a_01", "uid_user_b").first().isEmpty())
      assertTrue(repo.observeApiProviders("uid_user_b").first().isEmpty())

      // User B attempting to delete User A's review ID must NOT affect User A's row
      repo.removeCachedReview("rev_user_a_01", ownerScope = "uid_user_b")
      assertEquals(1, repo.getCachedReviewsOnce("uid_user_a").size)
      assertEquals(1, repo.observeChatMessages("rev_user_a_01", "uid_user_a").first().size)
      assertEquals(1, repo.observeApiProviders("uid_user_a").first().size)
    } finally {
      ApiKeyVault.keyProviderOverride = null
      db.close()
    }
  }

  @Test
  fun audit02_anonymousDraftsRemainLocalOnSignInUntilExplicitlyAdopted() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = LocalReviewRepository(db.sqlDao())
      val anonDraft = PaperReview(
        id = "anon_local_01",
        userId = "",
        title = "Signed-Out Anonymous Draft Manuscript"
      )
      repo.cacheReviewOffline(anonDraft, ownerScope = ANONYMOUS_OWNER_SCOPE)
      repo.insertChatMessage(
        SqlChatMessageEntity(
          reviewId = "anon_local_01",
          ownerScope = ANONYMOUS_OWNER_SCOPE,
          role = "user",
          personaId = "editor",
          modelTierId = "flash",
          content = "Signed-out follow-up question"
        )
      )

      // Before explicit adoption: User B's account scope has zero reviews, anonymous scope still holds draft
      assertTrue(repo.getCachedReviewsOnce("uid_user_b").isEmpty())
      assertEquals(1, repo.observeAnonymousReviews().first().size)

      // After explicit opt-in adoption: draft + chat migrate cleanly to User B
      val adoptedCount = repo.adoptAnonymousReviewsToAccount(listOf("anon_local_01"), "uid_user_b")
      assertEquals(1, adoptedCount)
      assertTrue(repo.observeAnonymousReviews().first().isEmpty())
      val adoptedReviews = repo.getCachedReviewsOnce("uid_user_b")
      assertEquals(1, adoptedReviews.size)
      assertEquals("uid_user_b", adoptedReviews.first().userId)
      assertEquals(1, repo.observeChatMessages("anon_local_01", "uid_user_b").first().size)
    } finally {
      db.close()
    }
  }

  @Test
  fun audit03_sessionSwitchRaceGuard_discardsStaleInFlightResults() {
    assertTrue(
      PaperCriticViewModel.canCommitInferenceResult(
        initiatingOwnerScope = "uid_user_a",
        initiatingSessionGen = 4L,
        currentOwnerScope = "uid_user_a",
        currentSessionGen = 4L
      )
    )
    assertFalse(
      "Stale result after account switch must be rejected",
      PaperCriticViewModel.canCommitInferenceResult(
        initiatingOwnerScope = "uid_user_a",
        initiatingSessionGen = 4L,
        currentOwnerScope = "uid_user_b",
        currentSessionGen = 5L
      )
    )
    assertFalse(
      "Stale result after sign-out / re-sign-in session bump must be rejected",
      PaperCriticViewModel.canCommitInferenceResult(
        initiatingOwnerScope = "uid_user_a",
        initiatingSessionGen = 4L,
        currentOwnerScope = "uid_user_a",
        currentSessionGen = 5L
      )
    )
  }

  @Test
  fun audit04_customProviderRouter_failsClosedWhenKeyIsMissingOrBlank() {
    val customSettings = AppSettingsState(
      useCustomApiProvider = true,
      activeProviderId = "openai_primary",
      autoFallbackToKeylessEngine = true
    )
    val missingKeyProvider = AiApiProviderEntry(
      id = "openai_primary",
      serviceName = "OpenAI Primary",
      protocolType = ApiProtocolType.OPENAI_COMPATIBLE_REST,
      baseUrl = "https://api.openai.com/v1/chat/completions",
      modelId = "gpt-4o",
      apiKey = "",
      isFreeTierPreset = false,
      enabled = true,
      notes = ""
    )
    val routeMissingKey = PaperCriticViewModel.resolveAiProviderRoute(customSettings, listOf(missingKeyProvider))
    assertTrue(
      "Missing/unreadable custom API key must fail closed (UnavailableCustomProvider), NEVER fall back to BuiltInKeyless",
      routeMissingKey is AiProviderRoute.UnavailableCustomProvider
    )

    val routeMissingProvider = PaperCriticViewModel.resolveAiProviderRoute(customSettings, emptyList())
    assertTrue(
      "Unconfigured custom provider ID must fail closed (UnavailableCustomProvider)",
      routeMissingProvider is AiProviderRoute.UnavailableCustomProvider
    )

    val validProvider = missingKeyProvider.copy(apiKey = "sk-live-valid-key-999")
    val routeValid = PaperCriticViewModel.resolveAiProviderRoute(customSettings, listOf(validProvider))
    assertTrue(routeValid is AiProviderRoute.CustomProvider)
  }

  @Test
  fun audit05_oversized100KbFindingsLedger_chunksLosslesslyWithSha256AndBoundingBox() {
    val largeFindings = (1..140).map { idx ->
      ReviewFinding(
        id = "F-%03d".format(idx),
        title = "Detailed Methodological Audit Finding #$idx",
        allegation = "Substantive statistical discrepancy and verification rationale repeated to exceed 100 KB total payload size for finding #$idx. ".repeat(4),
        severity = if (idx % 3 == 0) "CRITICAL" else "MAJOR",
        status = FindingStatus.VERIFIED,
        supportingEvidence = listOf(
          EvidenceAnchor(
            pageNumber = (idx % 12) + 1,
            section = "Results",
            exactQuote = "Verbatim manuscript evidence anchor for finding #$idx with HR = 0.74 (95% CI 0.58 to 0.94).",
            startOffset = idx * 100,
            endOffset = idx * 100 + 85,
            boundingBox = listOf(0.12f, 0.24f, 0.88f, 0.36f)
          )
        )
      )
    }
    val rawJson = ReviewFindingCodec.encode(largeFindings)
    assertTrue("Test payload must exceed 100 KB (got ${rawJson.length} chars)", rawJson.length > 100_000)

    val packaged = ReviewFindingCodec.packageForCloud(rawJson)
    assertTrue("Oversized ledger must split into multiple chunks", packaged.chunks.size >= 3)
    assertTrue("Inline JSON should be empty when chunked", packaged.inlineJson.isEmpty())
    assertEquals(64, packaged.checksumSha256.length)

    val reassembled = ReviewFindingCodec.reassembleAndVerify(packaged.chunks, packaged.checksumSha256)
    assertTrue("Chunk reassembly with matching SHA-256 must succeed", reassembled.isSuccess)
    val decoded = ReviewFindingCodec.decode(reassembled.getOrThrow())
    assertEquals(140, decoded.size)
    assertEquals(
      listOf(0.12f, 0.24f, 0.88f, 0.36f),
      decoded.first().supportingEvidence.first().boundingBox
    )

    // Tampered chunk must fail SHA-256 verification
    val tamperedChunks = packaged.chunks.toMutableList().apply { this[0] = this[0] + " " }
    assertTrue(
      "Corrupted/tampered chunk must fail SHA-256 verification",
      ReviewFindingCodec.reassembleAndVerify(tamperedChunks, packaged.checksumSha256).isFailure
    )
  }

  @Test
  fun audit06_legacyCloudDocumentDeserialization_defaultsUnvalidatedAndBlankYear() {
    val legacyDefault = PaperReview(id = "legacy_cloud_1", title = "Legacy Cloud Record")
    assertEquals("LEGACY_UNVALIDATED", legacyDefault.validationStatus)
    assertEquals("", legacyDefault.publicationYear)

    val payload = ReviewRepository.buildFirestorePayload(
      review = legacyDefault,
      uid = "uid_test",
      createdAtValue = 1L,
      updatedAtValue = 1L
    )
    assertEquals("LEGACY_UNVALIDATED", payload["validationStatus"])
    assertEquals("", payload["publicationYear"])
    assertEquals(5, payload["schemaVersion"])
  }

  @Test
  fun audit07_entailmentPolarity_contradictsNegatedOrReversedQuote() {
    val manuscript = """
      [PAGE 1]
      Results
      In the intention-to-treat population, Treatment did not reduce mortality by 20% compared with placebo (HR = 1.02, 95% CI 0.86 to 1.21, p = 0.81).
    """.trimIndent()
    val invertedFinding = ReviewFinding(
      id = "R-07",
      title = "Treatment reduced mortality",
      allegation = "Treatment reduced mortality by 20% compared with placebo.",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(
        EvidenceAnchor(
          pageNumber = 1,
          section = "Results",
          exactQuote = "Treatment did not reduce mortality by 20% compared with placebo (HR = 1.02, 95% CI 0.86 to 1.21, p = 0.81)."
        )
      )
    )
    val status = Reviewer2PipelineService.evaluateQuoteEntailment(
      finding = invertedFinding,
      matchedQuote = invertedFinding.supportingEvidence.first().exactQuote,
      surroundingWindow = manuscript
    )
    assertEquals(
      "Opposite-polarity quote must return CONTRADICTED_BY_POLARITY, NEVER ENTAILED",
      Reviewer2PipelineService.Companion.EntailmentStatus.CONTRADICTED_BY_POLARITY,
      status
    )

    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = listOf(invertedFinding),
      manuscriptText = manuscript,
      forensicReport = null,
      methodologyScore = 7,
      significanceScore = 7,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 7,
      reproducibilityScore = 7,
      rawVerdict = "MINOR_REVISION",
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      extractionConfidence = 5
    )
    assertEquals(
      "Polarity-contradicted finding must be marked CONTRADICTED",
      FindingStatus.CONTRADICTED,
      adjudicated.adjudicatedFindings.first().status
    )
  }

  @Test
  fun audit08_gradeMissingOutcomeEvidence_returnsInsufficientEvidenceNeverHighCertainty() {
    val rctWithoutOutcomeProfile = """
      [PAGE 1]
      Protocol Overview of a Randomized Controlled Trial in Hypertension
      Methods: Patients will be randomized to active therapy versus standard care across clinics.
      Discussion: Primary efficacy outcomes will be reported once follow-up completes.
    """.trimIndent()
    val forensics = ManuscriptForensicEngine.analyzeManuscript(
      text = rctWithoutOutcomeProfile,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(rctWithoutOutcomeProfile)
    )
    val grade = Reviewer2PipelineService.evaluateClinicalGradeCertainty(
      manuscriptText = rctWithoutOutcomeProfile,
      forensicReport = forensics,
      adjudicatedFindings = emptyList(),
      extractionConfidence = 5
    )
    assertFalse("RCT lacking outcome effect profile must not be rated assessable", grade.isAssessable)
    assertEquals("INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", grade.verdictEnum)

    // Systematic review mentioning "RCT" only in Discussion must NOT start at High
    val obsReviewMentioningRct = """
      [PAGE 1]
      Systematic Review of Observational Cohort Studies of Inpatient Beds
      Methods: We searched PubMed and included 22 observational cohort studies. Risk of bias was assessed using ROBINS-I.
      Results: Pooled rate ratio = 1.18 (95% CI 1.04 to 1.34, I² = 22%).
      Discussion: Future RCT designs are needed to confirm causality.
    """.trimIndent()
    val obsForensics = ManuscriptForensicEngine.analyzeManuscript(
      text = obsReviewMentioningRct,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(obsReviewMentioningRct)
    )
    val obsGrade = Reviewer2PipelineService.evaluateClinicalGradeCertainty(
      manuscriptText = obsReviewMentioningRct,
      forensicReport = obsForensics,
      adjudicatedFindings = emptyList(),
      extractionConfidence = 5
    )
    assertNotEquals(
      "Observational systematic review mentioning future RCT in Discussion must NEVER be rated HIGH_CERTAINTY",
      "HIGH_CERTAINTY",
      obsGrade.verdictEnum
    )
  }

  @Test
  fun audit09_scanned10PagePdfWith5Images_isBlockedByReadinessGate() {
    val scanned10Coverage = DocumentCoverageReport(
      totalPages = 10,
      extractedPages = 0,
      totalChars = 0,
      totalWords = 0,
      extractionEngine = "PDFBox (Vision-Only Scanned PDF)",
      extractionConfidence = 1,
      visionPagesAttached = 5
    )
    val partialGate = Reviewer2PipelineService.evaluateDocumentReadinessGate(
      manuscriptText = "PDF MANUSCRIPT (SCANNED / IMAGE-BASED): ten_page_scan.pdf",
      coverageReport = scanned10Coverage,
      attachedBitmapCount = 5,
      useExternalTextOnlyProvider = false
    )
    assertFalse("10-page scanned PDF with only 5 attached images must be blocked", partialGate.isReady)

    val fullGate = Reviewer2PipelineService.evaluateDocumentReadinessGate(
      manuscriptText = "PDF MANUSCRIPT (SCANNED / IMAGE-BASED): ten_page_scan.pdf",
      coverageReport = scanned10Coverage.copy(visionPagesAttached = 10),
      attachedBitmapCount = 10,
      useExternalTextOnlyProvider = false
    )
    assertTrue("10-page scanned PDF with all 10 page images attached passes multimodal readiness gate", fullGate.isReady)
  }

  @Test
  fun audit10_statisticalSaturationOver100ClaimsAndBayesianCrIExemption() {
    val manyClaimsText = buildString {
      appendLine("[PAGE 1]")
      appendLine("Results")
      for (i in 1..110) {
        appendLine("Endpoint $i showed OR = 1.42 (95% CI 1.12 to 1.80, p = 0.004) in subgroup $i.")
      }
      appendLine("Bayesian sensitivity analysis yielded HR = 1.35 (95% CrI 1.04 to 1.76, p = 0.09).")
    }
    val ledger = StatisticalClaimParser.parse(manyClaimsText)
    assertTrue(
      "Parser must discover >100 quantitative claims without silent 40-claim saturation (got ${ledger.claims.size})",
      ledger.claims.size >= 110
    )

    // Bayesian 95% CrI must NOT trigger a frequentist Wald CI-vs-p FAIL contradiction
    val criClaim = QuantitativeClaim(
      claimId = "C-CRI",
      page = 1,
      section = "Results",
      effectMeasure = "HR",
      pointEstimate = 1.35,
      ciLower = 1.04,
      ciUpper = 1.76,
      ciLevel = 0.95,
      intervalType = IntervalType.CREDIBLE_INTERVAL,
      pValue = 0.09,
      pValueOperator = "=",
      adjustedOrUnadjusted = AdjustmentStatus.UNADJUSTED,
      tailSpec = TailSpecification.TWO_SIDED,
      sourceQuote = "HR = 1.35 (95% CrI 1.04 to 1.76, p = 0.09)"
    )
    val criReconstructions = NumericalReconstructionEngine.runAllReconstructions(
      ledger = StatisticalClaimParser.ParsedManuscriptLedger(
        claims = listOf(criClaim),
        twoByTwoTables = emptyList(),
        metaStudyEffects = emptyList(),
        sampleFlowRecords = emptyList(),
        crossLinks = emptyList(),
        unsupportedDisclosures = emptyList(),
        globalTailSpec = TailSpecification.TWO_SIDED,
        globalAlpha = 0.05
      ),
      rawText = criClaim.sourceQuote
    )
    assertFalse(
      "Bayesian CrI must never be flagged as frequentist Wald CI-vs-p FAIL",
      criReconstructions.any { it.status == QuantFindingStatus.FAIL }
    )
  }

  @Test
  fun audit11_pdfRenderingLongTitlesWrappedTablesAndMultilingualMathSymbols() {
    val review = PaperReview(
      id = "pdf_audit_11",
      title = "A Comprehensive Multi-Center Longitudinal Evaluation of Neurocognitive Recovery Outcomes Across Diverse Multilingual European Cohorts (München — Évaluation Statistique)",
      authors = "Müller, K., Lefèvre, A., & Sørensen, M.",
      publicationYear = "2025",
      verdict = "MINOR_REVISION",
      credibilityScore = 78L,
      refereeConfidence = 4,
      extractionConfidence = 5,
      validationStatus = "VERIFIED",
      methodologyScore = 8,
      methodologyJustification = "Evaluated β = −0.42 (SE = 0.11), τ² = 0.18, α ≤ 0.05 across all prespecified endpoints.",
      abstractSummary = "Multilingual clinical trial summary with mathematical notation: β = −0.42, τ² = 0.18, α ≤ 0.05.",
      structuredFindingsJson = ReviewFindingCodec.encode(
        listOf(
          ReviewFinding(
            id = "R-01",
            title = "Subgroup heterogeneity in München cohort",
            allegation = "Random-effects τ² = 0.18 with α ≤ 0.05 requires sensitivity reporting.",
            severity = "MINOR",
            status = FindingStatus.VERIFIED,
            supportingEvidence = listOf(
              EvidenceAnchor(pageNumber = 2, section = "Results", exactQuote = "Random-effects τ² = 0.18 in München cohort.")
            )
          )
        )
      )
    )
    val tableBlock = ReportBlock.AuditTable(
      headers = listOf("Check", "Estimate", "Status", "Notes"),
      rows = listOf(
        listOf(
          "CI Geometry",
          "β = −0.42\n(95% CI −0.64 | −0.20)",
          "PASS",
          "Multiline wrapped note with embedded pipe | and symbols α ≤ 0.05, τ² = 0.18."
        )
      )
    )
    val rendered = PdfReportExporter.renderAndInspectPdf(
      review = review,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      precomputedQuantBlocks = listOf(tableBlock)
    )
    assertTrue("PDF diagnostics must be clean: ${rendered.diagnostics}", rendered.diagnostics.isClean)
    assertTrue("Rendered PDF must distinguish Source Quote Verification", rendered.renderedText.contains("Source Quote Verification:"))
    assertTrue("Rendered PDF must distinguish Scientific Verification", rendered.renderedText.contains("Scientific Verification:"))
    assertTrue("Rendered PDF must distinguish Editorial Validation", rendered.renderedText.contains("Editorial Validation:"))
    assertTrue("Rendered PDF must preserve math/multilingual symbols", rendered.renderedText.contains("β = −0.42") && rendered.renderedText.contains("München"))
  }

  @Test
  fun audit12_concurrentPatchPayloadsAndPsychiatricBedBenchmarkRegression() {
    // 1. Field-level concurrency safety: bookmark & personalNotes updates touch ONLY their own fields
    val bookmarkPatch = ReviewRepository.buildBookmarkPatchPayload(true)
    assertEquals(setOf("bookmarked", "updatedAt"), bookmarkPatch.keys)
    assertEquals(true, bookmarkPatch["bookmarked"])

    val notesPatch = ReviewRepository.buildPersonalNotesPatchPayload("Journal club note: verify Figure 2 CI")
    assertEquals(setOf("personalNotes", "updatedAt"), notesPatch.keys)
    assertEquals("Journal club note: verify Figure 2 CI", notesPatch["personalNotes"])

    // 2. Psychiatric-bed benchmark regression: Review identity & verified-only score gate
    val psychBedBenchmark = """
      [PAGE 1]
      Need Estimates of Psychiatric Beds: A Systematic Review and Meta-Analysis
      Correspondence: Charité Therapy Campus Mitte, Berlin, Germany.
      Abstract
      Methods: We conducted a systematic review and random-effects meta-analysis of 65 publications reporting 98 estimates of psychiatric inpatient bed need per 100,000 population across high-income and low- and middle-income countries.
      Results: Pooled acute psychiatric bed need was 32.4 per 100,000 (95% CI 26.1 to 38.7, I² = 94.2%).
    """.trimIndent()
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = psychBedBenchmark,
      parentArchetype = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS,
      coverageReport = DocumentCoverageReport.forCompleteText(psychBedBenchmark)
    )
    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = psychBedBenchmark,
      statAudit = statAudit,
      coverageReport = DocumentCoverageReport.forCompleteText(psychBedBenchmark),
      extractionConfidence = 5
    )
    assertNotEquals(
      "Systematic review must never infer causal treatment estimand ATT",
      "ATT",
      epistemic.studyIdentityCard.primaryEstimand.value
    )
    assertFalse(
      "Venue address text must never be extracted as exposure",
      epistemic.studyIdentityCard.exposureOrIntervention.value.contains("THERAPY CAMPUS MITTE", ignoreCase = true)
    )

    // Unverified detector IDs (empty verifiedDetectorIds) must NOT penalize math/methodology scores
    val gatedNoVerified = EvidenceDerivedScoreGate.evaluate(
      report = statAudit,
      baseMathScore = 8,
      baseMethodologyScore = 8,
      manuscriptText = psychBedBenchmark,
      verifiedDetectorIds = emptySet()
    )
    assertEquals(8, gatedNoVerified.finalMathScore)
    assertEquals(8, gatedNoVerified.finalMethodologyScore)
    assertEquals(null, gatedNoVerified.verdictCapReason)
  }

  @Test
  fun audit13_downstreamRegressionFixes_roomPkIsolationChunkPreservationFieldUpdatesAndFormatting() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val localRepo = LocalReviewRepository(db.sqlDao())

      // 1. Composite PRIMARY KEY(ownerScope, id) prevents cross-account collision on identical IDs
      val reviewA = PaperReview(
        id = "shared_review_id",
        userId = "uid_alpha",
        title = "Account Alpha Private Paper",
        structuredFindingsJson = """[{"id":"R-01","title":"Alpha Finding","status":"VERIFIED"}]""",
        findingsChunkCount = 2,
        findingsChecksum = "abc123"
      )
      val reviewB = PaperReview(
        id = "shared_review_id",
        userId = "uid_beta",
        title = "Account Beta Private Paper",
        structuredFindingsJson = """[{"id":"R-01","title":"Beta Finding","status":"VERIFIED"}]"""
      )
      localRepo.cacheReviewOffline(reviewA, ownerScope = "uid_alpha")
      localRepo.cacheReviewOffline(reviewB, ownerScope = "uid_beta")

      val alphaCached = localRepo.getCachedReviewsOnce("uid_alpha")
      val betaCached = localRepo.getCachedReviewsOnce("uid_beta")
      assertEquals(1, alphaCached.size)
      assertEquals(1, betaCached.size)
      assertEquals("Account Alpha Private Paper", alphaCached.first().title)
      assertEquals("Account Beta Private Paper", betaCached.first().title)

      // 2. Transient cloud chunk-hydration failure (blank structuredFindingsJson + findingsChunkCount > 0)
      //    must NEVER clobber intact local Room structuredFindingsJson
      val failedHydrationSnapshot = reviewA.copy(structuredFindingsJson = "")
      localRepo.cacheReviewsOffline(listOf(failedHydrationSnapshot), ownerScope = "uid_alpha")
      val preserved = localRepo.getCachedReviewsOnce("uid_alpha").first()
      assertEquals(
        "Intact local structuredFindingsJson must survive transient chunk hydration failure",
        reviewA.structuredFindingsJson,
        preserved.structuredFindingsJson
      )

      // 3. Field-level Room updates for bookmark & personalNotes must not clobber each other
      val staleSnapshot = preserved
      localRepo.updateBookmark(staleSnapshot, bookmarked = true, ownerScope = "uid_alpha")
      localRepo.updatePersonalNotes(staleSnapshot, personalNotes = "Check sensitivity Table S2", ownerScope = "uid_alpha")
      val afterBoth = localRepo.getCachedReviewsOnce("uid_alpha").first()
      assertTrue("Bookmark must remain true after concurrent notes update from stale snapshot", afterBoth.bookmarked)
      assertEquals("Check sensitivity Table S2", afterBoth.personalNotes)

      // 4. Legacy PaperReview default schemaVersion is 1, while cloud payload stamps v5
      val legacyDefault = PaperReview(title = "Legacy Record")
      assertEquals(1, legacyDefault.schemaVersion)
      val payload = ReviewRepository.buildFirestorePayload(
        review = legacyDefault,
        uid = "uid_alpha",
        createdAtValue = "ts",
        updatedAtValue = "ts"
      )
      assertEquals(5, payload["schemaVersion"])

      // 5. Plain-text dossier must render "Year not verified" instead of empty "()"
      val plainDossier = Reviewer2PipelineService.renderPlainTextDossier(
        title = "Undated Preprint",
        authors = "Doe, J.",
        year = "",
        venueOrCitation = "Preprint",
        isEmpirical = true,
        credibilityScore = 70L,
        verdict = "MINOR_REVISION",
        abstractSummary = "Summary",
        researchQuestion = "RQ",
        keyMethodology = "Methods",
        coreContributions = "Contributions",
        redTeamCritique = "",
        blueTeamVerification = "",
        mathAuditReport = "",
        codeAuditReport = "",
        editorNote = "",
        copyeditSuggestions = "",
        reviewerQuestions = ""
      )
      assertFalse("Plain dossier must never render empty '()'", plainDossier.contains("()"))
      assertTrue("Plain dossier should explicitly state Year not verified", plainDossier.contains("Year not verified"))

      // 6. [QUESTIONS REQUIRING FURTHER INVESTIGATION (UNVERIFIED)] parses to typed OPEN QUESTIONS SubHeader
      val blocks = DossierFormattingEngine.parseStructuredBlocks(
        "[QUESTIONS REQUIRING FURTHER INVESTIGATION (UNVERIFIED)]\n• Confirm blinding protocol."
      )
      val header = blocks.filterIsInstance<ReportBlock.SubHeader>().firstOrNull()
      assertTrue("Header must parse into OPEN QUESTIONS SubHeader", header != null && header.tag == "OPEN QUESTIONS")
      assertFalse("Header title must have no outer brackets", header!!.title.contains("[") || header.title.contains("]"))
    } finally {
      db.close()
    }
  }
}
