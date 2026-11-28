package com.example.referee

import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.referee.DocumentReadiness
import com.example.data.ai.referee.DocumentReadinessEvaluator
import com.example.data.ai.referee.EntailmentStatus
import com.example.data.ai.referee.ScientificProposition
import com.example.data.ai.referee.SupplementStatus
import com.example.data.ai.referee.TypedEvidenceAnchor
import com.example.data.ai.referee.TypedPropositionEntailmentEngine
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.util.DocumentCoverageReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phases 1, 2, and 4 Foundation Tests (written prior to implementation):
 * - Canonical typed [ScientificProposition] & [TypedEvidenceAnchor] / [EvidenceAnchor] round-trip
 * - [DocumentReadiness] & [SupplementStatus] gating
 * - All 10 required scientific entailment regression cases rejecting lexical-overlap-only matches
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase1To4RefereeFoundationTest {

  @Test
  fun phase1_evidenceAnchor_persistsExtractionMethodAndChecksumThroughCodec() {
    val anchor = EvidenceAnchor(
      documentId = "doc-sha256-abc",
      pageNumber = 4,
      section = "Results",
      exactQuote = "Drug A reduced adult mortality by 5% at 12 months (HR 0.95, 95% CI 0.91-0.99, p = 0.02).",
      startOffset = 120,
      endOffset = 205,
      boundingBox = listOf(10f, 20f, 200f, 40f),
      extractionMethod = "PDFBox-Android (ToUnicode)",
      sourceChecksum = "deadbeef123456"
    )
    val finding = ReviewFinding(
      id = "Q-01",
      title = "Primary endpoint check",
      allegation = "Drug A reduced adult mortality by 5% at 12 months.",
      severity = "MINOR",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(anchor),
      propositionId = "prop-1"
    )

    val encoded = ReviewFindingCodec.encode(listOf(finding))
    val decoded = ReviewFindingCodec.decodeResult(encoded).getOrThrow().single()
    val decodedAnchor = decoded.supportingEvidence.single()

    assertEquals("prop-1", decoded.propositionId)
    assertEquals("doc-sha256-abc", decodedAnchor.documentId)
    assertEquals("PDFBox-Android (ToUnicode)", decodedAnchor.extractionMethod)
    assertEquals("deadbeef123456", decodedAnchor.sourceChecksum)
    assertEquals(120, decodedAnchor.startOffset)
    assertEquals(205, decodedAnchor.endOffset)
  }

  @Test
  fun phase1_scientificProposition_exposesAllCanonicalFieldsAndTracksUnresolved() {
    val prop: ScientificProposition = TypedPropositionEntailmentEngine.extractProposition(
      id = "P-01",
      text = "In hospitalized adults, Drug A vs placebo reduced mortality by 5% at 12 months (adjusted HR 0.82, 95% CI 0.71 to 0.95, p = 0.008, intention-to-treat analysis).",
      sourceAnchorIds = listOf("anchor-1")
    )

    assertEquals("P-01", prop.id)
    assertEquals("ADULTS", prop.population)
    assertTrue(prop.interventionOrExposure?.contains("drug a", ignoreCase = true) == true)
    assertTrue(prop.comparator?.contains("placebo", ignoreCase = true) == true)
    assertEquals("MORTALITY", prop.outcome)
    assertEquals("HR", prop.effectMeasure)
    assertEquals(0.82, prop.estimate ?: 0.0, 0.001)
    assertEquals(0.71, prop.lowerBound ?: 0.0, 0.001)
    assertEquals(0.95, prop.upperBound ?: 0.0, 0.001)
    assertEquals(95.0, prop.confidenceLevel ?: 0.0, 0.01)
    assertEquals(0.008, prop.pValue ?: 0.0, 0.0001)
    assertEquals("ADJUSTED", prop.adjustmentStatus)
    assertEquals("OVERALL_ITT", prop.analysisPopulation)
    assertEquals("DECREASE", prop.direction)
    assertEquals(listOf("anchor-1"), prop.sourceAnchorIds)
    assertTrue(prop.extractionConfidence in 1..5)
  }

  @Test
  fun phase1_typedEvidenceAnchor_convertsCleanlyToModelAnchor() {
    val typed = TypedEvidenceAnchor(
      documentId = "manuscript_sha",
      pageNumber = 2,
      section = "Methods",
      startOffset = 10,
      endOffset = 64,
      exactQuote = "Patients were randomized 1:1 to Drug A or placebo.",
      boundingBox = null,
      extractionMethod = "DirectText",
      sourceChecksum = "abc123"
    )
    val model = typed.toEvidenceAnchor()
    assertEquals("DirectText", model.extractionMethod)
    assertEquals("abc123", model.sourceChecksum)
    assertEquals(2, model.pageNumber)
  }

  @Test
  fun phase2_documentReadiness_detectsReadyWithAndWithoutLimitationsAndBlocksIncomplete() {
    val completePaper = """
      [PAGE 1]
      Randomized Trial of Drug A in Adults
      Abstract
      Background and objective.
      Methods
      Adult patients (N = 400) were randomized to Drug A vs placebo. Table 1 summarizes baseline traits.
      [PAGE 2]
      Results
      Figure 1 shows the Kaplan-Meier curve. Mortality was 5% vs 10% at 12 months (HR 0.50, 95% CI 0.30 to 0.84).
      Sensitivity models are referenced in Supplementary Appendix Table S1.
      References
      1. Smith et al. Lancet 2024.
    """.trimIndent()

    val completeCov = DocumentCoverageReport(
      totalPages = 2,
      extractedPages = 2,
      totalChars = completePaper.length,
      totalWords = 90,
      lowTextPages = emptyList(),
      omittedPages = emptyList(),
      visionPagesAttached = 2,
      visionPageNumbers = listOf(1, 2),
      extractionEngine = "PDFBox-Android (Full ToUnicode)",
      extractionConfidence = 5,
      warnings = emptyList()
    )

    val readyWithSuppNote = DocumentReadinessEvaluator.assess(
      manuscriptText = completePaper,
      coverageReport = completeCov,
      attachedBitmapCount = 2,
      pdfVisionDigestSha256 = "vision_digest_ok",
      supplementAttachedText = "Supplementary Appendix Table S1: Sensitivity model HR 0.51."
    )
    assertEquals(DocumentReadiness.READY, readyWithSuppNote.readiness)
    assertEquals(SupplementStatus.PRESENT, readyWithSuppNote.supplementStatus)
    assertTrue(readyWithSuppNote.allowsVerifiedVerdict)
    assertTrue(readyWithSuppNote.searchableTextExists)
    assertTrue(readyWithSuppNote.methodsPresent)
    assertTrue(readyWithSuppNote.resultsPresent)
    assertTrue(readyWithSuppNote.tablesAndFiguresDetectable)
    assertTrue(readyWithSuppNote.referencesSeparated)
    assertTrue(readyWithSuppNote.visionPagesBound)

    val missingSupp = DocumentReadinessEvaluator.assess(
      manuscriptText = completePaper,
      coverageReport = completeCov,
      attachedBitmapCount = 2,
      pdfVisionDigestSha256 = "vision_digest_ok",
      supplementAttachedText = ""
    )
    assertEquals(DocumentReadiness.READY_WITH_LIMITATIONS, missingSupp.readiness)
    assertEquals(SupplementStatus.ABSENT, missingSupp.supplementStatus)
    assertTrue(missingSupp.allowsVerifiedVerdict)

    // Missing Methods & Results -> INCOMPLETE, never verified referee verdict
    val missingMethodsResults = DocumentReadinessEvaluator.assess(
      manuscriptText = "[PAGE 1]\nShort narrative commentary discussing broad academic policy themes, institutional framing, and historical context without formal empirical protocols or reported outcomes.",
      coverageReport = completeCov.copy(totalPages = 1, extractedPages = 1),
      attachedBitmapCount = 0,
      pdfVisionDigestSha256 = ""
    )
    assertEquals(DocumentReadiness.INCOMPLETE, missingMethodsResults.readiness)
    assertFalse(missingMethodsResults.allowsVerifiedVerdict)
    assertTrue(missingMethodsResults.allowsPreliminaryAppraisalOnly)

    // Vision bitmaps attached without digest binding -> INCOMPLETE
    val unboundVision = DocumentReadinessEvaluator.assess(
      manuscriptText = completePaper,
      coverageReport = completeCov,
      attachedBitmapCount = 2,
      pdfVisionDigestSha256 = "",
      supplementAttachedText = "Supplementary Appendix Table S1: present."
    )
    assertEquals(DocumentReadiness.INCOMPLETE, unboundVision.readiness)
    assertFalse(unboundVision.visionPagesBound)
    assertFalse(unboundVision.allowsVerifiedVerdict)

    // Extraction failure on blank/corrupted document
    val failed = DocumentReadinessEvaluator.assess(
      manuscriptText = "",
      coverageReport = DocumentCoverageReport.unknown("No text layer"),
      attachedBitmapCount = 0,
      pdfVisionDigestSha256 = ""
    )
    assertEquals(DocumentReadiness.EXTRACTION_FAILURE, failed.readiness)
    assertFalse(failed.allowsVerifiedVerdict)

    // Unsupported format
    val unsupported = DocumentReadinessEvaluator.assess(
      manuscriptText = "",
      coverageReport = completeCov.copy(extractionEngine = "Unsupported"),
      attachedBitmapCount = 0,
      pdfVisionDigestSha256 = "",
      sourceFormat = "PPTX"
    )
    assertEquals(DocumentReadiness.UNSUPPORTED_FORMAT, unsupported.readiness)
    assertFalse(unsupported.allowsVerifiedVerdict)
  }

  @Test
  fun phase4_entailmentEngine_rejectsAll10CanonicalMismatchCases() {
    fun assertNotEntailed(claim: String, evidence: String, label: String) {
      val result = TypedPropositionEntailmentEngine.evaluateEntailment(
        claimText = claim,
        matchedQuote = evidence,
        surroundingWindow = evidence
      )
      assertNotEquals("Case '$label' must NEVER be ENTAILED (got ${result.status}: ${result.reason})", EntailmentStatus.ENTAILED, result.status)

      val serviceFinding = ReviewFinding(
        id = "T-01",
        title = "Primary claim check",
        allegation = claim,
        severity = "MAJOR",
        status = FindingStatus.VERIFIED
      )
      val legacyStatus = Reviewer2PipelineService.evaluateQuoteEntailment(serviceFinding, evidence, evidence)
      assertNotEquals("Legacy gate for '$label' must NEVER be ENTAILED (got $legacyStatus)", Reviewer2PipelineService.Companion.EntailmentStatus.ENTAILED, legacyStatus)
    }

    // 1. 5% vs 50%
    assertNotEntailed(
      "Drug A reduced adult mortality by 5% at 12 months.",
      "Drug A reduced adult mortality by 50% at 12 months.",
      "5% vs 50%"
    )
    // 2. adults vs children
    assertNotEntailed(
      "Drug A reduced adult mortality by 5% at 12 months.",
      "Drug A reduced child mortality by 5% at 12 months.",
      "adults vs children"
    )
    // 3. 6 months vs 12 months
    assertNotEntailed(
      "Drug A reduced adult mortality by 5% at 12 months.",
      "Drug A reduced adult mortality by 5% at 6 months.",
      "6 months vs 12 months"
    )
    // 4. adjusted vs unadjusted
    assertNotEntailed(
      "In the adjusted analysis, Drug A reduced mortality (adjusted HR 0.78).",
      "In the unadjusted analysis, Drug A reduced mortality (unadjusted HR 0.78).",
      "adjusted vs unadjusted"
    )
    // 5. subgroup vs total population
    assertNotEntailed(
      "In the subgroup analysis, Drug A reduced mortality by 12%.",
      "In the overall cohort analysis, Drug A reduced mortality by 12%.",
      "subgroup vs total population"
    )
    // 6. treatment vs comparator arm reversal
    assertNotEntailed(
      "Drug A vs placebo reduced mortality by 12% in adults.",
      "Placebo vs Drug A reduced mortality by 12% in adults.",
      "treatment vs comparator reversal"
    )
    // 7. reduced vs increased mortality
    val dirResult = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = "Drug A reduced adult mortality by 10% at 12 months.",
      matchedQuote = "Drug A increased adult mortality by 10% at 12 months.",
      surroundingWindow = "Drug A increased adult mortality by 10% at 12 months."
    )
    assertEquals(EntailmentStatus.CONTRADICTED, dirResult.status)

    // 8. "did not perform" vs "performed"
    val polResult = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = "The authors did not perform sensitivity analysis for missing outcome data.",
      matchedQuote = "The authors performed sensitivity analysis for missing outcome data in Table 3.",
      surroundingWindow = "The authors performed sensitivity analysis for missing outcome data in Table 3."
    )
    assertEquals(EntailmentStatus.CONTRADICTED, polResult.status)

    // 9. 90% CI vs 95% CI
    assertNotEntailed(
      "The hazard ratio was 0.80 (95% CI 0.70 to 0.92) in adults.",
      "The hazard ratio was 0.80 (90% CI 0.70 to 0.92) in adults.",
      "90% CI vs 95% CI"
    )

    // 10. Bayesian CrI vs frequentist CI
    assertNotEntailed(
      "The hazard ratio was 0.80 (95% CI 0.70 to 0.92) in adults.",
      "The Bayesian posterior hazard ratio was 0.80 (95% CrI 0.70 to 0.92) in adults.",
      "Bayesian CrI vs frequentist CI"
    )
  }

  @Test
  fun phase4_entailmentEngine_acceptsExactAlignedPropositionAndRejectsTokenOverlapWithoutExactQuote() {
    val alignedClaim = "In adults, Drug A vs placebo reduced mortality by 15% at 12 months (adjusted HR 0.85, 95% CI 0.74 to 0.97)."
    val alignedQuote = "Among adults, Drug A vs placebo reduced mortality by 15% at 12 months (adjusted HR 0.85, 95% CI 0.74 to 0.97)."

    val entailed = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = alignedClaim,
      matchedQuote = alignedQuote,
      surroundingWindow = alignedQuote,
      exactQuoteMatched = true
    )
    assertEquals(EntailmentStatus.ENTAILED, entailed.status)

    // Token overlap alone without exact quote verification NEVER entails
    val notEntailedNoExact = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = alignedClaim,
      matchedQuote = alignedQuote,
      surroundingWindow = alignedQuote,
      exactQuoteMatched = false
    )
    assertNotEquals(EntailmentStatus.ENTAILED, notEntailedNoExact.status)

    // Document ID mismatch blocks entailment
    val docMismatch = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = alignedClaim,
      matchedQuote = alignedQuote,
      surroundingWindow = alignedQuote,
      exactQuoteMatched = true,
      claimDocumentId = "doc-A",
      evidenceDocumentId = "doc-B"
    )
    assertEquals(EntailmentStatus.NOT_ENTAILED, docMismatch.status)
  }
}
