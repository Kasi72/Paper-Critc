package com.example.data.ai.epistemic

import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import java.util.Locale

/**
 * Safeguards #10, #12, #13 & #14:
 *  - Bidirectional Adversarial Adjudication (#13): Defense pass actively searches the manuscript
 *    to rescue, downgrade, or dismiss false-positive skeptical allegations (NOT merely writing a
 *    polite rebuttal).
 *  - Red Flag vs. Fatal Flaw Tiering (#10): Separates FATAL_TO_PRIMARY_CONCLUSION from minor
 *    reporting omissions or editorial enhancements.
 *  - Four-State Reporting Discipline (#2) & Minimum Evidence to Overturn (#12).
 *  - Full Analysis Provenance (#14): sourcePage, sourceQuote, extractedValues, parserVersion,
 *    ruleVersion, modelVersion, promptCoverage, isDeterministic.
 */
object BidirectionalAdjudicator {

  data class AdjudicatedOutput(
    val findings: List<ReviewFinding>,
    val defenseRecords: List<BidirectionalAdjudicationRecord>
  )

  private data class DefenseRule(
    val allegationMatcher: Regex,
    val defenseEvidenceMatcher: Regex,
    val defenseLabel: String,
    val dismissesCompletely: Boolean,
    val overturnHint: String
  )

  private val defenseRules = listOf(
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|did\s+not\s+perform).{0,60}(random[- ]effects|meta[- ]analysis|pooled|heterogeneity|i²|i2|tau)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(random[- ]effects|dersimonian|paule[- ]mandel|reml|i(?:\^2|²|2)\s*[=<>]|tau[- ]?squared|τ²|forest\s+plot)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript explicitly reports random-effects / heterogeneity synthesis.",
      dismissesCompletely = true,
      overturnHint = "Manuscript Methods/Results section documenting pooling estimator and I²/τ²."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|did\s+not\s+(?:use|perform|report)).{0,60}(intention[- ]to[- ]treat|\bitt\b|randomization|allocation\s+concealment)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(intention[- ]to[- ]treat|intent[- ]to[- ]treat|centralized\s+random|interactive\s+web\s+response|opaque\s+sealed\s+envelope)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript documents ITT / allocation concealment in Methods.",
      dismissesCompletely = true,
      overturnHint = "Trial protocol or Methods text specifying ITT estimand and allocation concealment."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without).{0,60}(sensitivity\s+analys|multiple\s+imputation|robustness|bootstrap|wild\s+cluster)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(sensitivity\s+analys(?:is|es)|multiple\s+imputation|mice|wild\s+cluster\s+bootstrap|callaway[- ]sant|honestdid|e[- ]value|oster)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript explicitly documents sensitivity/imputation/robustness analyses.",
      dismissesCompletely = true,
      overturnHint = "Supplementary sensitivity analysis table or Methods robustness section."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without).{0,60}(preregist|pre[- ]regist|protocol|prospero|ethics|irb)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(prospero|crd\d{8,}|nct\d{6,10}|osf\.io|clinicaltrials\.gov|institutional\s+review\s+board|\birb\b|ethics\s+committee)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript provides explicit trial/review registry ID or ethics approval.",
      dismissesCompletely = true,
      overturnHint = "Registry identifier (NCT/PROSPERO/OSF) or IRB approval number."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without).{0,60}(external\s+validation|calibration|brier|ece|patient[- ]level\s+split|groupkfold)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(external\s+validation|independent\s+cohort|brier\s+score|expected\s+calibration\s+error|\bece\b|patient[- ]level\s+split|subject[- ]level\s+split|groupkfold)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript documents external validation, calibration, or patient-grouped splitting.",
      dismissesCompletely = true,
      overturnHint = "Validation cohort table, calibration curve, or grouped CV pipeline specification."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without).{0,60}(competing\s+risk|fine[- ]gray|schoenfeld|proportional\s+hazards)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(fine[- ]gray|aalen[- ]johansen|subdistribution\s+hazard|schoenfeld\s+residual|proportional\s+hazards\s+assumption\s+(?:was\s+)?(?:tested|verified|met))""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript explicitly evaluates proportional hazards or competing risks.",
      dismissesCompletely = true,
      overturnHint = "Schoenfeld residual p-values or Fine-Gray / Aalen-Johansen cumulative incidence results."
    ),
    // Soft rescue: alleged missing justification/method when manuscript defers to Supplementary Appendix -> DOWNGRADE to NOT_ASSESSABLE rather than FATAL
    DefenseRule(
      allegationMatcher = Regex("""(not\s+reported|omitted|missing|no\s+evidence\s+of|lacks|did\s+not\s+report)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(supplementary\s+(?:table|appendix|material|methods|figure)|online\s+(?:appendix|supplement)|etable\s*\d+|appendix\s+table)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript defers supporting details to Supplementary Materials not supplied in full.",
      dismissesCompletely = false,
      overturnHint = "Inspection of the referenced Supplementary Appendix / eTables."
    )
  )

  fun adjudicate(
    findings: List<ReviewFinding>,
    manuscriptText: String,
    epistemicReport: EpistemicSafeguardReport?,
    modelVersion: String = "gemini-adjudicated",
    promptCoverage: String = "100%"
  ): AdjudicatedOutput {
    val defenseRecords = mutableListOf<BidirectionalAdjudicationRecord>()
    val stopTriggered = epistemicReport?.stopAndAsk?.shouldStopAndAsk == true

    val updated = findings.map { finding ->
      val isDeterministic = finding.id.startsWith("Q-") || finding.id.startsWith("S-") || finding.id.startsWith("E-")
      val combinedClaim = "${finding.title} ${finding.allegation}"
      var newStatus = finding.status
      var newSeverity = finding.severity
      val counterList = finding.counterEvidence.toMutableList()
      val notes = mutableListOf<String>()
      if (finding.verificationNotes.isNotBlank()) notes.add(finding.verificationNotes)

      // 1. Defense Pass (only rescues non-deterministic allegations or absence claims)
      var defenseOutcome = DefenseOutcome.UPHELD
      var matchedDefenseSnippet = ""
      var matchedDefensePage = 0
      var customOverturn = ""

      if (!isDeterministic && newStatus != FindingStatus.CONTRADICTED) {
        for (rule in defenseRules) {
          if (rule.allegationMatcher.containsMatchIn(combinedClaim)) {
            val match = rule.defenseEvidenceMatcher.find(manuscriptText)
            if (match != null) {
              val s = (match.range.first - 50).coerceAtLeast(0)
              val e = (match.range.last + 90).coerceAtMost(manuscriptText.length)
              matchedDefenseSnippet = manuscriptText.substring(s, e).replace(Regex("""\s+"""), " ").trim()
              matchedDefensePage = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(manuscriptText, match.range.first)
              customOverturn = rule.overturnHint
              counterList.add(
                EvidenceAnchor(
                  pageNumber = matchedDefensePage,
                  section = if (matchedDefensePage > 0) "Page $matchedDefensePage" else "Defense Rescue",
                  exactQuote = matchedDefenseSnippet,
                  startOffset = s,
                  endOffset = e
                )
              )
              if (rule.dismissesCompletely) {
                defenseOutcome = DefenseOutcome.DISMISSED_FALSE_POSITIVE
                newStatus = FindingStatus.CONTRADICTED
                newSeverity = "INFO"
                notes.add("Defense Pass DISMISSED false positive: ${rule.defenseLabel}")
              } else {
                defenseOutcome = DefenseOutcome.DOWNGRADED
                if (newStatus == FindingStatus.VERIFIED) newStatus = FindingStatus.PARTIALLY_SUPPORTED
                if (newSeverity == "CRITICAL") newSeverity = "MAJOR"
                notes.add("Defense Pass DOWNGRADED critique: ${rule.defenseLabel}")
              }
              break
            }
          }
        }
      }

      // 2. Stop-and-Ask Threshold Cap: never issue FATAL or VERIFIED CRITICAL when evidence is insufficient
      if (stopTriggered && newSeverity == "CRITICAL" && !isDeterministic) {
        newStatus = FindingStatus.PARTIALLY_SUPPORTED
        newSeverity = "MAJOR"
        notes.add("Downgraded under Stop-and-Ask completeness gate (insufficient supplied evidence).")
      }

      // 3. Assign Red Flag vs. Fatal Flaw Tier (#10) and Four-State Reporting Status (#2)
      val flawTier = classifyFlawTier(finding, newStatus, newSeverity, combinedClaim, stopTriggered)
      val reportingStatus = classifyReportingStatus(finding, newStatus, defenseOutcome, combinedClaim, stopTriggered)
      val overturnText = customOverturn.ifBlank { inferOverturnEvidence(combinedClaim, finding.recommendedCorrection) }

      // 4. Attach Full Analysis Provenance (#14)
      val anchor = finding.supportingEvidence.firstOrNull()
      val provenance = AnalysisProvenance(
        sourcePage = anchor?.pageNumber ?: 0,
        sourceQuote = anchor?.exactQuote.orEmpty().take(180),
        extractedValues = extractNumericTokenSummary(combinedClaim),
        parserVersion = AnalysisProvenance.PARSER_VERSION,
        ruleVersion = AnalysisProvenance.RULE_VERSION,
        modelVersion = if (isDeterministic) "deterministic-kotlin-engine" else modelVersion,
        promptCoverage = promptCoverage,
        isDeterministic = isDeterministic
      )

      if (defenseOutcome != DefenseOutcome.UPHELD) {
        defenseRecords.add(
          BidirectionalAdjudicationRecord(
            findingId = finding.id,
            skepticalClaim = finding.title,
            defenseEvidence = matchedDefenseSnippet,
            defensePage = matchedDefensePage,
            outcome = defenseOutcome,
            resultingSeverityTier = flawTier,
            resultingReportingStatus = reportingStatus
          )
        )
      }

      finding.copy(
        severity = newSeverity,
        status = newStatus,
        counterEvidence = counterList.distinctBy { it.exactQuote },
        verificationNotes = notes.joinToString(" ").trim(),
        reportingStatus = reportingStatus.name,
        flawTier = flawTier.name,
        overturnEvidence = overturnText,
        provenanceSummary = formatProvenanceCompact(provenance)
      )
    }

    return AdjudicatedOutput(updated, defenseRecords)
  }

  internal fun classifyFlawTier(
    finding: ReviewFinding,
    status: FindingStatus,
    severity: String,
    combinedClaim: String,
    stopTriggered: Boolean
  ): FlawSeverityTier {
    if (status == FindingStatus.CONTRADICTED) return FlawSeverityTier.COSMETIC_OR_EDITORIAL
    val lower = combinedClaim.lowercase(Locale.US)
    val isFatalMechanism = lower.contains("leakage") || lower.contains("arithmetic") ||
      lower.contains("impossible") || lower.contains("exceeds total") || lower.contains("denominator") ||
      lower.contains("attrition") || lower.contains("twfe") || lower.contains("weak instrument") ||
      lower.contains("immortal-time") || lower.contains("competing risk") || lower.contains("per-protocol") ||
      lower.contains("outcome switching") || lower.contains("primary_vs_secondary")

    return when {
      stopTriggered && status != FindingStatus.VERIFIED -> FlawSeverityTier.WORTHWHILE_ENHANCEMENT
      severity == "CRITICAL" && status == FindingStatus.VERIFIED && isFatalMechanism -> FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION
      severity == "CRITICAL" && status == FindingStatus.VERIFIED -> FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE
      severity == "CRITICAL" && status == FindingStatus.PARTIALLY_SUPPORTED -> FlawSeverityTier.REQUIRES_REPORTING_CORRECTION
      severity == "MAJOR" && isFatalMechanism -> FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE
      severity == "MAJOR" -> FlawSeverityTier.REQUIRES_REPORTING_CORRECTION
      severity == "MINOR" -> FlawSeverityTier.WORTHWHILE_ENHANCEMENT
      else -> FlawSeverityTier.COSMETIC_OR_EDITORIAL
    }
  }

  internal fun classifyReportingStatus(
    finding: ReviewFinding,
    status: FindingStatus,
    defenseOutcome: DefenseOutcome,
    combinedClaim: String,
    stopTriggered: Boolean
  ): ReportingStatus {
    val lower = combinedClaim.lowercase(Locale.US)
    return when {
      status == FindingStatus.CONTRADICTED -> ReportingStatus.REPORTED_AND_ADEQUATE
      defenseOutcome == DefenseOutcome.DOWNGRADED || stopTriggered || lower.contains("supplementary") || lower.contains("not assessable") || lower.contains("unsupported") ->
        ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL
      lower.contains("not reported") || lower.contains("omitted") || lower.contains("missing") || lower.contains("no mention") ->
        ReportingStatus.NOT_REPORTED
      else -> ReportingStatus.REPORTED_BUT_INADEQUATE
    }
  }

  private fun inferOverturnEvidence(combinedClaim: String, remedy: String): String {
    val l = combinedClaim.lowercase(Locale.US)
    return when {
      l.contains("denominator") || l.contains("attrition") || l.contains("subgroup") ->
        "Corrected CONSORT/STROBE flow table reconciling every enrolled, excluded, and analyzed unit."
      l.contains("leakage") || l.contains("external") ->
        "Locked patient-grouped pipeline and external cohort validation metrics."
      l.contains("protocol") || l.contains("primary") || l.contains("multiplicity") ->
        "Timestamped pre-registered protocol / statistical analysis plan (SAP)."
      l.contains("arithmetic") || l.contains("confidence interval") || l.contains("statcheck") || l.contains("2x2") ->
        "Primary statistical output / raw contingency table confirming counts, adjustment, or rounding."
      l.contains("twfe") || l.contains("instrument") || l.contains("competing risk") || l.contains("fixed-effect") ->
        "Re-estimation with the design-appropriate estimator and sensitivity bounds."
      remedy.isNotBlank() -> remedy.take(140)
      else -> "Supplementary table, prespecified protocol, or targeted sensitivity analysis."
    }
  }

  private fun extractNumericTokenSummary(text: String): String {
    val tokens = Regex("""(?:OR|RR|HR|aOR|aHR|β|t|z|F|I²|N|n|p)\s*[=<>≤≥:]\s*[-+]?\d+(?:\.\d+)?%?""", RegexOption.IGNORE_CASE)
      .findAll(text).map { it.value.trim() }.distinct().take(4).toList()
    return if (tokens.isEmpty()) "Narrative/structural rule" else tokens.joinToString(", ")
  }

  private fun formatProvenanceCompact(p: AnalysisProvenance): String =
    "Page=${if (p.sourcePage > 0) p.sourcePage else "n/a"} | Source=${if (p.isDeterministic) "Deterministic" else "Model"} (${p.modelVersion}) | Parser=${p.parserVersion} | Rules=${p.ruleVersion} | Coverage=${p.promptCoverage}" +
      if (p.extractedValues.isNotBlank()) " | Values=[${p.extractedValues}]" else ""
}
