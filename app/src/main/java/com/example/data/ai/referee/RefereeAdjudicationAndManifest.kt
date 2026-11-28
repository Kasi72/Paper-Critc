package com.example.data.ai.referee

import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical Phase 7 autonomous referee decision states.
 * Verdict is computed strictly from the adjudicated evidence ledger and readiness/abstention gates.
 */
enum class RefereeDecision(val displayLabel: String) {
  VERIFIED_NO_MAJOR_CONCERNS("Verified — No Major Concerns"),
  VERIFIED_WITH_MINOR_CONCERNS("Verified — With Minor Concerns"),
  MATERIAL_CONCERNS("Material Concerns"),
  MAJOR_UNRESOLVED_CONCERNS("Major Unresolved Concerns"),
  INSUFFICIENT_EVIDENCE("Insufficient Evidence"),
  PROVISIONAL_APPRAISAL("Provisional Appraisal"),
  OUT_OF_SCOPE("Out of Scope");

  val isVerified: Boolean
    get() = this == VERIFIED_NO_MAJOR_CONCERNS || this == VERIFIED_WITH_MINOR_CONCERNS

  companion object {
    fun fromNameOrNull(raw: String?): RefereeDecision? =
      entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
  }
}

/**
 * Canonical Phase 8 triggers for centralized safe abstention.
 */
enum class AbstentionTrigger(val title: String) {
  MISSING_METHODS_OR_RESULTS("Methods or Results section missing"),
  PRIMARY_TABLES_UNAVAILABLE("Primary outcome tables/figures unavailable"),
  POOR_OCR_OR_EXTRACTION("Poor OCR or text extraction fidelity"),
  IRREPRODUCIBLE_SOURCE_ANCHORS("Primary findings lack reproducible source anchors"),
  PDF_OR_CONTENT_CHANGED_AFTER_CONSENT("Manuscript/PDF content changed after remote consent"),
  CRITICAL_POPULATION_OR_ENDPOINT_UNKNOWN("Critical population or primary endpoint unresolved"),
  MULTI_INTERPRETATION_STATISTICAL_RESULT("Statistical result admits multiple conflicting interpretations"),
  CAPPED_MANUSCRIPT_COVERAGE("Extraction or quantitative claim coverage capped"),
  UNCERTAIN_PROVIDER_OR_MODEL_FALLBACK("Model or provider fallback state is uncertain"),
  MISSING_DOMAIN_RUBRIC_OR_DESIGN_UNCERTAIN("Study design uncertain or domain rubric unavailable")
}

data class AbstentionReason(
  val trigger: AbstentionTrigger,
  val whatIsMissing: String,
  val whyItMatters: String,
  val blockedConclusion: String,
  val nextStepsForUser: List<String>
)

data class AbstentionResult(
  val shouldAbstainFromVerifiedVerdict: Boolean,
  val hardBlockAllVerdicts: Boolean,
  val triggeredReasons: List<AbstentionReason>,
  val primaryWhatIsMissing: String,
  val primaryWhyItMatters: String,
  val primaryBlockedConclusion: String,
  val combinedNextSteps: List<String>
) {
  fun toJson(): String {
    val obj = JSONObject()
    obj.put("shouldAbstainFromVerifiedVerdict", shouldAbstainFromVerifiedVerdict)
    obj.put("hardBlockAllVerdicts", hardBlockAllVerdictSafe(hardBlockAllVerdicts))
    obj.put("primaryWhatIsMissing", primaryWhatIsMissing)
    obj.put("primaryWhyItMatters", primaryWhyItMatters)
    obj.put("primaryBlockedConclusion", primaryBlockedConclusion)
    val stepsArr = JSONArray()
    combinedNextSteps.forEach { stepsArr.put(it) }
    obj.put("combinedNextSteps", stepsArr)
    val triggersArr = JSONArray()
    triggeredReasons.forEach { r ->
      val ro = JSONObject()
      ro.put("trigger", r.trigger.name)
      ro.put("whatIsMissing", r.whatIsMissing)
      ro.put("whyItMatters", r.whyItMatters)
      ro.put("blockedConclusion", r.blockedConclusion)
      val ns = JSONArray()
      r.nextStepsForUser.forEach { ns.put(it) }
      ro.put("nextStepsForUser", ns)
      triggersArr.put(ro)
    }
    obj.put("triggeredReasons", triggersArr)
    return obj.toString()
  }

  private fun hardBlockAllVerdictSafe(flag: Boolean): Boolean = flag

  companion object {
    fun fromJson(raw: String): AbstentionResult? {
      if (raw.isBlank()) return null
      return runCatching {
        val o = JSONObject(raw)
        val stepsArr = o.optJSONArray("combinedNextSteps")
        val triggersArr = o.optJSONArray("triggeredReasons")
        val parsedReasons = buildList {
          if (triggersArr != null) {
            for (i in 0 until triggersArr.length()) {
              val ro = triggersArr.optJSONObject(i) ?: continue
              val trigger = AbstentionTrigger.entries.firstOrNull {
                it.name.equals(ro.optString("trigger"), ignoreCase = true)
              } ?: continue
              val nsArr = ro.optJSONArray("nextStepsForUser")
              add(
                AbstentionReason(
                  trigger = trigger,
                  whatIsMissing = ro.optString("whatIsMissing", ""),
                  whyItMatters = ro.optString("whyItMatters", ""),
                  blockedConclusion = ro.optString("blockedConclusion", ""),
                  nextStepsForUser = buildList {
                    if (nsArr != null) {
                      for (j in 0 until nsArr.length()) {
                        nsArr.optString(j).takeIf { it.isNotBlank() }?.let { add(it) }
                      }
                    }
                  }
                )
              )
            }
          }
        }
        AbstentionResult(
          shouldAbstainFromVerifiedVerdict = o.optBoolean("shouldAbstainFromVerifiedVerdict", false),
          hardBlockAllVerdicts = o.optBoolean("hardBlockAllVerdicts", false),
          triggeredReasons = parsedReasons,
          primaryWhatIsMissing = o.optString("primaryWhatIsMissing", ""),
          primaryWhyItMatters = o.optString("primaryWhyItMatters", ""),
          primaryBlockedConclusion = o.optString("primaryBlockedConclusion", ""),
          combinedNextSteps = buildList {
            if (stepsArr != null) {
              for (i in 0 until stepsArr.length()) {
                stepsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
              }
            }
          }
        )
      }.getOrNull()
    }
  }
}

data class AnalysisManifest(
  val analysisId: String,
  val manuscriptSha256: String,
  val replicationCodeSha256: String,
  val pdfVisionDigest: String,
  val providerRoute: String,
  val modelId: String,
  val fallbackHistory: List<String>,
  val promptVersion: String,
  val rulesetVersion: String,
  val appVersion: String,
  val startedAtMs: Long,
  val completedAtMs: Long,
  val consentDigestAtApproval: String = "",
  val consentVerified: Boolean = true
) {
  fun toJson(): String {
    val obj = JSONObject()
    obj.put("analysisId", analysisId)
    obj.put("manuscriptSha256", manuscriptSha256)
    obj.put("replicationCodeSha256", replicationCodeSha256)
    obj.put("pdfVisionDigest", pdfVisionDigest)
    obj.put("providerRoute", providerRoute)
    obj.put("modelId", modelId)
    val fb = JSONArray()
    fallbackHistory.take(16).forEach { fb.put(it.take(160)) }
    obj.put("fallbackHistory", fb)
    obj.put("promptVersion", promptVersion)
    obj.put("rulesetVersion", rulesetVersion)
    obj.put("appVersion", appVersion)
    obj.put("startedAtMs", startedAtMs)
    obj.put("completedAtMs", completedAtMs)
    obj.put("consentDigestAtApproval", consentDigestAtApproval)
    obj.put("consentVerified", consentVerified)
    return obj.toString()
  }

  companion object {
    fun fromJson(raw: String): AnalysisManifest? {
      if (raw.isBlank()) return null
      return runCatching {
        val o = JSONObject(raw)
        val fbArr = o.optJSONArray("fallbackHistory")
        AnalysisManifest(
          analysisId = o.optString("analysisId", ""),
          manuscriptSha256 = o.optString("manuscriptSha256", ""),
          replicationCodeSha256 = o.optString("replicationCodeSha256", ""),
          pdfVisionDigest = o.optString("pdfVisionDigest", ""),
          providerRoute = o.optString("providerRoute", ""),
          modelId = o.optString("modelId", ""),
          fallbackHistory = buildList {
            if (fbArr != null) {
              for (i in 0 until minOf(fbArr.length(), 16)) {
                fbArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
              }
            }
          },
          promptVersion = o.optString("promptVersion", ""),
          rulesetVersion = o.optString("rulesetVersion", ""),
          appVersion = o.optString("appVersion", ""),
          startedAtMs = o.optLong("startedAtMs", 0L),
          completedAtMs = o.optLong("completedAtMs", 0L),
          consentDigestAtApproval = o.optString("consentDigestAtApproval", ""),
          consentVerified = o.optBoolean("consentVerified", true)
        )
      }.getOrNull()
    }
  }
}

enum class AuditLogVerificationStatus(val displayLabel: String) {
  VERIFIED("Cryptographically Committed (9 Stages)"),
  LOCAL_CHAIN_ONLY("Local Hash Chain Only (No Immutable Commitment)"),
  MISSING_COMMITMENT("Commitment Mismatch / Incomplete"),
  NOT_AVAILABLE("Audit Log Not Available"),
  TAMPERED("Audit Chain Tampered / Invalid")
}

data class AuditLogVerificationResult(
  val status: AuditLogVerificationStatus,
  val entryCount: Int,
  val terminalHash: String,
  val detail: String
) {
  val isChainIntact: Boolean
    get() = status == AuditLogVerificationStatus.VERIFIED || status == AuditLogVerificationStatus.LOCAL_CHAIN_ONLY

  val isCommittedAndVerified: Boolean
    get() = status == AuditLogVerificationStatus.VERIFIED
}

data class AuditLogEntry(
  val sequence: Int,
  val stageCode: String,
  val eventType: String,
  val payloadSummary: String,
  val payloadSha256: String,
  val previousEntryHash: String,
  val entryHash: String,
  val timestampMs: Long
)

/**
 * Phase 9 tamper-evident SHA-256 hash-chained audit trail.
 * Empty audit logs NEVER report valid/verified; they report [AuditLogVerificationStatus.NOT_AVAILABLE].
 */
class TamperEvidentAuditLog {
  private val records = mutableListOf<AuditLogEntry>()

  fun append(
    stageCode: String,
    eventType: String,
    payloadSummary: String,
    timestampMs: Long = System.currentTimeMillis()
  ): AuditLogEntry {
    val prevHash = records.lastOrNull()?.entryHash ?: "GENESIS"
    val seq = records.size
    val payloadHash = sha256Hex(payloadSummary)
    val entryHash = computeEntryHash(
      sequence = seq,
      stageCode = stageCode,
      eventType = eventType,
      payloadSummary = payloadSummary,
      payloadSha256 = payloadHash,
      previousEntryHash = prevHash,
      timestampMs = timestampMs
    )
    val entry = AuditLogEntry(
      sequence = seq,
      stageCode = stageCode,
      eventType = eventType,
      payloadSummary = payloadSummary,
      payloadSha256 = payloadHash,
      previousEntryHash = prevHash,
      entryHash = entryHash,
      timestampMs = timestampMs
    )
    records.add(entry)
    return entry
  }

  fun entries(): List<AuditLogEntry> = records.toList()

  fun verifyChain(): Boolean = verifyChain(records)

  fun toJson(): String {
    val arr = JSONArray()
    records.take(64).forEach { e ->
      val o = JSONObject()
      o.put("sequence", e.sequence)
      o.put("stageCode", e.stageCode)
      o.put("eventType", e.eventType)
      o.put("payloadSummary", e.payloadSummary)
      o.put("payloadSha256", e.payloadSha256)
      o.put("previousEntryHash", e.previousEntryHash)
      o.put("entryHash", e.entryHash)
      o.put("timestampMs", e.timestampMs)
      arr.put(o)
    }
    return arr.toString()
  }

  companion object {
    fun sha256Hex(text: String): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun computeEntryHash(
      sequence: Int,
      stageCode: String,
      eventType: String,
      payloadSummary: String,
      payloadSha256: String,
      previousEntryHash: String,
      timestampMs: Long
    ): String = sha256Hex("$sequence|$stageCode|$eventType|$payloadSummary|$payloadSha256|$previousEntryHash|$timestampMs")

    fun computeCommitmentHash(
      terminalHash: String,
      analysisId: String,
      manuscriptSha256: String,
      stageCount: Int
    ): String = sha256Hex("COMMIT_V1|$terminalHash|$analysisId|$manuscriptSha256|$stageCount")

    fun verifyChain(entries: List<AuditLogEntry>): Boolean {
      if (entries.isEmpty()) return false
      var expectedPrev = "GENESIS"
      entries.forEachIndexed { idx, entry ->
        if (entry.sequence != idx) return false
        if (entry.previousEntryHash != expectedPrev) return false
        val expectedPayloadHash = sha256Hex(entry.payloadSummary)
        if (entry.payloadSha256 != expectedPayloadHash) return false
        val expectedHash = computeEntryHash(
          sequence = entry.sequence,
          stageCode = entry.stageCode,
          eventType = entry.eventType,
          payloadSummary = entry.payloadSummary,
          payloadSha256 = entry.payloadSha256,
          previousEntryHash = entry.previousEntryHash,
          timestampMs = entry.timestampMs
        )
        if (entry.entryHash != expectedHash) return false
        expectedPrev = entry.entryHash
      }
      return true
    }

    fun verifyAuditLog(
      auditLogJson: String,
      commitment: String = "",
      expectedAnalysisId: String = "",
      expectedManuscriptSha256: String = "",
      expectedStageCount: Int = 9
    ): AuditLogVerificationResult {
      val trimmed = auditLogJson.trim()
      if (trimmed.isBlank()) {
        return AuditLogVerificationResult(
          status = AuditLogVerificationStatus.NOT_AVAILABLE,
          entryCount = 0,
          terminalHash = "",
          detail = "Audit log payload absent."
        )
      }
      val entries = runCatching {
        val arr = JSONArray(trimmed)
        buildList {
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return AuditLogVerificationResult(
              status = AuditLogVerificationStatus.TAMPERED,
              entryCount = i,
              terminalHash = "",
              detail = "Malformed audit entry at index $i."
            )
            add(
              AuditLogEntry(
                sequence = o.optInt("sequence", i),
                stageCode = o.optString("stageCode", ""),
                eventType = o.optString("eventType", ""),
                payloadSummary = o.optString("payloadSummary", ""),
                payloadSha256 = o.optString("payloadSha256", ""),
                previousEntryHash = o.optString("previousEntryHash", ""),
                entryHash = o.optString("entryHash", ""),
                timestampMs = o.optLong("timestampMs", 0L)
              )
            )
          }
        }
      }.getOrElse {
        return AuditLogVerificationResult(
          status = AuditLogVerificationStatus.TAMPERED,
          entryCount = 0,
          terminalHash = "",
          detail = "Unparseable audit log JSON."
        )
      }
      if (entries.isEmpty()) {
        return AuditLogVerificationResult(
          status = AuditLogVerificationStatus.NOT_AVAILABLE,
          entryCount = 0,
          terminalHash = "",
          detail = "Audit log contains zero entries."
        )
      }
      if (!verifyChain(entries)) {
        return AuditLogVerificationResult(
          status = AuditLogVerificationStatus.TAMPERED,
          entryCount = entries.size,
          terminalHash = entries.last().entryHash,
          detail = "SHA-256 hash chain verification failed."
        )
      }
      val terminalHash = entries.last().entryHash
      if (commitment.isBlank()) {
        return AuditLogVerificationResult(
          status = AuditLogVerificationStatus.LOCAL_CHAIN_ONLY,
          entryCount = entries.size,
          terminalHash = terminalHash,
          detail = "Local SHA-256 chain intact (${entries.size} entries), but no immutable server commitment is recorded."
        )
      }
      if (expectedAnalysisId.isBlank() || expectedManuscriptSha256.isBlank() || entries.size != expectedStageCount) {
        return AuditLogVerificationResult(
          status = AuditLogVerificationStatus.MISSING_COMMITMENT,
          entryCount = entries.size,
          terminalHash = terminalHash,
          detail = "Cannot verify commitment without manifest IDs and full $expectedStageCount stages (found ${entries.size})."
        )
      }
      val expectedCommitment = computeCommitmentHash(
        terminalHash = terminalHash,
        analysisId = expectedAnalysisId,
        manuscriptSha256 = expectedManuscriptSha256,
        stageCount = entries.size
      )
      return if (expectedCommitment == commitment.trim()) {
        AuditLogVerificationResult(
          status = AuditLogVerificationStatus.VERIFIED,
          entryCount = entries.size,
          terminalHash = terminalHash,
          detail = "SHA-256 chain and immutable commitment verified across ${entries.size} stages."
        )
      } else {
        AuditLogVerificationResult(
          status = AuditLogVerificationStatus.TAMPERED,
          entryCount = entries.size,
          terminalHash = terminalHash,
          detail = "Audit log terminal hash / stage count disagrees with persisted immutable commitment."
        )
      }
    }

    fun fromJson(raw: String): List<AuditLogEntry> {
      if (raw.isBlank()) return emptyList()
      return runCatching {
        val arr = JSONArray(raw)
        buildList {
          for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
              AuditLogEntry(
                sequence = o.optInt("sequence", i),
                stageCode = o.optString("stageCode", ""),
                eventType = o.optString("eventType", ""),
                payloadSummary = o.optString("payloadSummary", ""),
                payloadSha256 = o.optString("payloadSha256", ""),
                previousEntryHash = o.optString("previousEntryHash", ""),
                entryHash = o.optString("entryHash", ""),
                timestampMs = o.optLong("timestampMs", 0L)
              )
            )
          }
        }
      }.getOrDefault(emptyList())
    }
  }
}

data class ProviderRetentionCapability(
  val supportsRemoteDeletion: Boolean = false,
  val retentionDisclosure: String =
    "Local reference tracking only (supportsRemoteDeletion=false). Remote inference providers manage payload retention according to their own API policy."
)

/**
 * Phase 9 temporary payload reference cleanup contract.
 * Implementations MUST accurately disclose via [capability] whether remote provider deletion exists.
 */
interface RemotePayloadCleanupHook {
  val capability: ProviderRetentionCapability
    get() = ProviderRetentionCapability(supportsRemoteDeletion = false)

  fun registerPayloadHandle(handleId: String, expiresAtMs: Long)
  fun cleanupAll(reason: String): Int
  fun activeHandles(): List<String>
}

/**
 * Client-side local payload reference tracker. Clears in-memory references/handles created during
 * pipeline execution; does NOT delete payloads stored remotely by external LLM APIs.
 */
class LocalReferenceCleanupHook(
  override val capability: ProviderRetentionCapability = ProviderRetentionCapability(supportsRemoteDeletion = false)
) : RemotePayloadCleanupHook {
  private val handles = linkedMapOf<String, Long>()
  private val cleanupHistory = mutableListOf<String>()

  @Synchronized
  override fun registerPayloadHandle(handleId: String, expiresAtMs: Long) {
    if (handleId.isNotBlank()) {
      handles[handleId] = expiresAtMs
    }
  }

  @Synchronized
  override fun cleanupAll(reason: String): Int {
    val count = handles.size
    handles.clear()
    cleanupHistory.add("$reason:$count (localReferencesClearedOnly)")
    return count
  }

  @Synchronized
  override fun activeHandles(): List<String> = handles.keys.toList()

  @Synchronized
  fun cleanupHistory(): List<String> = cleanupHistory.toList()
}

data class RefereeCoverageMetrics(
  val pagesExtracted: Int,
  val totalPages: Int,
  val pagesAnalyzed: Int,
  val pagesOmitted: Int,
  val claimsDiscovered: Int,
  val claimsRetained: Int,
  val claimsReconstructed: Int,
  val claimsSkipped: Int,
  val unsupportedClaims: Int,
  val unresolvedClaims: Int,
  val sourceAnchorsVerified: Int,
  val interPassDisagreements: Int,
  val extractionCapApplied: Boolean,
  val claimCapApplied: Boolean,
  val standardizedLabel: String
) {
  fun toJson(): String {
    val obj = JSONObject()
    obj.put("pagesExtracted", pagesExtracted)
    obj.put("totalPages", totalPages)
    obj.put("pagesAnalyzed", pagesAnalyzed)
    obj.put("pagesOmitted", pagesOmitted)
    obj.put("claimsDiscovered", claimsDiscovered)
    obj.put("claimsRetained", claimsRetained)
    obj.put("claimsReconstructed", claimsReconstructed)
    obj.put("claimsSkipped", claimsSkipped)
    obj.put("unsupportedClaims", unsupportedClaims)
    obj.put("unresolvedClaims", unresolvedClaims)
    obj.put("sourceAnchorsVerified", sourceAnchorsVerified)
    obj.put("interPassDisagreements", interPassDisagreements)
    obj.put("extractionCapApplied", extractionCapApplied)
    obj.put("claimCapApplied", claimCapApplied)
    obj.put("standardizedLabel", standardizedLabel)
    return obj.toString()
  }

  companion object {
    const val LABEL_COMPLETE = "Complete quantitative coverage"
    const val LABEL_PARTIAL = "Partial quantitative coverage"
    const val LABEL_PRELIMINARY = "Preliminary appraisal"
    const val LABEL_INSUFFICIENT = "Evidence insufficient"
    const val LABEL_MANUAL_REQUIRED = "Manual verification required"

    fun fromJson(raw: String): RefereeCoverageMetrics? {
      if (raw.isBlank()) return null
      return runCatching {
        val o = JSONObject(raw)
        RefereeCoverageMetrics(
          pagesExtracted = o.optInt("pagesExtracted", 0),
          totalPages = o.optInt("totalPages", 0),
          pagesAnalyzed = o.optInt("pagesAnalyzed", 0),
          pagesOmitted = o.optInt("pagesOmitted", 0),
          claimsDiscovered = o.optInt("claimsDiscovered", 0),
          claimsRetained = o.optInt("claimsRetained", 0),
          claimsReconstructed = o.optInt("claimsReconstructed", 0),
          claimsSkipped = o.optInt("claimsSkipped", 0),
          unsupportedClaims = o.optInt("unsupportedClaims", 0),
          unresolvedClaims = o.optInt("unresolvedClaims", 0),
          sourceAnchorsVerified = o.optInt("sourceAnchorsVerified", 0),
          interPassDisagreements = o.optInt("interPassDisagreements", 0),
          extractionCapApplied = o.optBoolean("extractionCapApplied", false),
          claimCapApplied = o.optBoolean("claimCapApplied", false),
          standardizedLabel = o.optString("standardizedLabel", LABEL_PRELIMINARY)
        )
      }.getOrNull()
    }
  }
}

data class PassComparisonInput(
  val passName: String,
  val designClassification: String?,
  val primaryEndpoint: String?,
  val criticalVerdict: String?,
  val quantitativeFindingIds: Set<String> = emptySet()
)

data class RefereeAdjudicationInput(
  val analysisId: String,
  val manuscriptText: String,
  val replicationCodeText: String = "",
  val pdfVisionDigestSha256: String = "",
  val providerRoute: String,
  val modelId: String,
  val fallbackHistory: List<String> = emptyList(),
  val promptVersion: String = "v30.1",
  val rulesetVersion: String = "referee-2026.11",
  val appVersion: String = "1.0.0",
  val startedAtMs: Long,
  val completedAtMs: Long,
  val readiness: DocumentReadinessAssessment,
  val extractionConfidence: Int,
  val totalPages: Int,
  val pagesExtracted: Int,
  val pagesAnalyzed: Int,
  val omittedPages: List<Int> = emptyList(),
  val claimsDiscovered: Int = 0,
  val claimsRetained: Int = 0,
  val claimsReconstructed: Int = 0,
  val claimsSkipped: Int = 0,
  val unsupportedClaims: Int = 0,
  val unresolvedClaims: Int = 0,
  val extractionCapApplied: Boolean = false,
  val claimCapApplied: Boolean = false,
  val deterministicFindings: List<ReviewFinding> = emptyList(),
  val llmFindings: List<ReviewFinding> = emptyList(),
  val passComparisons: List<PassComparisonInput> = emptyList(),
  val quantitativeStatuses: List<QuantitativeFindingStatus> = emptyList(),
  val crossCheckChecklist: DiscrepancyCrossCheckChecklist = DiscrepancyCrossCheckChecklist(),
  val domainAppraisal: DomainAppraisalReport? = null,
  val unexpectedProviderFallback: Boolean = false,
  val consentVerified: Boolean = true,
  val consentDigestAtApproval: String = "",
  val isOutOfScopeDocument: Boolean = false
)

data class RefereeAdjudicationOutput(
  val decision: RefereeDecision,
  val stageRecords: List<PipelineStageRecord>,
  val deterministicFindingsPreserved: List<ReviewFinding>,
  val mergedFindings: List<ReviewFinding>,
  val interPassDisagreements: List<InterPassDisagreement>,
  val abstentionResult: AbstentionResult,
  val manifest: AnalysisManifest,
  val auditLogEntries: List<AuditLogEntry>,
  val auditLogJson: String,
  val auditLogChainValid: Boolean,
  val auditCommitment: String = "",
  val coverageMetrics: RefereeCoverageMetrics,
  val neverVerifiedReasons: List<String>
)

/**
 * Phase 8 centralized abstention gate.
 */
object AbstentionGate {

  fun evaluate(
    input: RefereeAdjudicationInput,
    disagreements: List<InterPassDisagreement> = emptyList()
  ): AbstentionResult {
    val reasons = mutableListOf<AbstentionReason>()

    if (!input.readiness.methodsPresent || !input.readiness.resultsPresent ||
      input.readiness.readiness == DocumentReadiness.EXTRACTION_FAILURE ||
      input.readiness.readiness == DocumentReadiness.UNSUPPORTED_FORMAT
    ) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.MISSING_METHODS_OR_RESULTS,
          whatIsMissing = "Complete Methods and/or Results sections (readiness=${input.readiness.readiness.name}).",
          whyItMatters = "Cannot evaluate design validity, confounding, or primary outcomes without Methods and Results.",
          blockedConclusion = "Verified scientific referee verdict blocked.",
          nextStepsForUser = input.readiness.requestedActions.ifEmpty {
            listOf("Upload the complete manuscript including Methods and Results.")
          }
        )
      )
    }

    if (!input.readiness.tablesAndFiguresDetectable) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.PRIMARY_TABLES_UNAVAILABLE,
          whatIsMissing = "Primary outcome tables or figure identifiers were not detected.",
          whyItMatters = "Numerical point estimates and intervals cannot be verified against source tables.",
          blockedConclusion = "Complete quantitative verification blocked.",
          nextStepsForUser = listOf("Ensure primary tables/figures are present as searchable text or bound PDF vision pages.")
        )
      )
    }

    if (!input.readiness.extractionQualityAcceptable || input.extractionConfidence < 4 || !input.readiness.searchableTextExists) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.POOR_OCR_OR_EXTRACTION,
          whatIsMissing = if (!input.readiness.searchableTextExists) {
            "Searchable text layer (scanned/image-only input; confidence=${input.extractionConfidence}/5)."
          } else {
            "High-fidelity searchable text extraction (confidence=${input.extractionConfidence}/5)."
          },
          whyItMatters = "Low OCR quality or missing text layer introduces character corruption and prevents exact quote verification.",
          blockedConclusion = "Verified quantitative and methodological verdict blocked.",
          nextStepsForUser = listOf("Provide a native text-searchable PDF, Word (.docx), or clean OCR transcript.")
        )
      )
    }

    val allFindings = input.deterministicFindings + input.llmFindings
    val verifiedWithoutAnchors = allFindings.filter {
      it.status == FindingStatus.VERIFIED &&
        (it.supportingEvidence.isEmpty() || it.supportingEvidence.all { a -> a.exactQuote.trim().length < 12 })
    }
    if (verifiedWithoutAnchors.isNotEmpty()) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.IRREPRODUCIBLE_SOURCE_ANCHORS,
          whatIsMissing = "${verifiedWithoutAnchors.size} verified finding(s) lack exact verifiable source anchors.",
          whyItMatters = "Findings without exact quotes, offsets, and document provenance cannot enter a verified verdict.",
          blockedConclusion = "Verified verdict blocked until unanchored findings are grounded or downgraded.",
          nextStepsForUser = listOf("Inspect unverified findings manually against the PDF source.")
        )
      )
    }

    if (!input.consentVerified || !input.readiness.visionPagesBound) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.PDF_OR_CONTENT_CHANGED_AFTER_CONSENT,
          whatIsMissing = "Valid cryptographic consent binding for exact manuscript text, code, and PDF page bitmaps.",
          whyItMatters = "Remote inference and vision inspection must never run or verify content that changed after approval.",
          blockedConclusion = "Analysis execution and verified verdict blocked pending re-consent.",
          nextStepsForUser = listOf("Review updated document checksums and re-confirm remote inference consent.")
        )
      )
    }

    val unknownEndpointOrPop = input.passComparisons.isNotEmpty() &&
      input.passComparisons.all { it.primaryEndpoint.isNullOrBlank() }
    if (unknownEndpointOrPop) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.CRITICAL_POPULATION_OR_ENDPOINT_UNKNOWN,
          whatIsMissing = "Unambiguous primary endpoint / target population classification across analysis passes.",
          whyItMatters = "Without a defined primary endpoint and population, effect-size and bias appraisal are underdetermined.",
          blockedConclusion = "Verified efficacy/credibility decision blocked.",
          nextStepsForUser = listOf("Verify the primary endpoint and study population manually.")
        )
      )
    }

    val multiInterpQuant = input.quantitativeStatuses.any {
      it == QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION ||
        it == QuantitativeFindingStatus.NOT_ASSESSABLE
    }
    if (multiInterpQuant) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.MULTI_INTERPRETATION_STATISTICAL_RESULT,
          whatIsMissing = "Unambiguous estimator scale/specification for one or more statistical results.",
          whyItMatters = "Discrepancies may reflect unreported covariates, bootstrap CIs, or rounding rather than mathematical error.",
          blockedConclusion = "Promotion of statistical discrepancy to confirmed error / verified verdict blocked.",
          nextStepsForUser = listOf("Cross-check regression model scale, CI method, and supplement tables.")
        )
      )
    }

    val materialSupplementOrCoverageGap = input.omittedPages.isNotEmpty() ||
      input.extractionCapApplied ||
      input.claimCapApplied ||
      input.claimsSkipped > 0 ||
      input.readiness.supplementStatus == SupplementStatus.ABSENT ||
      !input.readiness.referencesSeparated ||
      input.readiness.lowTextPageCount > 0
    if (materialSupplementOrCoverageGap) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.CAPPED_MANUSCRIPT_COVERAGE,
          whatIsMissing = buildList {
            if (input.omittedPages.isNotEmpty()) add("${input.omittedPages.size} omitted pages")
            if (input.claimsSkipped > 0 || input.claimCapApplied) add("${input.claimsSkipped} skipped claims")
            if (input.readiness.supplementStatus == SupplementStatus.ABSENT) add("referenced supplementary material absent")
            if (!input.readiness.referencesSeparated) add("references boundary unclear")
            if (input.readiness.lowTextPageCount > 0) add("${input.readiness.lowTextPageCount} sparse/low-text pages")
          }.joinToString(", ").ifBlank { "Full manuscript coverage" },
          whyItMatters = "Truncated pages, absent supplements, or unseparated references can hide primary limitations, errata, or sensitivity models.",
          blockedConclusion = "Claim of a complete verified audit is blocked.",
          nextStepsForUser = listOf("Supply complete manuscript, attachments/supplements, or inspect omitted sections separately.")
        )
      )
    }

    if (input.unexpectedProviderFallback) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.UNCERTAIN_PROVIDER_OR_MODEL_FALLBACK,
          whatIsMissing = "Deterministic confirmation of authorized AI provider/model execution (${input.providerRoute}).",
          whyItMatters = "Silent cross-provider or vision-dropping fallback invalidates expected inference guarantees.",
          blockedConclusion = "Verified verdict blocked due to uncertain model/provider fallback.",
          nextStepsForUser = listOf("Re-run with an explicitly authorized provider route and inspect fallback history.")
        )
      )
    }

    val domain = input.domainAppraisal
    if (domain == null ||
      domain.domain == AppraisalDomain.DESIGN_UNCERTAIN ||
      domain.restrictsVerdictDueToUncertainDesign ||
      domain.rubricCriteria.isEmpty() ||
      disagreements.any { it.unresolved }
    ) {
      reasons.add(
        AbstentionReason(
          trigger = AbstentionTrigger.MISSING_DOMAIN_RUBRIC_OR_DESIGN_UNCERTAIN,
          whatIsMissing = if (disagreements.any { it.unresolved }) {
            "Consensus across independent passes (${disagreements.size} unresolved disagreement(s))."
          } else {
            "Confirmed study design classification and applied domain methodological rubric."
          },
          whyItMatters = "A weighted score cannot substitute for design-specific bias appraisal (RoB 2, ROBINS-I, QUADAS-2, etc.).",
          blockedConclusion = "Verified verdict blocked; output restricted to provisional appraisal.",
          nextStepsForUser = listOf("Confirm study design or resolve inter-pass disagreements manually.")
        )
      )
    }

    val hardBlock = reasons.any {
      it.trigger == AbstentionTrigger.PDF_OR_CONTENT_CHANGED_AFTER_CONSENT ||
        input.readiness.readiness == DocumentReadiness.EXTRACTION_FAILURE ||
        input.readiness.readiness == DocumentReadiness.UNSUPPORTED_FORMAT
    }
    val primary = reasons.firstOrNull()
    return AbstentionResult(
      shouldAbstainFromVerifiedVerdict = reasons.isNotEmpty(),
      hardBlockAllVerdicts = hardBlock,
      triggeredReasons = reasons,
      primaryWhatIsMissing = primary?.whatIsMissing.orEmpty(),
      primaryWhyItMatters = primary?.whyItMatters.orEmpty(),
      primaryBlockedConclusion = primary?.blockedConclusion.orEmpty(),
      combinedNextSteps = reasons.flatMap { it.nextStepsForUser }.distinct()
    )
  }
}
