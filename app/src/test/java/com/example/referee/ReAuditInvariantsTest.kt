package com.example.referee

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.semantic.CalculatorAssumptionContract
import com.example.data.model.ReviewMode
import com.example.data.ai.referee.AbstentionGate
import com.example.data.ai.referee.AuditLogVerificationStatus
import com.example.data.ai.referee.AutonomousRefereeOrchestrator
import com.example.data.ai.referee.DocumentReadiness
import com.example.data.ai.referee.DocumentReadinessEvaluator
import com.example.data.ai.referee.DomainAppraisalAdapters
import com.example.data.ai.referee.DomainCriterionRating
import com.example.data.ai.referee.EntailmentStatus
import com.example.data.ai.referee.LocalReferenceCleanupHook
import com.example.data.ai.referee.QuantitativeAdjudicationContracts
import com.example.data.ai.referee.QuantitativeFindingStatus
import com.example.data.ai.referee.RefereeAdjudicationInput
import com.example.data.ai.referee.RefereeBenchmarkHarness
import com.example.data.ai.referee.RefereeDecision
import com.example.data.ai.referee.ReleaseReadinessEvaluator
import com.example.data.ai.referee.ReleaseValidationState
import com.example.data.ai.referee.SupplementStatus
import com.example.data.ai.referee.TamperEvidentAuditLog
import com.example.data.ai.referee.TypedPropositionEntailmentEngine
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.NumericalReconstructionResult
import com.example.data.ai.stats.StatisticalAuditReport
import com.example.data.ai.stats.StatisticalFindingType
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
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
 * End-to-end invariant tests verifying all 10 Paper Critic re-audit fixes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ReAuditInvariantsTest {

  @Test
  fun canonicalVerdictMapper_neverAllowsAcceptOrHighCredibilityOnNonVerifiedDecisions() {
    val domainReport = DomainAppraisalAdapters.appraise(
      "Methods: randomized trial. Results: HR 0.80 (95% CI 0.68-0.94).",
      StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL
    )

    assertEquals(
      "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
      Reviewer2PipelineService.mapRefereeDecisionToModeVerdict(
        decision = RefereeDecision.INSUFFICIENT_EVIDENCE,
        reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL,
        domainAppraisal = domainReport,
        preOrchestratorVerdict = "HIGH_CREDIBILITY",
        compositeScore = 95
      )
    )
    assertEquals(
      "PROVISIONAL_APPRAISAL",
      Reviewer2PipelineService.mapRefereeDecisionToModeVerdict(
        decision = RefereeDecision.PROVISIONAL_APPRAISAL,
        reviewMode = ReviewMode.JOURNAL_REFEREE,
        domainAppraisal = domainReport,
        preOrchestratorVerdict = "ACCEPT",
        compositeScore = 92
      )
    )
    val majorVerdict = Reviewer2PipelineService.mapRefereeDecisionToModeVerdict(
      decision = RefereeDecision.MAJOR_UNRESOLVED_CONCERNS,
      reviewMode = ReviewMode.JOURNAL_REFEREE,
      domainAppraisal = domainReport,
      preOrchestratorVerdict = "ACCEPT",
      compositeScore = 82
    )
    assertNotEquals("ACCEPT", majorVerdict)
    assertEquals("MAJOR_REVISION", majorVerdict)
  }

  @Test
  fun perFindingQuantitativeAdjudication_blocksConfirmedErrorWhenChecklistOrQuoteIncomplete() {
    val quote = "Treatment yielded HR 0.72 (95% CI 0.50-0.90, p = 0.004) in Table 1."
    val manuscript = "Methods\nMultivariable Cox model.\nResults\n$quote\nReferences\n1. Ref."
    val readiness = DocumentReadinessEvaluator.assess(
      manuscriptText = manuscript,
      coverageReport = DocumentCoverageReport(
        totalPages = 2,
        extractedPages = 2,
        totalChars = manuscript.length,
        totalWords = manuscript.split(Regex("\\s+")).size,
        extractionConfidence = 5
      ),
      attachedBitmapCount = 0
    )

    // Discrepancy with bootstrap qualifier in text & missing tolerance -> cannot escalate to CONFIRMED_ERROR
    val bootstrapQuote = "Treatment yielded OR 2.10 (95% CI 1.10-4.00) using bootstrap percentile intervals in Table 2."
    val bootstrapManuscript = "Methods\nStudy.\nResults\n$bootstrapQuote\nReferences\n1. Ref."
    val rec = NumericalReconstructionResult(
      checkId = "STAT-REC-1",
      calculatorName = "CI-Symmetry",
      reportedValue = "OR 2.10 (1.10-4.00)",
      recomputedValue = "OR 2.10 (1.09-4.05)",
      tolerance = "N/A",
      sourceEvidence = bootstrapQuote,
      pageNumber = 1,
      assumptions = listOf("Asymptotic normality"),
      status = QuantFindingStatus.FAIL,
      findingType = StatisticalFindingType.ARITHMETIC_ERROR,
      scientificConsequence = "Small interval discrepancy",
      detail = "Differs under normal approximation",
      assumptionContract = CalculatorAssumptionContract(
        calculatorScope = "CI-Symmetry",
        assumptionsSatisfied = emptyList(),
        assumptionsUnknown = listOf("Raw bootstrap draws"),
        alternativeValidInterpretations = listOf("Bootstrap percentile CI")
      )
    )
    val statReport = StatisticalAuditReport(
      subDesign = AuditSubDesign.OBSERVATIONAL_COHORT,
      claims = emptyList(),
      twoByTwoTables = emptyList(),
      metaStudyEffects = emptyList(),
      sampleFlowRecords = emptyList(),
      crossLinks = emptyList(),
      reconstructions = listOf(rec),
      domainFindings = emptyList(),
      unsupportedDisclosures = emptyList()
    )

    val (checklist, statuses) = Reviewer2PipelineService.evaluateQuantitativeFindingStatuses(
      manuscriptText = bootstrapManuscript,
      readiness = readiness,
      statAudit = statReport,
      extractionConfidence = 5,
      imagesSentCount = 0
    )

    assertFalse(checklist.allChannelsChecked)
    assertEquals(1, statuses.size)
    assertNotEquals(QuantitativeFindingStatus.CONFIRMED_ERROR, statuses.single())
    assertEquals(QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION, statuses.single())
    assertFalse(
      QuantitativeAdjudicationContracts.canGateScoreOrVerdict(
        status = statuses.single(),
        sourceAnchorsVerified = true,
        estimandCompatible = false
      )
    )
  }

  @Test
  fun abstentionAndReadinessWithMissingSupplement_neverProducesVerifiedDecision() {
    val base = RefereeBenchmarkHarness.defaultInternalFixtures().first().input
    val readinessNoSupp = base.readiness.copy(
      readiness = DocumentReadiness.READY_WITH_LIMITATIONS,
      supplementStatus = SupplementStatus.ABSENT
    )
    assertTrue(readinessNoSupp.hasMaterialLimitations)
    assertFalse(readinessNoSupp.allowsVerifiedVerdict)

    val out = AutonomousRefereeOrchestrator.adjudicate(
      base.copy(
        readiness = readinessNoSupp,
        deterministicFindings = emptyList(),
        llmFindings = emptyList(),
        quantitativeStatuses = emptyList()
      )
    )
    assertTrue(out.abstentionResult.shouldAbstainFromVerifiedVerdict)
    assertFalse("Absent supplement must block VERIFIED_* decisions", out.decision.isVerified)
    assertEquals(RefereeDecision.PROVISIONAL_APPRAISAL, out.decision)
  }

  @Test
  fun canonicalReadinessGate_requiresVisionDigestWhenBitmapsAttachedAndAllowsBoundScannedPages() {
    val scannedText = "PDF MANUSCRIPT (SCANNED / IMAGE-BASED) - Page images attached for vision inspection."
    val coverage = DocumentCoverageReport(
      totalPages = 3,
      extractedPages = 3,
      totalChars = scannedText.length,
      totalWords = 12,
      visionPagesAttached = 3,
      visionPageNumbers = listOf(1, 2, 3),
      extractionEngine = "PDFBox Vision-Only",
      extractionConfidence = 3
    )

    val unbound = Reviewer2PipelineService.evaluateDocumentReadinessGate(
      manuscriptText = scannedText,
      coverageReport = coverage,
      attachedBitmapCount = 3,
      useExternalTextOnlyProvider = false,
      pdfVisionDigestSha256 = ""
    )
    assertFalse("Attached bitmaps without vision digest must be rejected", unbound.isReady)

    val bound = Reviewer2PipelineService.evaluateDocumentReadinessGate(
      manuscriptText = scannedText,
      coverageReport = coverage,
      attachedBitmapCount = 3,
      useExternalTextOnlyProvider = false,
      pdfVisionDigestSha256 = "sha256-bound-vision-pages"
    )
    assertTrue("Bound vision pages on multimodal route should proceed as READY_WITH_LIMITATIONS", bound.isReady)

    val assessment = DocumentReadinessEvaluator.assess(
      manuscriptText = scannedText,
      coverageReport = coverage,
      attachedBitmapCount = 3,
      pdfVisionDigestSha256 = "sha256-bound-vision-pages"
    )
    assertEquals(DocumentReadiness.READY_WITH_LIMITATIONS, assessment.readiness)
    assertFalse(assessment.allowsVerifiedVerdict)
  }

  @Test
  fun typedPropositionEntailment_rejectsCrossClauseMergingAndParadigmTimePointMismatches() {
    // Clause 1 mentions Drug A in adults; Clause 2 reports HR 0.70 for Drug B in children -> must NOT merge across clauses
    val multiClauseQuote = "Drug A was evaluated in adult outpatients. Conversely, Drug B showed HR 0.70 (95% CI 0.55-0.89, p = 0.003) in pediatric inpatients at 6 months."
    val crossClauseAttempt = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = "Drug A showed HR 0.70 (95% CI 0.55-0.89, p = 0.003) in adults at 6 months.",
      matchedQuote = multiClauseQuote,
      surroundingWindow = multiClauseQuote,
      exactQuoteMatched = true
    )
    assertNotEquals(
      "Cross-clause borrowing across sentences must never be ENTAILED",
      EntailmentStatus.ENTAILED,
      crossClauseAttempt.status
    )

    // Bayesian CrI vs Frequentist CI mismatch
    val paradigmMismatch = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = "Drug A achieved OR 0.65 (95% CI 0.45-0.92, p = 0.01) for mortality in adults.",
      matchedQuote = "Drug A achieved OR 0.65 (95% Bayesian CrI 0.45-0.92) for mortality in adults.",
      surroundingWindow = "Drug A achieved OR 0.65 (95% Bayesian CrI 0.45-0.92) for mortality in adults.",
      exactQuoteMatched = true
    )
    assertEquals(EntailmentStatus.NOT_ENTAILED, paradigmMismatch.status)

    // Time-point mismatch (12 months vs 3 months)
    val timeMismatch = TypedPropositionEntailmentEngine.evaluateEntailment(
      claimText = "Drug A achieved HR 0.65 (95% CI 0.45-0.92, p = 0.01) for mortality in adults at 12 months.",
      matchedQuote = "Drug A achieved HR 0.65 (95% CI 0.45-0.92, p = 0.01) for mortality in adults at 3 months.",
      surroundingWindow = "Drug A achieved HR 0.65 (95% CI 0.45-0.92, p = 0.01) for mortality in adults at 3 months.",
      exactQuoteMatched = true
    )
    assertEquals(EntailmentStatus.NOT_ENTAILED, timeMismatch.status)
  }

  @Test
  fun domainAdapters_ignoreReferencesAndProposedFutureWorkWithoutAwardingLowConcern() {
    val trickyRct = """
      Introduction
      Prior work by Smith et al. (2020) used allocation concealment and double-blind masking.
      Methods
      Participants were randomized 1:1 to intervention or control. Allocation concealment was not performed. Blinding was not conducted (open-label). Future trials should consider intention-to-treat analysis and NCT registration.
      Results
      Primary mortality occurred in 10/50 vs 15/50 (OR 0.58, 95% CI 0.23-1.46, p = 0.25).
      References
      1. Double-blind intention-to-treat trial with allocation concealment and NCT12345678.
    """.trimIndent()

    val report = DomainAppraisalAdapters.appraise(trickyRct, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    val d1 = report.rubricCriteria.first { it.domainKey == "ROB2_D1" }
    val d2 = report.rubricCriteria.first { it.domainKey == "ROB2_D2" }
    val d5 = report.rubricCriteria.first { it.domainKey == "ROB2_D5" }

    assertEquals(DomainCriterionRating.HIGH_CONCERN, d1.rating)
    assertEquals(BidirectionalAdjudicator.MethodMentionStatus.NOT_PERFORMED, d1.mentionStatus)
    assertEquals(DomainCriterionRating.HIGH_CONCERN, d2.rating)
    assertNotEquals(DomainCriterionRating.LOW_CONCERN, d5.rating)
  }

  @Test
  fun auditLogVerification_distinguishesNotAvailableLocalChainOnlyCommittedAndTampered() {
    val base = RefereeBenchmarkHarness.defaultInternalFixtures().first().input
    val out = AutonomousRefereeOrchestrator.adjudicate(base)

    val emptyCheck = TamperEvidentAuditLog.verifyAuditLog("", "")
    assertEquals(AuditLogVerificationStatus.NOT_AVAILABLE, emptyCheck.status)
    assertFalse(emptyCheck.isChainIntact)

    val localOnly = TamperEvidentAuditLog.verifyAuditLog(
      auditLogJson = out.auditLogJson,
      commitment = "",
      expectedAnalysisId = out.manifest.analysisId,
      expectedManuscriptSha256 = out.manifest.manuscriptSha256
    )
    assertEquals(AuditLogVerificationStatus.LOCAL_CHAIN_ONLY, localOnly.status)
    assertTrue(localOnly.isChainIntact)
    assertFalse(localOnly.isCommittedAndVerified)

    val verified = TamperEvidentAuditLog.verifyAuditLog(
      auditLogJson = out.auditLogJson,
      commitment = out.auditCommitment,
      expectedAnalysisId = out.manifest.analysisId,
      expectedManuscriptSha256 = out.manifest.manuscriptSha256
    )
    assertEquals(AuditLogVerificationStatus.VERIFIED, verified.status)
    assertTrue(verified.isCommittedAndVerified)

    val tampered = TamperEvidentAuditLog.verifyAuditLog(
      auditLogJson = out.auditLogJson,
      commitment = "wrong-commitment-hash",
      expectedAnalysisId = out.manifest.analysisId,
      expectedManuscriptSha256 = out.manifest.manuscriptSha256
    )
    assertEquals(AuditLogVerificationStatus.TAMPERED, tampered.status)
    assertFalse(tampered.isChainIntact)
  }

  @Test
  fun localReferenceCleanupHook_andReleaseValidationState_reportHonestly() {
    val hook = LocalReferenceCleanupHook()
    assertFalse(hook.capability.supportsRemoteDeletion)
    hook.registerPayloadHandle("req-1", 1000L)
    assertEquals(1, hook.cleanupAll("test"))
    assertTrue(hook.activeHandles().isEmpty())

    val releaseReport = ReleaseReadinessEvaluator.evaluate(architectureDocPresent = true)
    assertFalse(releaseReport.externallyValidated)
    assertEquals(ReleaseValidationState.EXTERNAL_VALIDATION_PENDING, releaseReport.validationState)
    assertTrue(releaseReport.allGatesPassed)
  }
}
