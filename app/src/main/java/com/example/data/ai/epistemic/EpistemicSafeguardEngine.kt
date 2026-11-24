package com.example.data.ai.epistemic

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.semantic.MethodologicalAssessmentStatus
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.StatisticalAuditReport
import com.example.data.ai.stats.StatisticalFindingType
import com.example.util.CoverageStatus
import com.example.util.DocumentCoverageReport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic Epistemic Safeguard Engine implementing Safeguards #1–#12 and #15.
 * Designed to prevent confident misinterpretation of study designs, missing evidence,
 * denominator shifts, claim overreach, and clinically uncalibrated or underpowered results.
 */
object EpistemicSafeguardEngine {

  fun evaluate(
    text: String,
    statAudit: StatisticalAuditReport,
    coverageReport: DocumentCoverageReport? = null,
    extractionConfidence: Int = coverageReport?.extractionConfidence ?: DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN
  ): EpistemicSafeguardReport {
    val effectiveCoverage = coverageReport ?: DocumentCoverageReport.unknown()
    val effectiveConfidence = if (coverageReport == null && extractionConfidence == DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN) {
      DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN
    } else {
      extractionConfidence
    }
    val effectiveCoverageStatus = when {
      coverageReport == null -> if (effectiveConfidence > 0) CoverageStatus.PARTIAL else CoverageStatus.UNKNOWN
      effectiveCoverage.isCoverageUnknown || effectiveConfidence == DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN -> CoverageStatus.UNKNOWN
      else -> effectiveCoverage.coverageStatus
    }

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
    val stopAndAsk = buildStopAndAskAssessment(
      clean,
      lower,
      identityCard,
      denominatorLedger,
      primaryDecl,
      statAudit,
      coverageReport,
      effectiveConfidence,
      effectiveCoverageStatus
    )
    val uncertaintyBudget = buildUncertaintyBudget(
      lower,
      subDesign,
      statAudit,
      denominatorLedger,
      coverageReport,
      effectiveConfidence,
      effectiveCoverageStatus,
      stopAndAsk
    )
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
      stopAndAsk = stopAndAsk,
      coverageStatus = effectiveCoverageStatus,
      extractionConfidence = effectiveConfidence,
      strongConclusionsDisabled = stopAndAsk.strongConclusionsDisabled
    )
  }

  // ============================================================================
  // #1. STUDY IDENTITY CARD (Design-specific, provenance-ranked, venue/ref-safe)
  // ============================================================================
  private val affiliationOrVenueLineRegex = Regex(
    """(?i)(?:\b(?:department\s+of|division\s+of|faculty\s+of|school\s+of|institute\s+of|university\s+of|universit[äa]tsmedizin|charit[eé]|campus\s+\w+|medical\s+center|hospital\b|clinic\b|correspond(?:ence|ing\s+author)|e-?mail\s*:|orcid|doi\s*:|issn\b|©\s*\d{4}|copyright\b|published\s+by|received\s*:|accepted\s*:|available\s+online|open\s+access|funding\s*:|acknowledg(?:e)?ments?|competing\s+interests|conflict\s+of\s+interest)\b|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})"""
  )
  private val referenceSectionHeadingRegex = Regex(
    """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(references|bibliography|works\s+cited|literature\s+cited)\s*$"""
  )
  private val inlineCitationClauseRegex = Regex(
    """\([^()\n]{0,90}\b(?:19|20)\d{2}[a-z]?\b[^()\n]{0,40}\)"""
  )
  private val priorWorkSentenceRegex = Regex(
    """(?i)\b(?:previously\s+reported|prior\s+stud(?:y|ies)|earlier\s+(?:study|trial|review|meta-analysis)|in\s+contrast\s+to\s+[A-Z][a-z]+|consistent\s+with\s+[A-Z][a-z]+|[A-Z][a-z]+\s+et\s+al\.?\s*\(?\d{4}\)?)"""
  )

  private data class IdentitySectionCorpus(
    val methodsText: String,
    val abstractText: String,
    val resultsText: String,
    val cleanBodyText: String
  )

  private fun buildCleanIdentityCorpus(rawText: String): IdentitySectionCorpus {
    val refMatch = referenceSectionHeadingRegex.find(rawText)
    val withoutRefs = if (refMatch != null && refMatch.range.first > 80) {
      rawText.substring(0, refMatch.range.first)
    } else {
      rawText
    }
    val filteredLines = withoutRefs.lines().filterNot { line ->
      val trimmed = line.trim()
      if (trimmed.isEmpty()) return@filterNot false
      if (trimmed.startsWith("[PAGE ", ignoreCase = true)) return@filterNot false
      // Exclude author affiliation, campus/venue headers, publisher boilerplate, and correspondence lines
      val isAffiliation = affiliationOrVenueLineRegex.containsMatchIn(trimmed) &&
        !trimmed.contains("Methods", ignoreCase = true) &&
        !trimmed.contains("Abstract", ignoreCase = true) &&
        !trimmed.contains("Results", ignoreCase = true) &&
        trimmed.length < 220
      val isStandaloneCitation = Regex("""^\s*(?:\[\d+\]|\d+\.\s+[A-Z][a-z]+,\s+[A-Z])""").containsMatchIn(trimmed)
      isAffiliation || isStandaloneCitation
    }
    val cleaned = filteredLines.joinToString("\n")
      .replace(inlineCitationClauseRegex, " ")

    fun sliceSection(headerPattern: String): String {
      val headerRegex = Regex("""(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(?:$headerPattern)\b[^\n]*$""")
      val nextHeaderRegex = Regex(
        """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(?:abstract|introduction|background|methods?|materials?\s+and\s+methods?|statistical\s+analysis|results?|findings?|discussion|conclusions?|limitations?|references|bibliography|appendix|supplementary)\b[^\n]*$"""
      )
      val startMatch = headerRegex.find(cleaned) ?: return ""
      val bodyStart = startMatch.range.last + 1
      if (bodyStart >= cleaned.length) return ""
      val nextMatch = nextHeaderRegex.find(cleaned, startIndex = bodyStart)
      val bodyEnd = nextMatch?.range?.first ?: cleaned.length
      return cleaned.substring(bodyStart, bodyEnd).trim()
    }

    val methods = sliceSection("""methods?|materials?\s+and\s+methods?|study\s+design|search\s+strategy|statistical\s+analysis""")
    val abs = sliceSection("""abstract|summary""")
    val results = sliceSection("""results?|findings?""")
    return IdentitySectionCorpus(
      methodsText = methods,
      abstractText = abs,
      resultsText = results,
      cleanBodyText = cleaned
    )
  }

  private fun isPriorCitationSentence(source: String, matchStart: Int): Boolean {
    val lineStart = source.lastIndexOf('.', (matchStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = source.indexOf('.', matchStart).let { if (it < 0) source.length else it }
    val sentence = source.substring(lineStart, lineEnd)
    return priorWorkSentenceRegex.containsMatchIn(sentence)
  }

  private fun extractRankedCandidate(
    corpus: IdentitySectionCorpus,
    fullRawText: String,
    regex: Regex,
    groupIndex: Int = 0,
    validator: (String) -> Boolean = { true }
  ): ExtractedField<String> {
    data class RankedHit(val value: String, val section: String, val page: Int?, val quote: String, val score: Double)
    val hits = mutableListOf<RankedHit>()
    val orderedSections = listOf(
      "Methods" to (corpus.methodsText to 1.0),
      "Abstract" to (corpus.abstractText to 0.85),
      "Results" to (corpus.resultsText to 0.72),
      "Body" to (corpus.cleanBodyText to 0.55)
    )
    for ((sectionName, pair) in orderedSections) {
      val (sectionText, weight) = pair
      if (sectionText.isBlank()) continue
      regex.findAll(sectionText).take(6).forEach { m ->
        if (isPriorCitationSentence(sectionText, m.range.first)) return@forEach
        val candidate = (if (groupIndex == 0) m.value else m.groupValues.getOrNull(groupIndex).orEmpty())
          .replace(Regex("""\s+"""), " ")
          .trim()
        if (candidate.isBlank() || !validator(candidate)) return@forEach
        if (affiliationOrVenueLineRegex.containsMatchIn(candidate)) return@forEach
        val rawOffset = fullRawText.indexOf(candidate)
        val page = if (rawOffset >= 0) QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(fullRawText, rawOffset).takeIf { it > 0 } else null
        hits.add(RankedHit(candidate, sectionName, page, m.value.trim().take(120), weight))
      }
      if (hits.any { it.section == sectionName }) break
    }
    if (hits.isEmpty()) {
      return ExtractedField(
        value = null,
        applicability = FieldApplicability.NOT_REPORTED,
        sourceSection = null,
        sourcePage = null,
        sourceQuote = null,
        confidence = 0.0
      )
    }
    val best = hits.maxByOrNull { it.score } ?: return ExtractedField(
      value = null,
      applicability = FieldApplicability.NOT_REPORTED,
      sourceSection = null,
      sourcePage = null,
      sourceQuote = null,
      confidence = 0.0
    )
    val distinctValues = hits.map { it.value }.distinct()
    return ExtractedField(
      value = best.value,
      applicability = FieldApplicability.APPLICABLE,
      sourceSection = best.section,
      sourcePage = best.page,
      sourceQuote = best.quote,
      confidence = best.score,
      ambiguousCandidates = if (distinctValues.size > 1) distinctValues.take(3) else emptyList()
    )
  }

  private fun isDescriptiveOrNormativeSynthesis(subDesign: AuditSubDesign, cleanLower: String): Boolean {
    if (subDesign != AuditSubDesign.META_ANALYSIS && subDesign != AuditSubDesign.SYSTEMATIC_REVIEW) return false
    val normativeSignals = cleanLower.contains("bed need") ||
      cleanLower.contains("beds per") ||
      cleanLower.contains("psychiatric bed") ||
      cleanLower.contains("inpatient bed") ||
      cleanLower.contains("capacity") ||
      cleanLower.contains("prevalence") ||
      cleanLower.contains("incidence") ||
      cleanLower.contains("normative") ||
      cleanLower.contains("benchmark") ||
      cleanLower.contains("resource planning") ||
      cleanLower.contains("service planning") ||
      cleanLower.contains("epidemiological synthesis")
    val hasExplicitInterventionTrial = cleanLower.contains("randomized controlled trial") ||
      cleanLower.contains("randomised controlled trial") ||
      cleanLower.contains("intervention vs") ||
      cleanLower.contains("versus placebo") ||
      cleanLower.contains("treatment vs control") ||
      cleanLower.contains("drug efficacy")
    return normativeSignals && !hasExplicitInterventionTrial
  }

  private fun buildStudyIdentityCard(
    text: String,
    l: String,
    subDesign: AuditSubDesign,
    statAudit: StatisticalAuditReport,
    ledger: DenominatorLedger,
    coverage: DocumentCoverageReport?
  ): StudyIdentityCard {
    val corpus = buildCleanIdentityCorpus(text)
    val cleanBody = corpus.cleanBodyText
    val cleanLower = cleanBody.lowercase(Locale.US)
    val methodsAndAbstractLower = "${corpus.methodsText}\n${corpus.abstractText}".lowercase(Locale.US).ifBlank { cleanLower }
    val extractedMap = mutableMapOf<String, ExtractedField<String>>()

    val hasExplicitDesignKeyword = cleanLower.contains("randomized") || cleanLower.contains("randomised") || cleanLower.contains("cohort") ||
      cleanLower.contains("case-control") || cleanLower.contains("cross-sectional") || cleanLower.contains("meta-analysis") ||
      cleanLower.contains("systematic review") || cleanLower.contains("difference-in-differences") || cleanLower.contains("instrumental variable") ||
      cleanLower.contains("regression discontinuity") || cleanLower.contains("diagnostic accuracy") || cleanLower.contains("kaplan-meier") ||
      cleanLower.contains("cox proportional") || cleanLower.contains("neural network") || cleanLower.contains("transformer") ||
      cleanLower.contains("benchmark") || cleanLower.contains("theorem") || cleanLower.contains("proof") || cleanLower.contains("descriptive") || cleanLower.contains("survey")

    val isCrossSectional = cleanLower.contains("cross-sectional") || cleanLower.contains("cross sectional") || cleanLower.contains("prevalence survey")
    val isReviewOrMeta = subDesign == AuditSubDesign.META_ANALYSIS || subDesign == AuditSubDesign.SYSTEMATIC_REVIEW
    val isNormativeReview = isDescriptiveOrNormativeSynthesis(subDesign, cleanLower)

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
      isReviewOrMeta ->
        IdentityField(
          if (cleanLower.contains("estimate")) "Published studies / publications and extracted estimates (study-level synthesis)"
          else "Published studies / trials (aggregate study-level; NOT independent patient-level rows)",
          ReportingStatus.REPORTED_AND_ADEQUATE
        )
      subDesign == AuditSubDesign.FORMAL_THEOREM ->
        IdentityField("Mathematical objects / theorems / formal proofs", ReportingStatus.REPORTED_AND_ADEQUATE)
      cleanLower.contains("state-year") || cleanLower.contains("county-year") || cleanLower.contains("firm-year") || cleanLower.contains("panel") ->
        IdentityField("Clustered panel units (e.g., state/firm/region-time observations)", ReportingStatus.REPORTED_AND_ADEQUATE)
      cleanLower.contains("patient") || cleanLower.contains("participant") || cleanLower.contains("subject") ->
        IdentityField(
          if (cleanLower.contains("multiple images per patient") || cleanLower.contains("repeated measures") || cleanLower.contains("multiple visits"))
            "Repeated observations nested within patients/subjects"
          else "Individual patients / participants",
          ReportingStatus.REPORTED_AND_ADEQUATE
        )
      cleanLower.contains("image") || cleanLower.contains("sample") || cleanLower.contains("document") ->
        IdentityField("Dataset instances / samples", ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Uncertain unit of analysis", ReportingStatus.NOT_REPORTED)
    }

    val popExtracted = extractRankedCandidate(
      corpus = corpus,
      fullRawText = text,
      regex = Regex(
        """(?:patients?|participants?|adults?|children|adolescents?|subjects?|hospitals?|countries|regions?|states?|populations?|psychiatric\s+(?:services?|inpatient\s+services?|populations?)|studies|publications)\s+(?:with|undergoing|diagnosed\s+with|aged|from|across|in|requiring)\s+[^.,;\n]{4,75}""",
        RegexOption.IGNORE_CASE
      )
    )
    extractedMap["population"] = popExtracted
    val population = if (popExtracted.value != null) {
      IdentityField(popExtracted.value, ReportingStatus.REPORTED_AND_ADEQUATE)
    } else if (subDesign == AuditSubDesign.FORMAL_THEOREM) {
      IdentityField("Formal domain / metric or Hilbert/Banach space", ReportingStatus.REPORTED_AND_ADEQUATE)
    } else if (cleanLower.contains("patient") || cleanLower.contains("participant") || cleanLower.contains("cohort") || cleanLower.contains("studies") || cleanLower.contains("population")) {
      IdentityField("Study population referenced generically (exact eligibility criteria limited)", ReportingStatus.REPORTED_BUT_INADEQUATE)
    } else {
      IdentityField("Population not identified", ReportingStatus.NOT_REPORTED)
    }

    // Exposure / Intervention: STRICTLY exclude venue/affiliation lines and mark NOT_APPLICABLE for descriptive/normative meta-analyses
    val hasExplicitReviewIntervention = isReviewOrMeta && !isNormativeReview && Regex(
      """\b(?:intervention\s+group|treatment\s+arm|randomized\s+trials?\s+of|efficacy\s+of|effectiveness\s+of\s+(?:a|an|the)?\s*\w+\s+(?:drug|therapy|intervention|treatment))\b""",
      RegexOption.IGNORE_CASE
    ).containsMatchIn(methodsAndAbstractLower)

    val expExtracted = if (isReviewOrMeta && !hasExplicitReviewIntervention) {
      ExtractedField<String>(
        value = "Not applicable",
        applicability = FieldApplicability.NOT_APPLICABLE,
        sourceSection = null,
        sourcePage = null,
        sourceQuote = null,
        confidence = 1.0
      )
    } else {
      extractRankedCandidate(
        corpus = corpus,
        fullRawText = text,
        regex = Regex(
          """\b(?:intervention|treatment|drug|pharmacotherapy|exposure|policy|minimum[- ]wage|statin|biomarker|classifier|model|algorithm)\s+(?:of|with|using|:)?\s*[^.,;\n]{3,60}""",
          RegexOption.IGNORE_CASE
        ),
        validator = { cand ->
          val cl = cand.lowercase(Locale.US)
          !cl.contains("campus") && !cl.contains("psychotherapy") && !cl.contains("department") && !cl.contains("university")
        }
      )
    }
    extractedMap["exposureOrIntervention"] = expExtracted
    val exposure = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM -> IdentityField("Formal hypotheses / theorem conditions", ReportingStatus.REPORTED_AND_ADEQUATE)
      expExtracted.applicability == FieldApplicability.NOT_APPLICABLE -> IdentityField(
        value = "Not applicable (descriptive / normative synthesis without treatment intervention)",
        status = ReportingStatus.REPORTED_AND_ADEQUATE,
        applicability = FieldApplicability.NOT_APPLICABLE
      )
      expExtracted.value != null -> IdentityField(expExtracted.value, ReportingStatus.REPORTED_AND_ADEQUATE)
      cleanLower.contains("descriptive") || cleanLower.contains("normative") || cleanLower.contains("bed need") ->
        IdentityField(
          value = "Not applicable (descriptive/normative estimation study)",
          status = ReportingStatus.REPORTED_AND_ADEQUATE,
          applicability = FieldApplicability.NOT_APPLICABLE
        )
      else -> IdentityField("Exposure / intervention not explicitly specified", ReportingStatus.NOT_REPORTED)
    }

    // Comparator: NOT_APPLICABLE for descriptive/normative meta-analyses unless explicitly defined
    val compExtracted = if (isReviewOrMeta && !hasExplicitReviewIntervention) {
      ExtractedField<String>(
        value = "Not applicable",
        applicability = FieldApplicability.NOT_APPLICABLE,
        sourceSection = null,
        sourcePage = null,
        sourceQuote = null,
        confidence = 1.0
      )
    } else {
      extractRankedCandidate(
        corpus = corpus,
        fullRawText = text,
        regex = Regex(
          """\b(?:control\s+group|placebo|standard\s+of\s+care|usual\s+care|baseline\s+model|reference\s+standard|compared\s+(?:with|to)\s+[^.,;\n]{3,45}|versus\s+[^.,;\n]{3,45})""",
          RegexOption.IGNORE_CASE
        )
      )
    }
    extractedMap["comparator"] = compExtracted
    val comparator = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM -> IdentityField("Prior theorem bounds / counterexample baselines", ReportingStatus.REPORTED_AND_ADEQUATE)
      compExtracted.applicability == FieldApplicability.NOT_APPLICABLE -> IdentityField(
        value = "Not applicable (single-group / normative review without comparator arm)",
        status = ReportingStatus.REPORTED_AND_ADEQUATE,
        applicability = FieldApplicability.NOT_APPLICABLE
      )
      compExtracted.value != null -> IdentityField(compExtracted.value, ReportingStatus.REPORTED_AND_ADEQUATE)
      cleanLower.contains("descriptive") || cleanLower.contains("prevalence") || cleanLower.contains("normative") ->
        IdentityField(
          value = "Not applicable (single-group descriptive/normative target)",
          status = ReportingStatus.REPORTED_AND_ADEQUATE,
          applicability = FieldApplicability.NOT_APPLICABLE
        )
      else -> IdentityField("Comparator not reported", ReportingStatus.NOT_REPORTED)
    }

    val outExtracted = extractRankedCandidate(
      corpus = corpus,
      fullRawText = text,
      regex = Regex(
        """\b(?:primary\s+(?:outcome|endpoint)|outcome\s+(?:was|measure)|psychiatric\s+bed\s+need|bed\s+need\s+per\s+[\d,]+|beds?\s+per\s+[\d,]+\s*(?:population|inhabitants)?|mortality|survival|stroke|readmission|hospitalization|auroc|accuracy|sensitivity|prevalence|employment|wage|convergence|uniqueness)[^.,;\n]{0,60}""",
        RegexOption.IGNORE_CASE
      )
    )
    extractedMap["outcome"] = outExtracted
    val outcome = if (outExtracted.value != null) {
      IdentityField(outExtracted.value, ReportingStatus.REPORTED_AND_ADEQUATE)
    } else {
      IdentityField("Primary outcome uncertain / not reported", ReportingStatus.NOT_REPORTED)
    }

    // Time Horizon / Search Window: prefer explicit year range (e.g. 2000–2023) for systematic reviews, NOT earliest random duration like "7 years"
    val yearRangeRegex = Regex("""\b(?:from\s+)?(19\d{2}|20\d{2})\s*(?:to|–|—|-|through)\s*(19\d{2}|20\d{2}|present)\b""", RegexOption.IGNORE_CASE)
    val followUpRegex = Regex("""(?:\bmedian\s+follow-?up[^.,;\n]{0,35}|\bfollow-?up\s+(?:of|period\s+of)?\s*\d+(?:\.\d+)?[- ]?(?:day|week|month|year)s?\b|\b\d+(?:\.\d+)?[- ]?(?:day|week|month|year)s?\s+follow-?up\b)""", RegexOption.IGNORE_CASE)
    val genericDurationRegex = Regex("""\b\d+(?:\.\d+)?[- ]?(?:day|week|month|year)s?\b""", RegexOption.IGNORE_CASE)

    val horizonExtracted = if (isReviewOrMeta) {
      extractRankedCandidate(corpus, text, yearRangeRegex).takeIf { it.value != null }
        ?: extractRankedCandidate(corpus, text, Regex("""\b(?:searched\s+(?:from|up\s+to|until)|inception\s+(?:to|through|until)|published\s+(?:between|from))[^.,;\n]{4,55}""", RegexOption.IGNORE_CASE)).takeIf { it.value != null }
        ?: extractRankedCandidate(corpus, text, followUpRegex).takeIf { it.value != null }
        ?: extractRankedCandidate(corpus, text, Regex("""\bover\s+\d+(?:\.\d+)?[- ]?(?:day|week|month|year)s?\b""", RegexOption.IGNORE_CASE))
    } else {
      extractRankedCandidate(corpus, text, followUpRegex).takeIf { it.value != null }
        ?: extractRankedCandidate(corpus, text, yearRangeRegex).takeIf { it.value != null }
        ?: extractRankedCandidate(corpus, text, genericDurationRegex)
    }
    extractedMap["timeHorizon"] = horizonExtracted
    val timeHorizon = when {
      subDesign == AuditSubDesign.FORMAL_THEOREM || subDesign == AuditSubDesign.ML_BENCHMARK ->
        IdentityField(horizonExtracted.value ?: "Not time-horizon dependent (static formal/benchmark evaluation)", ReportingStatus.REPORTED_AND_ADEQUATE)
      horizonExtracted.value != null -> IdentityField(horizonExtracted.value, ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Follow-up / search time window not reported", ReportingStatus.NOT_REPORTED)
    }

    // Primary Estimand: NEVER use raw substring matching like contains("att") (which falsely matches "pattern"/"matter"/"attention")!
    val hasExplicitAtt = Regex("""\b(?:average\s+treatment\s+effect\s+on\s+the\s+treated|ATT)\b""").containsMatchIn(cleanBody) &&
      (cleanLower.contains("treatment") || cleanLower.contains("difference-in-differences") || cleanLower.contains("propensity") || cleanLower.contains("causal"))
    val hasExplicitAte = Regex("""\b(?:average\s+treatment\s+effect|ATE)\b""").containsMatchIn(cleanBody) &&
      (cleanLower.contains("treatment") || cleanLower.contains("causal") || cleanLower.contains("randomized") || cleanLower.contains("randomised"))
    val hasExplicitLate = Regex("""\b(?:local\s+average\s+treatment\s+effect|LATE)\b""").containsMatchIn(cleanBody) &&
      (cleanLower.contains("instrumental") || cleanLower.contains("2sls") || cleanLower.contains("complier"))

    val estimand = when {
      isNormativeReview -> IdentityField(
        "Descriptive / pooled normative synthesis estimand across included publications (not a causal treatment effect)",
        ReportingStatus.REPORTED_AND_ADEQUATE
      )
      subDesign == AuditSubDesign.META_ANALYSIS -> IdentityField(
        "Pooled summary effect across included studies (random/fixed-effects synthesis)",
        ReportingStatus.REPORTED_AND_ADEQUATE
      )
      subDesign == AuditSubDesign.SYSTEMATIC_REVIEW -> IdentityField(
        "Qualitative / narrative synthesis estimand across eligible publications",
        ReportingStatus.REPORTED_AND_ADEQUATE
      )
      cleanLower.contains("intention-to-treat") || cleanLower.contains("intent-to-treat") ->
        IdentityField("Intention-to-treat (treatment policy estimand)", ReportingStatus.REPORTED_AND_ADEQUATE)
      cleanLower.contains("per-protocol") ->
        IdentityField("Per-protocol estimand (vulnerable to post-randomization selection)", ReportingStatus.REPORTED_BUT_INADEQUATE)
      hasExplicitAtt -> IdentityField("Average Treatment Effect on the Treated (ATT)", ReportingStatus.REPORTED_AND_ADEQUATE)
      hasExplicitAte -> IdentityField("Average Treatment Effect (ATE)", ReportingStatus.REPORTED_AND_ADEQUATE)
      hasExplicitLate -> IdentityField("Local Average Treatment Effect (LATE / IV)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.DIAGNOSTIC_ACCURACY -> IdentityField("Diagnostic accuracy (sensitivity, specificity, likelihood ratios)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.ML_BENCHMARK -> IdentityField("Out-of-sample predictive performance (AUROC / PR-AUC / calibration)", ReportingStatus.REPORTED_AND_ADEQUATE)
      subDesign == AuditSubDesign.FORMAL_THEOREM -> IdentityField("Deductive mathematical truth under stated regularity assumptions", ReportingStatus.REPORTED_AND_ADEQUATE)
      isCrossSectional -> IdentityField("Cross-sectional descriptive / associational prevalence contrast", ReportingStatus.REPORTED_AND_ADEQUATE)
      else -> IdentityField("Primary estimand not explicitly declared", ReportingStatus.NOT_REPORTED)
    }

    val analysisExtracted = extractRankedCandidate(
      corpus = corpus,
      fullRawText = text,
      regex = Regex(
        """\b(?:(?:dersimonian[- ]laird\s+|reml\s+|inverse[- ]variance\s+)?(?:random-?effects|fixed-?effect)\s+meta-?analysis|dersimonian[- ]laird|restricted\s+maximum\s+likelihood|reml|inverse[- ]variance|meta-?regression|cox\s+proportional\s+hazards|logistic\s+regression|linear\s+regression|two-way\s+fixed\s+effects|difference-in-differences|2sls|kaplan-meier|mixed-effects|gee|transformer|neural\s+network|random\s+forest|xgboost|anova|chi-square|t-test|proof\s+by\s+contradiction)\b""",
        RegexOption.IGNORE_CASE
      )
    )
    extractedMap["primaryAnalysis"] = analysisExtracted
    val primaryAnalysis = if (analysisExtracted.value != null) {
      IdentityField(analysisExtracted.value, ReportingStatus.REPORTED_AND_ADEQUATE)
    } else if (statAudit.claims.any { !it.isFromReferenceOrCitation && it.analysisType.isNotBlank() }) {
      IdentityField(statAudit.claims.first { !it.isFromReferenceOrCitation && it.analysisType.isNotBlank() }.analysisType, ReportingStatus.REPORTED_AND_ADEQUATE)
    } else {
      IdentityField("Primary statistical/analytical model not explicitly identified", ReportingStatus.NOT_REPORTED)
    }

    // Systematic Review / Meta-Analysis counts: extract included publications/studies (k) and estimates (not first raw N= match!)
    val includedStudyCount = if (isReviewOrMeta) {
      val studyCountRegex = Regex(
        """\b(?:(\d{1,4})\s+(?:included\s+|eligible\s+|unique\s+)?(?:publications?|studies|articles|reports|papers|trials)\s+(?:were\s+)?(?:included|synthesized|identified\s+for\s+inclusion|met\s+inclusion|analyzed|analysed)|included\s+(\d{1,4})\s+(?:publications?|studies|articles|reports|papers|trials)|k\s*=\s*(\d{1,4}))\b""",
        RegexOption.IGNORE_CASE
      )
      val m = studyCountRegex.find(corpus.methodsText)
        ?: studyCountRegex.find(corpus.abstractText)
        ?: studyCountRegex.find(corpus.resultsText)
        ?: studyCountRegex.find(cleanBody)
      m?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
    } else null

    val includedEstimateCount = if (isReviewOrMeta) {
      val estCountRegex = Regex(
        """\b(\d{1,5})\s+(?:bed[- ]?need\s+|effect\s+|data\s+|independent\s+|extracted\s+)?estimates?\b""",
        RegexOption.IGNORE_CASE
      )
      val m = estCountRegex.find(corpus.resultsText)
        ?: estCountRegex.find(corpus.abstractText)
        ?: estCountRegex.find(corpus.methodsText)
        ?: estCountRegex.find(cleanBody)
      m?.groupValues?.getOrNull(1)?.toIntOrNull()
    } else null

    val geographicScope = extractRankedCandidate(
      corpus = corpus,
      fullRawText = text,
      regex = Regex(
        """\b(?:global|worldwide|international|\d+\s+countries|high[- ]income\s+countries|low[- ]and\s+middle[- ]income\s+countries|oecd\s+countries|europe(?:an)?|north\s+america|germany|united\s+kingdom|united\s+states)\b""",
        RegexOption.IGNORE_CASE
      )
    ).value

    val reviewCard = if (isReviewOrMeta) {
      ReviewIdentityCard(
        designType = designLabel,
        synthesisUnit = unitOfAnalysis.value,
        includedStudyCount = includedStudyCount,
        includedEstimateCount = includedEstimateCount,
        outcomeMetric = outExtracted.value,
        populationOrSetting = popExtracted.value,
        geographicScope = geographicScope,
        timeWindow = horizonExtracted.value,
        synthesisMethod = analysisExtracted.value,
        estimand = estimand.value,
        interventionOrExposure = if (expExtracted.applicability == FieldApplicability.NOT_APPLICABLE) null else expExtracted.value,
        comparator = if (compExtracted.applicability == FieldApplicability.NOT_APPLICABLE) null else compExtracted.value,
        interventionApplicability = expExtracted.applicability,
        comparatorApplicability = compExtracted.applicability
      )
    } else null

    val denomField = if (isReviewOrMeta && (includedStudyCount != null || includedEstimateCount != null)) {
      val parts = buildList {
        if (includedStudyCount != null) add("$includedStudyCount publications/studies (k = $includedStudyCount)")
        if (includedEstimateCount != null) add("$includedEstimateCount extracted estimates")
      }
      IdentityField(
        value = parts.joinToString("; "),
        status = ReportingStatus.REPORTED_AND_ADEQUATE
      )
    } else if (ledger.entries.isNotEmpty()) {
      val summary = ledger.entries.groupBy { it.category }.entries.take(4)
        .joinToString("; ") { (cat, list) -> "${cat.label}: N=${list.maxOf { it.n }}" }
      IdentityField(
        value = summary,
        status = if (ledger.discrepancies.any { it.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION })
          ReportingStatus.REPORTED_BUT_INADEQUATE else ReportingStatus.REPORTED_AND_ADEQUATE
      )
    } else if (subDesign == AuditSubDesign.FORMAL_THEOREM) {
      IdentityField("Not applicable (deductive theorem proof)", ReportingStatus.REPORTED_AND_ADEQUATE, applicability = FieldApplicability.NOT_APPLICABLE)
    } else if (coverage != null && coverage.omittedPages.isNotEmpty()) {
      IdentityField("Denominator not reconstructable from partial extract", ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL)
    } else {
      IdentityField("Analytical sample size / denominator not extracted", ReportingStatus.NOT_REPORTED)
    }

    val uncertain = buildList {
      if (designField.status != ReportingStatus.REPORTED_AND_ADEQUATE) add("Study design")
      if (unitOfAnalysis.status != ReportingStatus.REPORTED_AND_ADEQUATE) add("Unit of analysis")
      if (population.status == ReportingStatus.NOT_REPORTED || population.status == ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL) add("Population")
      if (exposure.applicability != FieldApplicability.NOT_APPLICABLE && exposure.status == ReportingStatus.NOT_REPORTED) add("Exposure/Intervention")
      if (comparator.applicability != FieldApplicability.NOT_APPLICABLE && comparator.status == ReportingStatus.NOT_REPORTED) add("Comparator")
      if (outcome.status == ReportingStatus.NOT_REPORTED) add("Primary outcome")
      if (timeHorizon.status == ReportingStatus.NOT_REPORTED) add("Time horizon")
      if (estimand.status == ReportingStatus.NOT_REPORTED) add("Primary estimand")
      if (primaryAnalysis.status == ReportingStatus.NOT_REPORTED) add("Primary analysis")
      if (denomField.applicability != FieldApplicability.NOT_APPLICABLE &&
        (denomField.status == ReportingStatus.NOT_REPORTED || denomField.status == ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL)
      ) add("Sample size / denominator")
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
      uncertainFields = uncertain,
      reviewIdentityCard = reviewCard,
      extractedFields = extractedMap
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
    val activeBody = SemanticValidityEngine.extractActiveDesignClassificationText(text)
    val activeL = activeBody.lowercase(Locale.US)
    val isCrossSectional = activeL.contains("cross-sectional") || activeL.contains("cross sectional") || activeL.contains("prevalence survey")
    val hasCausalIdentification = activeL.contains("randomized") || activeL.contains("randomised") ||
      activeL.contains("instrumental variable") || activeL.contains("2sls") || activeL.contains("regression discontinuity") ||
      activeL.contains("difference-in-differences") || activeL.contains("target trial emulation") || activeL.contains("mendelian randomization")

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

    val policyRegex = Regex("""(?:clinicians?\s+should|we\s+recommend\s+(?:routine|initiating|adopting|implementing)|policy\s*makers?\s+should|should\s+be\s+(?:adopted|prescribed|implemented)\s+as\s+standard|mandates?\s+(?:immediate|universal|routine|widespread)?\s*(?:clinical|policy|practice)(?:\s+policy)?\s+(?:change|adoption|implementation))""", RegexOption.IGNORE_CASE)
    val causalRegex = Regex("""(?:\bproves?\s+that\b|\bcauses?\s+(?:a|an|the|higher|lower|increased|decreased)\b|\bdirectly\s+reduces?\b|\bprevents?\s+(?:mortality|death|stroke|disease)\b|\beffectively\s+treats?\b|\bcausal\s+effect\s+(?:of|on)\b)""", RegexOption.IGNORE_CASE)
    val mechanisticRegex = Regex("""(?:\bmediates?\s+the\s+pathway\b|\bmechanism\s+by\s+which\b|\bdrives?\s+the\s+biological\b)""", RegexOption.IGNORE_CASE)
    val predictiveRegex = Regex("""(?:\bpredicts?\b|\bdiscriminates?\b|\bauroc\b|\bforecast(?:s|ing)?\b)""", RegexOption.IGNORE_CASE)
    val assocRegex = Regex("""(?:\bassociated\s+with\b|\bcorrelated\s+with\b|\bcorrelation\b|\bodds\s+ratio\b|\brisk\s+ratio\b|\bhazard\s+ratio\b)""", RegexOption.IGNORE_CASE)

    val policyHits = policyRegex.findAll(activeBody).map { it.value.trim() }.toList()
    val causalHits = causalRegex.findAll(activeBody).map { it.value.trim() }.toList()
    val mechHits = mechanisticRegex.findAll(activeBody).map { it.value.trim() }.toList()

    // Exclude negated causal caveats ("cannot prove causality", "does not establish a causal effect")
    val unhedgedCausalHits = causalHits.filter { hit ->
      val idx = activeBody.indexOf(hit)
      val prefix = if (idx > 0) activeBody.substring((idx - 35).coerceAtLeast(0), idx).lowercase(Locale.US) else ""
      !prefix.contains("cannot") && !prefix.contains("not ") && !prefix.contains("unable to") && !prefix.contains("does not") && !prefix.contains("no ")
    }

    val claimedLevel = when {
      policyHits.isNotEmpty() -> ClaimLevel.POLICY_OR_CLINICAL_RECOMMENDATION
      mechHits.isNotEmpty() -> ClaimLevel.MECHANISTIC
      unhedgedCausalHits.isNotEmpty() -> ClaimLevel.CAUSAL
      predictiveRegex.containsMatchIn(activeBody) -> ClaimLevel.PREDICTIVE
      assocRegex.containsMatchIn(activeBody) -> ClaimLevel.ASSOCIATIONAL
      else -> ClaimLevel.DESCRIPTIVE
    }

    val isOverclaimed = claimedLevel.level > maxSupported.level
    val quotes = (policyHits + unhedgedCausalHits + mechHits).take(3)
    val severity = when {
      !isOverclaimed -> FlawSeverityTier.WORTHWHILE_ENHANCEMENT
      isCrossSectional && claimedLevel.level >= ClaimLevel.CAUSAL.level -> FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION
      !hasCausalIdentification && maxSupported.level <= ClaimLevel.ASSOCIATIONAL.level && claimedLevel == ClaimLevel.POLICY_OR_CLINICAL_RECOMMENDATION ->
        FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION
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
    val cleanLower = buildCleanIdentityCorpus(text).cleanBodyText.lowercase(Locale.US)
    val isNormativeReview = isDescriptiveOrNormativeSynthesis(statAudit.subDesign, cleanLower)
    val active = statAudit.claims.filter { !it.isFromReferenceOrCitation }
    val hasSig = active.any { it.pValue != null && it.pValue < it.alphaThreshold }
    val hasNonSig = active.any { it.pValue != null && it.pValue >= it.alphaThreshold }
    val maxN = statAudit.sampleFlowRecords.maxOfOrNull { it.n } ?: 0

    if (isNormativeReview) {
      val hasPI = cleanLower.contains("prediction interval")
      val hasUnitNote = cleanLower.contains("per 100,000") || cleanLower.contains("per 100 000") || cleanLower.contains("harmoniz") || cleanLower.contains("standardiz")
      return PracticalSignificanceAssessment(
        statisticalSignificanceStatus = "Normative / descriptive synthesis (pooled capacity/prevalence estimand)",
        effectMagnitudeNote = "Interpret pooled estimates in policy/service-capacity units rather than clinical treatment effect sizes.",
        precisionNote = if (hasPI) "Prediction interval reported alongside pooled confidence interval." else "Report 95% prediction intervals alongside pooled CIs to convey cross-setting dispersion.",
        minimalImportantDifferenceStatus = ReportingStatus.REPORTED_AND_ADEQUATE,
        absoluteAndBaselineRiskStatus = ReportingStatus.REPORTED_AND_ADEQUATE,
        tinyButSignificantFlag = false,
        wideNonSignificantSpunAsNoEffectFlag = false,
        clinicalOrPolicyUtilitySummary = "Clinical MID, baseline risk, ARR/NNT, and clinical benefit-risk are not applicable to this review's estimand. Evaluate policy utility via prediction intervals, unit harmonization, geographic transportability, and need-versus-utilization distinction.",
        practicalImportanceStatus = if (hasPI && hasUnitNote) MethodologicalAssessmentStatus.DOCUMENTED_AND_ADEQUATE else MethodologicalAssessmentStatus.NOT_ASSESSABLE,
        requiredContextNote = "Applicable normative dimensions: prediction interval, unit harmonization, geographic transportability, and need-versus-utilization distinction. Clinical MID/ARR/NNT are not applicable to this review's estimand.",
        isNormativeOrServiceCapacityReview = true,
        midApplicability = CheckApplicability.NOT_APPLICABLE,
        baselineRiskApplicability = CheckApplicability.NOT_APPLICABLE,
        arrNntApplicability = CheckApplicability.NOT_APPLICABLE,
        clinicalBenefitRiskApplicability = CheckApplicability.NOT_APPLICABLE,
        predictionIntervalApplicability = CheckApplicability.APPLICABLE,
        unitHarmonizationApplicability = CheckApplicability.APPLICABLE,
        geographicTransportabilityApplicability = CheckApplicability.APPLICABLE,
        needVsUtilizationApplicability = CheckApplicability.APPLICABLE
      )
    }

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

    val hasMid = l.contains("minimal important difference") || l.contains("minimally important") || l.contains("mcid") || l.contains("clinical significance threshold") || Regex("""\bmid\b""").containsMatchIn(l)
    val hasAbsRisk = l.contains("absolute risk") || l.contains("arr") || l.contains("nnt") || l.contains("baseline risk") || l.contains("risk difference") || statAudit.twoByTwoTables.isNotEmpty()
    val hasHarmsOrCost = l.contains("adverse event") || l.contains("harms") || l.contains("cost-effectiveness") || l.contains("number needed to harm") || l.contains("nnh")

    val practicalImportanceStatus = when {
      wideNonSigSpun -> MethodologicalAssessmentStatus.DOCUMENTED_BUT_INADEQUATE
      tinyButSig && hasMid && hasAbsRisk -> MethodologicalAssessmentStatus.DOCUMENTED_BUT_INADEQUATE
      tinyButSig -> MethodologicalAssessmentStatus.NOT_ASSESSABLE
      !hasAbsRisk && active.any { it.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR") } ->
        MethodologicalAssessmentStatus.NOT_ASSESSABLE
      hasMid && hasAbsRisk -> MethodologicalAssessmentStatus.DOCUMENTED_AND_ADEQUATE
      else -> MethodologicalAssessmentStatus.NOT_REPORTED
    }

    val requiredContextBase = "Baseline risk / outcome prevalence, time horizon, absolute risk difference (ARR/NNT), domain Minimal Important Difference (MID), and harms/resource context" +
      if (!hasHarmsOrCost) " (harms/cost balance not reported)." else "."
    val requiredContext = if (tinyButSig && !(hasMid && hasAbsRisk)) {
      "Small relative effect detected; absolute and domain-specific importance cannot be assessed without baseline risk/MID. Required context: $requiredContextBase"
    } else {
      requiredContextBase
    }

    val utilitySummary = when {
      tinyButSig && !(hasMid && hasAbsRisk) ->
        "Small relative effect detected; absolute and domain-specific importance cannot be assessed without baseline risk/MID (${tinyEffectClaim?.sourceQuote ?: "near-null estimate"}, N=$maxN)."
      tinyButSig ->
        "Small relative effect detected (${tinyEffectClaim?.sourceQuote ?: "near-null estimate"}, N=$maxN); evaluate reported absolute difference against explicit Minimal Important Difference (MID) and harms/costs."
      wideNonSigSpun ->
        "FLAG (Absence of Evidence ≠ Evidence of Absence): Wide non-significant CI (${wideNonSigClaim?.sourceQuote}) is compatible with both substantial benefit and harm; claiming 'no effect' or equivalence is unjustified."
      !hasAbsRisk && active.any { it.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR") } ->
        "Relative ratio metrics reported without baseline/absolute risk (ARR/NNT) or MID; clinical/policy importance is NOT ASSESSABLE without baseline risk/MID."
      else -> "Statistical significance, effect magnitude, and precision evaluated; verify against domain-specific Minimal Important Difference."
    }

    return PracticalSignificanceAssessment(
      statisticalSignificanceStatus = when {
        hasSig && hasNonSig -> "Mixed significant and non-significant findings"
        hasSig -> "Statistically significant primary/reported claim(s)"
        hasNonSig -> "Non-significant reported claim(s)"
        else -> "Not assessable from supplied material"
      },
      effectMagnitudeNote = tinyEffectClaim?.let { "Small relative magnitude detected (${it.sourceQuote}); requires baseline risk & MID for practical interpretation" }
        ?: "Verify practical magnitude against domain benchmarks",
      precisionNote = wideNonSigClaim?.let { "Wide CI (${it.sourceQuote}) spans clinically important benefit and harm" } ?: "Check CI width against MID bounds",
      minimalImportantDifferenceStatus = if (hasMid) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
      absoluteAndBaselineRiskStatus = if (hasAbsRisk) ReportingStatus.REPORTED_AND_ADEQUATE else ReportingStatus.NOT_REPORTED,
      tinyButSignificantFlag = tinyButSig,
      wideNonSignificantSpunAsNoEffectFlag = wideNonSigSpun,
      clinicalOrPolicyUtilitySummary = utilitySummary,
      practicalImportanceStatus = practicalImportanceStatus,
      requiredContextNote = requiredContext,
      isNormativeOrServiceCapacityReview = false
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
    extractionConfidence: Int,
    coverageStatus: CoverageStatus
  ): StopAndAskAssessment {
    val triggers = mutableListOf<StopAndAskTrigger>()
    val isUnknownCoverage = coverageStatus == CoverageStatus.UNKNOWN ||
      extractionConfidence == DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN

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
      ((extractionConfidence in 1..2) || text.length < 350 || triggers.isNotEmpty())
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
    val hasSevereExtractionGap = (extractionConfidence in 1..2) ||
      triggers.any { it.triggerCode in setOf("MISSING_PAGES", "IMAGE_ONLY_TABLES", "TRUNCATED_EQUATIONS") }
    val shouldStop = (hasSevereExtractionGap && (triggers.size >= 2 || extractionConfidence == 1)) ||
      (!hasVerifiedDeterministicEvidence && triggers.size >= 3)
    val strongConclusionsDisabled = shouldStop || isUnknownCoverage

    val banner = when {
      shouldStop ->
        "INSUFFICIENT EVIDENCE FOR ASSESSMENT (${triggers.size} completeness trigger(s)): Strong methodological or statistical rulings are suspended to prevent filling missing evidence with assumptions."
      isUnknownCoverage ->
        "COVERAGE UNKNOWN: Document coverage metadata was not supplied; strong methodological conclusions are disabled until coverage is verified."
      else ->
        "Completeness gate passed (${triggers.size} minor caveat(s)); sufficient supplied evidence for structured appraisal."
    }

    return StopAndAskAssessment(
      shouldStopAndAsk = shouldStop,
      verdictOverride = if (shouldStop) "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT" else null,
      triggers = triggers,
      disclosureBanner = banner,
      coverageStatus = coverageStatus,
      strongConclusionsDisabled = strongConclusionsDisabled
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
    coverageStatus: CoverageStatus,
    stopAndAsk: StopAndAskAssessment
  ): UncertaintyBudget {
    val active = statAudit.claims.filter { !it.isFromReferenceOrCitation }
    val hasCIs = active.any { it.ciLower != null && it.ciUpper != null }
    val hasModelFail = statAudit.verifiedModelMismatchCount > 0
    val hasMissingFail = ledger.discrepancies.any { it.discrepancyType in setOf("IMPOSSIBLE_ATTRITION", "TABLE_DENOMINATOR_DRIFT", "PROTOCOL_POPULATION_MISMATCH") }
    val hasProtocol = l.contains("preregist") || l.contains("pre-regist") || l.contains("protocol") || l.contains("prospero") || l.contains("nct")
    val isUnknownCoverage = coverageStatus == CoverageStatus.UNKNOWN ||
      extractionConfidence == DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN

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
          isUnknownCoverage -> "UNASSESSABLE"
          stopAndAsk.shouldStopAndAsk || extractionConfidence in 1..2 -> "HIGH"
          extractionConfidence == 3 || statAudit.unsupportedDisclosures.isNotEmpty() || (coverage?.omittedPages?.isNotEmpty() == true) -> "MODERATE"
          else -> "LOW"
        },
        reportingStatus = if (isUnknownCoverage || stopAndAsk.shouldStopAndAsk) ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL else ReportingStatus.REPORTED_AND_ADEQUATE,
        note = if (isUnknownCoverage) {
          "Coverage UNKNOWN; extraction confidence UNKNOWN — strong conclusions disabled."
        } else {
          "Extraction confidence $extractionConfidence/5 (${coverageStatus.label}); ${statAudit.unsupportedDisclosures.size} unsupported-input disclosure(s)."
        }
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
