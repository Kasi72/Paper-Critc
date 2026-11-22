package com.example.data.ai.semantic

import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.AuditSubDesign
import java.util.Locale

/**
 * Phase 8 — Trust, Abstention & Semantic Validity Models.
 *
 * Ensures the statistical & epistemic auditor knows exactly what it knows, what it inferred,
 * what it cannot determine, and when a critique is aimed at the wrong estimand or population.
 */

// ============================================================================
// #1. EXTRACTION FAILURE VS. MANUSCRIPT OMISSION
// ============================================================================
enum class UnsupportedCause(val label: String, val isExtractionFailure: Boolean) {
  ABSENT_FROM_MANUSCRIPT("absent from manuscript", false),
  PRESENT_BUT_UNREADABLE("present but unreadable", true),
  AMBIGUOUS_NOTATION("ambiguous notation", true),
  OUTSIDE_COVERAGE("outside coverage", true),
  INSUFFICIENT_INPUTS("insufficient inputs", false)
}

data class StructuredUnsupportedItem(
  val targetCheck: String,
  val cause: UnsupportedCause,
  val detail: String,
  val pageNumber: Int = 0
) {
  fun formatLine(): String =
    "Unsupported [$targetCheck] because: ${cause.label} — $detail" +
      if (cause.isExtractionFailure) " (Extraction/parsing limitation — NOT a manuscript reporting failure.)" else ""
}

// ============================================================================
// #2. CLASSIFIER-CONFIDENCE GATE
// ============================================================================
data class DesignCandidate(
  val design: AuditSubDesign,
  val label: String,
  val confidence: Double,
  val evidenceTerms: List<String>
)

data class DesignClassificationResult(
  val primaryDesign: AuditSubDesign,
  val primaryLabel: String,
  val primaryConfidence: Double,
  val alternatives: List<DesignCandidate>,
  val evidence: List<String>,
  val abstained: Boolean,
  val suppressedChecks: List<String>
) {
  fun toSummaryBlock(): String = buildString {
    appendLine(
      String.format(
        Locale.US,
        "Primary classification: %s, confidence %.2f (Evidence: %s)",
        primaryLabel,
        primaryConfidence,
        evidence.take(5).joinToString(", ").ifBlank { "sparse structural cues" }
      )
    )
    alternatives.take(2).forEach { alt ->
      appendLine(String.format(Locale.US, "Alternative: %s, confidence %.2f", alt.label, alt.confidence))
    }
    if (abstained) {
      appendLine("Classifier abstention active: low confidence or competing plausible designs.")
    }
    if (suppressedChecks.isNotEmpty()) {
      appendLine("Design-sensitive checks suppressed: ${suppressedChecks.joinToString(", ")}")
    }
  }.trim()
}

// ============================================================================
// #3. ANTI-DOGMA ESTIMAND CONTRACT
// ============================================================================
data class EstimandMethodAssessment(
  val methodTopic: String,
  val targetEstimand: String,
  val requiredAssumptions: String,
  val assumptionsPlausible: String,
  val alternativeAnswersSameQuestion: Boolean,
  val verdictNote: String
)

// ============================================================================
// #4. ASSUMPTION-EXPLICIT CALCULATOR CONTRACT
// ============================================================================
data class CalculatorAssumptionContract(
  val calculatorScope: String,
  val assumptionsSatisfied: List<String>,
  val assumptionsUnknown: List<String>,
  val alternativeValidInterpretations: List<String>
) {
  fun toCompactString(): String = buildString {
    if (assumptionsSatisfied.isNotEmpty()) append("Satisfied: ${assumptionsSatisfied.joinToString("; ")}. ")
    if (assumptionsUnknown.isNotEmpty()) append("Unknown: ${assumptionsUnknown.joinToString("; ")}. ")
    if (alternativeValidInterpretations.isNotEmpty()) append("Alt valid interpretations: ${alternativeValidInterpretations.joinToString("; ")}.")
  }.trim()
}

// ============================================================================
// #5. COMPOUND EVIDENCE-TO-CLAIM KEY
// ============================================================================
data class CompoundClaimKey(
  val population: String = "unspecified",
  val outcome: String = "unspecified",
  val timePoint: String = "unspecified",
  val estimator: String = "unspecified",
  val subgroup: String = "overall",
  val adjustmentStatus: AdjustmentStatus = AdjustmentStatus.UNSPECIFIED
) {
  /**
   * Two claims may ONLY be compared for cross-section contradiction when their compound keys
   * are compatible (never compare two outcomes with the same name across different populations,
   * subgroups, time points, estimators, or adjustment states).
   */
  fun isCompatibleForComparison(other: CompoundClaimKey): Boolean {
    if (population != "unspecified" && other.population != "unspecified" &&
      !population.equals(other.population, ignoreCase = true)
    ) return false
    if (outcome != "unspecified" && other.outcome != "unspecified" &&
      !outcome.equals(other.outcome, ignoreCase = true)
    ) return false
    if (timePoint != "unspecified" && other.timePoint != "unspecified" &&
      !timePoint.equals(other.timePoint, ignoreCase = true)
    ) return false
    if (subgroup != "overall" && other.subgroup != "overall" &&
      !subgroup.equals(other.subgroup, ignoreCase = true)
    ) return false
    if (subgroup != other.subgroup && (subgroup != "overall" || other.subgroup != "overall")) {
      return false
    }
    if (adjustmentStatus != AdjustmentStatus.UNSPECIFIED && other.adjustmentStatus != AdjustmentStatus.UNSPECIFIED &&
      adjustmentStatus != other.adjustmentStatus
    ) return false
    if (estimator != "unspecified" && other.estimator != "unspecified" &&
      !estimator.equals(other.estimator, ignoreCase = true)
    ) return false
    return true
  }

  fun toKeyLabel(): String =
    "pop=$population | out=$outcome | t=$timePoint | est=$estimator | sub=$subgroup | adj=${adjustmentStatus.name}"
}

// ============================================================================
// #6. DENOMINATOR PROVENANCE GRAPH
// ============================================================================
enum class DenominatorUnitLevel(val label: String) {
  PERSON("person-level"),
  EYE("eye-level"),
  LESION("lesion-level"),
  VISIT("visit-level"),
  SAMPLE("sample-level"),
  EVENT("event-level"),
  STUDY("study-level"),
  EFFECT_ESTIMATE("effect-estimate-level"),
  UNSPECIFIED("unit-unspecified")
}

enum class AnalysisPopulationType(val label: String) {
  INTENTION_TO_TREAT("intention-to-treat"),
  MODIFIED_ITT("modified-ITT"),
  PER_PROTOCOL("per-protocol"),
  AS_TREATED("as-treated"),
  COMPLETE_CASE("complete-case"),
  UNSPECIFIED("unspecified")
}

data class DenominatorTransitionEdge(
  val fromStage: String, // eligible -> enrolled -> randomized -> treated -> followed -> analyzed
  val toStage: String,
  val fromN: Int,
  val toN: Int,
  val nLost: Int,
  val exclusionReason: String,
  val unitLevel: DenominatorUnitLevel,
  val populationType: AnalysisPopulationType,
  val page: Int = 0
)

data class DenominatorProvenanceGraph(
  val unitLevelsDetected: Set<DenominatorUnitLevel>,
  val populationType: AnalysisPopulationType,
  val stages: Map<String, Int>,
  val transitions: List<DenominatorTransitionEdge>,
  val unitLevelConflictWarning: String? = null
) {
  val hasMixedUnits: Boolean get() = unitLevelsDetected.filter { it != DenominatorUnitLevel.UNSPECIFIED }.size >= 2
}

// ============================================================================
// #7. MULTIPLICITY-FAMILY UNCERTAINTY
// ============================================================================
data class MultiplicityFamilyAssessment(
  val familyReconstructable: Boolean,
  val potentialInflationRisk: Boolean,
  val correctedPValuesComputable: Boolean,
  val observedTestCount: Int,
  val modeledDimensions: List<String>, // outcome, time point, treatment contrast, subgroup, stage, primary/exploratory, prespecified/post-hoc
  val statusMessage: String
)

// ============================================================================
// #8. ANALYST DEGREES-OF-FREEDOM AUDIT
// ============================================================================
data class FlexibilityDimensionCheck(
  val dimension: String,
  val riskLevel: String, // LOCKED, MODERATE, ELEVATED
  val evidenceNote: String
)

data class ResearcherDegreesOfFreedomReport(
  val dimensions: List<FlexibilityDimensionCheck>,
  val elevatedCount: Int,
  val summary: String
)

// ============================================================================
// #9. SUBSTANTIVE MISSING-DATA AUDIT
// ============================================================================
data class MissingDataAuditReport(
  val missingnessByVariableReported: Boolean,
  val missingnessByGroupReported: Boolean,
  val missingnessByOutcomeReported: Boolean,
  val reasonsForMissingnessReported: Boolean,
  val differentialLossEvaluated: Boolean,
  val multipleImputationUsed: Boolean,
  val imputationIncludesOutcomeTreatmentAux: Boolean,
  val mnarSensitivityConducted: Boolean,
  val substantiveAdequacy: String, // ADEQUATE, PARTIAL_MI_UNDERSPECIFIED, INADEQUATE_COMPLETE_CASE, NOT_REPORTED
  val summary: String
)

// ============================================================================
// #10. NEGATIVE-CONTROL & FALSIFICATION CHECKS
// ============================================================================
data class FalsificationCheckReport(
  val applicableToDesign: Boolean,
  val negativeControlOutcome: Boolean,
  val negativeControlExposure: Boolean,
  val placeboDates: Boolean,
  val preTreatmentOutcome: Boolean,
  val falsificationEndpoint: Boolean,
  val alternativeControlGroup: Boolean,
  val summary: String
) {
  val totalPresent: Int
    get() = listOf(
      negativeControlOutcome,
      negativeControlExposure,
      placeboDates,
      preTreatmentOutcome,
      falsificationEndpoint,
      alternativeControlGroup
    ).count { it }
}

// ============================================================================
// #11. EFFECT-DIRECTION & SCALE SANITY CHECKS
// ============================================================================
data class EffectScaleSanityFinding(
  val checkCode: String, // RATIO_AS_ADDITIVE, OR_AS_RR, RRR_WITHOUT_BASELINE, WIDE_CI_NO_ASSOCIATION, NONSIG_AS_EQUIVALENCE, SUBGROUP_SEPARATE_P, INTERACTION_WITHOUT_TEST, P_ORDERING_AS_MAGNITUDE
  val title: String,
  val detail: String,
  val quote: String,
  val pageNumber: Int = 0
)

// ============================================================================
// #12. PROPER ABSTENTION POLICY
// ============================================================================
data class AbstentionReason(
  val code: String, // UNCLEAR_ESTIMAND, UNCERTAIN_DESIGN, MISSING_TABLES, ABSTRACT_ONLY, UNREADABLE_FIGURES, AMBIGUOUS_UNIT_OF_ANALYSIS, UNOBSERVED_ASSUMPTION, ROUNDED_VALUES_ONLY
  val detail: String
)

data class AbstentionAssessment(
  val shouldAbstainFromStrongConclusions: Boolean,
  val reasons: List<AbstentionReason>,
  val policyStatement: String
)

// ============================================================================
// UNIFIED PHASE 8 SEMANTIC VALIDITY REPORT
// ============================================================================
data class SemanticValidityReport(
  val designClassification: DesignClassificationResult,
  val structuredUnsupported: List<StructuredUnsupportedItem>,
  val estimandAssessments: List<EstimandMethodAssessment>,
  val denominatorGraph: DenominatorProvenanceGraph,
  val multiplicityAssessment: MultiplicityFamilyAssessment,
  val researcherDofReport: ResearcherDegreesOfFreedomReport,
  val missingDataAudit: MissingDataAuditReport,
  val falsificationChecks: FalsificationCheckReport,
  val effectScaleFindings: List<EffectScaleSanityFinding>,
  val abstentionAssessment: AbstentionAssessment,
  val ocrNoiseDetected: Boolean = false,
  val duplicatedColumnsRemoved: Int = 0
) {
  fun toPromptBlock(): String = buildString {
    appendLine("[PHASE 8 — TRUST, ABSTENTION & SEMANTIC VALIDITY GATE]")
    appendLine(designClassification.toSummaryBlock())
    if (abstentionAssessment.shouldAbstainFromStrongConclusions) {
      appendLine("ABSTENTION POLICY ACTIVE: ${abstentionAssessment.policyStatement}")
      abstentionAssessment.reasons.forEach { r ->
        appendLine("  * [${r.code}] ${r.detail}")
      }
    }
    if (structuredUnsupported.isNotEmpty()) {
      appendLine("Unsupported Inputs Breakdown (Extraction Failure vs. Manuscript Omission):")
      structuredUnsupported.take(6).forEach { u -> appendLine("  * ${u.formatLine()}") }
    }
    appendLine("Denominator Provenance Graph: unit(s)=${denominatorGraph.unitLevelsDetected.joinToString(",") { it.label }}, pop=${denominatorGraph.populationType.label}, transitions=${denominatorGraph.transitions.size}")
    denominatorGraph.unitLevelConflictWarning?.let { appendLine("  * UNIT CONFLICT WARNING: $it") }
    appendLine("Multiplicity-Family Assessment: ${multiplicityAssessment.statusMessage}")
    if (effectScaleFindings.isNotEmpty()) {
      appendLine("Effect-Direction & Scale Sanity Flags (${effectScaleFindings.size}):")
      effectScaleFindings.take(6).forEach { f ->
        appendLine("  * [${f.checkCode}] ${f.title}: ${f.detail}")
      }
    }
    appendLine("Missing-Data Substantive Audit: ${missingDataAudit.summary}")
    appendLine("Researcher Degrees of Freedom (${researcherDofReport.elevatedCount} elevated): ${researcherDofReport.summary}")
    if (falsificationChecks.applicableToDesign) {
      appendLine("Falsification / Negative-Control Checks: ${falsificationChecks.summary}")
    }
    if (estimandAssessments.isNotEmpty()) {
      appendLine("Estimand & Non-Dogmatic Method Checks:")
      estimandAssessments.take(4).forEach { e ->
        appendLine("  * ${e.methodTopic} [Estimand: ${e.targetEstimand}]: ${e.verdictNote}")
      }
    }
  }.trim()

  fun toAuditMarkdown(): String = buildString {
    appendLine("[PHASE 8 — SEMANTIC VALIDITY, CLASSIFIER CONFIDENCE & ABSTENTION REPORT]")
    appendLine(designClassification.toSummaryBlock())

    if (abstentionAssessment.shouldAbstainFromStrongConclusions) {
      appendLine()
      appendLine("[ABSTENTION POLICY TRIGGERED]")
      appendLine(abstentionAssessment.policyStatement)
      abstentionAssessment.reasons.forEach { r ->
        appendLine("• [${r.code}] ${r.detail}")
      }
    }

    if (structuredUnsupported.isNotEmpty()) {
      appendLine()
      appendLine("[EXTRACTION FAILURE VS. MANUSCRIPT OMISSION CLASSIFICATION]")
      structuredUnsupported.forEach { u ->
        appendLine("• ${u.formatLine()}")
      }
    }

    appendLine()
    appendLine("[DENOMINATOR PROVENANCE GRAPH (${denominatorGraph.populationType.label} • ${denominatorGraph.unitLevelsDetected.joinToString(", ") { it.label }})]")
    if (denominatorGraph.transitions.isNotEmpty()) {
      appendLine("| From Stage | To Stage | From N | To N | Lost | Unit Level | Population | Reason |")
      appendLine("|---|---|---|---|---|---|---|---|")
      denominatorGraph.transitions.forEach { t ->
        appendLine("| ${t.fromStage} | ${t.toStage} | ${t.fromN} | ${t.toN} | ${t.nLost} | ${t.unitLevel.label} | ${t.populationType.label} | ${t.exclusionReason.replace("|", "/")} |")
      }
    } else {
      appendLine("• Stages captured: ${denominatorGraph.stages.entries.joinToString(" → ") { "${it.key}=${it.value}" }.ifBlank { "none reconstructed" }}")
    }
    denominatorGraph.unitLevelConflictWarning?.let { appendLine("• Unit-of-Analysis Alert: $it") }

    appendLine()
    appendLine("[MULTIPLICITY-FAMILY UNCERTAINTY]")
    appendLine("• ${multiplicityAssessment.statusMessage}")

    if (effectScaleFindings.isNotEmpty()) {
      appendLine()
      appendLine("[EFFECT-DIRECTION & SCALE SANITY CHECKS (${effectScaleFindings.size})]")
      effectScaleFindings.forEach { f ->
        val pg = if (f.pageNumber > 0) " (Page ${f.pageNumber})" else ""
        appendLine("• [${f.checkCode}]$pg ${f.title}: ${f.detail}")
      }
    }

    appendLine()
    appendLine("[SUBSTANTIVE MISSING-DATA AUDIT (${missingDataAudit.substantiveAdequacy})]")
    appendLine("• ${missingDataAudit.summary}")

    appendLine()
    appendLine("[ANALYST DEGREES-OF-FREEDOM AUDIT (${researcherDofReport.elevatedCount}/11 Elevated Flexibility Dimensions)]")
    appendLine("• ${researcherDofReport.summary}")

    if (falsificationChecks.applicableToDesign) {
      appendLine()
      appendLine("[NEGATIVE-CONTROL & FALSIFICATION CHECKS (${falsificationChecks.totalPresent}/6 Present)]")
      appendLine("• ${falsificationChecks.summary}")
    }

    if (estimandAssessments.isNotEmpty()) {
      appendLine()
      appendLine("[ESTIMAND-FIRST NON-DOGMATIC METHOD CHECKS]")
      estimandAssessments.forEach { e ->
        appendLine("• ${e.methodTopic} | Estimand: ${e.targetEstimand} | Same question? ${if (e.alternativeAnswersSameQuestion) "Yes" else "No (different estimand)"} — ${e.verdictNote}")
      }
    }
  }.trim()
}
