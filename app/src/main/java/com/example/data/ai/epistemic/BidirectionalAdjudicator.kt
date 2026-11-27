package com.example.data.ai.epistemic

import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.semantic.CompoundClaimKey
import com.example.data.ai.semantic.SemanticValidityEngine
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.AnalysisHierarchy
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.ReviewFinding
import java.util.Locale

/**
 * Safeguards #10, #12, #13 & #14 + Revision B Claim-Scoped Defense Adjudication:
 *  - Bidirectional Adversarial Adjudication (#13): Defense pass actively searches the manuscript
 *    (excluding References) to rescue, downgrade, or dismiss false-positive skeptical allegations.
 *  - Revision B Scope Guard: Defense evidence must match the finding's compound claim key
 *    (population, outcome, time point, estimator, subgroup, adjustment status, analysis hierarchy).
 *    A defense for a different outcome, secondary endpoint, or different trial phase can ONLY
 *    downgrade or annotate — NEVER dismiss a primary claim-specific finding.
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
    val isStudyWideProperty: Boolean,
    val overturnHint: String
  )

  /**
   * P0-03: Explicit method-status classifier preventing keyword-triggered false dismissals
   * when a manuscript explicitly negates a method, proposes it only for future work, or cites it
   * solely as prior literature.
   */
  enum class MethodMentionStatus {
    PERFORMED,
    NOT_PERFORMED,
    PROPOSED_ONLY,
    CITED_AS_PRIOR_WORK,
    UNCLEAR
  }

  private enum class DefenseScopeAlignment {
    EXACT_OR_STUDY_WIDE,
    PARTIAL_SCOPE_MISMATCH,
    CONFLICTING_SCOPE
  }

  private val defenseRules = listOf(
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|did\s+not\s+perform).{0,60}(random[- ]effects|meta[- ]analysis|pooled|heterogeneity|i²|i2|tau)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(random[- ]effects|dersimonian|paule[- ]mandel|reml|i(?:\^2|²|2)\s*[=<>]|tau[- ]?squared|τ²|forest\s+plot)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript explicitly reports random-effects / heterogeneity synthesis.",
      dismissesCompletely = true,
      isStudyWideProperty = false,
      overturnHint = "Manuscript Methods/Results section documenting pooling estimator and I²/τ²."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|did\s+not\s+(?:use|perform|report)).{0,60}(intention[- ]to[- ]treat|\bitt\b|randomization|allocation\s+concealment)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(intention[- ]to[- ]treat|intent[- ]to[- ]treat|centralized\s+random|interactive\s+web\s+response|opaque\s+sealed\s+envelope)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript documents ITT / allocation concealment in Methods.",
      dismissesCompletely = true,
      isStudyWideProperty = false,
      overturnHint = "Trial protocol or Methods text specifying ITT estimand and allocation concealment."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without).{0,60}(sensitivity\s+analys|multiple\s+imputation|robustness|bootstrap|wild\s+cluster)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(sensitivity\s+analys(?:is|es)|multiple\s+imputation|mice|wild\s+cluster\s+bootstrap|callaway[- ]sant|honestdid|e[- ]value|oster)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript explicitly documents sensitivity/imputation/robustness analyses.",
      dismissesCompletely = true,
      isStudyWideProperty = false,
      overturnHint = "Supplementary sensitivity analysis table or Methods robustness section."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without).{0,60}(preregist|pre[- ]regist|protocol|prospero|ethics|irb)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(prospero|crd\d{8,}|nct\d{6,10}|osf\.io|clinicaltrials\.gov|institutional\s+review\s+board|\birb\b|ethics\s+committee)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript provides explicit trial/review registry ID or ethics approval.",
      dismissesCompletely = true,
      isStudyWideProperty = true,
      overturnHint = "Registry identifier (NCT/PROSPERO/OSF) or IRB approval number."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without).{0,60}(external\s+validation|calibration|brier|ece|patient[- ]level\s+split|groupkfold)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(external\s+validation|independent\s+cohort|brier\s+score|expected\s+calibration\s+error|\bece\b|patient[- ]level\s+split|subject[- ]level\s+split|groupkfold)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript documents external validation, calibration, or patient-grouped splitting.",
      dismissesCompletely = true,
      isStudyWideProperty = false,
      overturnHint = "Validation cohort table, calibration curve, or grouped CV pipeline specification."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(no|omitted|lacked|missing|without|did\s+not\s+(?:test|check|evaluate|assess|perform|conduct|report)).{0,60}(competing\s+risk|fine[- ]gray|schoenfeld|proportional\s+hazards)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(fine[- ]gray|aalen[- ]johansen|subdistribution\s+hazard|schoenfeld\s+residual|proportional\s+hazards\s+assumption\s+(?:was\s+)?(?:tested|verified|met))""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript explicitly evaluates proportional hazards or competing risks.",
      dismissesCompletely = true,
      isStudyWideProperty = false,
      overturnHint = "Schoenfeld residual p-values or Fine-Gray / Aalen-Johansen cumulative incidence results."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(unadjusted\s+(?:observational\s+)?analysis|(?:failed\s+to|did\s+not)\s+adjust\s+for\s+confound|(?:no|omitted|lacked|missing|without).{0,50}(?:confound(?:er|ing)?\s+adjustment|multivariable\s+adjustment|covariate\s+adjustment|propensity\s+score))""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(multivariable\s+(?:cox|logistic|linear|poisson|mixed)?\s*regression\s+adjusted|adjusted\s+for\s+(?:age|sex|baseline|confound)|propensity\s+score\s+(?:matching|weighting|stratification)|inverse\s+probability\s+(?:of\s+treatment\s+)?weighting)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript documents multivariable confounding adjustment or propensity weighting.",
      dismissesCompletely = true,
      isStudyWideProperty = false,
      overturnHint = "Methods section detailing multivariable covariate adjustment or propensity score weighting."
    ),
    DefenseRule(
      allegationMatcher = Regex("""(unblinded\s+(?:reference\s+standard|outcome\s+adjudication|assessor)|(?:no|omitted|lacked|missing|without|did\s+not\s+use).{0,40}(?:blind(?:ed|ing)?|mask(?:ed|ing)?)\s+(?:reference\s+standard|outcome\s+adjudication|assessor))""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(blinded\s+(?:reference\s+standard|outcome\s+adjudication|assessor)|masked\s+(?:reference\s+standard|outcome\s+adjudication|assessor)|double[- ]blind(?:ed)?\s+outcome\s+adjudication)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript documents blinded reference standard or masked outcome adjudication.",
      dismissesCompletely = true,
      isStudyWideProperty = false,
      overturnHint = "Methods description confirming blinded reference standard or masked adjudication."
    ),
    // Soft rescue: alleged missing justification/method when manuscript defers to Supplementary Appendix -> DOWNGRADE to NOT_ASSESSABLE rather than FATAL
    DefenseRule(
      allegationMatcher = Regex("""(not\s+reported|omitted|missing|no\s+evidence\s+of|lacks|did\s+not\s+report)""", RegexOption.IGNORE_CASE),
      defenseEvidenceMatcher = Regex("""(supplementary\s+(?:table|appendix|material|methods|figure)|online\s+(?:appendix|supplement)|etable\s*\d+|appendix\s+table)""", RegexOption.IGNORE_CASE),
      defenseLabel = "Manuscript defers supporting details to Supplementary Materials not supplied in full.",
      dismissesCompletely = false,
      isStudyWideProperty = true,
      overturnHint = "Inspection of the referenced Supplementary Appendix / eTables."
    )
  )

  private val referencesCutoffRegex = Regex("""(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(references|bibliography|works\s+cited)\s*$""")
  private val trialPhaseRegex = Regex("""\bphase\s*(I{1,3}V?|\d[ab]?)\b""", RegexOption.IGNORE_CASE)

  private fun inferHierarchy(text: String): AnalysisHierarchy {
    val l = text.lowercase(Locale.US)
    return when {
      Regex("""\b(?:secondary\s+(?:outcome|endpoint|analysis|analyses)|exploratory\s+(?:outcome|endpoint|analysis))\b""").containsMatchIn(l) -> AnalysisHierarchy.SECONDARY
      Regex("""\b(?:subgroup|stratum|stratified\s+subgroup)\b""").containsMatchIn(l) -> AnalysisHierarchy.SUBGROUP
      Regex("""\b(?:primary\s+(?:outcome|endpoint|analysis|analyses)|main\s+(?:outcome|analysis))\b""").containsMatchIn(l) -> AnalysisHierarchy.PRIMARY
      else -> AnalysisHierarchy.UNSPECIFIED
    }
  }

  private fun inferAdjustment(text: String): AdjustmentStatus {
    val l = text.lowercase(Locale.US)
    return when {
      Regex("""\b(?:unadjusted|crude|bivariate)\b""").containsMatchIn(l) -> AdjustmentStatus.UNADJUSTED
      Regex("""\b(?:adjusted|multivariable|multivariate)\b""").containsMatchIn(l) -> AdjustmentStatus.ADJUSTED
      else -> AdjustmentStatus.UNSPECIFIED
    }
  }

  private fun extractSentenceAround(text: String, start: Int, end: Int): String {
    var s = start
    while (s > 0 && text[s - 1] != '\n' && text[s - 1] != '.') s--
    var e = end
    while (e < text.length && text[e] != '\n' && text[e] != '.') e++
    return text.substring(s, e.coerceAtMost(text.length)).replace(Regex("""\s+"""), " ").trim()
  }

  private val preNegationRegex = Regex(
    """(?:\bno\s+(?:formal\s+|additional\s+|separate\s+|further\s+)?|\bnot\s+(?:perform|conduct|report|describe|use|apply|include|undertake|calculate|compute|run)(?:ed|ing)?\s+(?:a\s+|an\s+|any\s+|formal\s+)?|\bwithout\s+(?:a\s+|an\s+|any\s+)?|\bnever\s+(?:performed|conducted|reported|used)\s+(?:a\s+|an\s+)?|\blacked\s+(?:a\s+|an\s+|any\s+)?|\b(?:did\s+not|could\s+not|unable\s+to|failed\s+to|declined\s+to|chose\s+not\s+to|refrained\s+from|avoided)\s+(?:perform|conduct|report|use|include|apply|undertake|run)(?:ing)?\s+(?:a\s+|an\s+|any\s+|formal\s+)?|\brather\s+than\s+(?:a\s+|an\s+|using\s+|performing\s+)?|\binstead\s+of\s+(?:a\s+|an\s+|using\s+|performing\s+)?)\s*$""",
    RegexOption.IGNORE_CASE
  )
  private val postNegationRegex = Regex(
    """^\s*(?:was|were|is|are|could\s+be)?\s*(?:not\s+(?:performed|conducted|described|reported|done|applied|undertaken|available|included|feasible|possible|conducted|used)|omitted|absent|lacking|unavailable|excluded)\b""",
    RegexOption.IGNORE_CASE
  )
  private val sentenceNegationRegex = Regex(
    """\b(?:we\s+did\s+not\s+(?:perform|conduct|use|apply|run|report)|was\s+not\s+(?:performed|conducted|done|used|applied|reported)|were\s+not\s+(?:performed|conducted|done|used|applied|reported)|not\s+performed\s+because|unable\s+to\s+(?:perform|conduct)|could\s+not\s+be\s+(?:performed|conducted)|no\s+formal\s+[a-z\s-]{0,30}(?:was|were)\s+(?:performed|conducted))\b""",
    RegexOption.IGNORE_CASE
  )
  private val proposedOnlyRegex = Regex(
    """\b(?:future\s+(?:studies|work|research|trials|analyses)|should\s+(?:perform|conduct|use|include|evaluate)|recommend(?:ed|s|ing)?\s+(?:that\s+)?(?:future|subsequent)|we\s+propose\s+to|would\s+benefit\s+from|warrants?\s+future|planned\s+for\s+future|remains\s+to\s+be\s+(?:tested|evaluated|performed))\b""",
    RegexOption.IGNORE_CASE
  )
  private val priorWorkCitationRegex = Regex(
    """\b(?:previous\s+(?:studies|work|reviews|trials|meta-analyses)|prior\s+(?:studies|work|research|reports)|earlier\s+(?:studies|work)|existing\s+literature|et\s+al\.|as\s+reported\s+by|similarly,\s+[A-Z][a-z]+)\b""",
    RegexOption.IGNORE_CASE
  )
  private val firstPersonPerformedRegex = Regex(
    """\b(?:we\s+(?:performed|conducted|used|applied|estimated|ran|calculated|computed|employed|implemented|registered|obtained|followed|evaluated|assessed|pooled|fitted)|in\s+(?:this|the\s+present|our)\s+(?:study|trial|review|analysis|meta-analysis)|was\s+(?:performed|conducted|used|applied|estimated|calculated|registered|approved|assessed|evaluated)|were\s+(?:performed|conducted|used|applied|estimated|calculated|assessed|evaluated|independently\s+(?:extracted|screened|reviewed)))\b""",
    RegexOption.IGNORE_CASE
  )
  private val sectionHeadingLineRegex = Regex(
    """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(abstract|introduction|background|methods|materials\s+and\s+methods|methodology|statistical\s+analysis|results|findings|discussion|limitations|conclusions?|references|bibliography|supplementary)\b.*$"""
  )

  internal fun resolveSectionAtOffset(manuscriptText: String, offset: Int): String {
    if (manuscriptText.isBlank() || offset <= 0) return "Unknown"
    val prefix = manuscriptText.substring(0, offset.coerceAtMost(manuscriptText.length))
    val lastHeading = sectionHeadingLineRegex.findAll(prefix).lastOrNull() ?: return "Body"
    val raw = lastHeading.groupValues[1].lowercase(Locale.US)
    return when {
      raw.contains("abstract") -> "Abstract"
      raw.contains("intro") || raw.contains("background") -> "Introduction"
      raw.contains("method") || raw.contains("statistical") -> "Methods"
      raw.contains("result") || raw.contains("finding") -> "Results"
      raw.contains("discussion") || raw.contains("limitation") || raw.contains("conclusion") -> "Discussion"
      raw.contains("reference") || raw.contains("bibliograph") -> "References"
      raw.contains("supplement") -> "Supplement"
      else -> "Body"
    }
  }

  /**
   * Classifies whether a methodological phrase in [sentence] represents an analysis actually
   * [MethodMentionStatus.PERFORMED] in this study, explicitly [MethodMentionStatus.NOT_PERFORMED],
   * [MethodMentionStatus.PROPOSED_ONLY] for future work, [MethodMentionStatus.CITED_AS_PRIOR_WORK],
   * or [MethodMentionStatus.UNCLEAR].
   *
   * Only [MethodMentionStatus.PERFORMED] may overturn an absence allegation.
   */
  internal fun classifyMethodMentionStatus(
    sentence: String,
    matchedValue: String,
    sectionName: String = "Body"
  ): MethodMentionStatus {
    val cleanSentence = sentence.replace(Regex("""\s+"""), " ").trim()
    if (cleanSentence.isBlank()) return MethodMentionStatus.UNCLEAR
    if (sectionName.equals("References", ignoreCase = true)) {
      return MethodMentionStatus.CITED_AS_PRIOR_WORK
    }

    val idx = cleanSentence.indexOf(matchedValue, ignoreCase = true)
    if (idx >= 0) {
      val before = cleanSentence.substring((idx - 65).coerceAtLeast(0), idx)
      val after = cleanSentence.substring((idx + matchedValue.length).coerceAtMost(cleanSentence.length)).take(65)
      if (preNegationRegex.containsMatchIn(before) || postNegationRegex.containsMatchIn(after)) {
        return MethodMentionStatus.NOT_PERFORMED
      }
    }
    if (sentenceNegationRegex.containsMatchIn(cleanSentence)) {
      return MethodMentionStatus.NOT_PERFORMED
    }
    if (proposedOnlyRegex.containsMatchIn(cleanSentence)) {
      return MethodMentionStatus.PROPOSED_ONLY
    }
    val mentionsFirstPerson = firstPersonPerformedRegex.containsMatchIn(cleanSentence)
    if (priorWorkCitationRegex.containsMatchIn(cleanSentence) && !mentionsFirstPerson) {
      return MethodMentionStatus.CITED_AS_PRIOR_WORK
    }
    if (sectionName.equals("Introduction", ignoreCase = true) && !mentionsFirstPerson) {
      return MethodMentionStatus.CITED_AS_PRIOR_WORK
    }
    // In Discussion/Limitations, require explicit first-person/passive-performed framing or numeric evidence
    if (sectionName.equals("Discussion", ignoreCase = true) && !mentionsFirstPerson &&
      !Regex("""\b(?:nct\d{6,}|crd\d{8,}|prospero|i[²2]\s*[=<>]|p\s*[=<>])\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleanSentence)
    ) {
      return MethodMentionStatus.UNCLEAR
    }
    return MethodMentionStatus.PERFORMED
  }

  private fun isNegatedDefenseMention(sentence: String, matchedValue: String): Boolean =
    classifyMethodMentionStatus(sentence, matchedValue, "Body") != MethodMentionStatus.PERFORMED

  private fun evaluateDefenseScopeAlignment(
    findingText: String,
    findingKey: CompoundClaimKey,
    findingHierarchy: AnalysisHierarchy,
    findingPage: Int,
    defenseSentence: String,
    defensePage: Int,
    rule: DefenseRule
  ): DefenseScopeAlignment {
    if (rule.isStudyWideProperty) return DefenseScopeAlignment.EXACT_OR_STUDY_WIDE

    val defenseHierarchy = inferHierarchy(defenseSentence)
    val defenseKey = SemanticValidityEngine.extractCompoundKey(
      window = defenseSentence,
      section = "Defense",
      effectMeasure = findingKey.estimator,
      adjustmentStatus = inferAdjustment(defenseSentence),
      hierarchy = defenseHierarchy
    )

    // 1. Hard scope conflicts: secondary/subgroup defense CANNOT dismiss a primary finding
    if (findingHierarchy == AnalysisHierarchy.PRIMARY &&
      defenseHierarchy in setOf(AnalysisHierarchy.SECONDARY, AnalysisHierarchy.SUBGROUP, AnalysisHierarchy.EXPLORATORY)
    ) {
      return DefenseScopeAlignment.CONFLICTING_SCOPE
    }
    if (findingHierarchy == AnalysisHierarchy.SECONDARY && defenseHierarchy == AnalysisHierarchy.PRIMARY) {
      return DefenseScopeAlignment.PARTIAL_SCOPE_MISMATCH
    }

    // 2. Trial phase conflict (e.g., Phase 1 vs Phase 2 allocation concealment)
    val fPhase = trialPhaseRegex.find(findingText)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
    val dPhase = trialPhaseRegex.find(defenseSentence)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)
    if (fPhase != null && dPhase != null && fPhase != dPhase) {
      return DefenseScopeAlignment.CONFLICTING_SCOPE
    }

    // 3. Explicit compound-key field conflicts (outcome, population, timePoint, subgroup, adjustment)
    if (findingKey.outcome != "unspecified" && defenseKey.outcome != "unspecified" &&
      !findingKey.outcome.equals(defenseKey.outcome, ignoreCase = true)
    ) {
      return DefenseScopeAlignment.CONFLICTING_SCOPE
    }
    if (findingKey.population != "unspecified" && defenseKey.population != "unspecified" &&
      !findingKey.population.equals(defenseKey.population, ignoreCase = true)
    ) {
      return DefenseScopeAlignment.CONFLICTING_SCOPE
    }
    if (findingKey.timePoint != "unspecified" && defenseKey.timePoint != "unspecified" &&
      !findingKey.timePoint.equals(defenseKey.timePoint, ignoreCase = true)
    ) {
      return DefenseScopeAlignment.CONFLICTING_SCOPE
    }
    if (findingKey.subgroup != "overall" && defenseKey.subgroup != "overall" &&
      !findingKey.subgroup.equals(defenseKey.subgroup, ignoreCase = true)
    ) {
      return DefenseScopeAlignment.CONFLICTING_SCOPE
    }
    if (!rule.defenseLabel.contains("confounding", ignoreCase = true) &&
      findingKey.adjustmentStatus != AdjustmentStatus.UNSPECIFIED &&
      defenseKey.adjustmentStatus != AdjustmentStatus.UNSPECIFIED &&
      findingKey.adjustmentStatus != defenseKey.adjustmentStatus
    ) {
      return DefenseScopeAlignment.CONFLICTING_SCOPE
    }

    // 4. If finding names a specific outcome or trial phase, defense must either name that same outcome/phase,
    //    be on the same page, or explicitly cover "all outcomes" / "primary analysis" / study-level Methods.
    val defenseLower = defenseSentence.lowercase(Locale.US)
    val coversAll = defenseLower.contains("all outcomes") || defenseLower.contains("all endpoints") ||
      defenseLower.contains("primary and secondary") || defenseLower.contains("entire cohort")
    val findingHasSpecificOutcome = findingKey.outcome != "unspecified"
    if (findingHasSpecificOutcome) {
      val sameOutcome = defenseKey.outcome.equals(findingKey.outcome, ignoreCase = true)
      val samePage = findingPage > 0 && defensePage > 0 && findingPage == defensePage
      if (sameOutcome || coversAll) {
        return DefenseScopeAlignment.EXACT_OR_STUDY_WIDE
      }
      // If defense mentions some OTHER endpoint explicitly ("for secondary", "only for", "subset") -> mismatch
      if (defenseLower.contains("only for") || defenseLower.contains("subset") ||
        defenseHierarchy == AnalysisHierarchy.SECONDARY || defenseHierarchy == AnalysisHierarchy.SUBGROUP
      ) {
        return DefenseScopeAlignment.CONFLICTING_SCOPE
      }
      // If defense doesn't mention the specific outcome and is on a different page -> downgrade only, do NOT fully dismiss!
      return if (samePage) DefenseScopeAlignment.EXACT_OR_STUDY_WIDE else DefenseScopeAlignment.PARTIAL_SCOPE_MISMATCH
    }

    // 5. Finding is study-level (e.g., "did not perform random-effects meta-analysis")
    //    Ensure defense itself isn't restricted to a secondary/subgroup-only footnote.
    if (defenseHierarchy == AnalysisHierarchy.SECONDARY || defenseHierarchy == AnalysisHierarchy.SUBGROUP) {
      return DefenseScopeAlignment.PARTIAL_SCOPE_MISMATCH
    }
    return DefenseScopeAlignment.EXACT_OR_STUDY_WIDE
  }

  fun adjudicate(
    findings: List<ReviewFinding>,
    manuscriptText: String,
    epistemicReport: EpistemicSafeguardReport?,
    modelVersion: String = "gemini-adjudicated",
    promptCoverage: String = "100%"
  ): AdjudicatedOutput {
    val defenseRecords = mutableListOf<BidirectionalAdjudicationRecord>()
    val stopTriggered = epistemicReport?.stopAndAsk?.shouldStopAndAsk == true
    val refCutoff = referencesCutoffRegex.find(manuscriptText)?.range?.first ?: manuscriptText.length
    val searchableManuscript = manuscriptText.substring(0, refCutoff)

    val updated = findings.map { finding ->
      val isDeterministic = finding.id.startsWith("Q-") || finding.id.startsWith("S-") || finding.id.startsWith("E-")
      val anchorQuote = finding.supportingEvidence.firstOrNull()?.exactQuote.orEmpty()
      val anchorPage = finding.supportingEvidence.firstOrNull()?.pageNumber ?: 0
      val combinedClaim = "${finding.title} ${finding.allegation}"
      val findingScopeText = "$combinedClaim $anchorQuote"
      val findingHierarchy = inferHierarchy(findingScopeText)
      val findingKey = SemanticValidityEngine.extractCompoundKey(
        window = findingScopeText,
        section = finding.supportingEvidence.firstOrNull()?.section ?: "Finding",
        effectMeasure = "unspecified",
        adjustmentStatus = inferAdjustment(findingScopeText),
        hierarchy = findingHierarchy
      )

      var newStatus = finding.status
      var newSeverity = finding.severity
      val counterList = finding.counterEvidence.toMutableList()
      val notes = mutableListOf<String>()
      if (finding.verificationNotes.isNotBlank()) notes.add(finding.verificationNotes)

      // 1. Claim-Scoped Defense Pass (only rescues non-deterministic allegations or absence claims)
      var defenseOutcome = DefenseOutcome.UPHELD
      var matchedDefenseSnippet = ""
      var matchedDefensePage = 0
      var customOverturn = ""

      if (!isDeterministic && newStatus != FindingStatus.CONTRADICTED) {
        for (rule in defenseRules) {
          if (rule.allegationMatcher.containsMatchIn(combinedClaim)) {
            val rawMatches = rule.defenseEvidenceMatcher.findAll(searchableManuscript).toList()
            val classifiedMatches = rawMatches.map { m ->
              val sent = extractSentenceAround(searchableManuscript, m.range.first, m.range.last)
              val sec = resolveSectionAtOffset(searchableManuscript, m.range.first)
              val mentionStatus = classifyMethodMentionStatus(sent, m.value, sec)
              Triple(m, sent, mentionStatus)
            }
            val performedMatches = classifiedMatches
              .filter { it.third == MethodMentionStatus.PERFORMED }
              .map { it.first }
            if (performedMatches.isEmpty()) {
              val confirmedNotPerformed = classifiedMatches.firstOrNull { it.third == MethodMentionStatus.NOT_PERFORMED }
              if (confirmedNotPerformed != null) {
                notes.add("Method-status audit confirmed manuscript explicitly states this analysis was NOT_PERFORMED (not dismissed as counterevidence).")
              }
              continue
            }
            val allMatches = performedMatches

            // Evaluate every candidate match and select the best-aligned scope
            val evaluated = allMatches.map { m ->
              val sent = extractSentenceAround(searchableManuscript, m.range.first, m.range.last)
              val pg = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(searchableManuscript, m.range.first)
              val alignment = evaluateDefenseScopeAlignment(
                findingText = findingScopeText,
                findingKey = findingKey,
                findingHierarchy = findingHierarchy,
                findingPage = anchorPage,
                defenseSentence = sent,
                defensePage = pg,
                rule = rule
              )
              Triple(m, sent to pg, alignment)
            }

            val best = evaluated.firstOrNull { it.third == DefenseScopeAlignment.EXACT_OR_STUDY_WIDE }
              ?: evaluated.firstOrNull { it.third == DefenseScopeAlignment.PARTIAL_SCOPE_MISMATCH }
              ?: evaluated.first()

            val match = best.first
            val (defenseSentence, page) = best.second
            val alignment = best.third
            val s = (match.range.first - 50).coerceAtLeast(0)
            val e = (match.range.last + 90).coerceAtMost(searchableManuscript.length)
            matchedDefenseSnippet = defenseSentence.ifBlank {
              searchableManuscript.substring(s, e).replace(Regex("""\s+"""), " ").trim()
            }
            matchedDefensePage = page
            customOverturn = rule.overturnHint

            when (alignment) {
              DefenseScopeAlignment.EXACT_OR_STUDY_WIDE -> {
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
                  notes.add("Defense Pass DISMISSED false positive (scope-aligned): ${rule.defenseLabel}")
                } else {
                  defenseOutcome = DefenseOutcome.DOWNGRADED
                  if (newStatus == FindingStatus.VERIFIED) newStatus = FindingStatus.PARTIALLY_SUPPORTED
                  if (newSeverity == "CRITICAL") newSeverity = "MAJOR"
                  notes.add("Defense Pass DOWNGRADED critique: ${rule.defenseLabel}")
                }
                break
              }
              DefenseScopeAlignment.PARTIAL_SCOPE_MISMATCH -> {
                counterList.add(
                  EvidenceAnchor(
                    pageNumber = matchedDefensePage,
                    section = if (matchedDefensePage > 0) "Page $matchedDefensePage (Partial Scope)" else "Defense Partial Scope",
                    exactQuote = matchedDefenseSnippet,
                    startOffset = s,
                    endOffset = e
                  )
                )
                // Revision B: partial scope match may ONLY downgrade, NEVER dismiss completely!
                defenseOutcome = DefenseOutcome.DOWNGRADED
                if (newStatus == FindingStatus.VERIFIED) newStatus = FindingStatus.PARTIALLY_SUPPORTED
                if (newSeverity == "CRITICAL") newSeverity = "MAJOR"
                notes.add("Defense Pass DOWNGRADED only (partial claim-scope match; full dismissal blocked): ${rule.defenseLabel}")
                break
              }
              DefenseScopeAlignment.CONFLICTING_SCOPE -> {
                // Conflicting endpoint/hierarchy/phase -> do NOT dismiss or clear the finding!
                notes.add("Defense candidate on Page $matchedDefensePage rejected for dismissal because it applies to a different outcome/analysis scope.")
              }
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
