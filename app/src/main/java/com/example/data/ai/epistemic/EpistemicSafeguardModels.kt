package com.example.data.ai.epistemic

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.semantic.MethodologicalAssessmentStatus
import com.example.data.ai.stats.AuditSubDesign
import com.example.util.CoverageStatus
import com.example.util.DossierFormattingEngine
import java.util.Locale

/**
 * Safeguard #2: Four-State Reporting Discipline.
 * Ensures "No evidence found" NEVER silently becomes "evidence of absence".
 */
enum class ReportingStatus(val displayLabel: String) {
  REPORTED_AND_ADEQUATE("Reported and adequate"),
  REPORTED_BUT_INADEQUATE("Reported but inadequate"),
  NOT_REPORTED("Not reported"),
  NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL("Not assessable from supplied material");

  companion object {
    fun fromNameOrDefault(raw: String?): ReportingStatus =
      entries.firstOrNull { it.name.equals(raw?.trim()?.replace(' ', '_'), ignoreCase = true) }
        ?: NOT_REPORTED
  }
}

/**
 * Safeguard #10: Red Flag vs. Fatal Flaw severity hierarchy.
 * Prevents minor reporting omissions from receiving the same weight as invalid randomization,
 * data leakage, or denominator contradictions.
 */
enum class FlawSeverityTier(val displayLabel: String, val rank: Int) {
  FATAL_TO_PRIMARY_CONCLUSION("Fatal to primary conclusion", 5),
  MATERIALLY_WEAKENS_CONFIDENCE("Materially weakens confidence", 4),
  REQUIRES_REPORTING_CORRECTION("Requires reporting correction", 3),
  WORTHWHILE_ENHANCEMENT("Worthwhile enhancement", 2),
  COSMETIC_OR_EDITORIAL("Cosmetic or editorial", 1);

  companion object {
    fun fromNameOrDefault(raw: String?): FlawSeverityTier =
      entries.firstOrNull { it.name.equals(raw?.trim()?.replace(' ', '_'), ignoreCase = true) }
        ?: WORTHWHILE_ENHANCEMENT
  }
}

/**
 * Safeguard #4: Claim Hierarchy (Level 1 -> Level 6).
 */
enum class ClaimLevel(val level: Int, val displayLabel: String) {
  DESCRIPTIVE(1, "1. Descriptive"),
  ASSOCIATIONAL(2, "2. Associational"),
  PREDICTIVE(3, "3. Predictive"),
  CAUSAL(4, "4. Causal"),
  MECHANISTIC(5, "5. Mechanistic"),
  POLICY_OR_CLINICAL_RECOMMENDATION(6, "6. Policy or clinical recommendation")
}

/**
 * Explicit applicability gate for identity card fields so non-applicable dimensions
 * (e.g., intervention/comparator in a descriptive systematic review) are never mislabeled
 * as missing or guessed from venue/affiliation text.
 */
enum class FieldApplicability(val displayLabel: String) {
  APPLICABLE("Applicable"),
  NOT_APPLICABLE("Not applicable to this study design"),
  NOT_REPORTED("Not reported"),
  NOT_ASSESSABLE("Not assessable from supplied material")
}

/**
 * Explicit applicability gate for generic methodological checks (e.g. MID / ARR / NNT).
 */
enum class CheckApplicability(val displayLabel: String) {
  APPLICABLE("Applicable"),
  NOT_APPLICABLE("Not applicable to this review's estimand"),
  NOT_REPORTED("Not reported"),
  NOT_ASSESSABLE("Not assessable from supplied material")
}

/**
 * Ranked candidate extraction record carrying section provenance and ambiguity state.
 */
data class ExtractedField<T>(
  val value: T?,
  val applicability: FieldApplicability,
  val sourceSection: String?,
  val sourcePage: Int?,
  val sourceQuote: String?,
  val confidence: Double,
  val ambiguousCandidates: List<String> = emptyList()
)

/**
 * Design-specific identity schema for Systematic Reviews & Meta-Analyses.
 * Avoids forcing trial/causal fields (intervention, comparator, ATT/ATE) onto descriptive syntheses.
 */
data class ReviewIdentityCard(
  val designType: String,
  val synthesisUnit: String,
  val includedStudyCount: Int?,
  val includedEstimateCount: Int?,
  val outcomeMetric: String?,
  val populationOrSetting: String?,
  val geographicScope: String?,
  val timeWindow: String?,
  val synthesisMethod: String?,
  val estimand: String?,
  val interventionOrExposure: String? = null,
  val comparator: String? = null,
  val interventionApplicability: FieldApplicability = FieldApplicability.NOT_APPLICABLE,
  val comparatorApplicability: FieldApplicability = FieldApplicability.NOT_APPLICABLE
)

/**
 * Safeguard #1: Study Identity Card (forced BEFORE methodological critique).
 */
data class IdentityField(
  val value: String,
  val status: ReportingStatus,
  val note: String = "",
  val applicability: FieldApplicability = FieldApplicability.APPLICABLE
)

data class StudyIdentityCard(
  val studyDesign: IdentityField,
  val subDesign: AuditSubDesign,
  val unitOfAnalysis: IdentityField,
  val population: IdentityField,
  val exposureOrIntervention: IdentityField,
  val comparator: IdentityField,
  val outcome: IdentityField,
  val timeHorizon: IdentityField,
  val primaryEstimand: IdentityField,
  val primaryAnalysis: IdentityField,
  val sampleSizeAndDenominator: IdentityField,
  val uncertainFields: List<String>,
  val reviewIdentityCard: ReviewIdentityCard? = null,
  val extractedFields: Map<String, ExtractedField<String>> = emptyMap()
) {
  val isAmbiguous: Boolean get() = uncertainFields.size >= 4 || studyDesign.status == ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL
}

/**
 * Safeguard #3: Denominator Ledger tracking every participant/unit count separately.
 */
enum class DenominatorCategory(val label: String) {
  ENROLLED("Enrolled"),
  RANDOMIZED("Randomized"),
  ANALYZED("Analyzed"),
  TABLE_INCLUDED("Table-included"),
  OUTCOME_INCLUDED("Outcome-included"),
  SUBGROUP("Subgroup"),
  FOLLOWED_UP("Followed up"),
  COMPLETE_DATA("Complete data")
}

data class DenominatorEntry(
  val category: DenominatorCategory,
  val n: Int,
  val contextLabel: String,
  val page: Int = 0,
  val section: String = "Body",
  val tableFigure: String = "",
  val sourceQuote: String = ""
)

data class DenominatorDiscrepancy(
  val discrepancyType: String, // PERCENTAGE_MISMATCH, SUBGROUP_EXCEEDS_PARENT, UNEXPLAINED_EXCLUSION, TABLE_DENOMINATOR_DRIFT, IMPOSSIBLE_ATTRITION, PROTOCOL_POPULATION_MISMATCH
  val severityTier: FlawSeverityTier,
  val reportingStatus: ReportingStatus,
  val description: String,
  val pages: List<Int>,
  val sourceQuotes: List<String>,
  val overturnEvidence: String
)

data class DenominatorLedger(
  val entries: List<DenominatorEntry>,
  val discrepancies: List<DenominatorDiscrepancy>,
  val reportingStatus: ReportingStatus
) {
  val isReconstructable: Boolean get() = entries.isNotEmpty()
}

/**
 * Safeguard #4: Claim-strength vs. Design-strength verification.
 */
data class ClaimHierarchyAudit(
  val highestClaimedLevel: ClaimLevel,
  val maxDesignSupportedLevel: ClaimLevel,
  val isOverclaimed: Boolean,
  val severityTier: FlawSeverityTier,
  val reportingStatus: ReportingStatus,
  val detectedClaimQuotes: List<String>,
  val justificationNote: String,
  val overturnEvidence: String
)

/**
 * Safeguard #5: Primary-Analysis Declaration.
 */
data class PrimaryAnalysisDeclaration(
  val primaryOutcome: IdentityField,
  val primaryTimePoint: IdentityField,
  val primaryComparison: IdentityField,
  val primaryModel: IdentityField,
  val prespecifiedSubgroups: IdentityField,
  val confirmatoryVsExploratory: IdentityField,
  val distinctOutcomesMentioned: Int,
  val selectiveEmphasisRisk: Boolean,
  val reportingStatus: ReportingStatus,
  val detail: String
)

/**
 * Safeguard #6: "What Would Change the Conclusion?" fragility & sensitivity table.
 */
data class ConclusionFragilityItem(
  val targetFinding: String,
  val keyAssumption: String,
  val plausibleViolation: String,
  val likelyBiasDirection: String,
  val minimumSensitivityAnalysis: String,
  val wouldHeadlineSurvive: String
)

/**
 * Safeguard #7: Contradiction Map across manuscript sections.
 */
data class ContradictionEdge(
  val edgeId: String,
  val fromSection: String,
  val toSection: String,
  val contradictionType: String, // PRIMARY_VS_SECONDARY_OUTCOME, SAMPLE_SIZE_MISMATCH, TEXT_VS_FIGURE_CI, CONCLUSION_STRONGER_THAN_ANALYSIS, SUBGROUP_AS_OVERALL
  val severityTier: FlawSeverityTier,
  val description: String,
  val pages: List<Int>,
  val quotes: List<String>,
  val overturnEvidence: String
)

data class ContradictionMap(
  val linkedSections: List<String>,
  val edges: List<ContradictionEdge>
)

/**
 * Safeguard #8: "Possible Reasons for the Result" 11-generator audit.
 */
data class AlternativeExplanationItem(
  val mechanism: String, // Confounding, Selection, Measurement Error, Regression to the Mean, Chance, Multiplicity, Missingness, Model Specification, Leakage, Publication Bias, Data-Dependent Choices
  val plausibility: String, // HIGH, MODERATE, LOW, ADDRESSED
  val reportingStatus: ReportingStatus,
  val rationale: String
)

/**
 * Safeguard #9: Clinical / Practical Significance Gate.
 */
data class PracticalSignificanceAssessment(
  val statisticalSignificanceStatus: String,
  val effectMagnitudeNote: String,
  val precisionNote: String,
  val minimalImportantDifferenceStatus: ReportingStatus,
  val absoluteAndBaselineRiskStatus: ReportingStatus,
  val tinyButSignificantFlag: Boolean,
  val wideNonSignificantSpunAsNoEffectFlag: Boolean,
  val clinicalOrPolicyUtilitySummary: String,
  val practicalImportanceStatus: MethodologicalAssessmentStatus = MethodologicalAssessmentStatus.NOT_ASSESSABLE,
  val requiredContextNote: String = "",
  val isNormativeOrServiceCapacityReview: Boolean = false,
  val midApplicability: CheckApplicability = CheckApplicability.APPLICABLE,
  val baselineRiskApplicability: CheckApplicability = CheckApplicability.APPLICABLE,
  val arrNntApplicability: CheckApplicability = CheckApplicability.APPLICABLE,
  val clinicalBenefitRiskApplicability: CheckApplicability = CheckApplicability.APPLICABLE,
  val predictionIntervalApplicability: CheckApplicability = CheckApplicability.NOT_APPLICABLE,
  val unitHarmonizationApplicability: CheckApplicability = CheckApplicability.NOT_APPLICABLE,
  val geographicTransportabilityApplicability: CheckApplicability = CheckApplicability.NOT_APPLICABLE,
  val needVsUtilizationApplicability: CheckApplicability = CheckApplicability.NOT_APPLICABLE
)

/**
 * Safeguard #11: Explicit 8-Source Uncertainty Budget (strictly separating extraction vs scientific uncertainty).
 */
enum class UncertaintySource(val displayLabel: String, val isExtractionSide: Boolean) {
  SAMPLING("Sampling uncertainty", false),
  MEASUREMENT("Measurement error", false),
  MODEL_ASSUMPTIONS("Model assumptions", false),
  MISSING_DATA("Missing data", false),
  SELECTION("Selection bias", false),
  EXTRACTION_QUALITY("Extraction / PDF quality", true),
  EXTERNAL_VALIDITY("External validity / transportability", false),
  ANALYST_DEGREES_OF_FREEDOM("Analyst degrees of freedom", false)
}

data class UncertaintyBudgetItem(
  val source: UncertaintySource,
  val level: String, // LOW, MODERATE, HIGH, UNASSESSABLE
  val reportingStatus: ReportingStatus,
  val note: String
)

data class UncertaintyBudget(
  val items: List<UncertaintyBudgetItem>,
  val scientificUncertaintyLevel: String,
  val extractionUncertaintyLevel: String,
  val separationNote: String
)

/**
 * Safeguard #12: Minimum Evidence to Overturn each major critique.
 */
data class OverturnCondition(
  val critiqueTitle: String,
  val severityTier: FlawSeverityTier,
  val minimumEvidenceToOverturn: String,
  val evidenceCategory: String // SUPPLEMENTARY_TABLE, PRESPECIFIED_PROTOCOL, CORRECTED_DENOMINATOR, ALTERNATIVE_MODEL, EXTERNAL_VALIDATION, SENSITIVITY_ANALYSIS
)

/**
 * Safeguard #14: Analysis Provenance stored on every finding.
 */
data class AnalysisProvenance(
  val sourcePage: Int = 0,
  val sourceQuote: String = "",
  val extractedValues: String = "",
  val parserVersion: String = PARSER_VERSION,
  val ruleVersion: String = RULE_VERSION,
  val modelVersion: String = "deterministic-engine",
  val promptCoverage: String = "100%",
  val isDeterministic: Boolean = true
) {
  companion object {
    const val PARSER_VERSION = "stats-parser-v2.0"
    const val RULE_VERSION = "epistemic-rules-v1.0"
  }
}

/**
 * Safeguard #15: "Stop and Ask" Threshold for incomplete or ambiguous evidence.
 */
data class StopAndAskTrigger(
  val triggerCode: String, // MISSING_PAGES, IMAGE_ONLY_TABLES, TRUNCATED_EQUATIONS, ABSENT_SUPPLEMENT, AMBIGUOUS_STUDY_DESIGN, UNIDENTIFIABLE_PRIMARY_OUTCOME, UNRECONSTRUCTABLE_DENOMINATOR
  val reason: String,
  val userQuestion: String
)

data class StopAndAskAssessment(
  val shouldStopAndAsk: Boolean,
  val verdictOverride: String?, // "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT" when triggered
  val triggers: List<StopAndAskTrigger>,
  val disclosureBanner: String,
  val coverageStatus: CoverageStatus = CoverageStatus.UNKNOWN,
  val strongConclusionsDisabled: Boolean = false
)

/**
 * Safeguard #13: Bidirectional Adversarial Adjudication record.
 */
enum class DefenseOutcome(val label: String) {
  UPHELD("Upheld after defense check"),
  DOWNGRADED("Downgraded by manuscript defense evidence"),
  DISMISSED_FALSE_POSITIVE("Dismissed as false positive by defense evidence")
}

data class BidirectionalAdjudicationRecord(
  val findingId: String,
  val skepticalClaim: String,
  val defenseEvidence: String,
  val defensePage: Int,
  val outcome: DefenseOutcome,
  val resultingSeverityTier: FlawSeverityTier,
  val resultingReportingStatus: ReportingStatus
)

/**
 * Unified Epistemic Safeguard Report combining Safeguards #1–#12 and #15.
 */
data class EpistemicSafeguardReport(
  val studyIdentityCard: StudyIdentityCard,
  val denominatorLedger: DenominatorLedger,
  val claimHierarchyAudit: ClaimHierarchyAudit,
  val primaryAnalysisDeclaration: PrimaryAnalysisDeclaration,
  val conclusionFragility: List<ConclusionFragilityItem>,
  val contradictionMap: ContradictionMap,
  val alternativeExplanations: List<AlternativeExplanationItem>,
  val practicalSignificanceGate: PracticalSignificanceAssessment,
  val uncertaintyBudget: UncertaintyBudget,
  val overturnConditions: List<OverturnCondition>,
  val stopAndAsk: StopAndAskAssessment,
  val coverageStatus: CoverageStatus = stopAndAsk.coverageStatus,
  val extractionConfidence: Int = 0,
  val strongConclusionsDisabled: Boolean = stopAndAsk.strongConclusionsDisabled
) {
  val fatalFlawCount: Int
    get() = denominatorLedger.discrepancies.count { it.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION } +
      contradictionMap.edges.count { it.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION } +
      (if (claimHierarchyAudit.isOverclaimed && claimHierarchyAudit.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION) 1 else 0)

  fun toPromptBlock(): String = buildString {
    appendLine("[EPISTEMIC SAFEGUARDS — READ STUDY IDENTITY & STOP-AND-ASK GATE BEFORE CRITIQUING]")
    if (stopAndAsk.shouldStopAndAsk) {
      appendLine("!!! STOP-AND-ASK THRESHOLD TRIGGERED: ${stopAndAsk.verdictOverride} !!!")
      appendLine(stopAndAsk.disclosureBanner)
      stopAndAsk.triggers.forEach { t ->
        appendLine("  * [${t.triggerCode}] ${t.reason} -> Ask user: ${t.userQuestion}")
      }
      appendLine("MANDATORY INSTRUCTION: Do NOT fill missing tables/pages/denominators with plausible assumptions. Label unavailable checks as 'Not assessable from supplied material'.")
    }

    appendLine("1. STUDY IDENTITY CARD:")
    appendLine("  - Design: ${studyIdentityCard.studyDesign.value} [${studyIdentityCard.studyDesign.status.displayLabel}]")
    appendLine("  - Unit of analysis: ${studyIdentityCard.unitOfAnalysis.value} [${studyIdentityCard.unitOfAnalysis.status.displayLabel}]")
    appendLine("  - Population: ${studyIdentityCard.population.value} [${studyIdentityCard.population.status.displayLabel}]")
    appendLine("  - Exposure/Intervention: ${studyIdentityCard.exposureOrIntervention.value} [${studyIdentityCard.exposureOrIntervention.status.displayLabel}]")
    appendLine("  - Comparator: ${studyIdentityCard.comparator.value} [${studyIdentityCard.comparator.status.displayLabel}]")
    appendLine("  - Outcome: ${studyIdentityCard.outcome.value} [${studyIdentityCard.outcome.status.displayLabel}]")
    appendLine("  - Time horizon: ${studyIdentityCard.timeHorizon.value} [${studyIdentityCard.timeHorizon.status.displayLabel}]")
    appendLine("  - Primary estimand: ${studyIdentityCard.primaryEstimand.value} [${studyIdentityCard.primaryEstimand.status.displayLabel}]")
    appendLine("  - Primary analysis: ${studyIdentityCard.primaryAnalysis.value} [${studyIdentityCard.primaryAnalysis.status.displayLabel}]")
    appendLine("  - Sample size / denominator: ${studyIdentityCard.sampleSizeAndDenominator.value} [${studyIdentityCard.sampleSizeAndDenominator.status.displayLabel}]")
    if (studyIdentityCard.uncertainFields.isNotEmpty()) {
      appendLine("  - Uncertain identity fields (state explicitly before critiquing): ${studyIdentityCard.uncertainFields.joinToString(", ")}")
    }

    appendLine("2. FOUR-STATE REPORTING RULE: Label every methodological conclusion strictly as one of: (a) Reported and adequate, (b) Reported but inadequate, (c) Not reported, or (d) Not assessable from supplied material. Never equate 'not reported' with 'not done'.")

    appendLine("3. DENOMINATOR LEDGER (${denominatorLedger.entries.size} tracked counts, ${denominatorLedger.discrepancies.size} discrepancies):")
    denominatorLedger.discrepancies.take(5).forEach { d ->
      appendLine("  * [${d.severityTier.displayLabel}] ${d.discrepancyType}: ${d.description} | Overturn with: ${d.overturnEvidence}")
    }

    appendLine("4. CLAIM HIERARCHY: Claimed=${claimHierarchyAudit.highestClaimedLevel.displayLabel} vs Design Max=${claimHierarchyAudit.maxDesignSupportedLevel.displayLabel} | Overclaimed=${claimHierarchyAudit.isOverclaimed}")
    if (claimHierarchyAudit.isOverclaimed) {
      appendLine("  * ${claimHierarchyAudit.justificationNote}")
    }

    appendLine("5. PRIMARY-ANALYSIS DECLARATION: ${primaryAnalysisDeclaration.detail} (Selective emphasis risk=${primaryAnalysisDeclaration.selectiveEmphasisRisk})")

    if (contradictionMap.edges.isNotEmpty()) {
      appendLine("6. CONTRADICTION MAP (${contradictionMap.edges.size} cross-section contradictions):")
      contradictionMap.edges.take(6).forEach { e ->
        appendLine("  * [${e.severityTier.displayLabel}] ${e.fromSection} <-> ${e.toSection} (${e.contradictionType}): ${e.description}")
      }
    }

    appendLine("7. PRACTICAL SIGNIFICANCE GATE: ${practicalSignificanceGate.clinicalOrPolicyUtilitySummary}")
    appendLine("8. UNCERTAINTY BUDGET: Scientific=${uncertaintyBudget.scientificUncertaintyLevel} | Extraction=${uncertaintyBudget.extractionUncertaintyLevel} (${uncertaintyBudget.separationNote})")
    appendLine("9. BIDIRECTIONAL DEFENSE RULE: Before finalizing any flaw, actively search the manuscript to rescue or downgrade false positives, and state the minimum evidence required to overturn every retained critique.")
  }.trim()

  fun toAuditMarkdown(): String = buildString {
    fun humanCode(code: String): String = code
      .replace('_', ' ')
      .lowercase(Locale.US)
      .replaceFirstChar { it.titlecase(Locale.US) }

    if (stopAndAsk.shouldStopAndAsk) {
      val overrideLabel = stopAndAsk.verdictOverride?.let(::humanCode) ?: "Insufficient evidence for assessment"
      appendLine("[STOP-AND-ASK COMPLETENESS GATE — $overrideLabel]")
      appendLine(DossierFormattingEngine.sanitizeTableCell(stopAndAsk.disclosureBanner))
      stopAndAsk.triggers.forEach { t ->
        appendLine("• [${humanCode(t.triggerCode)}] ${DossierFormattingEngine.sanitizeTableCell(t.reason)} — Required from author/user: ${DossierFormattingEngine.sanitizeTableCell(t.userQuestion)}")
      }
      appendLine()
    }

    appendLine("[STUDY IDENTITY CARD (Pre-Critique Grounding)]")
    appendLine("| Study Dimension | Extracted Identity | Reporting Status |")
    appendLine("|---|---|---|")
    fun row(name: String, f: IdentityField) {
      val valueCell = DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(f.value), 105)
      val statusCell = when (f.applicability) {
        FieldApplicability.NOT_APPLICABLE -> "Not applicable to this study design"
        else -> f.status.displayLabel
      }
      appendLine("| $name | $valueCell | $statusCell |")
    }
    row("Study Design", studyIdentityCard.studyDesign)
    row("Unit of Analysis", studyIdentityCard.unitOfAnalysis)
    row("Population / Setting", studyIdentityCard.population)
    row("Exposure / Intervention", studyIdentityCard.exposureOrIntervention)
    row("Comparator", studyIdentityCard.comparator)
    row("Outcome / Target Metric", studyIdentityCard.outcome)
    row("Time Horizon / Window", studyIdentityCard.timeHorizon)
    row("Primary Estimand", studyIdentityCard.primaryEstimand)
    row("Primary Analysis", studyIdentityCard.primaryAnalysis)
    row("Sample Size / Denominator", studyIdentityCard.sampleSizeAndDenominator)
    if (studyIdentityCard.uncertainFields.isNotEmpty()) {
      appendLine("• Uncertain identity fields disclosed before critique: ${studyIdentityCard.uncertainFields.joinToString(", ")}.")
    }

    appendLine()
    appendLine("[DENOMINATOR LEDGER (${denominatorLedger.entries.size} Counts Tracked • Status: ${denominatorLedger.reportingStatus.displayLabel})]")
    if (denominatorLedger.entries.isNotEmpty()) {
      appendLine("| Category | Count (N) | Section / Table | Evidence |")
      appendLine("|---|---|---|---|")
      denominatorLedger.entries.take(10).forEach { e ->
        val loc = DossierFormattingEngine.sanitizeTableCell(listOf(e.section, e.tableFigure).filter { it.isNotBlank() }.joinToString(" / "))
        val ev = DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(e.sourceQuote), 76)
        appendLine("| ${e.category.label} | ${e.n} | $loc | $ev |")
      }
    }
    denominatorLedger.discrepancies.forEach { d ->
      appendLine("• [${d.severityTier.displayLabel} • ${d.reportingStatus.displayLabel}] ${humanCode(d.discrepancyType)}: ${DossierFormattingEngine.sanitizeTableCell(d.description)} | Minimum evidence to overturn: ${DossierFormattingEngine.sanitizeTableCell(d.overturnEvidence)}")
    }

    appendLine()
    appendLine("[CLAIM HIERARCHY & PRIMARY-ANALYSIS DECLARATION]")
    appendLine("• Claimed Level: ${claimHierarchyAudit.highestClaimedLevel.displayLabel} vs. Design-Supported Maximum: ${claimHierarchyAudit.maxDesignSupportedLevel.displayLabel} (${if (claimHierarchyAudit.isOverclaimed) "OVERCLAIMED — ${claimHierarchyAudit.severityTier.displayLabel}" else "PROPORTIONATE"})")
    appendLine("• Rationale: ${DossierFormattingEngine.sanitizeTableCell(claimHierarchyAudit.justificationNote)}")
    appendLine("• Primary Analysis Declaration (${primaryAnalysisDeclaration.reportingStatus.displayLabel}): ${DossierFormattingEngine.sanitizeTableCell(primaryAnalysisDeclaration.detail)}")

    if (contradictionMap.edges.isNotEmpty()) {
      appendLine()
      appendLine("[CROSS-SECTION CONTRADICTION MAP (${contradictionMap.edges.size} Linkages)]")
      contradictionMap.edges.forEach { e ->
        appendLine("• [${e.severityTier.displayLabel}] ${e.fromSection} ↔ ${e.toSection} (${humanCode(e.contradictionType)}): ${DossierFormattingEngine.sanitizeTableCell(e.description)} | Overturn with: ${DossierFormattingEngine.sanitizeTableCell(e.overturnEvidence)}")
      }
    }

    appendLine()
    appendLine("[CLINICAL & PRACTICAL SIGNIFICANCE GATE]")
    appendLine("• Statistical vs. Practical Magnitude: ${DossierFormattingEngine.sanitizeTableCell(practicalSignificanceGate.clinicalOrPolicyUtilitySummary)}")
    appendLine("• Effect Magnitude: ${DossierFormattingEngine.sanitizeTableCell(practicalSignificanceGate.effectMagnitudeNote)} | Precision: ${DossierFormattingEngine.sanitizeTableCell(practicalSignificanceGate.precisionNote)}")
    val midLabel = if (practicalSignificanceGate.midApplicability == CheckApplicability.NOT_APPLICABLE) {
      practicalSignificanceGate.midApplicability.displayLabel
    } else {
      practicalSignificanceGate.minimalImportantDifferenceStatus.displayLabel
    }
    val riskLabel = if (practicalSignificanceGate.baselineRiskApplicability == CheckApplicability.NOT_APPLICABLE) {
      practicalSignificanceGate.baselineRiskApplicability.displayLabel
    } else {
      practicalSignificanceGate.absoluteAndBaselineRiskStatus.displayLabel
    }
    appendLine("• MID Reporting: $midLabel | Absolute/Baseline Risk: $riskLabel")
    if (practicalSignificanceGate.isNormativeOrServiceCapacityReview) {
      appendLine("• Normative / Capacity Review Applicability: Prediction interval (${practicalSignificanceGate.predictionIntervalApplicability.displayLabel}); Unit harmonization (${practicalSignificanceGate.unitHarmonizationApplicability.displayLabel}); Geographic transportability (${practicalSignificanceGate.geographicTransportabilityApplicability.displayLabel}); Need vs. utilization distinction (${practicalSignificanceGate.needVsUtilizationApplicability.displayLabel}).")
    }

    if (conclusionFragility.isNotEmpty()) {
      appendLine()
      appendLine("[WHAT WOULD CHANGE THE CONCLUSION?]")
      appendLine("| Major Finding | Key Assumption | Plausible Violation | Likely Bias Direction | Minimum Sensitivity Analysis | Headline Survival |")
      appendLine("|---|---|---|---|---|---|")
      conclusionFragility.take(6).forEach { f ->
        appendLine(
          "| ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(f.targetFinding), 52)} " +
            "| ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(f.keyAssumption), 52)} " +
            "| ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(f.plausibleViolation), 58)} " +
            "| ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(f.likelyBiasDirection), 44)} " +
            "| ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(f.minimumSensitivityAnalysis), 60)} " +
            "| ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(f.wouldHeadlineSurvive), 42)} |"
        )
      }
    }

    if (alternativeExplanations.isNotEmpty()) {
      appendLine()
      appendLine("[POSSIBLE REASONS FOR THE RESULT (11-Mechanism Audit)]")
      val elevated = alternativeExplanations.filter { it.plausibility in setOf("HIGH", "MODERATE") }
      val summaryList = if (elevated.isNotEmpty()) elevated else alternativeExplanations.take(5)
      summaryList.take(7).forEach { alt ->
        appendLine("• [${alt.plausibility} • ${alt.reportingStatus.displayLabel}] ${alt.mechanism}: ${DossierFormattingEngine.sanitizeTableCell(alt.rationale)}")
      }
    }

    appendLine()
    appendLine("[EXPLICIT UNCERTAINTY BUDGET (Scientific vs. Extraction Separation)]")
    appendLine("• Scientific Uncertainty: ${uncertaintyBudget.scientificUncertaintyLevel} | Extraction Uncertainty: ${uncertaintyBudget.extractionUncertaintyLevel} — ${DossierFormattingEngine.sanitizeTableCell(uncertaintyBudget.separationNote)}")
    appendLine("| Uncertainty Source | Level | Reporting Status | Assessment |")
    appendLine("|---|---|---|---|")
    uncertaintyBudget.items.forEach { u ->
      appendLine("| ${u.source.displayLabel} | ${u.level} | ${u.reportingStatus.displayLabel} | ${DossierFormattingEngine.clampCell(DossierFormattingEngine.sanitizeTableCell(u.note), 120)} |")
    }

    if (overturnConditions.isNotEmpty()) {
      appendLine()
      appendLine("[MINIMUM EVIDENCE TO OVERTURN MAJOR CRITIQUES]")
      overturnConditions.take(8).forEach { o ->
        appendLine("• [${o.severityTier.displayLabel}] ${DossierFormattingEngine.sanitizeTableCell(o.critiqueTitle)} -> Overturned by (${humanCode(o.evidenceCategory)}): ${DossierFormattingEngine.sanitizeTableCell(o.minimumEvidenceToOverturn)}")
      }
    }
  }.trim()
}
