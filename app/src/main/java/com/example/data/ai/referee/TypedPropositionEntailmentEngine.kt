package com.example.data.ai.referee

import com.example.data.ai.Reviewer2PipelineService
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 1 & Phase 4 Canonical Typed Scientific Proposition Extractor & Entailment Gate.
 * Strictly prohibits token/lexical overlap from acting as proof of entailment.
 */
object TypedPropositionEntailmentEngine {

  private val BAYESIAN_CUE_REGEX = Regex(
    """(?i)\b(?:bayesian|credible\s+interval|\bcri\b|posterior\s+(?:interval|distribution|mean|median|odds|hazard|risk)|mcmc)\b"""
  )
  private val FREQUENTIST_CI_CUE_REGEX = Regex(
    """(?i)\b(?:\d+(?:\.\d+)?%\s*(?:ci\b|confidence\s+interval)|confidence\s+interval|wald\b|frequentist|asymptotic\s+normal)\b"""
  )

  fun extractProposition(
    id: String,
    text: String,
    sourceAnchorIds: List<String> = emptyList()
  ): ScientificProposition {
    val clean = text.replace(Regex("""\[[A-Z]-\d{1,3}\]"""), " ").trim()
    val parsed = Reviewer2PipelineService.extractScientificProposition(clean)

    val population = when {
      parsed.populations.isEmpty() -> null
      parsed.populations.size == 1 -> parsed.populations.first()
      else -> parsed.populations.sorted().joinToString("+")
    }
    val intervention = parsed.interventions.firstOrNull()
    val comparator = parsed.comparatorPairs.firstOrNull()?.second
      ?: parsed.namedComparators.firstOrNull()
      ?: parsed.comparators.firstOrNull()
    val outcome = parsed.outcomes.firstOrNull()
    val effectMeasure = parsed.effectMeasures.firstOrNull()
    val estimate = parsed.effectEstimates.firstOrNull() ?: parsed.percentages.firstOrNull()
    val interval = parsed.intervals.firstOrNull()
    val pValue = parsed.pValues.firstOrNull()

    val timePoint = Regex("""(?i)\b(\d+(?:\.\d+)?\s*[-]?\s*(?:hours?|hrs?|days?|weeks?|wks?|months?|mos?|years?|yrs?))\b""")
      .find(clean)?.groupValues?.get(1)?.lowercase(Locale.US)?.replace(Regex("""\s+"""), " ")

    val analysisPop = when (parsed.subgroupScope) {
      "SUBGROUP" -> "SUBGROUP_OR_PER_PROTOCOL"
      "OVERALL" -> "OVERALL_ITT"
      else -> null
    }
    val inferenceParadigm = when {
      BAYESIAN_CUE_REGEX.containsMatchIn(clean) && !FREQUENTIST_CI_CUE_REGEX.containsMatchIn(clean) -> "BAYESIAN_CRI"
      FREQUENTIST_CI_CUE_REGEX.containsMatchIn(clean) && !BAYESIAN_CUE_REGEX.containsMatchIn(clean) -> "FREQUENTIST_CI"
      else -> null
    }
    val estimand = listOfNotNull(
      effectMeasure,
      parsed.scaleTransform,
      inferenceParadigm,
      parsed.adjustmentStatus
    ).takeIf { it.isNotEmpty() }?.joinToString("|")

    val direction = when (parsed.direction) {
      1 -> "INCREASE"
      -1 -> "DECREASE"
      else -> null
    }
    val polarity = when (parsed.polarity.name) {
      "ABSENT_OR_DEFICIENT" -> "NEGATIVE_ABSENT"
      "PRESENT_OR_ADEQUATE" -> "POSITIVE_PRESENT"
      else -> "NEUTRAL"
    }

    val unresolvedFields = buildList {
      if (population == null) add("population")
      if (intervention == null) add("interventionOrExposure")
      if (comparator == null) add("comparator")
      if (outcome == null) add("outcome")
      if (effectMeasure == null) add("effectMeasure")
      if (estimate == null) add("estimate")
      if (interval == null) {
        add("lowerBound")
        add("upperBound")
      }
      if (parsed.ciLevelPct == null) add("confidenceLevel")
      if (pValue == null) add("pValue")
      if (timePoint == null) add("timePoint")
      if (analysisPop == null) add("analysisPopulation")
      if (parsed.adjustmentStatus == null) add("adjustmentStatus")
      if (direction == null) add("direction")
    }

    val populatedCoreCount = listOf(
      population != null,
      intervention != null || comparator != null,
      outcome != null,
      effectMeasure != null || estimate != null,
      interval != null || pValue != null,
      timePoint != null || parsed.adjustmentStatus != null
    ).count { it }
    val confidence = when {
      clean.length < 12 -> 1
      populatedCoreCount >= 5 -> 5
      populatedCoreCount >= 3 -> 4
      populatedCoreCount >= 2 -> 3
      populatedCoreCount == 1 -> 2
      else -> 1
    }

    return ScientificProposition(
      id = id,
      population = population,
      interventionOrExposure = intervention,
      comparator = comparator,
      outcome = outcome,
      estimand = estimand,
      effectMeasure = effectMeasure,
      estimate = estimate,
      lowerBound = interval?.first,
      upperBound = interval?.second,
      confidenceLevel = parsed.ciLevelPct,
      pValue = pValue,
      timePoint = timePoint,
      analysisPopulation = analysisPop,
      adjustmentStatus = parsed.adjustmentStatus,
      direction = direction,
      polarity = polarity,
      sourceAnchorIds = sourceAnchorIds,
      extractionConfidence = confidence,
      unresolvedFields = unresolvedFields
    )
  }

  fun evaluateEntailment(
    claimText: String,
    matchedQuote: String,
    surroundingWindow: String = matchedQuote,
    exactQuoteMatched: Boolean = true,
    claimDocumentId: String? = null,
    evidenceDocumentId: String? = null,
    findingId: String = "CLAIM-01"
  ): EntailmentAssessment {
    val claimProp = extractProposition("${findingId}_CLAIM", claimText)
    val evidenceCombined = "$matchedQuote $surroundingWindow".trim()
    val evidenceProp = extractProposition("${findingId}_EVIDENCE", evidenceCombined)

    if (!claimDocumentId.isNullOrBlank() && !evidenceDocumentId.isNullOrBlank() && claimDocumentId != evidenceDocumentId) {
      return EntailmentAssessment(
        status = EntailmentStatus.NOT_ENTAILED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = listOf("documentId"),
        unresolvedCriticalFields = emptyList(),
        exactQuoteMatched = exactQuoteMatched,
        reason = "Source document ID mismatch ($claimDocumentId vs $evidenceDocumentId)."
      )
    }

    if (!exactQuoteMatched || matchedQuote.trim().length < 12) {
      return EntailmentAssessment(
        status = EntailmentStatus.NOT_ENTAILED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = listOf("exactQuote"),
        unresolvedCriticalFields = emptyList(),
        exactQuoteMatched = exactQuoteMatched,
        reason = "Exact source quotation is absent; token overlap never establishes entailment."
      )
    }

    val mismatched = mutableListOf<String>()
    val unresolvedCritical = mutableListOf<String>()

    // 1. Explicit Bayesian CrI vs Frequentist CI paradigm check
    val claimBayesian = BAYESIAN_CUE_REGEX.containsMatchIn(claimText)
    val claimFrequentist = FREQUENTIST_CI_CUE_REGEX.containsMatchIn(claimText) && !claimBayesian
    val evBayesian = BAYESIAN_CUE_REGEX.containsMatchIn(evidenceCombined)
    val evFrequentist = FREQUENTIST_CI_CUE_REGEX.containsMatchIn(evidenceCombined) && !evBayesian
    if ((claimBayesian && evFrequentist) || (claimFrequentist && evBayesian)) {
      mismatched.add("estimand/intervalParadigm (Bayesian CrI vs Frequentist CI)")
    }

    // 2. Confidence level check (e.g., 90% CI vs 95% CI)
    if (claimProp.confidenceLevel != null && evidenceProp.confidenceLevel != null &&
      abs(claimProp.confidenceLevel - evidenceProp.confidenceLevel) > 0.1
    ) {
      mismatched.add("confidenceLevel (${claimProp.confidenceLevel}% vs ${evidenceProp.confidenceLevel}%)")
    }

    // 3. Polarity & Direction checks
    val legacyFinding = com.example.data.model.ReviewFinding(
      id = findingId,
      title = claimText.take(80),
      allegation = claimText,
      severity = "MAJOR",
      status = com.example.data.model.FindingStatus.VERIFIED
    )
    val legacyStatus = Reviewer2PipelineService.evaluateQuoteEntailment(
      finding = legacyFinding,
      matchedQuote = matchedQuote,
      surroundingWindow = surroundingWindow
    )

    // Extract detailed field mismatches from canonical typed properties
    if (claimProp.population != null && evidenceProp.population != null && claimProp.population != evidenceProp.population) {
      mismatched.add("population (${claimProp.population} vs ${evidenceProp.population})")
    } else if (claimProp.population != null && evidenceProp.population == null) {
      unresolvedCritical.add("population")
    }
    if (claimProp.adjustmentStatus != null && evidenceProp.adjustmentStatus != null && claimProp.adjustmentStatus != evidenceProp.adjustmentStatus) {
      mismatched.add("adjustmentStatus (${claimProp.adjustmentStatus} vs ${evidenceProp.adjustmentStatus})")
    } else if (claimProp.adjustmentStatus != null && evidenceProp.adjustmentStatus == null) {
      unresolvedCritical.add("adjustmentStatus")
    }
    if (claimProp.analysisPopulation != null && evidenceProp.analysisPopulation != null && claimProp.analysisPopulation != evidenceProp.analysisPopulation) {
      mismatched.add("analysisPopulation (${claimProp.analysisPopulation} vs ${evidenceProp.analysisPopulation})")
    }
    if (claimProp.outcome != null && evidenceProp.outcome != null && claimProp.outcome != evidenceProp.outcome) {
      mismatched.add("outcome (${claimProp.outcome} vs ${evidenceProp.outcome})")
    }
    if (claimProp.effectMeasure != null && evidenceProp.effectMeasure != null && claimProp.effectMeasure != evidenceProp.effectMeasure) {
      mismatched.add("effectMeasure (${claimProp.effectMeasure} vs ${evidenceProp.effectMeasure})")
    }
    if (claimProp.estimate != null && evidenceProp.estimate != null) {
      val tol = maxOf(0.03, 0.05 * abs(evidenceProp.estimate))
      if (abs(claimProp.estimate - evidenceProp.estimate) > tol) {
        mismatched.add("estimate (${claimProp.estimate} vs ${evidenceProp.estimate})")
      }
    }

    if (legacyStatus == Reviewer2PipelineService.Companion.EntailmentStatus.CONTRADICTED_BY_POLARITY) {
      if (claimProp.direction != null && evidenceProp.direction != null && claimProp.direction != evidenceProp.direction) {
        mismatched.add("direction (${claimProp.direction} vs ${evidenceProp.direction})")
      }
      if (claimProp.polarity != evidenceProp.polarity) {
        mismatched.add("polarity (${claimProp.polarity} vs ${evidenceProp.polarity})")
      }
      return EntailmentAssessment(
        status = EntailmentStatus.CONTRADICTED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = mismatched.ifEmpty { listOf("polarityOrDirection") }.distinct(),
        unresolvedCriticalFields = unresolvedCritical.distinct(),
        exactQuoteMatched = true,
        reason = "Matched evidence directly contradicts claim polarity or effect direction."
      )
    }

    if (mismatched.isNotEmpty() || legacyStatus == Reviewer2PipelineService.Companion.EntailmentStatus.NOT_ENTAILED) {
      return EntailmentAssessment(
        status = EntailmentStatus.NOT_ENTAILED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = mismatched.ifEmpty { listOf("propositionCompatibility") }.distinct(),
        unresolvedCriticalFields = unresolvedCritical.distinct(),
        exactQuoteMatched = true,
        reason = "Typed proposition mismatch: ${(mismatched.ifEmpty { listOf("critical claim field incompatibility") }).joinToString(", ")}."
      )
    }

    if (legacyStatus == Reviewer2PipelineService.Companion.EntailmentStatus.UNRESOLVED || unresolvedCritical.isNotEmpty()) {
      return EntailmentAssessment(
        status = EntailmentStatus.UNRESOLVED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = emptyList(),
        unresolvedCriticalFields = unresolvedCritical.distinct(),
        exactQuoteMatched = true,
        reason = "Evidence lacks critical claim fields: ${unresolvedCritical.joinToString(", ").ifBlank { "required proposition context" }}."
      )
    }

    if (legacyStatus == Reviewer2PipelineService.Companion.EntailmentStatus.PARTIAL_OR_AMBIGUOUS) {
      return EntailmentAssessment(
        status = EntailmentStatus.PARTIALLY_SUPPORTED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = emptyList(),
        unresolvedCriticalFields = unresolvedCritical.distinct(),
        exactQuoteMatched = true,
        reason = "Quote partially supports claim context without complete entailment."
      )
    }

    return EntailmentAssessment(
      status = EntailmentStatus.ENTAILED,
      claimProposition = claimProp,
      evidenceProposition = evidenceProp,
      mismatchedFields = emptyList(),
      unresolvedCriticalFields = emptyList(),
      exactQuoteMatched = true,
      reason = "Exact quotation and typed scientific proposition fields agree."
    )
  }
}
