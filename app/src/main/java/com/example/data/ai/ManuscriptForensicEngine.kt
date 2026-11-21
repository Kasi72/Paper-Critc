package com.example.data.ai

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class StudyDesignArchetype(
  val displayName: String,
  val reportingGuideline: String,
  val riskOfBiasInstrument: String,
  val certaintyFramework: String,
  val auditGuidance: String
) {
  SYSTEMATIC_REVIEW_META_ANALYSIS(
    displayName = "Systematic Review & Meta-Analysis",
    reportingGuideline = "PRISMA 2020 (Reporting Completeness)",
    riskOfBiasInstrument = "AMSTAR-2 / ROBIS (Review Quality) & RoB 2 / NOS / JBI (Primary Study Risk of Bias)",
    certaintyFramework = "GRADE (Certainty of Evidence)",
    auditGuidance = "Distinguish reporting guidelines (PRISMA 2020), review/primary-study risk-of-bias tools (AMSTAR-2, ROBIS, RoB 2, NOS, JBI), and certainty-of-evidence frameworks (GRADE) — do NOT treat them as interchangeable. Audit PROSPERO registration, search cutoff & reproducibility, dual/independent screening & extraction, heterogeneity (I²/τ²/meta-regression/Prediction Intervals), case-mix/construct harmonization, and clinical/policy transferability (including HIC vs. LMIC skew and need vs. utilization). Do NOT critique absence of ML random seeds or neural network splits."
  ),
  CLINICAL_EPIDEMIOLOGICAL(
    displayName = "Clinical / Epidemiological Study",
    reportingGuideline = "CONSORT (Trials) / STROBE (Observational)",
    riskOfBiasInstrument = "RoB 2 (RCTs) / ROBINS-I / Newcastle-Ottawa Scale",
    certaintyFramework = "GRADE Clinical Certainty & Minimal Important Difference (MID)",
    auditGuidance = "Distinguish reporting (CONSORT/STROBE) from internal validity (RoB 2/ROBINS-I) and clinical utility. Audit patient representativeness, clinical vs. statistical significance (ARR, NNT, MID), benefits vs. harms balance, confounding & adjustment sets, attrition/missingness, and transferability to resource-constrained settings."
  ),
  MACHINE_LEARNING_AI(
    displayName = "Machine Learning / Computational Modeling",
    reportingGuideline = "TRIPOD+AI / CLAIM / NeurIPS Reproducibility Checklist",
    riskOfBiasInstrument = "PROBAST+AI / Leakage & Generalization Audit",
    certaintyFramework = "Out-of-Sample Calibration & External Validation",
    auditGuidance = "Audit train/validation/test split hygiene, entity/subject/temporal leakage, hyperparameter tuning on evaluation sets, baseline fairness, statistical significance across multiple seeds, calibration (ECE/Brier), and replication code availability."
  ),
  ECONOMETRICS_CAUSAL(
    displayName = "Econometrics & Causal Inference",
    reportingGuideline = "AEA / JPE Empirical Transparency Standards",
    riskOfBiasInstrument = "Identification Strategy & Sensitivity Analysis (Oster / Rambachan-Roth)",
    certaintyFramework = "Causal Credibility & External Validity",
    auditGuidance = "Audit identification assumptions (parallel trends, exclusion restriction, conditional independence), bad controls/collider bias, look-ahead or survivorship bias, standard error clustering (G < 42), and specification robustness."
  ),
  THEORETICAL_FORMAL(
    displayName = "Theoretical / Formal Mathematical Paper",
    reportingGuideline = "Formal Proof & Notation Standards",
    riskOfBiasInstrument = "Axiomatic & Boundary-Condition Audit",
    certaintyFramework = "Deductive Validity & Tightness of Bounds",
    auditGuidance = "Audit axioms, regularity/convexity/boundary assumptions, step-by-step proof completeness, notation/index consistency, edge cases, and non-triviality of theorems."
  ),
  GENERAL_EMPIRICAL(
    displayName = "Empirical & Methodological Research",
    reportingGuideline = "Discipline-Appropriate Transparency Checklist",
    riskOfBiasInstrument = "Construct, Internal & External Validity Appraisal",
    certaintyFramework = "Evidential Strength & Replicability",
    auditGuidance = "Audit construct-to-measure validity, sampling/selection bias, quantitative consistency, omitted confounds, transparent reporting, and reproducibility."
  )
}

data class ForensicPreFlightReport(
  val wordCount: Int,
  val estimatedPages: Int,
  val detectedDiscipline: String,
  val studyDesign: StudyDesignArchetype = StudyDesignArchetype.GENERAL_EMPIRICAL,
  val empiricalSignalsCount: Int,
  val theoreticalSignalsCount: Int,
  val extractedEquations: List<String>,
  val symbolAndNotationAlerts: List<String>,
  val extractedSampleSizes: List<String>,
  val statisticalClaims: List<String>,
  val grimAndNumericAlerts: List<String>,
  val proceduralLeakageAlerts: List<String>,
  val clinicalApplicabilityNotes: List<String> = emptyList(),
  val citationAlerts: List<String>,
  val replicationCodeAlerts: List<String>,
  val citationCountEstimate: Int,
  val quantForensics: QuantitativeForensicReport = QuantitativeForensicReport(
    statcheckResults = emptyList(),
    confidenceIntervalChecks = emptyList(),
    medianIqrChecks = emptyList(),
    heterogeneitySignals = emptyList(),
    powerAndMultiplicityAlerts = emptyList(),
    algebraicAndMetricAlerts = emptyList(),
    diagnosticTable = emptyList(),
    extractedPrecisionMetrics = emptyList()
  )
) {
  fun toStructuredPromptContext(): String = buildString {
    appendLine("=== DETERMINISTIC FORENSIC PRE-FLIGHT SCAN ===")
    appendLine("Word Count: $wordCount words (~$estimatedPages pages) | Detected Domain: $detectedDiscipline")
    appendLine("Detected Study Design: ${studyDesign.displayName}")
    appendLine("  • Reporting Guideline: ${studyDesign.reportingGuideline}")
    appendLine("  • Risk-of-Bias / Quality Instrument: ${studyDesign.riskOfBiasInstrument}")
    appendLine("  • Certainty-of-Evidence Framework: ${studyDesign.certaintyFramework}")
    appendLine("Domain-Exact Audit Mandate: ${studyDesign.auditGuidance}")
    appendLine("Signal Balance: Empirical markers=$empiricalSignalsCount, Theoretical markers=$theoreticalSignalsCount | Citations detected: ~$citationCountEstimate")

    appendLine("\n[1. EXTRACTED EQUATIONS & NOTATION INVENTORY]")
    if (extractedEquations.isEmpty()) {
      appendLine("- No explicit LaTeX or symbolic display equations detected in plain text.")
    } else {
      extractedEquations.take(12).forEachIndexed { idx, eq ->
        appendLine("  Eq#${idx + 1}: $eq")
      }
    }
    if (symbolAndNotationAlerts.isNotEmpty()) {
      appendLine("  Notation Pre-Flight Alerts:")
      symbolAndNotationAlerts.forEach { appendLine("  * [MATH-ALERT] $it") }
    }

    appendLine("\n[2. SAMPLE SIZES, TEST STATISTICS & GRIM CONSISTENCY]")
    if (extractedSampleSizes.isEmpty()) {
      appendLine("- No explicit sample size declarations (N = ...) matched.")
    } else {
      appendLine("  Sample Sizes Found: ${extractedSampleSizes.joinToString(", ")}")
    }
    if (statisticalClaims.isNotEmpty()) {
      appendLine("  Statistical Claims Found: ${statisticalClaims.take(14).joinToString(" | ")}")
    }
    if (grimAndNumericAlerts.isNotEmpty()) {
      appendLine("  Quantitative / GRIM / Consistency Alerts:")
      grimAndNumericAlerts.forEach { appendLine("  * [NUMBERS-ALERT] $it") }
    }

    appendLine("\n[3. DESIGN-SPECIFIC METHODOLOGICAL & REPORTING CHECKS (Verify in Complete Text)]")
    if (proceduralLeakageAlerts.isEmpty()) {
      appendLine("- No immediate keyword-level methodological gaps flagged; perform deep full-text semantic appraisal.")
    } else {
      proceduralLeakageAlerts.forEach { appendLine("  * [CHECK] $it") }
    }

    if (clinicalApplicabilityNotes.isNotEmpty()) {
      appendLine("\n[4. CLINICAL & POLICY APPLICABILITY LENS]")
      clinicalApplicabilityNotes.forEach { appendLine("  * [CLINICAL/POLICY] $it") }
    }

    if (citationAlerts.isNotEmpty()) {
      appendLine("\n[5. CITATION & BIBLIOGRAPHY CROSS-CHECK]")
      citationAlerts.forEach { appendLine("  * [CITATION-ALERT] $it") }
    }

    if (replicationCodeAlerts.isNotEmpty()) {
      appendLine("\n[6. REPLICATION CODE PACKAGE STATIC AUDIT]")
      replicationCodeAlerts.forEach { appendLine("  * [CODE-FLAG] $it") }
    }

    appendLine()
    append(quantForensics.toPromptBlock())
  }
}

object ManuscriptForensicEngine {

  private val inlineMathRegex = Regex("""\$([^$\n]{2,140})\$""")
  private val displayMathRegex = Regex("""\$\$([\s\S]{2,320}?)\$\$|\\begin\{(equation|align|eqnarray)\*?\}[\s\S]{2,320}?\\end\{\2\*?\}""")
  private val unicodeEquationRegex = Regex("""([A-Za-z0-9_^{}\\]+\s*[=≈≤≥<>∈]\s*[^\n.]{3,90})""")

  private val sampleSizeRegex = Regex("""\b([Nn])\s*=\s*([0-9][0-9,]*)\b""")
  private val pValueRegex = Regex("""\bp\s*([=<≤>])\s*(0?\.\d+|\d+\s*[×x]\s*10\^\{?-?\d+\}?)""", RegexOption.IGNORE_CASE)
  private val statMetricRegex = Regex(
    """\b(t|F|z|r|R\^2|chi\^2|χ2|CER|WER|AUC|AUROC|RMSE|MAE|Sharpe|alpha|β|λ|γ)\s*(?:\([^)]+\))?\s*[=≈<>]\s*[-+]?\d+(?:\.\d+)?%?""",
    RegexOption.IGNORE_CASE
  )
  private val percentageRegex = Regex("""\b(\d{1,3}\.\d{1,2})%""")
  private val splitNumbersRegex = Regex("""(\d[\d,]*)\s*(?:train|training)\b[\s\S]{0,60}?(\d[\d,]*)\s*(?:val|validation|dev)\b[\s\S]{0,60}?(\d[\d,]*)\s*(?:test|testing|eval|evaluation)\b""", RegexOption.IGNORE_CASE)

  private val citationBracketRegex = Regex("""\[(\d+(?:\s*,\s*\d+)*)\]""")
  private val authorYearCitationRegex = Regex("""\(([A-Z][A-Za-z\-]+(?:\s+et\s+al\.)?(?:,?\s+(?:and|&)\s+[A-Z][A-Za-z\-]+)?,?\s+(?:19|20)\d{2}[a-z]?)\)""")

  fun analyzeManuscript(
    text: String,
    isEmpiricalTrack: Boolean,
    replicationCode: String = ""
  ): ForensicPreFlightReport {
    val cleanText = text.trim()
    val words = if (cleanText.isBlank()) emptyList() else cleanText.split(Regex("""\s+"""))
    val wordCount = words.size
    val estimatedPages = (wordCount / 350).coerceAtLeast(1)

    // 1. Extract equations (display + inline + symbolic lines)
    val equations = mutableListOf<String>()
    displayMathRegex.findAll(cleanText).forEach { match ->
      val normalized = match.value.replace(Regex("""\s+"""), " ").trim()
      if (normalized.length >= 4 && normalized !in equations) {
        equations.add(normalized)
      }
    }
    inlineMathRegex.findAll(cleanText).forEach { match ->
      val inner = match.groupValues[1].trim()
      if ((inner.contains("=") || inner.contains("\\") || inner.contains("_") || inner.contains("^")) &&
        inner.length >= 4 && "$$inner$" !in equations
      ) {
        equations.add("$$inner$")
      }
    }
    if (equations.size < 4) {
      unicodeEquationRegex.findAll(cleanText).forEach { match ->
        val candidate = match.groupValues[1].trim()
        if (candidate.length in 6..100 && candidate !in equations) {
          equations.add(candidate)
        }
      }
    }

    // 2. Check mathematical notation consistency
    val mathAlerts = mutableListOf<String>()
    val joinedMath = equations.joinToString(" ")
    if (joinedMath.contains("\\arg\\max") || joinedMath.contains("argmax")) {
      if (!cleanText.contains("unique", ignoreCase = true) && !cleanText.contains("tie", ignoreCase = true) && !cleanText.contains("convex", ignoreCase = true)) {
        mathAlerts.add("Optimization operator (arg max / arg min) used without explicit uniqueness, convexity, or tie-breaking regularity discussion.")
      }
    }
    if ((joinedMath.contains("\\sum_") || joinedMath.contains("∑")) && !joinedMath.contains("^")) {
      mathAlerts.add("Summation operator detected without explicit upper bound index specification; verify summation limits.")
    }
    if (joinedMath.contains("\\lambda") || joinedMath.contains("\\gamma")) {
      if (cleanText.contains("test", ignoreCase = true) && cleanText.contains("grid search", ignoreCase = true)) {
        mathAlerts.add("Regularization / penalty hyperparameters (\\lambda, \\gamma) appear alongside evaluation-set grid search references.")
      }
    }
    if (joinedMath.contains("\\varepsilon") || joinedMath.contains("epsilon")) {
      if (!cleanText.contains("heteroskedast", ignoreCase = true) && !cleanText.contains("cluster", ignoreCase = true) && !cleanText.contains("i.i.d", ignoreCase = true)) {
        mathAlerts.add("Error term (\\varepsilon) specified without explicit covariance structure, clustering, or i.i.d. regularity justification.")
      }
    }

    // 3. Extract Sample Sizes (N = ...) and check split arithmetic
    val sampleSizes = mutableListOf<String>()
    val numericSampleValues = mutableListOf<Int>()
    sampleSizeRegex.findAll(cleanText).forEach { match ->
      val symbol = match.groupValues[1]
      val rawNum = match.groupValues[2]
      val parsed = rawNum.replace(",", "").toIntOrNull()
      sampleSizes.add("$symbol = $rawNum")
      if (parsed != null && parsed > 0) {
        numericSampleValues.add(parsed)
      }
    }

    val grimAlerts = mutableListOf<String>()

    splitNumbersRegex.findAll(cleanText).forEach { match ->
      val train = match.groupValues[1].replace(",", "").toIntOrNull() ?: 0
      val valid = match.groupValues[2].replace(",", "").toIntOrNull() ?: 0
      val test = match.groupValues[3].replace(",", "").toIntOrNull() ?: 0
      val splitSum = train + valid + test
      sampleSizes.add("Split(train=$train, val=$valid, test=$test; sum=$splitSum)")
      if (numericSampleValues.isNotEmpty() && splitSum !in numericSampleValues) {
        val closestN = numericSampleValues.minByOrNull { abs(it - splitSum) } ?: splitSum
        if (closestN != splitSum) {
          grimAlerts.add("Sample size mismatch: Train ($train) + Val ($valid) + Test ($test) = $splitSum, which differs from reported N = $closestN.")
        }
      }
    }

    // Extract p-values and test statistics
    val statClaims = mutableListOf<String>()
    pValueRegex.findAll(cleanText).forEach { match ->
      val claim = match.value.trim()
      statClaims.add(claim)
      val valStr = match.groupValues[2].toDoubleOrNull()
      if (valStr != null && valStr in 0.041..0.049) {
        grimAlerts.add("Marginal p-value near alpha threshold ($claim): high fragility to multiple-testing adjustment or specification curve variance.")
      }
    }
    statMetricRegex.findAll(cleanText).forEach { match ->
      val claim = match.value.trim()
      if (claim !in statClaims) statClaims.add(claim)
    }

    // GRIM check when N < 100
    val smallSampleN = numericSampleValues.filter { it in 5..95 }
    if (smallSampleN.isNotEmpty()) {
      val nRef = smallSampleN.first()
      percentageRegex.findAll(cleanText).take(6).forEach { match ->
        val pct = match.groupValues[1].toDoubleOrNull()
        if (pct != null) {
          val impliedCount = (pct / 100.0) * nRef
          val nearestHalfOrInt = (impliedCount * 2.0).roundToInt() / 2.0
          if (abs(impliedCount - nearestHalfOrInt) > 0.04) {
            grimAlerts.add(
              String.format(
                Locale.US,
                "GRIM Granularity Check: Reported %.2f%% with N=%d implies %.2f observations (verify exact denominator).",
                pct,
                nRef,
                impliedCount
              )
            )
          }
        }
      }
    }

    // 4. Study Design Archetype Classification
    val lower = cleanText.lowercase(Locale.US)
    val isSystematicReview = lower.contains("systematic review") ||
      lower.contains("meta-analysis") ||
      lower.contains("meta analysis") ||
      lower.contains("meta-analyses") ||
      lower.contains("prisma") ||
      (lower.contains("pubmed") && (lower.contains("embase") || lower.contains("psycinfo") || lower.contains("cochrane")))
    val isMlPaper = lower.contains("neural network") ||
      lower.contains("transformer") ||
      lower.contains("train/val/test") ||
      lower.contains("hyperparameter") ||
      lower.contains("fine-tun") ||
      lower.contains("crnn") ||
      lower.contains("llm") ||
      lower.contains("language model") ||
      lower.contains("character error rate")
    val isEconPaper = lower.contains("econometric") ||
      lower.contains("difference-in-differences") ||
      lower.contains("instrumental variable") ||
      lower.contains("fixed effects") ||
      lower.contains("abnormal return") ||
      lower.contains("volatility") ||
      lower.contains("equity") ||
      lower.contains("backtest")
    val isClinicalPaper = lower.contains("psychiatric") ||
      lower.contains("clinical") ||
      lower.contains("patient") ||
      lower.contains("inpatient") ||
      lower.contains("epidemiolog") ||
      lower.contains("randomized controlled") ||
      lower.contains("cohort") ||
      lower.contains("hospital")

    val empiricalKeywords = listOf("dataset", "sample", "regression", "experiment", "train", "test", "p <", "baseline", "ablation", "empirical", "survey", "clinical", "systematic review", "meta-analysis", "cohort")
    val theoryKeywords = listOf("theorem", "lemma", "proof", "corollary", "proposition", "axiom", "banach", "asymptotic", "closed-form")
    val empHits = empiricalKeywords.count { lower.contains(it) }
    val theoHits = theoryKeywords.count { lower.contains(it) }

    val studyDesign = when {
      !isEmpiricalTrack || (theoHits >= 3 && empHits <= 1) -> StudyDesignArchetype.THEORETICAL_FORMAL
      isSystematicReview -> StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS
      isMlPaper -> StudyDesignArchetype.MACHINE_LEARNING_AI
      isEconPaper -> StudyDesignArchetype.ECONOMETRICS_CAUSAL
      isClinicalPaper -> StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL
      else -> StudyDesignArchetype.GENERAL_EMPIRICAL
    }

    // 5. Study-Design-Aware Methodological, Reporting & Risk-of-Bias Checks (nuanced, non-overclaiming)
    val leakageAlerts = mutableListOf<String>()

    when (studyDesign) {
      StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS -> {
        if (!lower.contains("prospero") && !lower.contains("pre-regist") && !lower.contains("preregist") && !lower.contains("osf.io") && !lower.contains("protocol")) {
          leakageAlerts.add(
            "[PROTOCOL REGISTRATION CHECK] No PROSPERO, OSF, or a priori protocol identifier matched in extracted text; confirm in Methods/Supplement."
          )
        }
        val hasIndependentScreeningOrExtraction = lower.contains("kappa") ||
          lower.contains("inter-rater") ||
          lower.contains("interrater") ||
          lower.contains("independent reviewer") ||
          lower.contains("independently extracted") ||
          lower.contains("independently screened") ||
          lower.contains("two reviewers") ||
          lower.contains("two authors") ||
          lower.contains("three authors") ||
          lower.contains("duplicate extraction")
        if (!hasIndependentScreeningOrExtraction) {
          leakageAlerts.add(
            "[SCREENING / EXTRACTION RELIABILITY CHECK] No explicit independent/duplicate screening or inter-rater reliability (Cohen's κ) statement matched; verify in Methods."
          )
        }

        val hasValidatedRobTool = lower.contains("rob 2") || lower.contains("rob2") ||
          lower.contains("robins-i") || lower.contains("newcastle-ottawa") ||
          lower.contains("jbi") || lower.contains("amstar") || lower.contains("cochrane risk of bias")
        val hasCustomQualityScore = lower.contains("quality score") ||
          lower.contains("quality-scoring") ||
          lower.contains("quality scoring") ||
          lower.contains("quality assessment") ||
          lower.contains("quality appraisal") ||
          lower.contains("risk of bias")
        if (!hasValidatedRobTool && hasCustomQualityScore) {
          leakageAlerts.add(
            "[QUALITY APPRAISAL INSTRUMENT NOTE] A study quality-scoring approach is described, rather than a validated domain-based Risk-of-Bias instrument (e.g., RoB 2, ROBINS-I, NOS, JBI, AMSTAR-2); note limitations of composite quality scores without claiming appraisal was absent."
          )
        } else if (!hasValidatedRobTool && !hasCustomQualityScore) {
          leakageAlerts.add(
            "[RISK-OF-BIAS INSTRUMENT CHECK] No primary-study Risk-of-Bias tool (RoB 2, NOS, JBI) or quality-scoring procedure matched in extracted text; verify Methods & Supplement."
          )
        }

        if (!lower.contains("grade ") && !lower.contains("certainty of evidence")) {
          leakageAlerts.add(
            "[CERTAINTY OF EVIDENCE (GRADE)] No formal GRADE certainty-of-evidence rating detected (distinct from PRISMA reporting compliance and primary-study quality scoring)."
          )
        }
      }

      StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL -> {
        if (!lower.contains("confound") && !lower.contains("multivariable") && !lower.contains("propensity") && !lower.contains("randomiz")) {
          leakageAlerts.add(
            "[CONFOUNDING RISK] Verify whether key clinical/demographic confounders and case-mix acuity differences are adequately controlled."
          )
        }
        if (!lower.contains("ethics") && !lower.contains("irb") && !lower.contains("institutional review") && !lower.contains("consent")) {
          leakageAlerts.add(
            "[REPORTING TRANSPARENCY] Confirm ethics/IRB approval, CONSORT/STROBE compliance, and sample attrition disclosures."
          )
        }
      }

      StudyDesignArchetype.MACHINE_LEARNING_AI -> {
        if ((lower.contains("grid search") || lower.contains("tuned") || lower.contains("hyperparameter")) &&
          (lower.contains("evaluation set") || lower.contains("test set") || lower.contains("test split") || lower.contains("400-line evaluation"))
        ) {
          leakageAlerts.add(
            "[CRITICAL LEAKAGE] Hyperparameter tuning / grid search appears to be performed on or informed by the evaluation/test split, invalidating out-of-sample generalization claims."
          )
        }
        if ((lower.contains("randomly partitioned") || lower.contains("random split") || lower.contains("randomly split")) &&
          (lower.contains("prior to patient") || lower.contains("line crops") || lower.contains("patient") || lower.contains("clinic") || lower.contains("subject"))
        ) {
          leakageAlerts.add(
            "[CRITICAL ENTITY LEAKAGE] Instance/line-level random partitioning prior to patient/subject/clinic grouping allows the same subject's data to appear in both training and test sets."
          )
        }
        if (!lower.contains("seed") && !lower.contains("reproducib") && !lower.contains("github.com") && !lower.contains("replication package") && replicationCode.isBlank()) {
          leakageAlerts.add(
            "[REPRODUCIBILITY GAP] No explicit random seed protocol, deterministic training configuration, or replication code repository specified."
          )
        }
      }

      StudyDesignArchetype.ECONOMETRICS_CAUSAL -> {
        if ((lower.contains("rolling") || lower.contains("quantile") || lower.contains("percentile") || lower.contains("z-score") || lower.contains("normaliz")) &&
          (lower.contains("full sample") || lower.contains("2016 to 2025") || lower.contains("entire period") || !lower.contains("expanding window"))
        ) {
          if (lower.contains("return") || lower.contains("time series") || lower.contains("regime") || lower.contains("forecast") || lower.contains("breakout")) {
            leakageAlerts.add(
              "[LOOK-AHEAD BIAS RISK] Verify whether regime thresholds, quantiles, or normalization statistics are computed strictly causally (expanding window) or across the full sample horizon."
            )
          }
        }
        if (lower.contains("transaction cost") || lower.contains("basis points") || lower.contains("bps") || lower.contains("equity") || lower.contains("breakout")) {
          if (!lower.contains("delist") && !lower.contains("survivorship")) {
            leakageAlerts.add(
              "[SURVIVORSHIP & EXECUTION BIAS] Financial universe evaluated without explicit survivorship-free constituent reconstruction or market-impact slippage modeling."
            )
          }
        }
        if (!lower.contains("github.com") && !lower.contains("replication package") && replicationCode.isBlank()) {
          leakageAlerts.add(
            "[REPLICATION PACKAGE GAP] No econometric replication scripts or data-cleaning archive detected."
          )
        }
      }

      StudyDesignArchetype.THEORETICAL_FORMAL, StudyDesignArchetype.GENERAL_EMPIRICAL -> {
        if (isEmpiricalTrack && !lower.contains("github.com") && !lower.contains("replication") && !lower.contains("supplementary") && replicationCode.isBlank()) {
          leakageAlerts.add(
            "[TRANSPARENCY GAP] No public replication materials, supplementary data archive, or code repository referenced."
          )
        }
      }
    }

    if (isEmpiricalTrack && studyDesign != StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS) {
      if (!lower.contains("bonferroni") && !lower.contains("holm") && !lower.contains("benjamini") && !lower.contains("false discovery") && !lower.contains("multiple testing")) {
        if (statClaims.size >= 2) {
          leakageAlerts.add(
            "[MULTIPLE TESTING] Multiple hypothesis tests / horizons / metrics reported without family-wise error rate (FWER) or False Discovery Rate (FDR) adjustment."
          )
        }
      }
    }

    // 6. Clinical & Health-Policy Applicability Checks (for biomedical / psychiatric / clinical reviews)
    val clinicalNotes = mutableListOf<String>()
    if (isClinicalPaper || studyDesign == StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL ||
      (studyDesign == StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS && isClinicalPaper)
    ) {
      if (lower.contains("high-income") || lower.contains("hic") || lower.contains("low- and middle-income") || lower.contains("lmic")) {
        clinicalNotes.add(
          "Geographic & Resource-Setting Transferability: Evaluate whether high-income country (HIC) dominance limits applicability to low- and middle-income (LMIC) health systems."
        )
      }
      if (lower.contains("psychiatric bed") || lower.contains("inpatient bed") || lower.contains("bed need")) {
        clinicalNotes.add(
          "Need vs. Utilization Distinction: Distinguish normative/expert bed-need benchmarks from observed bed utilization, community mental health alternatives, case-mix acuity, length of stay, and local capacity constraints."
        )
      }
      clinicalNotes.add(
        "Clinical Utility & Certainty: Evaluate patient representativeness, whether outcome definitions are clinically meaningful, balance of benefits vs. harms, and justified certainty of evidence."
      )
    }

    // 7. Citation & Bibliography Cross-Checker
    val citationAlerts = mutableListOf<String>()
    val numberedCitations = citationBracketRegex.findAll(cleanText)
      .flatMap { m -> m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() } }
      .distinct()
      .sorted()
      .toList()
    val authorYearCitations = authorYearCitationRegex.findAll(cleanText)
      .map { it.groupValues[1].trim() }
      .distinct()
      .toList()
    val totalCitations = numberedCitations.size + authorYearCitations.size

    if (numberedCitations.isNotEmpty()) {
      val maxCite = numberedCitations.maxOrNull() ?: 0
      val missingNums = (1..maxCite.coerceAtMost(60)).filter { it !in numberedCitations }
      if (missingNums.isNotEmpty() && missingNums.size <= 10) {
        citationAlerts.add("Numbered citation sequence has gaps (cited up to [$maxCite], but missing ${missingNums.take(5).map { "[$it]" }.joinToString(", ")} in text).")
      }
    }
    if (wordCount > 150 && totalCitations == 0 && !lower.contains("references") && !lower.contains("bibliography")) {
      citationAlerts.add("Manuscript body contains zero formal numbered [N] or (Author, Year) literature citations; verify prior-art grounding.")
    }

    // 8. Replication Code Static Analyzer
    val codeAlerts = analyzeReplicationCode(replicationCode, numericSampleValues)

    // 9. Discipline Classification
    val discipline = when {
      lower.contains("psychiatric") || lower.contains("mental health") || lower.contains("deinstitutionalization") || lower.contains("inpatient bed") ->
        "Psychiatry, Public Health & Health Services Research"
      lower.contains("ocr") || (lower.contains("clinical") && isMlPaper) ->
        "Biomedical Informatics & Document Computer Vision"
      isSystematicReview && isClinicalPaper ->
        "Clinical Medicine, Epidemiology & Evidence Synthesis"
      isClinicalPaper ->
        "Clinical Medicine & Epidemiology"
      isEconPaper ->
        "Quantitative Finance & Financial Econometrics"
      lower.contains("peer review") || lower.contains("llm") || lower.contains("language model") || lower.contains("prompt") ->
        "Meta-Science, Computational Social Science & AI"
      isMlPaper ->
        "Machine Learning & Computational Science"
      else -> "Empirical & Methodological Research"
    }

    // 10. Deep Deterministic Quantitative & Statistical Forensics
    val quantForensics = QuantitativeForensicAnalyzer.analyze(
      text = cleanText,
      studyDesign = studyDesign,
      sampleSizes = numericSampleValues
    )

    return ForensicPreFlightReport(
      wordCount = wordCount,
      estimatedPages = estimatedPages,
      detectedDiscipline = discipline,
      studyDesign = studyDesign,
      empiricalSignalsCount = empHits,
      theoreticalSignalsCount = theoHits,
      extractedEquations = equations,
      symbolAndNotationAlerts = mathAlerts,
      extractedSampleSizes = sampleSizes.distinct(),
      statisticalClaims = statClaims.distinct(),
      grimAndNumericAlerts = grimAlerts.distinct(),
      proceduralLeakageAlerts = leakageAlerts.distinct(),
      clinicalApplicabilityNotes = clinicalNotes.distinct(),
      citationAlerts = citationAlerts.distinct(),
      replicationCodeAlerts = codeAlerts.distinct(),
      citationCountEstimate = totalCitations,
      quantForensics = quantForensics
    )
  }

  private fun analyzeReplicationCode(
    code: String,
    paperSampleSizes: List<Int>
  ): List<String> {
    if (code.isBlank()) return emptyList()
    val alerts = mutableListOf<String>()
    val lower = code.lowercase(Locale.US)

    val fitTransformIdx = lower.indexOf("fit_transform")
    val splitIdx = lower.indexOf("train_test_split")
    if (fitTransformIdx >= 0 && splitIdx >= 0 && fitTransformIdx < splitIdx) {
      alerts.add("[CODE LEAKAGE] `fit_transform()` is called BEFORE `train_test_split()`, leaking test-set mean/variance into training features.")
    }

    if (lower.contains("dropna(") || lower.contains("na.omit(") || lower.contains("drop if missing")) {
      alerts.add("[SAMPLE ATTRITION] Replication code drops missing observations (`dropna` / `na.omit` / `drop if`); verify whether dropped rows match manuscript sample size N.")
    }

    val hasSeed = lower.contains("random_state") ||
      lower.contains("set.seed") ||
      lower.contains("manual_seed") ||
      lower.contains("np.random.seed") ||
      lower.contains("set seed")
    if (!hasSeed) {
      alerts.add("[NON-DETERMINISTIC CODE] Replication scripts do not set an explicit random seed (`random_state`, `set.seed`, `manual_seed`).")
    }

    if ((lower.contains("gridsearchcv") || lower.contains("optuna") || lower.contains("cross_val")) &&
      (lower.contains("x_test") || lower.contains("test_df") || lower.contains("test_loader"))
    ) {
      alerts.add("[TEST-SET PEEKING] Hyperparameter search or cross-validation block references test-set variables (`X_test` / `test_df`).")
    }

    if (alerts.isEmpty()) {
      alerts.add("Replication code package attached (${code.lines().size} lines); cross-examining code vs. paper claims.")
    }
    return alerts
  }
}
