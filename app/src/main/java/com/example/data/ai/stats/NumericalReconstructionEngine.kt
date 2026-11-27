package com.example.data.ai.stats

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.semantic.CalculatorAssumptionContract
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
    val fisherExactTwoSidedP: Double,
    val arrCiLower: Double = arr,
    val arrCiUpper: Double = arr,
    val nntCrossesZero: Boolean = false,
    val hasZeroCell: Boolean = false,
    val isDoubleZeroEvents: Boolean = false,
    val nntDirection: String = when {
      arr > 1e-6 -> "NNTB"
      arr < -1e-6 -> "NNTH"
      else -> "NO_DIFFERENCE"
    },
    val nntSignedLabel: String = when {
      nntCrossesZero -> "ARR 95% CI spans zero (NNTB to ∞ to NNTH)"
      nnt == null -> "No risk difference (NNT undefined)"
      arr > 0.0 -> "NNTB = ${String.format(Locale.US, "%.1f", nnt)}"
      else -> "NNTH = ${String.format(Locale.US, "%.1f", nnt)}"
    },
    val fisherWasApproximated: Boolean = false,
    val fisherMethodLabel: String = "Exact two-sided hypergeometric Fisher"
  )

  data class FisherPResult(
    val pValue: Double,
    val isExact: Boolean,
    val methodLabel: String
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
    val looCrossesNull: Boolean,
    val randomEffectPM: Double = randomEffectDL,
    val randomSePM: Double = randomSeDL,
    val randomLowerPM: Double = randomLowerDL,
    val randomUpperPM: Double = randomUpperDL
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

    val activeClaims = ledger.claims
      .filter { !it.isFromReferenceOrCitation }
      .sortedWith(
        compareBy<QuantitativeClaim>(
          { claim ->
            when (claim.hierarchy) {
              AnalysisHierarchy.PRIMARY -> 0
              AnalysisHierarchy.SECONDARY -> 1
              AnalysisHierarchy.UNSPECIFIED -> 2
              AnalysisHierarchy.SENSITIVITY -> 3
              AnalysisHierarchy.SUBGROUP -> 4
              AnalysisHierarchy.EXPLORATORY -> 5
            }
          },
          { claim ->
            when {
              claim.section.contains("Result", ignoreCase = true) -> 0
              claim.section.contains("Abstract", ignoreCase = true) -> 1
              claim.section.contains("Table", ignoreCase = true) -> 2
              else -> 3
            }
          },
          { it.startOffset }
        )
      )

    // 1. Test-statistic reconstructions (t, z, chi2, F, r) with one-sided/two-sided & custom alpha
    activeClaims.filter { it.testStatisticName.isNotBlank() && it.testStatisticValue != null && it.pValue != null }
      .take(150)
      .forEach { claim ->
        reconstructTestStatistic(nextId(), claim)?.let { results.add(it) }
      }

    // 2. 2x2 Table reconstructions (OR, RR, ARR, NNT, chi2, Exact Fisher)
    ledger.twoByTwoTables.take(60).forEach { tbl ->
      results.addAll(reconstructTwoByTwoTable(tbl, ::nextId))
    }

    // 3. Binomial proportions (x/n vs reported %) & Wilson 95% CI
    activeClaims.filter { it.numerator != null && it.denominator != null && it.effectMeasure == "Proportion (%)" }
      .take(150)
      .forEach { claim ->
        reconstructProportion(nextId(), claim)?.let { results.add(it) }
      }

    // 4. Regression beta / SE / Wald z / CI reconstruction
    activeClaims.filter { it.effectMeasure.equals("beta", true) && it.pointEstimate != null && it.standardError != null }
      .take(150)
      .forEach { claim ->
        reconstructRegressionClaim(nextId(), claim)?.let { results.add(it) }
      }

    // 5. Ratio / Survival HR & OR/RR/PR CI log-symmetry & implied SE consistency
    activeClaims.filter {
      it.pointEstimate != null && it.ciLower != null && it.ciUpper != null &&
        it.parseStatus != ClaimParseStatus.AMBIGUOUS &&
        it.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR", "HR", "AHR", "IRR", "PR")
    }.take(150).forEach { claim ->
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
        val isAmbiguousUnstructuredFamily = (family in setOf("Body", "Results", "Exploratory")) &&
          famClaims.all { it.hierarchy == AnalysisHierarchy.UNSPECIFIED || it.hierarchy == AnalysisHierarchy.EXPLORATORY } &&
          !lower.contains("primary endpoint") && !lower.contains("primary outcome") &&
          !lower.contains("family-wise") && !lower.contains("multiplicity") && !lower.contains("table ")
        if (isAmbiguousUnstructuredFamily && !hasExplicitMultiplicityCorrection) {
          results.add(
            NumericalReconstructionResult(
              checkId = nextId(),
              calculatorName = "Multiple-Testing Family (Uncertain Boundaries)",
              reportedValue = "${famClaims.size} ungrouped p-values in '$family'",
              recomputedValue = "Corrected p-values: not computable (family boundary unknown)",
              tolerance = "Multiplicity-family non-fabrication guard",
              sourceEvidence = famClaims.take(4).joinToString("; ") { it.sourceQuote },
              pageNumber = famClaims.first().page,
              section = family,
              assumptions = listOf(
                "Active p-values lack explicit outcome/time-point/primary-vs-exploratory family specification."
              ),
              status = QuantFindingStatus.INFO,
              findingType = StatisticalFindingType.UNSUPPORTED_TO_ASSESS,
              isScreeningSignal = true,
              scientificConsequence = "Potential Type I error inflation risk exists, but exact Bonferroni/FDR thresholds cannot be fabricated without knowing the true prespecified family.",
              detail = "Multiplicity family cannot be reconstructed from the manuscript (${famClaims.size} ungrouped p-values). Potential inflation risk: yes. Corrected p-values: not computable.",
              assumptionContract = CalculatorAssumptionContract(
                calculatorScope = "Multiple-testing family reconstruction",
                assumptionsSatisfied = listOf("Reference/citation p-values excluded."),
                assumptionsUnknown = listOf("True family size m across prespecified vs exploratory outcomes, time points, and subgroups."),
                alternativeValidInterpretations = listOf("Hierarchical gatekeeping or closed testing may control FWER without raw Bonferroni/FDR.")
              )
            )
          )
          continue
        }
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
              detail = "Unadjusted testing in family '$family' (m=${mt.m}): ${mt.rawSignificantCount} raw p < ${formatNum(alpha)}, dropping to ${mt.bhFdrSignificantCount} under BH-FDR and ${mt.holmSignificantCount} under Holm-Bonferroni.",
              assumptionContract = CalculatorAssumptionContract(
                calculatorScope = "Family-scoped FWER/FDR check ($family)",
                assumptionsSatisfied = listOf("Reference/citation p-values isolated", "Uniform α=${formatNum(alpha)}"),
                assumptionsUnknown = listOf("Unreported tests omitted from manuscript", "Between-endpoint correlation structure"),
                alternativeValidInterpretations = listOf("Correlated endpoints may permit less conservative Westfall-Young/maxT adjustments", "Pre-specified sequential gatekeeping.")
              )
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
    val seArr = sqrt(max(0.0, (r1 * (1.0 - r1) / n1.toDouble()) + (r2 * (1.0 - r2) / n2.toDouble())))
    val arrLo = arr - z * seArr
    val arrHi = arr + z * seArr
    val nntCrossesZero = (arrLo < 0.0 && arrHi > 0.0)
    val nnt = if (abs(arr) > 1e-6) 1.0 / abs(arr) else null
    val nntDirection = when {
      arr > 1e-6 -> "NNTB"
      arr < -1e-6 -> "NNTH"
      else -> "NO_DIFFERENCE"
    }
    val nntSignedLabel = when {
      nntCrossesZero -> "ARR 95% CI spans zero (NNTB to ∞ to NNTH)"
      nnt == null -> "No risk difference (NNT undefined)"
      arr > 0.0 -> "NNTB = ${formatNum(nnt)}"
      else -> "NNTH = ${formatNum(nnt)}"
    }
    val isDoubleZero = (a == 0 && c == 0)

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
    val fisherResult = computeFisherTwoSidedPWithMethod(a, b, c, d)

    return TwoByTwoExactMetrics(
      or = or,
      orLower = orLo,
      orUpper = orHi,
      rr = rr,
      rrLower = rrLo,
      rrUpper = rrHi,
      arr = arr,
      nnt = nnt,
      chiSquare = chiSq,
      chiSquareP = chiP,
      fisherExactTwoSidedP = fisherResult.pValue,
      arrCiLower = arrLo,
      arrCiUpper = arrHi,
      nntCrossesZero = nntCrossesZero,
      hasZeroCell = hasZero,
      isDoubleZeroEvents = isDoubleZero,
      nntDirection = nntDirection,
      nntSignedLabel = nntSignedLabel,
      fisherWasApproximated = !fisherResult.isExact,
      fisherMethodLabel = fisherResult.methodLabel
    )
  }

  /**
   * Computes a two-sided Fisher's exact test p-value via log-factorial hypergeometric probabilities,
   * explicitly recording whether the exact hypergeometric sum was used or whether an asymptotic
   * Pearson chi-square approximation was substituted because the feasible table range exceeded 50,000.
   */
  fun computeFisherTwoSidedPWithMethod(a: Int, b: Int, c: Int, d: Int): FisherPResult {
    val n1 = a + b
    val n2 = c + d
    val m1 = a + c
    val total = n1 + n2
    if (total <= 0) {
      return FisherPResult(1.0, isExact = true, methodLabel = "Exact two-sided hypergeometric Fisher")
    }
    val minA = max(0, m1 - n2)
    val maxA = min(n1, m1)
    if (maxA - minA > 50_000) {
      val num = (a.toDouble() * d.toDouble() - b.toDouble() * c.toDouble())
      val denom = n1.toDouble() * n2.toDouble() * m1.toDouble() * (b + d).toDouble()
      val approxP = if (denom > 0.0) {
        QuantitativeForensicAnalyzer.chiSquareUpperTailP(total * num * num / denom, 1.0)
      } else {
        1.0
      }
      return FisherPResult(
        pValue = approxP.coerceIn(0.0, 1.0),
        isExact = false,
        methodLabel = "Asymptotic Pearson chi-square approximation (feasible hypergeometric range ${maxA - minA} > 50,000; not exact Fisher)"
      )
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
    return FisherPResult(
      pValue = pSum.coerceIn(0.0, 1.0),
      isExact = true,
      methodLabel = "Exact two-sided hypergeometric Fisher"
    )
  }

  /**
   * Two-sided Fisher's exact test p-value (exact hypergeometric sum up to feasible range 50,000;
   * see [computeFisherTwoSidedPWithMethod] for explicit exact-vs-asymptotic provenance).
   */
  fun exactFisherTwoSidedP(a: Int, b: Int, c: Int, d: Int): Double =
    computeFisherTwoSidedPWithMethod(a, b, c, d).pValue

  private fun reconstructTwoByTwoTable(
    tbl: TwoByTwoTableClaim,
    nextId: () -> String
  ): List<NumericalReconstructionResult> {
    val calc = computeTwoByTwo(tbl.a, tbl.b, tbl.c, tbl.d) ?: return emptyList()
    val out = mutableListOf<NumericalReconstructionResult>()
    val cellSummary = "${tbl.a}/${tbl.a + tbl.b} vs ${tbl.c}/${tbl.c + tbl.d}"

    // Adversarial Case #8: Double-zero events (0/n1 vs 0/n2) — OR & RR are unidentifiable without external prior!
    if (calc.isDoubleZeroEvents) {
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "2x2 Contingency (Double-Zero Events)",
          reportedValue = cellSummary,
          recomputedValue = "OR/RR undefined (0 events in both arms); ARR = 0.0% (95% CI ${formatNum(calc.arrCiLower * 100)}% to ${formatNum(calc.arrCiUpper * 100)}%)",
          tolerance = "Zero-event identifiability guard",
          sourceEvidence = tbl.sourceQuote,
          pageNumber = tbl.page,
          section = tbl.section,
          assumptions = listOf("Zero events observed in both arms ($cellSummary)."),
          status = QuantFindingStatus.INFO,
          findingType = StatisticalFindingType.UNSUPPORTED_TO_ASSESS,
          isScreeningSignal = true,
          scientificConsequence = "Relative ratios (OR/RR) are mathematically unidentifiable from double-zero tables without Bayesian priors or risk-difference modeling.",
          detail = "Both arms contain zero events ($cellSummary); do not apply Haldane 0.5 to manufacture an artificial OR=1.0 — evaluate absolute risk difference (ARR) or exact upper risk bounds.",
          assumptionContract = CalculatorAssumptionContract(
            calculatorScope = "2x2 double-zero event table",
            assumptionsSatisfied = listOf("Exact cell counts extracted ($cellSummary)"),
            assumptionsUnknown = listOf("Follow-up duration / exposure time", "Background event rate"),
            alternativeValidInterpretations = listOf("Risk difference with exact/Wilson/Newcombe CI", "Bayesian beta-binomial sensitivity")
          )
        )
      )
      return out
    }

    val assumptions = buildList {
      add("Reconstructed from unadjusted 2x2 cell counts ($cellSummary).")
      if (calc.hasZeroCell) add("Single zero-cell detected: Haldane-Anscombe 0.5 continuity adjustment applied for finite OR/RR approximation.")
      else add("Standard Woolf log-OR / Katz log-RR variance.")
      if (calc.nntCrossesZero) add("95% CI for ARR (${formatNum(calc.arrCiLower * 100)}% to ${formatNum(calc.arrCiUpper * 100)}%) spans zero -> NNT crosses infinity (NNTB to ∞ to NNTH).")
    }

    val orContract = CalculatorAssumptionContract(
      calculatorScope = "2x2 Odds Ratio & ${if (calc.fisherWasApproximated) "Asymptotic Chi-Square" else "Exact Fisher"}",
      assumptionsSatisfied = listOf("Complete 2x2 counts ($cellSummary)", calc.fisherMethodLabel),
      assumptionsUnknown = listOf("Whether study is cohort/RCT vs case-control", "Whether reported OR was covariate-adjusted or Firth-penalized", "Whether observations are clustered"),
      alternativeValidInterpretations = buildList {
        add("Reversed reference arm yields reciprocal 1/OR=${formatNum(1.0 / calc.or)}")
        if (calc.hasZeroCell) add("Exact conditional MLE / Peto OR / Firth penalized logistic regression differ from Haldane +0.5")
        if (calc.fisherWasApproximated) {
          add("Asymptotic Pearson chi-square approximation used because feasible hypergeometric range exceeded 50,000")
        } else {
          add("Fisher's exact test conditions on both margins; mid-p or Barnard/Boschloo exact tests are valid alternatives")
        }
      }
    )

    val rrContract = CalculatorAssumptionContract(
      calculatorScope = "2x2 Risk Ratio / ARR / NNT",
      assumptionsSatisfied = listOf("Complete arm numerators and denominators ($cellSummary)"),
      assumptionsUnknown = listOf("Fixed follow-up horizon vs unequal person-time at risk", "Competing risks / loss to follow-up"),
      alternativeValidInterpretations = buildList {
        add("RR is valid for cumulative incidence over fixed follow-up; inappropriate if sampling is case-control")
        if (calc.nntCrossesZero) add("ARR 95% CI spans zero: NNT spans NNTB through ∞ to NNTH (Altman 1998)")
      }
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
        calc.hasZeroCell -> Triple(
          QuantFindingStatus.WARN,
          StatisticalFindingType.ASSUMPTION_UNCHECKED,
          "Single zero-cell 2x2 ($cellSummary): Haldane +0.5 gives OR=${formatNum(calc.or)}, whereas reported OR=${formatNum(tbl.reportedOr)} may reflect exact conditional MLE, Peto OR, or Firth penalization."
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
          isScreeningSignal = calc.hasZeroCell && status == QuantFindingStatus.WARN,
          scientificConsequence = if (status == QuantFindingStatus.FAIL) "Effect magnitude cannot be reproduced from reported arm counts." else "Effect size verified against raw arm counts.",
          detail = detail,
          assumptionContract = orContract
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
        calc.hasZeroCell -> Triple(QuantFindingStatus.WARN, StatisticalFindingType.ASSUMPTION_UNCHECKED, "Single zero-cell 2x2 ($cellSummary): Haldane +0.5 gives RR=${formatNum(calc.rr)} vs reported RR=${formatNum(tbl.reportedRr)} (continuity-correction sensitive).")
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
          isScreeningSignal = calc.hasZeroCell && status == QuantFindingStatus.WARN,
          scientificConsequence = if (status == QuantFindingStatus.FAIL) "Reported risk ratio disagrees with raw arm counts." else "Risk ratio verified.",
          detail = detail,
          assumptionContract = rrContract
        )
      )
    }

    if (tbl.reportedNnt != null && calc.nnt != null) {
      val relErr = abs(tbl.reportedNnt - calc.nnt) / max(calc.nnt, 1.0)
      val fail = relErr > 0.12 && !calc.nntCrossesZero
      val status = when {
        fail -> QuantFindingStatus.FAIL
        calc.nntCrossesZero -> QuantFindingStatus.WARN
        else -> QuantFindingStatus.PASS
      }
      val fType = when {
        fail -> StatisticalFindingType.ARITHMETIC_ERROR
        calc.nntCrossesZero -> StatisticalFindingType.ASSUMPTION_UNCHECKED
        else -> StatisticalFindingType.PASS
      }
      val nntStr = if (calc.nntCrossesZero) {
        "ARR=${formatNum(calc.arr * 100)}% (95% CI ${formatNum(calc.arrCiLower * 100)}% to ${formatNum(calc.arrCiUpper * 100)}%), ${calc.nntSignedLabel}"
      } else {
        "ARR=${formatNum(calc.arr * 100)}%, ${calc.nntSignedLabel}"
      }
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "2x2 Clinical Magnitude (ARR / NNTB-NNTH)",
          reportedValue = "NNT=${formatNum(tbl.reportedNnt)}",
          recomputedValue = nntStr,
          tolerance = "±12% (rounding of ARR)",
          sourceEvidence = tbl.sourceQuote,
          pageNumber = tbl.page,
          section = tbl.section,
          assumptions = assumptions,
          status = status,
          findingType = fType,
          isScreeningSignal = calc.nntCrossesZero,
          scientificConsequence = when {
            fail -> "Clinical effect magnitude (${calc.nntDirection}) disagrees with reported arm risks."
            calc.nntCrossesZero -> "ARR 95% CI spans zero, so NNT CI is discontinuous across infinity (NNTB to ∞ to NNTH) and should be reported alongside ARR CI."
            calc.nntDirection == "NNTH" -> "Signed risk difference indicates harm (NNTH=${formatNum(calc.nnt)}); confirm benefit-vs-harm arm orientation."
            else -> "${calc.nntSignedLabel} matches 1/|ARR|."
          },
          detail = when {
            fail -> "Signed ARR=${formatNum(calc.arr * 100)}% implies ${calc.nntSignedLabel}, conflicting with reported NNT=${formatNum(tbl.reportedNnt)}."
            calc.nntCrossesZero -> "Point ${calc.nntDirection}≈${formatNum(calc.nnt)}, but ARR 95% CI (${formatNum(calc.arrCiLower * 100)}% to ${formatNum(calc.arrCiUpper * 100)}%) crosses zero (NNTB to ∞ to NNTH)."
            else -> "Reported NNT=${formatNum(tbl.reportedNnt)} aligns with ${calc.nntSignedLabel} (signed ARR=${formatNum(calc.arr * 100)}%)."
          },
          assumptionContract = rrContract
        )
      )
    }

    // If neither OR/RR/NNT was reported next to the 2x2 counts, still emit exact reconstruction summary (INFO/PASS)
    if (out.isEmpty()) {
      val pConflict = tbl.reportedP != null &&
        ((tbl.reportedP < 0.05 && calc.chiSquareP > 0.08 && calc.fisherExactTwoSidedP > 0.08) ||
          (tbl.reportedP >= 0.05 && calc.chiSquareP < 0.025 && calc.fisherExactTwoSidedP < 0.025))
      val nntSuffix = ", ${calc.nntSignedLabel}"
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "2x2 Exact Reconstruction",
          reportedValue = tbl.reportedP?.let { "$cellSummary (p=${formatP(it)})" } ?: cellSummary,
          recomputedValue = "OR=${formatNum(calc.or)}, RR=${formatNum(calc.rr)}, ARR=${formatNum(calc.arr * 100)}%$nntSuffix, Fisher p=${formatP(calc.fisherExactTwoSidedP)}",
          tolerance = "Exact Fisher & χ²(1)",
          sourceEvidence = tbl.sourceQuote,
          pageNumber = tbl.page,
          section = tbl.section,
          assumptions = assumptions,
          status = if (pConflict) QuantFindingStatus.FAIL else QuantFindingStatus.PASS,
          findingType = if (pConflict) StatisticalFindingType.ARITHMETIC_ERROR else StatisticalFindingType.PASS,
          scientificConsequence = if (pConflict) "2x2 significance decision disagrees with both exact Fisher and Pearson χ² tests." else "Independent 2x2 OR/RR/ARR/NNT and Fisher exact p reconstructed.",
          detail = if (pConflict) "Reported p=${formatP(tbl.reportedP ?: 1.0)} conflicts with exact Fisher p=${formatP(calc.fisherExactTwoSidedP)} and Pearson χ² p=${formatP(calc.chiSquareP)}."
          else "Reconstructed from $cellSummary: OR=${formatNum(calc.or)} (${formatNum(calc.orLower)}–${formatNum(calc.orUpper)}), RR=${formatNum(calc.rr)}, Fisher p=${formatP(calc.fisherExactTwoSidedP)}$nntSuffix.",
          assumptionContract = orContract
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
      else "Exact ratio $num/$den = ${formatNum(exactPct)}%, differing from reported ${formatNum(repPct)}% by ${formatNum(diff)} percentage points.",
      assumptionContract = CalculatorAssumptionContract(
        calculatorScope = "Binomial proportion & Wilson score CI",
        assumptionsSatisfied = listOf("Exact integer numerator and denominator extracted"),
        assumptionsUnknown = listOf("Whether percentage was survey-weighted or missing-excluded"),
        alternativeValidInterpretations = listOf("Weighted survey proportions can differ from unweighted raw x/n counts")
      )
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
        val isMultiplicityAdjusted = claim.parseNote.contains("Multiplicity-adjusted", ignoreCase = true)
        when {
          diff <= 0.005 || (effectiveP < 0.001 && repP < 0.001) -> Triple(QuantFindingStatus.PASS, StatisticalFindingType.PASS, "Exact $tailLabel p = ${formatP(effectiveP)} matches reported p = ${formatP(repP)}.")
          isMultiplicityAdjusted && repP >= effectiveP - 0.002 -> Triple(QuantFindingStatus.PASS, StatisticalFindingType.PASS, "Reported multiplicity-adjusted p = ${formatP(repP)} is validly >= raw $tailLabel p = ${formatP(effectiveP)}.")
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
      detail = detail,
      assumptionContract = CalculatorAssumptionContract(
        calculatorScope = "Test-statistic p-value recomputation (${claim.testStatisticName})",
        assumptionsSatisfied = listOf("Tail specification: $tailLabel", "Threshold α=${formatNum(alpha)}"),
        assumptionsUnknown = listOf("Whether reported p-value was multiplicity-adjusted (Bonferroni/Holm/FDR) or permutation-based"),
        alternativeValidInterpretations = listOf("Reported p > exact p may reflect multiplicity adjustment or exact/permutation p-values")
      )
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
      else "β=${formatNum(beta)} ± ${formatNum(crit)}×SE(${formatNum(se)}) matches reported interval/p-value.",
      assumptionContract = CalculatorAssumptionContract(
        calculatorScope = "Regression β/SE/CI/p Wald reconstruction",
        assumptionsSatisfied = listOf("Linear-scale β and positive SE extracted"),
        assumptionsUnknown = listOf("Finite-sample degrees of freedom (Satterthwaite/Kenward-Roger)", "Profile-likelihood or bootstrap CI vs Wald CI"),
        alternativeValidInterpretations = listOf("Profile-likelihood or BCa bootstrap CIs may legitimately deviate from symmetric Wald β ± z·SE")
      )
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

    if (!est.isFinite() || !lo.isFinite() || !hi.isFinite() || est <= 0.0 || lo < 0.0 || hi <= 0.0) {
      return NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival CI Domain ($label)",
        reportedValue = claim.sourceQuote,
        recomputedValue = "Positive finite ratio required (est > 0, bounds >= 0)",
        tolerance = "Strict positivity & finiteness",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("$label is strictly positive and finite on the multiplicative scale."),
        status = QuantFindingStatus.FAIL,
        findingType = StatisticalFindingType.ARITHMETIC_ERROR,
        scientificConsequence = "Impossible non-positive or non-finite ratio/bound.",
        detail = "$label = $est with bounds [$lo, $hi] violates strict positivity on the multiplicative scale (possible sign error, separation, or log-scale confusion).",
        assumptionContract = CalculatorAssumptionContract(
          calculatorScope = "Ratio / Survival CI positivity check ($label)",
          assumptionsSatisfied = listOf("Multiplicative scale requires strictly positive finite values (> 0)"),
          assumptionsUnknown = listOf("Whether value was mistakenly reported on log-scale (ln OR / ln HR)"),
          alternativeValidInterpretations = listOf("Complete separation can produce infinite/zero MLE unless penalized (Firth)")
        )
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
        detail = "Reported $label=$est lies outside reported bounds [$lo, $hi].",
        assumptionContract = CalculatorAssumptionContract(
          calculatorScope = "CI bound ordering ($label)",
          assumptionsSatisfied = listOf("Standard frequentist/Bayesian interval contains point estimate"),
          assumptionsUnknown = listOf("None — inverted or non-covering bounds indicate transcription or computation error"),
          alternativeValidInterpretations = emptyList()
        )
      )
    }

    // Valid point estimate with a zero lower bound (e.g., PR = 2.21, 95% CI 0.00 to 4.50)
    // reflects high variance / linear-scale truncation or sparse events — NOT an arithmetic error.
    if (lo == 0.0) {
      return NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Interval Precision ($label)",
        reportedValue = "$label=$est [$lo, $hi]",
        recomputedValue = "Wide interval spanning 0.00 to $hi (contains point estimate $est)",
        tolerance = "Precision & variance diagnostic",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf(
          "Valid confidence interval containing the positive point estimate; lower bound at 0.00 can arise from linear-scale truncation, random-effects heterogeneity, or sparse data."
        ),
        status = QuantFindingStatus.WARN,
        findingType = StatisticalFindingType.IMPRECISION_OR_HIGH_VARIANCE,
        isScreeningSignal = true,
        scientificConsequence = "Wide interval spanning zero indicates high imprecision or heterogeneity rather than a confirmed arithmetic error.",
        detail = "$label=$est (95% CI $lo to $hi) is geometrically valid (est lies inside bounds) but exhibits extreme imprecision/high variance. Report prediction intervals, influence diagnostics, leave-one-out analysis, and sensitivity analyses under prespecified outlier rules.",
        assumptionContract = CalculatorAssumptionContract(
          calculatorScope = "Wide / zero-truncated CI precision diagnostic ($label)",
          assumptionsSatisfied = listOf("Point estimate $est lies within [$lo, $hi]"),
          assumptionsUnknown = listOf("Whether CI was truncated at zero on linear scale or driven by between-study heterogeneity"),
          alternativeValidInterpretations = listOf("Linear-scale random-effects pooling or Hartung-Knapp intervals under high I² can legitimately touch 0.00")
        )
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

    val ratioContract = CalculatorAssumptionContract(
      calculatorScope = "Ratio / Survival log-scale CI reconstruction ($label)",
      assumptionsSatisfied = listOf("Positive finite ratio and ordered bounds"),
      assumptionsUnknown = buildList {
        add("Wald vs profile-likelihood vs bootstrap CI construction")
        if (label in setOf("HR", "AHR")) add("Proportional hazards assumption over follow-up")
        add("Adjusted vs unadjusted covariate set")
      },
      alternativeValidInterpretations = listOf(
        "Profile-likelihood, exact, or bootstrap intervals are legitimately asymmetric on the log scale",
        "Adjusted p-values (Bonferroni/FDR) can be larger than unadjusted Wald p implied by a 95% CI"
      )
    )

    // Assumption-aware CI-vs-p consistency check (P0-07):
    // Respects the actual confidence level (e.g., 90% CI -> α=0.10, 95% CI -> α=0.05, 99% CI -> α=0.01),
    // multiplicity-adjusted p-values (Bonferroni/Holm/FDR/q-value), and one-sided tests.
    val alphaLevel = (1.0 - claim.ciLevel).coerceIn(0.001, 0.25)
    val ciPctLabel = "${(claim.ciLevel * 100.0).roundToInt()}%"
    val quoteLower = claim.sourceQuote.lowercase(Locale.US)
    val isNonFrequentistInterval = claim.intervalType in setOf(
      IntervalType.CREDIBLE_INTERVAL,
      IntervalType.UNCERTAINTY_INTERVAL,
      IntervalType.PREDICTION_INTERVAL
    ) || Regex("""\b(?:CrI|UI|PI|credible\s+interval|uncertainty\s+interval|prediction\s+interval)\b""", RegexOption.IGNORE_CASE)
      .containsMatchIn(claim.sourceQuote)
    val isAdjustedP = Regex(
      """\b(?:adjusted\s+p|p[\s_-]?adj|bonferroni|holm|hochberg|benjamini|fdr|q[\s-]?value|multiplicity[- ]adjusted|corrected\s+p)\b""",
      RegexOption.IGNORE_CASE
    ).containsMatchIn(quoteLower)
    val isOneSided = Regex(
      """\b(?:one[- ]sided|one[- ]tailed|1[- ]sided|1[- ]tailed)\b""",
      RegexOption.IGNORE_CASE
    ).containsMatchIn(quoteLower)
    val ciExcludesNull = lo > 1.005 || hi < 0.995
    val ciIncludesNull = lo < 0.99 && hi > 1.01
    val repP = claim.pValue
    val upperAlphaMargin = alphaLevel * 1.25
    val lowerAlphaMargin = alphaLevel * 0.75
    val rawPDisagreesWithCi = repP != null && claim.pValueOperator == "=" &&
      ((ciExcludesNull && repP > upperAlphaMargin) || (ciIncludesNull && repP < lowerAlphaMargin))
    val pContradictsCi = rawPDisagreesWithCi && !isNonFrequentistInterval && !isAdjustedP && !isOneSided
    val pRequiresProcedureVerification = rawPDisagreesWithCi && !isNonFrequentistInterval && (isAdjustedP || isOneSided)

    return when {
      isNonFrequentistInterval -> NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Bayesian / Predictive Interval Geometry ($label • ${claim.intervalType.label})",
        reportedValue = claim.sourceQuote,
        recomputedValue = "Ordered positive bounds [$lo, $hi] containing $label=$est; frequentist Wald CI-vs-p test not applicable to ${claim.intervalType.label}.",
        tolerance = "Interval bound ordering (Bayesian/predictive guard)",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("Interval explicitly reported as ${claim.intervalType.label}; frequentist Wald two-sided p-value equivalence does not apply."),
        status = QuantFindingStatus.PASS,
        findingType = StatisticalFindingType.PASS,
        scientificConsequence = "Bounds are geometrically valid; frequentist CI-vs-p contradiction rules are intentionally suppressed for Bayesian credible, uncertainty, and prediction intervals.",
        detail = "$label=$est (${claim.intervalType.label} $lo–$hi) is properly ordered; skipped frequentist Wald CI-vs-p contradiction flag.",
        assumptionContract = ratioContract
      )
      pContradictsCi -> NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival CI-vs-p ($label)",
        reportedValue = "${claim.sourceQuote}, p=${formatP(repP)}",
        recomputedValue = "Implied Wald SE(ln)=${formatNum(impliedSe)}, p≈${formatP(impliedP)} ($ciPctLabel CI -> α=${formatNum(alphaLevel)})",
        tolerance = "$ciPctLabel CI null-crossing vs two-sided α=${formatNum(alphaLevel)}",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("$ciPctLabel CI exclusion of 1.0 corresponds to two-sided unadjusted Wald p < ${formatNum(alphaLevel)}."),
        status = QuantFindingStatus.FAIL,
        findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
        scientificConsequence = "Confidence interval and p-value give contradictory statistical significance conclusions under unadjusted two-sided testing at α=${formatNum(alphaLevel)}.",
        detail = "$label=$est ($ciPctLabel CI $lo–$hi) ${if (ciExcludesNull) "excludes" else "crosses"} 1.0, contradicting reported p=${formatP(repP)} relative to α=${formatNum(alphaLevel)} (implied Wald p≈${formatP(impliedP)}). Verify whether p-value was multiplicity-adjusted or computed from a different test.",
        assumptionContract = ratioContract
      )
      pRequiresProcedureVerification -> NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Survival CI-vs-p ($label • Adjusted/One-Sided Guard)",
        reportedValue = "${claim.sourceQuote}, p=${formatP(repP)}",
        recomputedValue = "Implied unadjusted two-sided Wald p≈${formatP(impliedP)} ($ciPctLabel CI -> α=${formatNum(alphaLevel)})",
        tolerance = "Adjusted / one-sided testing procedure verification",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("Multiplicity-adjusted or one-sided p-values can legitimately differ from an unadjusted two-sided $ciPctLabel CI."),
        status = QuantFindingStatus.WARN,
        findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
        isScreeningSignal = true,
        scientificConsequence = "Requires verification of whether the CI and p-value use matching adjustment/sidedness rather than indicating a confirmed contradiction.",
        detail = "$label=$est ($ciPctLabel CI $lo–$hi) differs from reported p=${formatP(repP)}, but context indicates ${if (isAdjustedP) "multiplicity-adjusted p-values" else "one-sided testing"}; verify CI/p procedure alignment.",
        assumptionContract = ratioContract
      )
      (hi / lo >= 10.0) || (hi / est >= 2.0 && (hi - lo) >= 2.0) -> NumericalReconstructionResult(
        checkId = checkId,
        calculatorName = "Ratio / Interval Precision ($label)",
        reportedValue = "$label=$est [$lo, $hi]",
        recomputedValue = "Implied SE(ln)=${formatNum(impliedSe)}, width ratio=${formatNum(hi / lo)}x",
        tolerance = "Precision & variance diagnostic",
        sourceEvidence = claim.sourceQuote,
        pageNumber = claim.page,
        section = claim.section,
        assumptions = listOf("Point estimate lies inside its confidence interval; wide bounds reflect imprecision or high heterogeneity rather than an arithmetic defect."),
        status = QuantFindingStatus.WARN,
        findingType = StatisticalFindingType.IMPRECISION_OR_HIGH_VARIANCE,
        isScreeningSignal = true,
        scientificConsequence = "Wide interval reflects substantial uncertainty or between-study variance.",
        detail = "$label=$est ($lo–$hi) is geometrically valid but highly imprecise (width ratio ${formatNum(hi / lo)}x). Report prediction intervals, influence diagnostics, leave-one-out analysis, and sensitivity analyses under prespecified outlier rules.",
        assumptionContract = ratioContract
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
        detail = "Log-scale midpoint (${formatNum(exp(logMid))}) deviates from reported $label=$est by ${(relAsym * 100).toInt()}% of log half-width; verify CI method (Wald vs profile/bootstrap) or transcription.",
        assumptionContract = ratioContract
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
        detail = "$label=$est ($lo–$hi) is log-symmetric (implied SE=${formatNum(impliedSe)}).",
        assumptionContract = ratioContract
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

    // DerSimonian-Laird pooled random-effects estimate (strictly using tau2DL)
    val wDL = v.map { 1.0 / (it + tau2DL) }
    val sumWDL = wDL.sum()
    val muDL = y.indices.sumOf { wDL[it] * y[it] } / sumWDL
    val seDL = sqrt(1.0 / sumWDL)

    // Paule-Mandel iterative τ² (solves Q_gen(τ²) = k - 1) and pooled random-effects estimate
    val tau2PM = solvePauleMandelTau2(y, v, tau2DL)
    val tau2Use = tau2PM
    val wRE = v.map { 1.0 / (it + tau2Use) }
    val sumWRE = wRE.sum()
    val muPM = y.indices.sumOf { wRE[it] * y[it] } / sumWRE
    val sePM = sqrt(1.0 / sumWRE)

    // Hartung-Knapp-Sidik-Jonkman (HKSJ) t-CI (using Paule-Mandel τ²)
    val qHksj = y.indices.sumOf { wRE[it] * (y[it] - muPM) * (y[it] - muPM) } / df.toDouble()
    val seHK = sqrt(max(1.0, qHksj) / sumWRE)
    val tCritHK = studentTQuantile(0.975, df.toDouble())
    val hkLo = muPM - tCritHK * seHK
    val hkHi = muPM + tCritHK * seHK

    // 95% Prediction Interval (requires k >= 3, df = k - 2)
    val (piLo, piHi) = if (k >= 3) {
      val tCritPI = studentTQuantile(0.975, (k - 2).toDouble())
      val halfPI = tCritPI * sqrt(tau2Use + sePM * sePM)
      (muPM - halfPI) to (muPM + halfPI)
    } else null to null

    // Leave-One-Out (LOO) Influence Diagnostics (P1-09):
    // Re-estimates τ²_PM for every omitted study AND evaluates significance with the same HKSJ
    // confidence-interval method as the full meta-analysis (consistent inference).
    var maxShift = 0.0
    var dominantStudy: String? = null
    var crossesNull = false
    if (k >= 3) {
      val baseSignificant = (hkLo > 0.0 && hkHi > 0.0) || (hkLo < 0.0 && hkHi < 0.0)
      for (dropIdx in studies.indices) {
        val ySub = y.filterIndexed { i, _ -> i != dropIdx }
        val vSub = v.filterIndexed { i, _ -> i != dropIdx }
        val kSub = ySub.size
        val dfSub = kSub - 1
        val wFeSub = vSub.map { 1.0 / it }
        val sumWFeSub = wFeSub.sum()
        val muFeSub = ySub.indices.sumOf { wFeSub[it] * ySub[it] } / sumWFeSub
        val qSub = ySub.indices.sumOf { wFeSub[it] * (ySub[it] - muFeSub) * (ySub[it] - muFeSub) }
        val cSub = sumWFeSub - (wFeSub.sumOf { it * it } / sumWFeSub)
        val tau2DlSub = if (cSub > 0.0) max(0.0, (qSub - dfSub) / cSub) else 0.0
        val tau2PmSub = solvePauleMandelTau2(ySub, vSub, tau2DlSub)

        val wSub = vSub.map { 1.0 / (it + tau2PmSub) }
        val sumWSub = wSub.sum()
        val muSub = ySub.indices.sumOf { wSub[it] * ySub[it] } / sumWSub
        val qHksjSub = ySub.indices.sumOf { wSub[it] * (ySub[it] - muSub) * (ySub[it] - muSub) } / dfSub.toDouble()
        val seHkSub = sqrt(max(1.0, qHksjSub) / sumWSub)
        val tCritSub = studentTQuantile(0.975, dfSub.toDouble())
        val hkSubLo = muSub - tCritSub * seHkSub
        val hkSubHi = muSub + tCritSub * seHkSub

        val shift = abs(muSub - muPM)
        if (shift > maxShift) {
          maxShift = shift
          dominantStudy = studies[dropIdx].studyLabel
        }
        val subSig = (hkSubLo > 0.0 && hkSubHi > 0.0) || (hkSubLo < 0.0 && hkSubHi < 0.0)
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
      randomEffectDL = muDL,
      randomSeDL = seDL,
      randomLowerDL = muDL - z95 * seDL,
      randomUpperDL = muDL + z95 * seDL,
      hartungKnappLower = hkLo,
      hartungKnappUpper = hkHi,
      predictionIntervalLower = piLo,
      predictionIntervalUpper = piHi,
      mostInfluentialStudy = dominantStudy,
      maxLooShift = maxShift,
      looCrossesNull = crossesNull,
      randomEffectPM = muPM,
      randomSePM = sePM,
      randomLowerPM = muPM - z95 * sePM,
      randomUpperPM = muPM + z95 * sePM
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
    val hasNonIndependentCues = lower.contains("multiple effect sizes per study") ||
      lower.contains("multiple effects per study") ||
      lower.contains("correlated outcomes") ||
      lower.contains("non-independent") ||
      lower.contains("nested within studies") ||
      lower.contains("shared control")
    val modelsDependence = lower.contains("robust variance") || lower.contains("rve") ||
      lower.contains("three-level") || lower.contains("multilevel") || lower.contains("sandwich variance")

    val metaContract = CalculatorAssumptionContract(
      calculatorScope = "Random-effects meta-analysis (IVW / DL / PM / HKSJ / PI)",
      assumptionsSatisfied = listOf("k=${summary.k} study effects with positive sampling variances on ${if (isLog) "log-ratio" else "additive"} scale"),
      assumptionsUnknown = buildList {
        add("Strict independence across extracted study effect sizes")
        add("Between-study effect normality for 95% prediction interval")
        if (summary.k <= 5) add("Small-k (k=${summary.k}) HKSJ/REML variance stability")
      },
      alternativeValidInterpretations = listOf(
        "Correlated / nested study effects require three-level meta-analysis or robust variance estimation (RVE)",
        "Bayesian hierarchical pooling is a valid alternative when k is small"
      )
    )

    out.add(
      NumericalReconstructionResult(
        checkId = nextId(),
        calculatorName = "Meta-Analysis Pooling (IVW/PM/HKSJ/PI)",
        reportedValue = "k=${summary.k} study effects extracted",
        recomputedValue = "RE(PM)=${fmtEst(summary.randomEffectPM)} (HKSJ ${fmtEst(summary.hartungKnappLower)}–${fmtEst(summary.hartungKnappUpper)}), RE(DL)=${fmtEst(summary.randomEffectDL)}, I²=${formatNum(summary.iSquaredPct)}%, τ²_PM=${formatNum(summary.tauSquaredPM)}, 95% PI $piStr",
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
        detail = "Reconstructed across k=${summary.k} studies: Q=${formatNum(summary.cochranQ)} (p=${formatP(summary.qPValue)}), I²=${formatNum(summary.iSquaredPct)}%, τ²_DL=${formatNum(summary.tauSquaredDL)}, τ²_PM=${formatNum(summary.tauSquaredPM)}, 95% PI=$piStr.",
        assumptionContract = metaContract
      )
    )

    if (hasNonIndependentCues && !modelsDependence) {
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "Meta-Analysis Effect-Size Independence Guard",
          reportedValue = "k=${summary.k} extracted effects with non-independence cues",
          recomputedValue = "Standard IVW/DL/PM assumes independent effects; requires RVE or three-level model",
          tolerance = "Within-study covariance assumption",
          sourceEvidence = studies.take(3).joinToString("; ") { it.studyLabel },
          pageNumber = studies.first().page,
          section = "Results",
          assumptions = listOf("Standard two-level meta-analysis assumes Cov(e_i, e_j) = 0 across all rows."),
          status = QuantFindingStatus.WARN,
          findingType = StatisticalFindingType.ASSUMPTION_UNCHECKED,
          isScreeningSignal = true,
          scientificConsequence = "Treating multiple correlated effects from the same study/shared control as independent understates pooled SE.",
          detail = "Non-independent effect sizes (multiple effects per study / correlated outcomes / shared control) detected without RVE or three-level nesting; standard IVW/PM SE is only an approximation.",
          assumptionContract = metaContract
        )
      )
    }

    if (summary.looCrossesNull && summary.mostInfluentialStudy != null) {
      out.add(
        NumericalReconstructionResult(
          checkId = nextId(),
          calculatorName = "Meta-Analysis Leave-One-Out Influence",
          reportedValue = "Pooled RE(PM)=${fmtEst(summary.randomEffectPM)}",
          recomputedValue = "Dropping '${summary.mostInfluentialStudy}' shifts estimate by ${formatNum(summary.maxLooShift)} & crosses null (re-estimated τ²_PM + HKSJ)",
          tolerance = "LOO significance stability (re-estimated τ²_PM + HKSJ)",
          sourceEvidence = "Most influential study: ${summary.mostInfluentialStudy}",
          pageNumber = studies.first().page,
          section = "Results",
          assumptions = listOf("Leave-one-out random-effects sensitivity re-estimating Paule-Mandel τ² and HKSJ CI for each omitted study."),
          status = QuantFindingStatus.WARN,
          findingType = StatisticalFindingType.POSSIBLE_BIAS,
          isScreeningSignal = true,
          scientificConsequence = "Pooled significance depends on a single influential study ('${summary.mostInfluentialStudy}').",
          detail = "Leave-one-out sensitivity (with re-estimated τ²_PM and HKSJ intervals) shows omitting '${summary.mostInfluentialStudy}' renders the pooled effect non-significant.",
          assumptionContract = metaContract
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
