package com.example.referee

import com.example.data.ai.referee.AbstentionGate
import com.example.data.ai.referee.AbstentionTrigger
import com.example.data.ai.referee.AnalysisManifest
import com.example.data.ai.referee.AppraisalDomain
import com.example.data.ai.referee.AutonomousRefereeOrchestrator
import com.example.data.ai.referee.DiscrepancyCrossCheckChecklist
import com.example.data.ai.referee.DocumentReadiness
import com.example.data.ai.referee.DocumentReadinessAssessment
import com.example.data.ai.referee.DomainAppraisalReport
import com.example.data.ai.referee.DomainCriterionEvaluation
import com.example.data.ai.referee.DomainCriterionRating
import com.example.data.ai.referee.InMemoryRemotePayloadCleanupHook
import com.example.data.ai.referee.InterPassDisagreement
import com.example.data.ai.referee.PassComparisonInput
import com.example.data.ai.referee.PipelineStageId
import com.example.data.ai.referee.QuantitativeFindingStatus
import com.example.data.ai.referee.RefereeAdjudicationInput
import com.example.data.ai.referee.RefereeDecision
import com.example.data.ai.referee.SupplementStatus
import com.example.data.ai.referee.TamperEvidentAuditLog
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phases 3, 7, 8, and 9 Tests (written prior to orchestrator/manifest implementation):
 * - All 9 explicit tracked pipeline stages with hashes, versions, assumptions, failures
 * - Deterministic finding immutability across LLM stages & explicit InterPassDisagreement emission
 * - All 8 NEVER VERIFIED enforcement gates in RefereeDecision
 * - All 10 AbstentionGate triggers returning {whatIsMissing, whyItMatters, blockedConclusion, nextStepsForUser}
 * - SHA-256 hash-chained TamperEvidentAuditLog, AnalysisManifest provenance, and RemotePayloadCleanupHook
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase3To9PipelineAbstentionProvenanceTest {

  private fun readyDoc(): DocumentReadinessAssessment = DocumentReadinessAssessment(
    readiness = DocumentReadiness.READY,
    searchableTextExists = true,
    pageCountPlausible = true,
    methodsPresent = true,
    resultsPresent = true,
    tablesAndFiguresDetectable = true,
    referencesSeparated = true,
    extractionQualityAcceptable = true,
    visionPagesBound = true,
    supplementStatus = SupplementStatus.PRESENT,
    reasons = emptyList(),
    requestedActions = emptyList()
  )

  private fun rctDomainReport(): DomainAppraisalReport = DomainAppraisalReport(
    domain = AppraisalDomain.RANDOMIZED_TRIAL,
    instrumentName = "Cochrane RoB 2 / CONSORT",
    classificationConfidence = 0.92,
    restrictsVerdictDueToUncertainDesign = false,
    rubricCriteria = listOf(
      DomainCriterionEvaluation(
        domainKey = "D1_RANDOMIZATION",
        label = "Randomization & Allocation Concealment",
        rating = DomainCriterionRating.LOW_CONCERN,
        detail = "Permuted-block randomization and concealed allocation described."
      )
    ),
    domainSummaryNote = "RCT appraised under RoB 2 / CONSORT."
  )

  private fun verifiedAnchoredFinding(id: String = "M-01"): ReviewFinding = ReviewFinding(
    id = id,
    title = "Baseline balance verified",
    allegation = "Randomization sequence and baseline balance were reported in Table 1.",
    severity = "MINOR",
    status = FindingStatus.VERIFIED,
    supportingEvidence = listOf(
      EvidenceAnchor(
        documentId = "doc-sha-1",
        pageNumber = 2,
        section = "Methods",
        exactQuote = "Patients were randomized 1:1 using permuted blocks of size 4.",
        startOffset = 40,
        endOffset = 98,
        extractionMethod = "PDFBox-Android",
        sourceChecksum = "chk-1"
      )
    )
  )

  private fun cleanInput(
    readiness: DocumentReadinessAssessment = readyDoc(),
    extractionConfidence: Int = 5,
    omittedPages: List<Int> = emptyList(),
    extractionCapApplied: Boolean = false,
    claimCapApplied: Boolean = false,
    findings: List<ReviewFinding> = listOf(verifiedAnchoredFinding()),
    deterministicFindings: List<ReviewFinding> = listOf(verifiedAnchoredFinding("DET-01")),
    passes: List<PassComparisonInput> = listOf(
      PassComparisonInput("Pass1", "RCT", "MORTALITY", "MINOR_CONCERNS"),
      PassComparisonInput("Pass2", "RCT", "MORTALITY", "MINOR_CONCERNS")
    ),
    quantStatuses: List<QuantitativeFindingStatus> = listOf(QuantitativeFindingStatus.PASS),
    domainAppraisal: DomainAppraisalReport? = rctDomainReport(),
    unexpectedFallback: Boolean = false,
    consentVerified: Boolean = true
  ): RefereeAdjudicationInput = RefereeAdjudicationInput(
    analysisId = "ana-001",
    manuscriptText = "Methods\nRandomized controlled trial in adults.\nResults\nTable 1 shows HR 0.82 (95% CI 0.70-0.95).\nReferences\n1. Ref.",
    replicationCodeText = "fit <- coxph(Surv(t, y) ~ trt)",
    pdfVisionDigestSha256 = "vis-sha-1",
    providerRoute = "Firebase AI Logic (Tier 1)",
    modelId = "gemini-3.8-flash",
    fallbackHistory = emptyList(),
    promptVersion = "v30.1",
    rulesetVersion = "referee-2026.11",
    appVersion = "1.0.0",
    startedAtMs = 1_700_000_000_000L,
    completedAtMs = 1_700_000_005_000L,
    readiness = readiness,
    extractionConfidence = extractionConfidence,
    totalPages = 4,
    pagesExtracted = 4 - omittedPages.size,
    pagesAnalyzed = 4 - omittedPages.size,
    omittedPages = omittedPages,
    claimsDiscovered = 4,
    claimsRetained = 4,
    claimsReconstructed = 3,
    claimsSkipped = if (claimCapApplied) 1 else 0,
    extractionCapApplied = extractionCapApplied,
    claimCapApplied = claimCapApplied,
    deterministicFindings = deterministicFindings,
    llmFindings = findings,
    passComparisons = passes,
    quantitativeStatuses = quantStatuses,
    crossCheckChecklist = DiscrepancyCrossCheckChecklist(
      primaryTablesChecked = true,
      figuresChecked = true,
      supplementsChecked = true,
      scaleTransformationsChecked = true,
      roundingToleranceChecked = true,
      adjustedModelsChecked = true,
      subgroupScopeChecked = true,
      alternativeMethodsChecked = true
    ),
    domainAppraisal = domainAppraisal,
    unexpectedProviderFallback = unexpectedFallback,
    consentVerified = consentVerified,
    consentDigestAtApproval = "consent-digest-1"
  )

  @Test
  fun phase3_pipelineTracksAll9StagesAndPreservesDeterministicFindingsOverLlmOverrides() {
    val detFinding = ReviewFinding(
      id = "DET-CI-01",
      title = "Deterministic CI Bounds Failure",
      allegation = "Reported 95% CI (0.72 to 1.45) is asymmetric around point estimate 0.85 on log scale.",
      severity = "CRITICAL",
      status = FindingStatus.VERIFIED,
      supportingEvidence = listOf(
        EvidenceAnchor(
          documentId = "doc-sha-1",
          pageNumber = 3,
          section = "Results",
          exactQuote = "HR 0.85 (95% CI 0.72 to 1.45)",
          startOffset = 10,
          endOffset = 38,
          extractionMethod = "PDFBox-Android",
          sourceChecksum = "chk-det"
        )
      )
    )
    // LLM attempts to overwrite DET-CI-01 as CONTRADICTED/INFO, and Pass1 vs Pass2 disagree on primaryEndpoint
    val conflictingLlmAttempt = detFinding.copy(
      severity = "INFO",
      status = FindingStatus.CONTRADICTED,
      allegation = "LLM attempted to dismiss deterministic CI failure."
    )
    val output = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(
        deterministicFindings = listOf(detFinding),
        findings = listOf(conflictingLlmAttempt),
        passes = listOf(
          PassComparisonInput("Pass1", "RCT", "MORTALITY", "MATERIAL_CONCERNS"),
          PassComparisonInput("Pass2", "RCT", "HOSPITALIZATION", "VERIFIED_NO_MAJOR_CONCERNS")
        )
      )
    )

    // All 9 stages tracked in exact order with non-blank SHA-256 hashes & versions
    assertEquals(9, output.stageRecords.size)
    assertEquals(PipelineStageId.entries, output.stageRecords.map { it.stageId })
    assertTrue(output.stageRecords.all { it.inputsHash.length == 64 && it.outputsHash.length == 64 && it.version.isNotBlank() })
    assertTrue(output.stageRecords.first { it.stageId == PipelineStageId.STAGE_4_STATISTICAL_RECONSTRUCTION }.deterministicImmutable)

    // Deterministic finding MUST remain immutable (never downgraded or dismissed by LLM)
    val preserved = output.deterministicFindingsPreserved.first { it.id == "DET-CI-01" }
    assertEquals(FindingStatus.VERIFIED, preserved.status)
    assertEquals("CRITICAL", preserved.severity)
    assertEquals(detFinding.allegation, preserved.allegation)

    // Disagreements recorded explicitly instead of silent overwrite
    assertTrue(output.interPassDisagreements.any { it.fieldName.contains("primaryEndpoint", ignoreCase = true) })
    assertTrue(output.interPassDisagreements.any { it.fieldName.contains("DET-CI-01", ignoreCase = true) })
    assertFalse(output.decision.isVerified)
  }

  @Test
  fun phase7_neverVerifiedEnforcesAll8MandatoryBlockingConditions() {
    // Baseline clean case MAY reach VERIFIED_WITH_MINOR_CONCERNS or VERIFIED_NO_MAJOR_CONCERNS
    val cleanOut = AutonomousRefereeOrchestrator.adjudicate(cleanInput())
    assertTrue("Expected verified baseline, got ${cleanOut.decision} (${cleanOut.neverVerifiedReasons})", cleanOut.decision.isVerified)

    // 1. Document is incomplete
    val c1 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(readiness = readyDoc().copy(readiness = DocumentReadiness.INCOMPLETE, methodsPresent = false))
    )
    assertFalse("1. Incomplete doc must never be verified", c1.decision.isVerified)

    // 2. Extraction/OCR quality is poor
    val c2 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(readiness = readyDoc().copy(extractionQualityAcceptable = false), extractionConfidence = 2)
    )
    assertFalse("2. Poor OCR/extraction must never be verified", c2.decision.isVerified)

    // 3. Coverage is partial or capped
    val c3 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(omittedPages = listOf(4), extractionCapApplied = true)
    )
    assertFalse("3. Capped coverage must never be verified", c3.decision.isVerified)

    // 4. Primary findings lack verified source anchors
    val unanchoredFinding = verifiedAnchoredFinding("M-NO-ANCHOR").copy(supportingEvidence = emptyList())
    val c4 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(deterministicFindings = emptyList(), findings = listOf(unanchoredFinding))
    )
    assertFalse("4. Unanchored verified findings must never produce verified decision", c4.decision.isVerified)

    // 5. Unresolved inter-pass disagreement on a critical claim
    val c5 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(
        passes = listOf(
          PassComparisonInput("Pass1", "RCT", "MORTALITY", "MAJOR_UNRESOLVED_CONCERNS"),
          PassComparisonInput("Pass2", "RCT", "MORTALITY", "VERIFIED_NO_MAJOR_CONCERNS")
        )
      )
    )
    assertFalse("5. Unresolved inter-pass disagreement must never be verified", c5.decision.isVerified)

    // 6. Conflicting quantitative finding is unresolved
    val c6 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(quantStatuses = listOf(QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION))
    )
    assertFalse("6. Unresolved conflicting quantitative finding must never be verified", c6.decision.isVerified)

    // 7. Domain appraisal missing or study design uncertain
    val c7 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(
        domainAppraisal = rctDomainReport().copy(
          domain = AppraisalDomain.DESIGN_UNCERTAIN,
          restrictsVerdictDueToUncertainDesign = true
        )
      )
    )
    assertFalse("7. Uncertain study design / missing domain appraisal must never be verified", c7.decision.isVerified)
    assertEquals(RefereeDecision.PROVISIONAL_APPRAISAL, c7.decision)

    // 8. Model/provider fallback status is uncertain
    val c8 = AutonomousRefereeOrchestrator.adjudicate(
      cleanInput(unexpectedFallback = true)
    )
    assertFalse("8. Uncertain provider fallback must never be verified", c8.decision.isVerified)
  }

  @Test
  fun phase8_abstentionGateCoversAll10TriggersWithStructuredExplanation() {
    fun assertStructured(trigger: AbstentionTrigger, input: RefereeAdjudicationInput) {
      val res = AbstentionGate.evaluate(input)
      assertTrue("Expected trigger $trigger to fire", res.triggeredReasons.any { it.trigger == trigger })
      assertTrue(res.shouldAbstainFromVerifiedVerdict)
      val reason = res.triggeredReasons.first { it.trigger == trigger }
      assertTrue(reason.whatIsMissing.isNotBlank())
      assertTrue(reason.whyItMatters.isNotBlank())
      assertTrue(reason.blockedConclusion.isNotBlank())
      assertTrue(reason.nextStepsForUser.isNotEmpty())
    }

    assertStructured(AbstentionTrigger.MISSING_METHODS_OR_RESULTS, cleanInput(readiness = readyDoc().copy(readiness = DocumentReadiness.INCOMPLETE, methodsPresent = false)))
    assertStructured(AbstentionTrigger.PRIMARY_TABLES_UNAVAILABLE, cleanInput(readiness = readyDoc().copy(tablesAndFiguresDetectable = false)))
    assertStructured(AbstentionTrigger.POOR_OCR_OR_EXTRACTION, cleanInput(readiness = readyDoc().copy(extractionQualityAcceptable = false), extractionConfidence = 2))
    assertStructured(AbstentionTrigger.IRREPRODUCIBLE_SOURCE_ANCHORS, cleanInput(deterministicFindings = emptyList(), findings = listOf(verifiedAnchoredFinding("U1").copy(supportingEvidence = emptyList()))))
    assertStructured(AbstentionTrigger.PDF_OR_CONTENT_CHANGED_AFTER_CONSENT, cleanInput(consentVerified = false))
    assertStructured(
      AbstentionTrigger.CRITICAL_POPULATION_OR_ENDPOINT_UNKNOWN,
      cleanInput(passes = listOf(PassComparisonInput("Pass1", "RCT", null, "PROVISIONAL_APPRAISAL")))
    )
    assertStructured(
      AbstentionTrigger.MULTI_INTERPRETATION_STATISTICAL_RESULT,
      cleanInput(quantStatuses = listOf(QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION))
    )
    assertStructured(AbstentionTrigger.CAPPED_MANUSCRIPT_COVERAGE, cleanInput(omittedPages = listOf(3), claimCapApplied = true))
    assertStructured(AbstentionTrigger.UNCERTAIN_PROVIDER_OR_MODEL_FALLBACK, cleanInput(unexpectedFallback = true))
    assertStructured(
      AbstentionTrigger.MISSING_DOMAIN_RUBRIC_OR_DESIGN_UNCERTAIN,
      cleanInput(
        domainAppraisal = rctDomainReport().copy(
          domain = AppraisalDomain.DESIGN_UNCERTAIN,
          restrictsVerdictDueToUncertainDesign = true
        )
      )
    )
  }

  @Test
  fun phase9_tamperEvidentAuditLogManifestAndCleanupHookAreReproducibleAndTamperDetectable() {
    val log = TamperEvidentAuditLog()
    log.append("S1_INGEST", "INGESTED", "4 pages extracted")
    log.append("S4_STATS", "QUANT_CHECK", "1 check passed")
    log.append("S8_ADJUD", "DECISION", "VERIFIED_NO_MAJOR_CONCERNS")

    assertTrue(log.verifyChain())
    val entries = log.entries()
    assertEquals(3, entries.size)
    assertEquals("GENESIS", entries[0].previousEntryHash)
    assertEquals(entries[0].entryHash, entries[1].previousEntryHash)

    // Mutate middle entry payload -> chain verification MUST fail
    val tampered = entries.toMutableList()
    tampered[1] = tampered[1].copy(payloadSummary = "Tampered payload")
    assertFalse(TamperEvidentAuditLog.verifyChain(tampered))

    // Manifest captures all required provenance identifiers
    val output = AutonomousRefereeOrchestrator.adjudicate(cleanInput())
    val manifest: AnalysisManifest = output.manifest
    assertEquals("ana-001", manifest.analysisId)
    assertEquals(64, manifest.manuscriptSha256.length)
    assertEquals(64, manifest.replicationCodeSha256.length)
    assertEquals("vis-sha-1", manifest.pdfVisionDigest)
    assertEquals("Firebase AI Logic (Tier 1)", manifest.providerRoute)
    assertEquals("gemini-3.8-flash", manifest.modelId)
    assertEquals("v30.1", manifest.promptVersion)
    assertEquals("referee-2026.11", manifest.rulesetVersion)
    assertEquals("1.0.0", manifest.appVersion)
    assertNotEquals(0L, manifest.startedAtMs)
    assertTrue(manifest.completedAtMs >= manifest.startedAtMs)

    // Remote payload cleanup hook tracks and clears temporary payload handles
    val hook = InMemoryRemotePayloadCleanupHook()
    hook.registerPayloadHandle("tmp_vision_bundle_1", System.currentTimeMillis() + 60_000L)
    assertEquals(1, hook.activeHandles().size)
    assertEquals(1, hook.cleanupAll("pipeline_completed"))
    assertEquals(0, hook.activeHandles().size)
  }
}
