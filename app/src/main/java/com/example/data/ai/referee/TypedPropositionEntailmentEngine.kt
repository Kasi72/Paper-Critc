package com.example.data.ai.referee

import com.example.data.ai.Reviewer2PipelineService
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 1 & Phase 4 Canonical Typed Scientific Proposition Extractor & Clause-Isolated Entailment Gate.
 * Strictly prohibits token/lexical overlap or cross-clause fact merging from acting as proof of entailment.
 */
object TypedPropositionEntailmentEngine {

  private val BAYESIAN_CUE_REGEX = Regex(
    """(?i)\b(?:bayesian|credible\s+interval|\bcri\b|posterior\s+(?:interval|distribution|mean|median|odds|hazard|risk)|mcmc)\b"""
  )
  private val FREQUENTIST_CI_CUE_REGEX = Regex(
    """(?i)\b(?:\d+(?:\.\d+)?%\s*(?:ci\b|confidence\s+interval)|confidence\s+interval|wald\b|frequentist|asymptotic\s+normal)\b"""
  )
  private val TIMEPOINT_REGEX = Regex(
    """(?i)\b(\d+(?:\.\d+)?)\s*[-]?\s*(hours?|hrs?|days?|weeks?|wks?|months?|mos?|years?|yrs?)\b"""
  )
  private val DISCREPANCY_CLAIM_REGEX = Regex(
    """(?i)\b(?:discrepancy|contradict\w*|mismatch|inconsisten\w*|recomputed|does\s+not\s+match|differs?\s+from|impossible\s+ci|arithmetic\s+error)\b"""
  )
  private val CLAUSE_CONJUNCTION_SPLIT_REGEX = Regex(
    """(?i)(?:,\s*|\s+)(?=(?:whereas|however|conversely|in\s+contrast|on\s+the\s+other\s+hand|while\s+in)\b)"""
  )
  private val ABBR_ENDINGS = listOf("vs.", "fig.", "et al.", "e.g.", "i.e.", "no.", "dr.", "st.", "approx.", "mr.", "ms.", "prof.")

  /**
   * Splits multi-sentence / multi-clause text into isolated scientific clauses without breaking
   * decimal numbers (e.g. 0.85, p = 0.004) or abbreviations ("vs.", "et al.", "Fig.").
   */
  fun splitIntoClauses(text: String): List<String> {
    val normalized = text.replace(Regex("""\[[A-Z]-\d{1,3}\]"""), " ").trim()
    if (normalized.isBlank()) return emptyList()

    val rawPieces = mutableListOf<String>()
    val current = StringBuilder()
    var parenDepth = 0
    for (i in normalized.indices) {
      val ch = normalized[i]
      if (ch == '(' || ch == '[') parenDepth++
      else if ((ch == ')' || ch == ']') && parenDepth > 0) parenDepth--

      current.append(ch)
      val nextCh = normalized.getOrNull(i + 1)
      val prevCh = normalized.getOrNull(i - 1)
      val isSentenceEnd = when {
        ch == '\n' || ch == ';' -> true
        (ch == '.' || ch == '!' || ch == '?') && parenDepth == 0 -> {
          val decimalBoundary = ch == '.' && prevCh?.isDigit() == true && nextCh?.isDigit() == true
          val followedByBreak = nextCh == null || nextCh.isWhitespace()
          if (decimalBoundary || !followedByBreak) {
            false
          } else {
            val tail = current.toString().lowercase(Locale.US)
            ABBR_ENDINGS.none { abbr -> tail.endsWith(abbr) }
          }
        }
        else -> false
      }
      if (isSentenceEnd) {
        val piece = current.toString().trim().trimEnd(';')
        if (piece.isNotBlank()) rawPieces.add(piece)
        current.clear()
      }
    }
    val remainder = current.toString().trim().trimEnd(';')
    if (remainder.isNotBlank()) rawPieces.add(remainder)

    return rawPieces
      .flatMap { piece ->
        if (piece.contains("(") && piece.contains(")")) listOf(piece)
        else piece.split(CLAUSE_CONJUNCTION_SPLIT_REGEX)
      }
      .map { it.replace(Regex("""\s+"""), " ").trim() }
      .filter { it.isNotBlank() }
      .ifEmpty { listOf(normalized) }
  }

  fun extractClausePropositions(
    idPrefix: String,
    text: String,
    sourceAnchorIds: List<String> = emptyList()
  ): List<Pair<String, ScientificProposition>> {
    val clauses = splitIntoClauses(text)
    if (clauses.isEmpty()) return emptyList()
    return clauses.mapIndexed { idx, clause ->
      clause to extractProposition("${idPrefix}_C${idx + 1}", clause, sourceAnchorIds)
    }
  }

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
    val intervention = parsed.comparatorPairs.firstOrNull()?.first
      ?: parsed.interventions.firstOrNull()
    val comparator = parsed.comparatorPairs.firstOrNull()?.second
      ?: parsed.namedComparators.firstOrNull()
      ?: parsed.comparators.firstOrNull()
    val outcome = parsed.outcomes.sorted().takeIf { it.isNotEmpty() }?.joinToString("+")
    val effectMeasure = parsed.effectMeasures.sorted().takeIf { it.isNotEmpty() }?.joinToString("+")
    val estimate = parsed.effectEstimates.firstOrNull() ?: parsed.percentages.firstOrNull()
    val interval = parsed.intervals.firstOrNull()
    val pValue = parsed.pValues.firstOrNull()

    val timePoint = TIMEPOINT_REGEX.find(clean)?.let {
      "${it.groupValues[1]} ${it.groupValues[2].lowercase(Locale.US)}"
    }

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

  private fun parseTimePointDays(timePoint: String?): Double? {
    if (timePoint.isNullOrBlank()) return null
    val m = TIMEPOINT_REGEX.find(timePoint) ?: return null
    val qty = m.groupValues[1].toDoubleOrNull() ?: return null
    val unit = m.groupValues[2].lowercase(Locale.US)
    return when {
      unit.startsWith("h") -> qty / 24.0
      unit.startsWith("d") -> qty
      unit.startsWith("w") -> qty * 7.0
      unit.startsWith("m") -> qty * 30.0
      unit.startsWith("y") -> qty * 365.0
      else -> null
    }
  }

  private fun armMatches(a: String, b: String): Boolean {
    val normA = a.lowercase(Locale.US).trim()
    val normB = b.lowercase(Locale.US).trim()
    if (normA == normB) return true
    if (normA.length >= 4 && normB.length >= 4 && (normA.contains(normB) || normB.contains(normA))) return true
    return false
  }

  private fun comparatorMatches(cProp: ScientificProposition, eProp: ScientificProposition): Boolean? {
    val cComp = cProp.comparator
    val eComp = eProp.comparator
    if (cComp != null && eComp != null) {
      if (armMatches(cComp, eComp)) return true
      val placeboSyn = setOf("placebo", "sham")
      if (cComp.lowercase(Locale.US) in placeboSyn && eComp.lowercase(Locale.US) in placeboSyn) return true
      return false
    }
    return null
  }

  private fun checkClausePair(
    claimClause: String,
    claimProp: ScientificProposition,
    evidenceClause: String,
    evidenceProp: ScientificProposition
  ): EntailmentAssessment {
    val mismatched = mutableListOf<String>()
    val unresolvedCritical = mutableListOf<String>()
    val isDiscrepancyClaim = DISCREPANCY_CLAIM_REGEX.containsMatchIn(claimClause)

    // 1. Polarity & Direction opposition
    if ((claimProp.polarity == "POSITIVE_PRESENT" && evidenceProp.polarity == "NEGATIVE_ABSENT") ||
      (claimProp.polarity == "NEGATIVE_ABSENT" && evidenceProp.polarity == "POSITIVE_PRESENT")
    ) {
      mismatched.add("polarity (${claimProp.polarity} vs ${evidenceProp.polarity})")
      return EntailmentAssessment(
        status = EntailmentStatus.CONTRADICTED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = mismatched,
        unresolvedCriticalFields = emptyList(),
        exactQuoteMatched = true,
        reason = "Evidence clause polarity contradicts claim polarity."
      )
    }
    if (claimProp.direction != null && evidenceProp.direction != null && claimProp.direction != evidenceProp.direction) {
      mismatched.add("direction (${claimProp.direction} vs ${evidenceProp.direction})")
      return EntailmentAssessment(
        status = EntailmentStatus.CONTRADICTED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = mismatched,
        unresolvedCriticalFields = emptyList(),
        exactQuoteMatched = true,
        reason = "Evidence clause effect direction contradicts claim direction."
      )
    }

    // 2. Explicit Bayesian CrI vs Frequentist CI paradigm check
    val claimBayesian = BAYESIAN_CUE_REGEX.containsMatchIn(claimClause)
    val claimFrequentist = FREQUENTIST_CI_CUE_REGEX.containsMatchIn(claimClause) && !claimBayesian
    val evBayesian = BAYESIAN_CUE_REGEX.containsMatchIn(evidenceClause)
    val evFrequentist = FREQUENTIST_CI_CUE_REGEX.containsMatchIn(evidenceClause) && !evBayesian
    if ((claimBayesian && evFrequentist) || (claimFrequentist && evBayesian)) {
      mismatched.add("estimand/intervalParadigm (Bayesian CrI vs Frequentist CI)")
    }

    // 3. All canonical typed fields checked individually per clause (zero cross-clause merging)
    if (claimProp.population != null && evidenceProp.population != null) {
      val cPops = claimProp.population.split("+").toSet()
      val ePops = evidenceProp.population.split("+").toSet()
      if (cPops.intersect(ePops).isEmpty() || cPops != ePops && !ePops.containsAll(cPops)) {
        mismatched.add("population (${claimProp.population} vs ${evidenceProp.population})")
      }
    } else if (claimProp.population != null && !isDiscrepancyClaim) {
      unresolvedCritical.add("population")
    }

    if (claimProp.interventionOrExposure != null && evidenceProp.interventionOrExposure != null) {
      if (!armMatches(claimProp.interventionOrExposure, evidenceProp.interventionOrExposure)) {
        mismatched.add("interventionOrExposure (${claimProp.interventionOrExposure} vs ${evidenceProp.interventionOrExposure})")
      }
    } else if (claimProp.interventionOrExposure != null && !isDiscrepancyClaim) {
      unresolvedCritical.add("interventionOrExposure")
    }

    val compCmp = comparatorMatches(claimProp, evidenceProp)
    if (compCmp == false) {
      mismatched.add("comparator (${claimProp.comparator} vs ${evidenceProp.comparator})")
    } else if (claimProp.comparator != null && evidenceProp.comparator == null && !isDiscrepancyClaim) {
      unresolvedCritical.add("comparator")
    }

    if (claimProp.outcome != null && evidenceProp.outcome != null) {
      val cOut = claimProp.outcome.split("+").toSet()
      val eOut = evidenceProp.outcome.split("+").toSet()
      if (cOut.intersect(eOut).isEmpty()) {
        mismatched.add("outcome (${claimProp.outcome} vs ${evidenceProp.outcome})")
      }
    } else if (claimProp.outcome != null && !isDiscrepancyClaim) {
      unresolvedCritical.add("outcome")
    }

    if (claimProp.effectMeasure != null && evidenceProp.effectMeasure != null) {
      val cMeas = claimProp.effectMeasure.split("+").toSet()
      val eMeas = evidenceProp.effectMeasure.split("+").toSet()
      if (cMeas.intersect(eMeas).isEmpty()) {
        mismatched.add("effectMeasure (${claimProp.effectMeasure} vs ${evidenceProp.effectMeasure})")
      }
    } else if (claimProp.effectMeasure != null && !isDiscrepancyClaim) {
      unresolvedCritical.add("effectMeasure")
    }

    if (claimProp.estimand != null && evidenceProp.estimand != null) {
      val cEst = claimProp.estimand.split("|").toSet()
      val eEst = evidenceProp.estimand.split("|").toSet()
      if ("BAYESIAN_CRI" in cEst && "FREQUENTIST_CI" in eEst || "FREQUENTIST_CI" in cEst && "BAYESIAN_CRI" in eEst) {
        mismatched.add("estimand (${claimProp.estimand} vs ${evidenceProp.estimand})")
      }
    }

    if (claimProp.estimate != null && evidenceProp.estimate != null) {
      val tol = maxOf(0.03, 0.05 * abs(evidenceProp.estimate))
      if (abs(claimProp.estimate - evidenceProp.estimate) > tol) {
        mismatched.add("estimate (${claimProp.estimate} vs ${evidenceProp.estimate})")
      }
    } else if (claimProp.estimate != null && !isDiscrepancyClaim) {
      unresolvedCritical.add("estimate")
    }

    if (claimProp.lowerBound != null && claimProp.upperBound != null) {
      if (evidenceProp.lowerBound != null && evidenceProp.upperBound != null) {
        val tolLo = maxOf(0.03, 0.05 * abs(evidenceProp.lowerBound))
        val tolHi = maxOf(0.03, 0.05 * abs(evidenceProp.upperBound))
        if (abs(claimProp.lowerBound - evidenceProp.lowerBound) > tolLo) {
          mismatched.add("lowerBound (${claimProp.lowerBound} vs ${evidenceProp.lowerBound})")
        }
        if (abs(claimProp.upperBound - evidenceProp.upperBound) > tolHi) {
          mismatched.add("upperBound (${claimProp.upperBound} vs ${evidenceProp.upperBound})")
        }
      } else if (!isDiscrepancyClaim) {
        unresolvedCritical.add("lowerBound")
        unresolvedCritical.add("upperBound")
      }
    }

    if (claimProp.confidenceLevel != null && evidenceProp.confidenceLevel != null &&
      abs(claimProp.confidenceLevel - evidenceProp.confidenceLevel) > 0.1
    ) {
      mismatched.add("confidenceLevel (${claimProp.confidenceLevel}% vs ${evidenceProp.confidenceLevel}%)")
    } else if (claimProp.confidenceLevel != null && evidenceProp.confidenceLevel == null && !isDiscrepancyClaim) {
      unresolvedCritical.add("confidenceLevel")
    }

    var pSignContradicted = false
    if (claimProp.pValue != null && evidenceProp.pValue != null) {
      val sameSideOfAlpha = (claimProp.pValue < 0.05) == (evidenceProp.pValue < 0.05)
      val tol = maxOf(0.002, 0.25 * maxOf(claimProp.pValue, evidenceProp.pValue))
      if (!sameSideOfAlpha) {
        pSignContradicted = true
        mismatched.add("pValueSignificance (${claimProp.pValue} vs ${evidenceProp.pValue})")
      } else if (abs(claimProp.pValue - evidenceProp.pValue) > tol) {
        mismatched.add("pValue (${claimProp.pValue} vs ${evidenceProp.pValue})")
      }
    } else if (claimProp.pValue != null && evidenceProp.pValue == null && !isDiscrepancyClaim) {
      unresolvedCritical.add("pValue")
    }

    if (claimProp.timePoint != null && evidenceProp.timePoint != null) {
      val cDays = parseTimePointDays(claimProp.timePoint)
      val eDays = parseTimePointDays(evidenceProp.timePoint)
      if (cDays != null && eDays != null) {
        val tol = maxOf(2.0, 0.15 * maxOf(cDays, eDays))
        if (abs(cDays - eDays) > tol) {
          mismatched.add("timePoint (${claimProp.timePoint} vs ${evidenceProp.timePoint})")
        }
      } else if (!claimProp.timePoint.equals(evidenceProp.timePoint, ignoreCase = true)) {
        mismatched.add("timePoint (${claimProp.timePoint} vs ${evidenceProp.timePoint})")
      }
    } else if (claimProp.timePoint != null && evidenceProp.timePoint == null && !isDiscrepancyClaim) {
      unresolvedCritical.add("timePoint")
    }

    if (claimProp.analysisPopulation != null && evidenceProp.analysisPopulation != null &&
      claimProp.analysisPopulation != evidenceProp.analysisPopulation
    ) {
      mismatched.add("analysisPopulation (${claimProp.analysisPopulation} vs ${evidenceProp.analysisPopulation})")
    } else if (claimProp.analysisPopulation != null && evidenceProp.analysisPopulation == null && !isDiscrepancyClaim) {
      unresolvedCritical.add("analysisPopulation")
    }

    if (claimProp.adjustmentStatus != null && evidenceProp.adjustmentStatus != null &&
      claimProp.adjustmentStatus != evidenceProp.adjustmentStatus
    ) {
      mismatched.add("adjustmentStatus (${claimProp.adjustmentStatus} vs ${evidenceProp.adjustmentStatus})")
    } else if (claimProp.adjustmentStatus != null && evidenceProp.adjustmentStatus == null && !isDiscrepancyClaim) {
      unresolvedCritical.add("adjustmentStatus")
    }

    val legacyFinding = com.example.data.model.ReviewFinding(
      id = claimProp.id,
      title = claimClause.take(80),
      allegation = claimClause,
      severity = "MAJOR",
      status = com.example.data.model.FindingStatus.VERIFIED
    )
    val legacyStatus = Reviewer2PipelineService.evaluateQuoteEntailment(
      finding = legacyFinding,
      matchedQuote = evidenceClause,
      surroundingWindow = evidenceClause
    )

    if (pSignContradicted || legacyStatus == Reviewer2PipelineService.Companion.EntailmentStatus.CONTRADICTED_BY_POLARITY) {
      return EntailmentAssessment(
        status = EntailmentStatus.CONTRADICTED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = mismatched.ifEmpty { listOf("polarityOrSignificance") }.distinct(),
        unresolvedCriticalFields = unresolvedCritical.distinct(),
        exactQuoteMatched = true,
        reason = "Matched evidence clause directly contradicts claim polarity, direction, or significance."
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
        reason = "Evidence clause lacks critical claim fields: ${unresolvedCritical.joinToString(", ").ifBlank { "required proposition context" }}."
      )
    }

    if (legacyStatus == Reviewer2PipelineService.Companion.EntailmentStatus.PARTIAL_OR_AMBIGUOUS) {
      return EntailmentAssessment(
        status = EntailmentStatus.PARTIALLY_SUPPORTED,
        claimProposition = claimProp,
        evidenceProposition = evidenceProp,
        mismatchedFields = emptyList(),
        unresolvedCriticalFields = emptyList(),
        exactQuoteMatched = true,
        reason = "Evidence clause partially supports claim context without complete entailment."
      )
    }

    return EntailmentAssessment(
      status = EntailmentStatus.ENTAILED,
      claimProposition = claimProp,
      evidenceProposition = evidenceProp,
      mismatchedFields = emptyList(),
      unresolvedCriticalFields = emptyList(),
      exactQuoteMatched = true,
      reason = "Exact quotation and typed scientific proposition fields agree within a single clause."
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
    val fullClaimProp = extractProposition("${findingId}_CLAIM", claimText)
    val quoteClauses = extractClausePropositions("${findingId}_Q", matchedQuote)
    val windowClauses = if (surroundingWindow != matchedQuote) {
      extractClausePropositions("${findingId}_W", surroundingWindow)
    } else {
      emptyList()
    }
    // Deduplicate while preserving single-clause boundaries (never merge clause 1 + clause 2!)
    val seenEvidence = HashSet<String>()
    val evidenceClauses = (quoteClauses + windowClauses).filter { (txt, _) ->
      seenEvidence.add(txt.lowercase(Locale.US))
    }
    val primaryEvidenceProp = evidenceClauses.firstOrNull()?.second
      ?: extractProposition("${findingId}_EVIDENCE", matchedQuote)

    if (!claimDocumentId.isNullOrBlank() && !evidenceDocumentId.isNullOrBlank() && claimDocumentId != evidenceDocumentId) {
      return EntailmentAssessment(
        status = EntailmentStatus.NOT_ENTAILED,
        claimProposition = fullClaimProp,
        evidenceProposition = primaryEvidenceProp,
        mismatchedFields = listOf("documentId"),
        unresolvedCriticalFields = emptyList(),
        exactQuoteMatched = exactQuoteMatched,
        reason = "Source document ID mismatch ($claimDocumentId vs $evidenceDocumentId)."
      )
    }

    if (!exactQuoteMatched || matchedQuote.trim().length < 12 || evidenceClauses.isEmpty()) {
      return EntailmentAssessment(
        status = EntailmentStatus.NOT_ENTAILED,
        claimProposition = fullClaimProp,
        evidenceProposition = primaryEvidenceProp,
        mismatchedFields = listOf("exactQuote"),
        unresolvedCriticalFields = emptyList(),
        exactQuoteMatched = exactQuoteMatched,
        reason = "Exact source quotation is absent; token overlap never establishes entailment."
      )
    }

    val claimClauses = extractClausePropositions("${findingId}_CLAIM", claimText)
    if (claimClauses.isEmpty()) {
      return EntailmentAssessment(
        status = EntailmentStatus.UNRESOLVED,
        claimProposition = fullClaimProp,
        evidenceProposition = primaryEvidenceProp,
        mismatchedFields = emptyList(),
        unresolvedCriticalFields = fullClaimProp.unresolvedFields,
        exactQuoteMatched = true,
        reason = "Claim text contained no extractable scientific clauses."
      )
    }

    // Evaluate each substantive claim clause independently against each individual evidence clause.
    // A claim clause succeeds ONLY if a SINGLE evidence clause entails all of its fields without borrowing
    // numbers/populations/arms from a different sentence or clause.
    val perClaimBest = claimClauses.map { (cText, cProp) ->
      val assessments = evidenceClauses.map { (eText, eProp) ->
        checkClausePair(cText, cProp, eText, eProp)
      }
      assessments.firstOrNull { it.status == EntailmentStatus.ENTAILED }
        ?: assessments.firstOrNull { it.status == EntailmentStatus.CONTRADICTED }
        ?: assessments.firstOrNull { it.status == EntailmentStatus.PARTIALLY_SUPPORTED }
        ?: assessments.firstOrNull { it.status == EntailmentStatus.UNRESOLVED }
        ?: assessments.first()
    }

    val contradicted = perClaimBest.firstOrNull { it.status == EntailmentStatus.CONTRADICTED }
    if (contradicted != null) {
      return contradicted.copy(claimProposition = fullClaimProp)
    }
    val notEntailed = perClaimBest.firstOrNull { it.status == EntailmentStatus.NOT_ENTAILED }
    if (notEntailed != null) {
      return notEntailed.copy(claimProposition = fullClaimProp)
    }
    val unresolved = perClaimBest.firstOrNull { it.status == EntailmentStatus.UNRESOLVED }
    if (unresolved != null) {
      return unresolved.copy(claimProposition = fullClaimProp)
    }
    val partial = perClaimBest.firstOrNull { it.status == EntailmentStatus.PARTIALLY_SUPPORTED }
    if (partial != null) {
      return partial.copy(claimProposition = fullClaimProp)
    }

    return EntailmentAssessment(
      status = EntailmentStatus.ENTAILED,
      claimProposition = fullClaimProp,
      evidenceProposition = perClaimBest.first().evidenceProposition,
      mismatchedFields = emptyList(),
      unresolvedCriticalFields = emptyList(),
      exactQuoteMatched = true,
      reason = "All ${claimClauses.size} claim clause(s) verified by matching single evidence clause(s) without cross-clause merging."
    )
  }
}
