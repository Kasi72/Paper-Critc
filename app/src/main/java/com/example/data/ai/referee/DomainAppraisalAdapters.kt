package com.example.data.ai.referee

import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.semantic.CalibratedStudyDesign
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.DomainStatisticalAuditModules
import java.util.Locale

enum class AppraisalDomain(val displayName: String) {
  RANDOMIZED_TRIAL("Randomized Controlled Trial"),
  OBSERVATIONAL_COHORT("Observational Cohort Study"),
  CASE_CONTROL("Case-Control Study"),
  DIAGNOSTIC_STUDY("Diagnostic Accuracy Study"),
  SYSTEMATIC_REVIEW_META_ANALYSIS("Systematic Review / Meta-Analysis"),
  PREDICTION_MODEL("Clinical / ML Prediction Model"),
  QUALITATIVE_STUDY("Qualitative Research Study"),
  DESIGN_UNCERTAIN("Uncertain / Ambiguous Study Design")
}

enum class DomainCriterionRating(val label: String) {
  LOW_CONCERN("Low concern / Addressed"),
  SOME_CONCERN("Some concern / Partial"),
  HIGH_CONCERN("High concern / Deficient"),
  NOT_ASSESSABLE("Not assessable from supplied text")
}

data class DomainCriterionEvaluation(
  val domainKey: String,
  val label: String,
  val rating: DomainCriterionRating,
  val detail: String
)

data class DomainAppraisalReport(
  val domain: AppraisalDomain,
  val instrumentName: String,
  val classificationConfidence: Double,
  val restrictsVerdictDueToUncertainDesign: Boolean,
  val rubricCriteria: List<DomainCriterionEvaluation>,
  val domainSummaryNote: String,
  val nonSubstitutableWithSingleScore: Boolean = true
)

/**
 * Phase 6 Domain-Specific Appraisal Adapters covering all 7 required research designs
 * plus the explicit DESIGN_UNCERTAIN fallback restricting strong verified verdicts.
 */
object DomainAppraisalAdapters {

  fun appraise(
    text: String,
    parentArchetype: StudyDesignArchetype
  ): DomainAppraisalReport {
    val cleanText = SemanticValidityEngine.extractActiveDesignClassificationText(text)
    val l = cleanText.lowercase(Locale.US)
    val subDesign = DomainStatisticalAuditModules.classifySubDesign(cleanText, parentArchetype)
    val calibrated = SemanticValidityEngine.classifyDesignWithConfidence(cleanText, parentArchetype)

    val domain = resolveDomain(subDesign, calibrated.calibratedDesign, l, calibrated.abstained)

    return when (domain) {
      AppraisalDomain.RANDOMIZED_TRIAL -> buildRctRob2(l, calibrated.primaryConfidence)
      AppraisalDomain.OBSERVATIONAL_COHORT -> buildCohortRobinsINos(l, calibrated.primaryConfidence)
      AppraisalDomain.CASE_CONTROL -> buildCaseControlNos(l, calibrated.primaryConfidence)
      AppraisalDomain.DIAGNOSTIC_STUDY -> buildDiagnosticQuadas2(l, calibrated.primaryConfidence)
      AppraisalDomain.SYSTEMATIC_REVIEW_META_ANALYSIS -> buildSystematicReviewAmstar2Grade(l, calibrated.primaryConfidence)
      AppraisalDomain.PREDICTION_MODEL -> buildPredictionTripod(l, calibrated.primaryConfidence)
      AppraisalDomain.QUALITATIVE_STUDY -> buildQualitativeCaspSrqrCoreq(l, calibrated.primaryConfidence)
      AppraisalDomain.DESIGN_UNCERTAIN -> DomainAppraisalReport(
        domain = AppraisalDomain.DESIGN_UNCERTAIN,
        instrumentName = "Provisional Design-Uncertain Fallback",
        classificationConfidence = calibrated.primaryConfidence,
        restrictsVerdictDueToUncertainDesign = true,
        rubricCriteria = listOf(
          DomainCriterionEvaluation(
            domainKey = "DESIGN_ID",
            label = "Study Design Identification",
            rating = DomainCriterionRating.NOT_ASSESSABLE,
            detail = "Study design could not be classified with sufficient certainty (${String.format(Locale.US, "%.2f", calibrated.primaryConfidence)}); strong verified verdicts are blocked."
          )
        ),
        domainSummaryNote = "Study design uncertain (DESIGN_UNCERTAIN): output restricted to provisional appraisal; a weighted score never substitutes for domain methodology."
      )
    }
  }

  private fun resolveDomain(
    subDesign: AuditSubDesign,
    calibratedDesign: CalibratedStudyDesign,
    l: String,
    abstained: Boolean
  ): AppraisalDomain {
    // Explicit high-specificity checks for Case-Control, Qualitative, and Prediction Model first
    if (subDesign == AuditSubDesign.CASE_CONTROL || calibratedDesign == CalibratedStudyDesign.CASE_CONTROL) {
      return AppraisalDomain.CASE_CONTROL
    }
    if (subDesign == AuditSubDesign.QUALITATIVE_STUDY) {
      return AppraisalDomain.QUALITATIVE_STUDY
    }
    if (subDesign == AuditSubDesign.PREDICTION_MODEL ||
      (subDesign == AuditSubDesign.ML_BENCHMARK && (l.contains("tripod") || l.contains("probast") || l.contains("prediction model") || l.contains("calibration slope")))
    ) {
      return AppraisalDomain.PREDICTION_MODEL
    }
    if (subDesign == AuditSubDesign.DESIGN_UNCERTAIN || (abstained && subDesign == AuditSubDesign.GENERAL_EMPIRICAL)) {
      return AppraisalDomain.DESIGN_UNCERTAIN
    }
    return when (subDesign) {
      AuditSubDesign.RCT -> AppraisalDomain.RANDOMIZED_TRIAL
      AuditSubDesign.OBSERVATIONAL_COHORT, AuditSubDesign.SURVIVAL_ANALYSIS -> AppraisalDomain.OBSERVATIONAL_COHORT
      AuditSubDesign.CASE_CONTROL -> AppraisalDomain.CASE_CONTROL
      AuditSubDesign.DIAGNOSTIC_ACCURACY -> AppraisalDomain.DIAGNOSTIC_STUDY
      AuditSubDesign.SYSTEMATIC_REVIEW, AuditSubDesign.META_ANALYSIS -> AppraisalDomain.SYSTEMATIC_REVIEW_META_ANALYSIS
      AuditSubDesign.PREDICTION_MODEL, AuditSubDesign.ML_BENCHMARK -> AppraisalDomain.PREDICTION_MODEL
      AuditSubDesign.QUALITATIVE_STUDY -> AppraisalDomain.QUALITATIVE_STUDY
      AuditSubDesign.ECONOMETRIC_PANEL -> AppraisalDomain.OBSERVATIONAL_COHORT
      AuditSubDesign.FORMAL_THEOREM, AuditSubDesign.GENERAL_EMPIRICAL, AuditSubDesign.DESIGN_UNCERTAIN -> AppraisalDomain.DESIGN_UNCERTAIN
    }
  }

  private fun buildRctRob2(l: String, conf: Double): DomainAppraisalReport {
    val hasConcealment = l.contains("allocation concealment") || l.contains("central") || l.contains("sealed") || l.contains("iwrs")
    val hasBlinding = l.contains("double-blind") || l.contains("triple-blind") || l.contains("masked outcome")
    val hasItt = l.contains("intention-to-treat") || l.contains("intent-to-treat") || Regex("""\bitt\b""").containsMatchIn(l)
    val hasMissingNote = l.contains("attrition") || l.contains("missing") || l.contains("loss to follow-up")
    val hasRegistration = Regex("""\b(?:nct\d{6,}|isrctn\d{6,}|registered|protocol)\b""").containsMatchIn(l)

    val criteria = listOf(
      DomainCriterionEvaluation("ROB2_D1", "D1: Randomization & Allocation Concealment", if (hasConcealment) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Random sequence & concealed allocation"),
      DomainCriterionEvaluation("ROB2_D2", "D2: Deviations from Intended Interventions (ITT & Blinding)", if (hasItt && hasBlinding) DomainCriterionRating.LOW_CONCERN else if (hasItt || hasBlinding) DomainCriterionRating.SOME_CONCERN else DomainCriterionRating.HIGH_CONCERN, "ITT estimand and participant/personnel masking"),
      DomainCriterionEvaluation("ROB2_D3", "D3: Missing Outcome Data / Attrition", if (hasMissingNote) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Completeness of outcome follow-up"),
      DomainCriterionEvaluation("ROB2_D4", "D4: Measurement of Outcome", if (hasBlinding) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Assessor masking and objective endpoint checks"),
      DomainCriterionEvaluation("ROB2_D5", "D5: Selection of Reported Result / Pre-registration", if (hasRegistration) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Trial protocol / primary endpoint registration")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.RANDOMIZED_TRIAL,
      instrumentName = "RoB 2",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with RoB 2 (5 signaling domains); weighted credibility score does NOT substitute for RoB 2 domain judgments."
    )
  }

  private fun buildCohortRobinsINos(l: String, conf: Double): DomainAppraisalReport {
    val hasConfounding = l.contains("adjusted") || l.contains("propensity") || l.contains("inverse probability") || l.contains("multivariable")
    val hasImmortalGuard = l.contains("time-varying") || l.contains("landmark") || l.contains("target trial")
    val hasMissing = l.contains("multiple imputation") || l.contains("missing") || l.contains("attrition")
    val hasOutcomeDef = l.contains("validated") || l.contains("icd") || l.contains("adjudicat")
    val hasSensitivity = l.contains("e-value") || l.contains("sensitivity analysis") || l.contains("negative control")

    val criteria = listOf(
      DomainCriterionEvaluation("ROBINS_D1", "Confounding & Baseline Adjustment", if (hasConfounding) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.HIGH_CONCERN, "DAG / multivariable or propensity control"),
      DomainCriterionEvaluation("ROBINS_D2", "Selection of Participants & Immortal-Time Bias", if (hasImmortalGuard) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Time-zero alignment & selection safeguards"),
      DomainCriterionEvaluation("ROBINS_D3", "Classification of Exposure / Intervention", DomainCriterionRating.SOME_CONCERN, "Non-differential vs differential exposure classification"),
      DomainCriterionEvaluation("ROBINS_D5", "Missing Data & Attrition Handling", if (hasMissing) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "MICE/IPW vs complete-case sensitivity"),
      DomainCriterionEvaluation("ROBINS_D6", "Outcome Ascertainment & Unmeasured Confounding Sensitivity", if (hasOutcomeDef && hasSensitivity) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Objective endpoint validation & E-value/sensitivity")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.OBSERVATIONAL_COHORT,
      instrumentName = "ROBINS-I / Newcastle-Ottawa",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with ROBINS-I / Newcastle-Ottawa observational cohort domains; weighted score is non-substitutable."
    )
  }

  private fun buildCaseControlNos(l: String, conf: Double): DomainAppraisalReport {
    val hasCaseDef = l.contains("incident") || l.contains("histolog") || l.contains("confirmed case") || l.contains("diagnostic criteria")
    val hasControlSel = l.contains("population-based") || l.contains("matched control") || l.contains("incidence density")
    val hasMatching = l.contains("matched") || l.contains("conditional logistic") || l.contains("adjusted")
    val hasExposureBlind = l.contains("blinded") || l.contains("structured interview") || l.contains("medical record")
    val hasNonResponse = l.contains("response rate") || l.contains("missing") || l.contains("recall bias")

    val criteria = listOf(
      DomainCriterionEvaluation("CC_D1", "Adequacy of Case Definition", if (hasCaseDef) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Independent validation & incident vs prevalent case clarity"),
      DomainCriterionEvaluation("CC_D2", "Selection & Representativeness of Controls", if (hasControlSel) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Same source population / Berkson bias prevention"),
      DomainCriterionEvaluation("CC_D3", "Comparability & Confounding (Matching / Conditional Model)", if (hasMatching) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.HIGH_CONCERN, "Matched design paired with conditional logistic or multivariable adjustment"),
      DomainCriterionEvaluation("CC_D4", "Exposure Ascertainment & Recall Bias Mitigation", if (hasExposureBlind) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Blinded interview or objective prior records"),
      DomainCriterionEvaluation("CC_D5", "Same Ascertainment Method & Non-Response Balance", if (hasNonResponse) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Comparable response/missingness across cases and controls")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.CASE_CONTROL,
      instrumentName = "Newcastle-Ottawa (Case-Control) / ROBINS-I",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with Case-Control Newcastle-Ottawa / ROBINS-I adapter; odds ratios only (absolute risk not directly estimable without base rates)."
    )
  }

  private fun buildDiagnosticQuadas2(l: String, conf: Double): DomainAppraisalReport {
    val hasConsecutive = l.contains("consecutive") || l.contains("random sample") || l.contains("prospective")
    val hasIndexBlind = l.contains("blinded") || l.contains("prespecified threshold") || l.contains("pre-specified cutoff")
    val hasRefStd = l.contains("reference standard") || l.contains("gold standard") || l.contains("rt-pcr") || l.contains("biopsy")
    val hasFlowTiming = l.contains("all patients received") || l.contains("verification bias") || l.contains("interval between")

    val criteria = listOf(
      DomainCriterionEvaluation("Q2_D1", "Patient Selection (Consecutive / Avoid Case-Control Spectrum Bias)", if (hasConsecutive) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Representative clinical spectrum"),
      DomainCriterionEvaluation("Q2_D2", "Index Test (Blinded Interpretation & Prespecified Threshold)", if (hasIndexBlind) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Threshold locked prior to reference comparison"),
      DomainCriterionEvaluation("Q2_D3", "Reference Standard (Accuracy & Independence from Index)", if (hasRefStd) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.HIGH_CONCERN, "Avoids incorporation bias"),
      DomainCriterionEvaluation("Q2_D4", "Flow & Timing (Partial Verification & Appropriate Interval)", if (hasFlowTiming) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "All participants verified with consistent reference standard")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.DIAGNOSTIC_STUDY,
      instrumentName = "QUADAS-2",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with QUADAS-2 across all 4 diagnostic bias domains."
    )
  }

  private fun buildSystematicReviewAmstar2Grade(l: String, conf: Double): DomainAppraisalReport {
    val hasProtocol = l.contains("prospero") || l.contains("crd") || l.contains("protocol")
    val hasDuplicate = l.contains("two independent") || l.contains("duplicate") || l.contains("kappa")
    val hasRobTool = l.contains("rob 2") || l.contains("rob-2") || l.contains("robins-i") || l.contains("newcastle-ottawa") || l.contains("quadas")
    val hasHeterogeneity = l.contains("i2") || l.contains("i²") || l.contains("tau") || l.contains("reml") || l.contains("dersimonian")
    val hasPubBias = l.contains("egger") || l.contains("funnel") || l.contains("begg") || l.contains("trim-and-fill")

    val criteria = listOf(
      DomainCriterionEvaluation("AMSTAR_PICO_PROTO", "Protocol Registration (PROSPERO) & PICO Clarity", if (hasProtocol) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Pre-specified review eligibility and methods"),
      DomainCriterionEvaluation("AMSTAR_DUP", "Duplicate Study Selection & Data Extraction", if (hasDuplicate) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Dual-reviewer consensus and inter-rater reliability"),
      DomainCriterionEvaluation("AMSTAR_ROB", "Validated Risk-of-Bias Instrument (Distinct from PRISMA)", if (hasRobTool) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.HIGH_CONCERN, "RoB 2 / ROBINS-I / NOS / QUADAS-2 appraisal"),
      DomainCriterionEvaluation("AMSTAR_SYNTH", "Synthesis Model, Heterogeneity (I²/τ²) & Prediction Interval", if (hasHeterogeneity) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Appropriate random-effects/HKSJ pooling"),
      DomainCriterionEvaluation("AMSTAR_PUBBIAS", "Publication Bias & Certainty Rating (GRADE)", if (hasPubBias) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Funnel/Egger investigation and certainty grading")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.SYSTEMATIC_REVIEW_META_ANALYSIS,
      instrumentName = "AMSTAR 2 / GRADE",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with AMSTAR 2 / GRADE synthesis adapter (distinguishing PRISMA reporting from RoB/certainty)."
    )
  }

  private fun buildPredictionTripod(l: String, conf: Double): DomainAppraisalReport {
    val hasCalibration = l.contains("calibration") || l.contains("brier") || l.contains("ece")
    val hasExternal = l.contains("external validation") || l.contains("temporal validation") || l.contains("out-of-distribution")
    val hasSplitGuard = l.contains("patient-level") || l.contains("hospital-level") || l.contains("nested cross-validation") || l.contains("grouped")
    val hasClinicalUtility = l.contains("decision curve") || l.contains("net benefit") || l.contains("pr-auc")

    val criteria = listOf(
      DomainCriterionEvaluation("TRIPOD_PART", "Participants & Leakage-Free Grouping / Splitting", if (hasSplitGuard) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Patient/site-disjoint splits & in-fold preprocessing"),
      DomainCriterionEvaluation("TRIPOD_CALIB", "Calibration Slope / Brier / ECE Reporting", if (hasCalibration) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.HIGH_CONCERN, "Risk probability calibration beyond AUROC"),
      DomainCriterionEvaluation("TRIPOD_EXT", "External / Temporal Validation", if (hasExternal) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Out-of-sample generalizability across sites/time"),
      DomainCriterionEvaluation("TRIPOD_UTIL", "Clinical Net Benefit & Imbalance Metrics", if (hasClinicalUtility) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Decision-curve analysis / PR-AUC")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.PREDICTION_MODEL,
      instrumentName = "TRIPOD / PROBAST",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with TRIPOD / PROBAST prediction-model adapter (calibration, leakage, and external validation)."
    )
  }

  private fun buildQualitativeCaspSrqrCoreq(l: String, conf: Double): DomainAppraisalReport {
    val hasReflexivity = l.contains("reflexiv") || l.contains("positionality")
    val hasSaturation = l.contains("saturation") || l.contains("purposive") || l.contains("maximum variation")
    val hasAuditTrail = l.contains("audit trail") || l.contains("dual-cod") || l.contains("triangulat") || l.contains("member check") || l.contains("inter-coder")
    val hasFramework = l.contains("coreq") || l.contains("srqr") || l.contains("casp") || l.contains("thematic analysis") || l.contains("grounded theory")
    val hasEthics = l.contains("ethics") || l.contains("irb") || l.contains("informed consent")

    val criteria = listOf(
      DomainCriterionEvaluation("QUAL_DESIGN", "Qualitative Methodology Congruence (CASP/SRQR/COREQ)", if (hasFramework) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Clear methodological framing (grounded theory, thematic, phenomenology)"),
      DomainCriterionEvaluation("QUAL_REFLEX", "Researcher Reflexivity & Positionality", if (hasReflexivity) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Explicit accounting of researcher relationship and assumptions"),
      DomainCriterionEvaluation("QUAL_SAMP", "Purposive Sampling & Saturation Justification", if (hasSaturation) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Information power / thematic saturation criteria"),
      DomainCriterionEvaluation("QUAL_TRUST", "Trustworthiness (Audit Trail, Triangulation, Member Checking)", if (hasAuditTrail) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Analytical credibility and confirmability"),
      DomainCriterionEvaluation("QUAL_ETHICS", "Ethical Clarity & Context Transferability", if (hasEthics) DomainCriterionRating.LOW_CONCERN else DomainCriterionRating.SOME_CONCERN, "Consent, confidentiality, and thick context description")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.QUALITATIVE_STUDY,
      instrumentName = "CASP / SRQR / COREQ",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with CASP / SRQR / COREQ qualitative appraisal adapter; inapplicable NHST/p-value checks suppressed."
    )
  }
}
