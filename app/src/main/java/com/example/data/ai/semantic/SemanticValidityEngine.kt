package com.example.data.ai.semantic

import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.stats.AdjustmentStatus
import com.example.data.ai.stats.AnalysisHierarchy
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.ClaimParseStatus
import com.example.data.ai.stats.NumericalReconstructionResult
import com.example.data.ai.stats.QuantitativeClaim
import com.example.data.ai.stats.StatisticalClaimParser
import com.example.util.CoverageStatus
import com.example.util.DocumentCoverageReport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Phase 8 — Trust, Abstention & Semantic Validity Engine.
 *
 * Implements:
 *  #1 Extraction failure vs. manuscript omission classification
 *  #2 Design-classifier confidence scoring & abstention gate
 *  #3 Anti-dogma estimand-first method assessments
 *  #5 Compound evidence-to-claim key extraction
 *  #6 Denominator provenance graph (eligible -> enrolled -> randomized -> treated -> followed -> analyzed)
 *  #7 Multiplicity-family uncertainty & non-fabrication guard
 *  #8 Analyst degrees-of-freedom (11 flexibility dimensions) audit
 *  #9 Substantive missing-data audit (beyond "MI used")
 *  #10 Negative-control & falsification checks
 *  #11 Effect-direction & scale sanity checks (8 failure modes)
 *  #12 Proper abstention policy
 *  + PDF column deduplication & OCR digit-confusion detection
 */
object SemanticValidityEngine {

  data class SanitizedTextResult(
    val text: String,
    val duplicatedColumnsRemoved: Int,
    val ocrNoiseDetected: Boolean,
    val suspectedDuplications: List<SuspectedDuplication> = emptyList()
  )

  private val ocrDigitConfusionRegex = Regex(
    """(?:\b[pP]\s*[=<>]\s*(?:[OoIl]\.\d+|\d+\.[OoIl]\d*)|\b(?:OR|RR|HR)\s*=\s*(?:[OoIl]\.\d+|\d+\.[OoIl]\d*)|\b\d+[OoIl]\.\d+)"""
  )

  /**
   * Layout-aware PDF deduplication (Revision E / P2):
   * - When [layoutMetadata] is supplied, deduplicates ONLY when layout evidence matches
   *   (same page + same column or overlapping bounding box/x-y coordinates).
   * - When only plain text is available, removes ONLY immediately consecutive identical long lines
   *   on the same page (classic 2-column extractor double-emit), and PRESERVES non-adjacent
   *   repeated lines (e.g., repeated Methods clauses, table row labels, eligibility statements)
   *   while tagging them in [SanitizedTextResult.suspectedDuplications] instead of silently deleting them.
   */
  fun sanitizePdfExtractionNoise(
    rawText: String,
    layoutMetadata: List<PdfLineLayoutMetadata>? = null
  ): SanitizedTextResult {
    val lines = rawText.lines()
    val deduped = mutableListOf<String>()
    val suspected = mutableListOf<SuspectedDuplication>()
    var removed = 0
    val hasLayout = layoutMetadata != null && layoutMetadata.size == lines.size

    var currentPage = 0
    var prevNonBlankNorm = ""
    var prevNonBlankPage = -1
    val seenByNorm = mutableMapOf<String, MutableList<Pair<Int, PdfLineLayoutMetadata?>>>()
    val pageTagRegex = Regex("""^\[PAGE\s+(\d+)\]$""", RegexOption.IGNORE_CASE)

    for (idx in lines.indices) {
      val line = lines[idx]
      val trimmed = line.trim()
      val pageHit = pageTagRegex.matchEntire(trimmed)
      if (pageHit != null) {
        currentPage = pageHit.groupValues[1].toIntOrNull() ?: currentPage
        deduped.add(line)
        continue
      }
      val normalized = trimmed.replace(Regex("""\s+"""), " ")
      val isLongContent = normalized.length >= 45
      val curMeta = if (hasLayout) layoutMetadata?.getOrNull(idx) else null
      val effectivePage = curMeta?.pageNumber ?: currentPage

      if (isLongContent) {
        val priorEntries = seenByNorm[normalized]
        if (hasLayout && priorEntries != null) {
          val matchingLayout = priorEntries.firstOrNull { (_, prevMeta) ->
            prevMeta != null && curMeta != null &&
              prevMeta.pageNumber == curMeta.pageNumber &&
              (prevMeta.columnIndex == curMeta.columnIndex ||
                (abs(prevMeta.x - curMeta.x) <= 24f && abs(prevMeta.y - curMeta.y) <= 24f))
          }
          if (matchingLayout != null) {
            removed++
            suspected.add(
              SuspectedDuplication(
                lineIndex = idx,
                pageNumber = effectivePage,
                excerpt = normalized.take(100),
                reason = "Layout-verified duplicate on page $effectivePage (same column/bounding region).",
                wasRemoved = true
              )
            )
            continue
          } else {
            // Different page or different column/region -> legitimate repetition; preserve & note
            suspected.add(
              SuspectedDuplication(
                lineIndex = idx,
                pageNumber = effectivePage,
                excerpt = normalized.take(100),
                reason = "Repeated text in distinct page/column layout region — preserved as legitimate text.",
                wasRemoved = false
              )
            )
          }
        } else if (!hasLayout && prevNonBlankNorm == normalized && prevNonBlankPage == effectivePage) {
          // Immediately consecutive duplicate on the same page -> safe column double-emit dedup
          removed++
          suspected.add(
            SuspectedDuplication(
              lineIndex = idx,
              pageNumber = effectivePage,
              excerpt = normalized.take(100),
              reason = "Immediately consecutive identical long line on same page (likely PDF column double-emit).",
              wasRemoved = true
            )
          )
          continue
        } else if (!hasLayout && priorEntries != null) {
          // Non-adjacent plain-text repetition without layout metadata -> DO NOT silently delete!
          suspected.add(
            SuspectedDuplication(
              lineIndex = idx,
              pageNumber = effectivePage,
              excerpt = normalized.take(100),
              reason = "Possible extraction duplication (plain-text only; no layout coordinates — preserved without deletion).",
              wasRemoved = false
            )
          )
        }
        seenByNorm.getOrPut(normalized) { mutableListOf() }.add(idx to curMeta)
      }

      deduped.add(line)
      if (normalized.isNotBlank()) {
        prevNonBlankNorm = normalized
        prevNonBlankPage = effectivePage
      }
    }

    val cleaned = deduped.joinToString("\n")
    val hasOcrNoise = ocrDigitConfusionRegex.containsMatchIn(cleaned) ||
      cleaned.count { it == '\uFFFD' } >= 2
    return SanitizedTextResult(cleaned, removed, hasOcrNoise, suspected)
  }

  // ============================================================================
  // #2. SECTION-FILTERED, MUTUALLY EXCLUSIVE, CALIBRATED DESIGN CLASSIFIER
  // ============================================================================
  private val referencesHeaderRegex = Regex(
    """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(references|bibliography|works\s+cited|literature\s+cited)\s*$"""
  )
  private val quotedPriorWorkRegex = Regex(
    """(?i)(?:[A-Z][A-Za-z\-]+(?:\s+et\s+al\.?)?\s*\(\s*(?:19|20)\d{2}[a-z]?\s*\)(?:et\s+al\.|\.\d|[^.\n]){0,220}|(?:previous|prior|earlier|other)\s+(?:studies|trials|papers|work|authors|meta-analyses|benchmarks)(?:et\s+al\.|\.\d|[^.\n]){0,220}|"[^"\n]{10,160}")"""
  )

  /**
   * Strips References/Bibliography sections and quoted/cited prior-work clauses so terms like
   * "randomized", "benchmark", or "proof" in citations cannot contaminate study-design classification.
   */
  internal fun extractActiveDesignClassificationText(rawText: String): String {
    val refMatch = referencesHeaderRegex.find(rawText)
    val bodyOnly = if (refMatch != null) rawText.substring(0, refMatch.range.first) else rawText
    return quotedPriorWorkRegex.replace(bodyOnly, " ")
  }

  private const val CALIBRATION_TEMPERATURE = 1.65
  private const val UNKNOWN_BASELINE_LOGIT = 2.60

  fun classifyDesignWithConfidence(
    text: String,
    parentArchetype: StudyDesignArchetype
  ): DesignClassificationResult {
    val activeBody = extractActiveDesignClassificationText(text)
    val l = activeBody.lowercase(Locale.US)
    val scores = mutableMapOf<CalibratedStudyDesign, Pair<Double, MutableList<String>>>()
    fun addScore(design: CalibratedStudyDesign, weight: Double, term: String) {
      val entry = scores.getOrPut(design) { 0.0 to mutableListOf() }
      if (term !in entry.second) {
        entry.second.add(term)
        scores[design] = (entry.first + weight) to entry.second
      }
    }

    // 1. Quantitative Meta-Analysis vs. Qualitative Systematic Review
    val metaTerms = listOf(
      "meta-analysis" to 3.6, "forest plot" to 2.2, "dersimonian" to 2.5, "reml" to 1.8,
      "paule-mandel" to 2.3, "tau-squared" to 1.8, "τ²" to 1.8, "pooled estimate" to 2.0,
      "pooled odds ratio" to 2.2, "random-effects model" to 2.0, "fixed-effect model" to 1.8,
      "hartung-knapp" to 2.0
    )
    metaTerms.forEach { (k, w) -> if (l.contains(k)) addScore(CalibratedStudyDesign.META_ANALYSIS, w, k) }
    if (Regex("""\bi(?:\^2|²|2)\s*[=<>]""").containsMatchIn(l)) {
      addScore(CalibratedStudyDesign.META_ANALYSIS, 2.0, "I²")
    }

    val srTerms = listOf(
      "systematic review" to 3.0, "prisma" to 2.2, "prospero" to 2.0,
      "narrative synthesis" to 2.6, "scoping review" to 2.4, "cochrane" to 1.4
    )
    val hasQuantPooling = scores[CalibratedStudyDesign.META_ANALYSIS]?.first ?: 0.0 >= 3.0
    srTerms.forEach { (k, w) ->
      if (l.contains(k)) {
        // Only credit qualitative systematic review when not primarily a quantitative meta-analysis
        val adjWeight = if (hasQuantPooling && k != "narrative synthesis") w * 0.35 else w
        addScore(CalibratedStudyDesign.SYSTEMATIC_REVIEW_NARRATIVE, adjWeight, k)
      }
    }

    // 2. Randomized Controlled Trial (RCT)
    val rctTerms = listOf(
      "randomized controlled" to 3.8, "randomised controlled" to 3.8, "randomly assigned" to 3.0,
      "double-blind" to 2.4, "placebo-controlled" to 2.4, "consort" to 2.4,
      "allocation concealment" to 2.4, "intention-to-treat" to 1.8, "cluster-randomized" to 3.2,
      "per-protocol" to 1.3
    )
    rctTerms.forEach { (k, w) -> if (l.contains(k)) addScore(CalibratedStudyDesign.RCT, w, k) }
    if (!l.contains("randomized controlled") && !l.contains("randomised controlled") &&
      Regex("""(?<!non[- ])(?<!un)(?<!quasi[- ])\b(?:randomized|randomised)\b""").containsMatchIn(l)
    ) {
      addScore(CalibratedStudyDesign.RCT, 2.0, "randomized")
    }

    // 3. Mutually exclusive Observational Designs: Case-Control, Cross-Sectional, Prospective Cohort, Retrospective Cohort
    val isCaseControl = l.contains("case-control") || l.contains("case control") ||
      (l.contains("matched controls") && l.contains("cases"))
    if (isCaseControl) {
      addScore(CalibratedStudyDesign.CASE_CONTROL, 3.8, "case-control")
      if (l.contains("strobe")) addScore(CalibratedStudyDesign.CASE_CONTROL, 1.4, "strobe")
      if (l.contains("matched controls")) addScore(CalibratedStudyDesign.CASE_CONTROL, 1.8, "matched controls")
      if (l.contains("odds ratio")) addScore(CalibratedStudyDesign.CASE_CONTROL, 1.0, "odds ratio")
    }

    val isCrossSectional = l.contains("cross-sectional") || l.contains("cross sectional") ||
      l.contains("prevalence survey") || l.contains("national health survey")
    if (isCrossSectional) {
      addScore(CalibratedStudyDesign.CROSS_SECTIONAL, 3.8, "cross-sectional")
      if (l.contains("survey")) addScore(CalibratedStudyDesign.CROSS_SECTIONAL, 1.5, "survey")
      if (l.contains("prevalence")) addScore(CalibratedStudyDesign.CROSS_SECTIONAL, 1.3, "prevalence")
      if (l.contains("observational")) addScore(CalibratedStudyDesign.CROSS_SECTIONAL, 1.0, "observational")
      if (l.contains("strobe")) addScore(CalibratedStudyDesign.CROSS_SECTIONAL, 1.2, "strobe")
    }

    if (l.contains("prospective cohort") || l.contains("longitudinal cohort")) {
      addScore(CalibratedStudyDesign.PROSPECTIVE_COHORT, 3.6, "prospective cohort")
      if (l.contains("strobe")) addScore(CalibratedStudyDesign.PROSPECTIVE_COHORT, 1.4, "strobe")
      if (l.contains("follow-up") || l.contains("followed")) addScore(CalibratedStudyDesign.PROSPECTIVE_COHORT, 1.2, "follow-up")
    }
    if (l.contains("retrospective cohort")) {
      addScore(CalibratedStudyDesign.RETROSPECTIVE_COHORT, 3.6, "retrospective cohort")
      if (l.contains("strobe")) addScore(CalibratedStudyDesign.RETROSPECTIVE_COHORT, 1.4, "strobe")
      if (l.contains("propensity score")) addScore(CalibratedStudyDesign.RETROSPECTIVE_COHORT, 1.6, "propensity score")
    } else if (!isCaseControl && !isCrossSectional && (l.contains("cohort study") || l.contains("observational study"))) {
      val target = if (l.contains("prospective")) CalibratedStudyDesign.PROSPECTIVE_COHORT else CalibratedStudyDesign.RETROSPECTIVE_COHORT
      if (l.contains("cohort study")) addScore(target, 3.0, "cohort study")
      if (l.contains("observational study")) addScore(target, 2.2, "observational study")
      if (l.contains("propensity score")) addScore(target, 1.6, "propensity score")
      if (l.contains("strobe")) addScore(target, 1.6, "strobe")
    }

    // 4. Survival / Time-to-Event
    val survTerms = listOf(
      "kaplan-meier" to 2.8, "cox proportional" to 3.2, "hazard ratio" to 2.0,
      "time-to-event" to 3.0, "competing risk" to 2.2, "fine-gray" to 2.4,
      "log-rank" to 1.8, "person-years" to 1.4
    )
    survTerms.forEach { (k, w) -> if (l.contains(k)) addScore(CalibratedStudyDesign.SURVIVAL_TIME_TO_EVENT, w, k) }

    // 5. Diagnostic Accuracy
    val diagTerms = listOf(
      "diagnostic accuracy" to 3.8, "stard" to 2.6, "quadas" to 2.6, "reference standard" to 2.6,
      "gold standard" to 1.8, "sensitivity and specificity" to 2.6, "likelihood ratio" to 1.8
    )
    diagTerms.forEach { (k, w) -> if (l.contains(k)) addScore(CalibratedStudyDesign.DIAGNOSTIC_ACCURACY, w, k) }

    // 6. Machine Learning / Predictive Benchmark (require genuine ML modeling bundle, NOT bare "benchmark dataset"!)
    val mlCoreTerms = listOf(
      "train/test" to 2.8, "cross-validation" to 2.4, "neural network" to 2.6, "transformer" to 2.6,
      "deep learning" to 2.6, "ablation" to 2.2, "hyperparameter" to 2.4, "xgboost" to 2.4,
      "random forest" to 2.2, "holdout" to 1.8, "epoch" to 1.8, "classifier" to 1.6
    )
    mlCoreTerms.forEach { (k, w) -> if (l.contains(k)) addScore(CalibratedStudyDesign.ML_PREDICTION_BENCHMARK, w, k) }
    val hasMlCoreBundle = (scores[CalibratedStudyDesign.ML_PREDICTION_BENCHMARK]?.first ?: 0.0) >= 1.5
    if (hasMlCoreBundle && l.contains("benchmark")) {
      addScore(CalibratedStudyDesign.ML_PREDICTION_BENCHMARK, 1.4, "benchmark")
    }

    // 7. Causal Econometric Panel vs. Descriptive Panel
    val causalEconTerms = listOf(
      "difference-in-differences" to 3.6, "instrumental variable" to 3.6, "two-stage least squares" to 3.2,
      "2sls" to 2.8, "regression discontinuity" to 3.6, "staggered adoption" to 2.8,
      "two-way fixed effects" to 2.6, "twfe" to 2.4, "parallel trends" to 2.6
    )
    causalEconTerms.forEach { (k, w) -> if (l.contains(k)) addScore(CalibratedStudyDesign.CAUSAL_ECONOMETRIC_PANEL, w, k) }
    if (l.contains("descriptive panel") && (scores[CalibratedStudyDesign.CAUSAL_ECONOMETRIC_PANEL]?.first ?: 0.0) < 2.0) {
      addScore(CalibratedStudyDesign.DESCRIPTIVE_PANEL, 3.4, "descriptive panel")
      if (l.contains("panel data")) addScore(CalibratedStudyDesign.DESCRIPTIVE_PANEL, 1.6, "panel data")
    } else if (l.contains("panel data") && (scores[CalibratedStudyDesign.CAUSAL_ECONOMETRIC_PANEL]?.first ?: 0.0) >= 2.0) {
      addScore(CalibratedStudyDesign.CAUSAL_ECONOMETRIC_PANEL, 1.4, "panel data")
    }

    // 8. Formal Mathematical Proof vs. Algorithmic Theory (never trigger from bare "proof" in an empirical paper!)
    val mathCoreTerms = listOf(
      "theorem" to 2.8, "lemma" to 2.5, "proposition" to 2.0, "q.e.d" to 2.4,
      "banach" to 2.6, "hilbert" to 2.6, "dominated convergence" to 2.4, "fubini" to 2.2,
      "corollary" to 2.0, "axiom" to 1.8
    )
    mathCoreTerms.forEach { (k, w) -> if (l.contains(k)) addScore(CalibratedStudyDesign.FORMAL_MATHEMATICAL_PROOF, w, k) }
    val hasMathBundle = (scores[CalibratedStudyDesign.FORMAL_MATHEMATICAL_PROOF]?.first ?: 0.0) >= 2.0
    if (hasMathBundle && Regex("""\bproof\b""").containsMatchIn(l) && !l.contains("proof of concept") && !l.contains("proof-of-concept")) {
      addScore(CalibratedStudyDesign.FORMAL_MATHEMATICAL_PROOF, 1.8, "proof")
    }
    if ((l.contains("time complexity") || l.contains("asymptotic complexity") || l.contains("np-hard")) &&
      (l.contains("algorithm") || l.contains("o(n"))
    ) {
      addScore(CalibratedStudyDesign.ALGORITHMIC_THEORY, 3.6, "algorithmic complexity")
      if (l.contains("theorem") || l.contains("lemma")) addScore(CalibratedStudyDesign.ALGORITHMIC_THEORY, 1.8, "theorem/lemma")
    }

    // Remove candidates lacking a positive design evidence bundle (logit < 2.0 and single weak term)
    val qualifiedScores = scores.filter { (_, pair) -> pair.first >= 2.0 || pair.second.size >= 2 }

    if (qualifiedScores.isEmpty()) {
      return DesignClassificationResult(
        primaryDesign = AuditSubDesign.GENERAL_EMPIRICAL,
        primaryLabel = CalibratedStudyDesign.UNKNOWN.displayName,
        primaryConfidence = 0.25,
        alternatives = emptyList(),
        evidence = emptyList(),
        abstained = true,
        suppressedChecks = listOf("Design-specific causal/estimator checks suppressed (insufficient positive design evidence bundle)"),
        calibratedDesign = CalibratedStudyDesign.UNKNOWN,
        margin = 0.0
      )
    }

    // Temperature-scaled softmax including an explicit UNKNOWN class prior
    val logits = mutableMapOf<CalibratedStudyDesign, Double>()
    qualifiedScores.forEach { (d, pair) -> logits[d] = pair.first }
    logits[CalibratedStudyDesign.UNKNOWN] = UNKNOWN_BASELINE_LOGIT
    val maxLogit = logits.values.max()
    val expScores = logits.mapValues { (_, v) -> exp((v - maxLogit) / CALIBRATION_TEMPERATURE) }
    val partition = expScores.values.sum()
    val probs = expScores.mapValues { (_, e) -> e / partition }

    val rankedAll = probs.entries.sortedByDescending { it.value }
    val topEntry = rankedAll.first()
    val nonUnknownRanked = rankedAll.filter { it.key != CalibratedStudyDesign.UNKNOWN }
    val runnerUpProb = rankedAll.getOrNull(1)?.value ?: 0.0
    val margin = (topEntry.value - runnerUpProb).coerceAtLeast(0.0)

    val topDesignKey = topEntry.key
    val primaryProb = topEntry.value.coerceIn(0.05, 0.99)
    val abstained = topDesignKey == CalibratedStudyDesign.UNKNOWN ||
      primaryProb < 0.50 ||
      (margin < 0.14 && runnerUpProb >= 0.22)

    val resolvedCalibrated = if (abstained && (topDesignKey == CalibratedStudyDesign.UNKNOWN || margin < 0.08)) {
      CalibratedStudyDesign.UNKNOWN
    } else {
      topDesignKey
    }
    val mappedSub = if (resolvedCalibrated == CalibratedStudyDesign.UNKNOWN) {
      nonUnknownRanked.firstOrNull()?.key?.mappedSubDesign ?: AuditSubDesign.GENERAL_EMPIRICAL
    } else {
      resolvedCalibrated.mappedSubDesign
    }

    val alternatives = nonUnknownRanked
      .filter { it.key != resolvedCalibrated }
      .take(2)
      .map { (d, p) ->
        DesignCandidate(
          design = d.mappedSubDesign,
          label = d.displayName,
          confidence = p.coerceIn(0.01, 0.95),
          evidenceTerms = qualifiedScores[d]?.second.orEmpty(),
          calibratedDesign = d
        )
      }

    val suppressed = mutableListOf<String>()
    if (abstained) {
      suppressed.add("design-sensitive methodological FAIL escalations")
    }
    if (resolvedCalibrated == CalibratedStudyDesign.CASE_CONTROL ||
      alternatives.any { it.calibratedDesign == CalibratedStudyDesign.CASE_CONTROL && it.confidence >= 0.20 }
    ) {
      suppressed.add("incidence-rate and risk-ratio direct-risk checks")
    }
    if (resolvedCalibrated == CalibratedStudyDesign.CROSS_SECTIONAL) {
      suppressed.add("longitudinal follow-up and cohort attrition checks")
    }
    if (resolvedCalibrated == CalibratedStudyDesign.SYSTEMATIC_REVIEW_NARRATIVE) {
      suppressed.add("pooled heterogeneity (I²/τ²) and forest-plot checks")
    }
    if (resolvedCalibrated == CalibratedStudyDesign.DESCRIPTIVE_PANEL) {
      suppressed.add("causal identification (DiD/IV/RDD) requirements")
    }
    if (resolvedCalibrated == CalibratedStudyDesign.ALGORITHMIC_THEORY) {
      suppressed.add("measure-theoretic integral interchange requirements")
    }

    return DesignClassificationResult(
      primaryDesign = mappedSub,
      primaryLabel = resolvedCalibrated.displayName,
      primaryConfidence = primaryProb,
      alternatives = alternatives,
      evidence = qualifiedScores[topDesignKey]?.second.orEmpty(),
      abstained = abstained,
      suppressedChecks = suppressed.distinct(),
      calibratedDesign = resolvedCalibrated,
      margin = margin
    )
  }

  /**
   * Labeled corpus for evaluating design-classifier calibration (accuracy, high-confidence error rate, ECE).
   */
  val defaultLabeledDesignCorpus: List<LabeledDesignExample> = listOf(
    LabeledDesignExample("D-01", "Abstract\nA double-blind placebo-controlled randomized controlled trial (CONSORT) with allocation concealment randomly assigned 240 patients to Drug A vs placebo.", CalibratedStudyDesign.RCT),
    LabeledDesignExample("D-02", "Abstract\nA prospective cohort study (STROBE) followed 1,200 adults over 5 years to evaluate statin exposure and stroke.", CalibratedStudyDesign.PROSPECTIVE_COHORT),
    LabeledDesignExample("D-03", "Abstract\nA retrospective cohort study (STROBE) used propensity score matching on 3,400 EHR records.", CalibratedStudyDesign.RETROSPECTIVE_COHORT),
    LabeledDesignExample("D-04", "Abstract\nA matched case-control study (STROBE) compared 250 lung cancer cases with 500 matched controls reporting odds ratios.", CalibratedStudyDesign.CASE_CONTROL),
    LabeledDesignExample("D-05", "Abstract\nA cross-sectional national health survey (STROBE) evaluated hypertension prevalence across 12,000 adults.", CalibratedStudyDesign.CROSS_SECTIONAL),
    LabeledDesignExample("D-06", "Abstract\nA time-to-event study estimated Cox proportional hazards and Kaplan-Meier curves with Fine-Gray competing risks.", CalibratedStudyDesign.SURVIVAL_TIME_TO_EVENT),
    LabeledDesignExample("D-07", "Abstract\nA diagnostic accuracy study (STARD, QUADAS) evaluated a rapid assay against the biopsy reference standard for sensitivity and specificity.", CalibratedStudyDesign.DIAGNOSTIC_ACCURACY),
    LabeledDesignExample("D-08", "Abstract\nA systematic review (PRISMA, PROSPERO) conducted a qualitative narrative synthesis of 18 studies without pooling.", CalibratedStudyDesign.SYSTEMATIC_REVIEW_NARRATIVE),
    LabeledDesignExample("D-09", "Abstract\nA quantitative meta-analysis pooled 14 trials using a DerSimonian-Laird random-effects model with forest plot and I² = 62%.", CalibratedStudyDesign.META_ANALYSIS),
    LabeledDesignExample("D-10", "Abstract\nA staggered adoption difference-in-differences panel data study tested parallel trends with two-way fixed effects.", CalibratedStudyDesign.CAUSAL_ECONOMETRIC_PANEL),
    LabeledDesignExample("D-11", "Abstract\nA deep learning Transformer neural network was trained with 5-fold cross-validation, hyperparameter tuning, and ablation on a holdout split.", CalibratedStudyDesign.ML_PREDICTION_BENCHMARK),
    LabeledDesignExample("D-12", "Theorem 1. In a complete Banach space, Lemma 2 establishes convergence by dominated convergence. Proof. By induction... Q.E.D.", CalibratedStudyDesign.FORMAL_MATHEMATICAL_PROOF)
  )

  fun evaluateDesignClassifierCalibration(
    corpus: List<LabeledDesignExample> = defaultLabeledDesignCorpus
  ): ClassifierCalibrationReport {
    if (corpus.isEmpty()) {
      return ClassifierCalibrationReport(0, 1.0, 0, 0, 0.0, 0.0, 0.0)
    }
    var correct = 0
    var highConfCount = 0
    var highConfErrors = 0
    var abstainedCount = 0
    val binCounts = IntArray(5)
    val binConfSum = DoubleArray(5)
    val binAccSum = DoubleArray(5)

    for (ex in corpus) {
      val res = classifyDesignWithConfidence(ex.text, ex.expectedDesign.mappedSubDesign.parentArchetype)
      val isCorrect = res.calibratedDesign == ex.expectedDesign
      if (isCorrect) correct++
      if (res.abstained) abstainedCount++
      if (!res.abstained && res.primaryConfidence >= 0.70) {
        highConfCount++
        if (!isCorrect) highConfErrors++
      }
      val bin = ((res.primaryConfidence * 5.0).toInt()).coerceIn(0, 4)
      binCounts[bin]++
      binConfSum[bin] += res.primaryConfidence
      binAccSum[bin] += if (isCorrect) 1.0 else 0.0
    }

    var ece = 0.0
    for (b in 0 until 5) {
      if (binCounts[b] > 0) {
        val avgConf = binConfSum[b] / binCounts[b]
        val avgAcc = binAccSum[b] / binCounts[b]
        ece += (binCounts[b].toDouble() / corpus.size) * abs(avgAcc - avgConf)
      }
    }

    return ClassifierCalibrationReport(
      totalExamples = corpus.size,
      overallAccuracy = correct.toDouble() / corpus.size,
      highConfidenceCount = highConfCount,
      highConfidenceErrorCount = highConfErrors,
      highConfidenceErrorRate = if (highConfCount > 0) highConfErrors.toDouble() / highConfCount else 0.0,
      abstentionRate = abstainedCount.toDouble() / corpus.size,
      expectedCalibrationError = ece
    )
  }

  // ============================================================================
  // #5. COMPOUND EVIDENCE-TO-CLAIM KEY EXTRACTOR
  // ============================================================================
  fun extractCompoundKey(
    window: String,
    section: String,
    effectMeasure: String,
    adjustmentStatus: AdjustmentStatus,
    hierarchy: AnalysisHierarchy
  ): CompoundClaimKey {
    val w = window.lowercase(Locale.US)
    val population = when {
      Regex("""\b(?:pediatric|children|adolescent)\b""").containsMatchIn(w) -> "pediatric"
      Regex("""\b(?:elderly|older\s+adults?|geriatric|aged\s*>=?\s*65)\b""").containsMatchIn(w) -> "older_adults"
      Regex("""\b(?:icu|critically\s+ill|intensive\s+care)\b""").containsMatchIn(w) -> "icu"
      Regex("""\b(?:outpatient|community|general\s+population)\b""").containsMatchIn(w) -> "outpatient"
      Regex("""\b(?:inpatient|hospitalized)\b""").containsMatchIn(w) -> "inpatient"
      Regex("""\b(?:validation\s+cohort|external\s+cohort|test\s+set)\b""").containsMatchIn(w) -> "validation_cohort"
      Regex("""\b(?:derivation\s+cohort|training\s+cohort|training\s+set)\b""").containsMatchIn(w) -> "training_cohort"
      Regex("""\b(?:per[- ]protocol)\b""").containsMatchIn(w) -> "per_protocol"
      Regex("""\b(?:intention[- ]to[- ]treat|intent[- ]to[- ]treat|\bitt\b)\b""").containsMatchIn(w) -> "itt"
      Regex("""\b(?:female|women)\b""").containsMatchIn(w) && !w.contains("male") -> "female"
      Regex("""\b(?:male|men)\b""").containsMatchIn(w) && !w.contains("female") -> "male"
      else -> "unspecified"
    }

    val outcome = Regex(
      """\b(all-cause\s+mortality|cardiovascular\s+mortality|cardiovascular\s+death|mortality|hospitalization|readmission|stroke|myocardial\s+infarction|progression-free\s+survival|overall\s+survival|remission|relapse|infection|bleeding|hypertension|depression|symptom\s+severity|auroc|accuracy|f1|rmse|income|employment|gdp)\b""",
      RegexOption.IGNORE_CASE
    ).find(window)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)?.replace(Regex("""\s+"""), "_") ?: "unspecified"

    val timePoint = Regex(
      """\b(\d{1,3}[- ]?(?:day|week|month|year)s?|at\s+baseline|at\s+discharge|in-hospital|short-term|long-term)\b""",
      RegexOption.IGNORE_CASE
    ).find(window)?.groupValues?.getOrNull(1)?.lowercase(Locale.US)?.replace(Regex("""\s+"""), "_") ?: "unspecified"

    val subgroup = when {
      hierarchy == AnalysisHierarchy.SUBGROUP || w.contains("subgroup") || w.contains("stratum") -> {
        val tag = Regex("""(?:subgroup\s+of\s+|among\s+|in\s+)([a-z0-9\- ]{3,25})""", RegexOption.IGNORE_CASE)
          .find(window)?.groupValues?.getOrNull(1)?.trim()?.lowercase(Locale.US)?.replace(Regex("""\s+"""), "_")
        tag ?: "subgroup"
      }
      else -> "overall"
    }

    val estimator = effectMeasure.trim().lowercase(Locale.US).ifBlank { "unspecified" }
    return CompoundClaimKey(
      population = population,
      outcome = outcome,
      timePoint = timePoint,
      estimator = estimator,
      subgroup = subgroup,
      adjustmentStatus = adjustmentStatus
    )
  }

  // ============================================================================
  // MASTER PHASE 8 EVALUATOR
  // ============================================================================
  fun evaluate(
    rawText: String,
    sanitized: SanitizedTextResult,
    designClassification: DesignClassificationResult,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger,
    reconstructions: List<NumericalReconstructionResult>,
    coverageReport: DocumentCoverageReport = DocumentCoverageReport.unknown(),
    extractionConfidence: Int = coverageReport.extractionConfidence
  ): SemanticValidityReport {
    val text = sanitized.text
    val l = text.lowercase(Locale.US)
    val unknownCoverage = coverageReport.isCoverageUnknown || extractionConfidence == DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN

    val structuredUnsupported = buildStructuredUnsupportedItems(
      text = text,
      l = l,
      sanitized = sanitized,
      ledger = ledger,
      coverageReport = if (unknownCoverage) null else coverageReport
    )
    val estimandAssessments = buildAntiDogmaEstimandChecks(text, l, designClassification.primaryDesign)
    val denominatorGraph = buildDenominatorProvenanceGraph(text, l, ledger)
    val multiplicityAssessment = buildMultiplicityFamilyAssessment(l, ledger)
    val dofReport = buildResearcherDofReport(l)
    val missingDataAudit = buildSubstantiveMissingDataAudit(text, l)
    val falsificationChecks = buildFalsificationChecks(l, designClassification.primaryDesign)
    val effectScaleFindings = buildEffectScaleSanityChecks(text, l, ledger)
    val abstention = buildAbstentionPolicy(
      text = text,
      l = l,
      designClassification = designClassification,
      denominatorGraph = denominatorGraph,
      ledger = ledger,
      coverageReport = coverageReport,
      extractionConfidence = extractionConfidence,
      ocrNoise = sanitized.ocrNoiseDetected
    )
    val structuredMethodChecks = buildStructuredMethodChecks(
      designClassification = designClassification,
      missingDataAudit = missingDataAudit,
      dofReport = dofReport,
      falsificationChecks = falsificationChecks,
      effectScaleFindings = effectScaleFindings,
      unknownCoverage = unknownCoverage
    )

    return SemanticValidityReport(
      designClassification = designClassification,
      structuredUnsupported = structuredUnsupported,
      estimandAssessments = estimandAssessments,
      denominatorGraph = denominatorGraph,
      multiplicityAssessment = multiplicityAssessment,
      researcherDofReport = dofReport,
      missingDataAudit = missingDataAudit,
      falsificationChecks = falsificationChecks,
      effectScaleFindings = effectScaleFindings,
      abstentionAssessment = abstention,
      ocrNoiseDetected = sanitized.ocrNoiseDetected,
      duplicatedColumnsRemoved = sanitized.duplicatedColumnsRemoved,
      suspectedDuplications = sanitized.suspectedDuplications,
      structuredMethodChecks = structuredMethodChecks,
      strongConclusionsDisabledDueToUnknownCoverage = unknownCoverage
    )
  }

  private fun buildStructuredMethodChecks(
    designClassification: DesignClassificationResult,
    missingDataAudit: MissingDataAuditReport,
    dofReport: ResearcherDegreesOfFreedomReport,
    falsificationChecks: FalsificationCheckReport,
    effectScaleFindings: List<EffectScaleSanityFinding>,
    unknownCoverage: Boolean
  ): List<StructuredMethodologicalCheck> = buildList {
    // 1. Missing-data handling (keyword screening vs substantive imputation specification)
    val missStatus = when {
      unknownCoverage -> MethodologicalAssessmentStatus.NOT_ASSESSABLE
      missingDataAudit.substantiveAdequacy == "ADEQUATE" -> MethodologicalAssessmentStatus.DOCUMENTED_AND_ADEQUATE
      missingDataAudit.substantiveAdequacy == "PARTIAL_MI_UNDERSPECIFIED" -> MethodologicalAssessmentStatus.DOCUMENTED_BUT_INADEQUATE
      missingDataAudit.substantiveAdequacy == "INADEQUATE_COMPLETE_CASE" -> MethodologicalAssessmentStatus.POTENTIAL_RISK
      else -> MethodologicalAssessmentStatus.NOT_REPORTED
    }
    add(
      StructuredMethodologicalCheck(
        checkId = "SMC-MISSING",
        domain = "Missing-Data Mechanism & Imputation Specification",
        observedEvidence = missingDataAudit.summary,
        requiredEvidence = "Missingness rates by variable/group/outcome, auxiliary variables in imputation model, and MNAR sensitivity analysis.",
        assessmentStatus = missStatus,
        applicability = if (designClassification.primaryDesign == AuditSubDesign.FORMAL_THEOREM) "Not applicable to deductive proofs" else "Applicable to empirical studies with participant/record attrition",
        confidence = if (unknownCoverage) 0.30 else if (missingDataAudit.multipleImputationUsed) 0.72 else 0.58,
        reasonForAbstention = if (unknownCoverage) "Document coverage is UNKNOWN; missing-data tables may reside in unextracted pages." else if (missStatus == MethodologicalAssessmentStatus.NOT_REPORTED) "Imputation model cannot be reconstructed solely from manuscript wording." else null
      )
    )

    // 2. Analyst degrees of freedom (screening signal, not definitive proof of p-hacking)
    val dofStatus = when {
      designClassification.primaryDesign == AuditSubDesign.FORMAL_THEOREM -> MethodologicalAssessmentStatus.NOT_APPLICABLE
      unknownCoverage -> MethodologicalAssessmentStatus.NOT_ASSESSABLE
      dofReport.elevatedCount >= 2 -> MethodologicalAssessmentStatus.POTENTIAL_RISK
      dofReport.elevatedCount == 1 -> MethodologicalAssessmentStatus.POTENTIAL_RISK
      else -> MethodologicalAssessmentStatus.DOCUMENTED_AND_ADEQUATE
    }
    add(
      StructuredMethodologicalCheck(
        checkId = "SMC-DOF",
        domain = "Analyst Degrees of Freedom (11 Flexibility Dimensions)",
        observedEvidence = dofReport.summary,
        requiredEvidence = "Timestamped preregistration/SAP specifying covariate set, outlier rules, cutpoints, and primary model before unblinding.",
        assessmentStatus = dofStatus,
        applicability = "Applicable to confirmatory empirical analyses (screening signal only without SAP inspection)",
        confidence = if (unknownCoverage) 0.30 else 0.62,
        reasonForAbstention = if (unknownCoverage) "Coverage UNKNOWN; protocol/SAP references may be outside extracted text." else "Phrase-level flexibility cues are screening signals requiring protocol verification."
      )
    )

    // 3. Negative-control & falsification checks (applicability-dependent!)
    val falsifApplicable = falsificationChecks.applicableToDesign &&
      designClassification.calibratedDesign !in setOf(
        CalibratedStudyDesign.RCT,
        CalibratedStudyDesign.FORMAL_MATHEMATICAL_PROOF,
        CalibratedStudyDesign.ALGORITHMIC_THEORY,
        CalibratedStudyDesign.DIAGNOSTIC_ACCURACY,
        CalibratedStudyDesign.ML_PREDICTION_BENCHMARK,
        CalibratedStudyDesign.DESCRIPTIVE_PANEL
      )
    val falsifStatus = when {
      !falsifApplicable -> MethodologicalAssessmentStatus.NOT_APPLICABLE
      unknownCoverage -> MethodologicalAssessmentStatus.NOT_ASSESSABLE
      falsificationChecks.totalPresent >= 1 -> MethodologicalAssessmentStatus.DOCUMENTED_AND_ADEQUATE
      else -> MethodologicalAssessmentStatus.NOT_REPORTED
    }
    add(
      StructuredMethodologicalCheck(
        checkId = "SMC-FALSIF",
        domain = "Negative-Control & Falsification Checks",
        observedEvidence = if (!falsifApplicable) "Not applicable to ${designClassification.calibratedDesign.displayName}." else falsificationChecks.summary,
        requiredEvidence = "Pre-specified negative-control exposure/outcome, placebo date, or falsification endpoint with observed null behavior.",
        assessmentStatus = falsifStatus,
        applicability = if (falsifApplicable) "Applicable to observational/quasi-experimental causal inference" else "Not applicable to ${designClassification.calibratedDesign.displayName}",
        confidence = if (!falsifApplicable) 0.90 else if (unknownCoverage) 0.30 else 0.65,
        reasonForAbstention = if (unknownCoverage) "Coverage UNKNOWN; falsification tests may appear in omitted pages or supplement." else if (!falsifApplicable) "Design does not require observational negative controls." else null
      )
    )

    // 4. Effect-direction & scale interpretation
    val scaleStatus = when {
      effectScaleFindings.isNotEmpty() -> MethodologicalAssessmentStatus.DOCUMENTED_BUT_INADEQUATE
      unknownCoverage -> MethodologicalAssessmentStatus.NOT_ASSESSABLE
      else -> MethodologicalAssessmentStatus.DOCUMENTED_AND_ADEQUATE
    }
    add(
      StructuredMethodologicalCheck(
        checkId = "SMC-SCALE",
        domain = "Effect-Direction & Scale Sanity Checks",
        observedEvidence = if (effectScaleFindings.isEmpty()) "No scale-conflation language detected in extracted text." else "${effectScaleFindings.size} scale/interpretation flag(s): ${effectScaleFindings.joinToString(", ") { it.checkCode }}",
        requiredEvidence = "Consistent ratio vs. additive scale interpretation, baseline risk alongside RRR, and interaction p-values for subgroup claims.",
        assessmentStatus = scaleStatus,
        applicability = "Applicable whenever quantitative effect measures or subgroup comparisons are reported",
        confidence = if (effectScaleFindings.isNotEmpty()) 0.78 else if (unknownCoverage) 0.35 else 0.70,
        reasonForAbstention = if (unknownCoverage && effectScaleFindings.isEmpty()) "Coverage UNKNOWN; results text may be incomplete." else null
      )
    )
  }

  // ============================================================================
  // #1. SEPARATE EXTRACTION FAILURE FROM MANUSCRIPT OMISSION
  // ============================================================================
  private fun buildStructuredUnsupportedItems(
    text: String,
    l: String,
    sanitized: SanitizedTextResult,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger,
    coverageReport: DocumentCoverageReport?
  ): List<StructuredUnsupportedItem> {
    val items = mutableListOf<StructuredUnsupportedItem>()
    val active = ledger.claims.filter { !it.isFromReferenceOrCitation }

    if (coverageReport != null && coverageReport.omittedPages.isNotEmpty()) {
      items.add(
        StructuredUnsupportedItem(
          targetCheck = "Full-document verification",
          cause = UnsupportedCause.OUTSIDE_COVERAGE,
          detail = "Pages ${coverageReport.omittedPages.take(6).joinToString(",")} were outside extracted text coverage."
        )
      )
    }

    if (sanitized.ocrNoiseDetected) {
      items.add(
        StructuredUnsupportedItem(
          targetCheck = "Numeric precision / OCR values",
          cause = UnsupportedCause.PRESENT_BUT_UNREADABLE,
          detail = "OCR digit-substitution or replacement glyphs detected (e.g., O/0 or l/1 confusion); affected numbers are excluded from arithmetic-error penalties."
        )
      )
    }

    val hasImageOnlyTable = (coverageReport != null && coverageReport.warnings.any { it.contains("scanned", true) || it.contains("image", true) }) ||
      (l.contains("forest plot") && ledger.metaStudyEffects.size < 2)
    if (hasImageOnlyTable) {
      items.add(
        StructuredUnsupportedItem(
          targetCheck = "Table / Forest-plot numerical reconstruction",
          cause = UnsupportedCause.PRESENT_BUT_UNREADABLE,
          detail = "Data appear to be rendered inside scanned tables or plot images that text extraction cannot parse."
        )
      )
    }

    val ambiguousClaims = active.filter { it.parseStatus == ClaimParseStatus.AMBIGUOUS }
    if (ambiguousClaims.isNotEmpty()) {
      items.add(
        StructuredUnsupportedItem(
          targetCheck = "Interval interpretation",
          cause = UnsupportedCause.AMBIGUOUS_NOTATION,
          detail = "${ambiguousClaims.size} parenthesized range(s) lacked explicit CI/IQR/SD labels (${ambiguousClaims.first().sourceQuote.take(40)}).",
          pageNumber = ambiguousClaims.first().page
        )
      )
    }

    val defersToSupplement = Regex("""(?:supplementary\s+table|eTable|online\s+appendix|table\s+s\d)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    if (defersToSupplement && (ledger.twoByTwoTables.isEmpty() || active.none { it.standardError != null })) {
      items.add(
        StructuredUnsupportedItem(
          targetCheck = "Supplementary tables & SEs",
          cause = UnsupportedCause.OUTSIDE_COVERAGE,
          detail = "Manuscript points to external Supplementary Tables / Online Appendix not included in the supplied file."
        )
      )
    }

    if (l.contains("regression") && active.none { it.standardError != null } && !hasImageOnlyTable && !defersToSupplement) {
      items.add(
        StructuredUnsupportedItem(
          targetCheck = "Regression SE / Wald reconstruction",
          cause = UnsupportedCause.ABSENT_FROM_MANUSCRIPT,
          detail = "Regression estimates are discussed in text without reporting standard errors or variance-covariance terms."
        )
      )
    }

    if ((l.contains("odds ratio") || l.contains("risk ratio")) && ledger.twoByTwoTables.isEmpty() && !defersToSupplement && !hasImageOnlyTable) {
      items.add(
        StructuredUnsupportedItem(
          targetCheck = "2x2 contingency reconstruction",
          cause = UnsupportedCause.INSUFFICIENT_INPUTS,
          detail = "Relative ratio reported without raw arm event counts (a, b, c, d)."
        )
      )
    }

    return items
  }

  // ============================================================================
  // #3. ANTI-DOGMA ESTIMAND-FIRST METHOD CHECKS
  // ============================================================================
  private fun buildAntiDogmaEstimandChecks(
    text: String,
    l: String,
    design: AuditSubDesign
  ): List<EstimandMethodAssessment> {
    val out = mutableListOf<EstimandMethodAssessment>()

    if (l.contains("fine-gray") || l.contains("cause-specific") || l.contains("competing risk")) {
      out.add(
        EstimandMethodAssessment(
          methodTopic = "Competing Risks (Cause-Specific vs. Fine-Gray)",
          targetEstimand = if (l.contains("cause-specific")) "Etiologic cause-specific hazard rate among event-free individuals" else "Prognostic cumulative incidence / subdistribution hazard",
          requiredAssumptions = "Cause-specific Cox assumes non-informative censoring of competing events for hazard rate; Fine-Gray models subdistribution CIF directly.",
          assumptionsPlausible = "Depends on whether research question is etiologic (cause-specific) or prognostic/clinical risk prediction (Fine-Gray).",
          alternativeAnswersSameQuestion = false,
          verdictNote = "Fine-Gray and cause-specific hazards answer DIFFERENT clinical questions; do not penalize cause-specific HR for omitting Fine-Gray if the estimand is etiologic (or vice versa)."
        )
      )
    }

    if (design == AuditSubDesign.META_ANALYSIS) {
      val smallK = Regex("""\bk\s*=\s*([2-5])\b""").containsMatchIn(l)
      out.add(
        EstimandMethodAssessment(
          methodTopic = "Meta-Analysis Pooling (DL vs. REML / Hartung-Knapp)",
          targetEstimand = "Mean of random-effects distribution across heterogeneous studies",
          requiredAssumptions = "Exchangeable study effects, independent study sampling errors, compatible effect scales.",
          assumptionsPlausible = if (smallK) "Small k (<=5): Hartung-Knapp can yield overly narrow or ultra-wide CIs; REML/PM/DL trade off bias vs variance." else "Moderate-to-large k supports random-effects sensitivity comparison.",
          alternativeAnswersSameQuestion = true,
          verdictNote = if (smallK) "With small k, neither REML nor Hartung-Knapp is universally dominant; interpret τ² uncertainty cautiously." else "Compare DL, REML, and Paule-Mandel as sensitivity checks rather than treating one estimator as dogma."
        )
      )
    }

    if (l.contains("multiple imputation") || l.contains("mice")) {
      out.add(
        EstimandMethodAssessment(
          methodTopic = "Missing Data (Multiple Imputation vs. MNAR)",
          targetEstimand = "Population parameter under hypothetical complete observation",
          requiredAssumptions = "Missing At Random (MAR) conditional on imputation variables AND uncongeniality-free imputation model.",
          assumptionsPlausible = if (l.contains("mnar") || l.contains("tipping")) "MNAR sensitivity explicitly evaluated." else "MI is NOT automatically valid under MNAR or when outcome/auxiliary predictors are omitted.",
          alternativeAnswersSameQuestion = true,
          verdictNote = "Multiple imputation is valid under MAR with a correctly specified model, NOT automatically valid under arbitrary MNAR mechanisms."
        )
      )
    }

    if (l.contains("fisher") || l.contains("chi-square") || l.contains("χ²")) {
      out.add(
        EstimandMethodAssessment(
          methodTopic = "2x2 Contingency Inference (Fisher Exact vs. χ²)",
          targetEstimand = "Independence of exposure and outcome in 2x2 table",
          requiredAssumptions = "Fisher conditions on both margins (conservative under unfixed margins); Pearson χ² relies on asymptotic cell counts.",
          assumptionsPlausible = "Both are valid depending on sampling design and cell sizes; Fisher is NOT universally superior to χ² or Barnard/Boschloo exact tests.",
          alternativeAnswersSameQuestion = true,
          verdictNote = "Evaluate choice by cell sparsity and sampling margins rather than assuming Fisher's exact test always dominates χ²."
        )
      )
    }

    if (l.contains("wild cluster bootstrap") || l.contains("clustered standard")) {
      out.add(
        EstimandMethodAssessment(
          methodTopic = "Clustered Inference (CRVE vs. Wild Cluster Bootstrap)",
          targetEstimand = "Variance of regression estimator under within-cluster correlation",
          requiredAssumptions = "Wild cluster bootstrap requires approximate cluster homogeneity / few treated clusters caveat (MacKinnon-Webb).",
          assumptionsPlausible = "Wild cluster bootstrap can still misbehave with very few treated clusters (e.g., 1–3 treated clusters).",
          alternativeAnswersSameQuestion = true,
          verdictNote = "Wild cluster bootstrap is helpful for moderate-small G, not a universal cure when treated clusters are severely unbalanced."
        )
      )
    }

    if (design == AuditSubDesign.FORMAL_THEOREM && (l.contains("integral") || l.contains("∫"))) {
      val hasActualInterchange = l.contains("interchang") || (l.contains("lim") && l.contains("integral"))
      out.add(
        EstimandMethodAssessment(
          methodTopic = "Integral Regularity (DCT / MCT / Fubini-Tonelli)",
          targetEstimand = "Validity of analytic manipulation involving integrals",
          requiredAssumptions = if (hasActualInterchange) "Passing limits/derivatives/sums across an integral requires an explicit interchange theorem." else "Elementary finite integrals over continuous bounded functions do NOT require invoking DCT/MCT/Fubini.",
          assumptionsPlausible = if (hasActualInterchange) "Verify explicit dominating function or non-negativity." else "No limit-integral interchange detected; do not demand DCT/MCT dogmatically.",
          alternativeAnswersSameQuestion = true,
          verdictNote = if (hasActualInterchange) "Limit-integral interchange requires explicit justification." else "Ordinary integrals without limit/sum exchange do not require DCT/MCT/Fubini."
        )
      )
    }

    return out
  }

  // ============================================================================
  // #6. DENOMINATOR PROVENANCE GRAPH
  // ============================================================================
  private fun buildDenominatorProvenanceGraph(
    text: String,
    l: String,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): DenominatorProvenanceGraph {
    val units = mutableSetOf<DenominatorUnitLevel>()
    if (Regex("""\b\d+\s+(?:patients?|participants?|subjects?|individuals?|adults?|children|people)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
      units.add(DenominatorUnitLevel.PERSON)
    }
    if (Regex("""\b\d+\s+eyes?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) units.add(DenominatorUnitLevel.EYE)
    if (Regex("""\b\d+\s+(?:lesions?|tumors?|nodules?|scans?|images?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) units.add(DenominatorUnitLevel.LESION)
    if (Regex("""\b\d+\s+(?:visits?|encounters?|admissions?|hospitalizations?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) units.add(DenominatorUnitLevel.VISIT)
    if (Regex("""\b\d+\s+(?:samples?|specimens?|biopsies)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) units.add(DenominatorUnitLevel.SAMPLE)
    if (Regex("""\b\d+\s+(?:events?|deaths?|episodes?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) units.add(DenominatorUnitLevel.EVENT)
    if (Regex("""\b(?:\d+\s+(?:studies|trials|cohorts)|k\s*=\s*\d+)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) units.add(DenominatorUnitLevel.STUDY)
    if (Regex("""\b\d+\s+(?:effect\s+estimates?|effect\s+sizes?|comparisons?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) units.add(DenominatorUnitLevel.EFFECT_ESTIMATE)
    if (units.isEmpty()) units.add(DenominatorUnitLevel.UNSPECIFIED)

    val popType = when {
      l.contains("modified intention-to-treat") || l.contains("modified intent-to-treat") || Regex("""\bmitt\b""").containsMatchIn(l) -> AnalysisPopulationType.MODIFIED_ITT
      l.contains("intention-to-treat") || l.contains("intent-to-treat") || Regex("""\bitt\b""").containsMatchIn(l) -> AnalysisPopulationType.INTENTION_TO_TREAT
      l.contains("per-protocol") || l.contains("per protocol") -> AnalysisPopulationType.PER_PROTOCOL
      l.contains("as-treated") || l.contains("as treated") -> AnalysisPopulationType.AS_TREATED
      l.contains("complete-case") || l.contains("complete case") -> AnalysisPopulationType.COMPLETE_CASE
      else -> AnalysisPopulationType.UNSPECIFIED
    }

    val stageOrder = listOf("eligible", "enrolled", "randomized", "treated", "followed", "analyzed")
    val stageRegexes = mapOf(
      "eligible" to Regex("""\b(?:(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?|individuals?)\s+(?:were\s+)?(?:screened|assessed\s+for\s+eligibility|eligible)|eligible\s*(?:cohort|sample)?\s*(?:\(?\s*[Nn]\s*=\s*|\s*=\s*)(\d{1,3}(?:,\d{3})*|\d{2,6}))""", RegexOption.IGNORE_CASE),
      "enrolled" to Regex("""\b(?:enrolled\s+(?:[Nn]\s*=\s*)?(\d{1,3}(?:,\d{3})*|\d{2,6})|(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?|eyes?|lesions?)\s+(?:were\s+)?enrolled)\b""", RegexOption.IGNORE_CASE),
      "randomized" to Regex("""\b(?:randomiz\w+\s+(?:[Nn]\s*=\s*)?(\d{1,3}(?:,\d{3})*|\d{2,6})|(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?)\s+(?:were\s+)?randomiz\w+)\b""", RegexOption.IGNORE_CASE),
      "treated" to Regex("""\b(?:(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?)\s+(?:received\s+(?:treatment|intervention|study\s+drug)|were\s+treated)|treated\s*\(\s*[Nn]\s*=\s*(\d{1,3}(?:,\d{3})*|\d{2,6})\s*\))""", RegexOption.IGNORE_CASE),
      "followed" to Regex("""\b(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?)\s+(?:completed\s+follow-?up|were\s+followed(?:\s+up)?|remained\s+at\s+follow-?up)\b""", RegexOption.IGNORE_CASE),
      "analyzed" to Regex("""\b(?:analyz\w+\s+(?:[Nn]\s*=\s*)?(\d{1,3}(?:,\d{3})*|\d{2,6})|(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?|eyes?|lesions?|visits?)\s+(?:were\s+)?(?:analyzed|analysed|included\s+in\s+(?:the\s+)?(?:primary\s+)?analysis))\b""", RegexOption.IGNORE_CASE)
    )

    val stages = linkedMapOf<String, Int>()
    for (st in stageOrder) {
      val m = stageRegexes[st]?.find(text)
      val rawNum = m?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.replace(",", "")?.toIntOrNull()
      if (rawNum != null && rawNum > 0) {
        stages[st] = rawNum
      }
    }
    // Supplement from parser sampleFlowRecords if a stage wasn't caught directly
    ledger.sampleFlowRecords.forEach { rec ->
      when (rec.stage) {
        "SCREENED" -> stages.putIfAbsent("eligible", rec.n)
        "ENROLLED", "TOTAL_N" -> stages.putIfAbsent("enrolled", rec.n)
        "ANALYZED" -> stages.putIfAbsent("analyzed", rec.n)
      }
    }

    val exclusionReason = when {
      l.contains("lost to follow") -> "loss to follow-up"
      l.contains("withdrew") || l.contains("withdrawal") -> "participant withdrawal"
      l.contains("missing data") || l.contains("incomplete") -> "missing/incomplete data"
      l.contains("protocol deviation") || l.contains("non-adherent") -> "protocol deviation / non-adherence"
      l.contains("ineligible") || l.contains("did not meet") -> "ineligibility"
      else -> "not explicitly itemized in text"
    }

    val primaryUnit = units.firstOrNull { it != DenominatorUnitLevel.UNSPECIFIED } ?: DenominatorUnitLevel.UNSPECIFIED
    val transitions = mutableListOf<DenominatorTransitionEdge>()
    val orderedFound = stageOrder.filter { it in stages }
    for (i in 0 until orderedFound.size - 1) {
      val fromSt = orderedFound[i]
      val toSt = orderedFound[i + 1]
      val fromN = stages[fromSt] ?: continue
      val toN = stages[toSt] ?: continue
      val lost = fromN - toN
      transitions.add(
        DenominatorTransitionEdge(
          fromStage = fromSt,
          toStage = toSt,
          fromN = fromN,
          toN = toN,
          nLost = lost,
          exclusionReason = if (lost == 0) "no loss (100% retained)" else if (lost < 0) "IMPOSSIBLE INCREASE (+${-lost})" else exclusionReason,
          unitLevel = primaryUnit,
          populationType = popType
        )
      )
    }

    val mixedWarning = if (units.filter { it != DenominatorUnitLevel.UNSPECIFIED }.size >= 2 &&
      (DenominatorUnitLevel.PERSON in units && (DenominatorUnitLevel.EYE in units || DenominatorUnitLevel.LESION in units || DenominatorUnitLevel.VISIT in units || DenominatorUnitLevel.EVENT in units))
    ) {
      "Multiple denominator unit levels detected (${units.filter { it != DenominatorUnitLevel.UNSPECIFIED }.joinToString(" + ") { it.label }}); verify clustered/repeated-unit correlation and never equate observation-level N with person-level N."
    } else null

    return DenominatorProvenanceGraph(
      unitLevelsDetected = units,
      populationType = popType,
      stages = stages,
      transitions = transitions,
      unitLevelConflictWarning = mixedWarning
    )
  }

  // ============================================================================
  // #7. MULTIPLICITY-FAMILY UNCERTAINTY
  // ============================================================================
  private fun buildMultiplicityFamilyAssessment(
    l: String,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): MultiplicityFamilyAssessment {
    val activeP = ledger.claims.filter { !it.isFromReferenceOrCitation && it.pValue != null && it.testingFamily != "Baseline" }
    val hasExplicitFamily = l.contains("bonferroni") || l.contains("holm") || l.contains("benjamini") ||
      l.contains("fdr") || l.contains("hierarchical testing") || l.contains("gatekeeping") ||
      l.contains("family-wise") || l.contains("multiplicity") || l.contains("primary endpoint") || l.contains("primary outcome")

    val distinctOutcomes = activeP.map { it.compoundKey?.outcome ?: it.outcome }.filter { it.isNotBlank() && it != "unspecified" }.distinct().size
    val distinctTimePoints = activeP.map { it.compoundKey?.timePoint ?: "" }.filter { it.isNotBlank() && it != "unspecified" }.distinct().size
    val distinctSubgroups = activeP.map { it.compoundKey?.subgroup ?: "overall" }.distinct().size
    val hasHierarchy = activeP.any { it.hierarchy != AnalysisHierarchy.UNSPECIFIED }
    val hasPrespec = l.contains("prespecified") || l.contains("pre-specified") || l.contains("protocol") || l.contains("preregist")

    val dims = buildList {
      if (distinctOutcomes > 0) add("outcomes($distinctOutcomes)")
      if (distinctTimePoints > 0) add("time_points($distinctTimePoints)")
      if (distinctSubgroups > 1) add("subgroups($distinctSubgroups)")
      if (hasHierarchy) add("primary_vs_exploratory")
      if (hasPrespec) add("prespecified_vs_posthoc")
    }

    // Refuse to manufacture Bonferroni/FDR when testing family boundaries are ambiguous!
    val familyReconstructable = hasExplicitFamily && (hasHierarchy || activeP.groupBy { it.testingFamily }.values.any { it.size >= 2 && it.first().testingFamily != "Body" && it.first().testingFamily != "Results" })
    val inflationRisk = activeP.size >= 4 && !l.contains("bonferroni") && !l.contains("holm") && !l.contains("benjamini") && !l.contains("fdr") && !l.contains("gatekeeping")

    val msg = when {
      activeP.size < 3 -> "Single or few hypothesis tests (${activeP.size} active p-values); acute multiplicity risk not observed."
      !familyReconstructable ->
        "Multiplicity family cannot be reconstructed from the manuscript (${activeP.size} p-values across ungrouped or mixed outcomes/stages). Potential inflation risk: ${if (inflationRisk) "yes" else "uncertain"}. Corrected p-values: not computable."
      inflationRisk ->
        "Explicit testing family reconstructed (${dims.joinToString(", ").ifBlank { "section-scoped" }}); potential inflation risk across ${activeP.size} unadjusted tests."
      else ->
        "Testing family & multiplicity handling explicitly documented (${dims.joinToString(", ").ifBlank { "protocol-defined" }})."
    }

    return MultiplicityFamilyAssessment(
      familyReconstructable = familyReconstructable,
      potentialInflationRisk = inflationRisk,
      correctedPValuesComputable = familyReconstructable && activeP.size >= 4,
      observedTestCount = activeP.size,
      modeledDimensions = dims,
      statusMessage = msg
    )
  }

  // ============================================================================
  // #8. ANALYST DEGREES-OF-FREEDOM AUDIT (11 FLEXIBILITY DIMENSIONS)
  // ============================================================================
  private fun buildResearcherDofReport(l: String): ResearcherDegreesOfFreedomReport {
    val hasPrereg = l.contains("preregist") || l.contains("pre-regist") || l.contains("prospero") || l.contains("nct") || l.contains("statistical analysis plan")
    fun check(name: String, lockedCue: Boolean, elevatedCue: Boolean, noteLocked: String, noteOpen: String): FlexibilityDimensionCheck {
      val risk = when {
        elevatedCue -> "ELEVATED"
        lockedCue || hasPrereg -> "LOCKED"
        else -> "MODERATE"
      }
      return FlexibilityDimensionCheck(name, risk, if (risk == "LOCKED") noteLocked else noteOpen)
    }

    val dims = listOf(
      check("Outcome definitions", l.contains("primary outcome") || l.contains("primary endpoint"), l.contains("composite") && !hasPrereg, "Primary outcome explicitly defined.", "Composite or unstated outcome definition increases selection flexibility."),
      check("Exclusion rules", l.contains("inclusion") && l.contains("exclusion"), l.contains("excluded") && !l.contains("prespecified"), "Eligibility/exclusion criteria specified.", "Post-hoc exclusion rules could shift the analytical sample."),
      check("Covariate selection", l.contains("dag") || l.contains("directed acyclic") || l.contains("prespecified covariate"), l.contains("stepwise") || l.contains("backward elimination") || l.contains("p < 0.20"), "Adjustment covariates justified a priori.", "Data-driven covariate selection (e.g. stepwise) inflates variance and Type I error."),
      check("Transformations", l.contains("log-transform") && hasPrereg, l.contains("log-transform") || l.contains("box-cox"), "Outcome scale prespecified.", "Post-hoc variable transformations can alter significance."),
      check("Subgroup selection", l.contains("prespecified subgroup") || l.contains("pre-specified subgroup"), l.contains("subgroup") && (l.contains("post-hoc") || l.contains("post hoc") || !l.contains("interaction")), "Subgroups prespecified.", "Exploratory subgroups without interaction tests create high fishing risk."),
      check("Time windows", l.contains("landmark") || hasPrereg, l.contains("post-hoc") && l.contains("follow-up"), "Follow-up window fixed a priori.", "Follow-up/time-window cutoffs should be verified against protocol."),
      check("Stopping rules", l.contains("o'brien-fleming") || l.contains("alpha-spending") || l.contains("dsmb") || l.contains("group sequential"), l.contains("stopped early") || l.contains("interim analysis") && !l.contains("alpha-spending"), "Interim stopping boundaries documented.", "Unadjusted interim looks or early stopping inflate effect estimates."),
      check("Imputation methods", l.contains("multiple imputation") && l.contains("sensitivity"), l.contains("last observation carried forward") || l.contains("locf") || l.contains("mean imputation"), "Imputation + sensitivity documented.", "Ad-hoc single imputation (LOCF/mean) introduces analyst bias."),
      check("Outlier treatment", l.contains("sensitivity excluding outliers") || l.contains("winsoriz"), l.contains("outliers were removed") || l.contains("excluded outliers"), "Outlier sensitivity reported.", "Unjustified outlier removal can manufacture significance."),
      check("Model variants", l.contains("sensitivity analysis") || l.contains("specification curve") || l.contains("multiverse"), l.contains("best-fitting model") || l.contains("selected the model"), "Alternative model specifications reported.", "Single unreported model selection path increases researcher DoF."),
      check("Threshold selection", l.contains("youden") && l.contains("external") || l.contains("prespecified cutoff"), l.contains("optimal cutoff") || l.contains("dichotomized at") || l.contains("median split"), "Cutoffs prespecified or externally validated.", "Data-driven cutoff dichotomization inflates apparent discrimination/effects.")
    )

    val elevated = dims.count { it.riskLevel == "ELEVATED" }
    val summary = if (elevated > 0) {
      "$elevated of 11 flexibility paths show elevated analyst degrees of freedom: ${dims.filter { it.riskLevel == "ELEVATED" }.joinToString(", ") { it.dimension }}."
    } else if (hasPrereg) {
      "Protocol/preregistration constrains major analyst degrees of freedom (0 elevated flags across 11 dimensions)."
    } else {
      "No overt data-dredging cues detected, though absence of a timestamped SAP leaves moderate flexibility across model/cutoff choices."
    }
    return ResearcherDegreesOfFreedomReport(dims, elevated, summary)
  }

  // ============================================================================
  // #9. SUBSTANTIVE MISSING-DATA AUDIT
  // ============================================================================
  private fun containsAffirmative(l: String, vararg phrases: String): Boolean {
    val negBefore = Regex("""\b(?:without|no|not|never|lacked|lacking|omitted|did\s+not|were\s+not|was\s+not|failed\s+to)\b(?:\s+\w+){0,5}\s*$""")
    val negAfter = Regex("""^\s*(?:\w+\s+){0,3}\b(?:were\s+not|was\s+not|not\s+assessed|not\s+conducted|not\s+performed|not\s+reported|absent|omitted)\b""")
    return phrases.any { phrase ->
      var searchFrom = 0
      var found = false
      while (searchFrom < l.length) {
        val idx = l.indexOf(phrase, searchFrom)
        if (idx < 0) break
        val before = l.substring((idx - 45).coerceAtLeast(0), idx)
        val after = l.substring((idx + phrase.length).coerceAtMost(l.length), (idx + phrase.length + 35).coerceAtMost(l.length))
        if (!negBefore.containsMatchIn(before) && !negAfter.containsMatchIn(after)) {
          found = true
          break
        }
        searchFrom = idx + phrase.length
      }
      found
    }
  }

  private fun buildSubstantiveMissingDataAudit(text: String, l: String): MissingDataAuditReport {
    val byVar = Regex("""missing(?:ness)?\s+(?:by|for|in)\s+(?:each\s+)?(?:variable|covariate|baseline)""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
      l.contains("missing data per variable")
    val byGroup = Regex("""(?:missing(?:ness)?|lost\s+to\s+follow-?up|attrition)\s+(?:by|across|between)\s+(?:treatment|exposure|arm|group)""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
      Regex("""\d+%\s+in\s+(?:the\s+)?(?:treatment|intervention)\s+(?:arm|group)[^.\n]{0,40}\d+%\s+in\s+(?:the\s+)?(?:control|placebo)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    val byOutcome = l.contains("missing outcome") || l.contains("outcome missingness")
    val reasons = containsAffirmative(l, "reasons for missing", "reason for withdrawal", "lost to follow-up due to", "consort")
    val diffLoss = containsAffirmative(l, "differential attrition", "differential loss") || byGroup
    val miUsed = containsAffirmative(l, "multiple imputation", "mice", "chained equations")
    val miSpec = miUsed && (containsAffirmative(l, "auxiliary") || (containsAffirmative(l, "imputation model") && l.contains("outcome") && (l.contains("treatment") || l.contains("exposure"))))
    val mnarSens = containsAffirmative(l, "mnar", "tipping-point", "tipping point", "pattern-mixture", "delta-adjusted", "not missing at random")
    val completeCase = containsAffirmative(l, "complete-case", "complete case", "listwise")

    val adequacy = when {
      miUsed && miSpec && mnarSens -> "ADEQUATE"
      miUsed && (!miSpec || !mnarSens) -> "PARTIAL_MI_UNDERSPECIFIED"
      completeCase -> "INADEQUATE_COMPLETE_CASE"
      byVar || byGroup || reasons -> "PARTIAL_REPORTING"
      else -> "NOT_REPORTED"
    }

    val summary = when (adequacy) {
      "ADEQUATE" -> "Multiple imputation specifies outcome/treatment/auxiliary predictors AND evaluates MNAR sensitivity."
      "PARTIAL_MI_UNDERSPECIFIED" -> "Multiple imputation is mentioned, but 'MI used' is NOT automatically adequate: " +
        listOfNotNull(
          if (!miSpec) "imputation model lacks explicit confirmation of outcome, treatment, and auxiliary variables" else null,
          if (!mnarSens) "no MNAR sensitivity analysis (tipping-point / pattern-mixture) reported" else null,
          if (!byGroup) "missingness by treatment/exposure group not reported" else null
        ).joinToString("; ") + "."
      "INADEQUATE_COMPLETE_CASE" -> "Relies on complete-case analysis without evaluating variable/group missingness or MNAR sensitivity."
      "PARTIAL_REPORTING" -> "Some missingness details reported; verify differential loss to follow-up and MAR/MNAR assumptions."
      else -> "Missingness by variable, arm, outcome, and reasons for missingness not reported in supplied text."
    }

    return MissingDataAuditReport(
      missingnessByVariableReported = byVar,
      missingnessByGroupReported = byGroup,
      missingnessByOutcomeReported = byOutcome,
      reasonsForMissingnessReported = reasons,
      differentialLossEvaluated = diffLoss,
      multipleImputationUsed = miUsed,
      imputationIncludesOutcomeTreatmentAux = miSpec,
      mnarSensitivityConducted = mnarSens,
      substantiveAdequacy = adequacy,
      summary = summary
    )
  }

  // ============================================================================
  // #10. NEGATIVE-CONTROL & FALSIFICATION CHECKS
  // ============================================================================
  private fun buildFalsificationChecks(l: String, design: AuditSubDesign): FalsificationCheckReport {
    val applicable = design in setOf(
      AuditSubDesign.OBSERVATIONAL_COHORT,
      AuditSubDesign.SURVIVAL_ANALYSIS,
      AuditSubDesign.ECONOMETRIC_PANEL
    )
    val ncOut = containsAffirmative(l, "negative-control outcome", "negative control outcome")
    val ncExp = containsAffirmative(l, "negative-control exposure", "negative control exposure")
    val placeboDate = containsAffirmative(l, "placebo date", "placebo timing", "in-time placebo", "pseudo-treatment")
    val preTreat = containsAffirmative(l, "pre-treatment outcome", "pretreatment outcome", "pre-trend", "event study")
    val falsif = containsAffirmative(l, "falsification", "placebo test", "placebo outcome")
    val altCtrl = containsAffirmative(l, "alternative control", "active comparator", "synthetic control")

    val present = listOf(ncOut, ncExp, placeboDate, preTreat, falsif, altCtrl).count { it }
    val summary = when {
      !applicable -> "Falsification/negative-control battery primarily applies to observational, survival, and causal econometric designs."
      present >= 2 -> "$present falsification / negative-control safeguards documented (strengthens causal credibility against residual confounding)."
      present == 1 -> "1 falsification check detected; adding negative-control outcomes/exposures or placebo timing would further strengthen causal identification."
      else -> "No negative-control outcomes, negative-control exposures, placebo dates, pre-treatment outcomes, or falsification endpoints detected in supplied text (reporting/design prompt, not an automatic error)."
    }

    return FalsificationCheckReport(
      applicableToDesign = applicable,
      negativeControlOutcome = ncOut,
      negativeControlExposure = ncExp,
      placeboDates = placeboDate,
      preTreatmentOutcome = preTreat,
      falsificationEndpoint = falsif,
      alternativeControlGroup = altCtrl,
      summary = summary
    )
  }

  // ============================================================================
  // #11. EFFECT-DIRECTION & SCALE SANITY CHECKS (8 FAILURE MODES)
  // ============================================================================
  private fun buildEffectScaleSanityChecks(
    text: String,
    l: String,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger
  ): List<EffectScaleSanityFinding> {
    val out = mutableListOf<EffectScaleSanityFinding>()
    val active = ledger.claims.filter { !it.isFromReferenceOrCitation }

    // 1. Ratio measure interpreted as additive percentage-point change
    val ratioAsAdditive = Regex(
      """(?:odds\s+ratio|risk\s+ratio|hazard\s+ratio|\bOR\b|\bRR\b|\bHR\b)\s*(?:of|=)\s*\d+(?:\.\d+)?(?:[^.\n]|\.\d){0,85}(?:percentage\s+points?|absolute\s+increase\s+of\s+\d+(?:\.\d+)?%|additive\s+increase)""",
      RegexOption.IGNORE_CASE
    ).find(text)
    if (ratioAsAdditive != null) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "RATIO_AS_ADDITIVE",
          title = "Multiplicative Ratio Interpreted as Additive Effect",
          detail = "A multiplicative ratio (OR/RR/HR) is described as an additive percentage-point or absolute change.",
          quote = ratioAsAdditive.value.trim().take(120),
          pageNumber = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, ratioAsAdditive.range.first)
        )
      )
    }

    // 2. Odds ratio described as risk ratio / "times more likely" (especially when outcome is not rare)
    val orAsRr = Regex(
      """(?:odds\s+ratio|\ba?OR\b)\s*(?:of|=)\s*(\d+(?:\.\d+)?)(?:[^.\n]|\.\d){0,85}(?:times\s+(?:more|less)\s+likely|times\s+higher\s+risk|higher\s+risk\s+of|relative\s+risk\s+of)""",
      RegexOption.IGNORE_CASE
    ).find(text)
    if (orAsRr != null) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "OR_AS_RR",
          title = "Odds Ratio Interpreted as Risk Ratio",
          detail = "Odds ratio is translated directly into 'times more likely / higher risk'; OR overstates RR whenever baseline outcome prevalence is non-rare (>10%).",
          quote = orAsRr.value.trim().take(120),
          pageNumber = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, orAsRr.range.first)
        )
      )
    }

    // 3. Relative risk reduction (RRR) without baseline risk or absolute risk reduction (ARR)
    val rrrMatch = Regex("""(?:relative\s+risk\s+reduction|\bRRR\b|reduced\s+(?:the\s+)?risk\s+by\s+\d+(?:\.\d+)?%)""", RegexOption.IGNORE_CASE).find(text)
    val hasBaselineOrAbs = l.contains("baseline risk") || l.contains("absolute risk") || l.contains("arr") || l.contains("nnt") || l.contains("risk difference") || ledger.twoByTwoTables.isNotEmpty()
    if (rrrMatch != null && !hasBaselineOrAbs) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "RRR_WITHOUT_BASELINE",
          title = "Relative Risk Reduction Without Baseline / Absolute Risk",
          detail = "Relative risk reduction is highlighted without reporting baseline control risk or absolute risk reduction (ARR/NNT).",
          quote = rrrMatch.value.trim().take(100),
          pageNumber = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, rrrMatch.range.first)
        )
      )
    }

    // 4. "No association" / "no effect" inferred from a wide confidence interval
    val wideCi = active.firstOrNull { c ->
      val lo = c.ciLower ?: return@firstOrNull false
      val hi = c.ciUpper ?: return@firstOrNull false
      c.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR") && lo < 0.70 && hi > 1.40
    }
    val noAssocMatch = Regex("""(?:no\s+association|no\s+effect|rules?\s+out\s+any\s+(?:effect|benefit|harm)|proves?\s+no\s+(?:effect|difference))""", RegexOption.IGNORE_CASE).find(text)
    if (wideCi != null && noAssocMatch != null) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "WIDE_CI_NO_ASSOCIATION",
          title = "'No Association' Inferred From Wide Confidence Interval",
          detail = "Confidence interval (${wideCi.sourceQuote}) is wide enough to include substantial benefit and harm; absence of significance does not establish no association.",
          quote = "${wideCi.sourceQuote} | ${noAssocMatch.value}",
          pageNumber = wideCi.page
        )
      )
    }

    // 5. Non-significance interpreted as equivalence / non-inferiority without equivalence margin
    val equivMatch = Regex("""(?:p\s*[=>]\s*0\.[0-9]+(?:[^.\n]|\.\d){0,75}(?:equivalent|equivalence|equally\s+effective|no\s+difference\s+between|similar\s+efficacy)|(?:equivalent|equally\s+effective)(?:[^.\n]|\.\d){0,75}p\s*[=>]\s*0\.[1-9]\d*)""", RegexOption.IGNORE_CASE).find(text)
    val hasEquivMargin = l.contains("equivalence margin") || l.contains("non-inferiority margin") || l.contains("tost") || l.contains("two one-sided")
    if (equivMatch != null && !hasEquivMargin) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "NONSIG_AS_EQUIVALENCE",
          title = "Non-Significance Interpreted as Equivalence",
          detail = "A non-significant superiority p-value is used to claim equivalence or equal efficacy without a prespecified equivalence/non-inferiority margin or TOST.",
          quote = equivMatch.value.trim().take(120),
          pageNumber = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, equivMatch.range.first)
        )
      )
    }

    // 6. Subgroup differences inferred from separate significance tests (Altman & Bland fallacy)
    val subSepMatch = Regex("""(?:significant\s+in\s+[a-z0-9\s\-]{2,30}\s*\(?p\s*[=<]\s*0\.0[0-4]\d*\)?(?:[^.\n]|\.\d){0,60}not\s+(?:statistically\s+)?significant\s+in|significant\s+in\s+[a-z\s]+\s+but\s+not\s+in)""", RegexOption.IGNORE_CASE).find(text)
    val hasInteractionTest = l.contains("p_interaction") || l.contains("p for interaction") || l.contains("interaction p") || l.contains("q_between") || l.contains("heterogeneity between subgroups")
    if (subSepMatch != null && !hasInteractionTest) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "SUBGROUP_SEPARATE_P",
          title = "Subgroup Difference Inferred From Separate Significance Tests",
          detail = "The difference between 'significant' and 'not significant' is not itself statistically significant (Altman & Bland); a formal interaction test is required.",
          quote = subSepMatch.value.trim().take(120),
          pageNumber = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, subSepMatch.range.first)
        )
      )
    }

    // 7. Interaction / effect modification claimed without an interaction test
    val interClaim = Regex("""(?:effect\s+modification|modified\s+the\s+effect|differed\s+significantly\s+by\s+(?:sex|age|subgroup|race)|stronger\s+effect\s+in\s+(?:men|women|older|younger))""", RegexOption.IGNORE_CASE).find(text)
    if (interClaim != null && !hasInteractionTest && !l.contains("interaction term") && !l.contains("interaction model")) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "INTERACTION_WITHOUT_TEST",
          title = "Effect Modification / Interaction Claimed Without Interaction Test",
          detail = "Effect heterogeneity across subgroups is claimed without reporting a formal interaction term or p-for-interaction.",
          quote = interClaim.value.trim().take(120),
          pageNumber = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, interClaim.range.first)
        )
      )
    }

    // 8. P-value ordering used as evidence of effect magnitude ("more strongly associated because smaller p")
    val pOrderMatch = Regex("""(?:stronger\s+(?:effect|association)(?:[^.\n]|\.\d){0,60}(?:smaller|lower)\s+p[- ]?value|(?:smaller|lower)\s+p[- ]?value(?:[^.\n]|\.\d){0,60}(?:larger|stronger|greater)\s+(?:effect|magnitude|impact)|more\s+significant\s*\(p\s*[=<])""", RegexOption.IGNORE_CASE).find(text)
    if (pOrderMatch != null) {
      out.add(
        EffectScaleSanityFinding(
          checkCode = "P_ORDERING_AS_MAGNITUDE",
          title = "P-Value Magnitude Conflated With Effect Size",
          detail = "Smaller p-values are interpreted as larger or stronger effects; p-values confound effect size with sample size and precision.",
          quote = pOrderMatch.value.trim().take(120),
          pageNumber = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(text, pOrderMatch.range.first)
        )
      )
    }

    return out
  }

  // ============================================================================
  // #12. PROPER ABSTENTION POLICY
  // ============================================================================
  private fun buildAbstentionPolicy(
    text: String,
    l: String,
    designClassification: DesignClassificationResult,
    denominatorGraph: DenominatorProvenanceGraph,
    ledger: StatisticalClaimParser.ParsedManuscriptLedger,
    coverageReport: DocumentCoverageReport?,
    extractionConfidence: Int,
    ocrNoise: Boolean
  ): AbstentionAssessment {
    val reasons = mutableListOf<AbstentionReason>()
    val words = text.trim().split(Regex("""\s+""")).count { it.isNotBlank() }

    if (designClassification.abstained) {
      reasons.add(
        AbstentionReason(
          "UNCERTAIN_DESIGN",
          "Study design classification confidence is low (${String.format(Locale.US, "%.2f", designClassification.primaryConfidence)}); abstaining from design-sensitive methodological penalties."
        )
      )
    }

    val hasMethods = Regex("""(?im)^\s*(?:\d+\.?\s+)?(methods?|materials?\s+and\s+methods?|statistical\s+analysis)\b""").containsMatchIn(text)
    val hasAbstract = Regex("""(?im)^\s*(?:\d+\.?\s+)?abstract\b""").containsMatchIn(text)
    if (hasAbstract && !hasMethods && words < 350) {
      reasons.add(
        AbstentionReason(
          "ABSTRACT_ONLY",
          "Only an abstract/short summary (~$words words) was supplied without full Methods or Results tables."
        )
      )
    }

    if (coverageReport != null && coverageReport.omittedPages.isNotEmpty()) {
      reasons.add(
        AbstentionReason(
          "MISSING_TABLES",
          "${coverageReport.omittedPages.size} page(s) were omitted from text extraction."
        )
      )
    }

    if (ocrNoise || (coverageReport != null && coverageReport.warnings.any { it.contains("scanned", true) || it.contains("image", true) })) {
      reasons.add(
        AbstentionReason(
          "UNREADABLE_FIGURES",
          "Scanned/image-only tables or OCR-corrupted numeric glyphs prevent reliable numerical verification."
        )
      )
    }

    if (denominatorGraph.hasMixedUnits) {
      reasons.add(
        AbstentionReason(
          "AMBIGUOUS_UNIT_OF_ANALYSIS",
          "Mixed unit levels (${denominatorGraph.unitLevelsDetected.filter { it != DenominatorUnitLevel.UNSPECIFIED }.joinToString(", ") { it.label }}) make cross-table denominator reconciliation ambiguous without explicit unit mapping."
        )
      )
    }

    val activeNums = ledger.claims.filter { !it.isFromReferenceOrCitation && it.pointEstimate != null }
    val allRoundedIntegers = activeNums.size >= 3 && activeNums.all { est ->
      val v = est.pointEstimate ?: 0.0
      abs(v - v.toInt().toDouble()) < 1e-9
    }
    if (allRoundedIntegers) {
      reasons.add(
        AbstentionReason(
          "ROUNDED_VALUES_ONLY",
          "Reported estimates appear heavily rounded to whole numbers; precision-sensitive arithmetic checks abstain to prevent rounding false positives."
        )
      )
    }

    val hasEstimandCue = l.contains("primary outcome") || l.contains("primary endpoint") || l.contains("estimand") ||
      l.contains("att") || l.contains("ate") || l.contains("odds ratio") || l.contains("hazard ratio") ||
      l.contains("risk ratio") || l.contains("auroc") || l.contains("theorem") || l.contains("pooled")
    if (!hasEstimandCue && words < 250) {
      reasons.add(
        AbstentionReason(
          "UNCLEAR_ESTIMAND",
          "Primary estimand cannot be uniquely identified from the supplied excerpt."
        )
      )
    }

    if (coverageReport == null || coverageReport.isCoverageUnknown || extractionConfidence == DocumentCoverageReport.EXTRACTION_CONFIDENCE_UNKNOWN) {
      reasons.add(
        AbstentionReason(
          "UNKNOWN_COVERAGE",
          "Document coverage and extraction confidence are UNKNOWN; strong methodological conclusions are disabled until coverage metadata is supplied."
        )
      )
    }

    val shouldAbstain = reasons.size >= 2 ||
      extractionConfidence in 1..2 ||
      reasons.any { it.code in setOf("ABSTRACT_ONLY", "MISSING_TABLES", "UNREADABLE_FIGURES", "UNKNOWN_COVERAGE") }

    val statement = if (shouldAbstain) {
      "This issue is potentially important, but the supplied evidence is insufficient to classify it definitively (${reasons.size} abstention guard(s) active)."
    } else {
      "Supplied evidence meets semantic completeness threshold (${reasons.size} minor advisory note(s))."
    }

    return AbstentionAssessment(
      shouldAbstainFromStrongConclusions = shouldAbstain,
      reasons = reasons,
      policyStatement = statement
    )
  }
}
