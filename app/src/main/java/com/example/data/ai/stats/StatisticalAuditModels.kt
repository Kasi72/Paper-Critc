package com.example.data.ai.stats

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.semantic.CalculatorAssumptionContract
import com.example.data.ai.semantic.CompoundClaimKey
import com.example.data.ai.semantic.DesignClassificationResult
import com.example.data.ai.semantic.SemanticValidityReport
import java.util.Locale

/**
 * Classification of how cleanly a quantitative claim could be extracted and parsed
 * from the manuscript text/tables.
 */
enum class ClaimParseStatus(val label: String) {
  EXACT("EXACT"),
  PARTIAL("PARTIAL"),
  AMBIGUOUS("AMBIGUOUS"),
  UNSUPPORTED("UNSUPPORTED")
}

/**
 * Separated statistical finding taxonomy preventing screening signals, missing-keyword
 * prompts, or unextractable inputs from being conflated with confirmed mathematical errors.
 */
enum class StatisticalFindingType(val label: String, val isConfirmedError: Boolean) {
  ARITHMETIC_ERROR("Arithmetic Error", true),
  INTERNAL_CONTRADICTION("Internal Contradiction", true),
  MODEL_MISMATCH("Model / Design Mismatch", true),
  ASSUMPTION_UNCHECKED("Assumption Unchecked", false),
  REPORTING_OMISSION("Reporting Omission", false),
  LOW_POWER("Low Power / Precision", false),
  POSSIBLE_BIAS("Possible Bias / Screening Signal", false),
  EXTERNAL_VALIDITY_LIMIT("External Validity Limit", false),
  UNSUPPORTED_TO_ASSESS("Unsupported to Assess (Missing Inputs)", false),
  PASS("Verified Pass", false)
}

enum class AnalysisHierarchy {
  PRIMARY,
  SECONDARY,
  EXPLORATORY,
  SENSITIVITY,
  SUBGROUP,
  UNSPECIFIED
}

enum class AdjustmentStatus {
  ADJUSTED,
  UNADJUSTED,
  STRATIFIED,
  MATCHED,
  UNSPECIFIED
}

enum class TailSpecification {
  TWO_SIDED,
  ONE_SIDED,
  UNSPECIFIED
}

/**
 * Fine-grained sub-design classification enabling precision domain-specific statistical audits
 * across all major scientific & mathematical genres.
 */
enum class AuditSubDesign(val displayName: String, val parentArchetype: StudyDesignArchetype) {
  RCT("Randomized Controlled Trial (RCT)", StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL),
  OBSERVATIONAL_COHORT("Observational Cohort / Case-Control Study", StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL),
  DIAGNOSTIC_ACCURACY("Diagnostic Accuracy / Prognostic Validation Study", StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL),
  SURVIVAL_ANALYSIS("Survival / Time-to-Event Study", StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL),
  SYSTEMATIC_REVIEW("Systematic Review (Qualitative / Narrative Synthesis)", StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS),
  META_ANALYSIS("Systematic Review & Quantitative Meta-Analysis", StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS),
  ECONOMETRIC_PANEL("Econometrics / Panel & Causal Inference Study", StudyDesignArchetype.ECONOMETRICS_CAUSAL),
  ML_BENCHMARK("Machine Learning / Computational Benchmark", StudyDesignArchetype.MACHINE_LEARNING_AI),
  FORMAL_THEOREM("Formal Mathematics / Theoretical Proof Paper", StudyDesignArchetype.THEORETICAL_FORMAL),
  GENERAL_EMPIRICAL("General Empirical Quantitative Study", StudyDesignArchetype.GENERAL_EMPIRICAL)
}

/**
 * Phase 1: Structured quantitative claim record stored in the manuscript evidence ledger.
 */
data class QuantitativeClaim(
  val claimId: String,
  val page: Int = 0,
  val section: String = "Body",
  val tableFigure: String = "",
  val outcome: String = "",
  val population: String = "",
  val numerator: Double? = null,
  val denominator: Double? = null,
  val effectMeasure: String = "",
  val pointEstimate: Double? = null,
  val standardError: Double? = null,
  val standardDeviation: Double? = null,
  val variance: Double? = null,
  val ciLower: Double? = null,
  val ciUpper: Double? = null,
  val ciLevel: Double = 0.95,
  val testStatisticName: String = "",
  val testStatisticValue: Double? = null,
  val degreesOfFreedom: Double? = null,
  val degreesOfFreedom2: Double? = null,
  val pValueOperator: String = "",
  val pValue: Double? = null,
  val tailSpec: TailSpecification = TailSpecification.UNSPECIFIED,
  val alphaThreshold: Double = 0.05,
  val analysisType: String = "",
  val adjustedOrUnadjusted: AdjustmentStatus = AdjustmentStatus.UNSPECIFIED,
  val hierarchy: AnalysisHierarchy = AnalysisHierarchy.UNSPECIFIED,
  val testingFamily: String = "Primary",
  val sourceQuote: String = "",
  val startOffset: Int = 0,
  val endOffset: Int = 0,
  val isFromReferenceOrCitation: Boolean = false,
  val parseStatus: ClaimParseStatus = ClaimParseStatus.EXACT,
  val parseNote: String = "",
  val compoundKey: CompoundClaimKey? = null
)

/**
 * Structured 2x2 contingency table extracted from text or table rows.
 * Exposed/Treatment: events = a, non-events = b (n1 = a + b)
 * Unexposed/Control: events = c, non-events = d (n2 = c + d)
 */
data class TwoByTwoTableClaim(
  val tableId: String,
  val page: Int = 0,
  val section: String = "Results",
  val tableFigure: String = "",
  val label: String = "",
  val a: Int,
  val b: Int,
  val c: Int,
  val d: Int,
  val reportedOr: Double? = null,
  val reportedOrLower: Double? = null,
  val reportedOrUpper: Double? = null,
  val reportedRr: Double? = null,
  val reportedRrLower: Double? = null,
  val reportedRrUpper: Double? = null,
  val reportedArr: Double? = null,
  val reportedNnt: Double? = null,
  val reportedP: Double? = null,
  val reportedChiSq: Double? = null,
  val sourceQuote: String = ""
)

/**
 * Study-level effect size + standard error extracted for executable meta-analysis reconstruction.
 */
data class MetaStudyEffectClaim(
  val studyLabel: String,
  val effect: Double,
  val standardError: Double,
  val isLogScale: Boolean = false,
  val sampleSize: Int? = null,
  val page: Int = 0,
  val sourceQuote: String = ""
)

/**
 * Sample-size / denominator flow record used for cross-section reconciliation.
 */
data class SampleFlowRecord(
  val stage: String, // SCREENED, ENROLLED, RANDOMIZED, ANALYZED, SUBGROUP, MISSING_OR_LOST, COHORT_TAG
  val label: String,
  val n: Int,
  val page: Int = 0,
  val section: String = "Body",
  val tableFigure: String = "",
  val sourceQuote: String = ""
)

/**
 * Cross-link across Abstract, Methods, Results, Tables, Figures, Supplements, or Protocol.
 */
data class EvidenceCrossLink(
  val linkId: String,
  val linkType: String, // DENOMINATOR_DRIFT, SUBGROUP_EXCEEDS_TOTAL, DUPLICATE_COHORT, ADJUSTED_UNADJUSTED_SHIFT, MISSING_DATA_MISMATCH, CROSS_SECTION_CONTRADICTION
  val findingType: StatisticalFindingType,
  val status: QuantFindingStatus,
  val pageNumbers: List<Int>,
  val sectionsInvolved: List<String>,
  val description: String,
  val sourceQuotes: List<String>,
  val isScreeningSignal: Boolean = false
)

/**
 * Phase 4: Structured output of any executable numerical reconstruction calculator.
 */
data class NumericalReconstructionResult(
  val checkId: String,
  val calculatorName: String,
  val reportedValue: String,
  val recomputedValue: String,
  val tolerance: String,
  val sourceEvidence: String,
  val pageNumber: Int = 0,
  val section: String = "Results",
  val assumptions: List<String>,
  val status: QuantFindingStatus,
  val findingType: StatisticalFindingType,
  val isScreeningSignal: Boolean = false,
  val scientificConsequence: String,
  val detail: String,
  val assumptionContract: CalculatorAssumptionContract? = null
)

/**
 * Phase 3: Domain-specific design-aware statistical audit finding.
 */
data class DomainAuditFinding(
  val findingId: String,
  val domainModule: String,
  val criterion: String,
  val findingType: StatisticalFindingType,
  val status: QuantFindingStatus,
  val isScreeningSignal: Boolean = false,
  val evidenceQuote: String = "",
  val pageNumber: Int = 0,
  val detail: String,
  val consequence: String,
  val recommendedAction: String
)

data class ScorePenaltyItem(
  val reason: String,
  val delta: Int,
  val category: String, // VERIFIED_CRITICAL, DESIGN_SPECIFIC, UNSUPPORTED_EVIDENCE, ROBUSTNESS_CREDIT
  val sourceFindingIds: List<String> = emptyList()
)

/**
 * Phase 6: Transparent, auditable evidence-derived score calculation breakdown.
 */
data class ScoreCalculationBreakdown(
  val baseMathScore: Int,
  val baseMethodologyScore: Int,
  val verifiedCriticalPenalties: List<ScorePenaltyItem>,
  val designSpecificPenalties: List<ScorePenaltyItem>,
  val unsupportedEvidencePenalties: List<ScorePenaltyItem>,
  val robustnessCredits: List<ScorePenaltyItem>,
  val finalMathScore: Int,
  val finalMethodologyScore: Int,
  val mathScoreCap: Int? = null,
  val methodologyScoreCap: Int? = null,
  val verdictCapReason: String? = null,
  val extractionConfidenceAdjustment: Int = 0,
  val extractionConfidenceReason: String = "",
  val formulaSummary: String = ""
) {
  fun toMarkdownSummary(): String = buildString {
    appendLine("[EVIDENCE-DERIVED SCORE CALCULATION BREAKDOWN]")
    appendLine(
      "Formula: Base (Math=$baseMathScore/10, Methodology=$baseMethodologyScore/10) " +
        "- Verified Critical Penalties (${verifiedCriticalPenalties.sumOf { -it.delta }}) " +
        "- Design Penalties (${designSpecificPenalties.sumOf { -it.delta }}) " +
        "- Unsupported Evidence Penalties (${unsupportedEvidencePenalties.sumOf { -it.delta }}) " +
        "+ Robustness Credits (+${robustnessCredits.sumOf { it.delta }}) " +
        "-> Final Math=$finalMathScore/10, Methodology=$finalMethodologyScore/10"
    )
    if (mathScoreCap != null) {
      appendLine("• Math Rigor Hard Cap Applied: <= $mathScoreCap/10")
    }
    if (methodologyScoreCap != null) {
      appendLine("• Methodology Hard Cap Applied: <= $methodologyScoreCap/10")
    }
    if (!verdictCapReason.isNullOrBlank()) {
      appendLine("• Verdict Gate: $verdictCapReason")
    }
    if (extractionConfidenceAdjustment < 0 && extractionConfidenceReason.isNotBlank()) {
      appendLine("• Extraction Confidence Adjustment ($extractionConfidenceAdjustment): $extractionConfidenceReason (Note: missing inputs reduce extraction confidence, NOT scientific validity.)")
    }
    val allItems = verifiedCriticalPenalties + designSpecificPenalties + unsupportedEvidencePenalties + robustnessCredits
    if (allItems.isNotEmpty()) {
      appendLine("| Category | Delta | Audit Basis |")
      appendLine("|---|---|---|")
      allItems.forEach { item ->
        val sign = if (item.delta >= 0) "+${item.delta}" else "${item.delta}"
        appendLine("| ${item.category} | $sign | ${item.reason.replace("|", "/")} |")
      }
    }
  }.trim()
}

/**
 * Complete output of the dedicated Statistical Audit Subsystem (Phases 1–6).
 */
data class StatisticalAuditReport(
  val subDesign: AuditSubDesign,
  val claims: List<QuantitativeClaim>,
  val twoByTwoTables: List<TwoByTwoTableClaim>,
  val metaStudyEffects: List<MetaStudyEffectClaim>,
  val sampleFlowRecords: List<SampleFlowRecord>,
  val crossLinks: List<EvidenceCrossLink>,
  val reconstructions: List<NumericalReconstructionResult>,
  val domainFindings: List<DomainAuditFinding>,
  val unsupportedDisclosures: List<String>,
  val scoreBreakdown: ScoreCalculationBreakdown? = null,
  val designClassification: DesignClassificationResult? = null,
  val semanticValidity: SemanticValidityReport? = null
) {
  val exactClaimCount: Int get() = claims.count { !it.isFromReferenceOrCitation && it.parseStatus == ClaimParseStatus.EXACT }
  val partialClaimCount: Int get() = claims.count { !it.isFromReferenceOrCitation && it.parseStatus == ClaimParseStatus.PARTIAL }
  val ambiguousClaimCount: Int get() = claims.count { !it.isFromReferenceOrCitation && it.parseStatus == ClaimParseStatus.AMBIGUOUS }
  val unsupportedClaimCount: Int get() = claims.count { !it.isFromReferenceOrCitation && it.parseStatus == ClaimParseStatus.UNSUPPORTED }
  val excludedReferenceClaimCount: Int get() = claims.count { it.isFromReferenceOrCitation }

  val verifiedArithmeticErrorCount: Int
    get() = reconstructions.count {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.ARITHMETIC_ERROR
    }

  val verifiedContradictionCount: Int
    get() = crossLinks.count {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.INTERNAL_CONTRADICTION
    } + reconstructions.count {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.INTERNAL_CONTRADICTION
    }

  val verifiedModelMismatchCount: Int
    get() = domainFindings.count {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.MODEL_MISMATCH
    } + reconstructions.count {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.MODEL_MISMATCH
    }

  val screeningSignalCount: Int
    get() = reconstructions.count { it.isScreeningSignal } +
      domainFindings.count { it.isScreeningSignal } +
      crossLinks.count { it.isScreeningSignal }

  val unsupportedAssessmentCount: Int
    get() = reconstructions.count { it.findingType == StatisticalFindingType.UNSUPPORTED_TO_ASSESS } +
      domainFindings.count { it.findingType == StatisticalFindingType.UNSUPPORTED_TO_ASSESS } +
      unsupportedDisclosures.size

  fun toPromptBlock(): String = buildString {
    appendLine("[DESIGN-AWARE STATISTICAL AUDIT SUBSYSTEM — ${subDesign.displayName}]")
    appendLine(
      "Evidence Ledger: ${claims.count { !it.isFromReferenceOrCitation }} primary claim(s) " +
        "($exactClaimCount EXACT, $partialClaimCount PARTIAL, $ambiguousClaimCount AMBIGUOUS, $unsupportedClaimCount UNSUPPORTED; " +
        "$excludedReferenceClaimCount reference/citation claim(s) excluded from testing families)."
    )
    appendLine(
      "Deterministic Audit Counts: $verifiedArithmeticErrorCount ARITHMETIC_ERROR, " +
        "$verifiedContradictionCount INTERNAL_CONTRADICTION, " +
        "$verifiedModelMismatchCount MODEL_MISMATCH, " +
        "$screeningSignalCount SCREENING SIGNAL(s), " +
        "$unsupportedAssessmentCount UNSUPPORTED_TO_ASSESS item(s)."
    )
    appendLine(
      "EPISTEMIC DISCIPLINE RULE: Treat ONLY confirmed ARITHMETIC_ERROR, INTERNAL_CONTRADICTION, and MODEL_MISMATCH as verified flaws. " +
        "Treat ASSUMPTION_UNCHECKED, REPORTING_OMISSION, POSSIBLE_BIAS, and screening signals as verification prompts, and NEVER penalize scientific validity for UNSUPPORTED_TO_ASSESS items caused by missing tables/supplements."
    )

    if (crossLinks.isNotEmpty()) {
      appendLine("  Cross-Section Evidence Links (Abstract / Methods / Results / Tables / Figures):")
      crossLinks.take(8).forEach { link ->
        val pages = if (link.pageNumbers.isNotEmpty()) " [Pages ${link.pageNumbers.joinToString(",")}]" else ""
        appendLine("  * [${link.findingType.name} | ${link.status.name}]$pages ${link.linkType}: ${link.description}")
      }
    }

    if (reconstructions.isNotEmpty()) {
      appendLine("  Executable Numerical Reconstructions (${reconstructions.size} calculators run):")
      reconstructions.take(14).forEach { r ->
        val pg = if (r.pageNumber > 0) " [Page ${r.pageNumber}]" else ""
        val sigTag = if (r.isScreeningSignal) " [SCREENING_SIGNAL]" else ""
        appendLine(
          "  * [${r.findingType.name} | ${r.status.name}]$sigTag$pg ${r.calculatorName}: " +
            "Reported=${r.reportedValue} vs Recomputed=${r.recomputedValue} (tol=${r.tolerance}) -> ${r.detail}"
        )
      }
    }

    if (domainFindings.isNotEmpty()) {
      appendLine("  Domain-Specific Statistical Audit Findings (${subDesign.displayName}):")
      domainFindings.take(14).forEach { d ->
        val pg = if (d.pageNumber > 0) " [Page ${d.pageNumber}]" else ""
        val sigTag = if (d.isScreeningSignal) " [SCREENING_SIGNAL]" else ""
        appendLine("  * [${d.findingType.name} | ${d.status.name}]$sigTag$pg ${d.criterion}: ${d.detail}")
      }
    }

    if (unsupportedDisclosures.isNotEmpty()) {
      appendLine("  Unsupported / Unextractable Statistical Input Disclosures:")
      unsupportedDisclosures.take(6).forEach { appendLine("  * [UNSUPPORTED_TO_ASSESS] $it") }
    }

    if (semanticValidity != null) {
      appendLine()
      appendLine(semanticValidity.toPromptBlock())
    }
  }.trim()

  fun toAuditTrailMarkdown(): String = buildString {
    appendLine("[DESIGN-AWARE STATISTICAL AUDIT LEDGER — ${subDesign.displayName}]")
    appendLine(
      String.format(
        Locale.US,
        "Parsed Claims: %d active (%d Exact, %d Partial, %d Ambiguous, %d Unsupported; %d cited/reference claims isolated) | " +
          "Reconstructions: %d | Cross-Links: %d | Domain Checks: %d",
        claims.count { !it.isFromReferenceOrCitation },
        exactClaimCount,
        partialClaimCount,
        ambiguousClaimCount,
        unsupportedClaimCount,
        excludedReferenceClaimCount,
        reconstructions.size,
        crossLinks.size,
        domainFindings.size
      )
    )

    if (reconstructions.isNotEmpty()) {
      appendLine()
      appendLine("[EXECUTABLE NUMERICAL RECONSTRUCTION TABLE]")
      appendLine("| Calculator | Taxonomy | Reported | Recomputed | Status | Consequence & Assumptions |")
      appendLine("|---|---|---|---|---|---|")
      reconstructions.take(16).forEach { r ->
        val calc = r.calculatorName.replace("|", "/")
        val tax = (if (r.isScreeningSignal) "${r.findingType.name} (Signal)" else r.findingType.name).replace("|", "/")
        val rep = r.reportedValue.replace("|", "/").take(42)
        val rec = r.recomputedValue.replace("|", "/").take(48)
        val contractNote = r.assumptionContract?.toCompactString()?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        val cons = "${r.detail} ${r.scientificConsequence}$contractNote".replace("|", "/").take(220)
        appendLine("| $calc | $tax | $rep | $rec | ${r.status.name} | $cons |")
      }
    }

    if (crossLinks.isNotEmpty()) {
      appendLine()
      appendLine("[CROSS-SECTION CONSISTENCY & SAMPLE-FLOW AUDIT]")
      crossLinks.forEach { link ->
        val pg = if (link.pageNumbers.isNotEmpty()) " (Pages ${link.pageNumbers.joinToString(",")})" else ""
        appendLine("• [${link.findingType.name} | ${link.status.name}] ${link.linkType}$pg: ${link.description}")
      }
    }

    if (domainFindings.isNotEmpty()) {
      appendLine()
      appendLine("[DOMAIN-SPECIFIC STATISTICAL AUDIT (${subDesign.displayName})]")
      appendLine("| Criterion | Taxonomy | Status | Audit Finding & Remedy |")
      appendLine("|---|---|---|---|")
      domainFindings.take(16).forEach { d ->
        val crit = d.criterion.replace("|", "/")
        val tax = (if (d.isScreeningSignal) "${d.findingType.name} (Signal)" else d.findingType.name).replace("|", "/")
        val body = "${d.detail} Remedy: ${d.recommendedAction}".replace("|", "/").take(210)
        appendLine("| $crit | $tax | ${d.status.name} | $body |")
      }
    }

    if (unsupportedDisclosures.isNotEmpty()) {
      appendLine()
      appendLine("[UNSUPPORTED-TO-ASSESS INPUT DISCLOSURES]")
      unsupportedDisclosures.distinct().take(8).forEach { d ->
        appendLine("• [UNSUPPORTED_TO_ASSESS] $d")
      }
    }

    if (semanticValidity != null) {
      appendLine()
      appendLine(semanticValidity.toAuditMarkdown())
    }

    if (scoreBreakdown != null) {
      appendLine()
      appendLine(scoreBreakdown.toMarkdownSummary())
    }
  }.trim()
}
