package com.example

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.epistemic.CheckApplicability
import com.example.data.ai.epistemic.EpistemicSafeguardEngine
import com.example.data.ai.epistemic.FieldApplicability
import com.example.data.ai.semantic.ClaimCompatibilityStatus
import com.example.data.ai.semantic.CompoundClaimKey
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.AnalysisHierarchy
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.NumericalReconstructionEngine
import com.example.data.ai.stats.StatisticalAuditOrchestrator
import com.example.data.ai.stats.StatisticalClaimParser
import com.example.data.ai.stats.StatisticalFindingType
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.util.DocumentCoverageReport
import com.example.util.DossierFormattingEngine
import com.example.util.PdfExportScope
import com.example.util.PdfReportExporter
import com.example.util.ReportBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase10PdfIdentityApplicabilityTest {

  private val psychiatricBedNeedReviewText = """
    [PAGE 1]
    Global estimates of psychiatric inpatient bed need: a systematic review and meta-analysis
    Department of Psychiatry and Psychotherapy, Charité – Universitätsmedizin Berlin, Campus Mitte
    Correspondence: author@charite.de | DOI: 10.1000/psych.2024.001

    Abstract
    Background: Patterns of inpatient mental health service capacity matter for national resource planning.
    Methods: We conducted a systematic review and random-effects meta-analysis of studies published from 2000 to 2023 estimating psychiatric bed need per 100,000 population across OECD and international health systems.
    Results: Across 46 publications contributing 134 estimates, the pooled acute psychiatric bed need estimate was 38.4 beds per 100,000 population (95% CI 31.2 to 45.6), with I² = 96.4%. A secondary prevalence ratio was PR = 2.21, 95% CI 0.00 to 4.50.
    Earlier work by Smith et al. (2015) studied N = 80 patients over 7 years of outpatient follow-up.

    [PAGE 2]
    Methods
    We searched PubMed, Embase, and PsycINFO from 2000 to 2023 for normative psychiatric bed need estimates per 100,000 population.
    We included 46 publications yielding 134 bed-need estimates across 28 countries. We applied random-effects meta-analysis (DerSimonian-Laird) to synthesize pooled capacity benchmarks.
    No clinical drug intervention or placebo comparator was evaluated because this is a descriptive normative capacity synthesis.

    [PAGE 3]
    Results
    Heterogeneity was high (I² = 96.4%). Subgroup prevalence ratio was PR = 2.21, 95% CI 0.00 to 4.50.
    Need versus utilization definitions varied across jurisdictions.

    References
    1. Jones A, et al. (2018). Randomized controlled trial of statin therapy versus placebo in N = 500 patients over 5 years. ATT = 0.24.
    2. Doe B, et al. (2019). Therapy Campus Mitte clinical cohort with intervention of cognitive therapy (N = 120).
  """.trimIndent()

  // ============================================================================
  // P0.1 — Internal bracket headers -> typed SubHeader (never raw [HEADER])
  // ============================================================================
  @Test
  fun internalHeaders_areNeverRenderedWithOuterBrackets() {
    val raw = """
      [STUDY IDENTITY CARD (Pre-Critique Grounding)]
      Body text for identity card.

      [CLAIM HIERARCHY & PRIMARY-ANALYSIS DECLARATION]
      Body text for claim hierarchy.

      [CLINICAL & PRACTICAL SIGNIFICANCE GATE]
      Body text for practical significance.

      [WHAT WOULD CHANGE THE CONCLUSION?]
      Body text for fragility.

      [POSSIBLE REASONS FOR THE RESULT (11-Mechanism Audit)]
      Body text for mechanisms.

      [EXPLICIT UNCERTAINTY BUDGET (Scientific vs. Extraction Separation)]
      Body text for uncertainty budget.

      [DESIGN-AWARE STATISTICAL AUDIT LEDGER — Meta-Analysis]
      Body text for design audit.

      [DOMAIN-SPECIFIC STATISTICAL AUDIT (Meta-Analysis)]
      Body text for domain audit.

      [UNSUPPORTED-TO-ASSESS INPUT DISCLOSURES]
      Body text for unsupported evidence.

      [PHASE 8 — SEMANTIC VALIDITY, CLASSIFIER CONFIDENCE & ABSTENTION REPORT]
      Body text for phase 8.

      [CUSTOM UNKNOWN SECTION HEADER]
      Body text for unknown header.

      [R-01] Preserved finding ID item in list.
    """.trimIndent()

    val blocks = DossierFormattingEngine.parseStructuredBlocks(raw)
    val subHeaders = blocks.filterIsInstance<ReportBlock.SubHeader>()
    assertTrue("Expected >= 11 typed SubHeaders, got ${subHeaders.size}", subHeaders.size >= 11)

    subHeaders.forEach { sh ->
      assertFalse("SubHeader title must not start with '[': ${sh.title}", sh.title.startsWith("["))
      assertFalse("SubHeader title must not end with ']': ${sh.title}", sh.title.endsWith("]"))
      assertFalse("SubHeader tag must not contain '[': ${sh.tag}", sh.tag.contains("["))
    }

    assertTrue(
      "PHASE 8 header must carry PHASE 8 tag",
      subHeaders.any { it.tag == "PHASE 8" && it.title.contains("Semantic Validity", ignoreCase = true) }
    )
    assertTrue(
      "Unknown bracketed header must convert to REPORT SubHeader",
      subHeaders.any { it.tag == "REPORT" && it.title.contains("Custom Unknown Section Header", ignoreCase = true) }
    )

    val paragraphs = blocks.filterIsInstance<ReportBlock.Paragraph>()
    paragraphs.forEach { p ->
      assertFalse("Paragraph must not contain raw [STUDY IDENTITY CARD: ${p.text}", p.text.contains("[STUDY IDENTITY CARD"))
      assertFalse("Paragraph must not contain raw [PHASE 8: ${p.text}", p.text.contains("[PHASE 8"))
      assertFalse("Paragraph must not contain raw [WHAT WOULD CHANGE: ${p.text}", p.text.contains("[WHAT WOULD CHANGE"))
      assertFalse("Paragraph must not contain raw [CUSTOM UNKNOWN: ${p.text}", p.text.contains("[CUSTOM UNKNOWN"))
    }

    val bullets = blocks.filterIsInstance<ReportBlock.Bullet>()
    assertTrue("Finding ID [R-01] must remain preserved as structured bullet marker", bullets.any { it.marker == "[R-01]" })
  }

  // ============================================================================
  // P0.2 — Table parsing immune to embedded line breaks and pipe characters
  // ============================================================================
  @Test
  fun auditTable_wrappedCell_remainsSingleTableRow() {
    val markdownWithWrappedRow = """
      | Check Domain | Reported Evidence | Status | Exact Mathematical Finding |
      |---|---|---|---|
      | CI Geometry (P3) | PR = 2.21, 95% CI 0.00 to 4.50 | WARN | Imprecision / high variance
      continued on second line without leading pipe
      and third wrapped line |
    """.trimIndent()

    val blocks = DossierFormattingEngine.parseStructuredBlocks(markdownWithWrappedRow)
    val tables = blocks.filterIsInstance<ReportBlock.AuditTable>()
    val paragraphs = blocks.filterIsInstance<ReportBlock.Paragraph>()

    assertEquals("Must produce exactly 1 AuditTable", 1, tables.size)
    assertEquals("Wrapped continuation lines must not leak as Paragraphs", 0, paragraphs.size)
    assertEquals("Must remain a single table row", 1, tables.first().rows.size)
    assertEquals("Row must have exact same column count as header", 4, tables.first().rows.first().size)
    assertTrue(
      "Wrapped cell content must be preserved in last column",
      tables.first().rows.first()[3].contains("continued on second line") &&
        tables.first().rows.first()[3].contains("third wrapped line")
    )
  }

  @Test
  fun auditTable_cellContainingPipe_doesNotCreateExtraColumn() {
    val sanitized = DossierFormattingEngine.sanitizeTableCell("Alpha | Beta | Gamma")
    assertFalse("Pipe must be replaced before serialization", sanitized.contains("|"))
    assertEquals("Alpha / Beta / Gamma", sanitized)

    val row = "| Domain | $sanitized | WARN | Detail |"
    val md = "| Col1 | Col2 | Col3 | Col4 |\n|---|---|---|---|\n$row"
    val table = DossierFormattingEngine.parseStructuredBlocks(md).filterIsInstance<ReportBlock.AuditTable>().single()
    assertEquals(4, table.headers.size)
    assertEquals(4, table.rows.single().size)
    assertEquals("Alpha / Beta / Gamma", table.rows.single()[1])
  }

  @Test
  fun auditTable_cellContainingNewline_doesNotCreateParagraph() {
    val sanitized = DossierFormattingEngine.sanitizeTableCell("Line one\nLine two\r\nLine three")
    assertFalse("Sanitized cell must contain no newlines", sanitized.contains('\n') || sanitized.contains('\r'))
    val md = "| Col1 | Col2 |\n|---|---|\n| Key | $sanitized |"
    val blocks = DossierFormattingEngine.parseStructuredBlocks(md)
    assertEquals(1, blocks.filterIsInstance<ReportBlock.AuditTable>().size)
    assertEquals(0, blocks.filterIsInstance<ReportBlock.Paragraph>().size)
  }

  // ============================================================================
  // P1.1 & P1.2 — Design-specific identity card, venue/ref exclusion, study counts
  // ============================================================================
  @Test
  fun metaAnalysis_doesNotInferTreatmentEstimand() {
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = psychiatricBedNeedReviewText,
      parentArchetype = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS
    )
    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = psychiatricBedNeedReviewText,
      statAudit = statAudit,
      coverageReport = DocumentCoverageReport(3, 3, psychiatricBedNeedReviewText.length, 320, extractionConfidence = 5),
      extractionConfidence = 5
    )

    val estimandVal = epistemic.studyIdentityCard.primaryEstimand.value
    assertFalse("Meta-analysis must NEVER infer ATT from 'pattern'/'matter': $estimandVal", estimandVal.contains("ATT"))
    assertFalse("Meta-analysis must NEVER infer ATE: $estimandVal", estimandVal.contains("Average Treatment Effect"))
    assertNotNull("Systematic review/meta-analysis must populate ReviewIdentityCard", epistemic.studyIdentityCard.reviewIdentityCard)
  }

  @Test
  fun venueText_cannotBecomeExposure() {
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = psychiatricBedNeedReviewText,
      parentArchetype = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS
    )
    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = psychiatricBedNeedReviewText,
      statAudit = statAudit,
      coverageReport = DocumentCoverageReport(3, 3, psychiatricBedNeedReviewText.length, 320, extractionConfidence = 5),
      extractionConfidence = 5
    )

    val exposure = epistemic.studyIdentityCard.exposureOrIntervention
    assertFalse("Affiliation venue text must NEVER become exposure: ${exposure.value}", exposure.value.contains("Campus Mitte", ignoreCase = true))
    assertFalse("Affiliation Psychotherapy must NEVER become exposure: ${exposure.value}", exposure.value.contains("Psychotherapy", ignoreCase = true))
    assertEquals(FieldApplicability.NOT_APPLICABLE, exposure.applicability)
    assertEquals(FieldApplicability.NOT_APPLICABLE, epistemic.studyIdentityCard.comparator.applicability)
  }

  @Test
  fun identityExtraction_excludesReferencesAndCitations() {
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = psychiatricBedNeedReviewText,
      parentArchetype = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS
    )
    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = psychiatricBedNeedReviewText,
      statAudit = statAudit,
      coverageReport = DocumentCoverageReport(3, 3, psychiatricBedNeedReviewText.length, 320, extractionConfidence = 5),
      extractionConfidence = 5
    )

    val card = epistemic.studyIdentityCard
    assertFalse("Must exclude References statin therapy: ${card.exposureOrIntervention.value}", card.exposureOrIntervention.value.contains("statin", ignoreCase = true))
    assertFalse("Must exclude References placebo: ${card.comparator.value}", card.comparator.value.contains("placebo", ignoreCase = true))
    assertFalse("Must exclude cited 7 years as time horizon: ${card.timeHorizon.value}", card.timeHorizon.value.contains("7 years", ignoreCase = true))
    assertTrue("Must prefer search window 2000 to 2023: ${card.timeHorizon.value}", card.timeHorizon.value.contains("2000") && card.timeHorizon.value.contains("2023"))
  }

  @Test
  fun identityCard_usesPublicationAndEstimateCounts_notFirstNMatch() {
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = psychiatricBedNeedReviewText,
      parentArchetype = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS
    )
    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = psychiatricBedNeedReviewText,
      statAudit = statAudit,
      coverageReport = DocumentCoverageReport(3, 3, psychiatricBedNeedReviewText.length, 320, extractionConfidence = 5),
      extractionConfidence = 5
    )

    val reviewCard = epistemic.studyIdentityCard.reviewIdentityCard
    assertNotNull(reviewCard)
    assertEquals(46, reviewCard!!.includedStudyCount)
    assertEquals(134, reviewCard.includedEstimateCount)
    val denomVal = epistemic.studyIdentityCard.sampleSizeAndDenominator.value
    assertTrue("Sample size must report 46 publications/studies: $denomVal", denomVal.contains("46"))
    assertTrue("Sample size must report 134 estimates: $denomVal", denomVal.contains("134"))
    assertFalse("Sample size must NOT adopt cited N=80: $denomVal", denomVal.contains("80"))
  }

  // ============================================================================
  // P1 Applicability — Clinical MID / ARR / NNT marked NOT_APPLICABLE for normative meta-analysis
  // ============================================================================
  @Test
  fun metaAnalysis_marksClinicalMIDNotApplicable() {
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = psychiatricBedNeedReviewText,
      parentArchetype = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS
    )
    val epistemic = EpistemicSafeguardEngine.evaluate(
      text = psychiatricBedNeedReviewText,
      statAudit = statAudit,
      coverageReport = DocumentCoverageReport(3, 3, psychiatricBedNeedReviewText.length, 320, extractionConfidence = 5),
      extractionConfidence = 5
    )

    val gate = epistemic.practicalSignificanceGate
    assertTrue(gate.isNormativeOrServiceCapacityReview)
    assertEquals(CheckApplicability.NOT_APPLICABLE, gate.midApplicability)
    assertEquals(CheckApplicability.NOT_APPLICABLE, gate.baselineRiskApplicability)
    assertEquals(CheckApplicability.NOT_APPLICABLE, gate.arrNntApplicability)
    assertEquals(CheckApplicability.NOT_APPLICABLE, gate.clinicalBenefitRiskApplicability)
    assertEquals(CheckApplicability.APPLICABLE, gate.predictionIntervalApplicability)
    assertEquals(CheckApplicability.APPLICABLE, gate.unitHarmonizationApplicability)
    assertEquals(CheckApplicability.APPLICABLE, gate.geographicTransportabilityApplicability)
    assertEquals(CheckApplicability.APPLICABLE, gate.needVsUtilizationApplicability)

    val md = epistemic.toAuditMarkdown()
    assertTrue("Markdown must explicitly display 'Not applicable to this review's estimand'", md.contains("Not applicable to this review's estimand"))
    assertFalse("MID Reporting must not say 'MID Reporting: Not reported'", md.contains("MID Reporting: Not reported"))
  }

  // ============================================================================
  // P1.5 — Wide / zero-lower-bound CI is IMPRECISION_OR_HIGH_VARIANCE (WARN), NOT ARITHMETIC_ERROR
  // ============================================================================
  @Test
  fun wideValidCI_isWarnNotVerified() {
    val text = "[PAGE 3]\nResults\nPooled prevalence ratio was PR = 2.21, 95% CI 0.00 to 4.50 across regions."
    val ledger = StatisticalClaimParser.parse(text)
    val reconstructions = NumericalReconstructionEngine.runAllReconstructions(ledger, text)

    val prCheck = reconstructions.firstOrNull { it.calculatorName.contains("PR", ignoreCase = true) }
    assertNotNull("PR = 2.21, 95% CI 0.00 to 4.50 must be parsed and checked", prCheck)
    assertEquals(QuantFindingStatus.WARN, prCheck!!.status)
    assertEquals(StatisticalFindingType.IMPRECISION_OR_HIGH_VARIANCE, prCheck.findingType)
    assertFalse("Wide valid CI must not be flagged as confirmed arithmetic error", prCheck.findingType.isConfirmedError)
    assertTrue("Must recommend prediction intervals / leave-one-out diagnostics", prCheck.detail.contains("prediction intervals", ignoreCase = true))
  }

  @Test
  fun confidenceIntervalWithZeroLowerBound_isNotAutomaticallyFailure() {
    val text = "[PAGE 3]\nResults\nSubgroup PR = 2.21, 95% CI 0.00 to 4.50."
    val coverage = DocumentCoverageReport(3, 3, text.length, 12, extractionConfidence = 5)
    val forensic = QuantitativeForensicAnalyzer.analyze(
      text = text,
      studyDesign = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS,
      sampleSizes = listOf(46),
      coverageReport = coverage,
      extractionConfidence = 5
    )

    val prCi = forensic.confidenceIntervalChecks.firstOrNull { it.metricLabel.equals("PR", ignoreCase = true) }
    assertNotNull("Forensic CI check must capture PR = 2.21, 95% CI 0.00 to 4.50", prCi)
    assertEquals(QuantFindingStatus.WARN, prCi!!.status)
    assertTrue(forensic.confidenceIntervalChecks.none { it.metricLabel.equals("PR", ignoreCase = true) && it.status == QuantFindingStatus.FAIL })
  }

  @Test
  fun pointEstimateOutsideConfidenceInterval_isArithmeticError() {
    val text = "[PAGE 4]\nResults\nAdjusted OR = 9.0 (95% CI 1.26 to 2.35) was reported in the narrative."
    val ledger = StatisticalClaimParser.parse(text)
    val reconstructions = NumericalReconstructionEngine.runAllReconstructions(ledger, text)

    val orCheck = reconstructions.firstOrNull { it.calculatorName.contains("OR", ignoreCase = true) }
    assertNotNull(orCheck)
    assertEquals(QuantFindingStatus.FAIL, orCheck!!.status)
    assertEquals(StatisticalFindingType.ARITHMETIC_ERROR, orCheck.findingType)
  }

  // ============================================================================
  // P1.3, P1.4 & Abstention-Aware / Severity-Aligned Verdicts
  // ============================================================================
  @Test
  fun allConfidenceValues_areConsistent() {
    val coverage = DocumentCoverageReport(
      totalPages = 14,
      extractedPages = 14,
      totalChars = 28000,
      totalWords = 4500,
      extractionConfidence = 5
    )
    val statAudit = StatisticalAuditOrchestrator.audit(
      text = psychiatricBedNeedReviewText,
      parentArchetype = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS
    )
    val summary = Reviewer2PipelineService.computeAuthoritativeConfidenceSummary(
      manuscriptText = psychiatricBedNeedReviewText,
      coverageReport = coverage,
      statisticalAudit = statAudit,
      scientificConfidence = 4
    )
    val disclosure = coverage.toDisclosureLine(
      overrideExtractionConfidence = summary.extractionConfidence,
      coverageSummary = summary.coverageSummary
    )

    assertTrue(
      "Disclosure must reflect single authoritative Extraction Confidence (${summary.extractionConfidence}/5): $disclosure",
      disclosure.contains("Extraction Confidence: ${summary.extractionConfidence}/5")
    )
    assertTrue("Disclosure must include separated Page coverage", disclosure.contains("Page coverage: 14/14"))
    assertTrue("Disclosure must include separated Text extraction", disclosure.contains("Text extraction:"))
    assertTrue("Disclosure must include separated Tables/figures parseability", disclosure.contains("Tables/figures parseability:"))
    assertTrue("Disclosure must include separated Quantitative reconstruction", disclosure.contains("Quantitative reconstruction:"))
  }

  @Test
  fun abstentionProducesProvisionalOrInsufficientVerdict() {
    val provisional = Reviewer2PipelineService.resolveVerdictForMode(
      rawVerdict = "ACCEPT",
      compositeScore = 82,
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL,
      classifierAbstained = true,
      identityUncertainCount = 2
    )
    assertEquals("PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN", provisional)

    val insufficient = Reviewer2PipelineService.resolveVerdictForMode(
      rawVerdict = "ACCEPT",
      compositeScore = 82,
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL,
      classifierAbstained = true,
      identityUncertainCount = 4
    )
    assertEquals("INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", insufficient)

    val majorEscalation = Reviewer2PipelineService.resolveVerdictForMode(
      rawVerdict = "MINOR_REVISION",
      compositeScore = 78,
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      verifiedMajorCount = 2
    )
    assertEquals("MAJOR_REVISION", majorEscalation)

    val caveatsMinor = Reviewer2PipelineService.resolveVerdictForMode(
      rawVerdict = "MINOR_REVISION",
      compositeScore = 78,
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      partiallySupportedMajorCount = 2
    )
    assertEquals("MINOR_REVISION_WITH_CAVEATS", caveatsMinor)
  }

  @Test
  fun unknownCompoundKeys_areNotCompatible() {
    val keyA = CompoundClaimKey(
      population = "unspecified",
      outcome = "unspecified",
      timePoint = "unspecified",
      estimator = "OR",
      subgroup = "unspecified",
      adjustmentStatus = AdjustmentStatus.UNSPECIFIED
    )
    val keyB = keyA.copy()
    assertEquals(
      ClaimCompatibilityStatus.AMBIGUOUS,
      keyA.evaluateCompatibility(keyB)
    )
    assertFalse("Unspecified keys must never be treated as strictly compatible", keyA.isCompatibleForComparison(keyB))
  }

  // ============================================================================
  // P0.3, P2.1, P2.2 — End-to-End PDF Export, Raw-Markup Gate, Confidence Sync & Appendix A4
  // ============================================================================
  @Test
  fun rawMarkdownRows_areNeverVisibleInPdf_andMetadataContainsNoInternalEnums() {
    val coverage = DocumentCoverageReport(
      totalPages = 3,
      extractedPages = 3,
      totalChars = psychiatricBedNeedReviewText.length,
      totalWords = 290,
      extractionConfidence = 5
    )
    val forensic = QuantitativeForensicAnalyzer.analyze(
      text = psychiatricBedNeedReviewText,
      studyDesign = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS,
      sampleSizes = listOf(46),
      coverageReport = coverage,
      extractionConfidence = 4
    )
    val findings = listOf(
      ReviewFinding(
        id = "R-01",
        title = "High between-study heterogeneity without prediction interval",
        allegation = "Pooled bed-need estimates exhibit I² = 96.4% without a 95% prediction interval.",
        severity = "MAJOR",
        status = FindingStatus.PARTIALLY_SUPPORTED,
        supportingEvidence = listOf(
          EvidenceAnchor(
            pageNumber = 1,
            section = "Results",
            exactQuote = "pooled acute psychiatric bed need estimate was 38.4 beds per 100,000 population (95% CI 31.2 to 45.6), with I² = 96.4%",
            startOffset = 412,
            endOffset = 535
          )
        ),
        methodologicalJustification = "Under extreme heterogeneity, a confidence interval for the mean conceals wide cross-country dispersion.",
        recommendedCorrection = "Report 95% prediction intervals and sensitivity analyses stratified by health-system definition.",
        verificationNotes = "Checked Methods and Results; no prediction interval found.",
        flawTier = "MATERIALLY_WEAKENS_CONFIDENCE",
        reportingStatus = "REPORTED_BUT_INADEQUATE",
        overturnEvidence = "Supplementary table reporting 95% prediction intervals across income strata.",
        provenanceSummary = "Parser=stats-parser-v2.0 | Rules=epistemic-rules-v1.0 | Coverage=3/3 pages"
      )
    )

    val review = PaperReview(
      id = "phase10-e2e",
      title = "Global estimates of psychiatric inpatient bed need: a systematic review and meta-analysis",
      authors = "Mundt et al.",
      publicationYear = "2024",
      venueOrCitation = "Lancet Psychiatry",
      credibilityScore = 76L,
      verdict = "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN",
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL.name,
      validationStatus = "PROVISIONAL",
      // Intentionally pass stale 5/5 in coverageDisclosure while extractionConfidence = 4 to prove PDF exporter reconciles them
      coverageDisclosure = "3/3 pages extracted • 290 words • Extraction Confidence: 5/5",
      extractionConfidence = 4,
      refereeConfidence = 4,
      methodologyScore = 7,
      significanceScore = 8,
      clarityScore = 8,
      originalityScore = 8,
      mathRigorScore = 7,
      reproducibilityScore = 7,
      abstractSummary = "Normative systematic review of psychiatric inpatient bed need across 46 publications.",
      researchQuestion = "What is the global pooled estimate of psychiatric inpatient bed need per 100,000 population?",
      keyMethodology = "Random-effects meta-analysis across 46 publications (134 estimates, 2000–2023).",
      coreContributions = "Synthesizes cross-national psychiatric inpatient bed-need benchmarks.",
      mathAuditReport = forensic.toDeterministicAuditSummary(),
      blueTeamVerification = "[STAGE 02c — BLUE TEAM DEFENSE]\nAuthors transparently acknowledge cross-national service variation.",
      codeAuditReport = "[CLINICAL & POLICY APPLICABILITY LENS]\nEvaluate need-versus-utilization definitions before policy adoption.",
      editorNote = "[STAGE 08a–08b — APPRAISAL DECISION MEMO]\nSubstantive limitations requiring clarification around heterogeneity and prediction intervals.",
      structuredFindingsJson = ReviewFindingCodec.encode(findings)
    )

    // 1. Default Scientific Appraisal Report
    val mainResult = PdfReportExporter.renderAndInspectPdf(
      review = review,
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      precomputedQuantBlocks = forensic.toDeterministicAuditBlocks()
    )
    assertTrue("Rendered PDF must be non-empty", mainResult.pdfBytes.size > 1500)
    assertEquals("Must have zero raw bracket headers in PDF text", 0, mainResult.diagnostics.rawHeaderCount)
    assertEquals("Must have zero raw Markdown pipe rows in PDF text", 0, mainResult.diagnostics.rawMarkdownRowCount)
    assertEquals("Must have zero raw internal enum names in PDF text", 0, mainResult.diagnostics.internalEnumCount)
    assertTrue("PdfRenderDiagnostics must report clean", mainResult.diagnostics.isClean)

    val text = mainResult.renderedText
    assertFalse("Must not render raw [STUDY IDENTITY CARD", text.contains("[STUDY IDENTITY CARD"))
    assertFalse("Must not render raw [PHASE 8", text.contains("[PHASE 8"))
    assertFalse("Must not render raw [WHAT WOULD CHANGE", text.contains("[WHAT WOULD CHANGE"))
    assertFalse("Must not render raw REPORTED_BUT_INADEQUATE", text.contains("REPORTED_BUT_INADEQUATE"))
    assertFalse("Must not render raw UNSUPPORTED_TO_ASSESS", text.contains("UNSUPPORTED_TO_ASSESS"))
    assertFalse("Must not render stale Extraction Confidence: 5/5 when rubric is 4/5", text.contains("Extraction Confidence: 5/5"))
    assertTrue("Must consistently render Extraction Confidence: 4/5", text.contains("Extraction Confidence: 4/5"))
    assertTrue("Must consistently render EXTRACTION CONF: 4/5", text.contains("EXTRACTION CONF: 4/5"))
    assertTrue("Must consistently render EXT 4/5", text.contains("EXT 4/5"))
    assertTrue("Must render PROVISIONAL badge on provisional verdict", text.contains("PROVISIONAL"))
    assertFalse("Main report finding card must omit raw parser/rule provenance clutter", text.contains("epistemic-rules-v1.0"))
    assertFalse("Main report must not contain spurious ATT estimand", text.contains("Average Treatment Effect on the Treated"))

    // 2. Full Technical Appendix (includes Technical Provenance Appendix A4)
    val appendixResult = PdfReportExporter.renderAndInspectPdf(
      review = review,
      scope = PdfExportScope.FULL_TECHNICAL_APPENDIX,
      precomputedQuantBlocks = forensic.toDeterministicAuditBlocks()
    )
    assertEquals(0, appendixResult.diagnostics.rawHeaderCount)
    assertEquals(0, appendixResult.diagnostics.rawMarkdownRowCount)
    assertEquals(0, appendixResult.diagnostics.internalEnumCount)
    assertTrue(
      "Full Technical Appendix must contain Technical Provenance Appendix A4",
      appendixResult.renderedText.contains("epistemic-rules-v1.0") &&
        appendixResult.renderedText.contains("P1 [412–535]")
    )
  }

  // ============================================================================
  // P2.3 — Severity & Editorial Decision Alignment (Journal Referee & Post-Publication)
  // ============================================================================
  @Test
  fun editorialDecisionAlignment_enforcesFatalMajorRuleAndPostPublicationLanguage() {
    val fatalEscalation = Reviewer2PipelineService.resolveVerdictForMode(
      rawVerdict = "MINOR_REVISION",
      compositeScore = 82,
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      verifiedFatalCount = 1
    )
    assertEquals("MAJOR_REVISION", fatalEscalation)

    val fourMajorFindings = (1..4).map { idx ->
      ReviewFinding(
        id = "R-0$idx",
        title = "Major methodological finding $idx",
        allegation = "Substantive limitation $idx.",
        severity = "MAJOR",
        status = FindingStatus.VERIFIED
      )
    }

    val rawMinorNote = "Overall synthesis is useful. Minor revisions are required before publication."
    val postPubAligned = Reviewer2PipelineService.alignEditorNoteWithVerdictAndMode(
      rawEditorNote = rawMinorNote,
      findings = fourMajorFindings,
      resolvedVerdict = "REQUIRES_CORRECTION",
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL
    )
    assertFalse("Post-publication appraisal must not say 'Minor revisions are required': $postPubAligned", postPubAligned.contains("Minor revisions are required", ignoreCase = true))
    assertTrue("Post-publication appraisal must use 'Substantive limitations requiring clarification': $postPubAligned", postPubAligned.contains("Substantive limitations requiring clarification", ignoreCase = true))

    val refereeAligned = Reviewer2PipelineService.alignEditorNoteWithVerdictAndMode(
      rawEditorNote = rawMinorNote,
      findings = fourMajorFindings,
      resolvedVerdict = "MAJOR_REVISION",
      reviewMode = ReviewMode.JOURNAL_REFEREE
    )
    assertFalse("Journal referee note with 4 verified major findings must not say 'Minor revisions are required': $refereeAligned", refereeAligned.contains("Minor revisions are required", ignoreCase = true))
    assertTrue("Journal referee note with 4 verified major findings must say 'Major revisions are required': $refereeAligned", refereeAligned.contains("Major revisions are required", ignoreCase = true))

    val partiallySupportedMajors = (1..2).map { idx ->
      ReviewFinding(
        id = "P-0$idx",
        title = "Partially supported major finding $idx",
        allegation = "Limitation $idx.",
        severity = "MAJOR",
        status = FindingStatus.PARTIALLY_SUPPORTED
      )
    }
    val caveatsAligned = Reviewer2PipelineService.alignEditorNoteWithVerdictAndMode(
      rawEditorNote = rawMinorNote,
      findings = partiallySupportedMajors,
      resolvedVerdict = "MINOR_REVISION_WITH_CAVEATS",
      reviewMode = ReviewMode.JOURNAL_REFEREE
    )
    assertTrue(
      "Partially supported major findings should align to minor revisions with caveats: $caveatsAligned",
      caveatsAligned.contains("Minor revisions with caveats", ignoreCase = true)
    )
  }
}
