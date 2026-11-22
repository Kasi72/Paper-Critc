package com.example.data.ai.epistemic

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.StatisticalAuditReport
import com.example.data.ai.stats.StatisticalFindingType
import com.example.util.DocumentCoverageReport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic Epistemic Safeguard Engine implementing Safeguards #1–#12 and #15.
 * Designed to prevent confident misinterpretation of study designs, missing evidence,
 * denominator shifts, claim overreach, and clinically trivial or underpowered results.
 */
object EpistemicSafeguardEngine {

  fun evaluate(
    text: String,
    statAudit: StatisticalAuditReport,
    coverageReport: DocumentCoverageReport? = null,
    extractionConfidence: Int = 5
  ): EpistemicSafeguardReport {
    val clean = text.replace("\r\n", "\n")
    val lower = clean.lowercase(Locale.US)
    val subDesign = statAudit.subDesign

    val denominatorLedger = buildDenominatorLedger(clean, lower, statAudit)
    val identityCard = buildStudyIdentityCard(clean, lower, subDesign, statAudit, denominatorLedger, coverageReport)
    val primaryDecl = buildPrimaryAnalysisDeclaration(clean, lower, subDesign, statAudit)
    val claimAudit = buildClaimHierarchyAudit(clean, lower, subDesign)
    val contradictionMap = buildContradictionMap(clean, lower, statAudit, denominatorLedger, claimAudit)
    val practicalGate = buildPracticalSignificanceGate(clean, lower, statAudit)
    val altExplanations = buildAlternativeExplanations(clean, lower, subDesign, statAudit, denominatorLedger)
    val fragilityItems = buildConclusionFragility(subDesign, statAudit, denominatorLedger, claimAudit, practicalGate)
    val stopAndAsk = buildStopAndAskAssessment(clean, lower, identityCard, denominatorLedger, primaryDecl, statAudit, coverageReport, extractionConfidence)
    val uncertaintyBudget = buildUncertaintyBudget(lower, subDesign, statAudit, denominatorLedger, coverageReport, extractionConfidence, stopAndAsk)
    val overturnConditions = buildOverturnConditions(denominatorLedger, claimAudit, contradictionMap, statAudit)

    return EpistemicSafeguardReport(
      studyIdentityCard = identityCard,
      denominatorLedger = denominatorLedger,
      claimHierarchyAudit = claimAudit,
      primaryAnalysisDeclaration = primaryDecl,
      conclusionFragility = fragilityItems,
      contradictionMap = contradictionMap,
      alternativeExplanations = altExplanations,
      practicalSignificanceGate = practicalGate,
      uncertaintyBudget = uncertaintyBudget,
      overturnConditions = overturnConditions,
      stopAndAsk = stopAndAsk
    )
  }

  // ============================================================================
  // #1. STUDY IDENTITY CARD (Forced before methodological critique)
  // ============================================================================
  private fun buildStudyIdentityCard(
    text: String,
    l: String,
    subDesign: AuditSubDesign,
    statAudit: StatisticalAuditReport,
    ledger: DenominatorLedger,
    coverage: DocumentCoverageReport?
  ): StudyIdentityCard {
    val hasExplicitDesignKeyword = l.contains("randomized") || l.contains("randomised") || l.contains("cohort") ||
      l.contains("case-control") || l.contains("cross-sectional") || l.contains("meta-analysis") ||
      l.contains("systematic review") || l.contains("difference-in-differences") || l.contains("instrumental variable") ||
      l.contains("regression discontinuity") || l.contains("diagnostic accuracy") || l.contains("kaplan-meier") ||
      l.contains("cox proportional") || l.contains("neural network") || l.contains("transformer") ||
      l.contains("benchmark") || l.contains("theorem") || l.contains("proof") || l.contains("descriptive") || l.contains("survey")

    val isCrossSectional = l.contains("cross-sectional") || l.contains("cross sectional") || l.contains("prevalence survey")
    val designLabel = if (isCrossSectional && subDesign == AuditSubDesign.GENERAL_EMPIRICAL) {
      "Cross-Sectional Observational Survey"
    } else if (hasExplicitDesignKeyword) {
      subDesign.displayName
    } else {
      "Uncertain / Unspecified study design"
    }
    val designField = IdentityField(
      value = designLabel,
      status = if (hasExplicitDesignKeyword) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL
    )

    val unitOfAnalysis = when {
      subDesign == AuditSubDesign.META_ANALYSIS || subDesign == AuditSubDesign.SYSTEMATIC_REVIEW ->
        IdentityField("Published studies / trials (aggregate study-level; NOT independent patient-level rows)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.FORMAL_THEOREM ->
        IdentityField("Mathematical objects / theorems / formal proofs", ReportingStatus.REPORTED_AND_ADEQUATE)
      l.contains("state-year") || l.contains("county-year") || l.contains("firm-year") || l.contains("panel") ->
        IdentityField("Clustered panel units (e.g., state/firm/region-time observations)", ReportingStatus.REPORTED_AND_ADEQUATE)
      l.contains("patient") || l.contains("participant") || l.contains("subject") ->
        IdentityField(
          if (l.contains("multiple images per patient") || l.contains("repeated measures") || l.contains("multiple visits"))
            "Repeated observations nested within patients/subjects"
          else "Individual patients / participants",
          ReportingStatus.REPORTED_AND_ADEQUATE
        )
      l.contains("image") || l.contains("sample") || l.contains("document") ->
        IdentityField("Dataset instances / samples", ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Uncertain unit of analysis", ReportingStatus.NOT_REPORTED)
    }

    val popMatch = Regex("""(?:patients?|participants?|adults?|children|subjects?|hospitals?|states?|studies|trials|samples?)\s+(?:with|undergoing|diagnosed\s+with|aged|from|across)\s+[^.,;\n]{4,70}""", RegexOption.IGNORE_CASE).find(text)
    val population = if (popMatch != null) {
      IdentityField(popMatch.value.trim(), ReportingStatus.REPORTED_AND_ADEQUATE)
    } else if (subDesign == AuditSubDesign.FORMAL_THEOREM) {
      IdentityField("Formal domain / metric or Hilbert/Banach space", ReportingStatus.REPORTED_AND_ADEQUATE)
    } else if (l.contains("patient") || l.contains("participant") || l.contains("cohort") || l.contains("studies") || l.contains("dataset")) {
      IdentityField("Study population referenced generically (exact eligibility criteria limited)", ReportingStatus.REPORTED_BUT_INADEQUATE)
    } else {
      IdentityField("Population not identified", ReportingStatus.NOT_REPORTED)
    }

    val expMatch = Regex("""(?:intervention|treatment|drug|therapy|exposure|policy|minimum[- ]wage|statin|biomarker|classifier|model|algorithm)\s+(?:of|with|using|:)?\s*[^.,;\n]{2,55}""", RegexOption.IGNORE_CASE).find(text)
    val exposure = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM -> IdentityField("Formal hypotheses / theorem conditions", ReportingStatus.REPORTED_AND_ADEQUATE)
      expMatch != null -> IdentityField(expMatch.value.trim(), ReportingStatus.REPORTED_AND_ADEQUATE)
      l.contains("descriptive") || l.contains("normative") || l.contains("bed need") ->
        IdentityField("None (descriptive/normative estimation study)", ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Exposure / intervention not explicitly specified", ReportingStatus.NOT_REPORTED)
    }

    val compMatch = Regex("""(?:vs\.?|versus|compared\s+(?:with|to)|control\s+group|placebo|standard\s+of\s+care|usual\s+care|baseline\s+model|reference\s+standard)[^.,;\n]{0,50}""", RegexOption.IGNORE_CASE).find(text)
    val comparator = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM -> IdentityField("Prior theorem bounds / counterexample baselines", ReportingStatus.REPORTED_AND_ADEQUATE)
      compMatch != null -> IdentityField(compMatch.value.trim(), ReportingStatus.REPORTED_AND_ADEQUATE)
      l.contains("descriptive") || l.contains("prevalence") || l.contains("normative") ->
        IdentityField("No comparator (single-group descriptive/normative target)", ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Comparator not reported", ReportingStatus.NOT_REPORTED)
    }

    val outMatch = Regex("""(?:primary\s+(?:outcome|endpoint)|outcome\s+was|mortality|survival|stroke|readmission|hospitalization|auroc|accuracy|sensitivity|bed\s+need|employment|wage|convergence|uniqueness)[^.,;\n]{0,55}""", RegexOption.IGNORE_CASE).find(text)
    val outcome = if (outMatch != null) {
      IdentityField(outMatch.value.trim(), ReportingStatus.REPORTED_AND_ADEQUATE)
    } else {
      IdentityField("Primary outcome uncertain / not reported", ReportingStatus.NOT_REPORTED)
    }

    val horizonMatch = Regex("""(?:\b\d+(?:\.\d+)?[- ]?(?:day|week|month|year)s?\b(?:\s+follow-?up)?|\b(?:19|20)\d{2}\s*[-–]\s*(?:19|20)\d{2}\b|median\s+follow-?up[^.,;\n]{0,35})""", RegexOption.IGNORE_CASE).find(text)
    val timeHorizon = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM || subDesign == AuditSubDesign.ML_BENCHMARK ->
        IdentityField(horizonMatch?.value?.trim() ?: "Not time-horizon dependent (static formal/benchmark evaluation)", ReportingStatus.REPORTED_AND_ADEQUATE)
      horizonMatch != null -> IdentityField(horizonMatch.value.trim(), ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Follow-up / time horizon not reported", ReportingStatus.NOT_REPORTED)
    }

    val estimand = when {
      l.contains("intention-to-treat") || l.contains("intent-to-treat") -> IdentityField("Intention-to-treat (treatment policy estimand)", ReportingStatus.REPORTED_AND_ADEQUATE)
      l.contains("per-protocol") -> IdentityField("Per-protocol estimand (vulnerable to post-randomization selection)", ReportingStatus.REPORTED_BUT_INADEQUATE)
      l.contains("att") || l.contains("average treatment effect on the treated") -> IdentityField("Average Treatment Effect on the Treated (ATT)", ReportingStatus.REPORTED_AND_ADEQUATE)
      l.contains("ate") || l.contains("average treatment effect") -> IdentityField("Average Treatment Effect (ATE)", ReportingStatus.REPORTED_AND_ADEQUATE)
      l.contains("late") || l.contains("local average treatment") -> IdentityField("Local Average Treatment Effect (LATE / IV)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.META_ANALYSIS -> IdentityField("Pooled summary effect across heterogeneous studies (random/fixed)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.DIAGNOSTIC_ACCURACY -> IdentityField("Diagnostic accuracy (sensitivity, specificity, likelihood ratios)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.ML_BENCHMARK -> IdentityField("Out-of-sample predictive performance (AUROC / PR-AUC / calibration)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.FORMAL_THEOREM -> IdentityField("Deductive mathematical truth under stated regularity assumptions", ReportingStatus.REPORTED_AND_ADEQUATE)
      isCrossSectional -> IdentityField("Cross-sectional descriptive / associational prevalence contrast", ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Primary estimand not explicitly declared", ReportingStatus.NOT_REPORTED)
    }

    val analysisMatch = Regex("""(?:cox\s+proportional\s+hazards|logistic\s+regression|linear\s+regression|two-way\s+fixed\s+effects|difference-in-differences|2sls|random-effects\s+meta-analysis|fixed-effect\s+meta-analysis|kaplan-meier|mixed-effects|gee|transformer|neural\s+network|random\s+forest|xgboost|anova|chi-square|t-test|proof\s+by\s+contradiction)""", RegexOption.IGNORE_CASE).find(text)
    val primaryAnalysis = if (analysisMatch != null) {
      IdentityField(analysisMatch.value.trim(), ReportingStatus.REPORTED_AND_ADEQUATE)
    } else if (statAudit.claims.any { !it.isFromReferenceOrCitation && it.analysisType.isNotBlank() }) {
      IdentityField(statAudit.claims.first { !it.isFromReferenceOrCitation && it.analysisType.isNotBlank() }.analysisType, ReportingStatus.REPORTED_AND_ADEQUATE)
    } else {
      IdentityField("Primary statistical/analytical model not explicitly identified", ReportingStatus.NOT_REPORTED)
    }

    val denomField = if (ledger.entries.isNotEmpty()) {
      val summary = ledger.entries.groupBy { it.category }.entries.take(4)
        .joinToString("; ") { (cat, list) -> "${cat.label}: N=${list.maxOf { it.n }}" }
      IdentityField(
        value = summary,
        status = if (ledger.discrepancies.any { it.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION })
          ReportingStatus.REPORTED_BUT_INADEQUATE else ReportingStatus.REPORTED_AND_ADEQUATE
      )
    } else if (subDesign == AuditSubDesign.FORMAL_THEOREM) {
      IdentityField("Not applicable (deductive theorem proof)", ReportingStatus.REPORTED_AND_ADEQUATE)
    } else if (coverage != null && coverage.omittedPages.isNotEmpty()) {
      IdentityField("Denominator not reconstructable from partial extract", ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL)
    } else {
      IdentityField("Analytical sample size / denominator not extracted", ReportingStatus.NOT_REPORTED)
    }

    val uncertain = buildList {
      if (designField.status != ReportingStatus.REPORTED_AND_ADEQUATE) add("Study design")
      if (unitOfAnalysis.status != ReportingStatus.REPORTED_AND_ADEQUATE) add("Unit of analysis")
      if (population.status == ReportingStatus.NOT_REPORTED || population.status == ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL) add("Population")
      if (exposure.status == ReportingStatus.NOT_REPORTED) add("Exposure/Intervention")
      if (comparator.status == ReportingStatus.NOT_REPORTED) add("Comparator")
      if (outcome.status == ReportingStatus.NOT_REPORTED) add("Primary outcome")
      if (timeHorizon.status == ReportingStatus.NOT_REPORTED) add("Time horizon")
      if (estimand.status == ReportingStatus.NOT_REPORTED) add("Primary estimand")
      if (primaryAnalysis.status == ReportingStatus.NOT_REPORTED) add("Primary analysis")
      if (denomField.status == ReportingStatus.NOT_REPORTED || denomField.status == ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL) add("Sample size / denominator")
    }

    return StudyIdentityCard(
      studyDesign = designField,
      subDesign = subDesign,
      unitOfAnalysis = unitOfAnalysis,
      population = population,
      exposureOrIntervention = exposure,
      comparator = comparator,
      outcome = outcome,
      timeHorizon = timeHorizon,
      primaryEstimand = estimand,
      primaryAnalysis = primaryAnalysis,
      sampleSizeAndDenominator = denomField,
      uncertainFields = uncertain
    )
  }

  // ============================================================================
  // #3. DENOMINATOR LEDGER
  // ============================================================================
  private fun buildDenominatorLedger(
    text: String,
    l: String,
    statAudit: StatisticalAuditReport
  ): DenominatorLedger {
    val entries = mutableListOf<DenominatorEntry>()
    val discrepancies = mutableListOf<DenominatorDiscrepancy>()

    // Map SampleFlowRecords from StatisticalClaimParser into strict DenominatorCategories
    statAudit.sampleFlowRecords.forEach { rec ->
      val cat = when {
        rec.label.contains("randomiz", true) || rec.label.contains("randomis", true) -> DenominatorCategory.RANDOMIZED
        rec.stage == "ENROLLED" -> DenominatorCategory.ENROLLED
        rec.stage == "ANALYZED" -> DenominatorCategory.ANALYZED
        rec.stage == "SUBGROUP" -> DenominatorCategory.SUBGROUP
        rec.tableFigure.contains("Table", true) -> DenominatorCategory.TABLE_INCLUDED
        rec.stage == "TOTAL_N" -> DenominatorCategory.ENROLLED
        else -> null
      }
      if (cat != null) {
        entries.add(DenominatorEntry(cat, rec.n, rec.label, rec.page, rec.section, rec.tableFigure, rec.sourceQuote))
      }
    }

    // Extract dedicated Followed-Up, Complete-Data, Outcome-Included, Subgroup, and Table-Included counts
    val extraPatterns = listOf(
      DenominatorCategory.FOLLOWED_UP to Regex("""\b(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?)\s+(?:completed\s+follow-?up|were\s+followed(?:\s+up)?|remained\s+at\s+follow-?up)\b""", RegexOption.IGNORE_CASE),
      DenominatorCategory.COMPLETE_DATA to Regex("""\b(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?|records?)\s+(?:had|with)\s+complete(?:\s+\w+)?\s+data\b""", RegexOption.IGNORE_CASE),
      DenominatorCategory.OUTCOME_INCLUDED to Regex("""\b(?:primary\s+outcome|mortality|endpoint)[^.\n]{0,40}?\(\s*[Nn]\s*=\s*(\d{1,3}(?:,\d{3})*|\d{2,6})\s*\)""", RegexOption.IGNORE_CASE),
      DenominatorCategory.TABLE_INCLUDED to Regex("""\bTable\s*\d+[^.\n]{0,50}?\(\s*[Nn]\s*=\s*(\d{1,3}(?:,\d{3})*|\d{2,6})\s*\)""", RegexOption.IGNORE_CASE),
      DenominatorCategory.SUBGROUP to Regex("""\b(?:subgroup|stratum|subset|sub-?cohort)[^.\n]{0,45}?\(\s*[Nn]\s*=\s*(\d{1,3}(?:,\d{3})*|\d{2,6})\s*\)""", RegexOption.IGNORE_CASE)
    )
    for ((cat, regex) in extraPatterns) {
      regex.findAll(text).take(10).forEach { m ->
        val n = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@forEach
        val pg = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, m.range.first)
        if (entries.none { it.category == cat && it.n == n }) {
          entries.add(DenominatorEntry(cat, n, m.value.trim().take(55), pg, "Body", if (cat == DenominatorCategory.TABLE_INCLUDED) m.value.take(10) else "", m.value.trim()))
        }
      }
    }

    // 1. Percentage-vs-denominator mismatches (from NumericalReconstructionEngine proportion checks)
    statAudit.reconstructions.filter { it.calculatorName.contains("Proportion") && it.status == QuantFindingStatus.FAIL }.forEach { r ->
      discrepancies.add(
        DenominatorDiscrepancy(
          discrepancyType = "PERCENTAGE_MISMATCH",
          severityTier = FlawSeverityTier.REQUIRES_REPORTING_CORRECTION,
          reportingStatus = ReportingStatus.REPORTED_BUT_INADEQUATE,
          description = r.detail,
          pages = listOf(r.pageNumber).filter { it > 0 },
          sourceQuotes = listOf(r.sourceEvidence),
          overturnEvidence = "Corrected numerator/denominator table or confirmation of weighted/imputed percentages."
        )
      )
    }

    // 2. Lift cross-links (subgroup > parent, denominator drift, impossible attrition / flow mismatch)
    statAudit.crossLinks.forEach { link ->
      when (link.linkType) {
        "SUBGROUP_EXCEEDS_TOTAL" -> discrepancies.add(
          DenominatorDiscrepancy(
            discrepancyType = "SUBGROUP_EXCEEDS_PARENT",
            severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
            reportingStatus = ReportingStatus.REPORTED_BUT_INADEQUATE,
            description = link.description,
            pages = link.pageNumbers,
            sourceQuotes = link.sourceQuotes,
            overturnEvidence = "Corrected subgroup table confirming non-overlapping strata or explicit note that subgroups are overlapping."
          )
        )
        "DENOMINATOR_DRIFT" -> discrepancies.add(
          DenominatorDiscrepancy(
            discrepancyType = "TABLE_DENOMINATOR_DRIFT",
            severityTier = if (link.status == QuantFindingStatus.FAIL) FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION else FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE,
            reportingStatus = ReportingStatus.REPORTED_BUT_INADEQUATE,
            description = link.description,
            pages = link.pageNumbers,
            sourceQuotes = link.sourceQuotes,
            overturnEvidence = "CONSORT/STROBE flow diagram or supplementary exclusion table reconciling each denominator change."
          )
        )
        "MISSING_DATA_FLOW_MISMATCH" -> discrepancies.add(
          DenominatorDiscrepancy(
            discrepancyType = "IMPOSSIBLE_ATTRITION",
            severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
            reportingStatus = ReportingStatus.REPORTED_BUT_INADEQUATE,
            description = link.description,
            pages = link.pageNumbers,
            sourceQuotes = link.sourceQuotes,
            overturnEvidence = "Reconciled participant disposition table accounting for every screened, enrolled, excluded, lost, and analyzed unit."
          )
        )
      }
    }

    // 3. Direct DenominatorLedger consistency checks (self-contained even if parser cross-links didn't emit)
    val randN = entries.filter { it.category == DenominatorCategory.RANDOMIZED }.maxOfOrNull { it.n }
      ?: entries.filter { it.category == DenominatorCategory.ENROLLED }.maxOfOrNull { it.n }
    val analyzedN = entries.filter { it.category == DenominatorCategory.ANALYZED }.minOfOrNull { it.n }
      ?: entries.filter { it.category == DenominatorCategory.COMPLETE_DATA }.minOfOrNull { it.n }
      ?: entries.filter { it.category == DenominatorCategory.TABLE_INCLUDED }.minOfOrNull { it.n }

    if (randN != null && discrepancies.none { it.discrepancyType == "SUBGROUP_EXCEEDS_PARENT" }) {
      val badSub = entries.firstOrNull { it.category == DenominatorCategory.SUBGROUP && it.n > randN }
      if (badSub != null) {
        discrepancies.add(
          DenominatorDiscrepancy(
            discrepancyType = "SUBGROUP_EXCEEDS_PARENT",
            severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
            reportingStatus = ReportingStatus.REPORTED_BUT_INADEQUATE,
            description = "Subgroup count (n = ${badSub.n}) exceeds enrolled/randomized parent sample (N = $randN).",
            pages = listOf(badSub.page).filter { it > 0 },
            sourceQuotes = listOf(badSub.sourceQuote),
            overturnEvidence = "Corrected subgroup table confirming non-overlapping strata or clarifying an external pooled cohort."
          )
        )
      }
    }

    if (randN != null && discrepancies.none { it.discrepancyType == "IMPOSSIBLE_ATTRITION" }) {
      val badFollow = entries.firstOrNull {
        it.category in setOf(DenominatorCategory.FOLLOWED_UP, DenominatorCategory.ANALYZED, DenominatorCategory.COMPLETE_DATA) &&
          it.n > randN
      }
      if (badFollow != null) {
        discrepancies.add(
          DenominatorDiscrepancy(
            discrepancyType = "IMPOSSIBLE_ATTRITION",
            severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
            reportingStatus = ReportingStatus.REPORTED_BUT_INADEQUATE,
            description = "${badFollow.category.label} count (n = ${badFollow.n}) exceeds enrolled/randomized baseline (N = $randN).",
            pages = listOf(badFollow.page).filter { it > 0 },
            sourceQuotes = listOf(badFollow.sourceQuote),
            overturnEvidence = "Reconciled participant disposition table accounting for every enrolled, lost, and analyzed unit."
          )
        )
      }
    }

    // 4. Protocol/ITT population inconsistency: declares Intention-to-Treat with randomized N, yet drops >=10% or excludes non-adherent per-protocol
    val claimsItt = l.contains("intention-to-treat") || l.contains("intent-to-treat")
    val excludesPerProtocol = l.contains("per-protocol") && (l.contains("excluded") || l.contains("non-adherent"))
    if (claimsItt && randN != null && (
        (analyzedN != null && analyzedN < (randN * 0.90).toInt()) || excludesPerProtocol
      ) && !l.contains("modified intention-to-treat") && !l.contains("multiple imputation") && !l.contains("sensitivity analysis")
    ) {
      val effAnalyzed = analyzedN ?: randN
      val dropped = (randN - effAnalyzed).coerceAtLeast(0)
      discrepancies.add(
        DenominatorDiscrepancy(
          discrepancyType = "PROTOCOL_POPULATION_MISMATCH",
          severityTier = FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE,
          reportingStatus = ReportingStatus.REPORTED_BUT_INADEQUATE,
          description = "Methods declares an Intention-to-Treat (ITT) analysis on N = $randN, yet analytical population shifts to n = $effAnalyzed ($dropped dropped; per-protocol exclusion=${excludesPerProtocol}) without imputation or modified-ITT justification.",
          pages = entries.map { it.page }.filter { it > 0 }.distinct(),
          sourceQuotes = entries.filter { it.category in setOf(DenominatorCategory.RANDOMIZED, DenominatorCategory.ENROLLED, DenominatorCategory.ANALYZED, DenominatorCategory.TABLE_INCLUDED) }.take(2).map { it.sourceQuote },
          overturnEvidence = "Prespecified protocol / SAP defining modified-ITT rules and full-ITT sensitivity analysis."
        )
      )
    }

    val status = when {
      entries.isEmpty() -> ReportingStatus.NOT_REPORTED
      discrepancies.isNotEmpty() -> ReportingStatus.REPORTED_BUT_INADEQUATE
      else -> ReportingStatus.REPORTED_AND_ADEQUATE
    }
    return DenominatorLedger(entries.distinctBy { "${it.category}|${it.n}|${it.tableFigure}" }, discrepancies, status)
  }

  // ============================================================================
  // #4. CLAIM HIERARCHY (Descriptive -> Policy/Clinical Recommendation)
  // ============================================================================
  private fun buildClaimHierarchyAudit(
    text: String,
    l: String,
    subDesign: AuditSubDesign
  ): ClaimHierarchyAudit {
    val isCrossSectional = l.contains("cross-sectional") || l.contains("cross sectional") || l.contains("prevalence survey")
    val hasCausalIdentification = l.contains("randomized") || l.contains("randomised") ||
      l.contains("instrumental variable") || l.contains("2sls") || l.contains("regression discontinuity") ||
      l.contains("difference-in-differences") || l.contains("target trial emulation") || l.contains("mendelian randomization")

    val maxSupported = when {
      isCrossSectional -> ClaimLevel.ASSOCIATIONAL
      subDesign == AuditSubDesign.RCT -> ClaimLevel.POLICY_OR_CLINICAL_RECOMMENDATION
      subDesign == AuditSubDesign.META_ANALYSIS -> ClaimLevel.POLICY_OR_CLINICAL_RECOMMENDATION
      subDesign == AuditSubDesign.SYSTEMATIC_REVIEW -> ClaimLevel.ASSOCIATIONAL
      subDesign == AuditSubDesign.ECONOMETRIC_PANEL && hasCausalIdentification -> ClaimLevel.CAUSAL
      subDesign == AuditSubDesign.ML_BENCHMARK || subDesign == AuditSubDesign.DIAGNOSTIC_ACCURACY -> ClaimLevel.PREDICTIVE
      subDesign == AuditSubDesign.OBSERVATIONAL_COHORT || subDesign == AuditSubDesign.SURVIVAL_ANALYSIS ->
        if (hasCausalIdentification) ClaimLevel.CAUSAL else ClaimLevel.ASSOCIATIONAL
      subDesign == AuditSubDesign.FORMAL_THEOREM -> ClaimLevel.MECHANISTIC
      else -> ClaimLevel.ASSOCIATIONAL
    }

    val policyRegex = Regex("""(?:clinicians?\s+should|we\s+recommend\s+(?:routine|initiating|adopting|implementing)|policy\s*makers?\s+should|should\s+be\s+(?:adopted|prescribed|implemented)\s+as\s+standard|mandates?\s+immediate\s+(?:clinical|policy)\s+change)""", RegexOption.IGNORE_CASE)
    val causalRegex = Regex("""(?:\bproves?\s+that\b|\bcauses?\s+(?:a|an|the|higher|lower|increased|decreased)\b|\bdirectly\s+reduces?\b|\bprevents?\s+(?:mortality|death|stroke|disease)\b|\beffectively\s+treats?\b|\bcausal\s+effect\s+(?:of|on)\b)""", RegexOption.IGNORE_CASE)
    val mechanisticRegex = Regex("""(?:\bmediates?\s+the\s+pathway\b|\bmechanism\s+by\s+which\b|\bdrives?\s+the\s+biological\b)""", RegexOption.IGNORE_CASE)
    val predictiveRegex = Regex("""(?:\bpredicts?\b|\bdiscriminates?\b|\bauroc\b|\bforecast(?:s|ing)?\b)""", RegexOption.IGNORE_CASE)
    val assocRegex = Regex("""(?:\bassociated\s+with\b|\bcorrelated\s+with\b|\bcorrelation\b|\bodds\s+ratio\b|\brisk\s+ratio\b|\bhazard\s+ratio\b)""", RegexOption.IGNORE_CASE)

    val policyHits = policyRegex.findAll(text).map { it.value.trim() }.toList()
    val causalHits = causalRegex.findAll(text).map { it.value.trim() }.toList()
    val mechHits = mechanisticRegex.findAll(text).map { it.value.trim() }.toList()

    // Exclude negated causal caveats ("cannot prove causality", "does not establish a causal effect")
    val unhedgedCausalHits = causalHits.filter { hit ->
      val idx = text.indexOf(hit)
      val prefix = if (idx > 0) text.substring((idx - 35).coerceAtLeast(0), idx).lowercase(Locale.US) else ""
      !prefix.contains("cannot") && !prefix.contains("not ") && !prefix.contains("unable to") && !prefix.contains("does not") && !prefix.contains("no ")
    }

    val claimedLevel = when {
      policyHits.isNotEmpty() -> ClaimLevel.POLICY_OR_CLINICAL_RECOMMENDATION
      mechHits.isNotEmpty() -> ClaimLevel.MECHANISTIC
      unhedgedCausalHits.isNotEmpty() -> ClaimLevel.CAUSAL
      predictiveRegex.containsMatchIn(text) -> ClaimLevel.PREDICTIVE
      assocRegex.containsMatchIn(text) -> ClaimLevel.ASSOCIATIONAL
      else -> ClaimLevel.DESCRIPTIVE
    }

    val isOverclaimed = claimedLevel.level > maxSupported.level
    val quotes = (policyHits + unhedgedCausalHits + mechHits).take(3)
    val severity = when {
      !isOverclaimed -> FlawSeverityTier.WORTHWHILE_ENHANCEMENT
      isCrossSectional && claimedLevel.level >= ClaimLevel.CAUSAL.level -> FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION
      claimedLevel.level - maxSupported.level >= 2 -> FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE
      else -> FlawSeverityTier.REQUIRES_REPORTING_CORRECTION
    }

    val note = if (isOverclaimed) {
      "Manuscript advances ${claimedLevel.displayLabel} language (${quotes.joinToString("; ") { "\"$it\"" }}), exceeding the maximum claim level supported by ${if (isCrossSectional) "a cross-sectional design" else subDesign.displayName} (${maxSupported.displayLabel}) without explicit causal identification."
    } else {
      "Claim language (${claimedLevel.displayLabel}) is proportionate to design ceiling (${maxSupported.displayLabel})."
    }

    return ClaimHierarchyAudit(
      highestClaimedLevel = claimedLevel,
      maxDesignSupportedLevel = maxSupported,
      isOverclaimed = isOverclaimed,
      severityTier = severity,
      reportingStatus = if (isOverclaimed) ReportingStatus.REPORTED_BUT_INADEQUATE else ReportingStatus.REPORTED_AND_ADEQUATE,
      detectedClaimQuotes = quotes,
      justificationNote = note,
      overturnEvidence = "Explicit causal identification strategy (e.g., randomization, validated IV/DAG + negative controls) or softening headline claims to ${maxSupported.displayLabel}."
    )
  }

  // ============================================================================
  // #5. PRIMARY-ANALYSIS DECLARATION
  // ============================================================================
  private fun buildPrimaryAnalysisDeclaration(
    text: String,
    l: String,
    subDesign: AuditSubDesign,
    statAudit: StatisticalAuditReport
  ): PrimaryAnalysisDeclaration {
    val primaryOutcomeMatch = Regex("""primary\s+(?:outcome|endpoint)\s*(?:was|is|:)?\s*[^.,;\n]{4,70}""", RegexOption.IGNORE_CASE).find(text)
    val primaryTimeMatch = Regex("""(?:at\s+\d+\s*(?:days?|weeks?|months?|years?)|primary\s+time\s*point[^.,;\n]{0,40})""", RegexOption.IGNORE_CASE).find(text)
    val primaryCompMatch = Regex("""(?:primary\s+comparison[^.,;\n]{0,55}|compared\s+[^.,;\n]{3,45}\s+(?:vs\.?|versus|with)\s+[^.,;\n]{3,45})""", RegexOption.IGNORE_CASE).find(text)
    val primaryModelMatch = Regex("""primary\s+(?:analysis|model)\s*(?:used|was|fitted|:)?\s*[^.,;\n]{4,65}""", RegexOption.IGNORE_CASE).find(text)
    val prespecSubgroup = l.contains("prespecified subgroup") || l.contains("pre-specified subgroup")
    val hasExploratorySplit = (l.contains("exploratory") || l.contains("post-hoc") || l.contains("post hoc")) &&
      (l.contains("confirmatory") || l.contains("prespecified") || l.contains("pre-specified") || l.contains("primary outcome"))

    val activePClaims = statAudit.claims.filter { !it.isFromReferenceOrCitation && (it.pValue != null || it.pointEstimate != null) }
    val distinctOutcomes = max(
      activePClaims.map { it.outcome.ifBlank { it.testingFamily } }.distinct().size,
      Regex("""\b(?:mortality|readmission|hospitalization|stroke|myocardial|infection|length\s+of\s+stay|quality\s+of\s+life|pain\s+score|biomarker|adverse\s+event|bleeding|recurrence|progression)\b""", RegexOption.IGNORE_CASE)
        .findAll(text).map { it.value.lowercase(Locale.US) }.distinct().count()
    )

    val noPrimaryDeclared = primaryOutcomeMatch == null && subDesign != AuditSubDesign.FORMAL_THEOREM
    val selectiveEmphasis = noPrimaryDeclared && (distinctOutcomes >= 3 || activePClaims.size >= 5)

    val status = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM -> ReportingStatus.REPORTED_AND_ADEQUATE
      primaryOutcomeMatch != null && primaryModelMatch != null -> ReportingStatus.REPORTED_AND_ADEQUATE
      selectiveEmphasis -> ReportingStatus.REPORTED_BUT_INADEQUATE
      primaryOutcomeMatch == null -> ReportingStatus.NOT_REPORTED
      else -> ReportingStatus.REPORTED_AND_ADEQUATE
    }

    val detail = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM -> "Main theorem and proof objective identified."
      selectiveEmphasis -> "Manuscript reports $distinctOutcomes distinct outcomes / ${activePClaims.size} quantitative estimates without declaring a single primary outcome or confirmatory hierarchy — elevated selective emphasis and multiplicity risk."
      primaryOutcomeMatch != null -> "Primary outcome explicitly declared (${primaryOutcomeMatch.value.trim()})."
      else -> "Primary outcome/timepoint not explicitly designated in extracted Methods; verify protocol/SAP."
    }

    return PrimaryAnalysisDeclaration(
      primaryOutcome = IdentityField(primaryOutcomeMatch?.value?.trim() ?: "Not declared", if (primaryOutcomeMatch != null) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED),
      primaryTimePoint = IdentityField(primaryTimeMatch?.value?.trim() ?: "Not declared", if (primaryTimeMatch != null) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED),
      primaryComparison = IdentityField(primaryCompMatch?.value?.trim() ?: "Not declared", if (primaryCompMatch != null) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED),
      primaryModel = IdentityField(primaryModelMatch?.value?.trim() ?: "Not declared", if (primaryModelMatch != null) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED),
      prespecifiedSubgroups = IdentityField(if (prespecSubgroup) "Prespecified subgroups noted" else "Not reported", if (prespecSubgroup) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED),
      confirmatoryVsExploratory = IdentityField(if (hasExploratorySplit) "Confirmatory vs exploratory separated" else "Not explicitly separated", if (hasExploratorySplit) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED),
      distinctOutcomesMentioned = distinctOutcomes,
      selectiveEmphasisRisk = selectiveEmphasis,
      reportingStatus = status,
      detail = detail
    )
  }

  // ============================================================================
  // #7. CONTRADICTION MAP (Abstract / Intro / Methods / Results / Tables / Figures / Conclusion)
  // ============================================================================
  private fun buildContradictionMap(
    text: String,
    l: String,
    statAudit: StatisticalAuditReport,
    ledger: DenominatorLedger,
    claimAudit: ClaimHierarchyAudit
  ): ContradictionMap {
    val edges = mutableListOf<ContradictionEdge>()
    var idx = 1
    fun nextId() = "CM-%02d".format(Locale.US, idx++)

    val abstractBlock = extractSectionBlock(text, "abstract")
    val methodsBlock = extractSectionBlock(text, "methods")
    val resultsBlock = extractSectionBlock(text, "results")
    val conclusionBlock = extractSectionBlock(text, "conclusion").ifBlank { extractSectionBlock(text, "discussion") }

    val linkedSections = buildList {
      if (abstractBlock.isNotBlank()) add("Abstract")
      if (methodsBlock.isNotBlank()) add("Methods")
      if (resultsBlock.isNotBlank()) add("Results")
      if (text.contains(Regex("""\bTable\s*\d+""", RegexOption.IGNORE_CASE))) add("Tables")
      if (text.contains(Regex("""\b(?:Figure|Fig\.?)\s*\d+""", RegexOption.IGNORE_CASE))) add("Figures")
      if (conclusionBlock.isNotBlank()) add("Conclusion/Discussion")
    }

    // 1. Outcome called "primary" in Abstract but "secondary" in Methods (outcome switching!)
    val absPrimary = Regex("""primary\s+(?:outcome|endpoint)\s*(?:was|is|of|:)?\s*([A-Za-z0-9\s-]{4,35})""", RegexOption.IGNORE_CASE).find(abstractBlock)?.groupValues?.getOrNull(1)?.trim()
    val methSecondary = Regex("""secondary\s+(?:outcome|endpoint)s?\s*(?:was|were|included|:)?\s*([^.\n]{4,90})""", RegexOption.IGNORE_CASE).find(methodsBlock)?.groupValues?.getOrNull(1)?.trim()
    if (!absPrimary.isNullOrBlank() && !methSecondary.isNullOrBlank()) {
      val coreTerm = absPrimary.split(Regex("""\s+""")).firstOrNull { it.length >= 5 }.orEmpty()
      if (coreTerm.isNotBlank() && methSecondary.contains(coreTerm, ignoreCase = true)) {
        edges.add(
          ContradictionEdge(
            edgeId = nextId(),
            fromSection = "Abstract",
            toSection = "Methods",
            contradictionType = "PRIMARY_VS_SECONDARY_OUTCOME",
            severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
            description = "Outcome '$absPrimary' is framed as the primary outcome in the Abstract but listed as a secondary outcome in Methods ('$methSecondary').",
            pages = emptyList(),
            quotes = listOf("Abstract: primary outcome $absPrimary", "Methods: secondary outcome $methSecondary"),
            overturnEvidence = "Timestamped pre-registration (ClinicalTrials.gov / PROSPERO / OSF) confirming primary outcome definition."
          )
        )
      }
    }

    // 2. Sample size differing between Methods and Results
    val sectionNRegex = Regex("""\bN\s*=\s*(\d{1,3}(?:,\d{3})*|\d{2,6})\b""")
    val methodsNs = (
      ledger.entries.filter { it.section.equals("Methods", true) && it.category != DenominatorCategory.SUBGROUP }.map { it.n } +
        sectionNRegex.findAll(methodsBlock).mapNotNull { it.groupValues[1].replace(",", "").toIntOrNull() }
      ).distinct()
    val resultsNs = (
      ledger.entries.filter { it.section in setOf("Results", "Tables") && it.category != DenominatorCategory.SUBGROUP }.map { it.n } +
        sectionNRegex.findAll(resultsBlock).mapNotNull { m ->
          val ctx = resultsBlock.substring((m.range.first - 35).coerceAtLeast(0), m.range.last)
          if (ctx.contains("subgroup", ignoreCase = true)) null else m.groupValues[1].replace(",", "").toIntOrNull()
        }
      ).distinct()
    if (methodsNs.isNotEmpty() && resultsNs.isNotEmpty()) {
      val mMax = methodsNs.max()
      val rMin = resultsNs.min()
      if (mMax != rMin && abs(mMax - rMin).toDouble() / max(mMax, 1) >= 0.05 && !l.contains("lost to follow") && !l.contains("excluded")) {
        edges.add(
          ContradictionEdge(
            edgeId = nextId(),
            fromSection = "Methods",
            toSection = "Results",
            contradictionType = "SAMPLE_SIZE_MISMATCH",
            severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
            description = "Sample size shifts between Methods (N = $mMax) and Results/Tables (N = $rMin) without exclusion accounting.",
            pages = ledger.entries.map { it.page }.filter { it > 0 }.distinct(),
            quotes = listOf("Methods N=$mMax", "Results N=$rMin"),
            overturnEvidence = "Participant flow chart reconciling the ${abs(mMax - rMin)} missing/excluded units."
          )
        )
      }
    }

    // 3. Cross-section contradictions from StatisticalClaimParser (Abstract vs Results CI/estimate conflict)
    statAudit.crossLinks.filter { it.linkType == "CROSS_SECTION_CONTRADICTION" }.forEach { link ->
      edges.add(
        ContradictionEdge(
          edgeId = nextId(),
          fromSection = link.sectionsInvolved.firstOrNull() ?: "Abstract",
          toSection = link.sectionsInvolved.lastOrNull() ?: "Results",
          contradictionType = "TEXT_VS_FIGURE_CI",
          severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
          description = link.description,
          pages = link.pageNumbers,
          quotes = link.sourceQuotes,
          overturnEvidence = "Primary statistical output table reconciling narrative text and figure/table bounds."
        )
      )
    }

    // 4. Conclusion stronger than reported analysis (spin of non-significant primary result OR claim overreach)
    val primaryNonSig = resultsBlock.contains(Regex("""primary[^.\n]{0,80}(?:not\s+(?:statistically\s+)?significant|non[- ]significant|p\s*=\s*0\.[1-9]\d*|p\s*>\s*0\.05)""", RegexOption.IGNORE_CASE))
    val spunPositive = (abstractBlock + "\n" + conclusionBlock).contains(
      Regex("""(?:demonstrates?\s+(?:clear\s+)?(?:efficacy|benefit)|proves?\s+(?:clinical\s+)?(?:efficacy|benefit|significant)|proved\s+(?:clinical\s+)?(?:efficacy|benefit)|clearly\s+effective|strong\s+benefit|significantly\s+improves?\s+survival|should\s+(?:be\s+adopted|adopt))""", RegexOption.IGNORE_CASE)
    )
    if (primaryNonSig && spunPositive) {
      edges.add(
        ContradictionEdge(
          edgeId = nextId(),
          fromSection = "Results",
          toSection = "Conclusion/Abstract",
          contradictionType = "CONCLUSION_STRONGER_THAN_ANALYSIS",
          severityTier = FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE,
          description = "Primary outcome in Results did not reach statistical significance, yet Abstract/Conclusion advances strong positive efficacy or adoption language (spin).",
          pages = emptyList(),
          quotes = listOf("Non-significant primary result paired with strong positive conclusion language."),
          overturnEvidence = "Revision of Abstract/Conclusion to accurately foreground the null primary endpoint."
        )
      )
    } else if (claimAudit.isOverclaimed) {
      edges.add(
        ContradictionEdge(
          edgeId = nextId(),
          fromSection = "Methods",
          toSection = "Conclusion/Abstract",
          contradictionType = "CONCLUSION_STRONGER_THAN_ANALYSIS",
          severityTier = claimAudit.severityTier,
          description = claimAudit.justificationNote,
          pages = emptyList(),
          quotes = claimAudit.detectedClaimQuotes,
          overturnEvidence = claimAudit.overturnEvidence
        )
      )
    }

    // 5. Subgroup result presented as overall result in Abstract/Conclusion
    val hasOverallNull = primaryNonSig || resultsBlock.contains(Regex("""(?:overall|full\s+cohort|entire\s+sample)[^.\n]{0,70}(?:no\s+(?:significant\s+)?difference|not\s+significant|non[- ]significant|p\s*=\s*0\.[1-9]\d*|p\s*>\s*0\.05)""", RegexOption.IGNORE_CASE))
    val hasSubgroupSig = resultsBlock.contains(Regex("""subgroup[^.\n]{0,90}(?:significant|p\s*[=<]\s*0\.0[0-4])""", RegexOption.IGNORE_CASE))
    val absMentionsSubgroup = abstractBlock.contains("subgroup", ignoreCase = true)
    if (hasOverallNull && hasSubgroupSig && !absMentionsSubgroup && spunPositive) {
      edges.add(
        ContradictionEdge(
          edgeId = nextId(),
          fromSection = "Results (Subgroup)",
          toSection = "Abstract",
          contradictionType = "SUBGROUP_AS_OVERALL",
          severityTier = FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE,
          description = "Overall primary analysis was non-significant while a subgroup reached nominal significance, yet the Abstract highlights benefit without disclosing it is restricted to a subgroup.",
          pages = emptyList(),
          quotes = listOf("Overall null result vs subgroup-only significance omitted from Abstract headline."),
          overturnEvidence = "Formal subgroup interaction test (p_interaction) and transparent Abstract disclosure of the overall null result."
        )
      )
    }

    return ContradictionMap(linkedSections, edges)
  }

  // ============================================================================
  // #9. CLINICAL / PRACTICAL SIGNIFICANCE GATE
  // ============================================================================
  private fun buildPracticalSignificanceGate(
    text: String,
    l: String,
    statAudit: StatisticalAuditReport
  ): PracticalSignificanceAssessment {
    val active = statAudit.claims.filter { !it.isFromReferenceOrCitation }
    val hasSig = active.any { it.pValue != null && it.pValue < it.alphaThreshold }
    val hasNonSig = active.any { it.pValue != null && it.pValue >= it.alphaThreshold }
    val maxN = statAudit.sampleFlowRecords.maxOfOrNull { it.n } ?: 0

    // Check for tiny-but-statistically-significant effect (huge N >= 10,000 with tiny ratio near 1.0 or tiny ARR < 0.5% or tiny d < 0.1)
    val tinyEffectClaim = active.firstOrNull { c ->
      val p = c.pValue ?: return@firstOrNull false
      val est = c.pointEstimate ?: return@firstOrNull false
      val measure = c.effectMeasure.uppercase(Locale.US)
      val isTinyRatio = measure in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR") && abs(est - 1.0) <= 0.04
      val isTinySmd = measure in setOf("SMD", "D", "G", "R") && abs(est) <= 0.08
      p < 0.05 && (isTinyRatio || isTinySmd)
    }
    val tinyButSig = (tinyEffectClaim != null && (maxN >= 5_000 || l.contains("large sample") || l.contains("national database"))) ||
      (maxN >= 50_000 && active.any { it.pValue != null && it.pValue < 0.01 && it.pointEstimate != null && abs(it.pointEstimate - 1.0) <= 0.03 })

    // Check for wide non-significant CI spun as "no effect" / "equivalent" / "rules out"
    val wideNonSigClaim = active.firstOrNull { c ->
      val lo = c.ciLower ?: return@firstOrNull false
      val hi = c.ciUpper ?: return@firstOrNull false
      val measure = c.effectMeasure.uppercase(Locale.US)
      val isRatio = measure in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR")
      isRatio && lo < 0.70 && hi > 1.40
    }
    val claimsNoEffectOrEquivalence = Regex(
      """(?:prov(?:e|es|ed|ing)\s+no\s+effect|confirm(?:s|ed|ing)?\s+no\s+difference|demonstrat(?:e|es|ed|ing)\s+equivalence|rul(?:e|es|ed|ing)\s+out\s+(?:any\s+)?(?:benefit|harm|effect)|no\s+effect\s+exists|completely\s+safe)""",
      RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
    val wideNonSigSpun = wideNonSigClaim != null && claimsNoEffectOrEquivalence

    val hasMid = l.contains("minimal important difference") || l.contains("minimally important") || l.contains("mcid") || l.contains("clinical significance threshold")
    val hasAbsRisk = l.contains("absolute risk") || l.contains("arr") || l.contains("nnt") || l.contains("baseline risk") || l.contains("risk difference") || statAudit.twoByTwoTables.isNotEmpty()

    val utilitySummary = when {
      tinyButSig -> "FLAG (Tiny-but-Significant): Effect (${tinyEffectClaim?.sourceQuote ?: "near-null estimate"}) is statistically significant under very large N (N=$maxN) but clinically/practically trivial; evaluate against Minimal Important Difference (MID) and absolute risk."
      wideNonSigSpun -> "FLAG (Absence of Evidence ≠ Evidence of Absence): Wide non-significant CI (${wideNonSigClaim?.sourceQuote}) is compatible with both substantial benefit and harm; claiming 'no effect' or equivalence is unjustified."
      !hasAbsRisk && active.any { it.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR") } ->
        "Relative ratio metrics reported without baseline/absolute risk (ARR/NNT) or MID; clinical/policy utility remains uncalibrated."
      else -> "Statistical significance, effect magnitude, and precision evaluated; verify against domain-specific Minimal Important Difference."
    }

    return PracticalSignificanceAssessment(
      statisticalSignificanceStatus = when {
        hasSig && hasNonSig -> "Mixed significant and non-significant findings"
        hasSig -> "Statistically significant primary/reported claim(s)"
        hasNonSig -> "Non-significant reported claim(s)"
        else -> "Not assessable from supplied material"
      },
      effectMagnitudeNote = tinyEffectClaim?.let { "Near-null magnitude detected (${it.sourceQuote})" } ?: "Verify practical magnitude against domain benchmarks",
      precisionNote = wideNonSigClaim?.let { "Wide CI (${it.sourceQuote}) spans clinically important benefit and harm" } ?: "Check CI width against MID bounds",
      minimalImportantDifferenceStatus = if (hasMid) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
      absoluteAndBaselineRiskStatus = if (hasAbsRisk) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
      tinyButSignificantFlag = tinyButSig,
      wideNonSignificantSpunAsNoEffectFlag = wideNonSigSpun,
      clinicalOrPolicyUtilitySummary = utilitySummary
    )
  }

  // ============================================================================
  // #8. "POSSIBLE REASONS FOR THE RESULT" (11-Generator Audit)
  // ============================================================================
  private fun buildAlternativeExplanations(
    text: String,
    l: String,
    subDesign: AuditSubDesign,
    statAudit: StatisticalAuditReport,
    ledger: DenominatorLedger
  ): List<AlternativeExplanationItem> {
    val isObs = subDesign in setOf(AuditSubDesign.OBSERVATIONAL_COHORT, AuditSubDesign.SURVIVAL_ANALYSIS, AuditSubDesign.ECONOMETRIC_PANEL, AuditSubDesign.GENERAL_EMPIRICAL)
    val hasLeakage = statAudit.domainFindings.any { it.domainModule == "ML/Leakage" && it.status == QuantFindingStatus.FAIL }
    val hasMultiplicity = statAudit.reconstructions.any { it.calculatorName.startsWith("Multiple-Testing") }
    val hasMissingDiscrepancy = ledger.discrepancies.any { it.discrepancyType in setOf("IMPOSSIBLE_ATTRITION", "TABLE_DENOMINATOR_DRIFT", "PROTOCOL_POPULATION_MISMATCH") }

    return listOf(
      AlternativeExplanationItem(
        mechanism = "Confounding",
        plausibility = when {
          subDesign == AuditSubDesign.RCT -> "LOW"
          isObs && !l.contains("e-value") && !l.contains("oster") && !l.contains("instrumental") -> "HIGH"
          isObs -> "MODERATE"
          else -> "LOW"
        },
        reportingStatus = if (l.contains("confound") || subDesign == AuditSubDesign.RCT) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = if (isObs) "Non-randomized exposure allocation leaves residual unmeasured confounding as a primary alternative explanation." else "Randomization or non-observational setting limits confounding."
      ),
      AlternativeExplanationItem(
        mechanism = "Selection Bias",
        plausibility = if (l.contains("per-protocol") || l.contains("case-control") || l.contains("convenience") || hasMissingDiscrepancy) "HIGH" else "MODERATE",
        reportingStatus = if (l.contains("selection bias") || l.contains("consort") || l.contains("strobe")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = "Verify recruitment, attrition, and differential selection into the analytical sample."
      ),
      AlternativeExplanationItem(
        mechanism = "Measurement Error",
        plausibility = if (l.contains("self-report") || l.contains("icd-") || l.contains("retrospective")) "MODERATE" else "LOW",
        reportingStatus = if (l.contains("blinding") || l.contains("validated") || l.contains("inter-rater")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = "Differential or non-differential exposure/outcome misclassification can attenuate or distort effects."
      ),
      AlternativeExplanationItem(
        mechanism = "Regression to the Mean",
        plausibility = if (l.contains("before-and-after") || l.contains("pre-post") || (l.contains("baseline") && !l.contains("control group"))) "HIGH" else "LOW",
        reportingStatus = if (l.contains("regression to the mean") || l.contains("ancova")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = "Extreme baseline selection without a concurrent control group naturally regresses toward average values."
      ),
      AlternativeExplanationItem(
        mechanism = "Chance / Sampling Variability",
        plausibility = if (statAudit.claims.any { !it.isFromReferenceOrCitation && (it.pValue ?: 1.0) in 0.02..0.05 }) "MODERATE" else "LOW",
        reportingStatus = ReportingStatus.REPORTED_AND_ADEQUATE,
        rationale = "Borderline significance or wide confidence intervals remain compatible with sampling fluctuation."
      ),
      AlternativeExplanationItem(
        mechanism = "Multiplicity",
        plausibility = if (hasMultiplicity) "HIGH" else "LOW",
        reportingStatus = if (l.contains("bonferroni") || l.contains("holm") || l.contains("fdr") || l.contains("benjamini")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = if (hasMultiplicity) "Multiple unadjusted tests in a testing family inflate false-positive probability." else "No acute unadjusted multiplicity spike detected."
      ),
      AlternativeExplanationItem(
        mechanism = "Missingness",
        plausibility = if (hasMissingDiscrepancy || l.contains("complete-case")) "HIGH" else "LOW",
        reportingStatus = if (l.contains("multiple imputation") || l.contains("missing")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = "Informative missingness (MNAR/MAR under complete-case) can induce substantial bias."
      ),
      AlternativeExplanationItem(
        mechanism = "Model Specification",
        plausibility = if (statAudit.verifiedModelMismatchCount > 0) "HIGH" else "MODERATE",
        reportingStatus = if (statAudit.verifiedModelMismatchCount > 0) ReportingStatus.REPORTED_BUT_INADEQUATE else ReportingStatus.REPORTED_AND_ADEQUATE,
        rationale = if (statAudit.verifiedModelMismatchCount > 0) "Confirmed model/estimator mismatch detected by domain audit." else "Verify functional form, interaction, and distributional assumptions."
      ),
      AlternativeExplanationItem(
        mechanism = "Data Leakage",
        plausibility = if (hasLeakage) "HIGH" else if (subDesign == AuditSubDesign.ML_BENCHMARK) "MODERATE" else "LOW",
        reportingStatus = if (hasLeakage) ReportingStatus.REPORTED_BUT_INADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = if (hasLeakage) "Confirmed train/test or patient-level leakage artificially inflates performance." else "Applicable primarily to predictive/ML pipelines."
      ),
      AlternativeExplanationItem(
        mechanism = "Publication Bias",
        plausibility = if (subDesign == AuditSubDesign.META_ANALYSIS && !l.contains("funnel") && !l.contains("egger")) "MODERATE" else "LOW",
        reportingStatus = if (l.contains("funnel") || l.contains("egger") || l.contains("publication bias")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = "Small-study effects and selective journal publication can inflate pooled literature estimates."
      ),
      AlternativeExplanationItem(
        mechanism = "Data-Dependent Analysis Choices",
        plausibility = if (!l.contains("preregist") && !l.contains("pre-regist") && !l.contains("protocol") && !l.contains("prospero") && !l.contains("nct")) "MODERATE" else "LOW",
        reportingStatus = if (l.contains("preregist") || l.contains("pre-regist") || l.contains("protocol") || l.contains("nct")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        rationale = "Absence of a timestamped protocol/SAP leaves covariate, subgroup, and cutoff choices open to researcher degrees of freedom."
      )
    )
  }

  // ============================================================================
  // #6. "WHAT WOULD CHANGE THE CONCLUSION?"
  // ============================================================================
  private fun buildConclusionFragility(
    subDesign: AuditSubDesign,
    statAudit: StatisticalAuditReport,
    ledger: DenominatorLedger,
    claimAudit: ClaimHierarchyAudit,
    practicalGate: PracticalSignificanceAssessment
  ): List<ConclusionFragilityItem> {
    val items = mutableListOf<ConclusionFragilityItem>()

    statAudit.domainFindings.filter { it.status == QuantFindingStatus.FAIL }.take(2).forEach { df ->
      items.add(
        ConclusionFragilityItem(
          targetFinding = df.criterion,
          keyAssumption = "Current estimator (${df.domainModule}) is unbiased.",
          plausibleViolation = df.detail.take(90),
          likelyBiasDirection = "Anti-conservative (exaggerates effect or understates SE)",
          minimumSensitivityAnalysis = df.recommendedAction.take(90),
          wouldHeadlineSurvive = "Uncertain — may reverse or lose significance"
        )
      )
    }

    if (ledger.discrepancies.isNotEmpty()) {
      val d = ledger.discrepancies.first()
      items.add(
        ConclusionFragilityItem(
          targetFinding = "Analytical population (${d.discrepancyType})",
          keyAssumption = "Excluded/unaccounted units are missing completely at random.",
          plausibleViolation = "Differential attrition correlated with treatment/outcome.",
          likelyBiasDirection = "Unpredictable / selection bias favoring treated arm",
          minimumSensitivityAnalysis = "Tipping-point & best/worst-case attrition bounds across full enrolled N",
          wouldHeadlineSurvive = "Fragile until denominators reconcile"
        )
      )
    }

    if (claimAudit.isOverclaimed) {
      items.add(
        ConclusionFragilityItem(
          targetFinding = "Causal / policy headline claim",
          keyAssumption = "No unmeasured confounding or reverse causation.",
          plausibleViolation = "Unmeasured baseline severity or healthy-user confounding.",
          likelyBiasDirection = "Away from null (spurious benefit/harm)",
          minimumSensitivityAnalysis = "Compute E-value / Oster δ and negative-control outcome test",
          wouldHeadlineSurvive = "Causal claim fails without identification; association may persist"
        )
      )
    }

    if (items.isEmpty()) {
      items.add(
        ConclusionFragilityItem(
          targetFinding = "Primary headline estimate (${subDesign.displayName})",
          keyAssumption = when (subDesign) {
            AuditSubDesign.OBSERVATIONAL_COHORT, AuditSubDesign.SURVIVAL_ANALYSIS -> "No unmeasured confounding, immortal-time bias, or informative censoring"
            AuditSubDesign.META_ANALYSIS -> "Exchangeable studies and unbiased τ² / small-study symmetry"
            AuditSubDesign.ML_BENCHMARK -> "IID generalization across external sites and populations"
            AuditSubDesign.ECONOMETRIC_PANEL -> "Parallel trends / exclusion restriction and correct clustering"
            AuditSubDesign.FORMAL_THEOREM -> "Stated regularity, compactness, and measurability hypotheses"
            else -> "Correct model specification and ignorable missingness"
          },
          plausibleViolation = when (subDesign) {
            AuditSubDesign.OBSERVATIONAL_COHORT, AuditSubDesign.SURVIVAL_ANALYSIS -> "Unmeasured baseline severity, healthy-user bias, or residual confounding"
            else -> "Unmodeled heterogeneity, domain shift, or informative missingness"
          },
          likelyBiasDirection = "Typically attenuates external effect or shifts estimate away from null",
          minimumSensitivityAnalysis = when (subDesign) {
            AuditSubDesign.OBSERVATIONAL_COHORT, AuditSubDesign.SURVIVAL_ANALYSIS -> "Compute E-value / Oster δ, negative-control outcome, and propensity overlap diagnostics"
            AuditSubDesign.META_ANALYSIS -> "REML/Paule-Mandel + Hartung-Knapp + leave-one-out + 95% PI"
            AuditSubDesign.ML_BENCHMARK -> "External site/temporal holdout + calibration curve + DeLong test"
            AuditSubDesign.ECONOMETRIC_PANEL -> "Callaway-Sant'Anna / HonestDiD pre-trend bounds + wild bootstrap"
            AuditSubDesign.FORMAL_THEOREM -> "Boundary-case counterexample check & regularity verification"
            else -> "Multiple imputation (MAR/MNAR delta) + alternative estimator"
          },
          wouldHeadlineSurvive = if (practicalGate.tinyButSignificantFlag) "Statistical p survives, practical utility does not" else "Likely robust if sensitivity bounds exclude null"
        )
      )
    }

    return items
  }

  // ============================================================================
  // #15. "STOP AND ASK" THRESHOLD FOR INCOMPLETE / AMBIGUOUS EVIDENCE
  // ============================================================================
  private fun buildStopAndAskAssessment(
    text: String,
    l: String,
    identity: StudyIdentityCard,
    ledger: DenominatorLedger,
    primaryDecl: PrimaryAnalysisDeclaration,
    statAudit: StatisticalAuditReport,
    coverage: DocumentCoverageReport?,
    extractionConfidence: Int
  ): StopAndAskAssessment {
    val triggers = mutableListOf<StopAndAskTrigger>()

    // 1. Missing pages from PDF extraction or prompt truncation
    if (coverage != null && coverage.omittedPages.isNotEmpty()) {
      triggers.add(
        StopAndAskTrigger(
          triggerCode = "MISSING_PAGES",
          reason = "${coverage.omittedPages.size} page(s) were omitted from text extraction (Pages ${coverage.omittedPages.take(6).joinToString(",")}).",
          userQuestion = "Please supply the omitted Results/Methods pages or upload a searchable PDF so missing analyses are not misjudged."
        )
      )
    }

    // 2. Image-only / scanned / unreadable tables
    val hasScannedOrImageOnlyTables = (coverage != null && coverage.warnings.any { it.contains("scanned", true) || it.contains("image", true) || it.contains("ocr", true) }) ||
      (l.contains("see table") && !text.contains("|") && !text.contains("\t") && statAudit.claims.none { !it.isFromReferenceOrCitation && it.pointEstimate != null })
    if (hasScannedOrImageOnlyTables) {
      triggers.add(
        StopAndAskTrigger(
          triggerCode = "IMAGE_ONLY_TABLES",
          reason = "Key tables appear to be image-only or unreadable in extracted text.",
          userQuestion = "Please provide readable/OCR'd tables or paste the primary numeric table rows for exact verification."
        )
      )
    }

    // 3. Truncated / garbled equations
    val hasTruncatedEquations = text.count { it == '\uFFFD' } >= 3 ||
      Regex("""(?:equation\s+\d+\s+\[?(?:unreadable|omitted|truncated)\]?|\[MATH\s+TRUNCATED\])""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    if (hasTruncatedEquations) {
      triggers.add(
        StopAndAskTrigger(
          triggerCode = "TRUNCATED_EQUATIONS",
          reason = "Mathematical notation or equations contain replacement characters () or truncation markers.",
          userQuestion = "Please provide LaTeX or clean text for the truncated equations before auditing formal derivations."
        )
      )
    }

    // 4. Key supplementary material absent when text defers quantitative evidence to Supplement
    val defersToSupplement = Regex("""(?:(?:shown|reported|presented|available|provided|detailed|described)\s+in|see|refer(?:ring|encing)?(?:\s+to)?)\s+(?:the\s+)?(?:supplementary|online\s+appendix|eTable|Table\s+S\d)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    if (defersToSupplement && (statAudit.unsupportedDisclosures.isNotEmpty() || statAudit.claims.isEmpty()) && statAudit.reconstructions.isEmpty()) {
      triggers.add(
        StopAndAskTrigger(
          triggerCode = "ABSENT_SUPPLEMENT",
          reason = "Primary quantitative inputs (raw counts, SEs, or sensitivity tables) are deferred to external Supplementary Materials not supplied.",
          userQuestion = "Please upload the Supplementary Appendix / eTables to enable independent numerical reconstruction."
        )
      )
    }

    // 5. Ambiguous study design
    if (identity.studyDesign.status == ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL) {
      triggers.add(
        StopAndAskTrigger(
          triggerCode = "AMBIGUOUS_STUDY_DESIGN",
          reason = "Study design cannot be reliably determined from the supplied text.",
          userQuestion = "Clarify the exact study design (e.g., RCT, cohort, cross-sectional, meta-analysis, ML benchmark, or formal proof)."
        )
      )
    }

    // 6. Primary outcome cannot be identified AND no structured quantitative claims extracted
    if (primaryDecl.primaryOutcome.status == ReportingStatus.NOT_REPORTED &&
      identity.outcome.status == ReportingStatus.NOT_REPORTED &&
      statAudit.subDesign != AuditSubDesign.FORMAL_THEOREM
    ) {
      triggers.add(
        StopAndAskTrigger(
          triggerCode = "UNIDENTIFIABLE_PRIMARY_OUTCOME",
          reason = "No primary outcome or quantitative endpoint could be identified in the supplied text.",
          userQuestion = "Which specific outcome and time point constitute the study's primary endpoint?"
        )
      )
    }

    // 7. Denominator cannot be reconstructed in an empirical quantitative study with low extraction confidence or missing sample counts
    if (!ledger.isReconstructable &&
      statAudit.subDesign != AuditSubDesign.FORMAL_THEOREM &&
      statAudit.twoByTwoTables.isEmpty() &&
      statAudit.metaStudyEffects.isEmpty() &&
      (extractionConfidence <= 2 || text.length < 350 || triggers.isNotEmpty())
    ) {
      triggers.add(
        StopAndAskTrigger(
          triggerCode = "UNRECONSTRUCTABLE_DENOMINATOR",
          reason = "Analytical sample size and denominators could not be reconstructed from the supplied text.",
          userQuestion = "Provide the enrolled, excluded, and final analyzed sample sizes (CONSORT/STROBE flow or table N)."
        )
      )
    }

    // Trigger "Stop and Ask" when severe extraction gaps exist OR when >=3 structural identity gaps exist
    // without verified deterministic evidence (never suppress a verified arithmetic/model error on clean text).
    val hasVerifiedDeterministicEvidence = statAudit.verifiedArithmeticErrorCount > 0 ||
      statAudit.verifiedModelMismatchCount > 0 ||
      statAudit.reconstructions.any { it.status == QuantFindingStatus.FAIL }
    val hasSevereExtractionGap = extractionConfidence <= 2 ||
      triggers.any { it.triggerCode in setOf("MISSING_PAGES", "IMAGE_ONLY_TABLES", "TRUNCATED_EQUATIONS") }
    val shouldStop = (hasSevereExtractionGap && (triggers.size >= 2 || extractionConfidence <= 1)) ||
      (!hasVerifiedDeterministicEvidence && triggers.size >= 3)

    val banner = if (shouldStop) {
      "INSUFFICIENT EVIDENCE FOR ASSESSMENT (${triggers.size} completeness trigger(s)): Strong methodological or statistical rulings are suspended to prevent filling missing evidence with assumptions."
    } else {
      "Completeness gate passed (${triggers.size} minor caveat(s)); sufficient supplied evidence for structured appraisal."
    }

    return StopAndAskAssessment(
      shouldStopAndAsk = shouldStop,
      verdictOverride = if (shouldStop) "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT" else null,
      triggers = triggers,
      disclosureBanner = banner
    )
  }

  // ============================================================================
  // #11. EXPLICIT 8-SOURCE UNCERTAINTY BUDGET
  // ============================================================================
  private fun buildUncertaintyBudget(
    l: String,
    subDesign: AuditSubDesign,
    statAudit: StatisticalAuditReport,
    ledger: DenominatorLedger,
    coverage: DocumentCoverageReport?,
    extractionConfidence: Int,
    stopAndAsk: StopAndAskAssessment
  ): UncertaintyBudget {
    val active = statAudit.claims.filter { !it.isFromReferenceOrCitation }
    val hasCIs = active.any { it.ciLower != null && it.ciUpper != null }
    val hasModelFail = statAudit.verifiedModelMismatchCount > 0
    val hasMissingFail = ledger.discrepancies.any { it.discrepancyType in setOf("IMPOSSIBLE_ATTRITION", "TABLE_DENOMINATOR_DRIFT", "PROTOCOL_POPULATION_MISMATCH") }
    val hasProtocol = l.contains("preregist") || l.contains("pre-regist") || l.contains("protocol") || l.contains("prospero") || l.contains("nct")

    val items = listOf(
      UncertaintyBudgetItem(
        UncertaintySource.SAMPLING,
        level = if (hasCIs) "MODERATE" else "UNASSESSABLE",
        reportingStatus = if (hasCIs) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        note = if (hasCIs) "Confidence intervals / SEs reported for sampling precision." else "Precision intervals not reported in extracted text."
      ),
      UncertaintyBudgetItem(
        UncertaintySource.MEASUREMENT,
        level = if (l.contains("validated") || l.contains("blinded")) "LOW" else "MODERATE",
        reportingStatus = if (l.contains("validated") || l.contains("reliability") || l.contains("blinded")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        note = "Instrument reliability, adjudication blinding, and misclassification bounds."
      ),
      UncertaintyBudgetItem(
        UncertaintySource.MODEL_ASSUMPTIONS,
        level = if (hasModelFail) "HIGH" else "MODERATE",
        reportingStatus = if (hasModelFail) ReportingStatus.REPORTED_BUT_INADEQUATE else ReportingStatus.REPORTED_AND_ADEQUATE,
        note = if (hasModelFail) "Confirmed design/model mismatch elevates structural model uncertainty." else "Standard parametric/identification assumptions apply."
      ),
      UncertaintyBudgetItem(
        UncertaintySource.MISSING_DATA,
        level = if (hasMissingFail || l.contains("complete-case")) "HIGH" else if (l.contains("multiple imputation")) "LOW" else "MODERATE",
        reportingStatus = if (hasMissingFail) ReportingStatus.REPORTED_BUT_INADEQUATE else if (l.contains("missing")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        note = if (hasMissingFail) "Unreconciled denominator/attrition shifts create high missingness uncertainty." else "Check missing-data mechanism (MCAR/MAR/MNAR)."
      ),
      UncertaintyBudgetItem(
        UncertaintySource.SELECTION,
        level = if (subDesign == AuditSubDesign.RCT && !l.contains("per-protocol")) "LOW" else "MODERATE",
        reportingStatus = if (l.contains("inclusion") || l.contains("eligibility") || l.contains("consort")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        note = "Cohort eligibility, non-response, and post-baseline selection."
      ),
      UncertaintyBudgetItem(
        UncertaintySource.EXTRACTION_QUALITY,
        level = when {
          stopAndAsk.shouldStopAndAsk || extractionConfidence <= 2 -> "HIGH"
          extractionConfidence == 3 || statAudit.unsupportedDisclosures.isNotEmpty() || (coverage?.omittedPages?.isNotEmpty() == true) -> "MODERATE"
          else -> "LOW"
        },
        reportingStatus = if (stopAndAsk.shouldStopAndAsk) ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL else ReportingStatus.REPORTED_AND_ADEQUATE,
        note = "Extraction confidence $extractionConfidence/5; ${statAudit.unsupportedDisclosures.size} unsupported-input disclosure(s)."
      ),
      UncertaintyBudgetItem(
        UncertaintySource.EXTERNAL_VALIDITY,
        level = if (l.contains("external validation") || l.contains("multicenter") || l.contains("multi-center")) "MODERATE" else "HIGH",
        reportingStatus = if (l.contains("generaliz") || l.contains("external validation")) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        note = "Transportability across settings, demographics, and time periods."
      ),
      UncertaintyBudgetItem(
        UncertaintySource.ANALYST_DEGREES_OF_FREEDOM,
        level = if (hasProtocol) "LOW" else "MODERATE",
        reportingStatus = if (hasProtocol) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
        note = if (hasProtocol) "Protocol / preregistration referenced." else "No timestamped SAP/protocol detected; verify prespecification."
      )
    )

    val sciHigh = items.count { !it.source.isExtractionSide && it.level == "HIGH" }
    val sciLevel = when {
      sciHigh >= 2 -> "HIGH"
      sciHigh == 1 -> "MODERATE-HIGH"
      else -> "MODERATE"
    }
    val extLevel = items.first { it.source == UncertaintySource.EXTRACTION_QUALITY }.level

    return UncertaintyBudget(
      items = items,
      scientificUncertaintyLevel = sciLevel,
      extractionUncertaintyLevel = extLevel,
      separationNote = "Extraction uncertainty ($extLevel) reflects document/table parseability and is strictly isolated from scientific validity ($sciLevel)."
    )
  }

  // ============================================================================
  // #12. MINIMUM EVIDENCE TO OVERTURN EACH MAJOR CRITIQUE
  // ============================================================================
  private fun buildOverturnConditions(
    ledger: DenominatorLedger,
    claimAudit: ClaimHierarchyAudit,
    contradictionMap: ContradictionMap,
    statAudit: StatisticalAuditReport
  ): List<OverturnCondition> {
    val out = mutableListOf<OverturnCondition>()

    ledger.discrepancies.forEach { d ->
      out.add(
        OverturnCondition(
          critiqueTitle = d.discrepancyType,
          severityTier = d.severityTier,
          minimumEvidenceToOverturn = d.overturnEvidence,
          evidenceCategory = "CORRECTED_DENOMINATOR"
        )
      )
    }

    statAudit.reconstructions.filter { it.status == QuantFindingStatus.FAIL }.take(4).forEach { r ->
      out.add(
        OverturnCondition(
          critiqueTitle = r.calculatorName,
          severityTier = if (r.findingType == StatisticalFindingType.ARITHMETIC_ERROR) FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION else FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE,
          minimumEvidenceToOverturn = "Raw primary table / statistical software output verifying exact counts, SEs, weighting, or covariate adjustment (${r.sourceEvidence.take(55)}).",
          evidenceCategory = "SUPPLEMENTARY_TABLE"
        )
      )
    }

    statAudit.domainFindings.filter { it.status == QuantFindingStatus.FAIL }.take(4).forEach { df ->
      val cat = when {
        df.domainModule.startsWith("ML") -> "EXTERNAL_VALIDATION"
        df.criterion.contains("Intention-to-Treat", true) || df.criterion.contains("Protocol", true) -> "PRESPECIFIED_PROTOCOL"
        else -> "ALTERNATIVE_MODEL"
      }
      out.add(
        OverturnCondition(
          critiqueTitle = df.criterion,
          severityTier = FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION,
          minimumEvidenceToOverturn = df.recommendedAction,
          evidenceCategory = cat
        )
      )
    }

    contradictionMap.edges.forEach { e ->
      if (out.none { it.critiqueTitle == e.contradictionType }) {
        out.add(
          OverturnCondition(
            critiqueTitle = "${e.contradictionType} (${e.fromSection} ↔ ${e.toSection})",
            severityTier = e.severityTier,
            minimumEvidenceToOverturn = e.overturnEvidence,
            evidenceCategory = "PRESPECIFIED_PROTOCOL"
          )
        )
      }
    }

    if (claimAudit.isOverclaimed && out.none { it.critiqueTitle.contains("CONCLUSION_STRONGER") }) {
      out.add(
        OverturnCondition(
          critiqueTitle = "Claim overreach (${claimAudit.highestClaimedLevel.displayLabel})",
          severityTier = claimAudit.severityTier,
          minimumEvidenceToOverturn = claimAudit.overturnEvidence,
          evidenceCategory = "SENSITIVITY_ANALYSIS"
        )
      )
    }

    return out
  }

  private fun extractSectionBlock(text: String, targetSection: String): String {
    val headerRegex = Regex(
      """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(abstract|introduction|background|methods?|materials?\s+and\s+methods?|results?|findings?|discussion|conclusions?|references|bibliography)\b.*$"""
    )
    val matches = headerRegex.findAll(text).toList()
    for (i in matches.indices) {
      val name = matches[i].groupValues[1].lowercase(Locale.US)
      val matched = when (targetSection) {
        "abstract" -> name.startsWith("abstract")
        "methods" -> name.startsWith("method") || name.startsWith("material")
        "results" -> name.startsWith("result") || name.startsWith("finding")
        "conclusion" -> name.startsWith("conclu")
        "discussion" -> name.startsWith("discuss")
        else -> false
      }
      if (matched) {
        val start = matches[i].range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
        if (start < end) return text.substring(start, end).trim()
      }
    }
    return ""
  }
}
