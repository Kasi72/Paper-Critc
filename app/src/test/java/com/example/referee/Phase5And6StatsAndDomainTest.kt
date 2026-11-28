package com.example.referee

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.referee.AppraisalDomain
import com.example.data.ai.referee.DiscrepancyCrossCheckChecklist
import com.example.data.ai.referee.DomainAppraisalAdapters
import com.example.data.ai.referee.QuantitativeAdjudicationContracts
import com.example.data.ai.referee.QuantitativeFindingStatus
import com.example.data.ai.semantic.CalculatorAssumptionContract
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.DomainStatisticalAuditModules
import com.example.data.ai.stats.NumericalReconstructionResult
import com.example.data.ai.stats.StatisticalFindingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phases 5 & 6 Tests (written before implementation):
 * - Canonical [QuantitativeFindingStatus] mapping & discrepancy alternative-explanation checklist
 * - Full 10-field [CalculatorAssumptionContract] on reconstructions
 * - Score-gating blocked unless compatible typed estimand + verified source anchors
 * - All 7 required domain adapters (RCT/RoB 2, Observational Cohort/ROBINS-I-NOS, Case-Control,
 *   Diagnostic/QUADAS-2, Systematic Review/AMSTAR 2 + GRADE, Prediction Model/TRIPOD,
 *   Qualitative/CASP-SRQR-COREQ) plus DESIGN_UNCERTAIN verdict restriction
 */
class Phase5And6StatsAndDomainTest {

  @Test
  fun phase5_assumptionContract_exposesAllRequiredFieldsAndDefaults() {
    val contract = CalculatorAssumptionContract(
      calculatorScope = "2x2 Woolf Odds Ratio",
      assumptionsSatisfied = listOf("Non-zero cell counts"),
      assumptionsUnknown = listOf("Clustering within centers"),
      alternativeValidInterpretations = listOf("Exact hypergeometric Fisher CI"),
      inputs = "a=24, b=296, c=42, d=278",
      formula = "OR = (a*d)/(b*c); SE(ln OR) = sqrt(1/a + 1/b + 1/c + 1/d)",
      estimand = "Unadjusted Odds Ratio",
      scale = "Logarithmic (ln OR)",
      confidenceLevel = "95%",
      nullValue = "1.0",
      tailDirection = "Two-tailed",
      degreesOfFreedom = "df = 1 (chi-square)",
      multiplicityAdjustment = "None declared",
      alternativeExplanations = listOf("Adjusted logistic model covariates", "Rounding in reported table")
    )

    val summary = contract.toDetailedSummary()
    assertTrue(summary.contains("Inputs:"))
    assertTrue(summary.contains("Formula:"))
    assertTrue(summary.contains("Estimand:"))
    assertTrue(summary.contains("Scale:"))
    assertTrue(summary.contains("95%"))
    assertTrue(summary.contains("Null=1.0"))
    assertTrue(summary.contains("Two-tailed"))
    assertTrue(summary.contains("df = 1"))
    assertTrue(summary.contains("Multiplicity:"))
    assertTrue(summary.contains("Alternative explanations:"))
  }

  @Test
  fun phase5_quantitativeStatusMappingAndDiscrepancyChecklistGateScoresCorrectly() {
    val unsupported = NumericalReconstructionResult(
      checkId = "NR-1",
      calculatorName = "Meta-Analysis Pooling",
      reportedValue = "0.75",
      recomputedValue = "Not computable",
      tolerance = "N/A",
      sourceEvidence = "Only pooled estimate reported without study SEs",
      assumptions = listOf("Study-level SEs required"),
      status = QuantFindingStatus.WARN,
      findingType = StatisticalFindingType.UNSUPPORTED_TO_ASSESS,
      scientificConsequence = "Cannot verify pooling",
      detail = "Study-level SEs absent"
    )
    assertEquals(
      QuantitativeFindingStatus.NOT_ASSESSABLE,
      QuantitativeAdjudicationContracts.mapStatus(unsupported, sourceAnchorsVerified = true, estimandCompatible = true)
    )

    val screening = unsupported.copy(
      checkId = "NR-2",
      findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
      isScreeningSignal = true
    )
    assertEquals(
      QuantitativeFindingStatus.SCREENING_SIGNAL,
      QuantitativeAdjudicationContracts.mapStatus(screening, sourceAnchorsVerified = true, estimandCompatible = true)
    )

    val arithError = unsupported.copy(
      checkId = "NR-3",
      calculatorName = "2x2 Odds Ratio",
      reportedValue = "OR = 0.54",
      recomputedValue = "OR = 0.92",
      status = QuantFindingStatus.FAIL,
      findingType = StatisticalFindingType.ARITHMETIC_ERROR,
      isScreeningSignal = false
    )

    // Without full discrepancy cross-checks or verified anchors/estimand, MUST hold at REQUIRES_SOURCE_RECONCILIATION
    val incompleteChecklist = DiscrepancyCrossCheckChecklist(
      primaryTablesChecked = true,
      figuresChecked = false,
      supplementsChecked = false,
      scaleTransformationsChecked = true,
      roundingToleranceChecked = true,
      adjustedModelsChecked = false,
      subgroupScopeChecked = true,
      alternativeMethodsChecked = false
    )
    assertFalse(incompleteChecklist.allChannelsChecked)

    val heldStatus = QuantitativeAdjudicationContracts.mapStatus(
      result = arithError,
      sourceAnchorsVerified = false,
      estimandCompatible = true,
      checklist = incompleteChecklist
    )
    assertEquals(QuantitativeFindingStatus.REQUIRES_SOURCE_RECONCILIATION, heldStatus)
    assertFalse(QuantitativeAdjudicationContracts.canGateScoreOrVerdict(heldStatus, sourceAnchorsVerified = false, estimandCompatible = true))

    // With verified anchors, compatible estimand, and full discrepancy cross-checks -> CONFIRMED_ERROR
    val completeChecklist = DiscrepancyCrossCheckChecklist(
      primaryTablesChecked = true,
      figuresChecked = true,
      supplementsChecked = true,
      scaleTransformationsChecked = true,
      roundingToleranceChecked = true,
      adjustedModelsChecked = true,
      subgroupScopeChecked = true,
      alternativeMethodsChecked = true
    )
    val confirmedStatus = QuantitativeAdjudicationContracts.mapStatus(
      result = arithError,
      sourceAnchorsVerified = true,
      estimandCompatible = true,
      checklist = completeChecklist
    )
    assertEquals(QuantitativeFindingStatus.CONFIRMED_ERROR, confirmedStatus)
    assertTrue(QuantitativeAdjudicationContracts.canGateScoreOrVerdict(confirmedStatus, sourceAnchorsVerified = true, estimandCompatible = true))

    // Incompatible estimands block score gating even if anchors are verified
    assertFalse(QuantitativeAdjudicationContracts.canGateScoreOrVerdict(confirmedStatus, sourceAnchorsVerified = true, estimandCompatible = false))
  }

  @Test
  fun phase6_all7DomainAdaptersAndUncertainDesignFallbackOperateDeterministically() {
    val rctText = """
      Methods: Double-blind randomized controlled trial (CONSORT) with central web-based allocation concealment.
      Intention-to-treat analysis included 400 adults assigned 1:1 to Drug A or placebo. Attrition was 2%.
      Results: Mortality 10/200 vs 22/200 (HR 0.45, 95% CI 0.21 to 0.94, p = 0.03).
    """.trimIndent()
    val rctReport = DomainAppraisalAdapters.appraise(rctText, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AppraisalDomain.RANDOMIZED_TRIAL, rctReport.domain)
    assertEquals("RoB 2", rctReport.instrumentName)
    assertFalse(rctReport.restrictsVerdictDueToUncertainDesign)
    assertTrue(rctReport.rubricCriteria.size >= 5)
    assertTrue(rctReport.nonSubstitutableWithSingleScore)

    val cohortText = """
      Methods: Prospective observational cohort study (STROBE) using inverse probability weighting and Cox proportional hazards regression adjusted for age and comorbidities.
      Results: Adjusted HR 0.76 (95% CI 0.62 to 0.93).
    """.trimIndent()
    val cohortReport = DomainAppraisalAdapters.appraise(cohortText, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AppraisalDomain.OBSERVATIONAL_COHORT, cohortReport.domain)
    assertTrue(cohortReport.instrumentName.contains("ROBINS-I"))

    val caseControlText = """
      Methods: Population-based case-control study recruiting 180 incident cases and 360 matched controls by age and sex. Exposure was ascertained via blinded structured interview.
      Results: Conditional logistic regression adjusted OR 2.15 (95% CI 1.40 to 3.30).
    """.trimIndent()
    val ccSub = DomainStatisticalAuditModules.classifySubDesign(caseControlText, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AuditSubDesign.CASE_CONTROL, ccSub)
    val ccReport = DomainAppraisalAdapters.appraise(caseControlText, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AppraisalDomain.CASE_CONTROL, ccReport.domain)
    assertTrue(ccReport.instrumentName.contains("Newcastle-Ottawa"))

    val diagText = """
      Methods: Diagnostic accuracy study (STARD / QUADAS-2) evaluating rapid antigen index test against RT-PCR reference standard in 520 patients.
      Results: Sensitivity 89.2% (95% CI 84.1-93.0%) and specificity 97.5% (95% CI 95.1-98.9%).
    """.trimIndent()
    val diagReport = DomainAppraisalAdapters.appraise(diagText, StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL)
    assertEquals(AppraisalDomain.DIAGNOSTIC_STUDY, diagReport.domain)
    assertEquals("QUADAS-2", diagReport.instrumentName)

    val srMaText = """
      Methods: Systematic review and random-effects meta-analysis (PROSPERO CRD42024001122, PRISMA). Two independent reviewers screened records and assessed RoB 2.
      Results: Pooled REML OR 0.72 (95% CI 0.58 to 0.89), I2 = 42%, Egger test p = 0.34.
    """.trimIndent()
    val srReport = DomainAppraisalAdapters.appraise(srMaText, StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS)
    assertEquals(AppraisalDomain.SYSTEMATIC_REVIEW_META_ANALYSIS, srReport.domain)
    assertTrue(srReport.instrumentName.contains("AMSTAR 2"))

    val predText = """
      Methods: Clinical prediction model development and external validation following TRIPOD guidelines. Split strictly at hospital-level; Brier score 0.11, calibration slope 0.96, AUROC 0.84.
      Results: External cohort validation confirmed good discrimination and calibration.
    """.trimIndent()
    val predSub = DomainStatisticalAuditModules.classifySubDesign(predText, StudyDesignArchetype.MACHINE_LEARNING_AI)
    assertEquals(AuditSubDesign.PREDICTION_MODEL, predSub)
    val predReport = DomainAppraisalAdapters.appraise(predText, StudyDesignArchetype.MACHINE_LEARNING_AI)
    assertEquals(AppraisalDomain.PREDICTION_MODEL, predReport.domain)
    assertTrue(predReport.instrumentName.contains("TRIPOD"))

    val qualText = """
      Methods: Qualitative thematic interview study reported using COREQ and SRQR checklists. Semi-structured interviews with 28 clinicians reached thematic saturation; reflexivity statement and dual-coder audit trail documented.
      Results: Three overarching themes emerged regarding clinical workflow adoption.
    """.trimIndent()
    val qualSub = DomainStatisticalAuditModules.classifySubDesign(qualText, StudyDesignArchetype.GENERAL_EMPIRICAL)
    assertEquals(AuditSubDesign.QUALITATIVE_STUDY, qualSub)
    val qualReport = DomainAppraisalAdapters.appraise(qualText, StudyDesignArchetype.GENERAL_EMPIRICAL)
    assertEquals(AppraisalDomain.QUALITATIVE_STUDY, qualReport.domain)
    assertTrue(qualReport.instrumentName.contains("CASP"))

    val ambiguousText = "We briefly describe preliminary observations in a heterogeneous sample without clarifying design, comparator, or analytic framework."
    val uncertainReport = DomainAppraisalAdapters.appraise(ambiguousText, StudyDesignArchetype.GENERAL_EMPIRICAL)
    assertEquals(AppraisalDomain.DESIGN_UNCERTAIN, uncertainReport.domain)
    assertTrue(uncertainReport.restrictsVerdictDueToUncertainDesign)
  }
}
