package com.example.data.ai.referee

import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding

/**
 * Phase 3 & Phase 7 Autonomous Scientific Referee Orchestrator.
 * - Persists all 9 tracked pipeline stages with SHA-256 input/output digests, versions, and failures
 * - Enforces deterministic finding immutability across LLM passes
 * - Preserves inter-pass disagreements explicitly (never silently overwritten)
 * - Enforces all 8 NEVER VERIFIED rules and delegates to [AbstentionGate]
 * - Produces a tamper-evident SHA-256 audit chain and reproducible [AnalysisManifest]
 */
object AutonomousRefereeOrchestrator {

  fun detectInterPassDisagreements(passes: List<PassComparisonInput>): List<InterPassDisagreement> {
    if (passes.size < 2) return emptyList()
    val disagreements = mutableListOf<InterPassDisagreement>()
    val baseline = passes.first()

    for (i in 1 until passes.size) {
      val other = passes[i]
      val baseDesign = baseline.designClassification?.trim().orEmpty()
      val otherDesign = other.designClassification?.trim().orEmpty()
      if (baseDesign.isNotBlank() && otherDesign.isNotBlank() &&
        !baseDesign.equals(otherDesign, ignoreCase = true)
      ) {
        disagreements.add(
          InterPassDisagreement(
            disagreementId = "DIS-DESIGN-${baseline.passName}-${other.passName}",
            fieldName = "designClassification",
            passAName = baseline.passName,
            passAValue = baseDesign,
            passBName = other.passName,
            passBValue = otherDesign,
            severity = "MAJOR"
          )
        )
      }

      val baseEp = baseline.primaryEndpoint?.trim().orEmpty()
      val otherEp = other.primaryEndpoint?.trim().orEmpty()
      if (baseEp.isNotBlank() && otherEp.isNotBlank() &&
        !baseEp.equals(otherEp, ignoreCase = true)
      ) {
        disagreements.add(
          InterPassDisagreement(
            disagreementId = "DIS-ENDPOINT-${baseline.passName}-${other.passName}",
            fieldName = "primaryEndpoint",
            passAName = baseline.passName,
            passAValue = baseEp,
            passBName = other.passName,
            passBValue = otherEp,
            severity = "CRITICAL"
          )
        )
      }

      val baseVerdict = baseline.criticalVerdict?.trim().orEmpty()
      val otherVerdict = other.criticalVerdict?.trim().orEmpty()
      if (baseVerdict.isNotBlank() && otherVerdict.isNotBlank() &&
        !baseVerdict.equals(otherVerdict, ignoreCase = true)
      ) {
        disagreements.add(
          InterPassDisagreement(
            disagreementId = "DIS-VERDICT-${baseline.passName}-${other.passName}",
            fieldName = "criticalVerdict",
            passAName = baseline.passName,
            passAValue = baseVerdict,
            passBName = other.passName,
            passBValue = otherVerdict,
            severity = "CRITICAL"
          )
        )
      }
    }
    return disagreements
  }

  /**
   * Preserves every deterministic finding unchanged against LLM rewrites/dismissals,
   * and emits explicit [InterPassDisagreement] records whenever an LLM stage attempts
   * to alter a deterministic finding's status or severity.
   */
  fun preserveDeterministicFindingsWithDisagreements(
    deterministicFindings: List<ReviewFinding>,
    llmFindings: List<ReviewFinding>
  ): Pair<List<ReviewFinding>, List<InterPassDisagreement>> {
    val detById = deterministicFindings.associateBy { it.id }
    val conflicts = mutableListOf<InterPassDisagreement>()

    llmFindings.forEach { llm ->
      val det = detById[llm.id]
      if (det != null && (det.status != llm.status || !det.severity.equals(llm.severity, ignoreCase = true))) {
        conflicts.add(
          InterPassDisagreement(
            disagreementId = "DIS-DET-${det.id}",
            fieldName = "finding:${det.id}",
            passAName = "DeterministicEngine",
            passAValue = "${det.status.name}/${det.severity}",
            passBName = "LLMStage",
            passBValue = "${llm.status.name}/${llm.severity}",
            severity = if (det.severity == "CRITICAL") "CRITICAL" else "MAJOR",
            resolutionNote = "Deterministic finding '${det.id}' preserved immutable; LLM override rejected."
          )
        )
      }
    }

    val preservedDet = deterministicFindings.map { det ->
      det.copy(
        provenanceSummary = det.provenanceSummary.ifBlank { "Deterministic Engine (Immutable)" }
      )
    }
    return preservedDet to conflicts
  }

  fun adjudicate(input: RefereeAdjudicationInput): RefereeAdjudicationOutput {
    val auditLog = TamperEvidentAuditLog()
    val manuscriptHash = TamperEvidentAuditLog.sha256Hex(input.manuscriptText)
    val codeHash = if (input.replicationCodeText.isBlank()) {
      TamperEvidentAuditLog.sha256Hex("")
    } else {
      TamperEvidentAuditLog.sha256Hex(input.replicationCodeText)
    }

    val (preservedDet, detConflicts) = preserveDeterministicFindingsWithDisagreements(
      deterministicFindings = input.deterministicFindings,
      llmFindings = input.llmFindings
    )
    val passDisagreements = detectInterPassDisagreements(input.passComparisons)
    val allDisagreements = (detConflicts + passDisagreements).distinctBy { it.disagreementId }

    val mergedFindings = buildList {
      val detIds = preservedDet.map { it.id }.toSet()
      addAll(preservedDet)
      addAll(input.llmFindings.filter { it.id !in detIds })
    }

    val abstention = AbstentionGate.evaluate(input, allDisagreements)

    // Evaluate all 8 NEVER VERIFIED conditions explicitly
    val neverVerifiedReasons = mutableListOf<String>()
    if (!input.readiness.allowsVerifiedVerdict) {
      neverVerifiedReasons.add("1. Document readiness is ${input.readiness.readiness.name}.")
    }
    if (!input.readiness.extractionQualityAcceptable || input.extractionConfidence < 4) {
      neverVerifiedReasons.add("2. Extraction/OCR quality insufficient (${input.extractionConfidence}/5).")
    }
    if (input.omittedPages.isNotEmpty() || input.extractionCapApplied || input.claimCapApplied || input.claimsSkipped > 0) {
      neverVerifiedReasons.add("3. Coverage is partial or capped (omittedPages=${input.omittedPages.size}, claimsSkipped=${input.claimsSkipped}).")
    }
    val verifiedMissingAnchorCount = mergedFindings.count {
      it.status == FindingStatus.VERIFIED &&
        (it.supportingEvidence.isEmpty() || it.supportingEvidence.all { a -> a.exactQuote.trim().length < 12 })
    }
    if (verifiedMissingAnchorCount > 0) {
      neverVerifiedReasons.add("4. $verifiedMissingAnchorCount verified finding(s) lack verified source anchors.")
    }
    if (allDisagreements.any { it.unresolved }) {
      neverVerifiedReasons.add("5. ${allDisagreements.count { it.unresolved }} unresolved inter-pass disagreement(s) on critical fields.")
    }
    if (input.quantitativeStatuses.any {
        it == QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION ||
          it == QuantitativeFindingStatus.NOT_ASSESSABLE
      }
    ) {
      neverVerifiedReasons.add("6. Conflicting or ambiguous quantitative finding remains unresolved.")
    }
    val domainReport = input.domainAppraisal
    if (domainReport == null ||
      domainReport.domain == AppraisalDomain.DESIGN_UNCERTAIN ||
      domainReport.restrictsVerdictDueToUncertainDesign ||
      domainReport.rubricCriteria.isEmpty()
    ) {
      neverVerifiedReasons.add("7. Domain-specific appraisal unavailable or study design uncertain.")
    }
    if (input.unexpectedProviderFallback) {
      neverVerifiedReasons.add("8. Model/provider fallback status is uncertain or unauthorized.")
    }
    if (!input.consentVerified) {
      neverVerifiedReasons.add("Content changed after consent verification.")
    }

    val confirmedFatalOrMajorCount = mergedFindings.count {
      it.status == FindingStatus.VERIFIED &&
        (it.severity.equals("CRITICAL", true) || it.severity.equals("MAJOR", true))
    } + input.quantitativeStatuses.count { it == QuantitativeFindingStatus.CONFIRMED_ERROR }
    val minorVerifiedCount = mergedFindings.count {
      it.status == FindingStatus.VERIFIED && it.severity.equals("MINOR", true)
    }

    val decision = when {
      input.isOutOfScopeDocument || input.readiness.readiness == DocumentReadiness.UNSUPPORTED_FORMAT ->
        RefereeDecision.OUT_OF_SCOPE
      !input.consentVerified ||
        input.readiness.readiness == DocumentReadiness.EXTRACTION_FAILURE ||
        !input.readiness.visionPagesBound ||
        (input.readiness.searchableTextExists && (!input.readiness.methodsPresent || !input.readiness.resultsPresent)) ||
        !input.readiness.extractionQualityAcceptable ->
        RefereeDecision.INSUFFICIENT_EVIDENCE
      domainReport == null ||
        domainReport.domain == AppraisalDomain.DESIGN_UNCERTAIN ||
        domainReport.restrictsVerdictDueToUncertainDesign ||
        domainReport.rubricCriteria.isEmpty() ->
        RefereeDecision.PROVISIONAL_APPRAISAL
      abstention.shouldAbstainFromVerifiedVerdict || neverVerifiedReasons.isNotEmpty() -> {
        if (abstention.hardBlockAllVerdicts) {
          RefereeDecision.INSUFFICIENT_EVIDENCE
        } else if (confirmedFatalOrMajorCount >= 2 ||
          input.quantitativeStatuses.any { it == QuantitativeFindingStatus.CONFIRMED_ERROR }
        ) {
          RefereeDecision.MAJOR_UNRESOLVED_CONCERNS
        } else {
          RefereeDecision.PROVISIONAL_APPRAISAL
        }
      }
      confirmedFatalOrMajorCount >= 2 ||
        mergedFindings.any { it.status == FindingStatus.VERIFIED && it.severity.equals("CRITICAL", true) } ->
        RefereeDecision.MAJOR_UNRESOLVED_CONCERNS
      confirmedFatalOrMajorCount == 1 ->
        RefereeDecision.MATERIAL_CONCERNS
      minorVerifiedCount > 0 || input.quantitativeStatuses.any { it == QuantitativeFindingStatus.WARNING } ->
        RefereeDecision.VERIFIED_WITH_MINOR_CONCERNS
      else -> RefereeDecision.VERIFIED_NO_MAJOR_CONCERNS
    }

    val standardizedLabel = when {
      decision == RefereeDecision.INSUFFICIENT_EVIDENCE -> RefereeCoverageMetrics.LABEL_INSUFFICIENT
      decision == RefereeDecision.PROVISIONAL_APPRAISAL -> RefereeCoverageMetrics.LABEL_PROVISIONAL()
      abstention.shouldAbstainFromVerifiedVerdict -> RefereeCoverageMetrics.LABEL_MANUAL_REQUIRED
      input.omittedPages.isNotEmpty() || input.extractionCapApplied || input.claimCapApplied ||
        input.claimsSkipped > 0 || input.unsupportedClaims > 0 || input.unresolvedClaims > 0 ->
        RefereeCoverageMetrics.LABEL_PARTIAL
      else -> RefereeCoverageMetrics.LABEL_COMPLETE
    }

    val verifiedAnchorCount = mergedFindings.sumOf { f ->
      f.supportingEvidence.count { it.exactQuote.trim().length >= 12 }
    }
    val coverageMetrics = RefereeCoverageMetrics(
      pagesExtracted = input.pagesExtracted,
      totalPages = input.totalPages,
      pagesAnalyzed = input.pagesAnalyzed,
      pagesOmitted = input.omittedPages.size,
      claimsDiscovered = input.claimsDiscovered,
      claimsRetained = input.claimsRetained,
      claimsReconstructed = input.claimsReconstructed,
      claimsSkipped = input.claimsSkipped,
      unsupportedClaims = input.unsupportedClaims,
      unresolvedClaims = input.unresolvedClaims,
      sourceAnchorsVerified = verifiedAnchorCount,
      interPassDisagreements = allDisagreements.size,
      extractionCapApplied = input.extractionCapApplied,
      claimCapApplied = input.claimCapApplied,
      standardizedLabel = standardizedLabel
    )

    val manifest = AnalysisManifest(
      analysisId = input.analysisId,
      manuscriptSha256 = manuscriptHash,
      replicationCodeSha256 = codeHash,
      pdfVisionDigest = input.pdfVisionDigestSha256,
      providerRoute = input.providerRoute,
      modelId = input.modelId,
      fallbackHistory = input.fallbackHistory,
      promptVersion = input.promptVersion,
      rulesetVersion = input.rulesetVersion,
      appVersion = input.appVersion,
      startedAtMs = input.startedAtMs,
      completedAtMs = input.completedAtMs,
      consentDigestAtApproval = input.consentDigestAtApproval,
      consentVerified = input.consentVerified
    )

    val stageRecords = buildNineStageRecords(
      input = input,
      manuscriptHash = manuscriptHash,
      mergedFindings = mergedFindings,
      disagreements = allDisagreements,
      decision = decision,
      abstention = abstention
    )

    stageRecords.forEach { stage ->
      auditLog.append(
        stageCode = stage.stageId.stageCode,
        eventType = if (stage.failures.isEmpty()) "STAGE_COMPLETED" else "STAGE_WARNING",
        payloadSummary = "${stage.stageId.displayName}|in=${stage.inputsHash.take(12)}|out=${stage.outputsHash.take(12)}",
        timestampMs = stage.executedAtMs
      )
    }

    val entries = auditLog.entries()
    val terminalHash = entries.lastOrNull()?.entryHash.orEmpty()
    val commitment = if (terminalHash.isNotBlank()) {
      TamperEvidentAuditLog.computeCommitmentHash(
        terminalHash = terminalHash,
        analysisId = input.analysisId,
        manuscriptSha256 = manuscriptHash,
        stageCount = entries.size
      )
    } else ""
    return RefereeAdjudicationOutput(
      decision = decision,
      stageRecords = stageRecords,
      deterministicFindingsPreserved = preservedDet,
      mergedFindings = mergedFindings,
      interPassDisagreements = allDisagreements,
      abstentionResult = abstention,
      manifest = manifest,
      auditLogEntries = entries,
      auditLogJson = auditLog.toJson(),
      auditLogChainValid = TamperEvidentAuditLog.verifyChain(entries),
      auditCommitment = commitment,
      coverageMetrics = coverageMetrics,
      neverVerifiedReasons = neverVerifiedReasons
    )
  }

  private fun RefereeCoverageMetrics.Companion.LABEL_PROVISIONAL(): String =
    RefereeCoverageMetrics.LABEL_PRELIMINARY

  private fun buildNineStageRecords(
    input: RefereeAdjudicationInput,
    manuscriptHash: String,
    mergedFindings: List<ReviewFinding>,
    disagreements: List<InterPassDisagreement>,
    decision: RefereeDecision,
    abstention: AbstentionResult
  ): List<PipelineStageRecord> {
    fun rec(
      stageId: PipelineStageId,
      inSummary: String,
      outSummary: String,
      assumptions: List<String> = emptyList(),
      failures: List<String> = emptyList(),
      immutable: Boolean = false
    ): PipelineStageRecord = PipelineStageRecord(
      stageId = stageId,
      inputsSummary = inSummary,
      inputsHash = TamperEvidentAuditLog.sha256Hex(inSummary),
      outputsSummary = outSummary,
      outputsHash = TamperEvidentAuditLog.sha256Hex(outSummary),
      version = "${input.rulesetVersion}/${input.promptVersion}",
      assumptions = assumptions,
      failures = failures,
      deterministicImmutable = immutable,
      executedAtMs = input.completedAtMs
    )

    return listOf(
      rec(
        PipelineStageId.STAGE_1_DOCUMENT_INGESTION,
        "sha256=$manuscriptHash;pages=${input.totalPages}",
        "extracted=${input.pagesExtracted}/${input.totalPages};conf=${input.extractionConfidence}",
        failures = if (input.readiness.extractionQualityAcceptable) emptyList() else listOf("Low extraction quality"),
        immutable = true
      ),
      rec(
        PipelineStageId.STAGE_2_STRUCTURE_DETECTION,
        "readiness_check=methods:${input.readiness.methodsPresent},results:${input.readiness.resultsPresent}",
        "readiness=${input.readiness.readiness.name};supp=${input.readiness.supplementStatus.name}",
        failures = input.readiness.reasons,
        immutable = true
      ),
      rec(
        PipelineStageId.STAGE_3_CLAIM_INVENTORY,
        "textLen=${input.manuscriptText.length}",
        "discovered=${input.claimsDiscovered};retained=${input.claimsRetained};skipped=${input.claimsSkipped}",
        failures = if (input.claimCapApplied) listOf("Claim cap applied") else emptyList()
      ),
      rec(
        PipelineStageId.STAGE_4_STATISTICAL_RECONSTRUCTION,
        "claims=${input.claimsRetained};checklist=${8 - input.crossCheckChecklist.uncheckedChannels().size}/8",
        "reconstructed=${input.claimsReconstructed};statuses=${input.quantitativeStatuses.joinToString(",")}",
        assumptions = listOf("Deterministic formulas locked against LLM override"),
        immutable = true
      ),
      rec(
        PipelineStageId.STAGE_5_METHODOLOGICAL_APPRAISAL,
        "domain=${input.domainAppraisal?.domain?.name ?: "NONE"}",
        "instrument=${input.domainAppraisal?.instrumentName ?: "NONE"};concerns=${
          input.domainAppraisal?.rubricCriteria?.count {
            it.rating == DomainCriterionRating.HIGH_CONCERN || it.rating == DomainCriterionRating.NOT_ASSESSABLE
          } ?: 0
        }",
        failures = input.domainAppraisal?.rubricCriteria
          ?.filter { it.rating == DomainCriterionRating.HIGH_CONCERN || it.rating == DomainCriterionRating.NOT_ASSESSABLE }
          ?.map { "${it.label}: ${it.rating.name}" }
          .orEmpty()
      ),
      rec(
        PipelineStageId.STAGE_6_SOURCE_VERIFICATION,
        "findings=${mergedFindings.size}",
        "verified=${mergedFindings.count { it.status == FindingStatus.VERIFIED }}",
        immutable = true
      ),
      rec(
        PipelineStageId.STAGE_7_CONTRADICTION_ANALYSIS,
        "passes=${input.passComparisons.size}",
        "disagreements=${disagreements.size}",
        failures = disagreements.map { "${it.fieldName}:${it.passAValue}!=${it.passBValue}" },
        immutable = true
      ),
      rec(
        PipelineStageId.STAGE_8_SEVERITY_ADJUDICATION,
        "abstentionTriggers=${abstention.triggeredReasons.size}",
        "decision=${decision.name}",
        failures = abstention.triggeredReasons.map { it.trigger.name },
        immutable = true
      ),
      rec(
        PipelineStageId.STAGE_9_FINAL_REPORT_GENERATION,
        "decision=${decision.name};findings=${mergedFindings.size}",
        "ledger_rendered=true;consentVerified=${input.consentVerified}",
        immutable = true
      )
    )
  }
}
