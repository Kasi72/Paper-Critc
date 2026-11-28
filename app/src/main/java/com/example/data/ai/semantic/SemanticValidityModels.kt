package com.example.data.ai.semantic

import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.AuditSubDesign
import com.example.util.DossierFormattingEngine
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
// #2. CALIBRATED CLASSIFIER-CONFIDENCE GATE (MUTUALLY EXCLUSIVE DESIGNS)
// ============================================================================
enum class CalibratedStudyDesign(val displayName: String, val mappedSubDesign: AuditSubDesign) {
  RCT("Randomized Controlled Trial (RCT)", AuditSubDesign.RCT),
  PROSPECTIVE_COHORT("Prospective Observational Cohort", AuditSubDesign.OBSERVATIONAL_COHORT),
  RETROSPECTIVE_COHORT("Retrospective Observational Cohort", AuditSubDesign.OBSERVATIONAL_COHORT),
  CASE_CONTROL("Case-Control Study", AuditSubDesign.OBSERVATIONAL_COHORT),
  CROSS_SECTIONAL("Cross-Sectional Survey / Prevalence Study", AuditSubDesign.OBSERVATIONAL_COHORT),
  SURVIVAL_TIME_TO_EVENT("Survival / Time-to-Event Study", AuditSubDesign.SURVIVAL_ANALYSIS),
  DIAGNOSTIC_ACCURACY("Diagnostic Accuracy / Validation Study", AuditSubDesign.DIAGNOSTIC_ACCURACY),
  SYSTEMATIC_REVIEW_NARRATIVE("Systematic Review (Qualitative / Narrative)", AuditSubDesign.SYSTEMATIC_REVIEW),
  META_ANALYSIS("Quantitative Meta-Analysis", AuditSubDesign.META_ANALYSIS),
  CAUSAL_ECONOMETRIC_PANEL("Causal Econometric / Quasi-Experimental Study", AuditSubDesign.ECONOMETRIC_PANEL),
  DESCRIPTIVE_PANEL("Descriptive Longitudinal / Panel Study", AuditSubDesign.ECONOMETRIC_PANEL),
  ML_PREDICTION_BENCHMARK("Machine Learning / Predictive Benchmark", AuditSubDesign.ML_BENCHMARK),
  FORMAL_MATHEMATICAL_PROOF("Formal Mathematical Theorem / Proof", AuditSubDesign.FORMAL_THEOREM),
  ALGORITHMIC_THEORY("Algorithmic / Complexity Theory Paper", AuditSubDesign.FORMAL_THEOREM),
  UNKNOWN("Unknown / Conflicting Study Design", AuditSubDesign.GENERAL_EMPIRICAL)
}

data class DesignCandidate(
  val design: AuditSubDesign,
  val label: String,
  val confidence: Double,
  val evidenceTerms: List<String>,
  val calibratedDesign: CalibratedStudyDesign = CalibratedStudyDesign.UNKNOWN
)

data class DesignClassificationResult(
  val primaryDesign: AuditSubDesign,
  val primaryLabel: String,
  val primaryConfidence: Double,
  val alternatives: List<DesignCandidate>,
  val evidence: List<String>,
  val abstained: Boolean,
  val suppressedChecks: List<String>,
  val calibratedDesign: CalibratedStudyDesign = CalibratedStudyDesign.UNKNOWN,
  val margin: Double = 0.0,
  val isCalibratedProbability: Boolean = true,
  val calibrationMethod: String = "Temperature-scaled softmax (T=1.65) validated on labeled design corpus"
) {
  fun toSummaryBlock(): String = buildString {
    appendLine(
      String.format(
        Locale.US,
        "Primary classification: %s [%s], calibrated probability %.2f (margin %.2f; Evidence: %s)",
        primaryLabel,
        calibratedDesign.name,
        primaryConfidence,
        margin,
        evidence.take(5).joinToString(", ").ifBlank { "sparse structural cues" }
      )
    )
    alternatives.take(2).forEach { alt ->
      appendLine(String.format(Locale.US, "Alternative: %s [%s], probability %.2f", alt.label, alt.calibratedDesign.name, alt.confidence))
    }
    if (abstained) {
      appendLine("Classifier abstention active: low calibrated probability, narrow margin, or conflicting design evidence.")
    }
    if (suppressedChecks.isNotEmpty()) {
      appendLine("Design-sensitive checks suppressed: ${suppressedChecks.joinToString(", ")}")
    }
  }.trim()
}

data class LabeledDesignExample(
  val exampleId: String,
  val text: String,
  val expectedDesign: CalibratedStudyDesign
)

data class ClassifierCalibrationReport(
  val totalExamples: Int,
  val overallAccuracy: Double,
  val highConfidenceCount: Int,
  val highConfidenceErrorCount: Int,
  val highConfidenceErrorRate: Double,
  val abstentionRate: Double,
  val expectedCalibrationError: Double
)

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
  val alternativeValidInterpretations: List<String>,
  val inputs: String = "",
  val formula: String = "",
  val estimand: String = "",
  val scale: String = "",
  val confidenceLevel: String = "95%",
  val nullValue: String = "",
  val tailDirection: String = "Two-tailed",
  val degreesOfFreedom: String = "",
  val multiplicityAdjustment: String = "",
  val alternativeExplanations: List<String> = alternativeValidInterpretations
) {
  fun toCompactString(): String = buildString {
    if (assumptionsSatisfied.isNotEmpty()) append("Satisfied: ${assumptionsSatisfied.joinToString("; ")}. ")
    if (assumptionsUnknown.isNotEmpty()) append("Unknown: ${assumptionsUnknown.joinToString("; ")}. ")
    if (alternativeValidInterpretations.isNotEmpty()) append("Alt valid interpretations: ${alternativeValidInterpretations.joinToString("; ")}.")
  }.trim()

  fun toDetailedSummary(): String = buildString {
    append("Scope: $calculatorScope")
    if (inputs.isNotBlank()) append(" | Inputs: $inputs")
    if (formula.isNotBlank()) append(" | Formula: $formula")
    if (estimand.isNotBlank()) append(" | Estimand: $estimand")
    if (scale.isNotBlank()) append(" | Scale: $scale")
    if (confidenceLevel.isNotBlank()) append(" | CI=$confidenceLevel")
    if (nullValue.isNotBlank()) append(" | Null=$nullValue")
    if (tailDirection.isNotBlank()) append(" | Tail=$tailDirection")
    if (degreesOfFreedom.isNotBlank()) append(" | DF=$degreesOfFreedom")
    if (multiplicityAdjustment.isNotBlank()) append(" | Multiplicity: $multiplicityAdjustment")
    if (alternativeExplanations.isNotEmpty()) append(" | Alternative explanations: ${alternativeExplanations.joinToString("; ")}")
  }.trim()
}

// ============================================================================
// #5. COMPOUND EVIDENCE-TO-CLAIM KEY (STRICT COMPATIBILITY — NO UNSPECIFIED MATCHES)
// ============================================================================
enum class ClaimCompatibilityStatus(val label: String) {
  COMPATIBLE("Compatible (explicit fields aligned)"),
  INCOMPATIBLE("Incompatible (conflicting explicit scope)"),
  AMBIGUOUS("Ambiguous (comparison-critical field unspecified)")
}

data class CompoundClaimKey(
  val population: String = "unspecified",
  val outcome: String = "unspecified",
  val timePoint: String = "unspecified",
  val estimator: String = "unspecified",
  val subgroup: String = "overall",
  val adjustmentStatus: AdjustmentStatus = AdjustmentStatus.UNSPECIFIED
) {
  /**
   * Strict semantic compatibility (Revision B):
   * Two claims are COMPATIBLE only when EVERY comparison-critical field is either:
   *  - equal and explicitly observed on both sides, OR
   *  - explicitly declared irrelevant in [irrelevantFields].
   *
   * `unspecified == unspecified` NEVER counts as compatible — it returns [ClaimCompatibilityStatus.AMBIGUOUS].
   */
  fun evaluateCompatibility(
    other: CompoundClaimKey,
    irrelevantFields: Set<String> = emptySet()
  ): ClaimCompatibilityStatus {
    val ignore = irrelevantFields.map { it.lowercase(Locale.US) }.toSet()
    var hasAmbiguous = false

    fun checkField(fieldName: String, a: String, b: String, unspecifiedSentinel: String = "unspecified"): Boolean {
      if (fieldName in ignore) return true
      val aSpec = !a.equals(unspecifiedSentinel, ignoreCase = true) && a.isNotBlank()
      val bSpec = !b.equals(unspecifiedSentinel, ignoreCase = true) && b.isNotBlank()
      if (aSpec && bSpec) {
        if (!a.equals(b, ignoreCase = true)) return false
      } else {
        hasAmbiguous = true
      }
      return true
    }

    if (!checkField("population", population, other.population)) return ClaimCompatibilityStatus.INCOMPATIBLE
    if (!checkField("outcome", outcome, other.outcome)) return ClaimCompatibilityStatus.INCOMPATIBLE
    if (!checkField("timepoint", timePoint, other.timePoint)) return ClaimCompatibilityStatus.INCOMPATIBLE
    if (!checkField("estimator", estimator, other.estimator)) return ClaimCompatibilityStatus.INCOMPATIBLE

    if ("subgroup" !in ignore) {
      val aBlank = subgroup.isBlank() || subgroup.equals("unspecified", ignoreCase = true)
      val bBlank = other.subgroup.isBlank() || other.subgroup.equals("unspecified", ignoreCase = true)
      if (aBlank || bBlank) {
        hasAmbiguous = true
      } else if (!subgroup.equals(other.subgroup, ignoreCase = true)) {
        return ClaimCompatibilityStatus.INCOMPATIBLE
      }
    }

    if ("adjustmentstatus" !in ignore && "adjustment" !in ignore) {
      val aSpec = adjustmentStatus != AdjustmentStatus.UNSPECIFIED
      val bSpec = other.adjustmentStatus != AdjustmentStatus.UNSPECIFIED
      if (aSpec && bSpec) {
        if (adjustmentStatus != other.adjustmentStatus) return ClaimCompatibilityStatus.INCOMPATIBLE
      } else {
        hasAmbiguous = true
      }
    }

    return if (hasAmbiguous) ClaimCompatibilityStatus.AMBIGUOUS else ClaimCompatibilityStatus.COMPATIBLE
  }

  fun unspecifiedComparisonFields(other: CompoundClaimKey): List<String> = buildList {
    if (population.equals("unspecified", true) || other.population.equals("unspecified", true)) add("population")
    if (outcome.equals("unspecified", true) || other.outcome.equals("unspecified", true)) add("outcome")
    if (timePoint.equals("unspecified", true) || other.timePoint.equals("unspecified", true)) add("timePoint")
    if (estimator.equals("unspecified", true) || other.estimator.equals("unspecified", true)) add("estimator")
    if (adjustmentStatus == AdjustmentStatus.UNSPECIFIED || other.adjustmentStatus == AdjustmentStatus.UNSPECIFIED) add("adjustmentStatus")
  }

  /**
   * Two claims may ONLY be compared for cross-section contradiction when their compound keys
   * are strictly [ClaimCompatibilityStatus.COMPATIBLE] (never on unspecified matches).
   */
  fun isCompatibleForComparison(
    other: CompoundClaimKey,
    irrelevantFields: Set<String> = emptySet()
  ): Boolean = evaluateCompatibility(other, irrelevantFields) == ClaimCompatibilityStatus.COMPATIBLE

  fun toKeyLabel(): String =
    "pop=$population | out=$outcome | t=$timePoint | est=$estimator | sub=$subgroup | adj=${adjustmentStatus.name}"
}

// ============================================================================
// LAYOUT-AWARE PDF DEDUPLICATION & 6-STATE METHODOLOGICAL ASSESSMENTS
// ============================================================================
data class PdfLineLayoutMetadata(
  val pageNumber: Int,
  val columnIndex: Int,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
  val fontSize: Float = 10f
)

data class SuspectedDuplication(
  val lineIndex: Int,
  val pageNumber: Int,
  val excerpt: String,
  val reason: String,
  val wasRemoved: Boolean
)

enum class MethodologicalAssessmentStatus(val label: String) {
  DOCUMENTED_AND_ADEQUATE("Documented and adequate"),
  DOCUMENTED_BUT_INADEQUATE("Documented but inadequate"),
  POTENTIAL_RISK("Potential risk (screening signal)"),
  NOT_REPORTED("Not reported"),
  NOT_ASSESSABLE("Not assessable"),
  NOT_APPLICABLE("Not applicable")
}

data class StructuredMethodologicalCheck(
  val checkId: String,
  val domain: String,
  val observedEvidence: String,
  val requiredEvidence: String,
  val assessmentStatus: MethodologicalAssessmentStatus,
  val applicability: String,
  val confidence: Double,
  val reasonForAbstention: String? = null
)

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
  val duplicatedColumnsRemoved: Int = 0,
  val suspectedDuplications: List<SuspectedDuplication> = emptyList(),
  val structuredMethodChecks: List<StructuredMethodologicalCheck> = emptyList(),
  val strongConclusionsDisabledDueToUnknownCoverage: Boolean = false
) {
  fun toPromptBlock(): String = buildString {
    appendLine("[PHASE 8 — TRUST, ABSTENTION & SEMANTIC VALIDITY GATE]")
    appendLine(designClassification.toSummaryBlock())
    if (strongConclusionsDisabledDueToUnknownCoverage) {
      appendLine("COVERAGE UNKNOWN GATE: Document coverage metadata was not supplied; strong methodological conclusions are disabled.")
    }
    if (abstentionAssessment.shouldAbstainFromStrongConclusions) {
      appendLine("ABSTENTION POLICY ACTIVE: ${abstentionAssessment.policyStatement}")
      abstentionAssessment.reasons.forEach { r ->
        appendLine("  * [${r.code}] ${r.detail}")
      }
    }
    if (structuredMethodChecks.isNotEmpty()) {
      appendLine("Structured Methodological Checks (6-State Epistemic Status — Not Binary Pass/Fail):")
      structuredMethodChecks.take(6).forEach { chk ->
        val absNote = chk.reasonForAbstention?.let { " | Abstention: $it" }.orEmpty()
        appendLine("  * [${chk.assessmentStatus.name}] ${chk.domain} (conf=${String.format(Locale.US, "%.2f", chk.confidence)}): Observed=${chk.observedEvidence} | Required=${chk.requiredEvidence}$absNote")
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
    fun humanCode(code: String): String = code
      .replace('_', ' ')
      .lowercase(Locale.US)
      .replaceFirstChar { it.titlecase(Locale.US) }

    appendLine("[PHASE 8 — SEMANTIC VALIDITY, CLASSIFIER CONFIDENCE & ABSTENTION REPORT]")
    appendLine(DossierFormattingEngine.sanitizeTableCell(designClassification.toSummaryBlock()))

    if (abstentionAssessment.shouldAbstainFromStrongConclusions) {
      appendLine()
      appendLine("[ABSTENTION POLICY TRIGGERED]")
      appendLine(DossierFormattingEngine.sanitizeTableCell(abstentionAssessment.policyStatement))
      abstentionAssessment.reasons.forEach { r ->
        appendLine("• [${humanCode(r.code)}] ${DossierFormattingEngine.sanitizeTableCell(r.detail)}")
      }
    }

    if (structuredUnsupported.isNotEmpty()) {
      appendLine()
      appendLine("[EXTRACTION FAILURE VS. MANUSCRIPT OMISSION CLASSIFICATION]")
      structuredUnsupported.forEach { u ->
        appendLine("• ${DossierFormattingEngine.sanitizeTableCell(u.formatLine())}")
      }
    }

    appendLine()
    appendLine("[DENOMINATOR PROVENANCE GRAPH (${denominatorGraph.populationType.label} • ${denominatorGraph.unitLevelsDetected.joinToString(", ") { it.label }})]")
    if (denominatorGraph.transitions.isNotEmpty()) {
      appendLine("| From Stage | To Stage | From N | To N | Lost | Unit Level | Population | Reason |")
      appendLine("|---|---|---|---|---|---|---|---|")
      denominatorGraph.transitions.forEach { t ->
        appendLine(
          "| ${DossierFormattingEngine.sanitizeTableCell(t.fromStage)} " +
            "| ${DossierFormattingEngine.sanitizeTableCell(t.toStage)} " +
            "| ${t.fromN} | ${t.toN} | ${t.nLost} " +
            "| ${DossierFormattingEngine.sanitizeTableCell(t.unitLevel.label)} " +
            "| ${DossierFormattingEngine.sanitizeTableCell(t.populationType.label)} " +
            "| ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(t.exclusionReason), 95)} |"
        )
      }
    } else {
      appendLine("• Stages captured: ${denominatorGraph.stages.entries.joinToString(" → ") { "${it.key}=${it.value}" }.ifBlank { "none reconstructed" }}")
    }
    denominatorGraph.unitLevelConflictWarning?.let { appendLine("• Unit-of-Analysis Alert: ${DossierFormattingEngine.sanitizeTableCell(it)}") }

    appendLine()
    appendLine("[MULTIPLICITY-FAMILY UNCERTAINTY]")
    appendLine("• ${DossierFormattingEngine.sanitizeTableCell(multiplicityAssessment.statusMessage)}")

    if (effectScaleFindings.isNotEmpty()) {
      appendLine()
      appendLine("[EFFECT-DIRECTION & SCALE SANITY CHECKS (${effectScaleFindings.size})]")
      effectScaleFindings.forEach { f ->
        val pg = if (f.pageNumber > 0) " (Page ${f.pageNumber})" else ""
        appendLine("• [${humanCode(f.checkCode)}]$pg ${DossierFormattingEngine.sanitizeTableCell(f.title)}: ${DossierFormattingEngine.sanitizeTableCell(f.detail)}")
      }
    }

    appendLine()
    appendLine("[SUBSTANTIVE MISSING-DATA AUDIT (${humanCode(missingDataAudit.substantiveAdequacy)})]")
    appendLine("• ${DossierFormattingEngine.sanitizeTableCell(missingDataAudit.summary)}")

    appendLine()
    appendLine("[ANALYST DEGREES-OF-FREEDOM AUDIT (${researcherDofReport.elevatedCount}/11 Elevated Flexibility Dimensions)]")
    appendLine("• ${DossierFormattingEngine.sanitizeTableCell(researcherDofReport.summary)}")

    if (falsificationChecks.applicableToDesign) {
      appendLine()
      appendLine("[NEGATIVE-CONTROL & FALSIFICATION CHECKS (${falsificationChecks.totalPresent}/6 Present)]")
      appendLine("• ${DossierFormattingEngine.sanitizeTableCell(falsificationChecks.summary)}")
    }

    if (estimandAssessments.isNotEmpty()) {
      appendLine()
      appendLine("[ESTIMAND-FIRST NON-DOGMATIC METHOD CHECKS]")
      estimandAssessments.forEach { e ->
        appendLine("• ${DossierFormattingEngine.sanitizeTableCell(e.methodTopic)} | Estimand: ${DossierFormattingEngine.sanitizeTableCell(e.targetEstimand)} | Same question? ${if (e.alternativeAnswersSameQuestion) "Yes" else "No (different estimand)"} — ${DossierFormattingEngine.sanitizeTableCell(e.verdictNote)}")
      }
    }
  }.trim()
}
