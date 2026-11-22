package com.example.data.ai.stats

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.util.DocumentCoverageReport
import java.util.Locale
import kotlin.math.min

/**
 * Phase 3, Phase 5 & Phase 6:
 *  - Fine-grained Study Sub-Design Classifier (10 archetypes)
 *  - 5 Dedicated Domain-Specific Statistical Audit Modules:
 *      1. Clinical & Epidemiological (RCT, Cohort, Diagnostic Accuracy, Survival)
 *      2. Systematic Reviews & Meta-Analyses
 *      3. Econometrics & Causal Inference
 *      4. Machine Learning & Computational Benchmarks
 *      5. Formal Mathematics & Theoretical Proofs
 *  - StatisticalAuditOrchestrator (Phases 1–5 unified runner)
 *  - EvidenceDerivedScoreGate (Phase 6 transparent ScoreCalculationBreakdown + score/verdict/extraction caps)
 */
object DomainStatisticalAuditModules {

  fun classifySubDesign(text: String, parentArchetype: StudyDesignArchetype): AuditSubDesign {
    val l = text.lowercase(Locale.US)
    val hasMetaPooling = l.contains("meta-analysis") || l.contains("forest plot") || l.contains("random-effects") ||
      l.contains("fixed-effect") || l.contains("dersimonian") || l.contains("tau-squared") || l.contains("τ²") ||
      Regex("""\bi(?:\^2|²|2)\s*[=<>]""").containsMatchIn(l)
    val hasSysReview = l.contains("systematic review") || l.contains("prisma") || l.contains("prospero") || l.contains("cochrane")
    val hasTheoremProof = (l.contains("theorem") || l.contains("lemma") || l.contains("proposition")) &&
      (l.contains("proof") || l.contains("q.e.d") || l.contains("contradiction") || l.contains("convergence") || l.contains("banach") || l.contains("hilbert"))
    val hasMlBenchmark = l.contains("train/test") || l.contains("cross-validation") || l.contains("neural network") ||
      l.contains("transformer") || l.contains("ablation") || l.contains("hyperparameter") || l.contains("deep learning") ||
      l.contains("random forest") || l.contains("xgboost") || l.contains("pr-auc") || l.contains("auroc") && l.contains("epoch")
    val hasEconometrics = l.contains("difference-in-differences") || l.contains("instrumental variable") || l.contains("two-stage least squares") ||
      l.contains("2sls") || l.contains("regression discontinuity") || l.contains("staggered adoption") || l.contains("two-way fixed effects") ||
      l.contains("twfe") || l.contains("panel data") || l.contains("parallel trends")
    val hasDiagnostic = (l.contains("sensitivity") && l.contains("specificity")) || l.contains("quadas") ||
      l.contains("stard") || l.contains("diagnostic accuracy") || l.contains("reference standard") || l.contains("ppv") && l.contains("npv")
    val hasSurvival = l.contains("kaplan-meier") || l.contains("cox proportional") || l.contains("hazard ratio") ||
      l.contains("competing risk") || l.contains("fine-gray") || l.contains("time-to-event") || l.contains("log-rank")
    val hasRct = l.contains("randomized controlled") || l.contains("randomised controlled") || l.contains("randomly assigned") ||
      l.contains("consort") || l.contains("double-blind") || l.contains("placebo-controlled") || l.contains("intention-to-treat")
    val hasCohort = l.contains("cohort") || l.contains("case-control") || l.contains("strobe") || l.contains("propensity score") ||
      l.contains("retrospective") || l.contains("prospective observational") || l.contains("cross-sectional") || l.contains("observational")

    return when {
      hasMetaPooling -> AuditSubDesign.META_ANALYSIS
      hasSysReview -> AuditSubDesign.SYSTEMATIC_REVIEW
      hasTheoremProof && !hasRct && !hasCohort -> AuditSubDesign.FORMAL_THEOREM
      hasEconometrics -> AuditSubDesign.ECONOMETRIC_PANEL
      hasMlBenchmark -> AuditSubDesign.ML_BENCHMARK
      hasDiagnostic && !hasRct -> AuditSubDesign.DIAGNOSTIC_ACCURACY
      hasSurvival && !hasRct -> AuditSubDesign.SURVIVAL_ANALYSIS
      hasRct -> AuditSubDesign.RCT
      hasCohort -> AuditSubDesign.OBSERVATIONAL_COHORT
      parentArchetype == StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS -> AuditSubDesign.SYSTEMATIC_REVIEW
      parentArchetype == StudyDesignArchetype.ECONOMETRICS_CAUSAL -> AuditSubDesign.ECONOMETRIC_PANEL
      parentArchetype == StudyDesignArchetype.MACHINE_LEARNING_AI -> AuditSubDesign.ML_BENCHMARK
      parentArchetype == StudyDesignArchetype.THEORETICAL_FORMAL -> AuditSubDesign.FORMAL_THEOREM
      parentArchetype == StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL -> AuditSubDesign.OBSERVATIONAL_COHORT
      else -> AuditSubDesign.GENERAL_EMPIRICAL
    }
  }

  fun runDomainAudit(
    text: String,
    subDesign: AuditSubDesign,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger,
    reconstructions: List<NumericalReconstructionResult>
  ): List<DomainAuditFinding> {
    val lower = text.lowercase(Locale.US)
    return when (subDesign) {
      AuditSubDesign.RCT,
      AuditSubDesign.OBSERVATIONAL_COHORT,
      AuditSubDesign.DIAGNOSTIC_ACCURACY,
      AuditSubDesign.SURVIVAL_ANALYSIS -> auditClinicalEpidemiological(text, lower, subDesign, ledger)
      AuditSubDesign.SYSTEMATIC_REVIEW,
      AuditSubDesign.META_ANALYSIS -> auditSystematicReviewMetaAnalysis(text, lower, subDesign, ledger)
      AuditSubDesign.ECONOMETRIC_PANEL -> auditEconometricsCausal(text, lower, ledger)
      AuditSubDesign.ML_BENCHMARK -> auditMachineLearningComputational(text, lower, ledger)
      AuditSubDesign.FORMAL_THEOREM -> auditFormalMathematics(text, lower)
      AuditSubDesign.GENERAL_EMPIRICAL -> auditGeneralEmpirical(lower, ledger)
    }
  }

  // ============================================================================
  // MODULE 1: CLINICAL & EPIDEMIOLOGICAL AUDIT (RCT, COHORT, DIAGNOSTIC, SURVIVAL)
  // ============================================================================
  private fun auditClinicalEpidemiological(
    text: String,
    l: String,
    subDesign: AuditSubDesign,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): List<DomainAuditFinding> {
    val out = mutableListOf<DomainAuditFinding>()
    var idx = 1
    fun id() = "CLIN-%02d".format(Locale.US, idx++)

    // 1. RCT baseline significance testing antipattern (Table 1 p-values)
    if (subDesign == AuditSubDesign.RCT) {
      val hasBaselinePValues = ledger.claims.any { !it.isFromReferenceOrCitation && it.testingFamily == "Baseline" && it.pValue != null } ||
        Regex("""table\s*1[^.\n]{0,80}\bp\s*[=<>]\s*0?\.\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text)
      if (hasBaselinePValues) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/RCT",
            criterion = "Baseline Balance Testing Antipattern",
            findingType = StatisticalFindingType.MODEL_MISMATCH,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "Baseline Table 1 reports hypothesis-test p-values across randomized arms; CONSORT discourages baseline significance testing because any difference under true randomization is by chance.",
            consequence = "Encourages post-hoc covariate fishing rather than pre-specified prognostic adjustment.",
            recommendedAction = "Replace baseline p-values with standardized mean differences (SMDs) and pre-specify prognostic covariates."
          )
        )
      }
      val hasItt = l.contains("intention-to-treat") || l.contains("intent-to-treat") || Regex("""\bitt\b""").containsMatchIn(l)
      val hasPerProtocolOnly = l.contains("per-protocol") && !hasItt
      if (hasPerProtocolOnly) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/RCT",
            criterion = "Estimand & Intention-to-Treat Adherence",
            findingType = StatisticalFindingType.MODEL_MISMATCH,
            status = QuantFindingStatus.FAIL,
            detail = "Primary analysis relies exclusively on Per-Protocol exclusions without an Intention-to-Treat (ITT) estimand.",
            consequence = "Breaks randomization and induces post-randomization selection bias.",
            recommendedAction = "Report ITT (treatment-policy estimand) as primary analysis alongside sensitivity per-protocol / CACE estimates."
          )
        )
      } else if (!hasItt) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/RCT",
            criterion = "Estimand Declaration (ITT/PP/ICH E9)",
            findingType = StatisticalFindingType.REPORTING_OMISSION,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "No explicit ITT/intercurrent-event estimand specification detected in extracted Methods text.",
            consequence = "Ambiguity over handling of protocol deviations and non-adherence.",
            recommendedAction = "Verify ITT vs modified-ITT definition and intercurrent event handling in protocol/Methods."
          )
        )
      }
      if (!l.contains("allocation concealment") && !l.contains("sealed") && !l.contains("centralized random") && !l.contains("interactive web")) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/RCT",
            criterion = "Allocation Concealment",
            findingType = StatisticalFindingType.REPORTING_OMISSION,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "Randomization sequence allocation concealment mechanism not explicitly described in extracted text.",
            consequence = "Potential selection bias at enrollment.",
            recommendedAction = "Confirm centralized IVRS/IWRS or opaque sealed allocation concealment in full protocol."
          )
        )
      }
    }

    // 2. Survival & Competing Risks checks
    val hasSurvival = subDesign == AuditSubDesign.SURVIVAL_ANALYSIS || l.contains("cox ") || l.contains("hazard ratio") || l.contains("kaplan-meier")
    if (hasSurvival) {
      val mentionsPhCheck = l.contains("proportional hazards assumption") || l.contains("schoenfeld") || l.contains("log-log") || l.contains("time-varying coefficient")
      if (!mentionsPhCheck) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Survival",
            criterion = "Proportional Hazards Assumption",
            findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "Time-to-event / Cox model reported without Schoenfeld residual test or log-minus-log diagnostic for proportional hazards.",
            consequence = "Average hazard ratios can mask crossing survival curves or time-varying treatment effects.",
            recommendedAction = "Report Schoenfeld global/covariate p-values or restricted mean survival time (RMST) differences."
          )
        )
      }
      val mentionsCompetingEvent = l.contains("competing risk") || l.contains("all-cause mortality") || l.contains("non-cancer death") ||
        l.contains("death from other causes") || l.contains("disease-specific mortality") || l.contains("cardiovascular death") || l.contains("transplant")
      val usesCompetingRiskModel = l.contains("fine-gray") || l.contains("fine and gray") || l.contains("aalen-johansen") ||
        l.contains("subdistribution hazard") || l.contains("cumulative incidence function") || l.contains("cause-specific hazard") ||
        l.contains("etiologic") || l.contains("restricted mean survival") || l.contains("rmst")
      val censorsCompetingDeaths = Regex("""censor(?:ed|ing)\s+(?:at|for)\s+(?:death|non-[a-z]+\s+death|competing)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
      if (censorsCompetingDeaths && !usesCompetingRiskModel) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Survival",
            criterion = "Competing Risks Censoring Distortion",
            findingType = StatisticalFindingType.MODEL_MISMATCH,
            status = QuantFindingStatus.FAIL,
            detail = "Competing events (e.g., deaths from other causes) are treated as right-censored in Kaplan-Meier/standard Cox without Aalen-Johansen CIF or Fine-Gray/cause-specific competing-risk modeling.",
            consequence = "1 − KM systematically overestimates cumulative incidence when competing events occur.",
            recommendedAction = "Estimate Aalen-Johansen cumulative incidence functions and report both cause-specific and Fine-Gray subdistribution HRs."
          )
        )
      } else if (mentionsCompetingEvent && !usesCompetingRiskModel) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Survival",
            criterion = "Competing Risks & Informative Censoring",
            findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "Cause-specific endpoint with potential competing events lacks explicit Fine-Gray/Aalen-Johansen competing-risk modeling or censoring sensitivity analysis.",
            consequence = "Cumulative risk estimates may be biased upward if competing mortality is non-trivial.",
            recommendedAction = "Verify whether competing events were modeled via Aalen-Johansen CIF / Fine-Gray regression."
          )
        )
      }
      if (!l.contains("censor") && !l.contains("follow-up") && !l.contains("person-years")) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Survival",
            criterion = "Censoring Mechanism & Follow-up",
            findingType = StatisticalFindingType.REPORTING_OMISSION,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "Survival analysis omits median follow-up (reverse Kaplan-Meier) and informative censoring assessment.",
            consequence = "Cannot verify differential censoring or administrative vs loss-to-follow-up rates.",
            recommendedAction = "Report number of events, person-time at risk, reverse-KM median follow-up, and differential censoring checks."
          )
        )
      }
    }

    // 3. Observational cohort confounding, immortal-time bias, EPV, clustering, missing data
    if (subDesign == AuditSubDesign.OBSERVATIONAL_COHORT || subDesign == AuditSubDesign.SURVIVAL_ANALYSIS) {
      val definesExposurePostBaseline = Regex("""(?:initiated|started|received)\s+(?:treatment|therapy|drug)\s+(?:during|after)\s+(?:follow-?up|index|baseline)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
      val handlesImmortalTime = l.contains("time-dependent") || l.contains("time-varying exposure") || l.contains("landmark analysis") || l.contains("target trial emulation") || l.contains("clone-censor")
      if (definesExposurePostBaseline && !handlesImmortalTime) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Epidemiology",
            criterion = "Immortal-Time Bias",
            findingType = StatisticalFindingType.MODEL_MISMATCH,
            status = QuantFindingStatus.FAIL,
            detail = "Exposure is defined using post-baseline treatment initiation without time-varying exposure modeling, landmark analysis, or clone-censor-weight target-trial emulation.",
            consequence = "Person-time before treatment initiation is misclassified as exposed, artificially inflating survival benefit.",
            recommendedAction = "Re-specify treatment as a time-varying covariate or apply target-trial emulation / landmarking."
          )
        )
      }
    }

    // 4. Clustering / repeated measures dependence check
    val hasClusteredOrRepeatedData = l.contains("multicenter") || l.contains("multi-center") || l.contains("repeated measures") ||
      l.contains("multiple visits") || l.contains("multiple eyes") || l.contains("cluster-randomized") || l.contains("hospitals") && l.contains("patients")
    val modelsDependence = l.contains("mixed-effect") || l.contains("mixed effect") || l.contains("random intercept") ||
      l.contains("gee") || l.contains("generalized estimating") || l.contains("cluster-robust") || l.contains("clustered standard") ||
      l.contains("frailty") || l.contains("sandwich") || l.contains("resampling across") || l.contains("hierarchical") || l.contains("multilevel")
    if (hasClusteredOrRepeatedData && !modelsDependence && (l.contains("naive") || l.contains("ordinary logistic") || l.contains("independent observations") || l.contains("chi-square"))) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Clinical/Dependence",
          criterion = "Clustered / Repeated-Measures Dependence",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Clustered or repeated-measures data analyzed with naive independence standard errors (no mixed-effects, GEE, or cluster-robust variance estimator).",
          consequence = "Standard errors are understated by the design effect DEFF = 1 + (m−1)ICC, inflating false-positive rates.",
          recommendedAction = "Refit with random intercepts/slopes, GEE, or cluster-robust sandwich SEs."
        )
      )
    }

    // 5. EPV / Overfitting check (extract events & predictors if reported)
    val eventsMatch = Regex("""(\d{1,4})\s+(?:events|deaths|outcomes|cases)\b""", RegexOption.IGNORE_CASE).find(text)
    val predMatch = Regex("""(\d{1,3})\s+(?:covariates|predictors|variables|parameters)\b""", RegexOption.IGNORE_CASE).find(text)
    if (eventsMatch != null && predMatch != null && (l.contains("logistic") || l.contains("cox"))) {
      val ev = eventsMatch.groupValues[1].toIntOrNull() ?: 0
      val pr = predMatch.groupValues[1].toIntOrNull() ?: 0
      if (ev > 0 && pr >= 3 && (ev.toDouble() / pr.toDouble()) < 10.0) {
        val epv = ev.toDouble() / pr.toDouble()
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Regression",
            criterion = "Events Per Variable (EPV) / Overfitting",
            findingType = StatisticalFindingType.LOW_POWER,
            status = QuantFindingStatus.WARN,
            detail = String.format(Locale.US, "Low events-per-variable ratio: %d events / %d predictors = %.1f EPV (< 10 threshold).", ev, pr, epv),
            consequence = "Sparse-data bias, inflated regression coefficients, and risk of complete/quasi-complete separation.",
            recommendedAction = "Apply Firth penalized likelihood, ridge/lasso shrinkage, or reduce model degrees of freedom."
          )
        )
      }
    }

    // 6. Missing-data mechanism (complete-case vs MI/MAR/MNAR)
    val hasSubstantialMissing = Regex("""(1[5-9]|[2-9]\d)(?:\.\d+)?%\s+(?:missing|lost\s+to\s+follow|attrition|incomplete)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    val usesCompleteCaseOnly = (l.contains("complete-case") || l.contains("complete case") || l.contains("listwise")) &&
      !l.contains("multiple imputation") && !l.contains("mice") && !l.contains("ipw") && !l.contains("sensitivity") &&
      !l.contains("non-ignorable") && !l.contains("inverse probability")
    if (hasSubstantialMissing && usesCompleteCaseOnly) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Clinical/MissingData",
          criterion = "Missing-Data Mechanism (MCAR vs MAR/MNAR)",
          findingType = StatisticalFindingType.POSSIBLE_BIAS,
          status = QuantFindingStatus.WARN,
          detail = "Substantial missingness (>=15%) handled solely via complete-case analysis without multiple imputation (MAR) or tipping-point/pattern-mixture (MNAR) sensitivity analysis.",
          consequence = "Complete-case analysis is unbiased only under strict MCAR or outcome-independent missingness.",
          recommendedAction = "Conduct multiple imputation (MICE) under MAR and delta-adjusted MNAR sensitivity analyses."
        )
      )
    }

    // 7. Diagnostic Accuracy specific checks
    if (subDesign == AuditSubDesign.DIAGNOSTIC_ACCURACY) {
      val hasSensSpec = l.contains("sensitivity") && l.contains("specificity")
      val hasDiagnosticCi = ledger.claims.any { !it.isFromReferenceOrCitation && it.ciLower != null && it.ciUpper != null }
      if (hasSensSpec && !hasDiagnosticCi) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Diagnostic",
            criterion = "Diagnostic Precision Intervals (STARD)",
            findingType = StatisticalFindingType.REPORTING_OMISSION,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "Sensitivity/specificity reported without 95% confidence intervals (Wilson/Clopper-Pearson).",
            consequence = "Precision of diagnostic accuracy cannot be judged.",
            recommendedAction = "Report exact 2x2 counts (TP/FP/FN/TN) and 95% CIs for sensitivity, specificity, LR+, and LR-."
          )
        )
      }
      if (l.contains("case-control") && (l.contains("ppv") || l.contains("positive predictive value"))) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Clinical/Diagnostic",
            criterion = "Spectrum Bias & Prevalence-Dependent PPV/NPV",
            findingType = StatisticalFindingType.MODEL_MISMATCH,
            status = QuantFindingStatus.FAIL,
            detail = "Positive/negative predictive values (PPV/NPV) reported from a diagnostic case-control design where disease prevalence is artificially fixed.",
            consequence = "Artificial prevalence distorts PPV/NPV and exaggerates apparent clinical utility.",
            recommendedAction = "Report likelihood ratios (LR+/LR-) or project PPV/NPV across realistic clinical prevalences via Bayes' theorem."
          )
        )
      }
    }

    // 8. Relative-only effect reporting without clinical magnitude (ARR/NNT) or harms
    val hasRelativeOnly = ledger.claims.any { !it.isFromReferenceOrCitation && it.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR") }
    if (subDesign == AuditSubDesign.RCT && hasRelativeOnly && !l.contains("arr") && !l.contains("absolute risk") && !l.contains("nnt") && !l.contains("risk difference")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Clinical/Magnitude",
          criterion = "Absolute Benefit Magnitude (ARR/NNT) & Harms",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "Relative effect ratios reported without Absolute Risk Reduction (ARR), Number Needed to Treat (NNT), or minimal important difference (MID).",
          consequence = "Relative risk reductions can overstate clinical importance when baseline risk is low.",
          recommendedAction = "Report absolute arm event rates, ARR/NNT with 95% CIs, and adverse-event benefit-risk balance."
        )
      )
    }

    return out
  }

  // ============================================================================
  // MODULE 2: SYSTEMATIC REVIEWS & META-ANALYSES
  // ============================================================================
  private fun auditSystematicReviewMetaAnalysis(
    text: String,
    l: String,
    subDesign: AuditSubDesign,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): List<DomainAuditFinding> {
    val out = mutableListOf<DomainAuditFinding>()
    var idx = 1
    fun id() = "META-%02d".format(Locale.US, idx++)

    val isMeta = subDesign == AuditSubDesign.META_ANALYSIS
    val maxI2 = Regex("""\bI(?:\^2|²|2)\s*(?:[=≈<>≤≥]|>=|of)\s*(\d+(?:\.\d+)?)\s*%""", RegexOption.IGNORE_CASE)
      .findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
    val kStudies = Regex("""\b(?:k\s*=\s*(\d+)|(\d+)\s+(?:included\s+)?(?:studies|trials|cohorts))\b""", RegexOption.IGNORE_CASE)
      .findAll(text).mapNotNull { (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }
      .filter { it in 2..5000 }.maxOrNull() ?: ledger.metaStudyEffects.size.takeIf { it >= 2 }

    // 1. Fixed vs Random Effects & τ² estimator sensitivity (DL vs REML/PM + Hartung-Knapp)
    val usesFixedOnly = (l.contains("fixed-effect") || l.contains("fixed effect")) && !l.contains("random-effect") && !l.contains("random effect")
    if (isMeta && usesFixedOnly && (maxI2 ?: 0.0) >= 50.0) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Meta-Analysis",
          criterion = "Fixed vs Random Effects Model Choice",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Fixed-effect pooling applied despite substantial heterogeneity (I² = ${maxI2?.toInt()}%).",
          consequence = "Confidence intervals assume τ² = 0 and severely understate true uncertainty across settings.",
          recommendedAction = "Switch to random-effects pooling (REML or Paule-Mandel τ²) with Hartung-Knapp-Sidik-Jonkman CIs."
        )
      )
    }

    if (isMeta && (l.contains("dersimonian") || l.contains("dl estimator")) && !l.contains("reml") && !l.contains("paule-mandel") && !l.contains("hartung-knapp") && !l.contains("knapp-hartung") && !l.contains("bayesian hierarchical")) {
      val smallK = kStudies != null && kStudies < 10
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Meta-Analysis",
          criterion = "τ² Estimator & Hartung-Knapp Small-k Sensitivity",
          findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "DerSimonian-Laird (DL) estimator used without REML/Paule-Mandel sensitivity comparison" +
            if (smallK) " (with small k = $kStudies, τ² uncertainty is high and even REML/Hartung-Knapp carry small-sample trade-offs)." else ".",
          consequence = "DL Wald CIs can have elevated Type I error when τ² is substantial; compare sensitivity estimators rather than assuming a single universal default.",
          recommendedAction = "Report sensitivity comparison across REML, Paule-Mandel, and (where appropriate for k) Hartung-Knapp or Bayesian hierarchical pooling."
        )
      )
    }

    // 2. Prediction interval when heterogeneity is substantial
    if (isMeta && (maxI2 ?: 0.0) >= 50.0 && !l.contains("prediction interval")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Meta-Analysis",
          criterion = "95% Prediction Interval",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "Substantial heterogeneity (I² = ${maxI2?.toInt()}%) reported without a 95% Prediction Interval.",
          consequence = "Confidence interval for the mean effect does not convey expected dispersion in a new population.",
          recommendedAction = "Report 95% prediction interval alongside τ² and I²."
        )
      )
    }

    // 3. Subgroup interaction test fallacy (claiming subgroup difference without p_interaction)
    if (l.contains("subgroup") && !l.contains("interaction") && !l.contains("q_between") && !l.contains("meta-regression") &&
      Regex("""significant\s+in\s+[a-z\s]+but\s+not\s+in""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    ) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Meta-Analysis",
          criterion = "Subgroup Interaction Test Fallacy",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Claims a subgroup difference based on one statistically significant and one non-significant stratum without a formal test of subgroup interaction (p_interaction / Q_between).",
          consequence = "Comparing nominal significance across strata yields severe false-positive subgroup claims (Altman & Bland fallacy).",
          recommendedAction = "Compute a formal between-subgroup interaction test (Q_between / meta-regression interaction p-value)."
        )
      )
    }

    // 4. Meta-regression overfitting / ecological fallacy
    if (l.contains("meta-regression")) {
      if (kStudies != null && kStudies < 10) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Meta-Analysis",
            criterion = "Meta-Regression Degrees of Freedom (k < 10)",
            findingType = StatisticalFindingType.LOW_POWER,
            status = QuantFindingStatus.WARN,
            detail = "Meta-regression attempted with k = $kStudies < 10 studies (Cochrane Handbook recommends >=10 studies per covariate).",
            consequence = "High risk of overfitting and spurious moderator associations.",
            recommendedAction = "Restrict meta-regression to pre-specified moderators with Knapp-Hartung df adjustment or treat as exploratory."
          )
        )
      }
      if ((l.contains("mean age") || l.contains("percent female") || l.contains("study-level")) && !l.contains("ecological fallacy") && !l.contains("aggregation bias")) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Meta-Analysis",
            criterion = "Ecological Fallacy in Aggregate Meta-Regression",
            findingType = StatisticalFindingType.EXTERNAL_VALIDITY_LIMIT,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "Patient-level characteristics modeled via study-level averages without acknowledging ecological/aggregation bias.",
            consequence = "Across-study slopes need not reflect within-study patient-level effect modification.",
            recommendedAction = "Caveat aggregate covariate slopes against ecological fallacy or obtain IPD."
          )
        )
      }
    }

    // 5. Unit-of-analysis / multi-arm double counting
    if ((l.contains("shared control") || l.contains("multi-arm") || l.contains("multiple arms")) &&
      !l.contains("split") && !l.contains("multilevel") && !l.contains("three-level") && !l.contains("robust variance") && !l.contains("network meta")
    ) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Meta-Analysis",
          criterion = "Unit-of-Analysis / Shared Control Double-Counting",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Multi-arm trials with shared control groups included without splitting the shared arm, combining arms, or fitting a multilevel/RVE/network model.",
          consequence = "Double-counts control participants and artificially narrows pooled confidence intervals.",
          recommendedAction = "Split shared control group counts or fit a three-level / robust variance estimation model."
        )
      )
    }

    // 6. GRADE certainty & downgrade justification
    if (!l.contains("grade") && !l.contains("certainty of evidence")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Systematic Review",
          criterion = "GRADE Certainty of Evidence Appraisal",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "No GRADE certainty-of-evidence framework (risk of bias, inconsistency, indirectness, imprecision, publication bias) detected.",
          consequence = "Translational certainty of pooled or narrative conclusions is uncalibrated.",
          recommendedAction = "Provide a GRADE Summary of Findings table with explicit downgrade justifications."
        )
      )
    }

    return out
  }

  // ============================================================================
  // MODULE 3: ECONOMETRICS & CAUSAL INFERENCE
  // ============================================================================
  private fun auditEconometricsCausal(
    text: String,
    l: String,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): List<DomainAuditFinding> {
    val out = mutableListOf<DomainAuditFinding>()
    var idx = 1
    fun id() = "ECON-%02d".format(Locale.US, idx++)

    // 1. Staggered DiD + TWFE negative weighting bias
    val hasStaggered = l.contains("staggered") || l.contains("differential timing") || l.contains("rollout") || l.contains("adopted in different years")
    val usesTwfe = l.contains("two-way fixed effects") || l.contains("twfe") || (l.contains("difference-in-differences") && l.contains("fixed effects"))
    val usesModernDid = l.contains("callaway") || l.contains("sant'anna") || l.contains("sun and abraham") || l.contains("borusyak") ||
      l.contains("de chaisemartin") || l.contains("stacked") || l.contains("gardner") || l.contains("imputation estimator")
    if (hasStaggered && usesTwfe && !usesModernDid) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Econometrics/DiD",
          criterion = "Staggered Adoption & TWFE Negative-Weighting Bias",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Staggered treatment adoption estimated with canonical Two-Way Fixed Effects (TWFE) without heterogeneity-robust estimators (Callaway-Sant'Anna, Sun-Abraham, Borusyak-Jaravel-Spiess, or Goodman-Bacon decomposition).",
          consequence = "Already-treated units act as 'forbidden comparisons', producing negative weights and even sign-reversed ATT estimates under dynamic treatment effects.",
          recommendedAction = "Re-estimate with Callaway-Sant'Anna / Sun-Abraham / imputation DiD and report Goodman-Bacon weight diagnostics."
        )
      )
    }

    // 2. Parallel trends & event-study pre-trends
    if (l.contains("difference-in-differences") || l.contains("did")) {
      if (!l.contains("event study") && !l.contains("event-study") && !l.contains("pre-trend") && !l.contains("parallel trend")) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Econometrics/DiD",
            criterion = "Parallel Trends & Event-Study Pre-Trends",
            findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
            status = QuantFindingStatus.WARN,
            detail = "Difference-in-differences identification lacks event-study pre-trend coefficients or Rambachan-Roth HonestDiD sensitivity bounds.",
            consequence = "Anticipation or differential pre-trends could drive the estimated treatment effect.",
            recommendedAction = "Report lead/lag event-study estimates and Rambachan-Roth pre-trend sensitivity bounds."
          )
        )
      }
    }

    // 3. Instrumental Variables: Weak instruments (First-stage F < 10)
    if (l.contains("instrumental variable") || l.contains("2sls") || l.contains("two-stage least squares")) {
      val firstStageF = Regex("""first[- ]stage\s+F(?:-?stat(?:istic)?)?\s*[=:]\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
      if (firstStageF != null && firstStageF < 10.0) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Econometrics/IV",
            criterion = "Weak Instrument Bias (First-Stage F < 10)",
            findingType = StatisticalFindingType.MODEL_MISMATCH,
            status = QuantFindingStatus.FAIL,
            detail = "Reported first-stage F = $firstStageF falls below the Staiger-Stock threshold (F < 10; Lee et al. tF recommends F > 104.7 for standard 5% Wald inference).",
            consequence = "2SLS estimates are biased toward OLS and standard Wald confidence intervals severely under-cover.",
            recommendedAction = "Report Anderson-Rubin weak-IV robust confidence sets and Kleibergen-Paap / Montiel Olea-Pflueger effective F."
          )
        )
      } else if (firstStageF == null && !l.contains("kleibergen") && !l.contains("anderson-rubin")) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Econometrics/IV",
            criterion = "First-Stage Relevance & Exclusion Restriction",
            findingType = StatisticalFindingType.REPORTING_OMISSION,
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "IV/2SLS analysis does not report first-stage effective F-statistic or weak-IV robust Anderson-Rubin intervals in extracted text.",
            consequence = "Instrument strength cannot be audited.",
            recommendedAction = "Report Montiel Olea-Pflueger effective F and Anderson-Rubin confidence intervals."
          )
        )
      }
    }

    // 4. RDD bandwidth & McCrary manipulation test
    if (l.contains("regression discontinuity") || Regex("""\brdd\b""").containsMatchIn(l)) {
      if (!l.contains("mccrary") && !l.contains("density test") && !l.contains("manipulation")) {
        out.add(
          DomainAuditFinding(
            findingId = id(),
            domainModule = "Econometrics/RDD",
            criterion = "RDD Running-Variable Manipulation & Bandwidth",
            findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
            status = QuantFindingStatus.WARN,
            detail = "RDD analysis lacks a McCrary / Cattaneo-Jansson-Ma density manipulation test and bandwidth sensitivity checks.",
            consequence = "Sorting around the cutoff invalidates local randomization.",
            recommendedAction = "Report density continuity test at the cutoff, covariate balance, and MSE-optimal / bias-corrected robust CIs."
          )
        )
      }
    }

    // 5. Clustering level & small-cluster inference (G < 42)
    val clusterMatch = Regex("""(\d{1,3})\s+(?:clusters|states|counties|districts|municipalities|provinces|firms|schools)\b""", RegexOption.IGNORE_CASE).find(text)
    val nClusters = clusterMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val hasClusteredSe = l.contains("clustered") || l.contains("cluster-robust") || l.contains("cluster robust")
    val hasPanel = l.contains("panel") || l.contains("state-year") || l.contains("county-year") || l.contains("firm-year")
    if (hasPanel && !hasClusteredSe && (l.contains("hc1") || l.contains("white robust") || l.contains("homoskedastic") || l.contains("ols standard error"))) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Econometrics/Inference",
          criterion = "Serial Correlation & Cluster-Robust Variance",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Panel data inference relies on non-clustered (homoskedastic/HC1) standard errors despite within-unit serial correlation (Bertrand-Duflo-Mullainathan).",
          consequence = "Standard errors can be understated by 2x–5x, inflating rejection rates well above 5%.",
          recommendedAction = "Cluster standard errors at the level of treatment assignment (or higher unit) and verify with wild cluster bootstrap."
        )
      )
    } else if (nClusters != null && nClusters in 2..41 && hasClusteredSe && !l.contains("wild") && !l.contains("bootstrap") && !l.contains("bell-mccaffrey") && !l.contains("cr2")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Econometrics/Inference",
          criterion = "Small-Cluster Inference (G = $nClusters < 42)",
          findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
          status = QuantFindingStatus.WARN,
          detail = "Asymptotic cluster-robust SEs used with only G = $nClusters clusters without wild cluster bootstrap or CR2/Bell-McCaffrey small-sample degrees-of-freedom adjustment.",
          consequence = "Cluster-robust Wald tests over-reject when G is small or cluster sizes are unbalanced.",
          recommendedAction = "Compute Webb/Rademacher wild cluster bootstrap p-values or CR2 Satterthwaite degrees of freedom."
        )
      )
    }

    // 6. Bad controls / post-treatment collider & omitted-confounding sensitivity
    if (Regex("""control(?:led|ling)?\s+for\s+post-?treatment""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Econometrics/Causal",
          criterion = "Post-Treatment / Bad Control Bias",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Specification conditions on a post-treatment variable (bad control / collider / mediator).",
          consequence = "Induces collider bias or blocks causal pathways, biasing the total treatment effect.",
          recommendedAction = "Remove post-treatment controls from the main causal adjustment set and justify covariates via a DAG."
        )
      )
    }
    if (!l.contains("oster") && !l.contains("cinelli") && !l.contains("e-value") && !l.contains("sensitivity to unmeasured")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Econometrics/Sensitivity",
          criterion = "Omitted-Confounding Sensitivity",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "No formal sensitivity analysis for unmeasured confounding (Oster δ, Cinelli-Hazlett partial R², or E-value) detected.",
          consequence = "Robustness of causal estimate to unobserved selection remains unquantified.",
          recommendedAction = "Report Oster's δ or Cinelli-Hazlett robustness value (RV) for primary causal estimates."
        )
      )
    }

    return out
  }

  // ============================================================================
  // MODULE 4: MACHINE LEARNING & COMPUTATIONAL BENCHMARKS
  // ============================================================================
  private fun auditMachineLearningComputational(
    text: String,
    l: String,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): List<DomainAuditFinding> {
    val out = mutableListOf<DomainAuditFinding>()
    var idx = 1
    fun id() = "ML-%02d".format(Locale.US, idx++)

    // 1. Preprocessing / SMOTE / Normalization / Imputation leakage BEFORE train-test split
    val preprocessingBeforeSplit = Regex(
      """(?:normaliz\w+|standardiz\w+|z-?scor\w+|imput\w+|smote|oversampl\w+|feature\s+selection|pca)\s+(?:(?:was|were)\s+)?(?:(?:performed|applied|fitted|computed|conducted|done)\s+)?(?:on\s+the\s+(?:entire|full|whole|complete)\s+dataset|before\s+(?:the\s+)?(?:train|split|cross-?validation)|prior\s+to\s+splitting)""",
      RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
    if (preprocessingBeforeSplit) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "ML/Leakage",
          criterion = "Preprocessing / Imputation / Resampling Data Leakage",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Preprocessing, imputation, feature selection, or SMOTE resampling was executed on the full dataset BEFORE train/test splitting.",
          consequence = "Test-set distributional information leaks into training, inflating reported AUROC/accuracy/F1.",
          recommendedAction = "Fit all scalers, imputers, SMOTE, and feature selectors strictly inside each training fold (e.g., sklearn Pipeline + nested CV)."
        )
      )
    }

    // 2. Subject-level / repeated-patient / temporal leakage
    val repeatedPatientRandomSplit = (l.contains("multiple images per patient") || l.contains("multiple visits per patient") ||
      l.contains("repeated measurements") || l.contains("slices per patient") || l.contains("records per subject") || l.contains("multiple scans per patient")) &&
      (l.contains("randomly split") || l.contains("random split") || l.contains("random 80/20") || l.contains("image-level split") || l.contains("slice-level")) &&
      !l.contains("patient-level split") && !l.contains("subject-level split") && !l.contains("grouped") && !l.contains("groupkfold") &&
      !l.contains("split subjects") && !l.contains("split patients") && !l.contains("patient-disjoint") && !l.contains("subject-disjoint")
    if (repeatedPatientRandomSplit) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "ML/Leakage",
          criterion = "Subject-Level / Repeated-Patient Split Leakage",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Dataset contains multiple records/scans per patient, yet train/test partitioning was performed randomly at the record/image level instead of patient/subject level.",
          consequence = "Models memorize patient identity rather than disease signal, invalidating generalization metrics.",
          recommendedAction = "Enforce strict patient-grouped splits (GroupKFold / site-level holdout) and re-evaluate."
        )
      )
    }

    val testSetTuningLeakage = Regex(
      """(?:hyperparameter|threshold|cutoff|early\s+stopping|model\s+selection)[^.\n]{0,60}(?:tuned|selected|optimized|chosen)\s+on\s+the\s+test\s+set""",
      RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
    if (testSetTuningLeakage) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "ML/Leakage",
          criterion = "Hyperparameter / Threshold Tuning on Test Set",
          findingType = StatisticalFindingType.MODEL_MISMATCH,
          status = QuantFindingStatus.FAIL,
          detail = "Hyperparameters or decision thresholds were tuned directly on the held-out test set.",
          consequence = "Test metrics reflect optimistic selection bias rather than out-of-sample performance.",
          recommendedAction = "Use nested cross-validation or a locked validation split strictly separated from the final test set."
        )
      )
    }

    // 3. Calibration (Brier, ECE, calibration slope) & PR-AUC under imbalance
    val hasProbClassification = l.contains("auroc") || l.contains("roc-auc") || l.contains("auc") || l.contains("classifier") || l.contains("prediction model")
    val hasCalibration = l.contains("calibration") || l.contains("brier") || l.contains("ece") || l.contains("expected calibration error")
    if (hasProbClassification && !hasCalibration) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "ML/Calibration",
          criterion = "Probability Calibration (Brier / ECE / Slope)",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "Discrimination (AUROC/accuracy) reported without probability calibration metrics (Brier score, ECE, calibration slope/intercept).",
          consequence = "High AUROC can coexist with severely miscalibrated risk probabilities.",
          recommendedAction = "Report calibration curve, Brier score, and Expected Calibration Error (ECE)."
        )
      )
    }

    val hasClassImbalance = l.contains("imbalanced") || l.contains("rare event") || Regex("""prevalence\s*(?:of|was|=)\s*[0-5](?:\.\d+)?%""").containsMatchIn(l)
    if (hasClassImbalance && !l.contains("pr-auc") && !l.contains("auprc") && !l.contains("average precision")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "ML/Metrics",
          criterion = "PR-AUC Under Class Imbalance",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          detail = "Severe class imbalance detected without Precision-Recall AUC (PR-AUC / AUPRC) alongside AUROC.",
          consequence = "AUROC appears deceptively optimistic when true negatives dominate.",
          recommendedAction = "Report PR-AUC (relative to baseline prevalence) and decision-curve net benefit."
        )
      )
    }

    // 4. Uncertainty (CIs / seed variability / paired significance: DeLong, McNemar)
    val hasUncertainty = ledger.claims.any { !it.isFromReferenceOrCitation && it.ciLower != null } ||
      l.contains("bootstrap") || l.contains("confidence interval") || l.contains("±") || l.contains("seeds")
    if (!hasUncertainty) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "ML/Uncertainty",
          criterion = "Confidence Intervals & Seed Variability",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          detail = "Benchmark metrics reported as single-run point estimates without bootstrap CIs, multi-seed variance, or paired significance tests (DeLong / McNemar).",
          consequence = "Apparent leaderboard gains may lie within Monte Carlo / resampling noise.",
          recommendedAction = "Report >=5 random seeds, 1,000-resample bootstrap 95% CIs, and paired DeLong/McNemar tests."
        )
      )
    }

    if (!l.contains("external validation") && !l.contains("out-of-distribution") && !l.contains("domain shift") && !l.contains("temporal validation")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "ML/Generalization",
          criterion = "External Validation & Distribution Shift",
          findingType = StatisticalFindingType.EXTERNAL_VALIDITY_LIMIT,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "Evaluation relies solely on internal splits without external cohort validation, temporal holdout, or subgroup fairness audit.",
          consequence = "Performance under real-world covariate/site shift is unverified.",
          recommendedAction = "Evaluate on an external/temporal dataset and report subgroup performance parity."
        )
      )
    }

    return out
  }

  // ============================================================================
  // MODULE 5: FORMAL MATHEMATICS & THEORETICAL PROOFS
  // ============================================================================
  private fun auditFormalMathematics(text: String, l: String): List<DomainAuditFinding> {
    val out = mutableListOf<DomainAuditFinding>()
    var idx = 1
    fun id() = "MATH-%02d".format(Locale.US, idx++)

    // 1. Limit / Integral / Sum interchange without Dominated / Monotone Convergence or Fubini-Tonelli
    val hasInterchange = (l.contains("interchang") && (l.contains("limit") || l.contains("integral") || l.contains("sum"))) ||
      (l.contains("lim") && l.contains("integral")) || (l.contains("exchang") && l.contains("expectation"))
    val justifiesInterchange = l.contains("dominated convergence") || l.contains("monotone convergence") ||
      l.contains("fubini") || l.contains("tonelli") || l.contains("uniform convergence") || l.contains("fatou")
    if (hasInterchange && !justifiesInterchange) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Formal Math",
          criterion = "Limit / Integral Interchange Justification",
          findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
          status = QuantFindingStatus.FAIL,
          detail = "Proof interchanges limits, integrals, or infinite sums without invoking Dominated Convergence (DCT), Monotone Convergence (MCT), Fubini-Tonelli, or uniform convergence.",
          consequence = "Pointwise convergence alone does not permit passing limits inside integrals or sums; counterexamples exist.",
          recommendedAction = "Establish an integrable dominating function (DCT), non-negativity (Tonelli/MCT), or uniform convergence on compact support."
        )
      )
    }

    // 2. Convergence mode ambiguity
    if (l.contains("converges") && !l.contains("uniformly") && !l.contains("pointwise") && !l.contains("almost surely") &&
      !l.contains("in probability") && !l.contains("in distribution") && !l.contains("in l^") && !l.contains("in l2") && !l.contains("weakly")
    ) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Formal Math",
          criterion = "Convergence Mode Specification",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          detail = "Theorem/proof states 'converges' without specifying the topology or mode (pointwise, uniform, L^p, in probability, almost surely, weak).",
          consequence = "Downstream continuity or expectation preservation claims depend critically on convergence mode.",
          recommendedAction = "Explicitly state the norm/topology of convergence in every theorem and lemma."
        )
      )
    }

    // 3. Domain guards: division by zero or log of non-positive argument
    val hasDivOrLog = Regex("""(?:1\s*/\s*\([^)]+\)|log\s*\([^)]+\)|ln\s*\([^)]+\))""").containsMatchIn(text)
    val withoutQuantifierEps = l.replace(Regex("""(?:epsilon|delta|ε|δ)\s*>\s*0"""), "")
    val statesPositivityGuard = withoutQuantifierEps.contains("> 0") ||
      withoutQuantifierEps.contains("strictly positive") ||
      withoutQuantifierEps.contains("non-zero") ||
      withoutQuantifierEps.contains("nonzero") ||
      withoutQuantifierEps.contains("≠ 0") ||
      withoutQuantifierEps.contains("!= 0")
    if (hasDivOrLog && !statesPositivityGuard) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Formal Math",
          criterion = "Domain Guards (Division-by-Zero / Logarithm Positivity)",
          findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
          status = QuantFindingStatus.WARN,
          detail = "Expressions involve division or logarithms without explicit strict-positivity / non-zero denominator hypotheses.",
          consequence = "Proof breaks on boundary cases where denominators vanish or log arguments hit zero.",
          recommendedAction = "State explicit domain bounds (> 0 or ≠ 0) and verify boundary/degenerate cases."
        )
      )
    }

    // 4. Quantifier order / hidden compactness / measurability / existence & uniqueness
    if ((l.contains("for all") || l.contains("∀")) && (l.contains("there exists") || l.contains("∃")) &&
      !l.contains("independent of") && !l.contains("uniform in") && l.contains("uniform")
    ) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Formal Math",
          criterion = "Quantifier Order & Uniformity Dependence",
          findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "Mixed universal/existential quantifiers alongside uniformity claim; verify that witness constants do not implicitly depend on quantified indices (∀ε ∃δ vs ∃δ ∀ε).",
          consequence = "Swapped quantifier order invalidates uniform bounds.",
          recommendedAction = "Explicitly track constant dependencies (e.g., C = C(d, p) independent of n)."
        )
      )
    }

    // 5. Existence & uniqueness / measurability / dimensional consistency
    if ((l.contains("unique") || l.contains("uniqueness")) && !l.contains("strictly convex") && !l.contains("contraction") && !l.contains("suppose") && !l.contains("injective")) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Formal Math",
          criterion = "Existence & Uniqueness Verification",
          findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "Uniqueness claimed without explicit contraction, strict convexity, or two-solution contradiction argument detected in text.",
          consequence = "Existence alone does not establish uniqueness.",
          recommendedAction = "Include a complete uniqueness proof (e.g., assume two solutions u, v and prove ||u - v|| = 0)."
        )
      )
    }

    // 6. Notation collision heuristic: same single-letter variable defined in two incompatible roles
    val hasNotationCollision = Regex("""\blet\s+([a-zA-Z])\s+(?:be|denote)\s+[^.,;\n]{1,35}?\b(matrix|set|function|space|constant|integer|vector|operator)\b""", RegexOption.IGNORE_CASE)
      .findAll(text)
      .groupBy { it.groupValues[1] }
      .any { (_, defs) -> defs.map { it.groupValues[2].lowercase(Locale.US) }.distinct().size >= 2 }
    if (hasNotationCollision) {
      out.add(
        DomainAuditFinding(
          findingId = id(),
          domainModule = "Formal Math",
          criterion = "Notation Collision Across Definitions",
          findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
          status = QuantFindingStatus.WARN,
          detail = "Same symbol appears to be defined with incompatible mathematical types (e.g., scalar/constant vs matrix/function/set).",
          consequence = "Ambiguous symbol overloading undermines proof readability and dimensional validity.",
          recommendedAction = "Assign distinct symbols to distinct mathematical objects and add a notation table."
        )
      )
    }

    return out
  }

  private fun auditGeneralEmpirical(
    l: String,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): List<DomainAuditFinding> {
    val out = mutableListOf<DomainAuditFinding>()
    val active = ledger.claims.filter { !it.isFromReferenceOrCitation }
    if (active.any { it.pValue != null } && active.none { it.ciLower != null } && !l.contains("effect size")) {
      out.add(
        DomainAuditFinding(
          findingId = "GEN-01",
          domainModule = "General Empirical",
          criterion = "Effect Size & Precision Interval Reporting",
          findingType = StatisticalFindingType.REPORTING_OMISSION,
          status = QuantFindingStatus.WARN,
          isScreeningSignal = true,
          detail = "Hypothesis-test p-values reported without accompanying effect sizes or confidence intervals.",
          consequence = "Statistical significance alone cannot establish practical or scientific magnitude.",
          recommendedAction = "Report point estimates with 95% confidence intervals alongside p-values."
        )
      )
    }
    return out
  }
}

/**
 * Unified Phase 1–5 Orchestrator invoked automatically by [com.example.data.ai.QuantitativeForensicAnalyzer].
 */
object StatisticalAuditOrchestrator {

  fun audit(
    text: String,
    parentArchetype: StudyDesignArchetype,
    coverageReport: DocumentCoverageReport? = null,
    extractionConfidence: Int = 5
  ): StatisticalAuditReport {
    val ledger = StatisticalClaimParser.parse(text)
    val sanitized = ledger.sanitizedResult ?: SemanticValidityEngine.sanitizePdfExtractionNoise(text)
    val cleanText = sanitized.text

    val subDesign = DomainStatisticalAuditModules.classifySubDesign(cleanText, parentArchetype)
    val designClassification = SemanticValidityEngine.classifyDesignWithConfidence(cleanText, parentArchetype)
    val reconstructions = NumericalReconstructionEngine.runAllReconstructions(ledger, cleanText)
    val rawDomainFindings = DomainStatisticalAuditModules.runDomainAudit(cleanText, subDesign, ledger, reconstructions)

    // Phase 8 #2 Classifier-Confidence Gate: when design classification abstains due to low confidence
    // or ambiguous competing designs, never escalate design-sensitive MODEL_MISMATCH to hard FAIL!
    val gatedDomainFindings = if (designClassification.abstained) {
      rawDomainFindings.map { finding ->
        if (finding.status == QuantFindingStatus.FAIL && finding.findingType == StatisticalFindingType.MODEL_MISMATCH) {
          finding.copy(
            status = QuantFindingStatus.WARN,
            isScreeningSignal = true,
            detail = "[Classifier-Confidence Gate: design confidence ${String.format(Locale.US, "%.2f", designClassification.primaryConfidence)} — downgraded from FAIL to screening prompt] ${finding.detail}"
          )
        } else {
          finding
        }
      }
    } else {
      rawDomainFindings
    }

    val semanticValidity = SemanticValidityEngine.evaluate(
      rawText = text,
      sanitized = sanitized,
      designClassification = designClassification,
      ledger = ledger,
      reconstructions = reconstructions,
      coverageReport = coverageReport,
      extractionConfidence = extractionConfidence
    )

    return StatisticalAuditReport(
      subDesign = subDesign,
      claims = ledger.claims,
      twoByTwoTables = ledger.twoByTwoTables,
      metaStudyEffects = ledger.metaStudyEffects,
      sampleFlowRecords = ledger.sampleFlowRecords,
      crossLinks = ledger.crossLinks,
      reconstructions = reconstructions,
      domainFindings = gatedDomainFindings,
      unsupportedDisclosures = ledger.unsupportedDisclosures,
      designClassification = designClassification,
      semanticValidity = semanticValidity
    )
  }
}

/**
 * Phase 6: Evidence-Derived Scoring & Verdict Gating.
 *
 * Connects verified deterministic statistical findings directly to mathRigorScore,
 * methodologyScore, verdict cap, and extractionConfidence, producing a transparent
 * [ScoreCalculationBreakdown]:
 *   Base score - verified critical penalties - design-specific penalties
 *   - unsupported-evidence penalties + justified robustness credits.
 *
 * CRITICAL EPISTEMIC RULE: Missing/unextractable tables/inputs (`UNSUPPORTED_TO_ASSESS`)
 * lower `extractionConfidence`, NOT scientific validity / methodology scores!
 */
object EvidenceDerivedScoreGate {

  fun evaluate(
    report: StatisticalAuditReport,
    baseMathScore: Int,
    baseMethodologyScore: Int,
    manuscriptText: String = ""
  ): ScoreCalculationBreakdown {
    val safeBaseMath = baseMathScore.coerceIn(1, 10)
    val safeBaseMethod = baseMethodologyScore.coerceIn(1, 10)

    val verifiedCriticalPenalties = mutableListOf<ScorePenaltyItem>()
    val designSpecificPenalties = mutableListOf<ScorePenaltyItem>()
    val unsupportedEvidencePenalties = mutableListOf<ScorePenaltyItem>()
    val robustnessCredits = mutableListOf<ScorePenaltyItem>()

    // 1. Verified Arithmetic Errors (only non-screening confirmed FAILs)
    val arithFails = report.reconstructions.filter {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.ARITHMETIC_ERROR && !it.isScreeningSignal
    }
    if (arithFails.isNotEmpty()) {
      val pen = min(4, arithFails.size * 2)
      verifiedCriticalPenalties.add(
        ScorePenaltyItem(
          reason = "${arithFails.size} verified arithmetic error(s) in exact numerical reconstruction (${arithFails.take(2).joinToString("; ") { it.calculatorName }})",
          delta = -pen,
          category = "VERIFIED_CRITICAL",
          sourceFindingIds = arithFails.map { it.checkId }
        )
      )
    }

    // 2. Verified Internal Contradictions (denominator drift, subgroup > total N, CI vs p conflict, Abstract vs Results conflict)
    val contraCross = report.crossLinks.filter {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.INTERNAL_CONTRADICTION && !it.isScreeningSignal
    }
    val contraRecon = report.reconstructions.filter {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.INTERNAL_CONTRADICTION && !it.isScreeningSignal
    }
    val totalContra = contraCross.size + contraRecon.size
    if (totalContra > 0) {
      val pen = min(3, totalContra * 2)
      verifiedCriticalPenalties.add(
        ScorePenaltyItem(
          reason = "$totalContra verified internal numerical contradiction(s) (${(contraCross.map { it.linkType } + contraRecon.map { it.calculatorName }).take(2).joinToString(", ")})",
          delta = -pen,
          category = "VERIFIED_CRITICAL",
          sourceFindingIds = contraCross.map { it.linkId } + contraRecon.map { it.checkId }
        )
      )
    }

    // 3. Verified Design-Specific Model Mismatches (ML leakage, TWFE staggered bias, Fixed-Effect under high I², Competing-risk censoring, Weak IV, Immortal-time bias, Subgroup fallacy)
    val modelMismatches = report.domainFindings.filter {
      it.status == QuantFindingStatus.FAIL && (it.findingType == StatisticalFindingType.MODEL_MISMATCH || it.findingType == StatisticalFindingType.ASSUMPTION_UNCHECKED)
    }
    val reconMismatches = report.reconstructions.filter {
      it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.MODEL_MISMATCH
    }
    val mismatchCount = modelMismatches.size + reconMismatches.size
    if (mismatchCount > 0) {
      val pen = min(4, mismatchCount * 2)
      designSpecificPenalties.add(
        ScorePenaltyItem(
          reason = "$mismatchCount confirmed design/model mismatch(es): ${(modelMismatches.map { it.criterion } + reconMismatches.map { it.calculatorName }).take(2).joinToString("; ")}",
          delta = -pen,
          category = "DESIGN_SPECIFIC",
          sourceFindingIds = modelMismatches.map { it.findingId } + reconMismatches.map { it.checkId }
        )
      )
    }

    // 4. Verified Robustness Credits (only when zero confirmed errors and verified checks/robustness exist)
    val lower = manuscriptText.lowercase(Locale.US)
    val passedReconstructions = report.reconstructions.count { it.status == QuantFindingStatus.PASS }
    val hasZeroConfirmedErrors = arithFails.isEmpty() && totalContra == 0 && mismatchCount == 0
    if (hasZeroConfirmedErrors && passedReconstructions >= 2) {
      robustnessCredits.add(
        ScorePenaltyItem(
          reason = "$passedReconstructions exact numerical reconstruction(s) passed with zero arithmetic or model discrepancies",
          delta = +1,
          category = "ROBUSTNESS_CREDIT"
        )
      )
    } else if (hasZeroConfirmedErrors && (lower.contains("sensitivity analysis") || lower.contains("hartung-knapp") || lower.contains("callaway") || lower.contains("nested cross-validation") || lower.contains("dominated convergence"))) {
      robustnessCredits.add(
        ScorePenaltyItem(
          reason = "Documented design-appropriate robustness/sensitivity checks with zero deterministic errors",
          delta = +1,
          category = "ROBUSTNESS_CREDIT"
        )
      )
    }

    // 5. Unsupported inputs -> NEVER penalize scientific validity; ONLY reduce extractionConfidence!
    val unsupportedCount = report.unsupportedDisclosures.size +
      report.reconstructions.count { it.findingType == StatisticalFindingType.UNSUPPORTED_TO_ASSESS }
    val extractionAdjustment = when {
      unsupportedCount >= 3 -> -2
      unsupportedCount >= 1 -> -1
      else -> 0
    }
    val extractionReason = if (extractionAdjustment < 0) {
      "$unsupportedCount statistical verification domain(s) marked UNSUPPORTED_TO_ASSESS due to missing raw tables, SEs, or figure-only data."
    } else {
      ""
    }

    // 6. Hard score caps & verdict gate derived strictly from verified errors
    val mathCap: Int? = when {
      arithFails.isNotEmpty() -> 4
      totalContra > 0 -> 5
      mismatchCount >= 2 -> 4
      mismatchCount == 1 -> 5
      else -> null
    }
    val methodCap: Int? = when {
      mismatchCount >= 2 -> 4
      mismatchCount == 1 -> 5
      totalContra > 0 -> 6
      else -> null
    }
    val verdictCapReason: String? = when {
      arithFails.isNotEmpty() -> "Verified primary arithmetic discrepancy (${arithFails.first().calculatorName}) blocks top-tier acceptance/high-credibility verdict."
      totalContra > 0 -> "Verified internal numerical contradiction (${(contraCross.firstOrNull()?.linkType ?: contraRecon.first().calculatorName)}) blocks top-tier verdict."
      mismatchCount > 0 -> "Verified design/model mismatch (${(modelMismatches.firstOrNull()?.criterion ?: reconMismatches.first().calculatorName)}) caps verdict pending re-analysis."
      else -> null
    }

    val totalMathDelta = verifiedCriticalPenalties.sumOf { it.delta } +
      designSpecificPenalties.sumOf { it.delta } +
      robustnessCredits.sumOf { it.delta }
    val totalMethodDelta = designSpecificPenalties.sumOf { it.delta } +
      (if (totalContra > 0) -1 else 0) +
      robustnessCredits.sumOf { it.delta }

    val uncappedMath = (safeBaseMath + totalMathDelta).coerceIn(1, 10)
    val uncappedMethod = (safeBaseMethod + totalMethodDelta).coerceIn(1, 10)

    val finalMath = if (mathCap != null) min(uncappedMath, mathCap) else uncappedMath
    val finalMethod = if (methodCap != null) min(uncappedMethod, methodCap) else uncappedMethod

    val formula = "Math: $safeBaseMath -> $finalMath (cap=${mathCap ?: "none"}); " +
      "Methodology: $safeBaseMethod -> $finalMethod (cap=${methodCap ?: "none"}); " +
      "ExtractionConfidenceDelta=$extractionAdjustment"

    return ScoreCalculationBreakdown(
      baseMathScore = safeBaseMath,
      baseMethodologyScore = safeBaseMethod,
      verifiedCriticalPenalties = verifiedCriticalPenalties,
      designSpecificPenalties = designSpecificPenalties,
      unsupportedEvidencePenalties = unsupportedEvidencePenalties,
      robustnessCredits = robustnessCredits,
      finalMathScore = finalMath,
      finalMethodologyScore = finalMethod,
      mathScoreCap = mathCap,
      methodologyScoreCap = methodCap,
      verdictCapReason = verdictCapReason,
      extractionConfidenceAdjustment = extractionAdjustment,
      extractionConfidenceReason = extractionReason,
      formulaSummary = formula
    )
  }
}
