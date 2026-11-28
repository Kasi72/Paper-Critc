package com.example.data.ai.referee

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.semantic.CalculatorAssumptionContract
import com.example.data.ai.stats.DomainAuditFinding
import com.example.data.ai.stats.NumericalReconstructionResult
import com.example.data.ai.stats.StatisticalFindingType

/**
 * Canonical Phase 5 quantitative finding taxonomy.
 */
enum class QuantitativeFindingStatus(val displayLabel: String) {
  PASS("Verified Pass"),
  WARNING("Warning"),
  SCREENING_SIGNAL("Screening Signal"),
  REQUIRES_SOURCE_RECONCILIATION("Requires Source Reconciliation"),
  CONFIRMED_ERROR("Confirmed Error"),
  NOT_ASSESSABLE("Not Assessable")
}

/**
 * Phase 5 explicit discrepancy cross-check contract: a detected numerical discrepancy cannot be
 * escalated to CONFIRMED_ERROR without ruling out alternative explanations across primary tables,
 * figures, supplements, scale transformations, rounding, adjusted models, subgroups, and alternative methods.
 */
data class DiscrepancyCrossCheckChecklist(
  val primaryTablesChecked: Boolean = false,
  val figuresChecked: Boolean = false,
  val supplementsChecked: Boolean = false,
  val scaleTransformationsChecked: Boolean = false,
  val roundingToleranceChecked: Boolean = false,
  val adjustedModelsChecked: Boolean = false,
  val subgroupScopeChecked: Boolean = false,
  val alternativeMethodsChecked: Boolean = false
) {
  val allChannelsChecked: Boolean
    get() = primaryTablesChecked &&
      figuresChecked &&
      supplementsChecked &&
      scaleTransformationsChecked &&
      roundingToleranceChecked &&
      adjustedModelsChecked &&
      subgroupScopeChecked &&
      alternativeMethodsChecked

  fun uncheckedChannels(): List<String> = buildList {
    if (!primaryTablesChecked) add("primary tables")
    if (!figuresChecked) add("figures")
    if (!supplementsChecked) add("supplements")
    if (!scaleTransformationsChecked) add("scale/unit transformations")
    if (!roundingToleranceChecked) add("rounding tolerance")
    if (!adjustedModelsChecked) add("adjusted vs unadjusted models")
    if (!subgroupScopeChecked) add("subgroup vs overall scope")
    if (!alternativeMethodsChecked) add("alternative statistical methods")
  }
}

object QuantitativeAdjudicationContracts {

  fun mapStatus(
    result: NumericalReconstructionResult,
    sourceAnchorsVerified: Boolean,
    estimandCompatible: Boolean,
    checklist: DiscrepancyCrossCheckChecklist = DiscrepancyCrossCheckChecklist()
  ): QuantitativeFindingStatus {
    if (result.findingType == StatisticalFindingType.UNSUPPORTED_TO_ASSESS) {
      return QuantitativeFindingStatus.NOT_ASSESSABLE
    }
    if (result.isScreeningSignal) {
      return QuantitativeFindingStatus.SCREENING_SIGNAL
    }
    return when (result.status) {
      QuantFindingStatus.PASS -> QuantitativeFindingStatus.PASS
      QuantFindingStatus.INFO -> QuantitativeFindingStatus.NOT_ASSESSABLE
      QuantFindingStatus.WARN -> {
        if (result.findingType == StatisticalFindingType.ASSUMPTION_UNCHECKED) {
          QuantitativeFindingStatus.SCREENING_SIGNAL
        } else {
          QuantitativeFindingStatus.WARNING
        }
      }
      QuantFindingStatus.FAIL -> {
        if (!result.findingType.isConfirmedError) {
          QuantitativeFindingStatus.WARNING
        } else if (sourceAnchorsVerified && estimandCompatible && checklist.allChannelsChecked) {
          QuantitativeFindingStatus.CONFIRMED_ERROR
        } else {
          QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION
        }
      }
    }
  }

  fun mapDomainStatus(
    finding: DomainAuditFinding,
    sourceAnchorsVerified: Boolean,
    estimandCompatible: Boolean,
    checklist: DiscrepancyCrossCheckChecklist = DiscrepancyCrossCheckChecklist()
  ): QuantitativeFindingStatus {
    if (finding.findingType == StatisticalFindingType.UNSUPPORTED_TO_ASSESS) {
      return QuantitativeFindingStatus.NOT_ASSESSABLE
    }
    if (finding.isScreeningSignal) {
      return QuantitativeFindingStatus.SCREENING_SIGNAL
    }
    return when (finding.status) {
      QuantFindingStatus.PASS -> QuantitativeFindingStatus.PASS
      QuantFindingStatus.INFO -> QuantitativeFindingStatus.NOT_ASSESSABLE
      QuantFindingStatus.WARN -> QuantitativeFindingStatus.WARNING
      QuantFindingStatus.FAIL -> {
        if (finding.findingType.isConfirmedError &&
          sourceAnchorsVerified &&
          estimandCompatible &&
          checklist.allChannelsChecked
        ) {
          QuantitativeFindingStatus.CONFIRMED_ERROR
        } else {
          QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION
        }
      }
    }
  }

  /**
   * Phase 5 invariant: quantitative scores and verified verdicts may ONLY be gated/penalized by
   * confirmed errors backed by verified source anchors and compatible typed estimands.
   */
  fun canGateScoreOrVerdict(
    status: QuantitativeFindingStatus,
    sourceAnchorsVerified: Boolean,
    estimandCompatible: Boolean
  ): Boolean = status == QuantitativeFindingStatus.CONFIRMED_ERROR &&
    sourceAnchorsVerified &&
    estimandCompatible

  fun enrichAssumptionContract(result: NumericalReconstructionResult): CalculatorAssumptionContract {
    val existing = result.assumptionContract
    val baseExplanations = listOf(
      "Reported values vs primary tables/figures/supplements",
      "Scale or log transformation differences",
      "Rounding of intermediate counts or SEs",
      "Adjusted vs unadjusted or subgroup model mismatch",
      "Alternative inference method (exact, bootstrap, HKSJ, robust SE)"
    )
    return if (existing != null) {
      existing.copy(
        inputs = existing.inputs.ifBlank { result.sourceEvidence.take(180) },
        formula = existing.formula.ifBlank { result.calculatorName },
        estimand = existing.estimand.ifBlank { result.section },
        scale = existing.scale.ifBlank { "As reported (${result.reportedValue})" },
        alternativeExplanations = existing.alternativeExplanations.ifEmpty { baseExplanations }
      )
    } else {
      CalculatorAssumptionContract(
        calculatorScope = result.calculatorName,
        assumptionsSatisfied = result.assumptions,
        assumptionsUnknown = listOf("Underlying raw individual-level covariates unless supplement provided"),
        alternativeValidInterpretations = baseExplanations,
        inputs = result.sourceEvidence.take(180),
        formula = result.calculatorName,
        estimand = result.section,
        scale = "Reported scale",
        confidenceLevel = "95%",
        nullValue = if (result.reportedValue.contains("OR", true) || result.reportedValue.contains("RR", true) || result.reportedValue.contains("HR", true)) "1.0" else "0.0",
        tailDirection = "Two-tailed",
        degreesOfFreedom = "Derived from reported inputs when available",
        multiplicityAdjustment = "Unadjusted unless explicitly declared",
        alternativeExplanations = baseExplanations
      )
    }
  }
}
