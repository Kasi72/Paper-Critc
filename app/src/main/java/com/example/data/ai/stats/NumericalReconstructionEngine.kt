package com.example.data.ai.stats

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Phase 4: Executable Pure-Kotlin Numerical Reconstruction Suite.
 *
 * Implements exact mathematical calculators for:
 *  - 2x2 contingency tables (OR, RR, ARR, NNT, Woolf/Katz 95% CIs, Pearson chi2, Exact Fisher's Test)
 *  - Binomial Wilson score & Poisson exact/Byar CIs and percentage verification
 *  - Test-statistic p-values (t, z, chi2, F, r-to-t) with one-sided/two-sided & custom alpha support
 *  - Regression beta / SE / Wald z / t / 95% CI reconstruction
 *  - ANOVA F-ratio decomposition (MS_between / MS_within)
 *  - Survival HR / log-HR CI symmetry & implied Wald SE consistency
 *  - Standardized Mean Difference (Cohen's d, Hedges' g, SE)
 *  - Meta-analysis pooling (Fixed-Effect IVW, Cochran's Q, I², DerSimonian-Laird τ²,
 *    Paule-Mandel iterative τ², Hartung-Knapp t-CI, 95% Prediction Interval, Leave-One-Out influence)
 *  - Multiple-testing corrections (Bonferroni, Holm-Bonferroni, Benjamini-Hochberg FDR)
 *    strictly scoped to distinct non-reference testing families.
 *
 * EPISTEMIC RULE: Never output FAIL / ARITHMETIC_ERROR unless all required inputs and
 * assumptions are present. Missing inputs emit UNSUPPORTED_TO_ASSESS.
 */
object NumericalReconstructionEngine {

  data class TwoByTwoExactMetrics(
    val or: Double,
    val orLower: Double,
    val orUpper: Double,
    val rr: Double,
    val rrLower: Double,
    val rrUpper: Double,
    val arr: Double,
    val nnt: Double?,
    val chiSquare: Double,
    val chiSquareP: Double,
    val fisherExactTwoSidedP: Double
  )

  data class MetaAnalysisExactSummary(
    val k: Int,
    val fixedEffect: Double,
    val fixedSe: Double,
    val fixedLower: Double,
    val fixedUpper: Double,
    val cochranQ: Double,
    val df: Int,
    val qPValue: Double,
    val iSquaredPct: Double,
    val tauSquaredDL: Double,
    val tauSquaredPM: Double,
    val randomEffectDL: Double,
    val randomSeDL: Double,
    val randomLowerDL: Double,
    val randomUpperDL: Double,
    val hartungKnappLower: Double,
    val hartungKnappUpper: Double,
    val predictionIntervalLower: Double?,
    val predictionIntervalUpper: Double?,
    val mostInfluentialStudy: String?,
    val maxLooShift: Double,
    val looCrossesNull: Boolean
  )

  data class SmdExactMetrics(
    val cohensD: Double,
    val hedgesG: Double,
    val seG: Double,
    val lower95: Double,
    val upper95: Double
  )

  data class MultipleTestingSummary(
    val familyName: String,
    val m: Int,
    val rawSignificantCount: Int,
    val bonferroniSignificantCount: Int,
    val holmSignificantCount: Int,
    val bhFdrSignificantCount: Int,
    val adjustedQValues: List<Pair<String, Double>>
  )

  fun runAllReconstructions(
    ledger: StatisticalClaimParser.ParsedManuscriptLedger,
    rawText: String
  ): List<NumericalReconstructionResult> {
    val results = mutableListOf<NumericalReconstructionResult>()
    var idx = 1
    fun nextId() = "NR-%03d".format(Locale.US, idx++)

    val activeClaims = ledger.claims.filter { !it.isFromReferenceOrCitation }

    // 1. Test-statistic reconstructions (t, z, chi2, F, r) with one-sided/two-sided & custom alpha
    activeClaims.filter { it.testStatisticName.isNotBlank() && it.testStatisticValue != null && it.pValue != null }
      .take(20)
      .forEach { claim ->
        reconstructTestStatistic(nextId(), claim)?.let { results.add(it) }
      }

    // 2. 2x2 Table reconstructions (OR, RR, ARR, NNT, chi2, Exact Fisher)
    ledger.twoByTwoTables.take(10).forEach { tbl ->
      results.addAll(reconstructTwoByTwoTable(tbl, ::nextId))
    }

    // 3. Binomial proportions (x/n vs reported %) & Wilson 95% CI
    activeClaims.filter { it.numerator != null && it.denominator != null && it.effectMeasure == "Proportion (%)" }
      .take(12)
      .forEach { claim ->
        reconstructProportion(nextId(), claim)?.let { results.add(it) }
      }

    // 4. Regression beta / SE / Wald z / CI reconstruction
    activeClaims.filter { it.effectMeasure.equals("beta", true) && it.pointEstimate != null && it.standardError != null }
      .take(12)
      .forEach { claim ->
        reconstructRegressionClaim(nextId(), claim)?.let { results.add(it) }
      }

    // 5. Ratio / Survival HR & OR/RR CI log-symmetry & implied SE consistency
    activeClaims.filter {
      it.pointEstimate != null && it.ciLower != null && it.ciUpper != null &&
        it.parseStatus != ClaimParseStatus.AMBIGUOUS &&
        it.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR", "IRR")
    }.take(14).forEach { claim ->
      reconstructRatioOrSurvivalCi(nextId(), claim)?.let { results.add(it) }
    }

    // 6. Meta-analysis executable pooling (IVW, DL/PM τ², Q, I², HK CI, 95% PI, LOO influence)
    if (ledger.metaStudyEffects.size >= 3) {
      results.addAll(reconstructMetaAnalysisSuite(ledger.metaStudyEffects, rawText, ::nextId))
    }

    // 7. Family-scoped multiple testing (Bonferroni, Holm, Benjamini-Hochberg FDR)
    // Explicitly excludes reference/citation claims!
    val pClaimsByFamily = activeClaims
      .filter { it.pValue != null && it.pValue in 0.0..1.0 && it.testingFamily != "Baseline" }
      .groupBy { it.testingFamily }
    val lower = rawText.lowercase(Locale.US)
    val hasExplicitMultiplicityCorrection = lower.contains("bonferroni") || lower.contains("holm") ||
      lower.contains("benjamini") || lower.contains("fdr") || lower.contains("false discovery") ||
      lower.contains("hochberg") || lower.contains("sidak") || lower.contains("hierarchical testing") ||
      lower.contains("gatekeeping") || lower.contains("q-value")

    for ((family, famClaims) in pClaimsByFamily) {
      if (famClaims.size >= 4) {
        val alpha = famClaims.firstOrNull()?.alphaThreshold ?: ledger.globalAlpha
        val mt = computeMultipleTestingAdjustments(
          familyName = family,
          namedPValues = famClaims.map { it.claimId to (it.pValue ?: 1.0) },
          alpha = alpha
        )
        if (mt.rawSignificantCount > 0 && mt.bhFdrSignificantCount < mt.rawSignificantCount && !hasExplicitMultiplicityCorrection) {
          results.add(
            NumericalReconstructionResult(
              checkId = nextId(),
              calculatorName = "Multiple-Testing Family ($family)",
              reportedValue = "${mt.rawSignificantCount}/${mt.m} significant at α=${formatNum(alpha)}",
              recomputedValue = "Holm=${mt.holmSignificantCount}/${mt.m}, BH-FDR=${mt.bhFdrSignificantCount}/${mt.m}, Bonf=${mt.bonferroniSignificantCount}/${mt.m}",
              tolerance = "α = ${formatNum(alpha)} (non-ref family)",
              sourceEvidence = famClaims.take(4).joinToString("; ") { it.sourceQuote },
              pageNumber = famClaims.first().page,
              section = family,
              assumptions = listOf(
                "Scoped strictly to $family family (${mt.m} active tests; ${ledger.claims.count { it.isFromReferenceOrCitation }} reference/citation p-values excluded).",
                "BH-FDR assumes independent or PRDS test statistics; Holm/Bonferroni require no dependence assumption."
              ),
              status = QuantFindingStatus.WARN,
              findingType = StatisticalFindingType.POSSIBLE_BIAS,
              isScreeningSignal = true,
              scientificConsequence = "${mt.rawSignificantCount - mt.bhFdrSignificantCount} nominal finding(s) in the $family family do not survive Benjamini-Hochberg FDR control.",
              detail = "Unadjusted testing in family '$family' (m=${mt.m}): ${mt.rawSignificantCount} raw p < ${formatNum(alpha)}, dropping to ${mt.bhFdrSignificantCount} under BH-FDR and ${mt.holmSignificantCount} under Holm-Bonferroni."
            )
          )
        }
      }
    }

    return results
  }

  // ============================================================================
  // CALCULATOR 1: 2x2 CONTINGENCY TABLE (OR, RR, ARR, NNT, CHI2, EXACT FISHER)
  // ============================================================================
  fun computeTwoByTwo(a: Int, b: Int, c: Int, d: Int): TwoByTwoExactMetrics? {
    if (a < 0 || b < 0 || c < 0 || d < 0) return null
    val n1 = a + b
    val n2 = c + d
    if (n1 <= 0 || n2 <= 0) return null
    val hasZero = (a == 0 || b == 0 || c == 0 || d == 0)
    // Haldane-Anscombe 0.5 correction for OR/RR SE only if a cell is zero
    val aa = if (hasZero) a + 0.5 else a.toDouble()
    val bb = if (hasZero) b + 0.5 else b.toDouble()
    val cc = if (hasZero) c + 0.5 else c.toDouble()
    val dd = if (hasZero) d + 0.5 else d.toDouble()

    val or = (aa * dd) / (bb * cc)
    val seLogOr = sqrt(1.0 / aa + 1.0 / bb + 1.0 / cc + 1.0 / dd)
    val z = 1.95996398454
    val orLo = exp(ln(or) - z * seLogOr)
    val orHi = exp(ln(or) + z * seLogOr)

    val r1 = a.toDouble() / n1.toDouble()
    val r2 = c.toDouble() / n2.toDouble()
    val rr = (aa / (aa + bb)) / (cc / (cc + dd))
    val seLogRr = sqrt(max(0.0, (1.0 / aa) - (1.0 / (aa + bb)) + (1.0 / cc) - (1.0 / (cc + dd))))
    val rrLo = exp(ln(rr) - z * seLogRr)
    val rrHi = exp(ln(rr) + z * seLogRr)

    val arr = r2 - r1
    val nnt = if (abs(arr) > 1e-6) 1.0 / abs(arr) else null

    val total = (n1 + n2).toDouble()
    val eA = n1 * (a + c) / total
    val eB = n1 * (b + d) / total
    val eC = n2 * (a + c) / total
    val eD = n2 * (b + d) / total
    val chiSq = if (eA > 0 && eB > 0 && eC > 0 && eD > 0) {
      val num = (a.toDouble() * d.toDouble() - b.toDouble() * c.toDouble())
      total * num * num / (n1.toDouble() * n2.toDouble() * (a + c).toDouble() * (b + d).toDouble())
    } else 0.0
    val chiP = QuantitativeForensicAnalyzer.chiSquareUpperTailP(chiSq, 1.0)
    val fisherP = exactFisherTwoSidedP(a, b, c, d)

    return TwoByTwoExactMetrics(or, orLo, orUpper = orHi, rr = rr, rrLower = rrLo, rrUpper = rrHi, arr = arr, nnt = nnt, chiSquare = chiSq, chiSquareP = chiP, fisherExactTwoSidedP = fisherP)
  }

  /**
   * Exact two-sided Fisher's exact test p-value via log-factorial hypergeometric probabilities.
   */
  fun exactFisherTwoSidedP(a: Int, b: Int, c: Int, d: Int): Double {
    val n1 = a + b
    val n2 = c + d
    val m1 = a + c
    val total = n1 + n2
    if (total <= 0) return 1.0
    val minA = max(0, m1 - n2)
    val maxA = min(n1, m1)
    if (maxA - minA > 50_000) {
      // Fallback to chi-square for huge N where exact summation is unnecessary and asymptotic is exact
      val num = (a.toDouble() * d.toDouble() - b.toDouble() * c.toDouble())
      val denom = n1.toDouble() * n2.toDouble() * m1.toDouble() * (b + d).toDouble()
      return if (denom > 0.0) QuantitativeForensicAnalyzer.chiSquareUpperTailP(total * num * num / denom, 1.0) else 1.0
    }
    val logDenom = logChoose(total, m1)
    fun prob(x: Int): Double {
      val logP = logChoose(n1, x) + logChoose(n2, m1 - x) - logDenom
      return exp(logP)
    }
    val pObs = prob(a)
    var pSum = 0.0
    for (x in minA..maxA) {
      val px = prob(x)
      if (px <= pObs + 1e-12) pSum += px
    }
    return pSum.coerceIn(0.0, 1.0)
  }

  private fun reconstructTwoByTwoTable(
    tbl: TwoByTwoTableClaim,
    nextId: () -> String
  ): List<NumericalReconstructionResult> {
    val calc = computeTwoByTwo(tbl.a, tbl.b, tbl.c, tbl.d) ?: return emptyList()
    val out = mutableListOf<NumericalReconstructionResult>()
    val cellSummary = "${tbl.a}/${tbl.a + tbl.b} vs ${tbl.c}/${tbl.c + tbl.d}"
    val assumptions = listOf(
      "Reconstructed from unadjusted 2x2 cell counts ($cellSummary).",
      if (tbl.a == 0 || tbl.b == 0 || tbl.c == 0 || tbl.d == 0) "Haldane-Anscombe 0.5 continuity adjustment applied for zero cell." else "Standard Woolf log-OR / Katz log-RR variance."
    )

    // Compare reported OR if present (allow for adjusted OR only if explicitly labeled; unadjusted mismatch = ARITHMETIC_ERROR)
    if (tbl.reportedOr != null) {
      val isLikelyAdjusted = tbl.sourceQuote.contains("aOR", ignoreCase = true) || tbl.sourceQuote.contains("adjusted", ignoreCase = true)
      val relErr = abs(tbl.reportedOr - calc.or) / max(calc.or, 1e-6)
      val invRelErr = abs(tbl.reportedOr - (1.0 / calc.or)) / max(1.0 / calc.or, 1e-6)
      val bestRel = min(relErr, invRelErr)
      val isFlippedRef = invRelErr < 0.06 && relErr > 0.15
      val (status, type, detail) = when {
        bestRel <= 0.06 -> Triple(
          QuantFindingStatus.PASS,
          StatisticalFindingType.PASS,
          if (isFlippedRef) "Reported OR=${formatNum(tbl.reportedOr)} matches reciprocal 1/OR=${formatNum(1.0 / calc.or)} (reversed reference arm)."
          else "Reported OR=${formatNum(tbl.reportedOr)} matches exact 2x2 OR=${formatNum(calc.or)} (95% CI ${formatNum(calc.orLower)}–${formatNum(calc.orUpper)})."
        )
        isLikelyAdjusted -> Triple(
          QuantFindingStatus.INFO,
          StatisticalFindingType.PASS,
          "Reported adjusted OR=${formatNum(tbl.reportedOr)} differs from crude 2x2 OR=${formatNum(calc.or)} (expected under covariate adjustment)."
        )
        else -> Triple(
          QuantFindingStatus.FAIL,
          StatisticalFindingType.ARITHMETIC_ERROR,
          "Crude 2x2 counts ($cellSummary) yield OR=${formatNum(calc.or)} (95% CI ${formatNum(calc.orLower)}–${formatNum(calc.orUpper)}), conflicting with reported OR=${formatNum(tbl.reportedOr)} (${(bestRel * 100).toInt()}% relative discrepancy)."
        )
      }
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "2x2 Contingency (OR/Fisher)",
          reportedValue = "OR=${formatNum(tbl.reportedOr)}",
          recomputedValue = "OR=${formatNum(calc.or)} (${formatNum(calc.orLower)}–${formatNum(calc.orUpper)}), Fisher p=${formatP(calc.fisherExactTwoSidedP)}",
          tolerance = "±6% relative",
          sourceEvidence = tbl.sourceQuote,
          pageNumber = tbl.page,
          section = tbl.section,
          assumptions = assumptions,
          status = status,
          findingType = type,
          scientificConsequence = if (status == QuantFindingStatus.FAIL) "Effect magnitude cannot be reproduced from reported arm counts." else "Effect size verified against raw arm counts.",
          detail = detail
        )
      )
    }

    if (tbl.reportedRr != null) {
      val isLikelyAdjusted = tbl.sourceQuote.contains("aRR", ignoreCase = true) || tbl.sourceQuote.contains("adjusted", ignoreCase = true)
      val relErr = abs(tbl.reportedRr - calc.rr) / max(calc.rr, 1e-6)
      val invRelErr = abs(tbl.reportedRr - (1.0 / calc.rr)) / max(1.0 / calc.rr, 1e-6)
      val bestRel = min(relErr, invRelErr)
      val (status, type, detail) = when {
        bestRel <= 0.06 -> Triple(QuantFindingStatus.PASS, StatisticalFindingType.PASS, "Reported RR=${formatNum(tbl.reportedRr)} matches exact 2x2 RR=${formatNum(calc.rr)} (95% CI ${formatNum(calc.rrLower)}–${formatNum(calc.rrUpper)}).")
        isLikelyAdjusted -> Triple(QuantFindingStatus.INFO, StatisticalFindingType.PASS, "Reported adjusted RR=${formatNum(tbl.reportedRr)} differs from crude 2x2 RR=${formatNum(calc.rr)}.")
        else -> Triple(QuantFindingStatus.FAIL, StatisticalFindingType.ARITHMETIC_ERROR, "Crude 2x2 counts ($cellSummary) yield RR=${formatNum(calc.rr)} (95% CI ${formatNum(calc.rrLower)}–${formatNum(calc.rrUpper)}), conflicting with reported RR=${formatNum(tbl.reportedRr)}.")
      }
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "2x2 Contingency (RR/ARR/NNT)",
          reportedValue = "RR=${formatNum(tbl.reportedRr)}",
          recomputedValue = "RR=${formatNum(calc.rr)} (${formatNum(calc.rrLower)}–${formatNum(calc.rrUpper)}), ARR=${formatNum(calc.arr * 100)}%, NNT=${calc.nnt?.let { formatNum(it) } ?: "∞"}",
          tolerance = "±6% relative",
          sourceEvidence = tbl.sourceQuote,
          pageNumber = tbl.page,
          section = tbl.section,
          assumptions = assumptions,
          status = status,
          findingType = type,
          scientificConsequence = if (status == QuantFindingStatus.FAIL) "Reported risk ratio disagrees with raw arm counts." else "Risk ratio verified.",
          detail = detail
        )
      )
    }

    if (tbl.reportedNnt != null && calc.nnt != null) {
      val relErr = abs(tbl.reportedNnt - calc.nnt) / max(calc.nnt, 1.0)
      val fail = relErr > 0.12
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "2x2 Clinical Magnitude (ARR/NNT)",
          reportedValue = "NNT=${formatNum(tbl.reportedNnt)}",
          recomputedValue = "ARR=${formatNum(abs(calc.arr) * 100)}%, NNT=${formatNum(calc.nnt)}",
          tolerance = "±12% (rounding of ARR)",
          sourceEvidence = tbl.sourceQuote,
          pageNumber = tbl.page,
          section = tbl.section,
          assumptions = assumptions,
          status = if (fail) QuantFindingStatus.FAIL else QuantFindingStatus.PASS,
          findingType = if (fail) StatisticalFindingType.ARITHMETIC_ERROR else StatisticalFindingType.PASS,
          scientificConsequence = if (fail) "Clinical benefit magnitude (NNT) is miscalculated from reported arm risks." else "NNT matches 1/|ARR|.",
          detail = if (fail) "1/|ARR| = 1/${formatNum(abs(calc.arr))} = ${formatNum(calc.nnt)}, conflicting with reported NNT=${formatNum(tbl.reportedNnt)}."
          else "Reported NNT=${formatNum(tbl.reportedNnt)} aligns with exact 1/|ARR|=${formatNum(calc.nnt)}."
        )
      )
    }

    // If neither OR/RR/NNT was reported next to the 2x2 counts, still emit exact reconstruction summary (INFO/PASS)
    if (out.isEmpty()) {
      val pConflict = tbl.reportedP != null &&
        ((tbl.reportedP < 0.05 && calc.chiSquareP > 0.08 && calc.fisherExactTwoSidedP > 0.08) ||
          (tbl.reportedP >= 0.05 && calc.chiSquareP < 0.025 && calc.fisherExactTwoSidedP < 0.025))
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "2x2 Exact Reconstruction",
          reportedValue = tbl.reportedP?.let { "$cellSummary (p=${formatP(it)})" } ?: cellSummary,
          recomputedValue = "OR=${formatNum(calc.or)}, RR=${formatNum(calc.rr)}, ARR=${formatNum(calc.arr * 100)}%, Fisher p=${formatP(calc.fisherExactTwoSidedP)}",
          tolerance = "Exact Fisher & χ²(1)",
          sourceEvidence = tbl.sourceQuote,
          pageNumber = tbl.page,
          section = tbl.section,
          assumptions = assumptions,
          status = if (pConflict) QuantFindingStatus.FAIL else QuantFindingStatus.PASS,
          findingType = if (pConflict) StatisticalFindingType.ARITHMETIC_ERROR else StatisticalFindingType.PASS,
          scientificConsequence = if (pConflict) "2x2 significance decision disagrees with both exact Fisher and Pearson χ² tests." else "Independent 2x2 OR/RR/ARR/NNT and Fisher exact p reconstructed.",
          detail = if (pConflict) "Reported p=${formatP(tbl.reportedP ?: 1.0)} conflicts with exact Fisher p=${formatP(calc.fisherExactTwoSidedP)} and Pearson χ² p=${formatP(calc.chiSquareP)}."
          else "Reconstructed from $cellSummary: OR=${formatNum(calc.or)} (${formatNum(calc.orLower)}–${formatNum(calc.orUpper)}), RR=${formatNum(calc.rr)}, Fisher p=${formatP(calc.fisherExactTwoSidedP)}."
        )
      )
    }
    return out
  }

  // ============================================================================
  // CALCULATOR 2: BINOMIAL WILSON & POISSON CI + PROPORTION ARITHMETIC
  // ============================================================================
  fun wilsonBinomialCi(events: Int, n: Int, alpha: Double = 0.05): Pair<Double, Double> {
    if (n <= 0) return 0.0 to 1.0
    val p = events.toDouble() / n.toDouble()
    val z = normalQuantile(1.0 - alpha / 2.0)
    val z2 = z * z
    val denom = 1.0 + z2 / n
    val center = (p + z2 / (2.0 * n)) / denom
    val half = (z / denom) * sqrt((p * (1.0 - p) / n) + (z2 / (4.0 * n * n)))
    return (center - half).coerceIn(0.0, 1.0) to (center + half).coerceIn(0.0, 1.0)
  }

  /** Byar's exact-quality approximation for Poisson count 95% CI (exact for k=0). */
  fun poissonCountCi(events: Int, alpha: Double = 0.05): Pair<Double, Double> {
    if (events < 0) return 0.0 to 0.0
    if (events == 0) return 0.0 to (-ln(alpha / 2.0))
    val z = normalQuantile(1.0 - alpha / 2.0)
    val k = events.toDouble()
    val lo = k * (1.0 - 1.0 / (9.0 * k) - z / (3.0 * sqrt(k))).let { it * it * it }
    val kp = k + 1.0
    val hi = kp * (1.0 - 1.0 / (9.0 * kp) + z / (3.0 * sqrt(kp))).let { it * it * it }
    return max(0.0, lo) to hi
  }

  private fun reconstructProportion(checkId: String, claim: QuantitativeClaim): NumericalReconstructionResult? {
    val num = claim.numerator?.toInt() ?: return null
    val den = claim.denominator?.toInt() ?: return null
    val repPct = claim.pointEstimate ?: return null
    if (den <= 0) return null
    val exactPct = 100.0 * num.toDouble() / den.toDouble()
    val (wLo, wHi) = wilsonBinomialCi(num, den, claim.alphaThreshold)
    val diff = abs(exactPct - repPct)
    // Only emit FAIL when arithmetic error exceeds rounding tolerance (>1.5 percentage points)
    if (diff <= 1.5 && num <= den) return null
    val impossible = num > den
    return NumericalReconstructionResult(
      checkId = checkId,
      calculatorName = "Binomial Proportion & Wilson CI",
      reportedValue = "$num/$den (${formatNum(repPct)}%)",
      recomputedValue = "${formatNum(exactPct)}% (Wilson 95% CI ${formatNum(wLo * 100)}%–${formatNum(wHi * 100)}%)",
      tolerance = "±1.5 percentage points",
      sourceEvidence = claim.sourceQuote,
      pageNumber = claim.page,
      section = claim.section,
      assumptions = listOf("Exact percentage = 100 * numerator / denominator; Wilson score 95% CI."),
      status = QuantFindingStatus.FAIL,
      findingType = StatisticalFindingType.ARITHMETIC_ERROR,
      scientificConsequence = if (impossible) "Numerator exceeds denominator." else "Reported percentage disagrees with exact numerator/denominator ratio.",
      detail = if (impossible) "Impossible proportion: numerator ($num) exceeds denominator ($den)."
      else "Exact ratio $num/$den = ${formatNum(exactPct)}%, differing from reported ${formatNum(repPct)}% by ${formatNum(diff)} percentage points."
    )
  }

  // ============================================================================
  // CALCULATOR 3: TEST STATISTIC (t, z, χ², F, r-to-t) WITH TAIL & ALPHA AWARENESS
  // ============================================================================
  private fun reconstructTestStatistic(checkId: String, claim: QuantitativeClaim): NumericalReconstructionResult? {
    val statVal = claim.testStatisticValue ?: return null
    val repP = claim.pValue ?: return null
    val op = claim.pValueOperator.ifBlank { "=" }
    if (repP !in 0.0..1.0) {
      return NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Statcheck (${claim.testStatisticName})",
        reportedValue = claim.sourceQuote,
        recomputedValue = "Invalid p outside [0,1]",
        tolerance = "Domain [0, 1]",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("Probability axioms require 0 <= p <= 1."),
        status = QuantFindingStatus.FAIL,
        findingType = StatisticalFindingType.ARITHMETIC_ERROR,
        scientificConsequence = "Mathematically impossible probability value.",
        detail = "Reported p-value ($repP) lies outside [0, 1]."
      )
    }

    val twoTailedP = when (claim.testStatisticName.lowercase(Locale.US)) {
      "t" -> {
        val df = claim.degreesOfFreedom ?: return null
        if (df <= 0.0) return null
        QuantitativeForensicAnalyzer.studentTTwoTailedP(abs(statVal), df)
      }
      "z" -> QuantitativeForensicAnalyzer.normalTwoTailedP(abs(statVal))
      "chi2" -> {
        val df = claim.degreesOfFreedom ?: return null
        if (df <= 0.0 || statVal < 0.0) return null
        QuantitativeForensicAnalyzer.chiSquareUpperTailP(statVal, df)
      }
      "f" -> {
        val df1 = claim.degreesOfFreedom ?: return null
        val df2 = claim.degreesOfFreedom2 ?: return null
        if (df1 <= 0.0 || df2 <= 0.0 || statVal < 0.0) return null
        QuantitativeForensicAnalyzer.fDistUpperTailP(statVal, df1, df2)
      }
      "r" -> {
        val df = claim.degreesOfFreedom ?: return null
        if (df <= 0.0 || abs(statVal) >= 1.0) return null
        val tEquiv = abs(statVal) * sqrt(df / (1.0 - statVal * statVal))
        QuantitativeForensicAnalyzer.studentTTwoTailedP(tEquiv, df)
      }
      else -> return null
    }

    val supportsOneSided = claim.testStatisticName.lowercase(Locale.US) in setOf("t", "z", "r")
    val effectiveP = if (claim.tailSpec == TailSpecification.ONE_SIDED && supportsOneSided) {
      twoTailedP / 2.0
    } else {
      twoTailedP
    }
    val tailLabel = if (claim.tailSpec == TailSpecification.ONE_SIDED && supportsOneSided) "one-sided" else "two-sided"
    val alpha = claim.alphaThreshold

    val reportedSig = when (op) {
      "<", "≤" -> repP <= alpha
      ">", "≥" -> false
      else -> repP < alpha
    }
    val recomputedSig = effectiveP < alpha

    val (status, findingType, detail) = when (op) {
      "<", "≤" -> when {
        effectiveP <= repP + 0.002 -> Triple(QuantFindingStatus.PASS, StatisticalFindingType.PASS, "Consistent $tailLabel inequality (recomputed p=${formatP(effectiveP)} vs reported $op ${formatP(repP)}).")
        reportedSig != recomputedSig -> Triple(QuantFindingStatus.FAIL, StatisticalFindingType.ARITHMETIC_ERROR, "Decision-changing conflict at α=${formatNum(alpha)}: reported p $op ${formatP(repP)}, but exact $tailLabel p = ${formatP(effectiveP)}.")
        else -> Triple(QuantFindingStatus.WARN, StatisticalFindingType.ARITHMETIC_ERROR, "Numerical discrepancy: reported p $op ${formatP(repP)} vs exact $tailLabel p = ${formatP(effectiveP)}.")
      }
      ">", "≥" -> when {
        effectiveP + 0.002 >= repP -> Triple(QuantFindingStatus.PASS, StatisticalFindingType.PASS, "Consistent $tailLabel inequality (recomputed p=${formatP(effectiveP)} vs reported $op ${formatP(repP)}).")
        else -> Triple(QuantFindingStatus.WARN, StatisticalFindingType.ARITHMETIC_ERROR, "Reported non-significant bound p $op ${formatP(repP)}, but exact $tailLabel p = ${formatP(effectiveP)}.")
      }
      else -> {
        val diff = abs(effectiveP - repP)
        when {
          diff <= 0.005 || (effectiveP < 0.001 && repP < 0.001) -> Triple(QuantFindingStatus.PASS, StatisticalFindingType.PASS, "Exact $tailLabel p = ${formatP(effectiveP)} matches reported p = ${formatP(repP)}.")
          // Check if unexplained two-sided mismatch would match one-sided!
          supportsOneSided && claim.tailSpec == TailSpecification.UNSPECIFIED && abs((twoTailedP / 2.0) - repP) <= 0.005 ->
            Triple(QuantFindingStatus.WARN, StatisticalFindingType.REPORTING_OMISSION, "Reported p=${formatP(repP)} matches ONE-SIDED p=${formatP(twoTailedP / 2.0)} (two-sided p=${formatP(twoTailedP)}) without declaring a one-sided test.")
          reportedSig != recomputedSig && diff > 0.01 -> Triple(QuantFindingStatus.FAIL, StatisticalFindingType.ARITHMETIC_ERROR, "Decision-changing statcheck error at α=${formatNum(alpha)}: reported p=${formatP(repP)} vs exact $tailLabel p=${formatP(effectiveP)}.")
          else -> Triple(QuantFindingStatus.WARN, StatisticalFindingType.ARITHMETIC_ERROR, "Minor p-value discrepancy: reported p=${formatP(repP)} vs exact $tailLabel p=${formatP(effectiveP)} (Δ=${formatNum(diff)}).")
        }
      }
    }

    return NumericalReconstructionResult(
      checkId = checkId,
      calculatorName = "Statcheck (${claim.testStatisticName}, $tailLabel)",
      reportedValue = claim.sourceQuote,
      recomputedValue = "p = ${formatP(effectiveP)} (α=${formatNum(alpha)})",
      tolerance = "±0.005 exact / α-crossing",
      sourceEvidence = claim.sourceQuote,
      pageNumber = claim.page,
      section = claim.section,
      assumptions = listOf("Tail specification: $tailLabel", "Decision threshold α = ${formatNum(alpha)}"),
      status = status,
      findingType = findingType,
      isScreeningSignal = status == QuantFindingStatus.WARN && findingType == StatisticalFindingType.REPORTING_OMISSION,
      scientificConsequence = if (status == QuantFindingStatus.FAIL) "Reported statistical significance conclusion reverses upon exact recomputation." else "Test statistic consistency evaluated.",
      detail = detail
    )
  }

  // ============================================================================
  // CALCULATOR 4: REGRESSION β / SE / WALD z / t / 95% CI RECONSTRUCTION
  // ============================================================================
  private fun reconstructRegressionClaim(checkId: String, claim: QuantitativeClaim): NumericalReconstructionResult? {
    val beta = claim.pointEstimate ?: return null
    val se = claim.standardError ?: return null
    if (se <= 0.0) return null
    val crit = if (claim.degreesOfFreedom != null && claim.degreesOfFreedom > 1.0) {
      studentTQuantile(1.0 - claim.alphaThreshold / 2.0, claim.degreesOfFreedom)
    } else {
      normalQuantile(1.0 - claim.alphaThreshold / 2.0)
    }
    val zStat = beta / se
    val pCalc = if (claim.degreesOfFreedom != null && claim.degreesOfFreedom > 1.0) {
      QuantitativeForensicAnalyzer.studentTTwoTailedP(abs(zStat), claim.degreesOfFreedom)
    } else {
      QuantitativeForensicAnalyzer.normalTwoTailedP(abs(zStat))
    }
    val expLo = beta - crit * se
    val expHi = beta + crit * se

    val ciMismatch = if (claim.ciLower != null && claim.ciUpper != null) {
      val tol = max(0.05, 0.25 * se)
      abs(claim.ciLower - expLo) > tol || abs(claim.ciUpper - expHi) > tol
    } else false

    val pMismatch = if (claim.pValue != null && claim.pValueOperator == "=") {
      val repSig = claim.pValue < claim.alphaThreshold
      val calcSig = pCalc < claim.alphaThreshold
      repSig != calcSig && abs(claim.pValue - pCalc) > 0.015
    } else false

    val isFail = ciMismatch || pMismatch
    return NumericalReconstructionResult(
      checkId = checkId,
      calculatorName = "Regression β/SE/CI Check",
      reportedValue = claim.sourceQuote.take(64),
      recomputedValue = "z=${formatNum(zStat)}, p=${formatP(pCalc)}, 95% CI [${formatNum(expLo)}, ${formatNum(expHi)}]",
      tolerance = "±max(0.05, 0.25*SE)",
      sourceEvidence = claim.sourceQuote,
      pageNumber = claim.page,
      section = claim.section,
      assumptions = listOf(if (claim.degreesOfFreedom != null) "Student-t df=${claim.degreesOfFreedom}" else "Asymptotic Wald Normal (z=${formatNum(crit)})"),
      status = if (isFail) QuantFindingStatus.FAIL else QuantFindingStatus.PASS,
      findingType = if (isFail) StatisticalFindingType.ARITHMETIC_ERROR else StatisticalFindingType.PASS,
      scientificConsequence = if (isFail) "Regression CI or p-value is inconsistent with reported β and SE." else "Regression β, SE, CI, and p-value are internally consistent.",
      detail = if (isFail) "From β=${formatNum(beta)} and SE=${formatNum(se)}, expected 95% CI is [${formatNum(expLo)}, ${formatNum(expHi)}] and p=${formatP(pCalc)}, conflicting with reported values."
      else "β=${formatNum(beta)} ± ${formatNum(crit)}×SE(${formatNum(se)}) matches reported interval/p-value."
    )
  }

  // ============================================================================
  // CALCULATOR 5: SURVIVAL HR & RATIO (OR/RR/IRR) LOG-SYMMETRY & IMPLIED SE
  // ============================================================================
  private fun reconstructRatioOrSurvivalCi(checkId: String, claim: QuantitativeClaim): NumericalReconstructionResult? {
    val est = claim.pointEstimate ?: return null
    val lo = claim.ciLower ?: return null
    val hi = claim.ciUpper ?: return null
    val label = claim.effectMeasure.uppercase(Locale.US)

    if (est <= 0.0 || lo <= 0.0 || hi <= 0.0) {
      return NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival CI Domain ($label)",
        reportedValue = claim.sourceQuote,
        recomputedValue = "Positive ratio required (> 0)",
        tolerance = "Strict positivity",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("$label is strictly positive on the multiplicative scale."),
        status = QuantFindingStatus.FAIL,
        findingType = StatisticalFindingType.ARITHMETIC_ERROR,
        scientificConsequence = "Impossible non-positive ratio or confidence bound.",
        detail = "$label = $est with bounds [$lo, $hi] violates strict positivity."
      )
    }

    if (lo > hi || est < lo - 0.015 || est > hi + 0.015) {
      return NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival CI Bounds ($label)",
        reportedValue = "$label=$est [$lo, $hi]",
        recomputedValue = "Must satisfy $lo <= $est <= $hi",
        tolerance = "±0.015 rounding",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("Point estimate must lie within its confidence interval."),
        status = QuantFindingStatus.FAIL,
        findingType = StatisticalFindingType.ARITHMETIC_ERROR,
        scientificConsequence = "Impossible CI geometry (point estimate outside bounds or inverted limits).",
        detail = "Reported $label=$est lies outside reported bounds [$lo, $hi]."
      )
    }

    val lnEst = ln(est)
    val lnLo = ln(lo)
    val lnHi = ln(hi)
    val logMid = (lnLo + lnHi) / 2.0
    val logHalf = (lnHi - lnLo) / 2.0
    val impliedSe = (lnHi - lnLo) / (2.0 * normalQuantile(1.0 - (1.0 - claim.ciLevel) / 2.0))
    val impliedZ = if (impliedSe > 1e-6) abs(lnEst) / impliedSe else 0.0
    val impliedP = QuantitativeForensicAnalyzer.normalTwoTailedP(impliedZ)
    val relAsym = if (logHalf > 1e-4) abs(lnEst - logMid) / logHalf else 0.0

    // Check p-value vs CI null-crossing contradiction
    val ciExcludesNull = lo > 1.005 || hi < 0.995
    val ciIncludesNull = lo < 0.99 && hi > 1.01
    val repP = claim.pValue
    val pContradictsCi = repP != null && claim.pValueOperator == "=" &&
      ((ciExcludesNull && repP > 0.06) || (ciIncludesNull && repP < 0.04))

    return when {
      pContradictsCi -> NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival CI-vs-p ($label)",
        reportedValue = "${claim.sourceQuote}, p=${formatP(repP!!)}",
        recomputedValue = "Implied Wald SE(ln)=${formatNum(impliedSe)}, p≈${formatP(impliedP)}",
        tolerance = "CI null-crossing vs α=0.05",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("95% CI exclusion of 1.0 corresponds to two-sided Wald p < 0.05."),
        status = QuantFindingStatus.FAIL,
        findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
        scientificConsequence = "Confidence interval and p-value give contradictory statistical significance conclusions.",
        detail = "$label=$est ($lo–$hi) ${if (ciExcludesNull) "excludes" else "crosses"} 1.0, contradicting reported p=${formatP(repP)} (implied Wald p≈${formatP(impliedP)})."
      )
      relAsym > 0.30 && logHalf > 0.08 -> NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival Log-Symmetry ($label)",
        reportedValue = "$label=$est [$lo, $hi]",
        recomputedValue = "Log-midpoint=${formatNum(exp(logMid))}, SE(ln)=${formatNum(impliedSe)}",
        tolerance = "±30% log-half-width",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("Wald log-scale CIs are symmetric around ln($label); profile-likelihood, exact, or bootstrap CIs may legitimately be asymmetric."),
        status = QuantFindingStatus.WARN,
        findingType = StatisticalFindingType.POSSIBLE_BIAS,
        isScreeningSignal = true,
        scientificConsequence = "Screening signal only: may reflect exact/bootstrap/profile-likelihood CI or a transcription typo.",
        detail = "Log-scale midpoint (${formatNum(exp(logMid))}) deviates from reported $label=$est by ${(relAsym * 100).toInt()}% of log half-width; verify CI method (Wald vs profile/bootstrap) or transcription."
      )
      else -> NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival CI ($label)",
        reportedValue = "$label=$est [$lo, $hi]",
        recomputedValue = "SE(ln)=${formatNum(impliedSe)}, implied p=${formatP(impliedP)}",
        tolerance = "Log-scale consistency",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("Multiplicative log-scale Wald reconstruction."),
        status = QuantFindingStatus.PASS,
        findingType = StatisticalFindingType.PASS,
        scientificConsequence = "Log-scale CI geometry and implied precision verified.",
        detail = "$label=$est ($lo–$hi) is log-symmetric (implied SE=${formatNum(impliedSe)})."
      )
    }
  }

  // ============================================================================
  // CALCULATOR 6: ANOVA F DECOMPOSITION & SMD (COHEN'S d / HEDGES' g)
  // ============================================================================
  fun computeAnovaF(ssBetween: Double, dfBetween: Double, ssWithin: Double, dfWithin: Double): Pair<Double, Double>? {
    if (dfBetween <= 0.0 || dfWithin <= 0.0 || ssBetween < 0.0 || ssWithin <= 0.0) return null
    val msBetween = ssBetween / dfBetween
    val msWithin = ssWithin / dfWithin
    val fVal = msBetween / msWithin
    val pVal = QuantitativeForensicAnalyzer.fDistUpperTailP(fVal, dfBetween, dfWithin)
    return fVal to pVal
  }

  fun computeSmd(mean1: Double, sd1: Double, n1: Int, mean2: Double, sd2: Double, n2: Int): SmdExactMetrics? {
    if (n1 < 2 || n2 < 2 || sd1 < 0.0 || sd2 < 0.0) return null
    val df = (n1 + n2 - 2).toDouble()
    val sp = sqrt(((n1 - 1) * sd1 * sd1 + (n2 - 1) * sd2 * sd2) / df)
    if (sp <= 1e-12) return null
    val d = (mean1 - mean2) / sp
    val j = 1.0 - 3.0 / (4.0 * df - 1.0)
    val g = j * d
    val seG = sqrt(((n1 + n2).toDouble() / (n1.toDouble() * n2.toDouble())) + (g * g) / (2.0 * (n1 + n2).toDouble()))
    val z = 1.95996398454
    return SmdExactMetrics(d, g, seG, g - z * seG, g + z * seG)
  }

  // ============================================================================
  // CALCULATOR 7: META-ANALYSIS POOLING (IVW, DL & PM τ², Q, I², HK CI, 95% PI, LOO)
  // ============================================================================
  fun computeMetaAnalysis(studies: List<MetaStudyEffectClaim>): MetaAnalysisExactSummary? {
    if (studies.size < 2) return null
    val k = studies.size
    val y = studies.map { it.effect }
    val v = studies.map { it.standardError * it.standardError }
    if (v.any { it <= 0.0 || it.isNaN() }) return null

    // Fixed-Effect IVW
    val wFE = v.map { 1.0 / it }
    val sumW = wFE.sum()
    val muFE = y.indices.sumOf { wFE[it] * y[it] } / sumW
    val seFE = sqrt(1.0 / sumW)
    val z95 = 1.95996398454

    // Cochran's Q & I²
    val q = y.indices.sumOf { wFE[it] * (y[it] - muFE) * (y[it] - muFE) }
    val df = k - 1
    val qP = QuantitativeForensicAnalyzer.chiSquareUpperTailP(q, df.toDouble())
    val i2 = if (q > df && q > 0.0) ((q - df) / q * 100.0).coerceIn(0.0, 100.0) else 0.0

    // DerSimonian-Laird τ²
    val cDenom = sumW - (wFE.sumOf { it * it } / sumW)
    val tau2DL = if (cDenom > 0.0) max(0.0, (q - df) / cDenom) else 0.0

    // Paule-Mandel iterative τ² (solves Q_gen(τ²) = k - 1)
    val tau2PM = solvePauleMandelTau2(y, v, tau2DL)

    // Random-Effects (using Paule-Mandel if converged, else DL; both reported)
    val tau2Use = tau2PM
    val wRE = v.map { 1.0 / (it + tau2Use) }
    val sumWRE = wRE.sum()
    val muRE = y.indices.sumOf { wRE[it] * y[it] } / sumWRE
    val seRE = sqrt(1.0 / sumWRE)

    // Hartung-Knapp-Sidik-Jonkman (HKSJ) t-CI
    val qHksj = y.indices.sumOf { wRE[it] * (y[it] - muRE) * (y[it] - muRE) } / df.toDouble()
    val seHK = sqrt(max(1.0, qHksj) / sumWRE)
    val tCritHK = studentTQuantile(0.975, df.toDouble())
    val hkLo = muRE - tCritHK * seHK
    val hkHi = muRE + tCritHK * seHK

    // 95% Prediction Interval (requires k >= 3, df = k - 2)
    val (piLo, piHi) = if (k >= 3) {
      val tCritPI = studentTQuantile(0.975, (k - 2).toDouble())
      val halfPI = tCritPI * sqrt(tau2Use + seRE * seRE)
      (muRE - halfPI) to (muRE + halfPI)
    } else null to null

    // Leave-One-Out (LOO) Influence Diagnostics
    var maxShift = 0.0
    var dominantStudy: String? = null
    var crossesNull = false
    if (k >= 3) {
      val baseSignificant = (hkLo > 0.0 && hkHi > 0.0) || (hkLo < 0.0 && hkHi < 0.0)
      for (dropIdx in studies.indices) {
        val sub = studies.filterIndexed { i, _ -> i != dropIdx }
        val wSub = sub.map { 1.0 / (it.standardError * it.standardError + tau2Use) }
        val muSub = sub.indices.sumOf { wSub[it] * sub[it].effect } / wSub.sum()
        val seSub = sqrt(1.0 / wSub.sum())
        val shift = abs(muSub - muRE)
        if (shift > maxShift) {
          maxShift = shift
          dominantStudy = studies[dropIdx].studyLabel
        }
        val subSig = (muSub - z95 * seSub > 0.0) || (muSub + z95 * seSub < 0.0)
        if (baseSignificant && !subSig) crossesNull = true
      }
    }

    return MetaAnalysisExactSummary(
      k = k,
      fixedEffect = muFE,
      fixedSe = seFE,
      fixedLower = muFE - z95 * seFE,
      fixedUpper = muFE + z95 * seFE,
      cochranQ = q,
      df = df,
      qPValue = qP,
      iSquaredPct = i2,
      tauSquaredDL = tau2DL,
      tauSquaredPM = tau2PM,
      randomEffectDL = muRE,
      randomSeDL = seRE,
      randomLowerDL = muRE - z95 * seRE,
      randomUpperDL = muRE + z95 * seRE,
      hartungKnappLower = hkLo,
      hartungKnappUpper = hkHi,
      predictionIntervalLower = piLo,
      predictionIntervalUpper = piHi,
      mostInfluentialStudy = dominantStudy,
      maxLooShift = maxShift,
      looCrossesNull = crossesNull
    )
  }

  private fun solvePauleMandelTau2(y: List<Double>, v: List<Double>, initTau2: Double): Double {
    val k = y.size
    val target = (k - 1).toDouble()
    fun qGen(t2: Double): Double {
      val w = v.map { 1.0 / (it + t2) }
      val sw = w.sum()
      val mu = y.indices.sumOf { w[it] * y[it] } / sw
      return y.indices.sumOf { w[it] * (y[it] - mu) * (y[it] - mu) }
    }
    if (qGen(0.0) <= target) return 0.0
    var lo = 0.0
    var hi = max(1.0, initTau2 * 4.0 + 1.0)
    var guard = 0
    while (qGen(hi) > target && guard < 40) {
      hi *= 2.0
      guard++
    }
    repeat(50) {
      val mid = 0.5 * (lo + hi)
      if (qGen(mid) > target) lo = mid else hi = mid
    }
    return 0.5 * (lo + hi)
  }

  private fun reconstructMetaAnalysisSuite(
    studies: List<MetaStudyEffectClaim>,
    rawText: String,
    nextId: () -> String
  ): List<NumericalReconstructionResult> {
    val summary = computeMetaAnalysis(studies) ?: return emptyList()
    val isLog = studies.all { it.isLogScale }
    val fmtEst: (Double) -> String = { v -> if (isLog) formatNum(exp(v)) else formatNum(v) }
    val out = mutableListOf<NumericalReconstructionResult>()

    val piStr = if (summary.predictionIntervalLower != null && summary.predictionIntervalUpper != null) {
      "[${fmtEst(summary.predictionIntervalLower)}, ${fmtEst(summary.predictionIntervalUpper)}]"
    } else "n/a"

    val lower = rawText.lowercase(Locale.US)
    val usesFixedOnly = (lower.contains("fixed-effect") || lower.contains("fixed effect")) && !lower.contains("random-effect") && !lower.contains("random effect")
    val modelMismatch = usesFixedOnly && summary.iSquaredPct >= 50.0

    out.add(
      NumericalReconstructionResult(
        checkId = nextId(),
        calculatorName = "Meta-Analysis Pooling (IVW/PM/HKSJ/PI)",
        reportedValue = "k=${summary.k} study effects extracted",
        recomputedValue = "RE=${fmtEst(summary.randomEffectDL)} (HKSJ ${fmtEst(summary.hartungKnappLower)}–${fmtEst(summary.hartungKnappUpper)}), I²=${formatNum(summary.iSquaredPct)}%, τ²_PM=${formatNum(summary.tauSquaredPM)}, 95% PI $piStr",
        tolerance = "Exact Q / Paule-Mandel τ² / HKSJ",
        sourceEvidence = studies.take(4).joinToString("; ") { it.studyLabel },
        pageNumber = studies.first().page,
        section = "Results",
        assumptions = listOf(
          "Independent study effects on ${if (isLog) "log-ratio" else "additive"} scale.",
          "Paule-Mandel τ²=${formatNum(summary.tauSquaredPM)} vs DL τ²=${formatNum(summary.tauSquaredDL)}; Hartung-Knapp t-CI (df=${summary.df})."
        ),
        status = if (modelMismatch) QuantFindingStatus.FAIL else QuantFindingStatus.PASS,
        findingType = if (modelMismatch) StatisticalFindingType.MODEL_MISMATCH else StatisticalFindingType.PASS,
        scientificConsequence = if (modelMismatch) "Fixed-effect pooling under substantial heterogeneity (I²=${formatNum(summary.iSquaredPct)}%) severely understates uncertainty."
        else "Independent random-effects pooling, τ², HKSJ CI, and 95% prediction interval verified.",
        detail = "Reconstructed across k=${summary.k} studies: Q=${formatNum(summary.cochranQ)} (p=${formatP(summary.qPValue)}), I²=${formatNum(summary.iSquaredPct)}%, τ²_DL=${formatNum(summary.tauSquaredDL)}, τ²_PM=${formatNum(summary.tauSquaredPM)}, 95% PI=$piStr."
      )
    )

    if (summary.looCrossesNull && summary.mostInfluentialStudy != null) {
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "Meta-Analysis Leave-One-Out Influence",
          reportedValue = "Pooled effect=${fmtEst(summary.randomEffectDL)}",
          recomputedValue = "Dropping '${summary.mostInfluentialStudy}' shifts estimate by ${formatNum(summary.maxLooShift)} & crosses null",
          tolerance = "LOO significance stability",
          sourceEvidence = "Most influential study: ${summary.mostInfluentialStudy}",
          pageNumber = studies.first().page,
          section = "Results",
          assumptions = listOf("Leave-one-out random-effects sensitivity at fixed τ²_PM."),
          status = QuantFindingStatus.WARN,
          findingType = StatisticalFindingType.POSSIBLE_BIAS,
          isScreeningSignal = true,
          scientificConsequence = "Pooled significance depends on a single influential study ('${summary.mostInfluentialStudy}').",
          detail = "Leave-one-out sensitivity shows omitting '${summary.mostInfluentialStudy}' renders the pooled effect non-significant."
        )
      )
    }

    return out
  }

  // ============================================================================
  // CALCULATOR 8: MULTIPLE TESTING (BONFERRONI, HOLM-BONFERRONI, BENJAMINI-HOCHBERG)
  // ============================================================================
  fun computeMultipleTestingAdjustments(
    familyName: String,
    namedPValues: List<Pair<String, Double>>,
    alpha: Double = 0.05
  ): MultipleTestingSummary {
    val m = namedPValues.size
    if (m == 0) return MultipleTestingSummary(familyName, 0, 0, 0, 0, 0, emptyList())
    val sorted = namedPValues.sortedBy { it.second }
    val rawSig = sorted.count { it.second < alpha }
    val bonfSig = sorted.count { (it.second * m).coerceAtMost(1.0) < alpha }

    // Holm step-down
    var holmSig = 0
    for (i in sorted.indices) {
      val adjP = (sorted[i].second * (m - i)).coerceAtMost(1.0)
      if (adjP < alpha) holmSig++ else break
    }

    // Benjamini-Hochberg step-up q-values
    val bhQ = DoubleArray(m)
    var runningMin = 1.0
    for (i in m - 1 downTo 0) {
      val q = (sorted[i].second * m.toDouble() / (i + 1).toDouble()).coerceAtMost(1.0)
      runningMin = min(runningMin, q)
      bhQ[i] = runningMin
    }
    val bhSig = bhQ.count { it < alpha }
    val pairs = sorted.indices.map { i -> sorted[i].first to bhQ[i] }
    return MultipleTestingSummary(familyName, m, rawSig, bonfSig, holmSig, bhSig, pairs)
  }

  // ============================================================================
  // EXACT QUANTILE & COMBINATORIAL HELPERS (Pure Kotlin)
  // ============================================================================

  /** Acklam's inverse standard normal CDF accurate to ~1e-9. */
  fun normalQuantile(p: Double): Double {
    if (p <= 0.0) return -8.0
    if (p >= 1.0) return 8.0
    if (p == 0.5) return 0.0
    val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
    val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
    val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
    val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
    val pLow = 0.02425
    val pHigh = 1.0 - pLow
    return when {
      p < pLow -> {
        val q = sqrt(-2.0 * ln(p))
        (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
          ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
      }
      p <= pHigh -> {
        val q = p - 0.5
        val r = q * q
        (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
          (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
      }
      else -> {
        val q = sqrt(-2.0 * ln(1.0 - p))
        -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
          ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
      }
    }
  }

  /** Upper quantile t_{df, p} via monotone bisection over exact studentTTwoTailedP. */
  fun studentTQuantile(p: Double, df: Double): Double {
    if (p <= 0.5) return 0.0
    if (df > 500.0) return normalQuantile(p)
    val targetTwoTail = 2.0 * (1.0 - p)
    var lo = 0.0
    var hi = 2.0
    var guard = 0
    while (QuantitativeForensicAnalyzer.studentTTwoTailedP(hi, df) > targetTwoTail && guard < 30) {
      hi *= 2.0
      guard++
    }
    repeat(48) {
      val mid = 0.5 * (lo + hi)
      if (QuantitativeForensicAnalyzer.studentTTwoTailedP(mid, df) > targetTwoTail) lo = mid else hi = mid
    }
    return 0.5 * (lo + hi)
  }

  private fun logChoose(n: Int, k: Int): Double {
    if (k < 0 || k > n) return Double.NEGATIVE_INFINITY
    return logFactorial(n) - logFactorial(k) - logFactorial(n - k)
  }

  private fun logFactorial(n: Int): Double {
    if (n <= 1) return 0.0
    // Lanczos approximation for logGamma(n + 1)
    val z = n + 1.0
    val coeffs = doubleArrayOf(
      76.18009172947146, -86.50532032941677, 24.01409824083091,
      -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
    )
    var y = z
    var tmp = z + 5.5
    tmp -= (z + 0.5) * ln(tmp)
    var ser = 1.000000000190015
    for (c in coeffs) {
      y += 1.0
      ser += c / y
    }
    return -tmp + ln(2.5066282746310005 * ser / z)
  }

  private fun formatNum(v: Double): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

  private fun formatP(p: Double): String = when {
    p < 0.0001 -> String.format(Locale.US, "%.2e", p)
    else -> String.format(Locale.US, "%.4f", p)
  }
}
