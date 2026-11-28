package com.example.data.ai.referee

import com.example.data.model.EvidenceAnchor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical Phase 1 typed scientific claim model.
 * Every substantive scientific claim evaluated by the autonomous referee carries explicit
 * PICO, estimand, quantitative magnitude, interval, timing, adjustment, direction, polarity,
 * anchor links, extraction confidence, and unresolved fields.
 */
data class ScientificProposition(
  val id: String,
  val population: String?,
  val interventionOrExposure: String?,
  val comparator: String?,
  val outcome: String?,
  val estimand: String?,
  val effectMeasure: String?,
  val estimate: Double?,
  val lowerBound: Double?,
  val upperBound: Double?,
  val confidenceLevel: Double?,
  val pValue: Double?,
  val timePoint: String?,
  val analysisPopulation: String?,
  val adjustmentStatus: String?,
  val direction: String?,
  val polarity: String?,
  val sourceAnchorIds: List<String>,
  val extractionConfidence: Int,
  val unresolvedFields: List<String>
)

/**
 * Canonical typed source anchor with full coordinate, extraction method, and SHA-256 checksum.
 */
data class TypedEvidenceAnchor(
  val documentId: String,
  val pageNumber: Int,
  val section: String?,
  val startOffset: Int?,
  val endOffset: Int?,
  val exactQuote: String,
  val boundingBox: List<Float>? = null,
  val extractionMethod: String,
  val sourceChecksum: String
) {
  fun toEvidenceAnchor(): EvidenceAnchor = EvidenceAnchor(
    documentId = documentId,
    pageNumber = pageNumber,
    section = section,
    exactQuote = exactQuote,
    startOffset = startOffset,
    endOffset = endOffset,
    boundingBox = boundingBox,
    extractionMethod = extractionMethod,
    sourceChecksum = sourceChecksum
  )

  companion object {
    fun fromEvidenceAnchor(
      anchor: EvidenceAnchor,
      defaultExtractionMethod: String = "TextCoordinateMatch",
      defaultChecksum: String = ""
    ): TypedEvidenceAnchor = TypedEvidenceAnchor(
      documentId = anchor.documentId,
      pageNumber = anchor.pageNumber,
      section = anchor.section,
      startOffset = anchor.startOffset,
      endOffset = anchor.endOffset,
      exactQuote = anchor.exactQuote,
      boundingBox = anchor.boundingBox,
      extractionMethod = anchor.extractionMethod.ifBlank { defaultExtractionMethod },
      sourceChecksum = anchor.sourceChecksum.ifBlank { defaultChecksum }
    )
  }
}

/**
 * Canonical Phase 2 document readiness states.
 */
enum class DocumentReadiness(val displayLabel: String) {
  READY("Ready for full referee audit"),
  READY_WITH_LIMITATIONS("Ready with limitations"),
  INCOMPLETE("Incomplete manuscript — preliminary appraisal only"),
  EXTRACTION_FAILURE("Extraction failure"),
  UNSUPPORTED_FORMAT("Unsupported document format")
}

/**
 * Canonical Phase 2 supplement availability status.
 */
enum class SupplementStatus(val displayLabel: String) {
  PRESENT("Supplement present"),
  ABSENT("Supplement referenced but absent"),
  UNKNOWN("Supplement status unknown")
}

data class DocumentReadinessAssessment(
  val readiness: DocumentReadiness,
  val searchableTextExists: Boolean,
  val pageCountPlausible: Boolean,
  val methodsPresent: Boolean,
  val resultsPresent: Boolean,
  val tablesAndFiguresDetectable: Boolean,
  val referencesSeparated: Boolean,
  val extractionQualityAcceptable: Boolean,
  val visionPagesBound: Boolean,
  val supplementStatus: SupplementStatus,
  val reasons: List<String>,
  val requestedActions: List<String>
) {
  val allowsVerifiedVerdict: Boolean
    get() = readiness == DocumentReadiness.READY || readiness == DocumentReadiness.READY_WITH_LIMITATIONS

  val allowsPreliminaryAppraisalOnly: Boolean
    get() = readiness == DocumentReadiness.INCOMPLETE && searchableTextExists

  fun toJson(): String {
    val o = JSONObject()
    o.put("readiness", readiness.name)
    o.put("searchableTextExists", searchableTextExists)
    o.put("pageCountPlausible", pageCountPlausible)
    o.put("methodsPresent", methodsPresent)
    o.put("resultsPresent", resultsPresent)
    o.put("tablesAndFiguresDetectable", tablesAndFiguresDetectable)
    o.put("referencesSeparated", referencesSeparated)
    o.put("extractionQualityAcceptable", extractionQualityAcceptable)
    o.put("visionPagesBound", visionPagesBound)
    o.put("supplementStatus", supplementStatus.name)
    o.put("reasons", JSONArray(reasons))
    o.put("requestedActions", JSONArray(requestedActions))
    return o.toString()
  }

  companion object {
    fun fromJson(raw: String): DocumentReadinessAssessment? {
      if (raw.isBlank()) return null
      return runCatching {
        val o = JSONObject(raw)
        val reasonsArr = o.optJSONArray("reasons")
        val actionsArr = o.optJSONArray("requestedActions")
        DocumentReadinessAssessment(
          readiness = DocumentReadiness.entries.firstOrNull {
            it.name.equals(o.optString("readiness"), ignoreCase = true)
          } ?: DocumentReadiness.INCOMPLETE,
          searchableTextExists = o.optBoolean("searchableTextExists", false),
          pageCountPlausible = o.optBoolean("pageCountPlausible", false),
          methodsPresent = o.optBoolean("methodsPresent", false),
          resultsPresent = o.optBoolean("resultsPresent", false),
          tablesAndFiguresDetectable = o.optBoolean("tablesAndFiguresDetectable", false),
          referencesSeparated = o.optBoolean("referencesSeparated", false),
          extractionQualityAcceptable = o.optBoolean("extractionQualityAcceptable", false),
          visionPagesBound = o.optBoolean("visionPagesBound", true),
          supplementStatus = SupplementStatus.entries.firstOrNull {
            it.name.equals(o.optString("supplementStatus"), ignoreCase = true)
          } ?: SupplementStatus.UNKNOWN,
          reasons = buildList {
            if (reasonsArr != null) {
              for (i in 0 until reasonsArr.length()) {
                reasonsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
              }
            }
          },
          requestedActions = buildList {
            if (actionsArr != null) {
              for (i in 0 until actionsArr.length()) {
                actionsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
              }
            }
          }
        )
      }.getOrNull()
    }
  }
}

/**
 * Canonical Phase 3 explicit 9-stage autonomous referee pipeline identifiers.
 */
enum class PipelineStageId(val stageOrder: Int, val stageCode: String, val displayName: String) {
  STAGE_1_DOCUMENT_INGESTION(1, "S1_INGEST", "Document ingestion & extraction"),
  STAGE_2_STRUCTURE_DETECTION(2, "S2_STRUCT", "Document structure detection"),
  STAGE_3_CLAIM_INVENTORY(3, "S3_CLAIMS", "Claim inventory"),
  STAGE_4_STATISTICAL_RECONSTRUCTION(4, "S4_STATS", "Statistical reconstruction"),
  STAGE_5_METHODOLOGICAL_APPRAISAL(5, "S5_METHODS", "Methodological appraisal"),
  STAGE_6_SOURCE_VERIFICATION(6, "S6_VERIFY", "Source verification"),
  STAGE_7_CONTRADICTION_ANALYSIS(7, "S7_CONTRA", "Contradiction analysis"),
  STAGE_8_SEVERITY_ADJUDICATION(8, "S8_ADJUD", "Severity adjudication"),
  STAGE_9_FINAL_REPORT_GENERATION(9, "S9_REPORT", "Final report generation")
}

data class PipelineStageRecord(
  val stageId: PipelineStageId,
  val stageOrder: Int = stageId.stageOrder,
  val inputsSummary: String,
  val inputsHash: String,
  val outputsSummary: String,
  val outputsHash: String,
  val version: String,
  val assumptions: List<String> = emptyList(),
  val failures: List<String> = emptyList(),
  val deterministicImmutable: Boolean = false,
  val executedAtMs: Long = System.currentTimeMillis()
)

data class InterPassDisagreement(
  val disagreementId: String,
  val fieldName: String,
  val passAName: String,
  val passAValue: String,
  val passBName: String,
  val passBValue: String,
  val severity: String = "MAJOR",
  val resolutionNote: String = "Explicitly preserved as unresolved inter-pass disagreement (never silently overwritten).",
  val unresolved: Boolean = true
)

/**
 * Canonical Phase 4 scientific entailment statuses.
 */
enum class EntailmentStatus {
  ENTAILED,
  CONTRADICTED,
  PARTIALLY_SUPPORTED,
  UNRESOLVED,
  NOT_ENTAILED
}

data class EntailmentAssessment(
  val status: EntailmentStatus,
  val claimProposition: ScientificProposition,
  val evidenceProposition: ScientificProposition,
  val mismatchedFields: List<String>,
  val unresolvedCriticalFields: List<String>,
  val exactQuoteMatched: Boolean,
  val reason: String
)
