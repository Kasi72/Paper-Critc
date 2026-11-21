package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Appraisal framing mode for Paper Critic.
 * - [POST_PUBLICATION_APPRAISAL]: Default for already published articles (evaluates methodological rigor,
 *   factual/numerical accuracy, applicability, and errata/caveats rather than journal Accept/Reject).
 * - [JOURNAL_REFEREE]: Peer-review referee decision mode (Accept, Minor Revision, Major Revision, etc.).
 * - [CLINICAL_EVIDENCE_APPRAISAL]: Biomedical/clinical utility lens evaluating patient representativeness,
 *   patient-important outcomes, benefits vs. harms, resource-constrained/LMIC transferability, and GRADE certainty.
 */
enum class ReviewMode(
  val shortLabel: String,
  val displayTitle: String,
  val description: String
) {
  POST_PUBLICATION_APPRAISAL(
    shortLabel = "Post-Pub",
    displayTitle = "Post-Publication Critical Appraisal",
    description = "Evidence-grounded appraisal of published articles (credibility, errata & applicability)"
  ),
  JOURNAL_REFEREE(
    shortLabel = "Referee",
    displayTitle = "Journal Peer-Review Referee",
    description = "Formal editorial submission review with Accept / Revision / Reject decision"
  ),
  CLINICAL_EVIDENCE_APPRAISAL(
    shortLabel = "Clinical",
    displayTitle = "Clinical & Policy Evidence Appraisal",
    description = "Patient representativeness, harms vs. benefits, LMIC transferability & GRADE certainty"
  );

  companion object {
    fun fromNameOrDefault(name: String?): ReviewMode =
      entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: POST_PUBLICATION_APPRAISAL
  }
}

/**
 * Source-document coordinate anchor linking every finding directly to an exact passage and page.
 */
data class EvidenceAnchor(
  val documentId: String = "",
  val pageNumber: Int = 0,
  val section: String? = null,
  val exactQuote: String = "",
  val startOffset: Int? = null,
  val endOffset: Int? = null,
  val boundingBox: List<Float>? = null
)

enum class FindingStatus(val displayBadge: String) {
  VERIFIED("VERIFIED"),
  PARTIALLY_SUPPORTED("PARTIALLY SUPPORTED"),
  CONTRADICTED("CONTRADICTED"),
  UNVERIFIED("UNVERIFIED"),
  NOT_APPLICABLE("NOT APPLICABLE");

  companion object {
    fun fromNameOrDefault(raw: String?): FindingStatus =
      entries.firstOrNull { it.name.equals(raw?.trim()?.replace(' ', '_'), ignoreCase = true) }
        ?: UNVERIFIED
  }
}

/**
 * Structured, evidence-anchored critical appraisal finding (e.g., M-01, Q-01, R-01).
 * A finding cannot remain [FindingStatus.VERIFIED] unless its supporting evidence passes
 * source-document grounding verification.
 */
data class ReviewFinding(
  val id: String,
  val title: String,
  val allegation: String,
  val severity: String, // CRITICAL, MAJOR, MINOR, INFO
  val status: FindingStatus,
  val supportingEvidence: List<EvidenceAnchor> = emptyList(),
  val counterEvidence: List<EvidenceAnchor> = emptyList(),
  val methodologicalJustification: String = "",
  val recommendedCorrection: String = "",
  val verificationNotes: String = ""
)

object ReviewFindingCodec {
  fun encode(findings: List<ReviewFinding>): String {
    if (findings.isEmpty()) return ""
    val arr = JSONArray()
    findings.forEach { f ->
      val obj = JSONObject()
      obj.put("id", f.id)
      obj.put("title", f.title)
      obj.put("allegation", f.allegation)
      obj.put("severity", f.severity)
      obj.put("status", f.status.name)
      obj.put("supportingEvidence", encodeAnchors(f.supportingEvidence))
      obj.put("counterEvidence", encodeAnchors(f.counterEvidence))
      obj.put("methodologicalJustification", f.methodologicalJustification)
      obj.put("recommendedCorrection", f.recommendedCorrection)
      obj.put("verificationNotes", f.verificationNotes)
      arr.put(obj)
    }
    return arr.toString()
  }

  fun decode(rawJson: String): List<ReviewFinding> {
    if (rawJson.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(rawJson)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          add(
            ReviewFinding(
              id = obj.optString("id", "F-${i + 1}"),
              title = obj.optString("title", "Untitled Finding"),
              allegation = obj.optString("allegation", ""),
              severity = obj.optString("severity", "MAJOR").uppercase(),
              status = FindingStatus.fromNameOrDefault(obj.optString("status", "UNVERIFIED")),
              supportingEvidence = decodeAnchors(obj.optJSONArray("supportingEvidence")),
              counterEvidence = decodeAnchors(obj.optJSONArray("counterEvidence")),
              methodologicalJustification = obj.optString("methodologicalJustification", ""),
              recommendedCorrection = obj.optString("recommendedCorrection", ""),
              verificationNotes = obj.optString("verificationNotes", "")
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun encodeAnchors(anchors: List<EvidenceAnchor>): JSONArray {
    val arr = JSONArray()
    anchors.forEach { a ->
      val obj = JSONObject()
      obj.put("documentId", a.documentId)
      obj.put("pageNumber", a.pageNumber)
      if (a.section != null) obj.put("section", a.section)
      obj.put("exactQuote", a.exactQuote)
      if (a.startOffset != null) obj.put("startOffset", a.startOffset)
      if (a.endOffset != null) obj.put("endOffset", a.endOffset)
      arr.put(obj)
    }
    return arr
  }

  private fun decodeAnchors(arr: JSONArray?): List<EvidenceAnchor> {
    if (arr == null) return emptyList()
    return buildList {
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        add(
          EvidenceAnchor(
            documentId = obj.optString("documentId", ""),
            pageNumber = obj.optInt("pageNumber", 0),
            section = obj.optString("section", "").takeIf { it.isNotBlank() },
            exactQuote = obj.optString("exactQuote", ""),
            startOffset = if (obj.has("startOffset") && !obj.isNull("startOffset")) obj.optInt("startOffset") else null,
            endOffset = if (obj.has("endOffset") && !obj.isNull("endOffset")) obj.optInt("endOffset") else null
          )
        )
      }
    }
  }
}

/**
 * Structured evaluation rubric for critical journal paper review.
 * Captures quantitative ratings (1–10) and qualitative referee justifications
 * across core peer-review dimensions: Methodology, Significance, Clarity,
 * Originality, Mathematical Rigor, and Reproducibility.
 *
 * The composite [overallCredibilityScore] is computed strictly from the 6 weighted dimensions
 * via [computeWeightedScore] so the headline score and rubric NEVER disagree.
 */
data class PaperReviewRubric(
  val methodology: Int = 7,
  val methodologyJustification: String = "Assesses identification strategy, experimental design, controls, and robustness checks.",
  val clarity: Int = 8,
  val clarityJustification: String = "Assesses academic prose quality, structural coherence, and table/figure readability.",
  val significance: Int = 7,
  val significanceJustification: String = "Assesses theoretical or empirical impact, literature contribution, and generalizability.",
  val originality: Int = 7,
  val originalityJustification: String = "Assesses novelty of the research question, model formulation, or dataset.",
  val mathematicalRigor: Int = 7,
  val mathematicalRigorJustification: String = "Assesses equation correctness, statistical estimation, and numerical consistency.",
  val reproducibility: Int = 6,
  val reproducibilityJustification: String = "Assesses protocol transparency, data provenance, reporting completeness, and replication readiness.",
  val refereeConfidence: Int = 4, // 1–5 scientific judgment confidence
  val extractionConfidence: Int = 4, // 1–5 document extraction & source verification confidence
  val overallCredibilityScore: Int = computeWeightedScore(7, 7, 8, 7, 7, 6),
  val recommendedVerdict: String = "SOUND_WITH_CAVEATS"
) {
  fun criteriaList(): List<RubricCriterionEvaluation> = listOf(
    RubricCriterionEvaluation(
      criterion = "Methodology",
      stageTag = "Design, Bias Control & Synthesis",
      score = methodology.coerceIn(1, 10),
      maxScore = 10,
      weightPercent = 25,
      justification = methodologyJustification
    ),
    RubricCriterionEvaluation(
      criterion = "Significance",
      stageTag = "Impact & Clinical/Policy Applicability",
      score = significance.coerceIn(1, 10),
      maxScore = 10,
      weightPercent = 20,
      justification = significanceJustification
    ),
    RubricCriterionEvaluation(
      criterion = "Clarity",
      stageTag = "Exposition, Tables & Reporting Precision",
      score = clarity.coerceIn(1, 10),
      maxScore = 10,
      weightPercent = 15,
      justification = clarityJustification
    ),
    RubricCriterionEvaluation(
      criterion = "Originality",
      stageTag = "Research Question & Contribution Delta",
      score = originality.coerceIn(1, 10),
      maxScore = 10,
      weightPercent = 15,
      justification = originalityJustification
    ),
    RubricCriterionEvaluation(
      criterion = "Mathematical Rigor",
      stageTag = "Quantitative & Statistical Verification",
      score = mathematicalRigor.coerceIn(1, 10),
      maxScore = 10,
      weightPercent = 15,
      justification = mathematicalRigorJustification
    ),
    RubricCriterionEvaluation(
      criterion = "Reproducibility",
      stageTag = "Protocol, Provenance & Data Availability",
      score = reproducibility.coerceIn(1, 10),
      maxScore = 10,
      weightPercent = 10,
      justification = reproducibilityJustification
    )
  )

  companion object {
    /**
     * Authoritative weighted composite formula (0–100):
     * 25% Methodology + 20% Significance + 15% Clarity + 15% Originality + 15% Math Rigor + 10% Reproducibility.
     */
    fun computeWeightedScore(
      methodology: Int,
      significance: Int,
      clarity: Int,
      originality: Int,
      mathematicalRigor: Int,
      reproducibility: Int
    ): Int {
      val m = methodology.coerceIn(1, 10)
      val s = significance.coerceIn(1, 10)
      val c = clarity.coerceIn(1, 10)
      val o = originality.coerceIn(1, 10)
      val math = mathematicalRigor.coerceIn(1, 10)
      val r = reproducibility.coerceIn(1, 10)
      val weighted = (m * 25 + s * 20 + c * 15 + o * 15 + math * 15 + r * 10) / 10.0
      return weighted.roundToInt().coerceIn(0, 100)
    }

    fun fromPaperReview(review: PaperReview): PaperReviewRubric {
      val hasStructuredScores = review.methodologyScore in 1..10 &&
        review.significanceScore in 1..10 &&
        review.clarityScore in 1..10 &&
        review.originalityScore in 1..10 &&
        review.mathRigorScore in 1..10 &&
        review.reproducibilityScore in 1..10

      if (hasStructuredScores) {
        val exactComposite = computeWeightedScore(
          methodology = review.methodologyScore,
          significance = review.significanceScore,
          clarity = review.clarityScore,
          originality = review.originalityScore,
          mathematicalRigor = review.mathRigorScore,
          reproducibility = review.reproducibilityScore
        )
        return PaperReviewRubric(
          methodology = review.methodologyScore,
          methodologyJustification = review.methodologyJustification.ifBlank {
            "Assessed via full-document methodological & risk-of-bias verification."
          },
          clarity = review.clarityScore,
          clarityJustification = review.clarityJustification.ifBlank {
            "Assessed via exposition, structural reporting, and figure/table consistency."
          },
          significance = review.significanceScore,
          significanceJustification = review.significanceJustification.ifBlank {
            "Assessed via scientific contribution, clinical relevance, and policy utility."
          },
          originality = review.originalityScore,
          originalityJustification = review.originalityJustification.ifBlank {
            "Assessed via novelty of research question, scope, and analytical contribution."
          },
          mathematicalRigor = review.mathRigorScore,
          mathematicalRigorJustification = review.mathRigorJustification.ifBlank {
            "Assessed via deterministic statcheck, CI geometry, and statistical audit."
          },
          reproducibility = review.reproducibilityScore,
          reproducibilityJustification = review.reproducibilityJustification.ifBlank {
            "Assessed via protocol transparency, search/code provenance, and reporting completeness."
          },
          refereeConfidence = review.refereeConfidence.coerceIn(1, 5),
          extractionConfidence = review.extractionConfidence.coerceIn(1, 5),
          overallCredibilityScore = exactComposite,
          recommendedVerdict = review.verdict
        )
      }

      // Backward-compatible path for legacy cached/Firestore dossiers created prior to structured rubric fields
      val base = (review.credibilityScore / 10L).toInt().coerceIn(3, 10)
      val note = review.editorNote

      fun extractScore(label: String, defaultVal: Int): Int {
        val match = Regex("""$label\s*=\s*(\d{1,2})\s*/\s*10""", RegexOption.IGNORE_CASE).find(note)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10) ?: defaultVal
      }

      fun extractJustification(label: String, fallback: String): String {
        val match = Regex("""\[RUBRIC:$label\]\s*([^\n]+)""", RegexOption.IGNORE_CASE).find(note)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: fallback
      }

      val methodologyScore = extractScore("Methodology", base)
      val clarityScore = extractScore("Clarity", (base + 1).coerceAtMost(10))
      val significanceScore = extractScore("Significance", base)
      val originalityScore = extractScore("Novelty", (base + 1).coerceAtMost(10))
      val mathScore = extractScore("Math", base)
      val reproScore = extractScore("Reproducibility", (base - 1).coerceAtLeast(3))
      val confidenceMatch = Regex("""\[CONFIDENCE:(\d)/5\]""", RegexOption.IGNORE_CASE).find(note)
      val confidence = confidenceMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 4
      val exactComposite = computeWeightedScore(
        methodology = methodologyScore,
        significance = significanceScore,
        clarity = clarityScore,
        originality = originalityScore,
        mathematicalRigor = mathScore,
        reproducibility = reproScore
      )

      fun cleanSnippet(raw: String, fallback: String): String {
        val cleaned = com.example.util.DossierFormattingEngine.cleanAcademicProse(raw)
          .lineSequence()
          .map { it.trim() }
          .firstOrNull { it.isNotBlank() && !it.startsWith("[STAGE", ignoreCase = true) && !it.startsWith("[PRE-FLIGHT", ignoreCase = true) }
        return cleaned?.take(260) ?: fallback
      }

      return PaperReviewRubric(
        methodology = methodologyScore,
        methodologyJustification = extractJustification(
          "Methodology",
          cleanSnippet(review.keyMethodology, "Evaluated via methodological design & verification cascade.")
        ),
        clarity = clarityScore,
        clarityJustification = extractJustification(
          "Clarity",
          cleanSnippet(review.copyeditSuggestions, "Evaluated via reporting clarity and academic copyediting pass.")
        ),
        significance = significanceScore,
        significanceJustification = extractJustification(
          "Significance",
          cleanSnippet(review.coreContributions, "Evaluated via claimed contributions and applicability synthesis.")
        ),
        originality = originalityScore,
        originalityJustification = extractJustification(
          "Originality",
          cleanSnippet(review.researchQuestion, "Evaluated via research question originality and novelty delta.")
        ),
        mathematicalRigor = mathScore,
        mathematicalRigorJustification = extractJustification(
          "Math",
          cleanSnippet(review.mathAuditReport, "Evaluated via statistical and numerical consistency audit.")
        ),
        reproducibility = reproScore,
        reproducibilityJustification = extractJustification(
          "Reproducibility",
          cleanSnippet(review.codeAuditReport, "Evaluated via protocol transparency and reproducibility audit.")
        ),
        refereeConfidence = confidence,
        extractionConfidence = review.extractionConfidence.coerceIn(1, 5),
        overallCredibilityScore = exactComposite,
        recommendedVerdict = review.verdict
      )
    }
  }
}

data class RubricCriterionEvaluation(
  val criterion: String,
  val stageTag: String,
  val score: Int,
  val maxScore: Int = 10,
  val weightPercent: Int,
  val justification: String
)

@IgnoreExtraProperties
data class PaperReview(
  @DocumentId val id: String = "",
  val userId: String = "",
  val title: String = "",
  val authors: String = "",
  val publicationYear: String = "2026",
  val venueOrCitation: String = "",
  @get:PropertyName("isEmpirical") val isEmpirical: Boolean = true,
  val credibilityScore: Long = 75L,
  val verdict: String = "SOUND_WITH_CAVEATS",
  // Structured 6-axis rubric scores & justifications (authoritative source of truth)
  val methodologyScore: Int = 0,
  val significanceScore: Int = 0,
  val clarityScore: Int = 0,
  val originalityScore: Int = 0,
  val mathRigorScore: Int = 0,
  val reproducibilityScore: Int = 0,
  val methodologyJustification: String = "",
  val significanceJustification: String = "",
  val clarityJustification: String = "",
  val originalityJustification: String = "",
  val mathRigorJustification: String = "",
  val reproducibilityJustification: String = "",
  // Separated confidence & appraisal provenance metadata
  val refereeConfidence: Int = 4,
  val extractionConfidence: Int = 4,
  val reviewMode: String = ReviewMode.POST_PUBLICATION_APPRAISAL.name,
  val coverageDisclosure: String = "",
  val validationStatus: String = "VERIFIED",
  val structuredFindingsJson: String = "",
  // Narrative dossier sections
  val abstractSummary: String = "",
  val researchQuestion: String = "",
  val keyMethodology: String = "",
  val coreContributions: String = "",
  val redTeamCritique: String = "",
  val blueTeamVerification: String = "",
  val mathAuditReport: String = "",
  val codeAuditReport: String = "",
  val editorNote: String = "",
  val copyeditSuggestions: String = "",
  val reviewerQuestions: String = "",
  val formattedDossier: String = "",
  val bookmarked: Boolean = false,
  val personalNotes: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  @Exclude
  fun toRubric(): PaperReviewRubric = PaperReviewRubric.fromPaperReview(this)

  @Exclude
  fun parsedFindings(): List<ReviewFinding> = ReviewFindingCodec.decode(structuredFindingsJson)

  @Exclude
  fun resolvedReviewMode(): ReviewMode = ReviewMode.fromNameOrDefault(reviewMode)
}

data class PipelineOptions(
  val isEmpirical: Boolean = true,
  val enableMathAudit: Boolean = true,
  val enableCodeAudit: Boolean = true,
  val enableCopyeditWriterMode: Boolean = true,
  val enableEditorNote: Boolean = true,
  val reviewMode: ReviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL,
  val targetVenue: String = "Top-Tier Peer-Reviewed Journal / Conference",
)

data class BenchmarkManuscript(
  val id: String,
  val badge: String,
  val title: String,
  val authors: String,
  val year: String,
  val venue: String,
  val isEmpirical: Boolean,
  val manuscriptText: String,
)

object BenchmarkManuscriptCatalog {
  private const val D = "$"

  val samples = listOf(
    BenchmarkManuscript(
      id = "yell_at_it_reviewer2",
      badge = "Benchmark #1 • Meta-Science & LLM Peer Review",
      title = "Yell at It: Prompt Engineering for Automated Peer Review",
      authors = "S. Garg, R. Kasi, et al.",
      year = "2026",
      venue = "Working Paper / isitcredible.com Technical Report",
      isEmpirical = true,
      manuscriptText = """
        [PAGE 1]
        ABSTRACT:
        Single-pass large language model (LLM) peer reviews suffer from sycophancy, superficial reading, and unverified hallucinations. We introduce reviewer2, a 30+ stage adversarial peer-review pipeline built around two principles: (1) aggressive red-team prompting ("Breaker", "Butcher", "Shredder") forces the model to interrogate identification assumptions, empirical tables, and proofs; and (2) a multi-stage Blue Team and Checker verification cascade filters out false positives and hallucinated critiques before assembling the final referee report.
        
        RESEARCH QUESTION:
        Can an adversarial multi-agent LLM cascade with explicit numerical, mathematical, and replication-code verification outperform single-prompt frontier models in blinded human evaluations by domain experts?
        
        METHODOLOGY & EQUATIONS:
        We evaluate reviewer2 across N = 20 economics, finance, and machine learning manuscripts in pairwise blinded comparisons against 5 baseline review systems. Let ${D}W_i \in \{0, 0.5, 1\}${D} denote the pairwise win indicator for manuscript ${D}i${D}. The estimated win rate is ${D}\hat{p} = \frac{1}{N}\sum_{i=1}^{N} W_i = 0.85${D} (15 wins, 4 ties, 1 loss, ${D}p < 0.001${D} under a two-sided exact binomial test).
        In Stage 01e-01fd (Math Audit), extracted inline LaTeX equations are cross-checked against boundary conditions. In Stage 01i-01n (Code Gonzo), R/Python/Stata scripts are merged into a combined PDF to audit variable definitions and sample attrition.
        
        EMPIRICAL RESULTS & LIMITATIONS:
        Across 20 benchmark pairings, reviewer2 achieves 15 wins, 4 ties, and 1 loss. Without the Stage 02c Blue Team defense and Stage 03a/03b Checker cascade, 38% of aggressive Red Team criticisms are false positives or misreadings of appendix tables. With the full cascade, false-positive rate drops below 7%. However, runtime averages 18–40 minutes per manuscript and requires high token budgets.
      """.trimIndent()
    ),
    BenchmarkManuscript(
      id = "clinical_ocr_documorph",
      badge = "Benchmark #2 • Medical & Document Vision",
      title = "DocuMorph: Hybrid CRNN and Contextual Transformer Post-Processing for Low-Resource Handwritten Clinical Manuscripts",
      authors = "K. Drkasi, A. Sundaram, V. Natarajan",
      year = "2026",
      venue = "IEEE Journal of Biomedical and Health Informatics (Submission)",
      isEmpirical = true,
      manuscriptText = """
        [PAGE 1]
        ABSTRACT:
        Digitizing handwritten regional clinical records and archival prescriptions remains challenging due to cursive ligatures, ink bleed-through, and domain-specific medical abbreviations. We propose DocuMorph, a two-stage pipeline combining a Convolutional Recurrent Neural Network (CRNN) with CTC loss and an LLM-assisted lexical-clinical post-processor.
        
        METHODOLOGY & FORMULATION:
        Given a line image ${D}X \in \mathbb{R}^{H \times W}${D}, the CRNN encoder produces frame logits ${D}Z = f_{\theta}(X)${D} trained with Connectionist Temporal Classification loss ${D}\mathcal{L}_{CTC} = -\log P(Y \mid X)${D}. Stage 2 applies a constrained beam-search decoder regularized by a clinical n-gram and transformer language prior:
        ${D}${D}\hat{Y} = \arg\max_{Y} \left( \log P_{CTC}(Y \mid X) + \lambda \log P_{LM}(Y) - \gamma \cdot d_{edit}(Y, \mathcal{D}_{lex}) \right)${D}${D}
        where ${D}\lambda = 0.35${D} and ${D}\gamma = 0.15${D} are tuned on the validation split.
        
        EXPERIMENTS & DATASET SPLIT:
        We evaluate on 4,800 handwritten clinical lines collected across 12 clinics (split 4,000 train / 400 validation / 400 test). Character Error Rate (CER) drops from 14.2% (baseline Tesseract 5) to 4.8% (DocuMorph), and Word Error Rate (WER) improves from 31.6% to 11.4%.
        Note on split construction: Line crops were randomly partitioned across the 4,800 lines prior to patient-level grouping, and hyperparameter grid search for ${D}\lambda, \gamma${D} was performed on the 400-line evaluation set.
      """.trimIndent()
    ),
    BenchmarkManuscript(
      id = "quant_atr_regime",
      badge = "Benchmark #3 • Quantitative Finance & Econometrics",
      title = "Volatility Compression Regimes and Asymmetric Breakout Returns in Emerging Equity Markets",
      authors = "R. Kasi, M. Balasubramanian",
      year = "2026",
      venue = "Journal of Empirical Finance",
      isEmpirical = true,
      manuscriptText = """
        [PAGE 1]
        ABSTRACT:
        We investigate whether extreme Average True Range (ATR) compression combined with Central Pivot Range (CPR) narrowing predicts asymmetric positive skewness over 5- to 20-day holding horizons across NSE 500 equities from 2016 to 2025.
        
        IDENTIFICATION & ECONOMETRIC SPECIFICATION:
        We define the normalized volatility compression ratio as:
        ${D}${D}C_{i,t} = \frac{\text{ATR}_{14}(i,t)}{\frac{1}{120}\sum_{k=1}^{120}\text{ATR}_{14}(i,t-k)}${D}${D}
        A compression event occurs when ${D}C_{i,t} < 0.55${D} and daily volume exceeds ${D}1.8 \times \text{MA}_{20}(\text{Vol}_{i,t})${D}. We estimate panel regressions with stock and month fixed effects:
        ${D}${D}R_{i, t \to t+h} = \alpha_i + \tau_t + \beta_1 \mathbb{I}(C_{i,t} < 0.55) + \gamma' X_{i,t} + \varepsilon_{i,t}${D}${D}
        
        RESULTS:
        Across 14,210 signal events, the 20-day cumulative abnormal return (CAR) is +4.12% (${D}t = 4.86${D}, standard errors clustered by stock). Backtests use current NSE 500 index constituents as of December 2025 and assume zero slippage at daily closing prices.
      """.trimIndent()
    )
  )
}
