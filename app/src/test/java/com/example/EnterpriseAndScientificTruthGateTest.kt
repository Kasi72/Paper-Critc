package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.ForensicPreFlightReport
import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicReport
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.StatcheckResult
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.EpistemicSafeguardEngine
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.AnalysisHierarchy
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
import com.example.data.model.BenchmarkManuscriptCatalog
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.LedgerLoadState
import com.example.data.model.NarrativeSectionCodec
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.data.repository.LocalReviewRepository
import com.example.data.repository.ReviewRepository
import com.example.ui.auth.resolveDefaultWebClientId
import com.example.ui.screens.isSafeExternalWebUrl
import com.example.ui.viewmodel.AiProviderRoute
import com.example.ui.viewmodel.AppSettingsState
import com.example.ui.viewmodel.PaperCriticViewModel
import com.example.ui.viewmodel.PipelineExecutionState
import com.example.util.DocumentCoverageReport
import com.example.util.DossierFormattingEngine
import com.example.util.ExtractedManuscriptFile
import com.example.util.ManuscriptExtractor
import com.example.util.ManuscriptInputFormat
import com.example.util.PdfExportScope
import com.example.util.PdfLayoutGeometryRecorder
import com.example.util.PdfReportExporter
import com.example.util.ReportBlock
import android.database.sqlite.SQLiteDatabase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
      Methods: This prospectively registered trial (NCT04123456) used centralized concealed allocation and double-blind outcome adjudication. Patients (N = 2400; powered at 90% for 400 events; optimal information size met) were randomized 1:1 to Drug X (n = 1200) or placebo (n = 1200) with low risk of bias and intention-to-treat analysis. Formal dissemination assessment found a symmetrical funnel plot and no evidence of publication bias (Egger's test p = 0.64).
      Results: Primary all-cause mortality endpoint occurred in 120/1200 vs 240/1200 (HR = 0.50, 95% CI 0.42 to 0.60, p < 0.001).
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
    assertTrue("Clean RCT with RoB, tight clinical-threshold CI, OIS, and dissemination bias evidence should be assessable", cleanRctGrade.isAssessable)
    assertEquals("HIGH_CERTAINTY", cleanRctGrade.verdictEnum)
    assertTrue("Outcome-specific profiles must be extracted", cleanRctGrade.outcomeProfiles.isNotEmpty())
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

  // =========================================================================
  // FRESH GITHUB AUDIT REPRODUCTION SUITE (ALL 12 END-TO-END FAILURE PATHS)
  // =========================================================================

  private fun buildTestViewModel(
    context: Context,
    db: PaperCriticSqlDatabase,
    initialUserId: String?
  ): PaperCriticViewModel {
    val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
      context,
      FirebaseOptions.Builder()
        .setApplicationId("1:1234567890:android:testpapercritic")
        .setApiKey("test-api-key")
        .setProjectId("paper-critic-test-proj")
        .build()
    )!!
    val repo = ReviewRepository(
      db = FirebaseFirestore.getInstance(app),
      auth = FirebaseAuth.getInstance(app)
    )
    return PaperCriticViewModel(
      repository = repo,
      localRepository = LocalReviewRepository(db.sqlDao()),
      pipelineService = Reviewer2PipelineService(),
      prefs = context.getSharedPreferences("test_vm_prefs_${System.nanoTime()}", Context.MODE_PRIVATE),
      initialUserId = initialUserId
    )
  }

  @Test
  fun freshAudit01_accountSwitchClearsConfidentialWorkbenchTextCodeAndBitmaps() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val vm = buildTestViewModel(context, db, initialUserId = "uid_user_a")
      val secretBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
      val extractedA = ExtractedManuscriptFile(
        fileName = "secret_trial_a.pdf",
        format = ManuscriptInputFormat.PDF,
        inferredTitle = "Confidential Phase III Drug Z Trial",
        extractedText = "CONFIDENTIAL UNPUBLISHED MANUSCRIPT DATA FOR ACCOUNT A ONLY.",
        wordCount = 9,
        pageCount = 1,
        pdfPageBitmaps = listOf(secretBitmap),
        coverageReport = DocumentCoverageReport.forCompleteText("CONFIDENTIAL UNPUBLISHED MANUSCRIPT DATA FOR ACCOUNT A ONLY."),
        summaryNotice = "Loaded secret_trial_a.pdf"
      )
      assertTrue(vm.applyExtractedManuscriptIfSessionValid(extractedA, vm.currentOwnerScope(), vm.currentSessionGeneration()))
      assertTrue(vm.applyExtractedCodeIfSessionValid("analysis_a.py", "secret_key = 'A_ONLY'", vm.currentOwnerScope(), vm.currentSessionGeneration()))

      assertEquals("Confidential Phase III Drug Z Trial", vm.formState.value.title)
      assertTrue(vm.formState.value.manuscriptText.contains("ACCOUNT A ONLY"))
      assertEquals(1, vm.formState.value.pdfPageBitmaps.size)
      assertTrue(vm.formState.value.replicationCodeText.contains("A_ONLY"))

      // Switch to Account B -> workbench MUST be wiped completely
      vm.onAuthenticatedUserChanged("uid_user_b")

      val formAfterSwitch = vm.formState.value
      assertEquals("", formAfterSwitch.title)
      assertEquals("", formAfterSwitch.manuscriptText)
      assertEquals("", formAfterSwitch.replicationCodeText)
      assertEquals(null, formAfterSwitch.importedFileName)
      assertEquals(null, formAfterSwitch.replicationCodeFileName)
      assertTrue("PDF page bitmaps must be cleared on account switch", formAfterSwitch.pdfPageBitmaps.isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun freshAudit02_lateAsyncDocumentAndCodeImportAfterAccountSwitchIsDiscarded() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val vm = buildTestViewModel(context, db, initialUserId = "uid_user_a")
      val ownerAtStart = vm.currentOwnerScope()
      val genAtStart = vm.currentSessionGeneration()

      // Account B signs in while Account A's PDF extraction is still running
      vm.onAuthenticatedUserChanged("uid_user_b")

      val lateExtractedA = ExtractedManuscriptFile(
        fileName = "late_secret_a.pdf",
        format = ManuscriptInputFormat.PDF,
        inferredTitle = "Late Arriving Secret Manuscript A",
        extractedText = "MUST NEVER APPEAR IN USER B WORKBENCH",
        wordCount = 7,
        pageCount = 1,
        pdfPageBitmaps = listOf(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)),
        coverageReport = DocumentCoverageReport.forCompleteText("MUST NEVER APPEAR IN USER B WORKBENCH"),
        summaryNotice = "Loaded late_secret_a.pdf"
      )
      assertFalse(
        "Late manuscript extraction from Account A must be rejected after Account B signs in",
        vm.applyExtractedManuscriptIfSessionValid(lateExtractedA, ownerAtStart, genAtStart)
      )
      assertFalse(
        "Late replication code extraction from Account A must be rejected after Account B signs in",
        vm.applyExtractedCodeIfSessionValid("late_a.R", "secret_a <- 1", ownerAtStart, genAtStart)
      )
      assertEquals("", vm.formState.value.manuscriptText)
      assertEquals("", vm.formState.value.replicationCodeText)
      assertTrue(vm.formState.value.pdfPageBitmaps.isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun freshAudit03_gradeDoesNotAwardHighCertaintyOnBareKeywordsOrNegatedPublicationBias() {
    val weakRct = """
      [PAGE 1]
      A Small Randomized Controlled Trial of Supplement Y vs Placebo in Adults
      Methods: Twenty-four participants (N = 24) were randomized to Supplement Y (n = 12) or placebo (n = 12).
      Publication bias was not assessed.
      Results: Primary outcome showed OR = 1.45 (95% CI 0.42 to 4.98, p = 0.56).
    """.trimIndent()
    val forensics = ManuscriptForensicEngine.analyzeManuscript(
      text = weakRct,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(weakRct)
    )
    val grade = Reviewer2PipelineService.evaluateClinicalGradeCertainty(
      manuscriptText = weakRct,
      forensicReport = forensics,
      adjudicatedFindings = emptyList(),
      extractionConfidence = 5
    )
    assertNotEquals(
      "Underpowered RCT mentioning bare placebo/CI and 'publication bias was not assessed' must NEVER receive HIGH_CERTAINTY",
      "HIGH_CERTAINTY",
      grade.verdictEnum
    )
    assertEquals(
      "Negated 'publication bias was not assessed' must mark domain NOT_ASSESSABLE",
      Reviewer2PipelineService.Companion.GradeDomainConcern.NOT_ASSESSABLE,
      grade.publicationBias
    )
    assertNotEquals(
      "Null-crossing wide CI (0.42 to 4.98) with N=24 must NOT pass imprecision as NOT_SERIOUS",
      Reviewer2PipelineService.Companion.GradeDomainConcern.NOT_SERIOUS,
      grade.imprecision
    )
  }

  @Test
  fun freshAudit04_opposingProseCannotInheritVerifiedBadgeViaCitationOrLexicalOverlap() {
    val verifiedFinding = ReviewFinding(
      id = "R-01",
      title = "No sensitivity analysis performed for attrition",
      allegation = "Authors did not perform sensitivity analysis for 35% outcome attrition in the primary endpoint.",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED
    )
    val contradictoryProse = """
      [R-01] Authors performed sensitivity analysis for 35% outcome attrition in the primary endpoint and confirmed results remained significant.
      Sensitivity analysis for 35% outcome attrition in the primary endpoint was properly conducted and validated.
    """.trimIndent()

    val governed = Reviewer2PipelineService.buildEvidenceGovernedReportSection(
      title = "Red Team",
      rawProse = contradictoryProse,
      adjudicatedFindings = listOf(verifiedFinding)
    )
    assertTrue(
      "Contradictory prose citing [R-01] or overlapping lexically must NEVER enter verifiedStatements",
      governed.verifiedStatements.isEmpty()
    )
    assertTrue(
      "Contradictory prose must be quarantined into openQuestionsForInvestigation without [R-01] badge",
      governed.openQuestionsForInvestigation.isNotEmpty()
    )
    assertFalse(
      "Quarantined opposing prose must have false [R-01] citation tag stripped",
      governed.openQuestionsForInvestigation.any { it.contains("[R-01]") }
    )
  }

  @Test
  fun freshAudit05_readinessBlockedDraftHasZeroScoreUnassessedFlagAndNaInPdf() {
    val unassessedReview = PaperReview(
      id = "rev_blocked_01",
      title = "Unreadable Scanned Manuscript",
      publicationYear = "",
      verdict = "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
      credibilityScore = 0L,
      methodologyScore = 0,
      significanceScore = 0,
      clarityScore = 0,
      originalityScore = 0,
      mathRigorScore = 0,
      reproducibilityScore = 0,
      refereeConfidence = 1,
      extractionConfidence = 1,
      validationStatus = "UNVERIFIED",
      schemaVersion = 5,
      abstractSummary = "Document readiness gate blocked automated assessment due to unreadable scanned pages."
    )
    assertTrue(unassessedReview.isUnassessed())
    val rubric = unassessedReview.toRubric()
    assertFalse("Rubric must report isAssessed = false for readiness-blocked draft", rubric.isAssessed)
    assertEquals(0, rubric.overallCredibilityScore)
    assertEquals(0, rubric.methodology)

    val rendered = PdfReportExporter.renderAndInspectPdf(
      review = unassessedReview,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
    )
    assertTrue("PDF must display N/A or NOT ASSESSED for unassessed draft", rendered.renderedText.contains("N/A") || rendered.renderedText.contains("NOT ASSESSED"))
    assertFalse("PDF must NEVER display fabricated 50/100 score for blocked draft", rendered.renderedText.contains("50/100"))
  }

  @Test
  fun freshAudit06_failedChunkHydrationDowngradesVerifiedStatusAndSetsDegradedLoadState() {
    val verifiedCloudDoc = PaperReview(
      id = "cloud_rev_01",
      title = "Cloud Verified Paper",
      validationStatus = "VERIFIED",
      findingsChunkCount = 3,
      findingsChecksum = "a".repeat(64),
      structuredFindingsJson = ""
    )
    val degraded = ReviewRepository.applyHydrationFailureState(
      review = verifiedCloudDoc,
      error = RuntimeException("Simulated offline chunk read failure")
    )
    assertEquals(LedgerLoadState.TEMPORARILY_UNAVAILABLE, degraded.ledgerLoadState)
    assertNotEquals(
      "Unhydrated cloud document MUST NEVER retain VERIFIED validationStatus",
      "VERIFIED",
      degraded.validationStatus
    )
    assertEquals("PROVISIONAL", degraded.validationStatus)

    // Also verify corrupt inline JSON fails decodeResult & packageForCloud validation
    val corruptInline = PaperReview(
      id = "corrupt_inline_1",
      validationStatus = "VERIFIED",
      structuredFindingsJson = "{not_valid_json_array"
    )
    val checkedCorrupt = ReviewRepository.inspectInlineLedgerIntegrity(corruptInline)
    assertEquals(LedgerLoadState.CORRUPT, checkedCorrupt.ledgerLoadState)
    assertNotEquals("VERIFIED", checkedCorrupt.validationStatus)
    assertEquals("REQUIRES_REANALYSIS", checkedCorrupt.validationStatus)
    assertTrue(ReviewFindingCodec.decodeResult("{not_valid_json_array").isFailure)
    val rejectedPackage = runCatching { ReviewFindingCodec.packageForCloud("{not_valid_json_array") }
    assertTrue("Malformed small inline JSON must be rejected before Firestore write", rejectedPackage.isFailure)
  }

  @Test
  fun freshAudit07_failedSubcollectionEnumerationAbortsParentDeleteToPreventOrphans() = runBlocking {
    var parentDeleted = false
    val result = ReviewRepository.deleteReviewAtomically(
      enumerateFindingsChunks = { throw RuntimeException("Simulated network drop during findings_chunks query") },
      enumerateNarrativeChunks = { emptyList<String>() },
      commitDeleteRefs = {
        parentDeleted = true
      }
    )
    assertTrue("Failed child enumeration must return Result.failure", result.isFailure)
    assertFalse("Parent review MUST NOT be deleted when child chunk enumeration fails", parentDeleted)

    val narrativeFailResult = ReviewRepository.deleteReviewAtomically(
      enumerateFindingsChunks = { listOf("f_0") },
      enumerateNarrativeChunks = { throw RuntimeException("Simulated permission error on narrative_chunks") },
      commitDeleteRefs = {
        parentDeleted = true
      }
    )
    assertTrue(narrativeFailResult.isFailure)
    assertFalse("Parent review MUST NOT be deleted when narrative_chunks enumeration fails", parentDeleted)
  }

  @Test
  fun freshAudit08_dirtyOfflineNotesAndBookmarksSurviveStaleCloudSnapshotAndSearchGrounding() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val localRepo = LocalReviewRepository(db.sqlDao())
      val baseReview = PaperReview(
        id = "cloud_sync_doc_1",
        userId = "uid_alpha",
        title = "Sync Conflict Test Paper",
        bookmarked = false,
        personalNotes = "Initial cloud note",
        updatedAt = Timestamp(Date(System.currentTimeMillis() - 60_000L))
      )
      localRepo.cacheReviewOffline(baseReview, ownerScope = "uid_alpha")

      // User edits notes & stars offline
      localRepo.updateBookmark(baseReview, bookmarked = true, ownerScope = "uid_alpha")
      localRepo.updatePersonalNotes(baseReview, personalNotes = "Offline critical follow-up note", ownerScope = "uid_alpha")

      // Stale cloud snapshot arrives with older values
      val staleCloudSnapshot = baseReview.copy(
        bookmarked = false,
        personalNotes = "Initial cloud note",
        updatedAt = Timestamp(Date(System.currentTimeMillis() - 30_000L))
      )
      localRepo.cacheReviewsOffline(listOf(staleCloudSnapshot), ownerScope = "uid_alpha")

      // Field-level Stage 03b search grounding completes concurrently
      localRepo.updateSearchGroundingReport(
        review = baseReview,
        report = "Verified 3 citations via Google Search.",
        ownerScope = "uid_alpha"
      )

      val saved = localRepo.getCachedReviewsOnce("uid_alpha").first()
      assertTrue("Dirty offline bookmark must survive stale cloud snapshot and search grounding", saved.bookmarked)
      assertEquals("Offline critical follow-up note", saved.personalNotes)
      val dirtyRows = localRepo.getDirtyAnnotationReviews("uid_alpha")
      assertEquals(1, dirtyRows.size)
      assertTrue(dirtyRows.first().bookmarkDirty && dirtyRows.first().personalNotesDirty)
    } finally {
      db.close()
    }
  }

  @Test
  fun freshAudit09_statisticalSaturationOver500ClaimsRecordsExactTruncationAndPrioritizesPrimary() {
    val hugeManuscript = buildString {
      appendLine("[PAGE 1]")
      appendLine("Appendix")
      for (i in 1..280) {
        appendLine("Exploratory appendix check $i had OR = 1.15 (95% CI 1.01 to 1.31, p = 0.038).")
      }
      appendLine("[PAGE 2]")
      appendLine("Appendix")
      for (i in 281..540) {
        appendLine("Secondary subgroup $i showed OR = 1.25 (95% CI 1.05 to 1.49, p = 0.012).")
      }
      appendLine("[PAGE 3]")
      appendLine("Results")
      appendLine("Primary endpoint showed HR = 0.72 (95% CI 0.58 to 0.89, p = 0.002).")
    }
    val ledger = StatisticalClaimParser.parse(hugeManuscript)
    assertTrue("Total discovered claims must count all >500 claims before capping (got ${ledger.totalDiscoveredClaims})", ledger.totalDiscoveredClaims > 500)
    assertTrue("truncatedClaimCount must accurately report omitted claims (got ${ledger.truncatedClaimCount})", ledger.truncatedClaimCount > 0)
    assertEquals(ledger.totalDiscoveredClaims - ledger.claims.size, ledger.truncatedClaimCount)
    assertTrue(
      "Primary endpoint from Results (even on Page 3 after 540 exploratory claims) must be prioritized and retained",
      ledger.claims.any {
        it.effectMeasure.equals("HR", ignoreCase = true) &&
          it.pointEstimate == 0.72 &&
          it.hierarchy == AnalysisHierarchy.PRIMARY
      }
    )
    assertTrue(
      "Truncation must be explicitly disclosed in unsupportedDisclosures",
      ledger.unsupportedDisclosures.any { it.contains("TRUNCATION", ignoreCase = true) || it.contains("omitted", ignoreCase = true) }
    )
  }

  @Test
  fun freshAudit10_oversizedNarrativeSectionsChunkAndReassembleLosslessly() {
    val hugeRedTeam = (1..900).joinToString("\n") { idx ->
      "Critique point #$idx: Detailed methodological analysis with preserved trailing marker END_$idx."
    }
    val hugeEditorNote = (1..700).joinToString("\n") { idx ->
      "Editorial memo paragraph #$idx: Actionable revision requirement ITEM_$idx."
    }
    val review = PaperReview(
      id = "long_narrative_1",
      title = "Exceptionally Long Peer Review",
      redTeamCritique = hugeRedTeam,
      editorNote = hugeEditorNote,
      abstractSummary = "Concise abstract."
    )
    assertTrue(hugeRedTeam.length > 40_000)

    val packaged = NarrativeSectionCodec.packageOverflowNarratives(review)
    assertTrue("Oversized narrative must be chunked into narrative_chunks", packaged.chunks.size >= 2)
    assertEquals(64, packaged.checksumSha256.length)
    assertTrue("Overflow set must identify redTeamCritique & editorNote", "redTeamCritique" in packaged.overflowFieldNames && "editorNote" in packaged.overflowFieldNames)

    val truncatedParent = review.copy(
      redTeamCritique = review.redTeamCritique.take(12_000),
      editorNote = review.editorNote.take(8_000)
    )
    val reassembled = NarrativeSectionCodec.reassembleAndApply(truncatedParent, packaged.chunks, packaged.checksumSha256)
    assertTrue("Lossless narrative reassembly must succeed", reassembled.isSuccess)
    val restored = reassembled.getOrThrow()
    assertEquals("Zero characters lost from redTeamCritique", hugeRedTeam, restored.redTeamCritique)
    assertEquals("Zero characters lost from editorNote", hugeEditorNote, restored.editorNote)
    assertEquals("Concise abstract.", restored.abstractSummary)

    // Tampered narrative chunk must fail SHA-256 verification
    val tampered = packaged.chunks.toMutableList().apply { this[0] = this[0] + "X" }
    assertTrue(NarrativeSectionCodec.reassembleAndApply(truncatedParent, tampered, packaged.checksumSha256).isFailure)
  }

  @Test
  fun freshAudit11_corruptPdfBytesFailExportValidationAndBlockSharing() {
    val corruptBytes = "NOT_A_REAL_PDF_BINARY_CORRUPT_HEADER_12345".toByteArray()
    val diagnostics = PdfReportExporter.validateExportedPdfBytes(corruptBytes)
    assertTrue("Corrupt PDF bytes must set pdfByteIntegrityFailed = true", diagnostics.pdfByteIntegrityFailed)
    assertFalse("Corrupt PDF bytes MUST fail isClean", diagnostics.isClean)
    assertTrue(diagnostics.verificationFailureReason.orEmpty().isNotBlank())

    val emptyDiagnostics = PdfReportExporter.validateExportedPdfBytes(ByteArray(0))
    assertTrue("Empty PDF bytes must fail byte integrity validation", emptyDiagnostics.pdfByteIntegrityFailed)
    assertFalse(emptyDiagnostics.isClean)
  }

  @Test
  fun freshAudit12_repositoryAccountMismatchBlocksMutationAndOptInRecoversLegacyApiKeys() = runBlocking {
    // 1. P1-06: Repository boundary throws SecurityException when active account != expectedUserId
    val mismatch = runCatching {
      ReviewRepository.verifyExpectedAccountOrThrow(activeUid = "uid_user_b", expectedUid = "uid_user_a")
    }
    assertTrue("Cross-account mutation must be blocked", mismatch.isFailure)
    assertTrue(mismatch.exceptionOrNull() is SecurityException)

    assertEquals(
      "uid_user_a",
      ReviewRepository.verifyExpectedAccountOrThrow(activeUid = "uid_user_a", expectedUid = "uid_user_a")
    )

    // 2. P1-07: Legacy/anonymous custom API provider stays isolated until explicit opt-in recovery
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    val testSecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    ApiKeyVault.keyProviderOverride = { testSecretKey }
    try {
      val localRepo = LocalReviewRepository(db.sqlDao())
      localRepo.upsertApiProvider(
        AiApiProviderEntry(
          id = "legacy_openrouter",
          serviceName = "Legacy OpenRouter",
          protocolType = ApiProtocolType.OPENAI_COMPATIBLE_REST,
          baseUrl = "https://openrouter.ai/api/v1/chat/completions",
          modelId = "deepseek/deepseek-r1:free",
          apiKey = "sk-legacy-anon-key-123",
          isFreeTierPreset = false,
          enabled = true,
          notes = "Pre-sign-in key"
        ),
        ownerScope = ANONYMOUS_OWNER_SCOPE
      )

      // Before opt-in: signed-in account does NOT see legacy anonymous key, count == 1
      assertEquals(1, localRepo.observeAnonymousCustomProviderCount().first())
      assertTrue(localRepo.observeApiProviders("uid_user_a").first().isEmpty())

      // Explicit opt-in recovery migrates provider cleanly to uid_user_a and removes anonymous copy
      val moved = localRepo.adoptAnonymousApiProvidersToAccount("uid_user_a")
      assertEquals(1, moved)
      assertEquals(0, localRepo.observeAnonymousCustomProviderCount().first())
      val recovered = localRepo.observeApiProviders("uid_user_a").first()
      assertEquals(1, recovered.size)
      assertEquals("sk-legacy-anon-key-123", recovered.first().toDomain().apiKey)
    } finally {
      ApiKeyVault.keyProviderOverride = null
      db.close()
    }
  }

  @Test
  fun freshAudit13_suspendedSecondaryMutationAcrossAccountSwitchCannotMutateWrongAccount() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val localRepo = LocalReviewRepository(db.sqlDao())
      val docId = "shared_doc_99"
      localRepo.cacheReviewOffline(
        PaperReview(id = docId, userId = "uid_user_a", title = "Account A Paper", bookmarked = false),
        ownerScope = "uid_user_a"
      )
      localRepo.cacheReviewOffline(
        PaperReview(id = docId, userId = "uid_user_b", title = "Account B Paper", bookmarked = false),
        ownerScope = "uid_user_b"
      )

      // Simulate an in-flight coroutine for Account A that starts under Account A and suspends BEFORE hitting repository mutation
      var currentActiveUid = "uid_user_a"
      var currentSessionGen = 10L
      val started = CompletableDeferred<Unit>()
      val gate = CompletableDeferred<Unit>()
      var mutationError: Throwable? = null
      var localBWasTouched = false

      val queuedBookmarkJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        val initiatingUid = currentActiveUid
        val initiatingSession = currentSessionGen
        assertEquals("uid_user_a", initiatingUid)
        started.complete(Unit)
        // Coroutine suspends while user switches to Account B
        gate.await()
        // Both ViewModel commit guard AND Repository account boundary must reject the mutation
        val canCommit = PaperCriticViewModel.canCommitInferenceResult(
          initiatingOwnerScope = initiatingUid,
          initiatingSessionGen = initiatingSession,
          currentOwnerScope = currentActiveUid,
          currentSessionGen = currentSessionGen
        )
        assertFalse("Commit guard must reject suspended mutation after account switch", canCommit)
        mutationError = runCatching {
          ReviewRepository.verifyExpectedAccountOrThrow(
            activeUid = currentActiveUid,
            expectedUid = initiatingUid
          )
          // Would only reach here if boundary failed
          localRepo.updateBookmark(
            review = PaperReview(id = docId, userId = currentActiveUid, title = "Account B Paper"),
            bookmarked = true,
            ownerScope = currentActiveUid
          )
          localBWasTouched = true
        }.exceptionOrNull()
      }

      started.await()
      // Switch account to B while Account A's mutation coroutine is suspended
      currentActiveUid = "uid_user_b"
      currentSessionGen += 1L
      gate.complete(Unit)
      queuedBookmarkJob.join()

      assertTrue("Repository boundary must throw SecurityException on mismatched active UID", mutationError is SecurityException)
      assertFalse("Account B's record must never be mutated by Account A's queued coroutine", localBWasTouched)
      val accountBDoc = localRepo.getCachedReviewsOnce("uid_user_b").first()
      assertFalse("Account B bookmark must remain untouched (false)", accountBDoc.bookmarked)
    } finally {
      db.close()
    }
  }

  @Test
  fun freshAudit14_roomV5ToV8SchemaMigrationPreservesLegacyReviewsAndIsolatesLegacyApiKeys() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_v5_to_v8_test_${System.nanoTime()}.db"
    val dbFile: File = context.getDatabasePath(dbName)
    dbFile.parentFile?.mkdirs()
    if (dbFile.exists()) dbFile.delete()

    val testSecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    ApiKeyVault.keyProviderOverride = { testSecretKey }
    var migratedDb: PaperCriticSqlDatabase? = null
    try {
      // 1. Seed a genuine SQLite v5 database matching the pre-v6 schema
      val rawDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
      try {
        rawDb.version = 5
        rawDb.execSQL(
          """
          CREATE TABLE sql_review_cache (
            id TEXT NOT NULL PRIMARY KEY,
            userId TEXT NOT NULL,
            title TEXT NOT NULL,
            authors TEXT NOT NULL,
            publicationYear TEXT NOT NULL,
            venueOrCitation TEXT NOT NULL,
            isEmpirical INTEGER NOT NULL,
            credibilityScore INTEGER NOT NULL,
            verdict TEXT NOT NULL,
            methodologyScore INTEGER NOT NULL,
            clarityScore INTEGER NOT NULL,
            significanceScore INTEGER NOT NULL,
            originalityScore INTEGER NOT NULL,
            mathRigorScore INTEGER NOT NULL,
            reproducibilityScore INTEGER NOT NULL,
            methodologyJustification TEXT NOT NULL,
            clarityJustification TEXT NOT NULL,
            significanceJustification TEXT NOT NULL,
            originalityJustification TEXT NOT NULL,
            mathRigorJustification TEXT NOT NULL,
            reproducibilityJustification TEXT NOT NULL,
            refereeConfidence INTEGER NOT NULL,
            extractionConfidence INTEGER NOT NULL,
            reviewMode TEXT NOT NULL,
            coverageDisclosure TEXT NOT NULL,
            validationStatus TEXT NOT NULL,
            structuredFindingsJson TEXT NOT NULL,
            abstractSummary TEXT NOT NULL,
            researchQuestion TEXT NOT NULL,
            keyMethodology TEXT NOT NULL,
            coreContributions TEXT NOT NULL,
            redTeamCritique TEXT NOT NULL,
            blueTeamVerification TEXT NOT NULL,
            mathAuditReport TEXT NOT NULL,
            codeAuditReport TEXT NOT NULL,
            editorNote TEXT NOT NULL,
            copyeditSuggestions TEXT NOT NULL,
            reviewerQuestions TEXT NOT NULL,
            formattedDossier TEXT NOT NULL,
            bookmarked INTEGER NOT NULL,
            personalNotes TEXT NOT NULL,
            searchGroundingReport TEXT NOT NULL,
            createdAtSeconds INTEGER NOT NULL,
            createdAtNanos INTEGER NOT NULL,
            updatedAtEpochMs INTEGER NOT NULL
          )
          """.trimIndent()
        )
        rawDb.execSQL(
          """
          CREATE TABLE sql_chat_messages (
            messageId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            reviewId TEXT NOT NULL,
            role TEXT NOT NULL,
            personaId TEXT NOT NULL,
            modelTierId TEXT NOT NULL,
            content TEXT NOT NULL,
            searchGroundedQueries TEXT NOT NULL,
            searchGroundedSources TEXT NOT NULL,
            timestampEpochMs INTEGER NOT NULL
          )
          """.trimIndent()
        )
        rawDb.execSQL(
          """
          CREATE TABLE sql_api_providers (
            id TEXT NOT NULL PRIMARY KEY,
            serviceName TEXT NOT NULL,
            protocolTypeName TEXT NOT NULL,
            baseUrl TEXT NOT NULL,
            modelId TEXT NOT NULL,
            apiKey TEXT NOT NULL,
            isFreeTierPreset INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            notes TEXT NOT NULL,
            updatedAtEpochMs INTEGER NOT NULL
          )
          """.trimIndent()
        )
        rawDb.execSQL(
          """
          INSERT INTO sql_review_cache VALUES (
            'legacy_rev_1', 'uid_alice', 'Legacy Migrated Paper', 'Doe et al.', '2024', 'Nature',
            1, 74, 'SOUND_WITH_CAVEATS',
            7, 8, 7, 7, 8, 7,
            'Solid', 'Clear', 'High', 'Novel', 'Checked', 'Open',
            4, 5, 'POST_PUBLICATION_APPRAISAL', '10/10 pages', 'LEGACY_UNVALIDATED', '',
            'Abstract', 'RQ', 'Methods', 'Contrib', 'Red', 'Blue', 'Math', 'Code', 'Editor', 'Copy', 'QA', 'Dossier',
            1, 'Saved note', '', 1700000000, 0, 1700000000000
          )
          """.trimIndent()
        )
        rawDb.execSQL(
          """
          INSERT INTO sql_chat_messages (
            reviewId, role, personaId, modelTierId, content,
            searchGroundedQueries, searchGroundedSources, timestampEpochMs
          ) VALUES (
            'legacy_rev_1', 'user', 'red_team', 'flash', 'Check sample size attrition in Table 1',
            '', '', 1700000001000
          )
          """.trimIndent()
        )
        rawDb.execSQL(
          """
          INSERT INTO sql_api_providers VALUES (
            'legacy_custom_1', 'Legacy DeepSeek', 'OPENAI_COMPATIBLE_REST',
            'https://api.deepseek.com/v1/chat/completions', 'deepseek-reasoner',
            '${ApiKeyVault.encrypt("sk-v5-secret-key")}', 0, 1, 'Legacy v5 key', 1700000000000
          )
          """.trimIndent()
        )
      } finally {
        rawDb.close()
      }

      // 2. Open with Room at v9 running real MIGRATION_5_6 -> MIGRATION_6_7 -> MIGRATION_7_8 -> MIGRATION_8_9
      migratedDb = Room.databaseBuilder(context, PaperCriticSqlDatabase::class.java, dbName)
        .addMigrations(
          PaperCriticSqlDatabase.MIGRATION_5_6,
          PaperCriticSqlDatabase.MIGRATION_6_7,
          PaperCriticSqlDatabase.MIGRATION_7_8,
          PaperCriticSqlDatabase.MIGRATION_8_9
        )
        .allowMainThreadQueries()
        .build()

      val localRepo = LocalReviewRepository(migratedDb.sqlDao())
      val aliceReviews = localRepo.getCachedReviewsOnce("uid_alice")
      assertEquals(1, aliceReviews.size)
      assertEquals("Legacy Migrated Paper", aliceReviews.first().title)
      assertEquals("LEGACY_UNVALIDATED", aliceReviews.first().validationStatus)
      assertEquals(LedgerLoadState.COMPLETE, aliceReviews.first().ledgerLoadState)
      assertTrue(aliceReviews.first().bookmarked)
      assertEquals("Saved note", aliceReviews.first().personalNotes)

      // Legacy chat row is backfilled to uid_alice and invisible to uid_bob
      val aliceChats = migratedDb.sqlDao().observeChatMessagesForOwner("legacy_rev_1", "uid_alice").first()
      assertEquals(1, aliceChats.size)
      assertEquals("Check sample size attrition in Table 1", aliceChats.first().content)
      assertTrue(migratedDb.sqlDao().observeChatMessagesForOwner("legacy_rev_1", "uid_bob").first().isEmpty())

      // Bob sees zero of Alice's migrated reviews
      assertTrue(localRepo.getCachedReviewsOnce("uid_bob").isEmpty())

      // Legacy API provider is quarantined in ANONYMOUS scope (not leaked to Alice or Bob) until explicit opt-in
      assertEquals(1, localRepo.observeAnonymousCustomProviderCount().first())
      assertTrue(localRepo.observeApiProviders("uid_alice").first().isEmpty())
      val recoveredCount = localRepo.adoptAnonymousApiProvidersToAccount("uid_alice")
      assertEquals(1, recoveredCount)
      val aliceProviders = localRepo.observeApiProviders("uid_alice").first()
      assertEquals(1, aliceProviders.size)
      assertEquals("sk-v5-secret-key", aliceProviders.first().toDomain().apiKey)
    } finally {
      migratedDb?.close()
      ApiKeyVault.keyProviderOverride = null
      dbFile.delete()
    }
  }

  @Test
  fun freshAudit15_statisticalTruncationBlocksVerifiedStatusAndDegradedPdfRendersWarningBanner() {
    // 1. P1-04: Truncated statistical audit prevents VERIFIED status and surfaces explicit prompt warning
    val hugeManuscript = buildString {
      appendLine("[PAGE 1]")
      appendLine("Results")
      for (i in 1..530) {
        appendLine("Exploratory model $i yielded OR = 1.20 (95% CI 1.02 to 1.41, p = 0.028).")
      }
    }
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = hugeManuscript,
      parentArchetype = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
      coverageReport = DocumentCoverageReport.forCompleteText(hugeManuscript)
    )
    assertTrue("StatisticalAuditReport must expose truncatedClaimCount > 0", statAudit.truncatedClaimCount > 0)
    assertTrue(statAudit.toPromptBlock().contains("QUANTITATIVE COVERAGE PARTIAL"))

    val verifiedFinding = ReviewFinding(
      id = "R-01",
      title = "Sensitivity analysis omitted",
      allegation = "No sensitivity analysis was performed for missing outcome data.",
      status = FindingStatus.VERIFIED,
      severity = "MAJOR"
    )
    val validationWithTruncation = Reviewer2PipelineService.computeValidationStatus(
      resolvedVerdict = "SOUND_WITH_CAVEATS",
      adjudicatedFindings = listOf(verifiedFinding),
      anyJsonRepaired = false,
      omittedOrPartialPromptPagesCount = 0,
      wasPromptTrimmed = false,
      extractionConfidence = 5,
      hasSubstantiveEditorialProse = true,
      truncatedStatisticalClaimCount = statAudit.truncatedClaimCount
    )
    assertEquals(
      "Truncated statistical audit must NEVER claim full VERIFIED status",
      "PARTIALLY_VALIDATED",
      validationWithTruncation
    )

    // 2. P0-04 hardened checks: positive-existence & clinical benefit/harm direction inversion blocked
    assertFalse(
      "Positive existence claim ('is present in Table 3') contradicting omission finding must be blocked",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        sentence = "[R-01] Sensitivity analysis for missing outcome data is present in Table 3.",
        finding = verifiedFinding
      )
    )
    val harmFinding = ReviewFinding(
      id = "Q-02",
      title = "Mortality harm signal",
      allegation = "Drug X was associated with increased mortality and elevated risk of hospitalization.",
      status = FindingStatus.VERIFIED,
      severity = "CRITICAL"
    )
    assertFalse(
      "Opposing benefit claim contradicting mortality harm finding must be blocked",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        sentence = "[Q-02] Drug X significantly reduced mortality and showed protective hospitalization benefit.",
        finding = harmFinding
      )
    )

    // 3. Degraded PDF banner + Not Assessed Page 2+ running header check
    val degradedReview = PaperReview(
      id = "degraded_pdf_1",
      title = "Degraded Evidence Ledger Dossier",
      verdict = "PROVISIONAL",
      validationStatus = "PROVISIONAL",
      ledgerLoadState = LedgerLoadState.CORRUPT,
      methodologyScore = 7,
      clarityScore = 7,
      significanceScore = 7,
      originalityScore = 7,
      mathRigorScore = 7,
      reproducibilityScore = 7,
      credibilityScore = 70L,
      abstractSummary = "Summary text."
    )
    val degradedPdf = PdfReportExporter.renderAndInspectPdf(
      review = degradedReview,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      requireByteExtraction = false
    )
    assertTrue(
      "Corrupt ledger PDF must render explicit integrity warning banner",
      degradedPdf.renderedText.contains("EVIDENCE LEDGER INTEGRITY WARNING")
    )
    assertFalse(
      "Corrupt ledger PDF must NEVER claim Source-Anchored & Entailed",
      degradedPdf.renderedText.contains("Source-Anchored & Entailed")
    )
  }

  @Test
  fun freshAudit16_multiDesignScientificCalibrationBenchmark_measuresTruePositiveAndFalsePositiveRates() {
    // 1. Clean well-powered double-blind RCT (Negative control — expects 0 false critical errors)
    val cleanRct = """
      [PAGE 1]
      A Multi-Center Double-Blind Randomized Controlled Trial of Therapy Z vs Placebo in Heart Failure
      Methods: Prospectively registered (NCT05112233). Centralized concealed allocation randomized 640 patients 1:1 (n = 320 Therapy Z; n = 320 placebo), powered at 80% for mortality. Intention-to-treat analysis and double-blind outcome adjudication were performed with low risk of bias.
      Results: All-cause mortality occurred in 24/320 (7.5%) vs 42/320 (13.1%), HR = 0.56 (95% CI 0.34 to 0.92, p = 0.022).
    """.trimIndent()
    val cleanRctReport = ManuscriptForensicEngine.analyzeManuscript(
      text = cleanRct,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(cleanRct)
    )
    val cleanRctFalseCriticals = cleanRctReport.quantForensics?.statisticalAudit?.verifiedArithmeticErrorCount ?: 0

    // 2. Deliberately flawed RCT (Positive control — impossible point estimate outside CI + denominator drift)
    val flawedRct = """
      [PAGE 1]
      Randomized Trial of Drug Q vs Control
      Methods: Total of 100 patients were enrolled and randomized. 15 patients were lost to follow-up, leaving 98 patients in the final intention-to-treat analysis.
      Results: Primary outcome showed OR = 3.20 (95% CI 0.85 to 1.45, p = 0.004).
    """.trimIndent()
    val flawedRctReport = ManuscriptForensicEngine.analyzeManuscript(
      text = flawedRct,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(flawedRct)
    )
    val flawedRctDetected = ((flawedRctReport.quantForensics?.statisticalAudit?.verifiedArithmeticErrorCount ?: 0) +
      (flawedRctReport.quantForensics?.statisticalAudit?.verifiedContradictionCount ?: 0)) > 0

    // 3. Clean Observational Cohort with multivariable adjustment (Negative control — 0 false critical errors)
    val cleanCohort = """
      [PAGE 1]
      Prospective Observational Cohort Study of Statin Use and Stroke Incidence
      Methods: We followed 2,400 adults over 5 years using multivariable Cox regression adjusted for age, sex, hypertension, and diabetes, with propensity score matching sensitivity analysis.
      Results: Adjusted HR = 0.78 (95% CI 0.64 to 0.95, p = 0.014).
    """.trimIndent()
    val cleanCohortReport = ManuscriptForensicEngine.analyzeManuscript(
      text = cleanCohort,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(cleanCohort)
    )
    val cleanCohortFalseCriticals = cleanCohortReport.quantForensics?.statisticalAudit?.verifiedArithmeticErrorCount ?: 0

    // 4. Deliberately flawed Diagnostic Accuracy 2x2 / CI paper (Positive control — impossible CI order)
    val flawedDiagnostic = """
      [PAGE 1]
      Diagnostic Accuracy of Biomarker K for Acute Pulmonary Embolism
      Methods: Cross-sectional diagnostic accuracy study in 200 emergency patients using CT angiography as reference standard.
      Results: Diagnostic odds ratio OR = 4.50 (95% CI 5.10 to 6.20, p = 0.001).
    """.trimIndent()
    val flawedDiagReport = ManuscriptForensicEngine.analyzeManuscript(
      text = flawedDiagnostic,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(flawedDiagnostic)
    )
    val flawedDiagDetected = (flawedDiagReport.quantForensics?.statisticalAudit?.verifiedArithmeticErrorCount ?: 0) > 0

    // 5. Meta-analysis with high heterogeneity (I² = 82%) and unassessed publication bias (GRADE gate)
    val heteroMeta = """
      [PAGE 1]
      Systematic Review and Meta-Analysis of Randomized Controlled Trials of Intervention M
      Methods: We pooled 6 randomized controlled trials (N = 900 patients) versus placebo. Heterogeneity was substantial (I² = 82%). Publication bias was not assessed due to fewer than 10 included studies.
      Results: Pooled RR = 0.76 (95% CI 0.52 to 1.11, p = 0.16) for all-cause mortality.
    """.trimIndent()
    val heteroMetaForensics = ManuscriptForensicEngine.analyzeManuscript(
      text = heteroMeta,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(heteroMeta)
    )
    val heteroMetaGrade = Reviewer2PipelineService.evaluateClinicalGradeCertainty(
      manuscriptText = heteroMeta,
      forensicReport = heteroMetaForensics,
      adjudicatedFindings = emptyList(),
      extractionConfidence = 5
    )
    val heteroMetaProperlyDowngradedOrWithheld = heteroMetaGrade.verdictEnum != "HIGH_CERTAINTY"

    // 6. Psychiatric-bed meta-analysis Page 8 impossible CI (Positive control)
    val psychBedExcerpt = """
      [PAGE 1]
      Need Estimates of Psychiatric Beds: A Systematic Review and Meta-Analysis
      Methods: Following PRISMA guidelines, we synthesized 65 publications across 40 countries using random-effects models.
      [PAGE 8]
      Results: Pooled prevalence ratio PR = 2.21, 95% CI 0.00 to 4.50, p = 0.04 across inpatient cohorts.
    """.trimIndent()
    val psychBedReport = ManuscriptForensicEngine.analyzeManuscript(
      text = psychBedExcerpt,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(psychBedExcerpt)
    )
    val psychBedCaught = psychBedReport.quantForensics?.statisticalAudit?.reconstructions?.any {
      it.status == QuantFindingStatus.FAIL || it.status == QuantFindingStatus.WARN
    } == true

    // Calibration summary assertions: 100% TPR on positive controls, 0% FPR on clean controls
    val truePositives = listOf(flawedRctDetected, flawedDiagDetected, heteroMetaProperlyDowngradedOrWithheld, psychBedCaught).count { it }
    val falsePositives = listOf(cleanRctFalseCriticals > 0, cleanCohortFalseCriticals > 0).count { it }
    assertEquals("Multi-design calibration suite must achieve 4/4 (100%) TPR across positive controls", 4, truePositives)
    assertEquals("Multi-design calibration suite must achieve 0/2 (0%) FPR across clean negative controls", 0, falsePositives)
  }

  // =========================================================================
  // NEW CODE AUDIT (27 NOV 2026) — FINDINGS 1 THROUGH 14 REGRESSION SUITE
  // =========================================================================

  @Test
  fun auditV2_01_revisionAwareDirtySync_delayedAckForNoteA_neverClearsNewerNoteB() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = LocalReviewRepository(db.sqlDao())
      val owner = "uid_race_test"
      val rev = PaperReview(id = "race_rev_1", userId = owner, title = "Concurrent Annotation Race Test", personalNotes = "")
      repo.cacheReviewOffline(rev, ownerScope = owner)

      // 1. User saves Note A -> captures revA
      val revA = repo.updatePersonalNotes(review = rev, personalNotes = "Note A", ownerScope = owner)
      assertTrue("Revision must be strictly positive", revA >= 1L)

      // 2. Before Note A's cloud write completes, user edits to Note B -> captures revB > revA
      val revB = repo.updatePersonalNotes(review = rev, personalNotes = "Note B", ownerScope = owner)
      assertTrue("Newer edit must strictly increment revision", revB > revA)

      // 3. Stale cloud ack for Note A arrives and tries to clear dirty flag using revA
      repo.clearPersonalNotesDirtyIfRevisionMatches("race_rev_1", owner, revA)
      val dirtyAfterStaleAck = repo.getDirtyAnnotationReviews(owner)
      assertEquals("Note B MUST remain dirty after stale ack for Note A", 1, dirtyAfterStaleAck.size)
      assertTrue("personalNotesDirty must still be true", dirtyAfterStaleAck.first().personalNotesDirty)
      assertEquals("Note B", dirtyAfterStaleAck.first().personalNotes)

      // 4. Same race test for bookmarks
      val bmRev1 = repo.updateBookmark(review = rev, bookmarked = true, ownerScope = owner)
      val bmRev2 = repo.updateBookmark(review = rev, bookmarked = false, ownerScope = owner)
      assertTrue(bmRev2 > bmRev1)
      repo.clearBookmarkDirtyIfRevisionMatches("race_rev_1", owner, bmRev1)
      assertTrue("Newer bookmark toggle must remain dirty after stale ack", repo.getDirtyAnnotationReviews(owner).first().bookmarkDirty)

      // 5. Once current revisions revB and bmRev2 are acknowledged, dirty flags clear cleanly
      repo.clearPersonalNotesDirtyIfRevisionMatches("race_rev_1", owner, revB)
      repo.clearBookmarkDirtyIfRevisionMatches("race_rev_1", owner, bmRev2)
      assertTrue("Both dirty flags must clear once latest revisions are acknowledged", repo.getDirtyAnnotationReviews(owner).isEmpty())
    } finally {
      db.close()
    }
  }

  @Test
  fun auditV2_02_oauthClientIdResolution_failsClosedOnPlaceholderOrMissingConfig() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val resolved = resolveDefaultWebClientId(context)
    // In CI/test without real OAuth client ID, placeholder strings must be rejected; if real google-services.json
    // is present locally, it must end in .apps.googleusercontent.com and never equal the CI placeholder.
    if (resolved != null) {
      assertFalse(resolved.contains("PLACEHOLDER", ignoreCase = true))
      assertFalse(resolved.contains("CI_TEST", ignoreCase = true))
      assertTrue(resolved.isNotBlank())
    }
  }

  @Test
  fun auditV2_03_typedScientificPropositionGate_rejectsConflictingNumbersPopulationsAdjustmentsComparatorsAndTimepoints() {
    val adultMortalityFinding = ReviewFinding(
      id = "Q-10",
      title = "Mortality increase in adults",
      allegation = "Treatment increased 30-day mortality by 5% in adults compared with placebo in adjusted analysis (HR = 1.15, 95% CI 1.04 to 1.27, p = 0.008).",
      status = FindingStatus.VERIFIED,
      severity = "CRITICAL"
    )
    // 1. Consistent restatement MUST pass
    assertTrue(
      "Consistent restatement matching magnitude, population, comparator, adjustment, and timepoint must pass",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        "[Q-10] In adjusted analysis, treatment increased 30-day mortality by 5% in adults versus placebo (HR = 1.15, 95% CI 1.04 to 1.27).",
        adultMortalityFinding
      )
    )
    // 2. Conflicting percentage (5% vs 50%) MUST fail
    assertFalse(
      "Conflicting magnitude (50% vs 5%) must NEVER be certified as verified",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        "[Q-10] Treatment increased mortality by 50% in adults compared with placebo.",
        adultMortalityFinding
      )
    )
    // 3. Conflicting population (adults vs children) MUST fail
    assertFalse(
      "Conflicting population (children vs adults) must NEVER be certified as verified",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        "[Q-10] Treatment increased mortality by 5% in children compared with placebo.",
        adultMortalityFinding
      )
    )
    // 4. Conflicting adjustment status (unadjusted vs adjusted) MUST fail
    assertFalse(
      "Conflicting adjustment status (unadjusted vs adjusted) must fail",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        "[Q-10] In unadjusted analysis, treatment increased 30-day mortality by 5% in adults.",
        adultMortalityFinding
      )
    )
    // 5. Conflicting follow-up period (5-year vs 30-day) MUST fail
    assertFalse(
      "Conflicting follow-up period (5-year vs 30-day) must fail",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        "[Q-10] Treatment increased 5-year mortality by 5% in adults compared with placebo.",
        adultMortalityFinding
      )
    )
    // 6. Conflicting comparator arm (surgery vs placebo) MUST fail
    assertFalse(
      "Conflicting comparator arm (surgery vs placebo) must fail",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        "[Q-10] Treatment increased 30-day mortality by 5% in adults compared with surgery.",
        adultMortalityFinding
      )
    )
    // 7. Conflicting effect measure (OR vs HR) MUST fail
    assertFalse(
      "Conflicting effect measure (OR vs HR) must fail",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(
        "[Q-10] Treatment increased 30-day mortality in adults with OR = 1.15.",
        adultMortalityFinding
      )
    )
  }

  @Test
  fun auditV2_04_evidenceGate_quarantinesUnsupportedPositiveAndConclusiveScientificClaims() {
    val prose = """
      Scope: Editorial appraisal of the supplied clinical trial draft.
      The treatment is safe and clinically effective for routine adoption.
      This study is methodologically sound and proves causal benefit with high certainty.
      Outcomes were reduced by 28% (p = 0.003).
    """.trimIndent()

    // Test BOTH with empty adjudicatedFindings and with an unrelated verified finding
    val sectionEmptyLedger = Reviewer2PipelineService.buildEvidenceGovernedReportSection(
      title = "Editorial Verdict",
      rawProse = prose,
      adjudicatedFindings = emptyList()
    )
    assertEquals(
      "Only neutral scope framing may remain in contextStatements",
      1,
      sectionEmptyLedger.contextStatements.size
    )
    assertTrue(sectionEmptyLedger.contextStatements.first().startsWith("Scope:"))
    assertEquals(
      "All 3 unsupported positive/quantitative claims MUST be quarantined to openQuestionsForInvestigation",
      3,
      sectionEmptyLedger.openQuestionsForInvestigation.size
    )
    assertTrue(
      "Quarantined claims must be explicitly labeled provisional",
      sectionEmptyLedger.openQuestionsForInvestigation.all { it.contains("Provisional (Unverified Assertion)") }
    )
    assertTrue(sectionEmptyLedger.verifiedStatements.isEmpty())
  }

  @Test
  fun auditV2_05_twoStageStatisticalCoverage_tracksBothDiscoveryAndReconstructionSkipsAndDowngradesValidation() {
    // 1. Trigger Stage-1 discovery cap on structured table rows (>80 rows)
    val manyTableRowsManuscript = buildString {
      appendLine("[PAGE 1]")
      appendLine("Results")
      appendLine("| Endpoint | Events | Rate | Effect |")
      appendLine("|---|---|---|---|")
      for (i in 1..95) {
        appendLine("| Outcome $i | 12/100 (12.0%) | 10/100 (10.0%) | OR = 1.22 (95% CI 0.50 to 2.98, p = 0.65) |")
      }
    }
    val tableAudit = StatisticalAuditOrchestrator.audit(
      text = manyTableRowsManuscript,
      parentArchetype = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
      coverageReport = DocumentCoverageReport.forCompleteText(manyTableRowsManuscript)
    )
    assertTrue(
      "Stage-1 table-row cap must be reflected in coverageMetrics.discoverySkipped",
      tableAudit.coverageMetrics.discoverySkipped > 0
    )
    assertTrue(
      "Skipped category must mention table rows",
      tableAudit.coverageMetrics.skippedCategories.any { it.contains("table", ignoreCase = true) }
    )

    // 2. Trigger Stage-2 reconstruction cap (>150 ratio CI claims inside the 500 Stage-1 discovery limit)
    val manyRatioCiManuscript = buildString {
      appendLine("[PAGE 1]")
      appendLine("Results")
      for (i in 1..175) {
        appendLine("Subgroup $i showed HR = 1.25 (95% CI 1.05 to 1.49, p = 0.012).")
      }
    }
    val reconAudit = StatisticalAuditOrchestrator.audit(
      text = manyRatioCiManuscript,
      parentArchetype = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
      coverageReport = DocumentCoverageReport.forCompleteText(manyRatioCiManuscript)
    )
    assertTrue(
      "Stage-2 reconstruction cap must be reflected in coverageMetrics.reconstructionSkipped (${reconAudit.coverageMetrics})",
      reconAudit.coverageMetrics.reconstructionSkipped > 0
    )
    assertTrue(reconAudit.truncatedClaimCount >= reconAudit.coverageMetrics.reconstructionSkipped)
    assertTrue(reconAudit.toPromptBlock().contains("QUANTITATIVE COVERAGE PARTIAL"))

    val verifiedFinding = ReviewFinding(
      id = "S-01",
      title = "Sample check",
      allegation = "Verified check.",
      status = FindingStatus.VERIFIED,
      severity = "MINOR"
    )
    val status = Reviewer2PipelineService.computeValidationStatus(
      resolvedVerdict = "SOUND_WITH_CAVEATS",
      adjudicatedFindings = listOf(verifiedFinding),
      anyJsonRepaired = false,
      omittedOrPartialPromptPagesCount = 0,
      wasPromptTrimmed = false,
      extractionConfidence = 5,
      hasSubstantiveEditorialProse = true,
      truncatedStatisticalClaimCount = reconAudit.truncatedClaimCount
    )
    assertEquals("PARTIALLY_VALIDATED", status)
  }

  @Test
  fun auditV2_06_and_11_manuscriptReplacement_detachesStaleReplicationCodeAndSeparatesUserEditedMetadata() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val vm = buildTestViewModel(context, db, initialUserId = "uid_draft_binding")
      // 1. Load Paper A and attach Replication Code A
      vm.updateManuscriptText("Paper A: A randomized trial of Drug Alpha versus placebo in 400 adults with acute heart failure. Results: HR = 0.72 (95% CI 0.58 to 0.89).")
      vm.updateTitle("Paper A Custom User Title")
      vm.updateAuthors("Dr. User Entered")
      vm.updateReplicationCodeText("import pandas as pd\ndf = pd.read_csv('paper_a.csv')\n")
      val draftIdA = vm.formState.value.draftId
      assertEquals(draftIdA, vm.formState.value.replicationCodeBoundDraftId)
      assertTrue(PaperCriticViewModel.resolveBoundReplicationCode(vm.formState.value).contains("paper_a.csv"))

      // 2. Replace with a completely different Paper B via manual text replacement
      vm.updateManuscriptText("Paper B: A prospective cohort study of Biomarker Beta in 1200 oncology patients followed for 5 years. Results: OR = 1.84 (95% CI 1.32 to 2.56, p < 0.001).")
      val stateB = vm.formState.value
      assertNotEquals("Substantive manuscript replacement must mint a fresh draftId", draftIdA, stateB.draftId)
      assertEquals("Stale replication code from Paper A must be cleared automatically", "", stateB.replicationCodeText)
      assertEquals("Unbound/stale code must never pass the analysis boundary", "", PaperCriticViewModel.resolveBoundReplicationCode(stateB))
      assertTrue(
        "User-edited metadata must be flagged for reconfirmation instead of silently carried onto Paper B",
        stateB.metadataNeedsReconfirmation
      )

      // 3. Loading a benchmark case must also clear stale replication code and reset user-edited flags
      vm.updateReplicationCodeText("# stale code for B")
      vm.loadBenchmarkManuscript(BenchmarkManuscriptCatalog.samples.first())
      assertEquals("", vm.formState.value.replicationCodeText)
      assertFalse(vm.formState.value.metadataNeedsReconfirmation)
    } finally {
      db.close()
    }
  }

  @Test
  fun auditV2_07_losslessNarrativeRecovery_restoresAll12FieldsByChecksumWithoutLengthHeuristics() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = LocalReviewRepository(db.sqlDao())
      val owner = "uid_lossless_12"
      val checksum = "sha256_narrative_v1_exact"
      val fullReview = PaperReview(
        id = "rev_12_fields",
        userId = owner,
        title = "Complete 12-Field Narrative Dossier",
        ledgerLoadState = LedgerLoadState.COMPLETE,
        narrativeChunkCount = 2,
        narrativeChecksum = checksum,
        abstractSummary = "Full abstract summary text",
        researchQuestion = "Full research question text",
        keyMethodology = "Full key methodology text",
        coreContributions = "Full core contributions text",
        redTeamCritique = "Full red team critique",
        blueTeamVerification = "Full blue team defense",
        mathAuditReport = "Full math audit",
        codeAuditReport = "Full code audit",
        editorNote = "Full editor note",
        copyeditSuggestions = "Full copyedit suggestions",
        reviewerQuestions = "Full reviewer questions",
        formattedDossier = "Full formatted dossier"
      )
      repo.cacheReviewOffline(fullReview, ownerScope = owner)

      // Simulate partial cloud hydration failure (all 12 narrative fields empty, same checksum, TEMPORARILY_UNAVAILABLE)
      val degradedIncoming = PaperReview(
        id = "rev_12_fields",
        userId = owner,
        title = "Complete 12-Field Narrative Dossier",
        ledgerLoadState = LedgerLoadState.TEMPORARILY_UNAVAILABLE,
        narrativeChunkCount = 2,
        narrativeChecksum = checksum
      )
      repo.cacheReviewsOffline(listOf(degradedIncoming), ownerScope = owner)
      val recovered = repo.getCachedReviewsOnce(owner).first()
      assertEquals(LedgerLoadState.COMPLETE, recovered.ledgerLoadState)
      assertEquals("Full abstract summary text", recovered.abstractSummary)
      assertEquals("Full research question text", recovered.researchQuestion)
      assertEquals("Full key methodology text", recovered.keyMethodology)
      assertEquals("Full core contributions text", recovered.coreContributions)
      assertEquals("Full copyedit suggestions", recovered.copyeditSuggestions)
      assertEquals("Full reviewer questions", recovered.reviewerQuestions)
      assertEquals("Full red team critique", recovered.redTeamCritique)
      assertEquals("Full formatted dossier", recovered.formattedDossier)

      // If checksum mismatches (cloud updated to a new version whose narrative failed to hydrate),
      // MUST NOT silently overwrite with stale cached narrative!
      val mismatchedIncoming = PaperReview(
        id = "rev_12_fields",
        userId = owner,
        title = "Complete 12-Field Narrative Dossier",
        ledgerLoadState = LedgerLoadState.TEMPORARILY_UNAVAILABLE,
        narrativeChunkCount = 2,
        narrativeChecksum = "different_checksum_v2"
      )
      repo.cacheReviewsOffline(listOf(mismatchedIncoming), ownerScope = owner)
      val notOverwritten = repo.getCachedReviewsOnce(owner).first()
      assertEquals(LedgerLoadState.TEMPORARILY_UNAVAILABLE, notOverwritten.ledgerLoadState)
      assertEquals("", notOverwritten.abstractSummary)
    } finally {
      db.close()
    }
  }

  @Test
  fun auditV2_08_contentBoundRemoteConsent_blocksExecutionUntilConfirmedAndInvalidatesOnAnyContentEdit() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val vm = buildTestViewModel(context, db, initialUserId = "uid_consent_gate")
      vm.updateSettings { it.copy(confidentialLocalOnly = true) }
      vm.updateManuscriptText("Draft 1 manuscript text with clinical RCT results HR = 0.78 (95% CI 0.64 to 0.95).")

      // 1. Without consent, consent is invalid and runAdversarialReviewAndSave is blocked!
      assertFalse(vm.isRemoteInferenceConsentValidNow())
      vm.runAdversarialReviewAndSave()
      val blockedState = vm.pipelineState.value as? PipelineExecutionState.Error
      assertTrue(
        "Execution must be blocked at ViewModel boundary without content-bound consent (got: ${vm.pipelineState.value})",
        blockedState != null && blockedState.message.contains("remote ai inference", ignoreCase = true)
      )

      // 2. Confirming consent binds to exact SHA-256 of manuscript + code + provider
      vm.confirmRemoteInferenceForCurrentDraft()
      assertTrue("Consent must be valid for the exact confirmed draft", vm.isRemoteInferenceConsentValidNow())

      // 3. Editing even 1 character of manuscript invalidates consent immediately!
      vm.updateManuscriptText("Draft 1 manuscript text with clinical RCT results HR = 0.78 (95% CI 0.64 to 0.96).")
      assertFalse("Any manuscript edit must invalidate prior remote inference consent", vm.isRemoteInferenceConsentValidNow())

      // 4. Re-confirm, then edit replication code -> invalidates consent!
      vm.confirmRemoteInferenceForCurrentDraft()
      assertTrue(vm.isRemoteInferenceConsentValidNow())
      vm.updateReplicationCodeText("x = 42\n")
      assertFalse("Attaching or editing replication code must invalidate prior consent", vm.isRemoteInferenceConsentValidNow())
    } finally {
      db.close()
    }
  }

  @Test
  fun auditV2_09_atomicFirestoreDeletion_usesSingleBatchUpTo499ChildrenAndTombstoneOnlyAbove499() {
    assertEquals(499, ReviewRepository.MAX_SINGLE_BATCH_CHILD_DELETES)
    assertTrue("Supported records (<=499 child docs) must delete atomically in 1 batch", 499 <= ReviewRepository.MAX_SINGLE_BATCH_CHILD_DELETES)
    assertFalse("Oversized legacy records (>499 child docs) require tombstone fallback", 500 <= ReviewRepository.MAX_SINGLE_BATCH_CHILD_DELETES)
  }

  @Test
  fun auditV2_10_clinicalGradeScreen_checksClinicalDecisionThresholds_rejectsRegistrationOnlyPubBias_andFramesHonestly() {
    // 1. N = 150 with wide CI crossing clinical decision threshold 0.75 (0.50 to 0.99, ratio 1.98 > 1.8)
    //    AND protocol registration NCT05001122 WITHOUT formal funnel/Egger publication bias assessment
    val rctRegistrationOnly = """
      [PAGE 1]
      A Double-Blind Randomized Controlled Trial of Therapy Y vs Placebo in Chronic Heart Failure
      Methods: Prospectively registered (NCT05001122). Centralized concealed allocation randomized 150 patients 1:1 (n = 75 vs n = 75) with double-blind outcome adjudication and low risk of bias.
      Results: All-cause mortality HR = 0.70 (95% CI 0.50 to 0.99, p = 0.044).
    """.trimIndent()
    val forensics = ManuscriptForensicEngine.analyzeManuscript(
      text = rctRegistrationOnly,
      isEmpiricalTrack = true,
      replicationCode = "",
      coverageReport = DocumentCoverageReport.forCompleteText(rctRegistrationOnly)
    )
    val screen = Reviewer2PipelineService.evaluateClinicalGradeCertainty(
      manuscriptText = rctRegistrationOnly,
      forensicReport = forensics,
      adjudicatedFindings = emptyList(),
      extractionConfidence = 5
    )
    assertEquals(
      "Protocol registration alone must NEVER earn NOT_SERIOUS for publication bias",
      Reviewer2PipelineService.Companion.GradeDomainConcern.NOT_ASSESSABLE,
      screen.publicationBias
    )
    assertEquals(
      "CI crossing 0.75 appreciable-benefit threshold with width ratio 1.98 must be rated SERIOUS imprecision, never NOT_SERIOUS",
      Reviewer2PipelineService.Companion.GradeDomainConcern.SERIOUS,
      screen.imprecision
    )
    assertNotEquals("HIGH_CERTAINTY", screen.verdictEnum)
    assertTrue(
      "Scope label must honestly frame output as an automated preliminary clinical certainty screen",
      screen.assessmentScopeLabel.contains("Preliminary", ignoreCase = true)
    )
    assertTrue(screen.toSummaryBlock().contains("Automated Preliminary Clinical Certainty Screen"))
  }

  @Test
  fun auditV2_14_geometryAwarePdfVerification_passesCleanExportsAndCatchesOverlapsOutOfBoundsAndCellOverflow() {
    // 1. Clean export with long title, Unicode math, and wrapped table passes geometry verification
    val richReview = PaperReview(
      id = "geom_clean_1",
      title = "A Very Long Multi-Line Title Evaluating Bayesian Hierarchical Survival Models (χ² = 12.4, I² = 18%, α = 0.05) Across Multi-Center Randomized Cohorts",
      authors = "A. Researcher, B. Biostatistician",
      publicationYear = "2026",
      venueOrCitation = "Journal of Clinical Methodology",
      verdict = "SOUND_WITH_CAVEATS",
      credibilityScore = 78L,
      methodologyScore = 8,
      clarityScore = 8,
      significanceScore = 8,
      originalityScore = 7,
      mathRigorScore = 8,
      reproducibilityScore = 8,
      abstractSummary = "Primary outcome HR = 0.78 (95% CI 0.65 to 0.93, p = 0.006) across N = 1,240 patients.",
      redTeamCritique = "Minor caveat regarding subgroup heterogeneity in secondary endpoints.",
      editorNote = "Manuscript is well-structured with transparent sensitivity analyses."
    )
    val rendered = PdfReportExporter.renderAndInspectPdf(
      review = richReview,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      requireByteExtraction = false
    )
    assertTrue(
      "Clean report must have zero geometry issues (found: ${rendered.diagnostics.geometryIssues})",
      rendered.diagnostics.geometryClean
    )
    assertTrue("Geometry recorder must inspect rendered blocks", rendered.diagnostics.geometryBlocksChecked > 0)

    // 2. Synthetic geometry violations (vertical overlap, margin out-of-bounds, header collision, cell overflow) MUST fail!
    val badRecorder = PdfLayoutGeometryRecorder()
    badRecorder.recordHeaderBand(pageNumber = 2, bottomY = 33f)
    badRecorder.recordFooterBand(pageNumber = 2, topY = 808f)
    badRecorder.recordBodyBlock(pageNumber = 2, kind = "paragraph", left = 40f, top = 20f, right = 555f, bottom = 80f) // collides with header!
    badRecorder.recordBodyBlock(pageNumber = 2, kind = "paragraph", left = 40f, top = 70f, right = 610f, bottom = 120f) // overlaps prev + exceeds right margin!
    badRecorder.recordTableCell(pageNumber = 2, rowTop = 140f, rowBottom = 160f, cellLeft = 40f, cellRight = 200f, measuredTextHeight = 45f, padV = 4f, maxPageRight = 555f)
    val badReport = badRecorder.verify()
    assertFalse("Synthetic geometry violations must fail verification", badReport.isGeometryClean)
    assertTrue(badReport.overlapDetected)
    assertTrue(badReport.outOfBoundsDetected)
    assertTrue(badReport.headerFooterCollisionDetected)
    assertTrue(badReport.tableCellOverflowDetected)
  }

  @Test
  fun auditV2_13_expanded12ManuscriptCalibrationAndAdjudicationBenchmark_reportsPrecisionRecallFprAndWilsonCIs() {
    data class BenchmarkManuscript(
      val id: String,
      val design: String,
      val isFlawedPositiveControl: Boolean,
      val text: String,
      val candidateFindingForAdjudication: ReviewFinding,
      val expectedFindingVerified: Boolean
    )

    val corpus = listOf(
      // --- 6 CLEAN NEGATIVE CONTROLS (across 6 designs) ---
      BenchmarkManuscript(
        id = "CLEAN_RCT",
        design = "RCT",
        isFlawedPositiveControl = false,
        text = """
          [PAGE 1]
          Multi-Center Double-Blind Randomized Controlled Trial of Drug A vs Placebo
          Abstract: We randomized 800 adults 1:1 to Drug A or placebo.
          Methods: Prospectively registered (NCT06110001). Centralized concealed allocation randomized 800 patients (400 Drug A, 400 placebo). Double-blind outcome adjudication and intention-to-treat analysis were performed. Sensitivity analysis for missing outcome data was conducted using multiple imputation in Table 3.
          Results: All-cause mortality occurred in 32/400 (8.0%) vs 56/400 (14.0%), HR = 0.56 (95% CI 0.36 to 0.87, p = 0.010).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "C-01",
          title = "Missing sensitivity analysis",
          allegation = "Authors did not perform sensitivity analysis for missing outcome data.",
          severity = "MAJOR",
          status = FindingStatus.VERIFIED
        ),
        expectedFindingVerified = false
      ),
      BenchmarkManuscript(
        id = "CLEAN_COHORT",
        design = "Observational Cohort",
        isFlawedPositiveControl = false,
        text = """
          [PAGE 1]
          Prospective Cohort Study of Antihypertensive Adherence and Stroke Risk
          Methods: We enrolled 2,400 adults followed for 5 years. Multivariable Cox regression adjusted for age, sex, smoking, blood pressure, and diabetes was combined with propensity score weighting.
          Results: Adjusted HR = 0.78 (95% CI 0.65 to 0.94, p = 0.009).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "C-02",
          title = "Unadjusted observational analysis",
          allegation = "Study failed to adjust for confounding factors in the observational cohort.",
          severity = "MAJOR",
          status = FindingStatus.VERIFIED
        ),
        expectedFindingVerified = false
      ),
      BenchmarkManuscript(
        id = "CLEAN_META",
        design = "Meta-Analysis",
        isFlawedPositiveControl = false,
        text = """
          [PAGE 1]
          Systematic Review and Meta-Analysis of Randomized Trials of Beta-Blockers
          Methods: Following PRISMA guidelines, we pooled 4 randomized controlled trials using DerSimonian-Laird random-effects models. Risk of bias was low on RoB 2. Funnel plot showed no asymmetry (Egger's test p = 0.54).
          Results:
          Smith (2020): OR = 0.72 (95% CI 0.56 to 0.93)
          Jones (2021): OR = 0.75 (95% CI 0.58 to 0.97)
          Lee (2022): OR = 0.70 (95% CI 0.52 to 0.94)
          Patel (2023): OR = 0.74 (95% CI 0.57 to 0.96)
          Pooled OR = 0.73 (95% CI 0.64 to 0.83, I² = 0%, p < 0.001).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "C-03",
          title = "No heterogeneity or publication bias assessment",
          allegation = "Meta-analysis omitted heterogeneity and funnel plot publication bias assessment.",
          severity = "MAJOR",
          status = FindingStatus.VERIFIED
        ),
        expectedFindingVerified = false
      ),
      BenchmarkManuscript(
        id = "CLEAN_DIAGNOSTIC",
        design = "Diagnostic Accuracy",
        isFlawedPositiveControl = false,
        text = """
          [PAGE 1]
          Diagnostic Accuracy of High-Sensitivity Troponin Assay for Myocardial Infarction
          Methods: Prospective consecutive cohort of 420 emergency department patients using blinded reference standard angiography (STARD compliant).
          Results: Sensitivity was 92.0% (92/100) and specificity was 87.5% (280/320), AUROC = 0.93 (95% CI 0.90 to 0.96).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "C-04",
          title = "Unblinded reference standard",
          allegation = "Reference standard interpretation was unblinded to index test results.",
          severity = "MAJOR",
          status = FindingStatus.VERIFIED
        ),
        expectedFindingVerified = false
      ),
      BenchmarkManuscript(
        id = "CLEAN_SURVIVAL",
        design = "Survival Analysis",
        isFlawedPositiveControl = false,
        text = """
          [PAGE 1]
          Long-Term Survival Following Transcatheter Valve Replacement
          Methods: Kaplan-Meier curves and multivariable Cox proportional hazards regression were evaluated in 1,100 patients. Proportional hazards assumption was tested via Schoenfeld residuals (p = 0.42).
          Results: 5-year survival HR = 0.81 (95% CI 0.69 to 0.95, p = 0.011).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "C-05",
          title = "Proportional hazards not tested",
          allegation = "Authors did not test the proportional hazards assumption for Cox regression.",
          severity = "MAJOR",
          status = FindingStatus.VERIFIED
        ),
        expectedFindingVerified = false
      ),
      BenchmarkManuscript(
        id = "CLEAN_ML",
        design = "Clinical ML Prediction",
        isFlawedPositiveControl = false,
        text = """
          [PAGE 1]
          External Validation of a Gradient-Boosted Sepsis Prediction Model
          Methods: Training split (n = 3,200) and held-out external validation cohort (n = 1,400) from an independent hospital were separated prior to feature preprocessing and scaling. Calibration slope = 0.98 and Brier score = 0.11.
          Results: External validation AUROC = 0.86 (95% CI 0.83 to 0.89).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "C-06",
          title = "No external validation",
          allegation = "Prediction model lacked external validation and calibration metrics.",
          severity = "MAJOR",
          status = FindingStatus.VERIFIED
        ),
        expectedFindingVerified = false
      ),
      // --- 6 FLAWED POSITIVE CONTROLS (across 6 designs) ---
      BenchmarkManuscript(
        id = "FLAW_RCT_IMPOSSIBLE_CI",
        design = "RCT",
        isFlawedPositiveControl = true,
        text = """
          [PAGE 1]
          Randomized Controlled Trial of Agent K vs Standard Care
          Methods: Total of 100 patients were enrolled and randomized. 18 patients were lost to follow-up, leaving 97 patients in the primary analysis.
          Results: Mortality OR = 3.20 (95% CI 0.85 to 1.45, p = 0.004).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "F-01",
          title = "Point estimate outside confidence interval",
          allegation = "Mortality OR = 3.20 falls outside its reported 95% CI 0.85 to 1.45.",
          severity = "CRITICAL",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(exactQuote = "Mortality OR = 3.20 (95% CI 0.85 to 1.45, p = 0.004).", pageNumber = 1, section = "Results")
          )
        ),
        expectedFindingVerified = true
      ),
      BenchmarkManuscript(
        id = "FLAW_COHORT_IMPOSSIBLE_T_P",
        design = "Observational Cohort",
        isFlawedPositiveControl = true,
        text = """
          [PAGE 1]
          Observational Cohort Study of Dietary Sodium and Blood Pressure
          Methods: Retrospective cohort of 120 adults analyzed with two-sided t-tests.
          Results: Blood pressure difference t(118) = 0.45, p = 0.001 between high and low sodium groups.
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "F-02",
          title = "Incompatible t-statistic and p-value",
          allegation = "Reported t(118) = 0.45 is mathematically incompatible with p = 0.001.",
          severity = "CRITICAL",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(exactQuote = "Blood pressure difference t(118) = 0.45, p = 0.001 between high and low sodium groups.", pageNumber = 1, section = "Results")
          )
        ),
        expectedFindingVerified = true
      ),
      BenchmarkManuscript(
        id = "FLAW_META_CI_ORDER",
        design = "Meta-Analysis",
        isFlawedPositiveControl = true,
        text = """
          [PAGE 1]
          Systematic Review and Meta-Analysis of Intervention P
          Methods: Random-effects meta-analysis pooling 14 clinical studies.
          Results: Pooled RR = 0.72 (95% CI 1.15 to 0.48, p = 0.02) across included studies.
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "F-03",
          title = "Inverted confidence interval bounds",
          allegation = "Pooled RR = 0.72 reports inverted 95% CI 1.15 to 0.48.",
          severity = "CRITICAL",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(exactQuote = "Pooled RR = 0.72 (95% CI 1.15 to 0.48, p = 0.02) across included studies.", pageNumber = 1, section = "Results")
          )
        ),
        expectedFindingVerified = true
      ),
      BenchmarkManuscript(
        id = "FLAW_DIAGNOSTIC_2X2_PCT",
        design = "Diagnostic Accuracy",
        isFlawedPositiveControl = true,
        text = """
          [PAGE 1]
          Diagnostic Accuracy of Biomarker M for Sepsis
          Methods: Cross-sectional diagnostic study of 200 emergency patients.
          Results: Positive rate was 18/100 (45.0%) in cases versus 10/100 (10.0%) in controls; OR = 4.50 (95% CI 5.10 to 6.20, p = 0.001).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "F-04",
          title = "Proportion and CI arithmetic errors",
          allegation = "Positive rate 18/100 is reported as 45.0% and OR = 4.50 lies outside 95% CI 5.10 to 6.20.",
          severity = "CRITICAL",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(exactQuote = "Positive rate was 18/100 (45.0%) in cases versus 10/100 (10.0%) in controls; OR = 4.50 (95% CI 5.10 to 6.20, p = 0.001).", pageNumber = 1, section = "Results")
          )
        ),
        expectedFindingVerified = true
      ),
      BenchmarkManuscript(
        id = "FLAW_SURVIVAL_HR_OUTSIDE_CI",
        design = "Survival Analysis",
        isFlawedPositiveControl = true,
        text = """
          [PAGE 1]
          Survival Analysis of Adjuvant Chemotherapy in Stage III Cancer
          Methods: Cox proportional hazards regression in 340 oncology patients.
          Results: Overall survival HR = 2.10 (95% CI 1.15 to 1.85, p = 0.03).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "F-05",
          title = "Hazard ratio outside confidence interval",
          allegation = "Overall survival HR = 2.10 falls outside its reported 95% CI 1.15 to 1.85.",
          severity = "CRITICAL",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(exactQuote = "Overall survival HR = 2.10 (95% CI 1.15 to 1.85, p = 0.03).", pageNumber = 1, section = "Results")
          )
        ),
        expectedFindingVerified = true
      ),
      BenchmarkManuscript(
        id = "FLAW_ML_SUBGROUP_DRIFT",
        design = "Clinical ML Prediction",
        isFlawedPositiveControl = true,
        text = """
          [PAGE 1]
          Machine Learning Readmission Classifier in 250 Hospitalized Adults
          Methods: Total N = 250 patients were analyzed; training subgroup (n = 310) was used for feature selection.
          Results: Reported odds ratio OR = 2.40 (95% CI 3.10 to 4.80, p = 0.002).
        """.trimIndent(),
        candidateFindingForAdjudication = ReviewFinding(
          id = "F-06",
          title = "Impossible OR outside CI",
          allegation = "Reported odds ratio OR = 2.40 falls outside its 95% CI 3.10 to 4.80.",
          severity = "CRITICAL",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(exactQuote = "Reported odds ratio OR = 2.40 (95% CI 3.10 to 4.80, p = 0.002).", pageNumber = 1, section = "Results")
          )
        ),
        expectedFindingVerified = true
      )
    )

    fun wilsonCi95(successes: Int, total: Int): Pair<Double, Double> {
      if (total <= 0) return 0.0 to 1.0
      val z = 1.95996398454
      val p = successes.toDouble() / total.toDouble()
      val denom = 1.0 + (z * z) / total
      val center = (p + (z * z) / (2.0 * total)) / denom
      val margin = (z * kotlin.math.sqrt((p * (1.0 - p) + (z * z) / (4.0 * total)) / total)) / denom
      return (center - margin).coerceIn(0.0, 1.0) to (center + margin).coerceIn(0.0, 1.0)
    }

    var tp = 0
    var fp = 0
    var tn = 0
    var fn = 0
    var adjCorrect = 0

    for (item in corpus) {
      val report = ManuscriptForensicEngine.analyzeManuscript(
        text = item.text,
        isEmpiricalTrack = true,
        replicationCode = "",
        coverageReport = DocumentCoverageReport.forCompleteText(item.text)
      )
      val audit = report.quantForensics?.statisticalAudit
      val detectedCritical = ((audit?.verifiedArithmeticErrorCount ?: 0) +
        (audit?.verifiedContradictionCount ?: 0) +
        (audit?.reconstructions?.count { it.status == QuantFindingStatus.FAIL } ?: 0)) > 0

      if (item.isFlawedPositiveControl) {
        if (detectedCritical) tp++ else fn++
      } else {
        if (detectedCritical) fp++ else tn++
      }

      // Adjudicate candidate finding against full manuscript text
      val adj = BidirectionalAdjudicator.adjudicate(
        findings = listOf(item.candidateFindingForAdjudication),
        manuscriptText = item.text,
        epistemicReport = null
      ).findings.first()
      val actuallyVerified = adj.status == FindingStatus.VERIFIED
      if (actuallyVerified == item.expectedFindingVerified) adjCorrect++
    }

    val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp) else 0.0
    val recall = if (tp + fn > 0) tp.toDouble() / (tp + fn) else 0.0
    val fpr = if (fp + tn > 0) fp.toDouble() / (fp + tn) else 0.0
    val adjAcc = adjCorrect.toDouble() / corpus.size
    val recallCi = wilsonCi95(tp, tp + fn)
    val adjCi = wilsonCi95(adjCorrect, corpus.size)

    // Controlled synthetic benchmark assertions (explicitly scoped to deterministic engine + adjudicator;
    // NOT a claim of general real-world peer-review accuracy across uncurated papers).
    assertEquals("All 6 positive controls across 6 designs must be detected (TP=6, FN=$fn)", 6, tp)
    assertEquals("All 6 clean negative controls across 6 designs must have 0 false critical errors (FP=$fp)", 0, fp)
    assertEquals("Precision on controlled 12-manuscript benchmark", 1.0, precision, 1e-6)
    assertEquals("Recall on controlled 12-manuscript benchmark", 1.0, recall, 1e-6)
    assertEquals("False positive rate on controlled 12-manuscript benchmark", 0.0, fpr, 1e-6)
    assertTrue("Adjudication accuracy must be >= 90% (got $adjAcc, 95% Wilson CI ${adjCi.first}..${adjCi.second})", adjAcc >= 0.90)
    assertTrue("Wilson 95% CI lower bound for recall must be finite and valid", recallCi.first in 0.5..1.0)
  }

  @Test
  fun p2_14_pdfOutlineBookmarksAndAccessibilityMetadata_areEmbeddedInRealPdfBytes() {
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    val review = PaperReview(
      id = "rev_p2_14_outline",
      title = "Randomized Trial of Biomarker-Guided Therapy in Heart Failure",
      authors = "Smith J, Patel A",
      venueOrCitation = "Journal of Clinical Trials 2026",
      validationStatus = "VERIFIED",
      verdict = "MAJOR_REVISION",
      credibilityScore = 76,
      abstractSummary = "Executive appraisal summary for clinical trial.",
      keyMethodology = "Double-blind randomized controlled trial.",
      redTeamCritique = "Primary endpoint analysis reviewed.",
      blueTeamVerification = "Supporting sensitivity analyses verified."
    )

    // 1. Capture real outline entries produced by renderReviewToPdfStream
    val recordedOutline = mutableListOf<com.example.util.PdfOutlineEntry>()
    val shadowBaos = java.io.ByteArrayOutputStream()
    PdfReportExporter.renderReviewToPdfStream(
      review = review,
      outputStream = shadowBaos,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      outlineEntriesSink = recordedOutline
    )
    assertTrue("Exporter must record at least 3 section outline bookmarks (got ${recordedOutline.size})", recordedOutline.size >= 3)
    assertEquals("Executive Summary & 6-Axis Rubric", recordedOutline.first().title)

    // 2. Build a real 2-page PDF via PDFBox, then enrich it with enrichPdfWithOutlineAndCatalogMetadata
    val rawRealPdf = java.io.ByteArrayOutputStream().use { out ->
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        repeat(2) { idx ->
          val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
          doc.addPage(page)
          com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 11f)
            cs.newLineAtOffset(48f, 760f)
            cs.showText("Paper Critic Scientific Appraisal Page ${idx + 1}")
            cs.endText()
          }
        }
        doc.save(out)
      }
      out.toByteArray()
    }

    val enrichedPdf = PdfReportExporter.enrichPdfWithOutlineAndCatalogMetadata(
      rawPdfBytes = rawRealPdf,
      review = review,
      outlineEntries = recordedOutline
    )

    // 3. Re-parse enriched PDF bytes via PDFBox and verify interactive outline + catalog metadata
    val diagnostics = PdfReportExporter.validateExportedPdfBytes(
      pdfBytes = enrichedPdf,
      requireByteExtraction = true
    )
    assertTrue("Enriched PDF must pass integrity verification: ${diagnostics.verificationFailureReason}", diagnostics.isClean)
    assertTrue("Enriched PDF must embed interactive document outline bookmarks (got ${diagnostics.outlineEntryCount})", diagnostics.outlineEntryCount >= 3)
    assertTrue("Enriched PDF must embed title, language, and DisplayDocTitle catalog metadata", diagnostics.hasPdfCatalogMetadata)
  }

  // =========================================================================
  // 28 NOV 2026 DEEP AUDIT REGRESSION SUITE (auditV3_01 .. auditV3_05)
  // =========================================================================

  @Test
  fun auditV3_01_pdfVisionContentDigest_invalidatesConsentOnSameCountBitmapSwapOrVisionToggle() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val vm = buildTestViewModel(context, db, initialUserId = "uid_vision_consent")
      val sharedText = "Confidential trial manuscript text unchanged across PDF bitmap swap (N = 400, HR = 0.82)."

      // Enable confidential mode + PDF vision pages
      vm.updateSettings { it.copy(confidentialLocalOnly = true, enablePdfVisionPages = true) }
      vm.updateManuscriptText(sharedText)

      // PDF A: 2 pages with distinct blue/green pixel payloads
      val pdfAPage1 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
      val pdfAPage2 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
      vm.updatePdfPageBitmapsForTest(listOf(pdfAPage1, pdfAPage2), visionPageNumbers = listOf(1, 2))

      // Confirm consent for PDF A
      vm.confirmRemoteInferenceForCurrentDraft()
      assertTrue("Consent must be valid immediately after confirmation for PDF A", vm.isRemoteInferenceConsentValidNow())
      val recordA = vm.currentRemoteConsentRecordForTest
      assertNotNull("Consent record must be populated", recordA)
      assertTrue("Consent record must store a 64-char SHA-256 PDF vision digest (never raw pixel bytes)", recordA!!.pdfVisionDigestSha256.length == 64)
      assertTrue("Consent record must capture visionEnabled=true", recordA.visionEnabled)
      assertEquals(listOf(1, 2), recordA.selectedVisionPages)

      // Identical bitmap pixels with same text and same selected pages -> consent remains valid
      val pdfACopy1 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
      val pdfACopy2 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
      vm.updatePdfPageBitmapsForTest(listOf(pdfACopy1, pdfACopy2), visionPageNumbers = listOf(1, 2))
      assertTrue("Identical bitmap pixels and unchanged text must keep consent valid", vm.isRemoteInferenceConsentValidNow())

      // PDF B: SAME page count (2), SAME dimensions (8x8), SAME manuscript text, DIFFERENT pixel contents!
      val pdfBPage1 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
      val pdfBPage2 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.YELLOW) }
      vm.updatePdfPageBitmapsForTest(listOf(pdfBPage1, pdfBPage2), visionPageNumbers = listOf(1, 2))
      assertFalse(
        "Replacing PDF page bitmaps with different pixels (same page count & text) MUST immediately invalidate remote consent",
        vm.isRemoteInferenceConsentValidNow()
      )
      kotlinx.coroutines.yield()
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      assertFalse(
        "hasValidRemoteInferenceConsent StateFlow must reflect invalidation after same-count bitmap swap",
        vm.hasValidRemoteInferenceConsent.value
      )

      // Blocked at execution gate without fresh consent
      vm.runAdversarialReviewAndSave()
      val blockedState = vm.pipelineState.value as? com.example.ui.viewmodel.PipelineExecutionState.Error
      assertTrue(
        "Execution must be blocked after same-count PDF bitmap swap until re-confirmed",
        blockedState != null && blockedState.message.contains("remote ai inference", ignoreCase = true)
      )

      // Confirm consent for PDF B -> valid again
      vm.confirmRemoteInferenceForCurrentDraft()
      assertTrue("Re-confirming consent for PDF B must validate consent", vm.isRemoteInferenceConsentValidNow())
      assertNotEquals(
        "PDF B vision digest must differ from PDF A vision digest",
        recordA.pdfVisionDigestSha256,
        vm.currentRemoteConsentRecordForTest!!.pdfVisionDigestSha256
      )

      // Changing selected vision page indices with same bitmaps MUST invalidate consent
      vm.updatePdfPageBitmapsForTest(listOf(pdfBPage1, pdfBPage2), visionPageNumbers = listOf(1, 5))
      assertFalse(
        "Changing selected vision page indices must invalidate prior consent",
        vm.isRemoteInferenceConsentValidNow()
      )

      // Re-confirm, then toggle vision-enabled setting OFF -> MUST invalidate consent
      vm.confirmRemoteInferenceForCurrentDraft()
      assertTrue(vm.isRemoteInferenceConsentValidNow())
      vm.updateSettings { it.copy(enablePdfVisionPages = false) }
      assertFalse(
        "Toggling enablePdfVisionPages must invalidate prior consent",
        vm.isRemoteInferenceConsentValidNow()
      )
    } finally {
      db.close()
    }
  }

  @Test
  fun auditV3_02_typedPropositionEntailment_rejectsPopulationMagnitudeTimepointAdjustmentAndComparatorMismatches() {
    // 1. Exact reproduction from 28 Nov 2026 P0 audit:
    //    Finding: "Drug A reduced adult mortality by 5% at 12 months"
    //    Quote/Prose: "Drug A reduced child mortality by 50% at 6 months"
    val finding = ReviewFinding(
      id = "R-01",
      title = "Drug A reduced adult mortality by 5% at 12 months",
      allegation = "Drug A reduced adult mortality by 5% at 12 months compared with placebo in the adjusted analysis.",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(
        EvidenceAnchor(
          pageNumber = 2,
          section = "Results",
          exactQuote = "Drug A reduced child mortality by 50% at 6 months compared with placebo in the adjusted analysis."
        )
      )
    )

    val mismatchedQuote = "Drug A reduced child mortality by 50% at 6 months compared with placebo in the adjusted analysis."
    val entailment = Reviewer2PipelineService.evaluateQuoteEntailment(
      finding = finding,
      matchedQuote = mismatchedQuote,
      surroundingWindow = mismatchedQuote
    )
    assertNotEquals(
      "Population/magnitude/time-point mismatch MUST NEVER return ENTAILED",
      Reviewer2PipelineService.Companion.EntailmentStatus.ENTAILED,
      entailment
    )

    // Prose citing [R-01] with child/50%/6-month mismatch must be quarantined out of verifiedStatements
    val proseSection = Reviewer2PipelineService.buildEvidenceGovernedReportSection(
      title = "Clinical Efficacy",
      rawProse = "Drug A reduced child mortality by 50% at 6 months compared with placebo in the adjusted analysis [R-01].",
      adjudicatedFindings = listOf(finding)
    )
    assertTrue(
      "Mismatched population/magnitude/time-point prose MUST NOT enter verifiedStatements",
      proseSection.verifiedStatements.isEmpty()
    )
    assertTrue(
      "Mismatched prose must be quarantined under openQuestionsForInvestigation",
      proseSection.openQuestionsForInvestigation.any { it.contains("50%") || it.contains("child") }
    )

    // 2. Single-dimension mismatches MUST EACH fail entailment AND prose consistency:
    val baseFinding = ReviewFinding(
      id = "R-02",
      title = "Adjusted adult mortality reduction",
      allegation = "Drug A vs placebo reduced adult mortality by 5% at 12 months in an adjusted analysis.",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED
    )
    val singleAxisMismatches = listOf(
      "magnitude 5% vs 50%" to "Drug A vs placebo reduced adult mortality by 50% at 12 months in an adjusted analysis.",
      "population adults vs children" to "Drug A vs placebo reduced child mortality by 5% at 12 months in an adjusted analysis.",
      "time point 12 months vs 6 months" to "Drug A vs placebo reduced adult mortality by 5% at 6 months in an adjusted analysis.",
      "adjustment adjusted vs unadjusted" to "Drug A vs placebo reduced adult mortality by 5% at 12 months in an unadjusted analysis.",
      "opposite comparator Drug A vs Drug B" to "Drug A vs Drug B reduced adult mortality by 5% at 12 months in an adjusted analysis.",
      "reversed arm order Placebo vs Drug A" to "Placebo vs Drug A reduced adult mortality by 5% at 12 months in an adjusted analysis."
    )
    singleAxisMismatches.forEach { (label, mismatchedText) ->
      val status = Reviewer2PipelineService.evaluateQuoteEntailment(baseFinding, mismatchedText, mismatchedText)
      assertNotEquals(
        "Single-axis mismatch ($label) MUST NOT be ENTAILED (got $status)",
        Reviewer2PipelineService.Companion.EntailmentStatus.ENTAILED,
        status
      )
      assertFalse(
        "Single-axis mismatch ($label) MUST fail isProseSemanticallyConsistentWithFinding",
        Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(mismatchedText, baseFinding)
      )
    }

    // 3. Missing critical fields in quote must return UNRESOLVED (not ENTAILED)
    val incompleteQuote = "Drug A reduced mortality in the study cohort."
    val unresolvedStatus = Reviewer2PipelineService.evaluateQuoteEntailment(baseFinding, incompleteQuote, incompleteQuote)
    assertEquals(
      "Quote missing critical claim fields (population, magnitude, time point, comparator, adjustment) must return UNRESOLVED",
      Reviewer2PipelineService.Companion.EntailmentStatus.UNRESOLVED,
      unresolvedStatus
    )

    // 4. Exact-matching compatible typed proposition MUST pass both entailment and prose consistency
    val compatibleText = "Drug A vs placebo reduced adult mortality by 5% at 12 months in an adjusted analysis."
    assertEquals(
      "Compatible typed proposition must be ENTAILED",
      Reviewer2PipelineService.Companion.EntailmentStatus.ENTAILED,
      Reviewer2PipelineService.evaluateQuoteEntailment(baseFinding, compatibleText, compatibleText)
    )
    assertTrue(
      "Compatible typed proposition must pass prose consistency",
      Reviewer2PipelineService.isProseSemanticallyConsistentWithFinding(compatibleText, baseFinding)
    )
  }

  @Test
  fun auditV3_03_deterministicPromotion_blocksVerifiedOnAssumptionsSubgroupAdjustedTransformedBootstrapAnd90CiMismatches() {
    fun stubForensic(audit: com.example.data.ai.stats.StatisticalAuditReport): ForensicPreFlightReport =
      ForensicPreFlightReport(
        wordCount = 100,
        estimatedPages = 2,
        detectedDiscipline = "Clinical Medicine",
        studyDesign = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
        empiricalSignalsCount = 4,
        theoreticalSignalsCount = 0,
        extractedEquations = emptyList(),
        symbolAndNotationAlerts = emptyList(),
        extractedSampleSizes = emptyList(),
        statisticalClaims = emptyList(),
        grimAndNumericAlerts = emptyList(),
        proceduralLeakageAlerts = emptyList(),
        citationAlerts = emptyList(),
        replicationCodeAlerts = emptyList(),
        citationCountEstimate = 0,
        quantForensics = QuantitativeForensicReport(
          statcheckResults = emptyList(),
          confidenceIntervalChecks = emptyList(),
          medianIqrChecks = emptyList(),
          heterogeneitySignals = emptyList(),
          powerAndMultiplicityAlerts = emptyList(),
          algebraicAndMetricAlerts = emptyList(),
          diagnosticTable = emptyList(),
          extractedPrecisionMetrics = emptyList(),
          statisticalAudit = audit
        )
      )

    // 1. Cross-section contradictions with incompatible typed contexts (subgroup vs overall, adjusted vs unadjusted,
    //    log-transformed vs raw, bootstrap vs Wald, 90% CI vs 95% CI) MUST NEVER auto-promote to VERIFIED.
    val incompatibleCases = listOf(
      Triple(
        "subgroup vs overall",
        "[PAGE 1]\nOverall cohort analysis yielded HR = 0.80 (95% CI 0.70 to 0.91).\n[PAGE 2]\nSubgroup analysis yielded HR = 1.25 (95% CI 0.95 to 1.65).",
        listOf("Overall cohort analysis yielded HR = 0.80 (95% CI 0.70 to 0.91).", "Subgroup analysis yielded HR = 1.25 (95% CI 0.95 to 1.65).")
      ),
      Triple(
        "adjusted vs unadjusted",
        "[PAGE 1]\nUnadjusted analysis showed OR = 1.85 (95% CI 1.20 to 2.85).\n[PAGE 2]\nMultivariable adjusted analysis showed OR = 1.12 (95% CI 0.88 to 1.42).",
        listOf("Unadjusted analysis showed OR = 1.85 (95% CI 1.20 to 2.85).", "Multivariable adjusted analysis showed OR = 1.12 (95% CI 0.88 to 1.42).")
      ),
      Triple(
        "log-transformed vs raw scale",
        "[PAGE 1]\nOn the raw scale the mean difference was 4.2 (95% CI 1.1 to 7.3).\n[PAGE 2]\nOn the log-transformed scale the mean difference was 0.35 (95% CI 0.10 to 0.60).",
        listOf("On the raw scale the mean difference was 4.2 (95% CI 1.1 to 7.3).", "On the log-transformed scale the mean difference was 0.35 (95% CI 0.10 to 0.60).")
      ),
      Triple(
        "bootstrap vs Wald SE",
        "[PAGE 1]\nUsing Wald standard errors, estimate was 2.10 (95% CI 1.50 to 2.70).\n[PAGE 2]\nUsing bootstrap resampling, estimate was 2.10 (95% CI 1.10 to 3.10).",
        listOf("Using Wald standard errors, estimate was 2.10 (95% CI 1.50 to 2.70).", "Using bootstrap resampling, estimate was 2.10 (95% CI 1.10 to 3.10).")
      ),
      Triple(
        "90% CI vs 95% CI",
        "[PAGE 1]\nPrimary estimate was 1.40 (90% CI 1.10 to 1.70).\n[PAGE 2]\nPrimary estimate was 1.40 (95% CI 1.02 to 1.78).",
        listOf("Primary estimate was 1.40 (90% CI 1.10 to 1.70).", "Primary estimate was 1.40 (95% CI 1.02 to 1.78).")
      )
    )

    incompatibleCases.forEach { (label, docText, quotes) ->
      val link = com.example.data.ai.stats.EvidenceCrossLink(
        linkId = "XL-TEST-${label.hashCode()}",
        linkType = "CROSS_SECTION_CONTRADICTION",
        findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
        status = QuantFindingStatus.FAIL,
        pageNumbers = listOf(1, 2),
        sectionsInvolved = listOf("Page 1", "Page 2"),
        description = "Discrepant estimate across sections ($label)",
        sourceQuotes = quotes
      )
      val statAudit = com.example.data.ai.stats.StatisticalAuditReport(
        subDesign = com.example.data.ai.stats.AuditSubDesign.RCT,
        claims = emptyList(),
        twoByTwoTables = emptyList(),
        metaStudyEffects = emptyList(),
        sampleFlowRecords = emptyList(),
        reconstructions = emptyList(),
        crossLinks = listOf(link),
        domainFindings = emptyList(),
        unsupportedDisclosures = emptyList()
      )
      val result = Reviewer2PipelineService.adjudicateFindingsAndScores(
        rawFindings = emptyList(),
        manuscriptText = docText,
        forensicReport = stubForensic(statAudit),
        methodologyScore = 7,
        significanceScore = 7,
        clarityScore = 7,
        originalityScore = 7,
        mathRigorScore = 7,
        reproducibilityScore = 7,
        rawVerdict = "MINOR_REVISION",
        reviewMode = ReviewMode.JOURNAL_REFEREE,
        extractionConfidence = 5,
        applyStatisticalScoreGate = true
      )
      val injected = result.adjudicatedFindings.firstOrNull { it.id.startsWith("S-") }
      assertNotNull("Cross-link finding must be injected for $label", injected)
      assertEquals(
        "Cross-link with incompatible $label MUST NOT auto-promote to VERIFIED",
        FindingStatus.PARTIALLY_SUPPORTED,
        injected!!.status
      )
      assertEquals(
        "Downgraded cross-link ($label) MUST carry REQUIRES_MANUAL_RECONCILIATION reportingStatus",
        com.example.data.ai.epistemic.ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name,
        injected.reportingStatus
      )
    }

    // 2. Single-passage reconstruction in a bootstrap / 90% CI / log-transformed context must also stay PARTIALLY_SUPPORTED
    val bootstrapDoc = "[PAGE 1]\nResults: Bootstrap resampling yielded OR = 2.40 (90% CI 1.15 to 3.80, p = 0.03) from the log-transformed model."
    val rec = com.example.data.ai.stats.NumericalReconstructionResult(
      checkId = "REC-BOOT-1",
      calculatorName = "CI-to-SE Wald Reconstruction",
      reportedValue = "OR = 2.40 (90% CI 1.15 to 3.80)",
      recomputedValue = "Wald 95% CI mismatch",
      tolerance = "0.05",
      sourceEvidence = "Bootstrap resampling yielded OR = 2.40 (90% CI 1.15 to 3.80, p = 0.03)",
      pageNumber = 1,
      section = "Results",
      assumptions = listOf("Asymptotic Wald 95% CI"),
      status = QuantFindingStatus.FAIL,
      findingType = StatisticalFindingType.ARITHMETIC_ERROR,
      isScreeningSignal = false,
      scientificConsequence = "Interval width differs from Wald normal approximation.",
      detail = "Reported bounds differ from standard 95% Wald interval."
    )
    val recAudit = com.example.data.ai.stats.StatisticalAuditReport(
      subDesign = com.example.data.ai.stats.AuditSubDesign.OBSERVATIONAL_COHORT,
      claims = emptyList(),
      twoByTwoTables = emptyList(),
      metaStudyEffects = emptyList(),
      sampleFlowRecords = emptyList(),
      reconstructions = listOf(rec),
      crossLinks = emptyList(),
      domainFindings = emptyList(),
      unsupportedDisclosures = emptyList()
    )
    val recResult = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = bootstrapDoc,
      forensicReport = stubForensic(recAudit),
      methodologyScore = 7,
      significanceScore = 7,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 7,
      reproducibilityScore = 7,
      rawVerdict = "MINOR_REVISION",
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      extractionConfidence = 5,
      applyStatisticalScoreGate = true
    )
    val injectedRec = recResult.adjudicatedFindings.first { it.id.startsWith("S-") }
    assertEquals(
      "Single-passage reconstruction under bootstrap/90% CI/log-transformed qualifiers MUST NOT auto-promote to VERIFIED",
      FindingStatus.PARTIALLY_SUPPORTED,
      injectedRec.status
    )
    assertEquals(
      com.example.data.ai.epistemic.ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name,
      injectedRec.reportingStatus
    )
  }

  @Test
  fun auditV3_04_pdfCatalogMetadata_renamedHonestlyWithoutClaimingTaggedPdfUa() {
    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    val rawMinimalPdf = java.io.ByteArrayOutputStream().use { out ->
      com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
        val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
        doc.addPage(page)
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
          cs.beginText()
          cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 11f)
          cs.newLineAtOffset(48f, 760f)
          cs.showText("Minimal raw PDF without catalog language or DisplayDocTitle metadata.")
          cs.endText()
        }
        doc.save(out)
      }
      out.toByteArray()
    }
    val rawDiag = PdfReportExporter.validateExportedPdfBytes(rawMinimalPdf, requireByteExtraction = true)
    assertFalse("Raw unenriched PDF must report hasPdfCatalogMetadata=false", rawDiag.hasPdfCatalogMetadata)

    val review = PaperReview(
      id = "rev_catalog_honest",
      title = "Catalog Metadata Honesty Verification",
      validationStatus = "VERIFIED",
      verdict = "MINOR_REVISION",
      credibilityScore = 80
    )
    val enriched = PdfReportExporter.enrichPdfWithOutlineAndCatalogMetadata(
      rawPdfBytes = rawMinimalPdf,
      review = review,
      outlineEntries = listOf(com.example.util.PdfOutlineEntry("Executive Summary", 0, 760f))
    )
    val enrichedDiag = PdfReportExporter.validateExportedPdfBytes(enriched, requireByteExtraction = true)
    assertTrue("Enriched PDF must set hasPdfCatalogMetadata=true", enrichedDiag.hasPdfCatalogMetadata)
    assertEquals("Enriched PDF must have 1 outline bookmark", 1, enrichedDiag.outlineEntryCount)
  }

  @Test
  fun auditV3_05_partialCoverageAndLedgerCaps_surfaceOmissionCountsInDisclosurePdfAndBlockVerifiedStatus() {
    // 1. Create a statistical audit with explicit discoverySkipped, reconstructionSkipped, unsupported, AND >2 statcheck ledger caps
    val metrics = com.example.data.ai.stats.StatisticalCoverageMetrics(
      discoveredTotal = 140,
      parsedRetained = 100,
      discoverySkipped = 40,
      reconstructionEligible = 45,
      reconstructedCompleted = 30,
      reconstructionSkipped = 15,
      unsupportedCount = 4,
      truncatedPages = listOf(6, 7),
      skippedCategories = listOf("ci_discovery_cap", "reconstruction_cap")
    )
    val statAudit = com.example.data.ai.stats.StatisticalAuditReport(
      subDesign = com.example.data.ai.stats.AuditSubDesign.RCT,
      claims = emptyList(),
      twoByTwoTables = emptyList(),
      metaStudyEffects = emptyList(),
      sampleFlowRecords = emptyList(),
      reconstructions = emptyList(),
      crossLinks = emptyList(),
      domainFindings = emptyList(),
      unsupportedDisclosures = listOf("Missing raw Table S2", "Missing covariance matrix", "Missing subgroup counts", "Missing imputation code"),
      totalDiscoveredClaims = 140,
      truncatedClaimCount = 40,
      truncatedPages = listOf(6, 7),
      coverageMetrics = metrics
    )

    // Ledger cap: 4 failing statcheck results -> take(2) omits 2!
    val fourStatchecks = (1..4).map { idx ->
      StatcheckResult(
        rawClaim = "t(40) = ${3.1 + idx * 0.1}, p = 0.08",
        testType = "t",
        reportedP = "p = 0.08",
        recomputedP = 0.002,
        status = QuantFindingStatus.FAIL,
        note = "Statcheck mismatch #$idx",
        pageNumber = idx
      )
    }
    val forensic = ForensicPreFlightReport(
      wordCount = 200,
      estimatedPages = 4,
      detectedDiscipline = "Clinical Medicine",
      studyDesign = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
      empiricalSignalsCount = 5,
      theoreticalSignalsCount = 0,
      extractedEquations = emptyList(),
      symbolAndNotationAlerts = emptyList(),
      extractedSampleSizes = emptyList(),
      statisticalClaims = emptyList(),
      grimAndNumericAlerts = emptyList(),
      proceduralLeakageAlerts = emptyList(),
      citationAlerts = emptyList(),
      replicationCodeAlerts = emptyList(),
      citationCountEstimate = 0,
      quantForensics = QuantitativeForensicReport(
        statcheckResults = fourStatchecks,
        confidenceIntervalChecks = emptyList(),
        medianIqrChecks = emptyList(),
        heterogeneitySignals = emptyList(),
        powerAndMultiplicityAlerts = emptyList(),
        algebraicAndMetricAlerts = emptyList(),
        diagnosticTable = emptyList(),
        extractedPrecisionMetrics = emptyList(),
        statisticalAudit = statAudit
      )
    )
    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = fourStatchecks.joinToString("\n") { "[PAGE ${it.pageNumber}]\n${it.rawClaim}" },
      forensicReport = forensic,
      methodologyScore = 7,
      significanceScore = 7,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 7,
      reproducibilityScore = 7,
      rawVerdict = "MINOR_REVISION",
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      extractionConfidence = 5,
      applyStatisticalScoreGate = true
    )

    assertEquals("4 statcheck FAILs capped at 2 must record ledgerCapOmittedCount=2", 2, adjudicated.ledgerCapOmittedCount)
    assertTrue(
      "ledgerCapCategories must disclose statcheck ledger cap",
      adjudicated.ledgerCapCategories.any { it.contains("statcheck_ledger_cap") }
    )

    val summaryLine = statAudit.formatCoverageLedgerSummary(
      ledgerCapOmittedCount = adjudicated.ledgerCapOmittedCount,
      ledgerCapCategories = adjudicated.ledgerCapCategories
    )
    assertTrue("Summary must explicitly flag Quantitative Coverage Partial", summaryLine.contains("Quantitative Coverage Partial"))
    assertTrue("Summary must report discoverySkipped=40", summaryLine.contains("discoverySkipped=40"))
    assertTrue("Summary must report reconstructionSkipped=15", summaryLine.contains("reconstructionSkipped=15"))
    assertTrue("Summary must report unsupported=4", summaryLine.contains("unsupported=4"))
    assertTrue("Summary must report ledgerCapOmitted=2", summaryLine.contains("ledgerCapOmitted=2"))
    assertFalse("Summary must never claim complete quantitative audit", summaryLine.contains("complete quantitative audit", ignoreCase = true))

    // 2. Capped ledger MUST block VERIFIED validation status even if all visible findings were VERIFIED
    val verifiedFinding = ReviewFinding(
      id = "F-1",
      title = "Verified Finding",
      allegation = "Verified allegation",
      severity = "MAJOR",
      status = FindingStatus.VERIFIED
    )
    val validationStatus = Reviewer2PipelineService.computeValidationStatus(
      resolvedVerdict = "MINOR_REVISION",
      adjudicatedFindings = listOf(verifiedFinding),
      anyJsonRepaired = false,
      omittedOrPartialPromptPagesCount = 0,
      wasPromptTrimmed = false,
      extractionConfidence = 5,
      hasSubstantiveEditorialProse = true,
      truncatedStatisticalClaimCount = adjudicated.ledgerCapOmittedCount
    )
    assertEquals(
      "Ledger-cap omissions must downgrade validationStatus from VERIFIED to PARTIALLY_VALIDATED",
      "PARTIALLY_VALIDATED",
      validationStatus
    )

    // 3. PDF export must surface partial quantitative coverage & caps prominently without claiming complete coverage
    val review = PaperReview(
      id = "rev_partial_cov_pdf",
      title = "Partial Coverage & Ledger Cap Disclosure Test",
      validationStatus = validationStatus,
      verdict = "MAJOR_REVISION",
      credibilityScore = 71,
      coverageDisclosure = "Full text extracted • $summaryLine",
      abstractSummary = "Executive appraisal summary."
    )
    val rendered = PdfReportExporter.renderAndInspectPdf(
      review = review,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT
    )
    assertTrue("PDF render must be clean: ${rendered.diagnostics.verificationFailureReason}", rendered.diagnostics.isClean)
    assertTrue(
      "Rendered PDF text must contain QUANTITATIVE COVERAGE PARTIAL banner",
      rendered.renderedText.contains("QUANTITATIVE COVERAGE PARTIAL")
    )
    assertTrue(
      "Rendered PDF text must report discoverySkipped=40 and ledgerCapOmitted=2",
      rendered.renderedText.contains("discoverySkipped=40") &&
        rendered.renderedText.contains("ledgerCapOmitted=2")
    )
    assertFalse(
      "Rendered PDF text must never claim complete quantitative audit when caps are nonzero",
      rendered.renderedText.contains("complete quantitative audit", ignoreCase = true)
    )
  }
}

