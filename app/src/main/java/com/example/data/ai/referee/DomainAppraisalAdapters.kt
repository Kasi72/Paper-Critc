package com.example.data.ai.referee

import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.BidirectionalAdjudicator.MethodMentionStatus
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
  val detail: String,
  val mentionStatus: MethodMentionStatus = MethodMentionStatus.UNCLEAR,
  val evidenceSection: String = "",
  val supportingQuote: String = "",
  val counterEvidenceQuote: String = ""
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
 *
 * Uses section-aware, negation-aware [BidirectionalAdjudicator.classifyMethodMentionStatus]
 * evidence verification rather than raw substring checks.
 */
object DomainAppraisalAdapters {

  private val referencesCutoffRegex = Regex("""(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(references|bibliography|works\s+cited)\s*$""")

  fun appraise(
    text: String,
    parentArchetype: StudyDesignArchetype
  ): DomainAppraisalReport {
    val cleanText = SemanticValidityEngine.extractActiveDesignClassificationText(text)
    val refCutoff = referencesCutoffRegex.find(cleanText)?.range?.first ?: cleanText.length
    val bodyText = cleanText.substring(0, refCutoff)
    val l = bodyText.lowercase(Locale.US)
    val subDesign = DomainStatisticalAuditModules.classifySubDesign(bodyText, parentArchetype)
    val calibrated = SemanticValidityEngine.classifyDesignWithConfidence(bodyText, parentArchetype)

    val domain = resolveDomain(subDesign, calibrated.calibratedDesign, l, calibrated.abstained)

    return when (domain) {
      AppraisalDomain.RANDOMIZED_TRIAL -> buildRctRob2(bodyText, calibrated.primaryConfidence)
      AppraisalDomain.OBSERVATIONAL_COHORT -> buildCohortRobinsINos(bodyText, calibrated.primaryConfidence)
      AppraisalDomain.CASE_CONTROL -> buildCaseControlNos(bodyText, calibrated.primaryConfidence)
      AppraisalDomain.DIAGNOSTIC_STUDY -> buildDiagnosticQuadas2(bodyText, calibrated.primaryConfidence)
      AppraisalDomain.SYSTEMATIC_REVIEW_META_ANALYSIS -> buildSystematicReviewAmstar2Grade(bodyText, calibrated.primaryConfidence)
      AppraisalDomain.PREDICTION_MODEL -> buildPredictionTripod(bodyText, calibrated.primaryConfidence)
      AppraisalDomain.QUALITATIVE_STUDY -> buildQualitativeCaspSrqrCoreq(bodyText, calibrated.primaryConfidence)
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

  private fun extractSentenceAt(text: String, start: Int, end: Int): String {
    var s = start
    while (s > 0 && text[s - 1] != '\n' && text[s - 1] != '.') s--
    var e = end
    while (e < text.length && text[e] != '\n' && text[e] != '.') e++
    return text.substring(s, e.coerceAtMost(text.length)).replace(Regex("""\s+"""), " ").trim().take(240)
  }

  private data class CriterionScan(
    val rating: DomainCriterionRating,
    val status: MethodMentionStatus,
    val section: String,
    val supportingQuote: String,
    val counterEvidenceQuote: String,
    val statusNote: String
  )

  private fun scanCriterion(
    bodyText: String,
    pattern: Regex,
    absenceRating: DomainCriterionRating = DomainCriterionRating.SOME_CONCERN
  ): CriterionScan {
    if (bodyText.isBlank()) {
      return CriterionScan(absenceRating, MethodMentionStatus.UNCLEAR, "", "", "", "No text provided")
    }
    var performedMatch: Triple<String, String, String>? = null // (sentence, section, matchedVal)
    var notPerformedMatch: Pair<String, String>? = null // (sentence, section)
    var proposedOrPriorMatch: Triple<MethodMentionStatus, String, String>? = null

    pattern.findAll(bodyText).forEach { m ->
      val section = BidirectionalAdjudicator.resolveSectionAtOffset(bodyText, m.range.first)
      if (section.equals("References", ignoreCase = true)) return@forEach
      val sentence = extractSentenceAt(bodyText, m.range.first, m.range.last + 1)
      val status = BidirectionalAdjudicator.classifyMethodMentionStatus(sentence, m.value, section)
      when (status) {
        MethodMentionStatus.PERFORMED -> {
          if (performedMatch == null) performedMatch = Triple(sentence, section, m.value)
        }
        MethodMentionStatus.NOT_PERFORMED -> {
          if (notPerformedMatch == null) notPerformedMatch = sentence to section
        }
        MethodMentionStatus.PROPOSED_ONLY, MethodMentionStatus.CITED_AS_PRIOR_WORK -> {
          if (proposedOrPriorMatch == null) proposedOrPriorMatch = Triple(status, sentence, section)
        }
        MethodMentionStatus.UNCLEAR -> Unit
      }
    }

    if (performedMatch != null) {
      val (sent, sec, _) = performedMatch!!
      return CriterionScan(
        rating = DomainCriterionRating.LOW_CONCERN,
        status = MethodMentionStatus.PERFORMED,
        section = sec,
        supportingQuote = sent,
        counterEvidenceQuote = "",
        statusNote = "Verified performed in $sec"
      )
    }
    if (notPerformedMatch != null) {
      val (sent, sec) = notPerformedMatch!!
      return CriterionScan(
        rating = DomainCriterionRating.HIGH_CONCERN,
        status = MethodMentionStatus.NOT_PERFORMED,
        section = sec,
        supportingQuote = "",
        counterEvidenceQuote = sent,
        statusNote = "Explicitly not performed / omitted ($sec)"
      )
    }
    if (proposedOrPriorMatch != null) {
      val (st, sent, sec) = proposedOrPriorMatch!!
      val label = if (st == MethodMentionStatus.PROPOSED_ONLY) "proposed for future work only" else "cited only as prior work"
      return CriterionScan(
        rating = absenceRating,
        status = st,
        section = sec,
        supportingQuote = "",
        counterEvidenceQuote = sent,
        statusNote = "Keyword $label ($sec); not confirmed performed"
      )
    }
    return CriterionScan(
      rating = absenceRating,
      status = MethodMentionStatus.UNCLEAR,
      section = "",
      supportingQuote = "",
      counterEvidenceQuote = "",
      statusNote = "Not reported in active body sections"
    )
  }

  private fun buildCriterion(
    domainKey: String,
    label: String,
    scan: CriterionScan,
    baseDetail: String
  ): DomainCriterionEvaluation {
    val detailText = "$baseDetail [${scan.statusNote}]"
    return DomainCriterionEvaluation(
      domainKey = domainKey,
      label = label,
      rating = scan.rating,
      detail = detailText,
      mentionStatus = scan.status,
      evidenceSection = scan.section,
      supportingQuote = scan.supportingQuote,
      counterEvidenceQuote = scan.counterEvidenceQuote
    )
  }

  private fun combineDualScans(
    domainKey: String,
    label: String,
    s1: CriterionScan,
    s2: CriterionScan,
    baseDetail: String
  ): DomainCriterionEvaluation {
    val rating = when {
      s1.status == MethodMentionStatus.NOT_PERFORMED || s2.status == MethodMentionStatus.NOT_PERFORMED ->
        DomainCriterionRating.HIGH_CONCERN
      s1.status == MethodMentionStatus.PERFORMED && s2.status == MethodMentionStatus.PERFORMED ->
        DomainCriterionRating.LOW_CONCERN
      s1.status == MethodMentionStatus.PERFORMED || s2.status == MethodMentionStatus.PERFORMED ->
        DomainCriterionRating.SOME_CONCERN
      else -> DomainCriterionRating.HIGH_CONCERN
    }
    val status = when {
      s1.status == MethodMentionStatus.NOT_PERFORMED || s2.status == MethodMentionStatus.NOT_PERFORMED -> MethodMentionStatus.NOT_PERFORMED
      s1.status == MethodMentionStatus.PERFORMED && s2.status == MethodMentionStatus.PERFORMED -> MethodMentionStatus.PERFORMED
      else -> MethodMentionStatus.UNCLEAR
    }
    val quote = listOf(s1.supportingQuote, s2.supportingQuote).filter { it.isNotBlank() }.joinToString(" | ").take(240)
    val counter = listOf(s1.counterEvidenceQuote, s2.counterEvidenceQuote).filter { it.isNotBlank() }.joinToString(" | ").take(240)
    val sec = listOf(s1.section, s2.section).filter { it.isNotBlank() }.distinct().joinToString("/")
    return DomainCriterionEvaluation(
      domainKey = domainKey,
      label = label,
      rating = rating,
      detail = "$baseDetail [${s1.statusNote}; ${s2.statusNote}]",
      mentionStatus = status,
      evidenceSection = sec,
      supportingQuote = quote,
      counterEvidenceQuote = counter
    )
  }

  private fun resolveDomain(
    subDesign: AuditSubDesign,
    calibratedDesign: CalibratedStudyDesign,
    l: String,
    abstained: Boolean
  ): AppraisalDomain {
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

  private fun buildRctRob2(bodyText: String, conf: Double): DomainAppraisalReport {
    val concealmentScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:allocation\s+concealment|concealed\s+allocation|central\s+(?:web[\s-]?based\s+)?randomiz\w*|sealed\s+(?:opaque\s+)?envelopes?|iwrs|interactive\s+web\s+response)\b""")
    )
    val blindingScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:double[\s-]?blind\w*|triple[\s-]?blind\w*|masked\s+outcome\s+adjudication|outcome\s+assessors?\s+(?:were\s+)?(?:blinded|masked)|open[\s-]?label|unblinded)\b""")
    )
    val ittScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:intention[\s-]?to[\s-]?treat|intent[\s-]?to[\s-]?treat|\bitt\b)\b""")
    )
    val attritionScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:attrition|loss\s+to\s+follow[\s-]?up|missing\s+outcome\s+data|drop[\s-]?out\s+rate|consort\s+flow)\b""")
    )
    val registrationScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:nct\d{6,}|isrctn\d{6,}|pre[\s-]?regist(?:ered|ration)|trial\s+protocol)\b""")
    )

    val criteria = listOf(
      buildCriterion("ROB2_D1", "D1: Randomization & Allocation Concealment", concealmentScan, "Random sequence & concealed allocation"),
      combineDualScans("ROB2_D2", "D2: Deviations from Intended Interventions (ITT & Blinding)", ittScan, blindingScan, "ITT estimand and participant/personnel masking"),
      buildCriterion("ROB2_D3", "D3: Missing Outcome Data / Attrition", attritionScan, "Completeness of outcome follow-up"),
      buildCriterion("ROB2_D4", "D4: Measurement of Outcome", blindingScan, "Assessor masking and objective endpoint checks"),
      buildCriterion("ROB2_D5", "D5: Selection of Reported Result / Pre-registration", registrationScan, "Trial protocol / primary endpoint registration")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.RANDOMIZED_TRIAL,
      instrumentName = "RoB 2",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with section-aware RoB 2 (5 signaling domains); weighted credibility score does NOT substitute for RoB 2 domain judgments."
    )
  }

  private fun buildCohortRobinsINos(bodyText: String, conf: Double): DomainAppraisalReport {
    val confoundingScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:multivariable\s+(?:cox|logistic|linear|poisson|regression)?\s*(?:adjusted|model)|propensity[\s-]?(?:score|matched|weighted)|inverse\s+probability\s+(?:of\s+treatment\s+)?weighting|adjusted\s+for\s+(?:age|sex|baseline|confound\w*)|confounder\s+adjustment)\b"""),
      absenceRating = DomainCriterionRating.HIGH_CONCERN
    )
    val immortalScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:time[\s-]?varying|landmark\s+analys\w*|target\s+trial|immortal[\s-]?time)\b""")
    )
    val exposureClassScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:exposure\s+ascertainment|time[\s-]?updated\s+exposure|validated\s+exposure|pharmacy\s+dispens\w*)\b""")
    )
    val missingScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:multiple\s+imputation|\bmice\b|missing\s+data\s+(?:imputation|sensitivity)|attrition\s+analys\w*)\b""")
    )
    val outcomeDefScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:validated\s+(?:outcome|endpoint|icd)|icd[\s-]?(?:9|10)|adjudicat(?:ed|ion)\s+endpoint)\b""")
    )
    val sensitivityScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:e[\s-]?value|sensitivity\s+analys\w*|negative\s+control\s+(?:outcome|exposure))\b""")
    )

    val criteria = listOf(
      buildCriterion("ROBINS_D1", "Confounding & Baseline Adjustment", confoundingScan, "DAG / multivariable or propensity control"),
      buildCriterion("ROBINS_D2", "Selection of Participants & Immortal-Time Bias", immortalScan, "Time-zero alignment & selection safeguards"),
      buildCriterion("ROBINS_D3", "Classification of Exposure / Intervention", exposureClassScan, "Non-differential vs differential exposure classification"),
      buildCriterion("ROBINS_D5", "Missing Data & Attrition Handling", missingScan, "MICE/IPW vs complete-case sensitivity"),
      combineDualScans("ROBINS_D6", "Outcome Ascertainment & Unmeasured Confounding Sensitivity", outcomeDefScan, sensitivityScan, "Objective endpoint validation & E-value/sensitivity")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.OBSERVATIONAL_COHORT,
      instrumentName = "ROBINS-I / Newcastle-Ottawa",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with section-aware ROBINS-I / Newcastle-Ottawa observational cohort domains; weighted score is non-substitutable."
    )
  }

  private fun buildCaseControlNos(bodyText: String, conf: Double): DomainAppraisalReport {
    val caseDefScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:incident\s+cases?|histolog\w+\s+confirm\w*|confirmed\s+cases?|diagnostic\s+criteria)\b""")
    )
    val controlSelScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:population[\s-]?based\s+(?:case[\s-]?control|controls?)|matched\s+controls?|incidence\s+density\s+sampl\w*)\b""")
    )
    val matchingScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:conditional\s+logistic|matched\s+(?:controls?|by\s+age)|multivariable\s+(?:logistic\s+)?adjusted)\b"""),
      absenceRating = DomainCriterionRating.HIGH_CONCERN
    )
    val exposureBlindScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:blinded\s+(?:structured\s+)?interview|structured\s+interview|medical\s+record\s+(?:abstraction|review))\b""")
    )
    val nonResponseScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:response\s+rate|recall\s+bias|non[\s-]?response)\b""")
    )

    val criteria = listOf(
      buildCriterion("CC_D1", "Adequacy of Case Definition", caseDefScan, "Independent validation & incident vs prevalent case clarity"),
      buildCriterion("CC_D2", "Selection & Representativeness of Controls", controlSelScan, "Same source population / Berkson bias prevention"),
      buildCriterion("CC_D3", "Comparability & Confounding (Matching / Conditional Model)", matchingScan, "Matched design paired with conditional logistic or multivariable adjustment"),
      buildCriterion("CC_D4", "Exposure Ascertainment & Recall Bias Mitigation", exposureBlindScan, "Blinded interview or objective prior records"),
      buildCriterion("CC_D5", "Same Ascertainment Method & Non-Response Balance", nonResponseScan, "Comparable response/missingness across cases and controls")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.CASE_CONTROL,
      instrumentName = "Newcastle-Ottawa (Case-Control) / ROBINS-I",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with section-aware Case-Control Newcastle-Ottawa / ROBINS-I adapter; odds ratios only (absolute risk not directly estimable without base rates)."
    )
  }

  private fun buildDiagnosticQuadas2(bodyText: String, conf: Double): DomainAppraisalReport {
    val consecutiveScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:consecutive\s+(?:patients?|sample|enrollment)|random\s+sample|prospective\s+(?:diagnostic\s+)?cohort)\b""")
    )
    val indexBlindScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:blinded\s+interpretation|pre[\s-]?specified\s+(?:threshold|cutoff)|masked\s+to\s+(?:the\s+)?reference)\b""")
    )
    val refStdScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:reference\s+standard|gold\s+standard|rt[\s-]?pcr|histopatholog\w*\s+biopsy|biopsy)\b"""),
      absenceRating = DomainCriterionRating.HIGH_CONCERN
    )
    val flowTimingScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:all\s+patients\s+received|partial\s+verification|verification\s+bias|interval\s+between\s+index)\b""")
    )

    val criteria = listOf(
      buildCriterion("Q2_D1", "Patient Selection (Consecutive / Avoid Case-Control Spectrum Bias)", consecutiveScan, "Representative clinical spectrum"),
      buildCriterion("Q2_D2", "Index Test (Blinded Interpretation & Prespecified Threshold)", indexBlindScan, "Threshold locked prior to reference comparison"),
      buildCriterion("Q2_D3", "Reference Standard (Accuracy & Independence from Index)", refStdScan, "Avoids incorporation bias"),
      buildCriterion("Q2_D4", "Flow & Timing (Partial Verification & Appropriate Interval)", flowTimingScan, "All participants verified with consistent reference standard")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.DIAGNOSTIC_STUDY,
      instrumentName = "QUADAS-2",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with section-aware QUADAS-2 across all 4 diagnostic bias domains."
    )
  }

  private fun buildSystematicReviewAmstar2Grade(bodyText: String, conf: Double): DomainAppraisalReport {
    val protocolScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:prospero|crd\d{6,}|pre[\s-]?registered\s+protocol|review\s+protocol)\b""")
    )
    val duplicateScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:two\s+independent\s+(?:reviewers?|authors?|extractors?)|duplicate\s+(?:screening|extraction)|cohen'?s?\s+kappa|\bκ\s*=)\b""")
    )
    val robToolScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:rob[\s-]?2|robins[\s-]?i|newcastle[\s-]?ottawa|quadas[\s-]?2)\b"""),
      absenceRating = DomainCriterionRating.HIGH_CONCERN
    )
    val heterogeneityScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:i[²2]\s*=|tau[\s-]?squared|τ²|dersimonian|reml|random[\s-]?effects\s+meta[\s-]?analys\w*|prediction\s+interval)\b""")
    )
    val pubBiasScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:egger'?s?\s+(?:test|regression)|funnel\s+plot|begg'?s?\s+test|trim[\s-]?and[\s-]?fill)\b""")
    )

    val criteria = listOf(
      buildCriterion("AMSTAR_PICO_PROTO", "Protocol Registration (PROSPERO) & PICO Clarity", protocolScan, "Pre-specified review eligibility and methods"),
      buildCriterion("AMSTAR_DUP", "Duplicate Study Selection & Data Extraction", duplicateScan, "Dual-reviewer consensus and inter-rater reliability"),
      buildCriterion("AMSTAR_ROB", "Validated Risk-of-Bias Instrument (Distinct from PRISMA)", robToolScan, "RoB 2 / ROBINS-I / NOS / QUADAS-2 appraisal"),
      buildCriterion("AMSTAR_SYNTH", "Synthesis Model, Heterogeneity (I²/τ²) & Prediction Interval", heterogeneityScan, "Appropriate random-effects/HKSJ pooling"),
      buildCriterion("AMSTAR_PUBBIAS", "Publication Bias & Certainty Rating (GRADE)", pubBiasScan, "Funnel/Egger investigation and certainty grading")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.SYSTEMATIC_REVIEW_META_ANALYSIS,
      instrumentName = "AMSTAR 2 / GRADE",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with section-aware AMSTAR 2 / GRADE synthesis adapter (distinguishing PRISMA reporting from RoB/certainty)."
    )
  }

  private fun buildPredictionTripod(bodyText: String, conf: Double): DomainAppraisalReport {
    val splitScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:patient[\s-]?level\s+split|hospital[\s-]?level|subject[\s-]?level|nested\s+cross[\s-]?validation|groupkfold)\b""")
    )
    val calibScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:calibration\s+(?:slope|intercept|plot|curve)|brier\s+score|expected\s+calibration\s+error|\bece\b)\b"""),
      absenceRating = DomainCriterionRating.HIGH_CONCERN
    )
    val externalScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:external\s+validation|temporal\s+validation|out[\s-]?of[\s-]?distribution\s+validation|independent\s+validation\s+cohort)\b""")
    )
    val utilityScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:decision\s+curve\s+analys\w*|net\s+benefit|pr[\s-]?auc|precision[\s-]?recall\s+auc)\b""")
    )

    val criteria = listOf(
      buildCriterion("TRIPOD_PART", "Participants & Leakage-Free Grouping / Splitting", splitScan, "Patient/site-disjoint splits & in-fold preprocessing"),
      buildCriterion("TRIPOD_CALIB", "Calibration Slope / Brier / ECE Reporting", calibScan, "Risk probability calibration beyond AUROC"),
      buildCriterion("TRIPOD_EXT", "External / Temporal Validation", externalScan, "Out-of-sample generalizability across sites/time"),
      buildCriterion("TRIPOD_UTIL", "Clinical Net Benefit & Imbalance Metrics", utilityScan, "Decision-curve analysis / PR-AUC")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.PREDICTION_MODEL,
      instrumentName = "TRIPOD / PROBAST",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with section-aware TRIPOD / PROBAST prediction-model adapter (calibration, leakage, and external validation)."
    )
  }

  private fun buildQualitativeCaspSrqrCoreq(bodyText: String, conf: Double): DomainAppraisalReport {
    val frameworkScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:coreq|srqr|casp|thematic\s+analysis|grounded\s+theory|phenomenolog\w+)\b""")
    )
    val reflexivityScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:reflexiv\w*|positionality\s+statement)\b""")
    )
    val saturationScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:thematic\s+saturation|data\s+saturation|purposive\s+sampl\w*|maximum\s+variation)\b""")
    )
    val trustScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:audit\s+trail|dual[\s-]?cod\w*|triangulat\w*|member\s+check\w*|inter[\s-]?coder)\b""")
    )
    val ethicsScan = scanCriterion(
      bodyText,
      Regex("""(?i)\b(?:irb\b|ethics\s+(?:approval|committee)|informed\s+consent)\b""")
    )

    val criteria = listOf(
      buildCriterion("QUAL_DESIGN", "Qualitative Methodology Congruence (CASP/SRQR/COREQ)", frameworkScan, "Clear methodological framing (grounded theory, thematic, phenomenology)"),
      buildCriterion("QUAL_REFLEX", "Researcher Reflexivity & Positionality", reflexivityScan, "Explicit accounting of researcher relationship and assumptions"),
      buildCriterion("QUAL_SAMP", "Purposive Sampling & Saturation Justification", saturationScan, "Information power / thematic saturation criteria"),
      buildCriterion("QUAL_TRUST", "Trustworthiness (Audit Trail, Triangulation, Member Checking)", trustScan, "Analytical credibility and confirmability"),
      buildCriterion("QUAL_ETHICS", "Ethical Clarity & Context Transferability", ethicsScan, "Consent, confidentiality, and thick context description")
    )
    return DomainAppraisalReport(
      domain = AppraisalDomain.QUALITATIVE_STUDY,
      instrumentName = "CASP / SRQR / COREQ",
      classificationConfidence = conf,
      restrictsVerdictDueToUncertainDesign = false,
      rubricCriteria = criteria,
      domainSummaryNote = "Evaluated with section-aware CASP / SRQR / COREQ qualitative appraisal adapter; inapplicable NHST/p-value checks suppressed."
    )
  }
}
