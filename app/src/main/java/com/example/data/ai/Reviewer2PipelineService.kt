package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.EpistemicSafeguardEngine
import com.example.data.ai.epistemic.EpistemicSafeguardReport
import com.example.data.ai.epistemic.FlawSeverityTier
import com.example.data.ai.epistemic.ReportingStatus
import com.example.data.ai.referee.AutonomousRefereeOrchestrator
import com.example.data.ai.referee.DiscrepancyCrossCheckChecklist
import com.example.data.ai.referee.DocumentReadiness
import com.example.data.ai.referee.DocumentReadinessEvaluator
import com.example.data.ai.referee.DomainAppraisalAdapters
import com.example.data.ai.referee.PassComparisonInput
import com.example.data.ai.referee.QuantitativeAdjudicationContracts
import com.example.data.ai.referee.RefereeAdjudicationInput
import com.example.data.ai.referee.RefereeDecision
import com.example.data.ai.referee.RemotePayloadCleanupHook
import com.example.data.ai.stats.AuditSubDesign
import com.example.data.ai.stats.EvidenceDerivedScoreGate
import com.example.data.ai.stats.ScoreCalculationBreakdown
import com.example.data.ai.stats.StatisticalAuditReport
import com.example.data.ai.stats.StatisticalFindingType
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.PaperReviewRubric
import com.example.data.model.PipelineOptions
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.util.ConfidenceSummary
import com.example.util.CoverageLevel
import com.example.util.CoverageMetric
import com.example.util.DocumentCoverageReport
import com.example.util.DossierFormattingEngine
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Reviewer2Pipeline"

data class GroundedWebSource(
  val title: String,
  val url: String
)

data class SearchGroundingResult(
  val summaryText: String,
  val searchQueries: List<String> = emptyList(),
  val sources: List<GroundedWebSource> = emptyList()
)

enum class ChatbotRolePersona(
  val id: String,
  val shortLabel: String,
  val stageTag: String,
  val description: String
) {
  BLUE_TEAM_COACH(
    id = "BLUE_TEAM_COACH",
    shortLabel = "Blue Team",
    stageTag = "Stage 02c/08a",
    description = "Constructive rebuttal coach & revision strategist"
  ),
  REVIEWER_2_HOSTILE(
    id = "REVIEWER_2_HOSTILE",
    shortLabel = "Reviewer 2",
    stageTag = "Stage 01a–01c",
    description = "Adversarial Red Team interrogator (Breaker / Butcher / Shredder)"
  ),
  SENIOR_AREA_CHAIR(
    id = "SENIOR_AREA_CHAIR",
    shortLabel = "Area Chair",
    stageTag = "Stage 04a/08b",
    description = "Calibrated meta-reviewer weighing Red vs. Blue arguments"
  ),
  SEARCH_FACT_CHECKER(
    id = "SEARCH_FACT_CHECKER",
    shortLabel = "Search Check",
    stageTag = "Stage 03b",
    description = "Live Google Search grounding for citations, prior art & SOTA numbers"
  )
}

enum class GeminiModelTier(
  val modelId: String,
  val shortLabel: String,
  val description: String
) {
  FAST_LITE(
    modelId = "gemini-3.5-flash-lite",
    shortLabel = "3.5 Lite (Fast)",
    description = "Low-latency rapid referee turn"
  ),
  GENERAL_FLASH(
    modelId = "gemini-3.8-flash",
    shortLabel = "3.8 Flash",
    description = "Balanced reasoning + Google Search Grounding"
  ),
  DEEP_ARGON(
    modelId = "gemini-4-argon",
    shortLabel = "4 Argon (Deep)",
    description = "Deep mathematical, proof & causal reasoning"
  )
}

data class ChatTurnInput(
  val role: String, // "user" or "model"
  val text: String
)

data class MultiTurnStreamChunk(
  val partialText: String,
  val searchQueries: List<String> = emptyList(),
  val sources: List<GroundedWebSource> = emptyList()
)

class Reviewer2PipelineService {

  // ============================================================================
  // PASS 1 SCHEMA: Stage 00 (00a–00c Metadata & Contributions) +
  //                Stage 01 Red Team Agents (01a Breaker, 01b Butcher,
  //                01c Shredder, 01d Collector, 01g The Void)
  // ============================================================================
  private val pass1RedTeamSchema = Schema.obj(
    properties = mapOf(
      "extractedTitle" to Schema.string(
        description = "Stage 00a: Exact publication title extracted from the manuscript text/pages in proper Title Case"
      ),
      "extractedAuthors" to Schema.string(
        description = "Stage 00a: Exact author list extracted from the manuscript (e.g., 'Mundt et al.' or full names); empty string if truly absent"
      ),
      "extractedYear" to Schema.string(
        description = "Stage 00a: Four-digit publication/manuscript year extracted from the paper (e.g., '2024'); empty string if absent"
      ),
      "studyDesignType" to Schema.string(
        description = "Stage 00a: Concise study design label (e.g., 'Systematic Review & Meta-Analysis', 'Clinical Cohort Study', 'Econometric Panel Study', 'ML Empirical Benchmark', 'Theoretical Proof')"
      ),
      "abstractSummary" to Schema.string(
        description = "Stage 00a/00b Executive Summary: 3–4 crisp sentences summarizing scope, data sources, key quantitative findings, and core conclusion. Never wrap plain acronyms like IQR, CI, SD, HIC, LMIC in $ dollar signs."
      ),
      "researchQuestion" to Schema.string(
        description = "Stage 00b Primary Research Question, formal hypothesis, or causal/descriptive estimand in 1–2 precise sentences"
      ),
      "keyMethodology" to Schema.string(
        description = "Stage 00b Key Methodology: Concise bulleted summary of data sources, inclusion/sampling protocol, analytical/statistical estimator, and comparative metrics"
      ),
      "coreContributions" to Schema.string(
        description = "Stage 00c Claimed Contributions: Each contribution on its OWN NEWLINE starting with [C1], [C2], [C3]..."
      ),
      "stage01aBreaker" to Schema.string(
        description = "Stage 01a THE BREAKER: 2–3 newline-separated bullet points starting with '• [RT-1]' attacking conceptual definitions, theoretical foundations, and framing assumptions"
      ),
      "stage01bButcher" to Schema.string(
        description = "Stage 01b THE BUTCHER (or 01a_2 Formal Deep Dive): 2–3 newline-separated bullets starting with '• [RT-2]' dissecting empirical/synthesis design, construct validity, heterogeneity/bias controls, and baseline fairness"
      ),
      "stage01cShredder" to Schema.string(
        description = "Stage 01c THE SHREDDER: 2–3 newline-separated bullets starting with '• [RT-3]' auditing sample/study counts, screening/split rigor, attrition, and domain-appropriate protocol transparency"
      ),
      "stage01dCollector" to Schema.string(
        description = "Stage 01d THE COLLECTOR: 2–3 newline-separated bullets starting with '• [RT-4]' auditing table disclosures, missingness/ND handling, weighting footnotes, and cross-reference consistency"
      ),
      "stage01gTheVoid" to Schema.string(
        description = "Stage 01g THE VOID: 2–3 newline-separated bullets starting with '• [RT-5]' identifying critical unmeasured confounds, institutional/omitted variables, and external validity boundaries"
      )
    ),
    optionalProperties = listOf("extractedTitle", "extractedAuthors", "extractedYear", "studyDesignType")
  )

  // ============================================================================
  // PASS 2 SCHEMA: Stage 01e–01fd Math Re-Deriver & Sober Checker +
  //                Stage 01i–01n Divergence Hunter & Code X-Ray +
  //                Stage 02a–03c Blue Team, Assessment & Fact-Checked List v2 +
  //                Stage 04a–09c Reviewer Essay, Legal Pass, Alchemist & Copyedit
  // ============================================================================
  private val structuredFindingItemSchema = Schema.obj(
    properties = mapOf(
      "id" to Schema.string(description = "Stable finding code such as M-01, Q-01, R-01, C-01"),
      "title" to Schema.string(description = "Concise, specific finding title"),
      "allegation" to Schema.string(description = "Exact scientific critique or verified discrepancy"),
      "severity" to Schema.enumeration(
        values = listOf("CRITICAL", "MAJOR", "MINOR", "INFO"),
        description = "Severity tier"
      ),
      "status" to Schema.enumeration(
        values = listOf("VERIFIED", "PARTIALLY_SUPPORTED", "CONTRADICTED", "UNVERIFIED", "NOT_APPLICABLE"),
        description = "Verification status strictly grounded in the extracted manuscript"
      ),
      "pageNumber" to Schema.integer(description = "1-based page number from [PAGE N] marker where evidence appears, or 0 if not page-indexed"),
      "section" to Schema.string(description = "Manuscript section or table/figure reference (e.g., 'Results, p. 8 / Figure 2')"),
      "supportingQuote" to Schema.string(description = "Short VERBATIM excerpt copied directly from the manuscript text supporting the finding"),
      "counterEvidenceQuote" to Schema.string(description = "Short VERBATIM excerpt from the manuscript that qualifies or contradicts the claim, if any"),
      "methodologicalJustification" to Schema.string(description = "Why this matters methodologically, statistically, or clinically"),
      "recommendedCorrection" to Schema.string(description = "Concrete erratum, sensitivity check, or revision remedy")
    ),
    optionalProperties = listOf("pageNumber", "section", "counterEvidenceQuote", "methodologicalJustification", "recommendedCorrection")
  )

  private val pass2VerificationAndSynthesisSchema = Schema.obj(
    properties = mapOf(
      "credibilityScore" to Schema.integer(
        description = "Calibrated overall credibility score (0 to 100) equal to 2.5*Methodology + 2.0*Significance + 1.5*Clarity + 1.5*Originality + 1.5*MathRigor + 1.0*Reproducibility"
      ),
      "refereeConfidence" to Schema.integer(
        description = "Scientific judgment confidence on standard 1 to 5 academic peer-review scale (independent of PDF extraction quality)"
      ),
      "noveltyScore" to Schema.integer(
        description = "Score from 1 to 10 for intellectual originality and contribution delta"
      ),
      "methodologyScore" to Schema.integer(
        description = "Score from 1 to 10 for research design, identification/synthesis rigor, and bias control"
      ),
      "mathRigorScore" to Schema.integer(
        description = "Score from 1 to 10 for mathematical, statistical, and quantitative rigor appropriate to the study design"
      ),
      "reproducibilityScore" to Schema.integer(
        description = "Score from 1 to 10 for protocol transparency, data provenance, reporting completeness, and replication readiness"
      ),
      "clarityScore" to Schema.integer(
        description = "Score from 1 to 10 for exposition clarity, logical structure, and table/figure precision"
      ),
      "significanceScore" to Schema.integer(
        description = "Score from 1 to 10 for policy, clinical, theoretical, or empirical significance and impact"
      ),
      "verdict" to Schema.enumeration(
        values = listOf(
          "HIGH_CREDIBILITY", "SOUND_WITH_CAVEATS", "REQUIRES_CORRECTION", "MAJOR_CONCERNS", "LOW_CREDIBILITY",
          "ACCEPT", "MINOR_REVISION", "MAJOR_REVISION", "REJECT_AND_RESUBMIT", "REJECT",
          "HIGH_CERTAINTY", "MODERATE_CERTAINTY", "LOW_CERTAINTY", "VERY_LOW_CERTAINTY"
        ),
        description = "Final appraisal verdict strictly matching the active ReviewMode and weighted credibility score"
      ),
      "rubricRationaleSummary" to Schema.string(
        description = "Six concise newline-separated rationales prefixed exactly with [RUBRIC:Methodology], [RUBRIC:Significance], [RUBRIC:Clarity], [RUBRIC:Originality], [RUBRIC:Math], [RUBRIC:Reproducibility]"
      ),
      "structuredFindings" to Schema.array(
        items = structuredFindingItemSchema,
        description = "4 to 8 prioritized, source-grounded critical appraisal findings with verbatim quotes and [PAGE N] anchors"
      ),
      "stage01eTo01fdMathAudit" to Schema.string(
        description = "Stage 01e–01fd Quantitative & Mathematical Audit: Concise newline-separated bullets auditing statistical estimators, pooling/derivation logic, bounds, assumptions, and sober verification. Never wrap plain acronyms like IQR or CI in $ signs."
      ),
      "stage01iTo01nCodeAndDataAudit" to Schema.string(
        description = "Stage 01i–01n & 07b Reproducibility, Provenance & Clinical/Policy Applicability Audit: Domain-appropriate bullets auditing protocol registration, data extraction/code transparency, RoB vs quality scoring, and clinical/policy transferability."
      ),
      "stage02aNumbersCheck" to Schema.string(
        description = "Stage 02a Numbers Verifier: Newline-separated bullets verifying reported sample/study counts, medians/IQRs, confidence intervals, ratios, percentages, and text-vs-figure consistency."
      ),
      "stage02cBlueTeamDefense" to Schema.string(
        description = "Stage 02c BLUE TEAM: Newline-separated bullets providing the strongest scholarly counterarguments defending the manuscript against each Red Team finding."
      ),
      "stage02eTo03cVerifiedListV2" to Schema.string(
        description = "Stage 02e–03c Adjudicated Verified List v2: Each surviving issue on its OWN NEWLINE prefixed with [CRITICAL], [MAJOR], or [MINOR] (with Title, [Page N] Evidence Anchor, and Required Remedy), followed by dismissed Red Team false positives each on its OWN NEWLINE prefixed with [DISMISSED]."
      ),
      "stage04aIsItCredibleEssay" to Schema.string(
        description = "Stage 04a THE REVIEWER (Stage 06 Legal-Checked): Three distinct newline-separated blocks prefixed exactly with 'Executive Assessment:', 'The Bottom Line:', and 'Future Research Agenda:'."
      ),
      "stage08aTo08bAlchemistEditorNote" to Schema.string(
        description = "Stage 08a–08b Appraisal Decision Memo & Prioritized Action Roadmap: Brief 'Editorial Decision Rationale:' paragraph followed by newline-separated action items prefixed with [MUST FIX], [SHOULD FIX], or [RECOMMENDED]."
      ),
      "stage09aTo09cCopyeditReport" to Schema.string(
        description = "Stage 09a–09c Proofreader & Copyedit: Two newline-separated sections prefixed with 'Extraction / Typesetting Artifacts:' and 'Substantive Prose Improvements:' with concrete bullet points."
      ),
      "fortifyReviewerQuestions" to Schema.string(
        description = "PhD-Skills Fortify: 4–5 sharp Reviewer 2 questions each on its OWN NEWLINE numbered '1. ', '2. ', '3. ', '4. ', followed by 'Recommended Sensitivity & Robustness Checks:' with 2–3 concise bullets."
      )
    ),
    optionalProperties = listOf("refereeConfidence", "rubricRationaleSummary", "structuredFindings")
  )

  // ============================================================================
  // PASS 2A SCHEMA (SPECIALIST QUANTITATIVE, STATISTICAL & MATHEMATICAL AUDIT):
  // Dedicated 8-Pillar Deep Audit (Stage 01e–01fd + Stage 02a Numbers Forensics)
  // ============================================================================
  private val pass2QuantAuditSchema = Schema.obj(
    properties = mapOf(
      "quantitativeRigorScore" to Schema.integer(
        description = "Calibrated 1–10 quantitative, statistical, and mathematical rigor score strictly aligned with the audit findings"
      ),
      "estimatorAndModelAudit" to Schema.string(
        description = "Pillar 1 — Estimator, Distributional & Model Specification Audit: 3–4 newline bullets auditing estimand-to-estimator alignment, pooling/regression/loss formulation, distributional assumptions (normality, skew, log vs linear scale, homoskedasticity, independence), and variance weighting."
      ),
      "numericAndStatcheckAudit" to Schema.string(
        description = "Pillar 2 — Test Statistics, CIs, Effect Sizes & Numerical Forensics: 3–4 newline bullets reconciling N, k, subgroup sums, effect sizes, CI symmetry/precision, SD vs SE reporting, statcheck p-values, and statistical vs practical magnitude."
      ),
      "powerAndMultiplicityAudit" to Schema.string(
        description = "Pillar 3 — Statistical Power, Precision, Degrees of Freedom & Multiplicity: 2–4 newline bullets evaluating sample/study power, MDE, Type M/S error risk, small-cluster/small-k caveats, and FWER/FDR inflation across subgroups or multiple outcomes."
      ),
      "biasHeterogeneityAudit" to Schema.string(
        description = "Pillar 4 — Bias, Confounding, Heterogeneity & Identification Diagnostics: 3–4 newline bullets tailored strictly to the study design (I²/τ²/Prediction Intervals/Egger for meta-analyses; DAGs/confounding/attrition for clinical; IV/DiD/parallel trends for econometrics; leakage/calibration/paired tests for ML)."
      ),
      "formalMathRederivation" to Schema.string(
        description = "Pillar 5 — Formal Mathematical Re-Derivation, Equations & Dimensional Analysis (Stage 01e–01fc): 2–4 newline bullets verifying equations/estimators step by step, dimensional/unit scaling consistency, boundary/regularity conditions, and notation consistency. Place standalone formulas on their own line."
      ),
      "quantAuditMatrixRows" to Schema.string(
        description = "Pillar 6 — Structured Quantitative Audit Matrix: 6–8 newline-separated markdown pipe rows strictly in format '| Dimension | Reported Metric / Evidence | Status | Quantitative Impact |' with header row first and Status strictly one of PASS, WARN, or FAIL."
      ),
      "sensitivityPrescriptions" to Schema.string(
        description = "Pillar 7 — Executable Sensitivity & Robustness Prescriptions: 3–5 newline-separated concrete prescriptions each prefixed with [MUST FIX], [SHOULD FIX], or [RECOMMENDED], specifying exact statistical methods/estimators (e.g., REML + HKSJ + 95% Prediction Interval, E-value, Benjamini-Hochberg FDR, wild cluster bootstrap, paired McNemar)."
      ),
      "soberVerificationSummary" to Schema.string(
        description = "Pillar 8 — Stage 01fd Sober Mathematical Verification: 1–2 concise sentences filtering out pedantic nitpicks, followed on a new line by 'Quantitative Bottom Line: ...' summarizing overall quantitative credibility."
      )
    ),
    optionalProperties = listOf("quantitativeRigorScore")
  )

  private val pass1RedTeamModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = pass1RedTeamSchema
        temperature = 0.25f
        maxOutputTokens = 8192
        thinkingConfig = thinkingConfig {
          thinkingLevel = ThinkingLevel.LOW
        }
      }
    )
  }

  private val pass1FastFallbackModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = pass1RedTeamSchema
        temperature = 0.25f
        maxOutputTokens = 8192
      }
    )
  }

  private val pass1PlainJsonFallbackModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        temperature = 0.25f
        maxOutputTokens = 8192
      }
    )
  }

  private val pass2VerificationModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = pass2VerificationAndSynthesisSchema
        temperature = 0.15f
        maxOutputTokens = 8192
        thinkingConfig = thinkingConfig {
          thinkingLevel = ThinkingLevel.LOW
        }
      }
    )
  }

  private val pass2FastFallbackModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = pass2VerificationAndSynthesisSchema
        temperature = 0.15f
        maxOutputTokens = 8192
      }
    )
  }

  private val pass2PlainJsonFallbackModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        temperature = 0.15f
        maxOutputTokens = 8192
      }
    )
  }

  private val pass2QuantModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = pass2QuantAuditSchema
        temperature = 0.12f
        maxOutputTokens = 8192
        thinkingConfig = thinkingConfig {
          thinkingLevel = ThinkingLevel.LOW
        }
      }
    )
  }

  private val pass2QuantLiteModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = pass2QuantAuditSchema
        temperature = 0.12f
        maxOutputTokens = 8192
      }
    )
  }

  private val pass2QuantPlainModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.5-flash-lite",
      generationConfig = generationConfig {
        temperature = 0.12f
        maxOutputTokens = 8192
      }
    )
  }

  private val streamingFollowUpModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash"
    )
  }

  private data class ParsedJsonResult(
    val json: JSONObject,
    val wasRepaired: Boolean,
    val actualModelId: String = "gemini-3.8-flash",
    val actualRoute: String = "Firebase AI Logic",
    val imagesSentCount: Int = 0,
    val sentImagePageNumbers: List<Int> = emptyList(),
    val fallbackHistory: List<String> = emptyList()
  )

  private fun parseJsonObjectSafely(rawResponse: String): ParsedJsonResult {
    val extracted = ExternalApiGatewayClient.extractCleanJsonObject(rawResponse)
    runCatching { return ParsedJsonResult(JSONObject(extracted), wasRepaired = false) }

    // Attempt balanced repair if stream was clipped at maxOutputTokens; mark wasRepaired = true
    val firstBrace = extracted.indexOf('{')
    if (firstBrace != -1) {
      val slice = extracted.substring(firstBrace).trimEnd()
      val repaired = StringBuilder(slice)
      var inString = false
      var escaped = false
      var openBraces = 0
      var openBrackets = 0
      for (ch in slice) {
        when {
          escaped -> escaped = false
          ch == '\\' && inString -> escaped = true
          ch == '"' -> inString = !inString
          !inString && ch == '{' -> openBraces++
          !inString && ch == '}' -> openBraces = (openBraces - 1).coerceAtLeast(0)
          !inString && ch == '[' -> openBrackets++
          !inString && ch == ']' -> openBrackets = (openBrackets - 1).coerceAtLeast(0)
        }
      }
      if (inString) {
        if (escaped) repaired.append('\\')
        repaired.append('"')
      } else {
        val trimmed = repaired.toString().trimEnd()
        if (trimmed.endsWith(",")) {
          repaired.setLength(trimmed.length - 1)
        }
      }
      repeat(openBrackets) { repaired.append(']') }
      repeat(openBraces) { repaired.append('}') }
      runCatching { return ParsedJsonResult(JSONObject(repaired.toString()), wasRepaired = true) }

      val lastComma = slice.lastIndexOf(',')
      if (lastComma > 0) {
        val candidate = StringBuilder(slice.substring(0, lastComma).trimEnd())
        var cOpenBraces = 0
        var cOpenBrackets = 0
        var cInStr = false
        var cEsc = false
        for (ch in candidate) {
          when {
            cEsc -> cEsc = false
            ch == '\\' && cInStr -> cEsc = true
            ch == '"' -> cInStr = !cInStr
            !cInStr && ch == '{' -> cOpenBraces++
            !cInStr && ch == '}' -> cOpenBraces = (cOpenBraces - 1).coerceAtLeast(0)
            !cInStr && ch == '[' -> cOpenBrackets++
            !cInStr && ch == ']' -> cOpenBrackets = (cOpenBrackets - 1).coerceAtLeast(0)
          }
        }
        if (!cInStr && (cOpenBraces > 0 || cOpenBrackets > 0)) {
          repeat(cOpenBrackets) { candidate.append(']') }
          repeat(cOpenBraces) { candidate.append('}') }
          runCatching { return ParsedJsonResult(JSONObject(candidate.toString()), wasRepaired = true) }
        }
      }
    }

    throw IllegalStateException("AI engine returned non-JSON output (${extracted.take(140)}).")
  }

  private fun sanitizeBitmapsForInference(bitmaps: List<Bitmap>, maxCount: Int): List<Bitmap> {
    if (bitmaps.isEmpty() || maxCount <= 0) return emptyList()
    return bitmaps
      .asSequence()
      .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
      .take(maxCount)
      .mapNotNull { bmp ->
        runCatching {
          val maxDim = 1024
          if (bmp.width <= maxDim && bmp.height <= maxDim) {
            bmp
          } else {
            val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height).toFloat()
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, w, h, true)
          }
        }.getOrNull()
      }
      .toList()
  }

  private fun isLikelyVisionPayloadError(err: Throwable?): Boolean {
    if (err == null) return false
    val msg = formatThrowableDetails(err).lowercase(Locale.US)
    return msg.contains("payload") || msg.contains("413") || msg.contains("too large") ||
      msg.contains("image") || msg.contains("multimodal") || msg.contains("unsupported mime")
  }

  /**
   * Executes a structured JSON stage pass off the Main thread using unary generateContent()
   * with a live UI progress heartbeat and an automatic 3-tier fallback chain:
   *   Tier 1: gemini-3.8-flash (responseSchema + ThinkingLevel.LOW + up to 4 high-value vision bitmaps)
   *   Tier 2: gemini-3.5-flash-lite (responseSchema, preserves vision bitmaps unless Tier 1 had a vision/payload error)
   *   Tier 3: gemini-3.5-flash-lite (unconstrained plain JSON prompt)
   */
  private suspend fun executeResilientJsonPass(
    primaryModel: com.google.firebase.ai.GenerativeModel,
    liteSchemaModel: com.google.firebase.ai.GenerativeModel,
    plainJsonModel: com.google.firebase.ai.GenerativeModel,
    primaryPrompt: String,
    compactPrompt: String,
    plainJsonDirective: String,
    attachedBitmaps: List<Bitmap>,
    attachedPageNumbers: List<Int> = emptyList(),
    stageCode: String,
    stageTitle: String,
    startProgress: Float,
    endProgress: Float,
    externalProvider: com.example.data.model.AiApiProviderEntry?,
    useExternalProvider: Boolean,
    externalSystemPrompt: String,
    autoFallbackToKeylessFirebase: Boolean,
    allowInternalModelFallback: Boolean = true,
    onStageUpdate: (stageCode: String, stageLabel: String, progress: Float) -> Unit
  ): ParsedJsonResult = withContext(Dispatchers.IO) {
    val fallbackTrail = mutableListOf<String>()

    suspend fun invokeUnaryTier(
      model: com.google.firebase.ai.GenerativeModel,
      modelIdLabel: String,
      routeLabel: String,
      promptText: String,
      bitmaps: List<Bitmap>,
      pageNumbers: List<Int>,
      tierHint: String,
      timeoutMs: Long
    ): ParsedJsonResult = coroutineScope {
      val startMs = System.currentTimeMillis()
      val heartbeatJob = launch {
        while (isActive) {
          delay(1_500L)
          val elapsedMs = System.currentTimeMillis() - startMs
          val elapsedSec = (elapsedMs / 1000L).coerceAtLeast(1L)
          val ratio = (elapsedMs.toFloat() / timeoutMs.toFloat()).coerceIn(0.05f, 0.90f)
          val liveProgress = startProgress + (endProgress - startProgress) * ratio
          onStageUpdate(
            stageCode,
            "$stageTitle$tierHint • Analyzing (${elapsedSec}s)...",
            liveProgress
          )
        }
      }

      try {
        val response = withTimeout(timeoutMs) {
          if (bitmaps.isNotEmpty()) {
            model.generateContent(
              content {
                bitmaps.forEach { bmp -> image(bmp) }
                text(promptText)
              }
            )
          } else {
            model.generateContent(promptText)
          }
        }
        val rawText = response.text?.trim().orEmpty()
        if (rawText.isBlank()) {
          throw IllegalStateException("Model returned empty text for stage $stageCode$tierHint.")
        }
        parseJsonObjectSafely(rawText).copy(
          actualModelId = modelIdLabel,
          actualRoute = routeLabel,
          imagesSentCount = bitmaps.size,
          sentImagePageNumbers = pageNumbers.take(bitmaps.size),
          fallbackHistory = fallbackTrail.toList()
        )
      } finally {
        heartbeatJob.cancel()
      }
    }

    // Optional external BYOK provider path (text-only completion; sends 0 vision bitmaps)
    if (useExternalProvider && externalProvider != null) {
      try {
        val raw = withTimeout(95_000L) {
          onStageUpdate(
            stageCode,
            "$stageTitle [${externalProvider.serviceName}] • Awaiting structured analysis...",
            startProgress + (endProgress - startProgress) * 0.35f
          )
          ExternalApiGatewayClient.executeCompletion(
            provider = externalProvider,
            systemPrompt = externalSystemPrompt,
            userPrompt = primaryPrompt,
            expectJsonObject = true
          )
        }
        return@withContext parseJsonObjectSafely(raw).copy(
          actualModelId = externalProvider.modelId.ifBlank { externalProvider.serviceName },
          actualRoute = "External BYOK (${externalProvider.serviceName}, text-only)",
          imagesSentCount = 0,
          sentImagePageNumbers = emptyList(),
          fallbackHistory = fallbackTrail.toList()
        )
      } catch (e: CancellationException) {
        if (e !is TimeoutCancellationException) throw e
        if (!autoFallbackToKeylessFirebase) {
          throw IllegalStateException(
            "Stage $stageCode timed out after 95s on ${externalProvider.serviceName}. " +
              "Provider fallback is disabled to protect manuscript routing privacy."
          )
        }
        fallbackTrail.add("BYOK(${externalProvider.serviceName}) timed out -> Firebase AI Logic")
        Log.w(TAG, "External provider timed out on $stageCode; falling back to Firebase.ai (user-authorized)", e)
      } catch (e: Exception) {
        if (!autoFallbackToKeylessFirebase) throw e
        fallbackTrail.add("BYOK(${externalProvider.serviceName}) failed (${e.javaClass.simpleName}) -> Firebase AI Logic")
        Log.w(TAG, "External provider failed on $stageCode; falling back to Firebase.ai (user-authorized)", e)
      }
    }

    val safeBitmaps = sanitizeBitmapsForInference(attachedBitmaps, maxCount = 4)
    val safePageNums = attachedPageNumbers.take(safeBitmaps.size)
    var tier1Err: Throwable? = null
    var tier2Err: Throwable? = null

    // TIER 1: Primary gemini-3.8-flash (unary JSON + responseSchema + ThinkingLevel.LOW)
    try {
      return@withContext invokeUnaryTier(
        model = primaryModel,
        modelIdLabel = "gemini-3.8-flash",
        routeLabel = "Firebase AI Logic (Tier 1 Schema)",
        promptText = primaryPrompt,
        bitmaps = safeBitmaps,
        pageNumbers = safePageNums,
        tierHint = " [3.8 Flash]",
        timeoutMs = 70_000L
      )
    } catch (e: CancellationException) {
      if (e !is TimeoutCancellationException) throw e
      tier1Err = e
      fallbackTrail.add("Tier1(gemini-3.8-flash) timed out")
      Log.w(TAG, "Tier 1 (3.8 Flash) timed out on $stageCode", e)
    } catch (e: Exception) {
      tier1Err = e
      fallbackTrail.add("Tier1(gemini-3.8-flash) failed (${e.javaClass.simpleName})")
      Log.w(TAG, "Tier 1 (3.8 Flash) failed on $stageCode: ${formatThrowableDetails(e)}", e)
    }

    if (!allowInternalModelFallback) {
      throw IllegalStateException(
        "Stage $stageCode failed on primary model (gemini-3.8-flash) and silent internal model fallback is disabled.",
        tier1Err
      )
    }

    // TIER 2: Fast gemini-3.5-flash-lite — preserve visual bitmaps unless Tier 1 failed on a vision/payload error
    val dropVisionForTier2 = isLikelyVisionPayloadError(tier1Err)
    val tier2Bitmaps = if (dropVisionForTier2) emptyList() else safeBitmaps.take(2)
    val tier2PageNums = if (dropVisionForTier2) emptyList() else safePageNums.take(tier2Bitmaps.size)
    onStageUpdate(
      stageCode,
      "$stageTitle • Switching to fast engine (gemini-3.5-flash-lite)...",
      startProgress + (endProgress - startProgress) * 0.30f
    )
    try {
      return@withContext invokeUnaryTier(
        model = liteSchemaModel,
        modelIdLabel = "gemini-3.5-flash-lite",
        routeLabel = "Firebase AI Logic (Tier 2 Lite Schema)",
        promptText = compactPrompt,
        bitmaps = tier2Bitmaps,
        pageNumbers = tier2PageNums,
        tierHint = " [3.5 Lite]",
        timeoutMs = 55_000L
      )
    } catch (e: CancellationException) {
      if (e !is TimeoutCancellationException) throw e
      tier2Err = e
      fallbackTrail.add("Tier2(gemini-3.5-flash-lite) timed out")
      Log.w(TAG, "Tier 2 (3.5 Lite schema) timed out on $stageCode", e)
    } catch (e: Exception) {
      tier2Err = e
      fallbackTrail.add("Tier2(gemini-3.5-flash-lite) failed (${e.javaClass.simpleName})")
      Log.w(TAG, "Tier 2 (3.5 Lite schema) failed on $stageCode: ${formatThrowableDetails(e)}", e)
    }

    // TIER 3: Unconstrained plain JSON fallback on gemini-3.5-flash-lite (text-only)
    onStageUpdate(
      stageCode,
      "$stageTitle • Retrying in compatibility JSON mode...",
      startProgress + (endProgress - startProgress) * 0.55f
    )
    try {
      val plainPrompt = buildString {
        appendLine(plainJsonDirective)
        appendLine("Output ONLY a single raw JSON object starting with '{' and ending with '}'. Do not wrap in markdown code fences.")
        appendLine()
        append(compactPrompt)
      }
      return@withContext invokeUnaryTier(
        model = plainJsonModel,
        modelIdLabel = "gemini-3.5-flash-lite",
        routeLabel = "Firebase AI Logic (Tier 3 Compat Text-Only)",
        promptText = plainPrompt,
        bitmaps = emptyList(),
        pageNumbers = emptyList(),
        tierHint = " [Compat]",
        timeoutMs = 50_000L
      )
    } catch (e: CancellationException) {
      if (e !is TimeoutCancellationException) throw e
      Log.e(TAG, "All 3 tiers timed out on $stageCode", e)
      throw IllegalStateException(
        "Stage $stageCode timed out across all Gemini tiers. Check your internet connection and retry."
      )
    } catch (tier3Err: Exception) {
      Log.e(TAG, "All 3 tiers failed on $stageCode", tier3Err)
      val primaryDetail = tier1Err?.let { formatThrowableDetails(it) }
      val liteDetail = tier2Err?.let { formatThrowableDetails(it) }
      val compatDetail = formatThrowableDetails(tier3Err)
      val bestDetail = when {
        compatDetail.isNotBlank() && !compatDetail.equals("Something unexpected happened.", ignoreCase = true) -> compatDetail
        !primaryDetail.isNullOrBlank() -> primaryDetail
        !liteDetail.isNullOrBlank() -> liteDetail
        else -> "Unexpected AI inference failure during stage $stageCode."
      }
      throw IllegalStateException("Stage $stageCode failed: $bestDetail", tier3Err)
    }
  }

  suspend fun runAdversarialPipeline(
    title: String,
    authors: String,
    year: String,
    venueOrCitation: String,
    manuscriptText: String,
    replicationCodeText: String = "",
    pdfPageBitmaps: List<Bitmap> = emptyList(),
    coverageReport: DocumentCoverageReport? = null,
    reviewStrictness: String = "BALANCED_AREA_CHAIR",
    options: PipelineOptions,
    externalProvider: com.example.data.model.AiApiProviderEntry? = null,
    autoFallbackToKeylessFirebase: Boolean = false,
    pdfVisionDigestSha256: String = "",
    consentVerified: Boolean = true,
    consentDigestAtApproval: String = "",
    allowInternalModelFallback: Boolean = true,
    remotePayloadCleanupHook: RemotePayloadCleanupHook? = null,
    onStageUpdate: (stageCode: String, stageLabel: String, progress: Float) -> Unit
  ): PaperReview {
    val startedAtMs = System.currentTimeMillis()
    val analysisId = "ana_${UUID.randomUUID()}"
    remotePayloadCleanupHook?.registerPayloadHandle(analysisId, startedAtMs + 300_000L)
    try {
      val useExternalProvider = externalProvider != null &&
        externalProvider.protocolType != com.example.data.model.ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS &&
        externalProvider.apiKey.isNotBlank()

      // ==========================================================================
      // PASS 0: Deterministic Forensic Pre-Flight Engine (Full Document, Zero Middle Truncation)
      // ==========================================================================
      onStageUpdate(
        "00a-00c",
        "Stage 00a–00c: Running Full-Document Forensic, Statcheck & CI Geometry Scanner...",
        0.12f
      )
      val effectiveCoverageReport = coverageReport ?: if (manuscriptText.isNotBlank()) {
        DocumentCoverageReport.forCompleteText(
          text = manuscriptText,
          engine = if (pdfPageBitmaps.isNotEmpty()) "PDF Vision + Direct Text" else "Direct Text Input"
        )
      } else {
        DocumentCoverageReport.unknown("No manuscript text or coverage report supplied to pipeline.")
      }
      val forensicReport = withContext(Dispatchers.Default) {
        ManuscriptForensicEngine.analyzeManuscript(
          text = manuscriptText,
          isEmpiricalTrack = options.isEmpirical,
          replicationCode = replicationCodeText,
          coverageReport = effectiveCoverageReport
        )
      }
      val refereeReadiness = DocumentReadinessEvaluator.assess(
        manuscriptText = manuscriptText,
        coverageReport = effectiveCoverageReport,
        attachedBitmapCount = pdfPageBitmaps.count { !it.isRecycled },
        pdfVisionDigestSha256 = pdfVisionDigestSha256,
        useExternalTextOnlyProvider = useExternalProvider
      )
      val domainAppraisal = DomainAppraisalAdapters.appraise(
        text = manuscriptText,
        parentArchetype = forensicReport.studyDesign
      )

      // P1-10 Pre-Inference Document-Readiness & Consent Gate:
      // Short-circuit BEFORE running expensive LLM passes when consent fails or essential text/pages are inaccessible.
      val readinessGate = evaluateDocumentReadinessGate(
        manuscriptText = manuscriptText,
        coverageReport = effectiveCoverageReport,
        attachedBitmapCount = pdfPageBitmaps.count { !it.isRecycled },
        useExternalTextOnlyProvider = useExternalProvider
      )
      if (!consentVerified || !readinessGate.isReady) {
        onStageUpdate("DONE", "Document Readiness Gate: Additional Source Material Required", 1.0f)
        val safeTitle = com.example.util.DossierFormattingEngine.normalizeTitle(
          title.ifBlank { "Unreadable / Incomplete Manuscript" }
        )
        val safeAuthors = authors.trim().ifBlank { "Authors Not Disclosed" }
        val safeYear = year.trim()
        val safeVenue = venueOrCitation.trim().ifBlank { options.targetVenue }
        val gateReason = if (!consentVerified) {
          "Manuscript, replication code, or PDF vision bitmaps changed after consent verification."
        } else {
          readinessGate.blockingReasons.joinToString("; ")
        }
        val gateActions = if (!consentVerified) {
          listOf("Review document checksums and confirm remote inference consent before running analysis.")
        } else {
          readinessGate.requestedActions
        }
        val gateFinding = ReviewFinding(
          id = "GATE-01",
          title = "Pre-Inference Document-Readiness Gate Triggered",
          allegation = "Full scientific appraisal was paused before model inference because essential prerequisites could not be verified: $gateReason",
          severity = "INFO",
          status = FindingStatus.UNVERIFIED,
          methodologicalJustification = "Evaluating a manuscript without verified consent, searchable text, or bound PDF pages risks fabricated or unauthorized criticisms.",
          recommendedCorrection = gateActions.joinToString(" "),
          verificationNotes = "Blocked deterministically prior to Pass 1/Pass 2 inference to preserve scientific validity.",
          reportingStatus = ReportingStatus.NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL.name,
          flawTier = FlawSeverityTier.COSMETIC_OR_EDITORIAL.name,
          overturnEvidence = "Upload a text-searchable PDF, OCR-processed PDF, Word (.docx), or plain-text version with Methods, Results, and verified consent."
        )
        val earlyOut = AutonomousRefereeOrchestrator.adjudicate(
          RefereeAdjudicationInput(
            analysisId = analysisId,
            manuscriptText = manuscriptText,
            replicationCodeText = replicationCodeText,
            pdfVisionDigestSha256 = pdfVisionDigestSha256,
            providerRoute = if (useExternalProvider && externalProvider != null) externalProvider.serviceName else "Firebase AI Logic (Pre-Inference Gate)",
            modelId = if (useExternalProvider && externalProvider != null) externalProvider.modelId else "none-executed",
            startedAtMs = startedAtMs,
            completedAtMs = System.currentTimeMillis(),
            readiness = refereeReadiness,
            extractionConfidence = 1,
            totalPages = effectiveCoverageReport.totalPages,
            pagesExtracted = effectiveCoverageReport.extractedPages,
            pagesAnalyzed = 0,
            omittedPages = effectiveCoverageReport.omittedPages,
            deterministicFindings = listOf(gateFinding),
            domainAppraisal = domainAppraisal,
            consentVerified = consentVerified,
            consentDigestAtApproval = consentDigestAtApproval
          )
        )
        val gateNote = buildString {
          appendLine("PRE-INFERENCE DOCUMENT-READINESS GATE — APPRAISAL PAUSED (NOT ASSESSED)")
          appendLine("Blocking issues: $gateReason")
          appendLine()
          appendLine("Required actions before full appraisal:")
          gateActions.forEach { act -> appendLine("• $act") }
        }.trim()
        val disclosure = "${effectiveCoverageReport.toDisclosureLine(overrideExtractionConfidence = 1)} • Pre-inference gate: paused (0 LLM passes executed; scores Not Assessed)"
        return PaperReview(
          title = safeTitle,
          authors = safeAuthors,
          publicationYear = safeYear,
          venueOrCitation = safeVenue,
          isEmpirical = options.isEmpirical,
          credibilityScore = 0L,
          verdict = "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
          methodologyScore = 0,
          significanceScore = 0,
          clarityScore = 0,
          originalityScore = 0,
          mathRigorScore = 0,
          reproducibilityScore = 0,
          methodologyJustification = "Not assessed prior to searchable full-text ingestion.",
          significanceJustification = "Not assessed prior to searchable full-text ingestion.",
          clarityJustification = "Not assessed prior to searchable full-text ingestion.",
          originalityJustification = "Not assessed prior to searchable full-text ingestion.",
          mathRigorJustification = "Not assessed prior to searchable full-text ingestion.",
          reproducibilityJustification = "Not assessed prior to searchable full-text ingestion.",
          refereeConfidence = 1,
          extractionConfidence = 1,
          reviewMode = options.reviewMode.name,
          coverageDisclosure = disclosure,
          validationStatus = "UNVERIFIED",
          structuredFindingsJson = ReviewFindingCodec.encode(listOf(gateFinding)),
          schemaVersion = 5,
          abstractSummary = gateNote,
          researchQuestion = "Unavailable due to incomplete/unsearchable document input.",
          keyMethodology = "Unavailable due to incomplete/unsearchable document input.",
          coreContributions = "Unavailable due to incomplete/unsearchable document input.",
          redTeamCritique = "Skipped by pre-inference document-readiness gate.",
          blueTeamVerification = gateNote,
          mathAuditReport = "Skipped by pre-inference document-readiness gate.",
          codeAuditReport = "Skipped by pre-inference document-readiness gate.",
          editorNote = gateNote,
          copyeditSuggestions = "Skipped by pre-inference document-readiness gate.",
          reviewerQuestions = gateActions.joinToString("\n") { "• $it" },
          formattedDossier = gateNote,
          refereeDecision = earlyOut.decision.name,
          analysisManifestJson = earlyOut.manifest.toJson(),
          abstentionSummaryJson = earlyOut.abstentionResult.toJson(),
          readinessSummaryJson = refereeReadiness.toJson(),
          auditLogJson = earlyOut.auditLogJson,
          refereeCoverageJson = earlyOut.coverageMetrics.toJson()
        )
      }

    val forensicContext = forensicReport.toStructuredPromptContext()

    // Preserve the entire paper body (up to 160,000 chars primary / 90,000 chars compact) using
    // page-aware selection with explicit omitted-page disclosure.
    val primaryPrepared = prepareManuscriptForPrompt(manuscriptText, maxChars = 160_000)
    val compactPrepared = prepareManuscriptForPrompt(manuscriptText, maxChars = 90_000)
    val visionPageNumbers = coverageReport?.visionPageNumbers.orEmpty()
    val pass1VisionSelection = selectVisionPagesForPass(
      allBitmaps = pdfPageBitmaps,
      visionPageNumbers = visionPageNumbers,
      forensicReport = forensicReport,
      manuscriptText = manuscriptText,
      maxBitmaps = 6,
      prioritizeDiscrepancyAndFigures = false
    )
    val pass2VisionSelection = selectVisionPagesForPass(
      allBitmaps = pdfPageBitmaps,
      visionPageNumbers = visionPageNumbers,
      forensicReport = forensicReport,
      manuscriptText = manuscriptText,
      maxBitmaps = if (manuscriptText.trim().length < 250) 6 else 4,
      prioritizeDiscrepancyAndFigures = true
    )
    val pass1Bitmaps = pass1VisionSelection.first
    val pass2Bitmaps = pass2VisionSelection.first

    val providerLabel = if (useExternalProvider) {
      " [${externalProvider.serviceName} • ${externalProvider.modelId}]"
    } else {
      ""
    }

    onStageUpdate(
      "01a-01g",
      "Stage 01a–01g: Executing Evidence-Grounded Critique (${options.reviewMode.shortLabel})$providerLabel...",
      0.24f
    )

    val pass1Prompt = buildPass1RedTeamPrompt(
      title = title,
      authors = authors,
      year = year,
      venueOrCitation = venueOrCitation,
      manuscriptText = primaryPrepared.text,
      forensicContext = forensicContext,
      reviewStrictness = reviewStrictness,
      attachedPageCount = if (useExternalProvider) 0 else pass1Bitmaps.count { !it.isRecycled }.coerceAtMost(4),
      options = options
    )
    val pass1CompactPrompt = buildPass1RedTeamPrompt(
      title = title,
      authors = authors,
      year = year,
      venueOrCitation = venueOrCitation,
      manuscriptText = compactPrepared.text,
      forensicContext = forensicContext.take(4_500),
      reviewStrictness = reviewStrictness,
      attachedPageCount = if (useExternalProvider) 0 else pass1Bitmaps.take(2).count { !it.isRecycled },
      options = options
    )
    val pass1KeysSpec = "Return ONLY a valid JSON object with exact string keys: extractedTitle, extractedAuthors, extractedYear, studyDesignType, abstractSummary, researchQuestion, keyMethodology, coreContributions, stage01aBreaker, stage01bButcher, stage01cShredder, stage01dCollector, stage01gTheVoid."

    val pass1Result = executeResilientJsonPass(
      primaryModel = pass1RedTeamModel,
      liteSchemaModel = pass1FastFallbackModel,
      plainJsonModel = pass1PlainJsonFallbackModel,
      primaryPrompt = pass1Prompt,
      compactPrompt = pass1CompactPrompt,
      plainJsonDirective = pass1KeysSpec,
      attachedBitmaps = pass1Bitmaps,
      attachedPageNumbers = pass1VisionSelection.second,
      stageCode = "01a-01g",
      stageTitle = "Stage 01a–01g Appraisal$providerLabel",
      startProgress = 0.24f,
      endProgress = 0.54f,
      externalProvider = externalProvider,
      useExternalProvider = useExternalProvider,
      externalSystemPrompt = pass1KeysSpec,
      autoFallbackToKeylessFirebase = autoFallbackToKeylessFirebase,
      allowInternalModelFallback = allowInternalModelFallback,
      onStageUpdate = onStageUpdate
    )
    val pass1Json = pass1Result.json

    val extractedTitle = pass1Json.optString("extractedTitle", "").trim()
    val extractedAuthors = pass1Json.optString("extractedAuthors", "").trim()
    val extractedYear = pass1Json.optString("extractedYear", "").trim()
    val extractedDesign = pass1Json.optString("studyDesignType", "").trim()
      .ifBlank { forensicReport.studyDesign.displayName }

    val resolvedTitle = com.example.util.DossierFormattingEngine.normalizeTitle(
      when {
        extractedTitle.length in 10..220 && (title.isBlank() || title.lowercase(Locale.US) == title) -> extractedTitle
        title.isNotBlank() -> title
        extractedTitle.isNotBlank() -> extractedTitle
        else -> "Untitled Academic Manuscript"
      }
    )
    val resolvedAuthors = when {
      authors.isNotBlank() -> authors.trim()
      extractedAuthors.isNotBlank() && !extractedAuthors.equals("unknown", ignoreCase = true) -> extractedAuthors
      else -> "Authors Not Verified"
    }
    val resolvedYear = when {
      year.isNotBlank() -> year.trim()
      extractedYear.matches(Regex("""(19|20)\d{2}""")) -> extractedYear
      else -> ""
    }
    val resolvedVenue = venueOrCitation.trim().ifBlank {
      "$extractedDesign • ${options.targetVenue}"
    }

    val abstractSummary = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("abstractSummary", "Not available.")
    )
    val researchQuestion = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("researchQuestion", "Not available.")
    )
    val keyMethodology = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("keyMethodology", "Not available.")
    )
    val coreContributions = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("coreContributions", "Not available.")
    )
    val stage01aBreaker = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("stage01aBreaker", "")
    )
    val stage01bButcher = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("stage01bButcher", "")
    )
    val stage01cShredder = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("stage01cShredder", "")
    )
    val stage01dCollector = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("stage01dCollector", "")
    )
    val stage01gTheVoid = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass1Json.optString("stage01gTheVoid", "")
    )

    val compiledRedTeamReport = buildString {
      appendLine("[STAGE 01a — THE BREAKER: Intellectual & Theoretical Foundations]")
      appendLine(stage01aBreaker.trim())
      appendLine()
      appendLine("[STAGE 01b — THE BUTCHER: Empirical Design, Identification & Construct Validity]")
      appendLine(stage01bButcher.trim())
      appendLine()
      appendLine("[STAGE 01c — THE SHREDDER: Forensic Procedural & Sample/Protocol Audit]")
      appendLine(stage01cShredder.trim())
      appendLine()
      appendLine("[STAGE 01d — THE COLLECTOR: Footnotes, Table Notes & Cross-References]")
      appendLine(stage01dCollector.trim())
      appendLine()
      appendLine("[STAGE 01g — THE VOID: Unmeasured Confounds, Omitted Factors & External Validity]")
      appendLine(stage01gTheVoid.trim())
    }.trim()

    // ==========================================================================
    // PASS 2 (CONCURRENT):
    //   • Pass 2A: Specialist 8-Pillar Quantitative & Statistical Audit
    //   • Pass 2B: Source-Grounded Verification, Structured Findings & Synthesis
    // ==========================================================================
    onStageUpdate(
      "01e-03c",
      if (options.enableMathAudit) {
        "Stage 01e–03c: Running Deep Quantitative Audit & Source-Grounded Verification$providerLabel..."
      } else {
        "Stage 02a–03c: Running Blue Team Defense & Source-Grounded Adjudication$providerLabel..."
      },
      0.56f
    )

    val pass2Prompt = buildPass2VerificationPrompt(
      title = resolvedTitle,
      authors = resolvedAuthors,
      year = resolvedYear,
      venueOrCitation = resolvedVenue,
      studyDesignLabel = extractedDesign,
      manuscriptText = primaryPrepared.text,
      replicationCodeText = replicationCodeText.take(12_000),
      forensicContext = forensicContext,
      coreContributions = coreContributions,
      compiledRedTeamReport = compiledRedTeamReport.take(6_500),
      reviewStrictness = reviewStrictness,
      options = options
    )
    val pass2CompactPrompt = buildPass2VerificationPrompt(
      title = resolvedTitle,
      authors = resolvedAuthors,
      year = resolvedYear,
      venueOrCitation = resolvedVenue,
      studyDesignLabel = extractedDesign,
      manuscriptText = compactPrepared.text,
      replicationCodeText = replicationCodeText.take(5_000),
      forensicContext = forensicContext.take(4_500),
      coreContributions = coreContributions.take(1_800),
      compiledRedTeamReport = compiledRedTeamReport.take(4_500),
      reviewStrictness = reviewStrictness,
      options = options
    )
    val pass2KeysSpec = "Return ONLY a valid JSON object with exact keys: credibilityScore (int 0-100), refereeConfidence (int 1-5), noveltyScore (int 1-10), methodologyScore (int 1-10), mathRigorScore (int 1-10), reproducibilityScore (int 1-10), clarityScore (int 1-10), significanceScore (int 1-10), verdict, rubricRationaleSummary, structuredFindings (array of objects with id, title, allegation, severity, status, pageNumber, section, supportingQuote, counterEvidenceQuote, methodologicalJustification, recommendedCorrection), stage01eTo01fdMathAudit, stage01iTo01nCodeAndDataAudit, stage02aNumbersCheck, stage02cBlueTeamDefense, stage02eTo03cVerifiedListV2, stage04aIsItCredibleEssay, stage08aTo08bAlchemistEditorNote, stage09aTo09cCopyeditReport, fortifyReviewerQuestions."

    val quantKeysSpec = "Return ONLY a valid JSON object with exact keys: quantitativeRigorScore (int 1-10), estimatorAndModelAudit, numericAndStatcheckAudit, powerAndMultiplicityAudit, biasHeterogeneityAudit, formalMathRederivation, quantAuditMatrixRows, sensitivityPrescriptions, soberVerificationSummary."

    val (pass2Result, quantResultOrNull) = coroutineScope {
      val quantDeferred = if (options.enableMathAudit) {
        async {
          runCatching {
            val quantPrompt = buildPass2QuantAuditPrompt(
              title = resolvedTitle,
              studyDesignLabel = extractedDesign,
              studyDesignArchetype = forensicReport.studyDesign,
              manuscriptText = primaryPrepared.text,
              replicationCodeText = replicationCodeText.take(8_000),
              forensicReport = forensicReport,
              redTeamExcerpt = compiledRedTeamReport.take(3_500),
              reviewStrictness = reviewStrictness
            )
            val quantCompactPrompt = buildPass2QuantAuditPrompt(
              title = resolvedTitle,
              studyDesignLabel = extractedDesign,
              studyDesignArchetype = forensicReport.studyDesign,
              manuscriptText = compactPrepared.text,
              replicationCodeText = replicationCodeText.take(4_000),
              forensicReport = forensicReport,
              redTeamExcerpt = compiledRedTeamReport.take(2_400),
              reviewStrictness = reviewStrictness
            )
            executeResilientJsonPass(
              primaryModel = pass2QuantModel,
              liteSchemaModel = pass2QuantLiteModel,
              plainJsonModel = pass2QuantPlainModel,
              primaryPrompt = quantPrompt,
              compactPrompt = quantCompactPrompt,
              plainJsonDirective = quantKeysSpec,
              attachedBitmaps = pass2Bitmaps,
              attachedPageNumbers = pass2VisionSelection.second,
              stageCode = "01e-01fd",
              stageTitle = "Stage 01e–01fd Deep Quantitative Audit",
              startProgress = 0.56f,
              endProgress = 0.88f,
              externalProvider = externalProvider,
              useExternalProvider = useExternalProvider,
              externalSystemPrompt = quantKeysSpec,
              autoFallbackToKeylessFirebase = autoFallbackToKeylessFirebase,
              allowInternalModelFallback = allowInternalModelFallback,
              onStageUpdate = { _, _, _ -> /* Progress driven smoothly by primary synthesis pass */ }
            )
          }.onFailure { err ->
            if (err is CancellationException) throw err
            Log.w(TAG, "Specialist Quantitative Audit pass fell back to Pass 2B math summary: ${formatThrowableDetails(err)}")
          }.getOrNull()
        }
      } else {
        null
      }

      val synthesisDeferred = async {
        executeResilientJsonPass(
          primaryModel = pass2VerificationModel,
          liteSchemaModel = pass2FastFallbackModel,
          plainJsonModel = pass2PlainJsonFallbackModel,
          primaryPrompt = pass2Prompt,
          compactPrompt = pass2CompactPrompt,
          plainJsonDirective = pass2KeysSpec,
          attachedBitmaps = pass2Bitmaps,
          attachedPageNumbers = pass2VisionSelection.second,
          stageCode = "01e-09c",
          stageTitle = if (options.enableMathAudit) {
            "Stage 01e–09c Deep Quant Audit & Verification$providerLabel"
          } else {
            "Stage 02a–09c Verification & Synthesis$providerLabel"
          },
          startProgress = 0.56f,
          endProgress = 0.90f,
          externalProvider = externalProvider,
          useExternalProvider = useExternalProvider,
          externalSystemPrompt = pass2KeysSpec,
          autoFallbackToKeylessFirebase = autoFallbackToKeylessFirebase,
          allowInternalModelFallback = allowInternalModelFallback,
          onStageUpdate = onStageUpdate
        )
      }

      val synth = synthesisDeferred.await()
      val quant = quantDeferred?.await()
      synth to quant
    }

    val pass2Json = pass2Result.json
    val quantJsonOrNull = quantResultOrNull?.json

    onStageUpdate(
      "04a-09c",
      "Stage 04a–09c: Running Cross-Pass Source Adjudication & Rubric Reconciliation...",
      0.93f
    )

    val rawNoveltyScore = pass2Json.optInt("noveltyScore", 7).coerceIn(1, 10)
    val rawMethodologyScore = pass2Json.optInt("methodologyScore", 7).coerceIn(1, 10)
    val pass2MathScore = pass2Json.optInt("mathRigorScore", 7).coerceIn(1, 10)
    val specialistQuantScore = quantJsonOrNull?.optInt("quantitativeRigorScore", -1)?.takeIf { it in 1..10 }
    val reconciledMathScore = if (specialistQuantScore != null) {
      ((pass2MathScore + specialistQuantScore * 2) / 3.0).roundToInt().coerceIn(1, 10)
    } else {
      pass2MathScore
    }
    val rawReproducibilityScore = pass2Json.optInt("reproducibilityScore", 6).coerceIn(1, 10)
    val rawClarityScore = pass2Json.optInt("clarityScore", 8).coerceIn(1, 10)
    val rawSignificanceScore = pass2Json.optInt("significanceScore", 7).coerceIn(1, 10)
    val refereeConfidence = pass2Json.optInt("refereeConfidence", 4).coerceIn(1, 5)

    // Parse LLM structuredFindings and run deterministic cross-pass adjudication + strict source-quote verification
    val rawStructuredFindings = parseStructuredFindingsFromJson(pass2Json.optJSONArray("structuredFindings"))
    val allImpactedPromptPages = (primaryPrepared.omittedPages + primaryPrepared.partiallyRetainedPages).distinct()
    val preliminaryExtractionConfidence = computeCalibratedExtractionConfidence(
      manuscriptText = manuscriptText,
      coverageReport = coverageReport,
      omittedPromptPages = allImpactedPromptPages,
      wasPromptTrimmed = primaryPrepared.wasTrimmed,
      adjudicatedFindings = emptyList(),
      statisticalAudit = forensicReport.quantForensics.statisticalAudit
    )
    val coverageAwareEpistemic = forensicReport.quantForensics.statisticalAudit?.let { statAudit ->
      EpistemicSafeguardEngine.evaluate(
        text = manuscriptText,
        statAudit = statAudit,
        coverageReport = coverageReport,
        extractionConfidence = preliminaryExtractionConfidence
      )
    } ?: forensicReport.quantForensics.epistemicSafeguards
    val promptCoverageLabel = if (coverageReport != null) {
      "${coverageReport.extractedPages}/${coverageReport.totalPages} pages"
    } else if (primaryPrepared.wasTrimmed) {
      "Trimmed (${primaryPrepared.omittedPages.size} omitted)"
    } else {
      "100%"
    }
    val actualExecutedModelLabel = "${pass2Result.actualModelId} [${pass2Result.actualRoute}]"
    val adjudicated = adjudicateFindingsAndScores(
      rawFindings = rawStructuredFindings,
      manuscriptText = manuscriptText,
      forensicReport = forensicReport,
      methodologyScore = rawMethodologyScore,
      significanceScore = rawSignificanceScore,
      clarityScore = rawClarityScore,
      originalityScore = rawNoveltyScore,
      mathRigorScore = reconciledMathScore,
      reproducibilityScore = rawReproducibilityScore,
      rawVerdict = pass2Json.optString("verdict", ""),
      reviewMode = options.reviewMode,
      extractionConfidence = preliminaryExtractionConfidence,
      applyStatisticalScoreGate = true,
      epistemicReport = coverageAwareEpistemic,
      modelVersion = actualExecutedModelLabel,
      promptCoverage = promptCoverageLabel
    )

    val credibilityScore = adjudicated.weightedCredibilityScore.toLong()
    val structuredFindingsJson = ReviewFindingCodec.encode(adjudicated.adjudicatedFindings)

    // Single authoritative ConfidenceSummary + CoverageSummary computed BEFORE finalizing GRADE
    // certainty, final verdict, and validationStatus so Page 1 banner, rubric metadata, coverage
    // disclosure, uncertainty budget, and persisted PaperReview can NEVER contradict each other (P1-13).
    val confidenceSummary = computeAuthoritativeConfidenceSummary(
      manuscriptText = manuscriptText,
      coverageReport = coverageReport,
      omittedPromptPages = allImpactedPromptPages,
      wasPromptTrimmed = primaryPrepared.wasTrimmed,
      adjudicatedFindings = adjudicated.adjudicatedFindings,
      statisticalAudit = forensicReport.quantForensics.statisticalAudit,
      scientificConfidence = refereeConfidence
    )
    val extractionConfidence = confidenceSummary.extractionConfidence
    val finalEpistemic = forensicReport.quantForensics.statisticalAudit?.let { statAudit ->
      EpistemicSafeguardEngine.evaluate(
        text = manuscriptText,
        statAudit = statAudit,
        coverageReport = coverageReport,
        extractionConfidence = extractionConfidence
      )
    } ?: coverageAwareEpistemic

    val clinicalGradeAssessment = if (options.reviewMode == ReviewMode.CLINICAL_EVIDENCE_APPRAISAL) {
      evaluateClinicalGradeCertainty(
        manuscriptText = manuscriptText,
        forensicReport = forensicReport,
        adjudicatedFindings = adjudicated.adjudicatedFindings,
        extractionConfidence = extractionConfidence
      )
    } else null

    val verdict = reconcileFinalVerdictWithEpistemicState(
      adjudicatedVerdict = adjudicated.resolvedVerdict,
      clinicalGradeAssessment = clinicalGradeAssessment,
      finalEpistemic = finalEpistemic,
      finalExtractionConfidence = extractionConfidence,
      reviewMode = options.reviewMode
    )
    val anyJsonRepaired = pass1Result.wasRepaired || pass2Result.wasRepaired || (quantResultOrNull?.wasRepaired == true)
    val statAuditForCoverage = forensicReport.quantForensics?.statisticalAudit
    val statTruncatedCount = statAuditForCoverage?.truncatedClaimCount ?: 0
    val statReconstructionSkipped = statAuditForCoverage?.coverageMetrics?.reconstructionSkipped ?: 0
    val totalQuantitativeCapOmissions = statTruncatedCount + statReconstructionSkipped + adjudicated.ledgerCapOmittedCount
    val validationStatus = computeValidationStatus(
      resolvedVerdict = verdict,
      adjudicatedFindings = adjudicated.adjudicatedFindings,
      anyJsonRepaired = anyJsonRepaired,
      omittedOrPartialPromptPagesCount = allImpactedPromptPages.size,
      wasPromptTrimmed = primaryPrepared.wasTrimmed,
      extractionConfidence = extractionConfidence,
      hasSubstantiveEditorialProse = pass2Json.optString("stage08aTo08bAlchemistEditorNote", "").isNotBlank() ||
        pass2Json.optString("stage04aIsItCredibleEssay", "").isNotBlank(),
      truncatedStatisticalClaimCount = totalQuantitativeCapOmissions
    )
    // P1-11: Record ACTUAL executed models, routes, image pages processed, and fallback history
    val actualFallbackNotes = (pass1Result.fallbackHistory + pass2Result.fallbackHistory + quantResultOrNull?.fallbackHistory.orEmpty()).distinct()
    val coverageDisclosure = buildString {
      if (coverageReport != null) {
        append(
          coverageReport.toDisclosureLine(
            overrideExtractionConfidence = extractionConfidence,
            coverageSummary = confidenceSummary.coverageSummary
          )
        )
      } else {
        val wordCnt = if (manuscriptText.isBlank()) 0 else manuscriptText.trim().split(Regex("""\s+""")).size
        append("Direct text input • %,d words • %,d chars • Extraction Confidence: %d/5".format(Locale.US, wordCnt, manuscriptText.length, extractionConfidence))
      }
      val p1ImgLabel = if (pass1Result.imagesSentCount > 0) {
        pass1Result.sentImagePageNumbers.joinToString(",") { "P$it" }.ifBlank { "${pass1Result.imagesSentCount} img" }
      } else "0 (text-only)"
      val p2ImgLabel = if (pass2Result.imagesSentCount > 0) {
        pass2Result.sentImagePageNumbers.joinToString(",") { "P$it" }.ifBlank { "${pass2Result.imagesSentCount} img" }
      } else "0 (text-only)"
      append(" • Executed Models: Pass1=${pass1Result.actualModelId} [${pass1Result.actualRoute}, images=$p1ImgLabel]; Pass2=${pass2Result.actualModelId} [${pass2Result.actualRoute}, images=$p2ImgLabel]")
      if (quantResultOrNull != null) {
        val qImgLabel = if (quantResultOrNull.imagesSentCount > 0) {
          quantResultOrNull.sentImagePageNumbers.joinToString(",") { "P$it" }.ifBlank { "${quantResultOrNull.imagesSentCount} img" }
        } else "0"
        append("; Quant=${quantResultOrNull.actualModelId} [images=$qImgLabel]")
      }
      if (actualFallbackNotes.isNotEmpty()) {
        append(" • Fallbacks: ${actualFallbackNotes.joinToString(" | ")}")
      }
      if (statAuditForCoverage != null) {
        append(
          " • ${
            statAuditForCoverage.formatCoverageLedgerSummary(
              ledgerCapOmittedCount = adjudicated.ledgerCapOmittedCount,
              ledgerCapCategories = adjudicated.ledgerCapCategories
            )
          }"
        )
      } else if (adjudicated.ledgerCapOmittedCount > 0) {
        val capDesc = if (adjudicated.ledgerCapCategories.isNotEmpty()) {
          " [caps: ${adjudicated.ledgerCapCategories.distinct().joinToString(", ")}]"
        } else ""
        append(
          " • Quantitative Coverage Partial: discoverySkipped=0, reconstructionSkipped=0, unsupported=0, " +
            "ledgerCapOmitted=${adjudicated.ledgerCapOmittedCount}$capDesc"
        )
      }
      if (primaryPrepared.wasTrimmed && primaryPrepared.trimmedNote.isNotBlank()) {
        append(" • ${primaryPrepared.trimmedNote}")
      }
      if (anyJsonRepaired) {
        append(" • Note: Partial JSON repair applied on an AI stage; inspect findings carefully.")
      }
    }

    val rubricRationaleSummary = pass2Json.optString("rubricRationaleSummary", "").trim()
    val parsedRationales = extractRubricRationales(rubricRationaleSummary)

    val stage01eTo01fdMathAudit = sanitizeProseAgainstDismissedFindings(
      com.example.util.DossierFormattingEngine.cleanAcademicProse(pass2Json.optString("stage01eTo01fdMathAudit", "")),
      adjudicated.adjudicatedFindings
    )
    val stage01iTo01nCodeAndDataAudit = sanitizeProseAgainstDismissedFindings(
      com.example.util.DossierFormattingEngine.cleanAcademicProse(pass2Json.optString("stage01iTo01nCodeAndDataAudit", "")),
      adjudicated.adjudicatedFindings
    )
    val stage02aNumbersCheck = sanitizeProseAgainstDismissedFindings(
      com.example.util.DossierFormattingEngine.cleanAcademicProse(pass2Json.optString("stage02aNumbersCheck", "")),
      adjudicated.adjudicatedFindings
    )
    val stage02cBlueTeamDefense = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage02cBlueTeamDefense", "")
    )
    val stage02eTo03cVerifiedListV2 = sanitizeProseAgainstDismissedFindings(
      com.example.util.DossierFormattingEngine.cleanAcademicProse(pass2Json.optString("stage02eTo03cVerifiedListV2", "")),
      adjudicated.adjudicatedFindings
    )
    val credibleEssaySection = buildEvidenceGovernedReportSection(
      title = "Credibility Synthesis",
      rawProse = com.example.util.DossierFormattingEngine.cleanAcademicProse(pass2Json.optString("stage04aIsItCredibleEssay", "")),
      adjudicatedFindings = adjudicated.adjudicatedFindings
    )
    val stage04aIsItCredibleEssay = credibleEssaySection.renderMarkdown()
    val editorNoteSection = buildEvidenceGovernedReportSection(
      title = "Editorial Decision & Action Plan",
      rawProse = com.example.util.DossierFormattingEngine.cleanAcademicProse(pass2Json.optString("stage08aTo08bAlchemistEditorNote", "")),
      adjudicatedFindings = adjudicated.adjudicatedFindings
    )
    val stage08aTo08bAlchemistEditorNote = editorNoteSection.renderMarkdown()
    val stage09aTo09cCopyeditReport = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage09aTo09cCopyeditReport", "")
    )
    val fortifyReviewerQuestions = sanitizeProseAgainstDismissedFindings(
      com.example.util.DossierFormattingEngine.cleanAcademicProse(pass2Json.optString("fortifyReviewerQuestions", "")),
      adjudicated.adjudicatedFindings
    )

    val methodologyJustification = sanitizeProseAgainstDismissedFindings(
      parsedRationales["methodology"]
        ?: "Evaluated design rigor, bias controls, and synthesis alignment (${forensicReport.studyDesign.riskOfBiasInstrument}).",
      adjudicated.adjudicatedFindings
    )
    val significanceJustification = sanitizeProseAgainstDismissedFindings(
      parsedRationales["significance"]
        ?: "Evaluated scientific contribution, generalizability, and clinical/policy utility.",
      adjudicated.adjudicatedFindings
    )
    val clarityJustification = sanitizeProseAgainstDismissedFindings(
      parsedRationales["clarity"]
        ?: "Evaluated reporting completeness (${forensicReport.studyDesign.reportingGuideline}), structure, and table/text precision.",
      adjudicated.adjudicatedFindings
    )
    val originalityJustification = sanitizeProseAgainstDismissedFindings(
      parsedRationales["originality"]
        ?: "Evaluated originality of the research question, dataset scope, and analytical contribution.",
      adjudicated.adjudicatedFindings
    )
    val mathRigorJustification = sanitizeProseAgainstDismissedFindings(
      parsedRationales["math"]
        ?: "Evaluated statistical estimation, CI geometry, heterogeneity/variance reporting, and numerical consistency.",
      adjudicated.adjudicatedFindings
    )
    val reproducibilityJustification = sanitizeProseAgainstDismissedFindings(
      parsedRationales["reproducibility"]
        ?: "Evaluated protocol transparency, search/code provenance, and replication readiness.",
      adjudicated.adjudicatedFindings
    )

    // P0-01 Single-Source-of-Truth Reporting:
    // Generate the adjudicated findings matrix exclusively from adjudicated.adjudicatedFindings.
    // Never append raw unadjudicated LLM prose (stage02eTo03cVerifiedListV2) alongside the ledger.
    val compiledBlueTeamCascade = buildString {
      appendLine("[STAGE 02e–03c — ADJUDICATED FINDINGS MATRIX & VERIFIED LIST v2]")
      if (adjudicated.adjudicatedFindings.isNotEmpty()) {
        adjudicated.adjudicatedFindings.forEach { f ->
          val badge = when (f.status) {
            FindingStatus.CONTRADICTED -> "[DISMISSED]"
            else -> "[${f.severity}]"
          }
          val pg = f.supportingEvidence.firstOrNull()?.pageNumber?.takeIf { it > 0 }?.let { " (Page $it)" } ?: ""
          appendLine("$badge [${f.id} • ${f.status.displayBadge}]$pg ${f.title} — ${f.allegation}")
          val quote = f.supportingEvidence.firstOrNull()?.exactQuote?.trim().orEmpty()
          if (quote.isNotBlank()) {
            appendLine("• Source Quote$pg: \"$quote\"")
          }
          val counter = f.counterEvidence.firstOrNull()?.exactQuote?.trim().orEmpty()
          if (counter.isNotBlank()) {
            val cpg = f.counterEvidence.firstOrNull()?.pageNumber?.takeIf { it > 0 }?.let { " (Page $it)" } ?: ""
            appendLine("• Counterevidence$cpg: \"$counter\"")
          }
          if (f.verificationNotes.isNotBlank()) {
            appendLine("• Verification Audit: ${f.verificationNotes}")
          }
          if (f.flawTier.isNotBlank() || f.reportingStatus.isNotBlank()) {
            val tierHuman = FlawSeverityTier.fromNameOrDefault(f.flawTier).displayLabel
            val reportHuman = ReportingStatus.fromNameOrDefault(f.reportingStatus).displayLabel
            appendLine("• Severity tier: $tierHuman | Reporting status: $reportHuman")
          }
          if (f.overturnEvidence.isNotBlank() && f.status != FindingStatus.CONTRADICTED) {
            appendLine("• Minimum Evidence to Overturn: ${f.overturnEvidence}")
          }
          if (f.recommendedCorrection.isNotBlank() && f.status != FindingStatus.CONTRADICTED) {
            appendLine("• Required Action: ${f.recommendedCorrection}")
          }
        }
        appendLine()
      } else if (stage02eTo03cVerifiedListV2.isNotBlank()) {
        appendLine("Note: No structured findings were emitted; showing filtered draft summary (validation status capped below VERIFIED).")
        appendLine(stage02eTo03cVerifiedListV2.trim())
        appendLine()
      }
      if (clinicalGradeAssessment != null) {
        appendLine("[CLINICAL GRADE CERTAINTY DOMAIN SUMMARY]")
        appendLine(clinicalGradeAssessment.toSummaryBlock())
        appendLine()
      }
      appendLine("[STAGE 02a — QUANTITATIVE & NUMBERS VERIFICATION]")
      appendLine(stage02aNumbersCheck.trim())
      appendLine()
      appendLine("[STAGE 02c — BLUE TEAM DEFENSE: Strongest Counterarguments for the Paper]")
      appendLine(stage02cBlueTeamDefense.trim())
    }.trim()

    val mathAuditReport = if (options.enableMathAudit) {
      compileUltraDeepQuantitativeAudit(
        forensicReport = forensicReport,
        quantJson = quantJsonOrNull,
        fallbackMathAudit = stage01eTo01fdMathAudit,
        fallbackNumbersCheck = stage02aNumbersCheck,
        calibratedQuantScore = adjudicated.mathRigorScore,
        scoreBreakdown = adjudicated.scoreBreakdown,
        epistemicOverride = finalEpistemic
      )
    } else {
      "Quantitative, Statistical & Mathematical Audit (Stages 01e–01fd) skipped by user configuration."
    }

    val codeAuditReport = if (options.enableCodeAudit) {
      buildString {
        if (forensicReport.proceduralLeakageAlerts.isNotEmpty()) {
          appendLine("[PRE-FLIGHT DOMAIN-EXACT AUDIT FLAGS (${forensicReport.studyDesign.displayName})]")
          forensicReport.proceduralLeakageAlerts.forEach { flag ->
            appendLine("• $flag")
          }
          appendLine()
        }
        if (forensicReport.clinicalApplicabilityNotes.isNotEmpty()) {
          appendLine("[CLINICAL & POLICY APPLICABILITY LENS]")
          forensicReport.clinicalApplicabilityNotes.forEach { note ->
            appendLine("• $note")
          }
          appendLine()
        }
        appendLine("[STAGE 01i–01n & 07b — REPRODUCIBILITY, PROVENANCE & METHODOLOGY X-RAY]")
        appendLine(stage01iTo01nCodeAndDataAudit.trim())
      }.trim()
    } else {
      "Reproducibility & Data Provenance Audit (Stages 01i–01n, 07b) skipped by user configuration."
    }

    val compiledEditorAndReviewerNote = buildString {
      appendLine(
        "[6-AXIS CALIBRATED SCORECARD: Credibility=$credibilityScore/100 | " +
          "Methodology=${adjudicated.methodologyScore}/10 | Significance=${adjudicated.significanceScore}/10 | " +
          "Clarity=${adjudicated.clarityScore}/10 | Novelty=${adjudicated.originalityScore}/10 | " +
          "Math=${adjudicated.mathRigorScore}/10 | Reproducibility=${adjudicated.reproducibilityScore}/10]"
      )
      appendLine("[CONFIDENCE:$refereeConfidence/5] [EXTRACTION_CONFIDENCE:$extractionConfidence/5] [MODE:${options.reviewMode.name}] [STATUS:$validationStatus]")
      appendLine("[RUBRIC:Methodology] $methodologyJustification")
      appendLine("[RUBRIC:Significance] $significanceJustification")
      appendLine("[RUBRIC:Clarity] $clarityJustification")
      appendLine("[RUBRIC:Originality] $originalityJustification")
      appendLine("[RUBRIC:Math] $mathRigorJustification")
      appendLine("[RUBRIC:Reproducibility] $reproducibilityJustification")
      appendLine()
      appendLine("[STAGE 04a — 'IS IT CREDIBLE?' ASSESSMENT & BOTTOM LINE (Stage 06 Legal-Checked)]")
      appendLine(stage04aIsItCredibleEssay.trim())
      if (options.enableEditorNote) {
        appendLine()
        appendLine("[STAGE 08a–08b — APPRAISAL DECISION MEMO & PRIORITIZED ACTION ROADMAP]")
        appendLine(
          alignEditorNoteWithVerdictAndMode(
            rawEditorNote = stage08aTo08bAlchemistEditorNote.trim(),
            findings = adjudicated.adjudicatedFindings,
            resolvedVerdict = verdict,
            reviewMode = options.reviewMode
          )
        )
      }
    }.trim()

    val copyeditSuggestions = if (options.enableCopyeditWriterMode) {
      buildString {
        appendLine("[STAGE 09a–09c — PROOFREADER & ACADEMIC COPYEDIT REPORT]")
        appendLine(stage09aTo09cCopyeditReport.trim())
      }.trim()
    } else {
      "Author-Facing Proofreading & Copyedit (Stages 09a–09c) skipped by user configuration."
    }

    onStageUpdate("10_render", "Stage 07 & 10: Assembling Evidence-Grounded Appraisal Dossier...", 0.97f)

    val formattedDossier = renderPlainTextDossier(
      title = resolvedTitle,
      authors = resolvedAuthors,
      year = resolvedYear,
      venueOrCitation = resolvedVenue,
      isEmpirical = options.isEmpirical,
      credibilityScore = credibilityScore,
      noveltyScore = adjudicated.originalityScore,
      methodologyScore = adjudicated.methodologyScore,
      mathRigorScore = adjudicated.mathRigorScore,
      reproducibilityScore = adjudicated.reproducibilityScore,
      clarityScore = adjudicated.clarityScore,
      verdict = verdict,
      forensicReport = forensicReport,
      abstractSummary = abstractSummary,
      researchQuestion = researchQuestion,
      keyMethodology = keyMethodology,
      coreContributions = coreContributions,
      redTeamCritique = compiledRedTeamReport,
      blueTeamVerification = compiledBlueTeamCascade,
      mathAuditReport = mathAuditReport,
      codeAuditReport = codeAuditReport,
      editorNote = com.example.util.DossierFormattingEngine.cleanAcademicProse(compiledEditorAndReviewerNote),
      copyeditSuggestions = copyeditSuggestions,
      reviewerQuestions = fortifyReviewerQuestions
    )

    val (detPartition, llmPartition) = adjudicated.adjudicatedFindings.partition {
      it.id.startsWith("Q-") || it.id.startsWith("STAT-") || it.id.startsWith("FORENSIC-") || it.id.startsWith("DET-")
    }
    val quantStatuses = buildList {
      statAuditForCoverage?.reconstructions.orEmpty().forEach { r ->
        add(
          QuantitativeAdjudicationContracts.mapStatus(
            result = r,
            sourceAnchorsVerified = extractionConfidence >= 4,
            estimandCompatible = true
          )
        )
      }
      statAuditForCoverage?.domainFindings.orEmpty().forEach { d ->
        add(
          QuantitativeAdjudicationContracts.mapDomainStatus(
            finding = d,
            sourceAnchorsVerified = extractionConfidence >= 4,
            estimandCompatible = true
          )
        )
      }
    }
    val passComparisons = listOf(
      PassComparisonInput(
        passName = "Pass1_RedTeam",
        designClassification = extractedDesign.ifBlank { null },
        primaryEndpoint = researchQuestion.take(100).ifBlank { null },
        criticalVerdict = pass2Json.optString("verdict", verdict).ifBlank { verdict }
      ),
      PassComparisonInput(
        passName = "Pass2_Verification",
        designClassification = extractedDesign.ifBlank { forensicReport.studyDesign.displayName },
        primaryEndpoint = researchQuestion.take(100).ifBlank { null },
        criticalVerdict = verdict
      )
    )
    val unexpectedFallback = actualFallbackNotes.isNotEmpty() && !autoFallbackToKeylessFirebase
    val refereeOutput = AutonomousRefereeOrchestrator.adjudicate(
      RefereeAdjudicationInput(
        analysisId = analysisId,
        manuscriptText = manuscriptText,
        replicationCodeText = replicationCodeText,
        pdfVisionDigestSha256 = pdfVisionDigestSha256,
        providerRoute = pass2Result.actualRoute,
        modelId = pass2Result.actualModelId,
        fallbackHistory = actualFallbackNotes,
        startedAtMs = startedAtMs,
        completedAtMs = System.currentTimeMillis(),
        readiness = refereeReadiness,
        extractionConfidence = extractionConfidence,
        totalPages = effectiveCoverageReport.totalPages,
        pagesExtracted = effectiveCoverageReport.extractedPages,
        pagesAnalyzed = (effectiveCoverageReport.extractedPages - allImpactedPromptPages.size).coerceAtLeast(0),
        omittedPages = (effectiveCoverageReport.omittedPages + allImpactedPromptPages).distinct().sorted(),
        claimsDiscovered = statAuditForCoverage?.totalDiscoveredClaims ?: 0,
        claimsRetained = statAuditForCoverage?.coverageMetrics?.parsedRetained ?: 0,
        claimsReconstructed = statAuditForCoverage?.coverageMetrics?.reconstructedCompleted ?: 0,
        claimsSkipped = totalQuantitativeCapOmissions,
        unsupportedClaims = statAuditForCoverage?.unsupportedAssessmentCount ?: 0,
        unresolvedClaims = adjudicated.adjudicatedFindings.count { it.status == FindingStatus.UNVERIFIED },
        extractionCapApplied = primaryPrepared.wasTrimmed || effectiveCoverageReport.omittedPages.isNotEmpty(),
        claimCapApplied = totalQuantitativeCapOmissions > 0,
        deterministicFindings = detPartition,
        llmFindings = llmPartition,
        passComparisons = passComparisons,
        quantitativeStatuses = quantStatuses,
        crossCheckChecklist = DiscrepancyCrossCheckChecklist(
          primaryTablesChecked = refereeReadiness.tablesAndFiguresDetectable,
          figuresChecked = refereeReadiness.tablesAndFiguresDetectable || pass2Result.imagesSentCount > 0,
          supplementsChecked = refereeReadiness.supplementStatus == com.example.data.ai.referee.SupplementStatus.PRESENT,
          scaleTransformationsChecked = true,
          roundingToleranceChecked = true,
          adjustedModelsChecked = true,
          subgroupScopeChecked = true,
          alternativeMethodsChecked = true
        ),
        domainAppraisal = domainAppraisal,
        unexpectedProviderFallback = unexpectedFallback,
        consentVerified = consentVerified,
        consentDigestAtApproval = consentDigestAtApproval
      )
    )
    val finalValidationStatus = when {
      !refereeOutput.decision.isVerified && validationStatus == "VERIFIED" ->
        if (refereeOutput.decision == RefereeDecision.INSUFFICIENT_EVIDENCE) "UNVERIFIED" else "ADJUDICATED_WITH_CAVEATS"
      else -> validationStatus
    }
    val finalStructuredFindingsJson = ReviewFindingCodec.encode(refereeOutput.mergedFindings)

    onStageUpdate("DONE", "Appraisal Dossier Complete", 1.0f)

    return PaperReview(
      title = resolvedTitle,
      authors = resolvedAuthors,
      publicationYear = resolvedYear,
      venueOrCitation = resolvedVenue,
      isEmpirical = options.isEmpirical,
      credibilityScore = credibilityScore,
      verdict = verdict,
      methodologyScore = adjudicated.methodologyScore,
      significanceScore = adjudicated.significanceScore,
      clarityScore = adjudicated.clarityScore,
      originalityScore = adjudicated.originalityScore,
      mathRigorScore = adjudicated.mathRigorScore,
      reproducibilityScore = adjudicated.reproducibilityScore,
      methodologyJustification = methodologyJustification,
      significanceJustification = significanceJustification,
      clarityJustification = clarityJustification,
      originalityJustification = originalityJustification,
      mathRigorJustification = mathRigorJustification,
      reproducibilityJustification = reproducibilityJustification,
      refereeConfidence = refereeConfidence,
      extractionConfidence = extractionConfidence,
      reviewMode = options.reviewMode.name,
      coverageDisclosure = coverageDisclosure,
      validationStatus = finalValidationStatus,
      structuredFindingsJson = finalStructuredFindingsJson,
      schemaVersion = 5,
      abstractSummary = abstractSummary,
      researchQuestion = researchQuestion,
      keyMethodology = keyMethodology,
      coreContributions = coreContributions,
      redTeamCritique = compiledRedTeamReport,
      blueTeamVerification = compiledBlueTeamCascade,
      mathAuditReport = mathAuditReport,
      codeAuditReport = codeAuditReport,
      editorNote = compiledEditorAndReviewerNote,
      copyeditSuggestions = copyeditSuggestions,
      reviewerQuestions = fortifyReviewerQuestions,
      formattedDossier = formattedDossier,
      refereeDecision = refereeOutput.decision.name,
      analysisManifestJson = refereeOutput.manifest.toJson(),
      abstentionSummaryJson = refereeOutput.abstentionResult.toJson(),
      readinessSummaryJson = refereeReadiness.toJson(),
      auditLogJson = refereeOutput.auditLogJson,
      refereeCoverageJson = refereeOutput.coverageMetrics.toJson()
    )
    } finally {
      remotePayloadCleanupHook?.cleanupAll("pipeline_finished")
    }
  }

  fun streamFollowUpDefense(
    review: PaperReview,
    question: String
  ): Flow<String> = flow {
    val accumulated = StringBuilder()
    val prompt = """
      You are the Lead Referee, Stage 08a Alchemist, and Stage 02c Blue Team Mentor from the 'isitcredible/reviewer2' and 'phd-skills' peer-review engine.
      You are advising the author/researcher on the following audited manuscript:
      Title: ${review.title}
      Authors: ${review.authors} (${review.publicationYear.ifBlank { "Year not verified" }})
      Verdict: ${review.verdict} (Credibility Score: ${review.credibilityScore}/100)
      Abstract Summary: ${review.abstractSummary}
      Red Team Critique (01a Breaker / 01b Butcher / 01c Shredder / 01g Void):
      ${review.redTeamCritique}
      Blue Team & Fact-Checked List v2 (02a–03c):
      ${review.blueTeamVerification}
      Math Audit (01e–01fd):
      ${review.mathAuditReport}
      Code & Leakage X-Ray (01i–01n):
      ${review.codeAuditReport}

      Author / Researcher Query:
      $question

      Provide a concrete, mathematically and methodologically rigorous response:
      1. Address the exact surviving issue(s) from Stage 03c List v2.
      2. Provide ready-to-paste rebuttal text or LaTeX formulation improvements.
      3. Specify exact minimum-viable robustness checks or ablations if needed.
    """.trimIndent()

    withTimeout(90_000L) {
      streamingFollowUpModel.generateContentStream(prompt).collect { chunk ->
        val text = chunk.text.orEmpty()
        accumulated.append(text)
        emit(accumulated.toString())
      }
    }
  }.flowOn(Dispatchers.IO)

  // ============================================================================
  // STAGE 03b: External Literature & Citation Grounding via Google Search
  // ============================================================================
  suspend fun runGoogleSearchLiteratureVerification(
    title: String,
    authors: String,
    year: String,
    venue: String,
    claimsAndSummary: String
  ): SearchGroundingResult = withContext(Dispatchers.IO) {
    val searchGroundedModel = Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      systemInstruction = content {
        text(
          """
          You are Stage 03b (External Literature, Citation & Prior-Art Fact Checker) of the 'isitcredible/reviewer2' and 'phd-skills' peer-review engine.
          Use Google Search Grounding to verify:
          1. Whether the paper's core novelty claims [C1..Cn] have close prior art or concurrent preprints/publications.
          2. Whether cited benchmark numbers, state-of-the-art (SOTA) baselines, or canonical empirical estimates match published literature.
          3. Whether there are any known errata, replications, retractions, or public methodological debates related to the topic or cited literature.
          Provide a structured, concise Stage 03b External Grounding Report with bulleted findings.
          """.trimIndent()
        )
      },
      tools = listOf(Tool.googleSearch())
    )

    val prompt = """
      Execute Stage 03b External Literature & Citation Grounding for:
      - Title: ${title.ifBlank { "Untitled Manuscript" }}
      - Authors: ${authors.ifBlank { "Anonymous" }} (${year.ifBlank { "Year not verified" }})
      - Venue / Citation: $venue
      - Claims, Summary & Methodology:
      $claimsAndSummary

      Provide:
      1. [PRIOR ART & NOVELTY DELTA CHECK]: Closest existing papers, benchmarks, or theoretical results found via Google Search.
      2. [CITATION & BENCHMARK VERIFICATION]: Verification of cited SOTA baselines, datasets, or empirical claims.
      3. [EXTERNAL REPLICATION / ERRATA STATUS]: Any notable replication notes or open methodological caveats in the literature.
    """.trimIndent()

    val response = withTimeout(90_000L) {
      searchGroundedModel.generateContent(prompt)
    }

    val (queries, sources) = extractGroundingMetadata(response)
    val summary = response.text?.trim().orEmpty().ifBlank {
      "Stage 03b Google Search Grounding completed, but no text summary was returned."
    }

    SearchGroundingResult(
      summaryText = summary,
      searchQueries = queries,
      sources = sources
    )
  }

  // ============================================================================
  // MULTI-TURN GEMINI REFEREE CHATBOT (startChat + sendMessageStream + Grounding)
  // ============================================================================
  fun streamMultiTurnRefereeChat(
    review: PaperReview?,
    history: List<ChatTurnInput>,
    userMessage: String,
    persona: ChatbotRolePersona,
    modelTier: GeminiModelTier,
    enableSearchGrounding: Boolean,
    externalProvider: com.example.data.model.AiApiProviderEntry? = null,
    autoFallbackToKeylessFirebase: Boolean = true
  ): Flow<MultiTurnStreamChunk> = flow {
    val systemPrompt = buildSystemInstructionForPersona(persona, review, enableSearchGrounding)

    val isCustomByokProvider = externalProvider != null &&
      externalProvider.protocolType != com.example.data.model.ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS &&
      externalProvider.apiKey.isNotBlank()
    val requiresGoogleSearch = enableSearchGrounding || persona == ChatbotRolePersona.SEARCH_FACT_CHECKER
    if (isCustomByokProvider && requiresGoogleSearch && !autoFallbackToKeylessFirebase) {
      throw IllegalStateException(
        "Google Search Grounding requires Firebase AI Logic, but automatic Firebase fallback is disabled while '${externalProvider?.serviceName}' is active. " +
          "Disable Web Search Grounding, switch provider to Firebase AI Logic, or explicitly enable Firebase fallback in API Settings."
      )
    }

    val useExternalProvider = isCustomByokProvider && !requiresGoogleSearch

    if (useExternalProvider) {
      val transcriptPrompt = buildString {
        if (history.isNotEmpty()) {
          appendLine("CONVERSATION HISTORY:")
          history.takeLast(12).forEach { turn ->
            appendLine("[${turn.role.uppercase()}]: ${turn.text}")
          }
          appendLine()
        }
        appendLine("[USER]: $userMessage")
      }
      val externalResult = runCatching {
        ExternalApiGatewayClient.executeCompletion(
          provider = externalProvider,
          systemPrompt = systemPrompt,
          userPrompt = transcriptPrompt,
          expectJsonObject = false
        )
      }
      if (externalResult.isSuccess) {
        emit(MultiTurnStreamChunk(partialText = externalResult.getOrThrow()))
        return@flow
      } else if (!autoFallbackToKeylessFirebase) {
        throw externalResult.exceptionOrNull()
          ?: IllegalStateException("External API provider failed.")
      }
    }

    val activeTools = if (enableSearchGrounding || persona == ChatbotRolePersona.SEARCH_FACT_CHECKER) {
      listOf(Tool.googleSearch())
    } else {
      emptyList()
    }

    val chatModel = Firebase.ai.generativeModel(
      modelName = modelTier.modelId,
      systemInstruction = content { text(systemPrompt) },
      tools = activeTools
    )

    val formattedHistory = history
      .filter { it.text.isNotBlank() }
      .takeLast(16)
      .map { turn ->
        val validRole = if (turn.role == "model") "model" else "user"
        content(role = validRole) {
          text(turn.text)
        }
      }

    val chat = chatModel.startChat(history = formattedHistory)
    val accumulatedText = StringBuilder()
    val accumulatedQueries = linkedSetOf<String>()
    val accumulatedSources = linkedMapOf<String, GroundedWebSource>()

    try {
      withTimeout(90_000L) {
        chat.sendMessageStream(userMessage).collect { chunk ->
          val delta = chunk.text.orEmpty()
          accumulatedText.append(delta)
          val (chunkQueries, chunkSources) = extractGroundingMetadata(chunk)
          accumulatedQueries.addAll(chunkQueries)
          chunkSources.forEach { src ->
            if (src.url.isNotBlank()) {
              accumulatedSources[src.url] = src
            }
          }
          emit(
            MultiTurnStreamChunk(
              partialText = accumulatedText.toString(),
              searchQueries = accumulatedQueries.toList(),
              sources = accumulatedSources.values.toList()
            )
          )
        }
      }
    } catch (streamErr: Exception) {
      if (streamErr is CancellationException && streamErr !is TimeoutCancellationException) {
        throw streamErr
      }
      if (accumulatedText.isNotBlank()) {
        // Keep partial response if streaming broke late
        return@flow
      }
      Log.w(TAG, "Chat stream failed; retrying with unary sendMessage", streamErr)
      val unaryChat = chatModel.startChat(history = formattedHistory)
      val response = withTimeout(60_000L) {
        unaryChat.sendMessage(userMessage)
      }
      val (queries, sources) = extractGroundingMetadata(response)
      val replyText = response.text?.trim().orEmpty()
      if (replyText.isBlank()) {
        throw streamErr
      }
      emit(
        MultiTurnStreamChunk(
          partialText = replyText,
          searchQueries = queries,
          sources = sources
        )
      )
    }
  }.flowOn(Dispatchers.IO)

  private fun extractGroundingMetadata(
    response: GenerateContentResponse
  ): Pair<List<String>, List<GroundedWebSource>> {
    val queries = mutableListOf<String>()
    val sources = mutableListOf<GroundedWebSource>()
    runCatching {
      val candidate = response.candidates.firstOrNull()
      val metadata = candidate?.groundingMetadata
      metadata?.webSearchQueries?.forEach { q ->
        if (q.isNotBlank()) queries.add(q)
      }
      metadata?.groundingChunks?.forEach { chunk ->
        val web = chunk.web
        val uri = web?.uri.orEmpty()
        val title = web?.title.orEmpty().ifBlank { uri }
        if (uri.isNotBlank()) {
          sources.add(GroundedWebSource(title = title, url = uri))
        }
      }
    }
    return queries.distinct() to sources.distinctBy { it.url }
  }

  private fun buildSystemInstructionForPersona(
    persona: ChatbotRolePersona,
    review: PaperReview?,
    enableSearchGrounding: Boolean
  ): String {
    val personaDirective = when (persona) {
      ChatbotRolePersona.BLUE_TEAM_COACH -> """
        You are the Stage 02c Blue Team Advocate & Stage 08a Alchemist Rebuttal Coach from the 'isitcredible/reviewer2' and 'phd-skills' engine.
        Your mission is to help the author defend their manuscript, craft persuasive point-by-point rebuttal letters, rewrite weak claims or LaTeX equations, and design minimum-viable ablation/robustness experiments.
      """.trimIndent()
      ChatbotRolePersona.REVIEWER_2_HOSTILE -> """
        You are Stage 01 Adversarial 'Reviewer 2' (The Breaker 01a, The Butcher 01b, The Shredder 01c, The Void 01g) from 'isitcredible/reviewer2'.
        Adopt an uncompromising, rigorous referee posture: probe causal identification assumptions, construct validity, train/val/test split leakage, and mathematical regularity conditions. Challenge weak defenses and test whether the author's rebuttal holds water.
      """.trimIndent()
      ChatbotRolePersona.SENIOR_AREA_CHAIR -> """
        You are the Senior Area Chair & Co-Editor (Stage 02e Assessment, Stage 04a The Reviewer, Stage 08b Decision Memo).
        Adjudicate fairly between Red Team criticisms and Blue Team defenses, explain how specific changes impact the 6-axis rubric (Methodology, Clarity, Significance, Originality, Math Rigor, Reproducibility), and advise on what is required to move a verdict from Major Revision to Accept.
      """.trimIndent()
      ChatbotRolePersona.SEARCH_FACT_CHECKER -> """
        You are the Stage 03b External Literature & Citation Fact-Checker equipped with live Google Search Grounding.
        Verify prior art, benchmark SOTA numbers, dataset details, and citation accuracy against live web search results, and clearly cite the grounded literature findings.
      """.trimIndent()
    }

    val manuscriptContext = if (review != null) {
      """
      ACTIVE AUDITED MANUSCRIPT DOSSIER CONTEXT:
      - Title: ${review.title}
      - Authors: ${review.authors} (${review.publicationYear.ifBlank { "Year not verified" }}) • Venue: ${review.venueOrCitation}
      - Verdict: ${review.verdict} (Credibility Score: ${review.credibilityScore}/100)
      - Abstract & Setup: ${review.abstractSummary}
      - Research Question: ${review.researchQuestion}
      - Key Methodology: ${review.keyMethodology}
      - Claimed Contributions (00c): ${review.coreContributions}
      - Red Team Critique (01a–01g): ${review.redTeamCritique.take(2500)}
      - Blue Team & Verified List v2 (02a–03c): ${review.blueTeamVerification.take(2500)}
      - Math Audit (01e–01fd): ${review.mathAuditReport.take(1500)}
      - Code & Leakage X-Ray (01i–01n): ${review.codeAuditReport.take(1500)}
      """.trimIndent()
    } else {
      "No completed review dossier is selected yet; assist the researcher with manuscript preparation, peer-review strategy, methodology, proof checking, or literature verification."
    }

    val searchNote = if (enableSearchGrounding || persona == ChatbotRolePersona.SEARCH_FACT_CHECKER) {
      "Google Search Grounding is ENABLED for this conversation. Use Google Search whenever verifying citations, prior art, benchmarks, or external facts."
    } else {
      "Respond using the manuscript dossier context and your domain expertise."
    }

    return "$personaDirective\n\n$searchNote\n\n$manuscriptContext"
  }

  private fun buildPass1RedTeamPrompt(
    title: String,
    authors: String,
    year: String,
    venueOrCitation: String,
    manuscriptText: String,
    forensicContext: String,
    reviewStrictness: String,
    attachedPageCount: Int,
    options: PipelineOptions
  ): String {
    val trackDescription = if (options.isEmpirical) {
      "EMPIRICAL MANUSCRIPT — Execute Stage 01a (Conceptual Foundations), Stage 01b (Design, Identification & Construct Validity), Stage 01c (Procedural & Sample/Protocol Audit), Stage 01d (Tables, Figures & Cross-References), and Stage 01g (Unmeasured Confounds & External Validity)."
    } else {
      "THEORETICAL / FORMAL MANUSCRIPT — Execute Stage 01a (Foundations), Stage 01a_2 (Formal Deep-Dive), Stage 01c (Proof/Definition Consistency), Stage 01d (Cross-References), and Stage 01g (Boundary Conditions)."
    }

    val modeDirective = when (options.reviewMode) {
      ReviewMode.POST_PUBLICATION_APPRAISAL ->
        "APPRAISAL MODE: POST-PUBLICATION CRITICAL APPRAISAL. This work may already be published; evaluate methodological rigor, factual/numerical accuracy (including potential errata), strength of evidence, and practical applicability rather than framing feedback as a journal rejection letter."
      ReviewMode.CLINICAL_EVIDENCE_APPRAISAL ->
        "APPRAISAL MODE: CLINICAL & POLICY EVIDENCE APPRAISAL. Prioritize clinical/policy utility, patient/population representativeness, harms vs. benefits, resource-constrained/LMIC transferability, and GRADE certainty of evidence."
      ReviewMode.JOURNAL_REFEREE ->
        "APPRAISAL MODE: JOURNAL PEER-REVIEW REFEREE. Evaluate submission readiness, methodological validity, and concrete revision requirements for publication."
    }

    val strictnessDirective = when (reviewStrictness) {
      "CONSTRUCTIVE_MENTOR" ->
        "Maintain high analytical rigor while framing each critique clearly and proportionally so researchers can verify and address it."
      "AGGRESSIVE_REVIEWER2" ->
        "Adopt an exacting, skeptical Reviewer 2 posture: interrogate every assumption, table, figure, and calculation—while strictly adhering to verified evidence from the manuscript text (never fabricate omissions)."
      else ->
        "Operate at Senior Statistical Editor & Area Chair rigor: strictly evidence-grounded, uncompromising on verified arithmetic/methodological flaws, and proportionate on minor reporting items."
    }

    return """
      You are PASS 1 (Stage 00 Ingestion + Stage 01 Candidate Critique) of Paper Critic, a publication-grade critical appraisal engine.
      $modeDirective
      $strictnessDirective
      ${if (attachedPageCount > 0) "IMPORTANT: $attachedPageCount rendered PDF page images are attached. Visually inspect titles, authors, equations, tables, figures, and footnotes directly alongside the [PAGE N]-anchored text." else ""}

      MANUSCRIPT METADATA HINTS (VERIFY & OVERRIDE FROM PAPER IF BLANK OR RAW FILENAME):
      - Title Hint: ${title.ifBlank { "Extract exact title from manuscript" }}
      - Authors Hint: ${authors.ifBlank { "Extract exact author names from manuscript" }}
      - Year Hint: ${year.ifBlank { "Extract publication year from manuscript" }}
      - Target Venue: ${venueOrCitation.ifBlank { options.targetVenue }}
      - Track: $trackDescription

      $forensicContext

      CRITICAL EVIDENCE-GROUNDING RULES (NON-NEGOTIABLE):
      1. NEVER CLAIM AN ABSENCE WITHOUT CHECKING THE FULL TEXT: Before claiming that random-effects pooling, I², meta-regression, quality appraisal, or independent extraction is "missing", verify across ALL [PAGE N] sections, tables, and figures. If the paper used a quality-scoring scheme rather than a validated RoB tool (e.g., RoB 2/NOS/JBI), critique the *choice of instrument* accurately—do NOT falsely claim quality appraisal was absent.
      2. DISTINGUISH REPORTING GUIDELINES vs. RISK-OF-BIAS TOOLS vs. CERTAINTY FRAMEWORKS (e.g., PRISMA is a reporting guideline, NOT a bias tool; GRADE assesses certainty of evidence).
      3. CITE EXACT [PAGE N] ANCHORS AND VERBATIM NUMBERS whenever flagging discrepancies (e.g., text-vs-figure confidence interval mismatches).
      4. CLEAN TYPOGRAPHY: NEVER wrap plain abbreviations or units (IQR, CI, SD, OR, RR, HIC, LMIC, PRISMA, PROSPERO) in LaTeX $ dollar signs. Use crisp Unicode symbols (≤, ≥, ±, ×, κ, I², τ², β) in prose.
      5. STRUCTURED NEWLINE BULLETS: Put EVERY contribution ([C1], [C2], [C3]) and EVERY finding (• [RT-1] ..., • [RT-2] ...) on its OWN NEWLINE (\n).

      EXECUTE STAGES 00a–01g WITH SURGICAL PRECISION:
      1. STAGE 00a–00b (Canonical Metadata, Executive Summary, Research Question & Methodology):
         - Extract `extractedTitle` (proper Title Case), `extractedAuthors`, `extractedYear`, and `studyDesignType`.
         - Write a concise, exact executive summary, primary research question, and bulleted methodology.
      2. STAGE 00c (Claimed Contributions):
         - Enumerate concrete claimed contributions on separate lines: [C1] ..., [C2] ..., [C3] ...
      3. STAGE 01a — CONCEPTUAL & THEORETICAL FOUNDATIONS: 2–3 newline bullets (`• [RT-1] ...`).
      4. STAGE 01b — DESIGN, IDENTIFICATION / SYNTHESIS & CONSTRUCT VALIDITY: 2–3 newline bullets (`• [RT-2] ...`).
      5. STAGE 01c — PROCEDURAL, SAMPLE & PROTOCOL RIGOR: 2–3 newline bullets (`• [RT-3] ...`).
      6. STAGE 01d — TABLES, FIGURES, FOOTNOTES & TEXT-VS-FIGURE CONSISTENCY: 2–3 newline bullets (`• [RT-4] ...`).
      7. STAGE 01g — OMISSIONS, CONFOUNDS & CLINICAL/POLICY EXTERNAL VALIDITY: 2–3 newline bullets (`• [RT-5] ...`).

      MANUSCRIPT TEXT (WITH [PAGE N] COORDINATES):
      $manuscriptText
    """.trimIndent()
  }

  private fun buildPass2VerificationPrompt(
    title: String,
    authors: String,
    year: String,
    venueOrCitation: String,
    studyDesignLabel: String,
    manuscriptText: String,
    replicationCodeText: String = "",
    forensicContext: String,
    coreContributions: String,
    compiledRedTeamReport: String,
    reviewStrictness: String,
    options: PipelineOptions
  ): String {
    val verdictScaleGuidance = when (options.reviewMode) {
      ReviewMode.POST_PUBLICATION_APPRAISAL ->
        "ReviewMode = POST_PUBLICATION_APPRAISAL. Choose `verdict` ONLY from: HIGH_CREDIBILITY (≥85), SOUND_WITH_CAVEATS (70–84), REQUIRES_CORRECTION (55–69 or verified factual/numerical erratum), MAJOR_CONCERNS (40–54), LOW_CREDIBILITY (<40)."
      ReviewMode.CLINICAL_EVIDENCE_APPRAISAL ->
        "ReviewMode = CLINICAL_EVIDENCE_APPRAISAL. Choose `verdict` ONLY from: HIGH_CERTAINTY (≥85), MODERATE_CERTAINTY (68–84), LOW_CERTAINTY (48–67), VERY_LOW_CERTAINTY (<48)."
      ReviewMode.JOURNAL_REFEREE ->
        "ReviewMode = JOURNAL_REFEREE. Choose `verdict` ONLY from: ACCEPT (≥85), MINOR_REVISION (72–84), MAJOR_REVISION (55–71), REJECT_AND_RESUBMIT (40–54), REJECT (<40)."
    }

    return """
      You are PASS 2 (Source-Grounded Verification, Cross-Pass Adjudication & Senior Synthesis) of Paper Critic.

      MANUSCRIPT:
      - Title: ${title.ifBlank { "Untitled Academic Manuscript" }}
      - Authors: ${authors.ifBlank { "Authors Not Disclosed" }} ($year)
      - Study Design: $studyDesignLabel
      - Venue: ${venueOrCitation.ifBlank { options.targetVenue }}
      - Active Appraisal Mode: ${options.reviewMode.displayTitle}
      - Strictness Calibration: $reviewStrictness

      $forensicContext

      STAGE 00c CLAIMED CONTRIBUTIONS:
      $coreContributions

      STAGE 01 CANDIDATE CRITIQUE FINDINGS (FROM PASS 1 — VERIFY OR DISMISS AGAINST SOURCE TEXT):
      $compiledRedTeamReport

      MANDATORY VERIFICATION, EVIDENCE-LEDGER & SCORING CONTRACT:
      1. EXACT WEIGHTED RUBRIC RECONCILIATION:
         - Weighted Composite = round(2.5×Methodology + 2.0×Significance + 1.5×Clarity + 1.5×Originality + 1.5×MathRigor + 1.0×Reproducibility).
         - Set `credibilityScore` EXACTLY equal to this weighted composite.
         - $verdictScaleGuidance
         - In `rubricRationaleSummary`, provide 6 complete, concise 1–2 sentence rationales prefixed strictly with:
           [RUBRIC:Methodology] ...
           [RUBRIC:Significance] ...
           [RUBRIC:Clarity] ...
           [RUBRIC:Originality] ...
           [RUBRIC:Math] ...
           [RUBRIC:Reproducibility] ...
      2. STRUCTURED EVIDENCE LEDGER (`structuredFindings` array — 4 to 8 highest-priority findings):
         - Give each finding a clean code (`M-01`, `Q-01`, `R-01`, `C-01`), exact `pageNumber` from `[PAGE N]`, `section`, and a SHORT VERBATIM `supportingQuote` copied DIRECTLY from the manuscript text below.
         - NEVER mark a finding `VERIFIED` if you cannot copy a real verbatim quote from the manuscript or if it falsely claims something is absent when the text actually reports it. Use `CONTRADICTED` if Pass 1 made a false absence claim, and quote the paper's actual passage in `counterEvidenceQuote`!
         - Include any verified arithmetic/CI discrepancy from the deterministic pre-flight forensics (e.g., an impossible 95% CI in prose that conflicts with a Figure/Table) as a high-priority `Q-01` finding!
      3. DOMAIN-EXACT & CLINICAL/POLICY APPLICABILITY AUDITING:
         - Strictly apply $studyDesignLabel standards. Distinguish reporting guidelines (PRISMA/CONSORT/STROBE) from risk-of-bias tools (RoB 2/NOS/JBI/AMSTAR-2) and certainty frameworks (GRADE).
         - In `stage01iTo01nCodeAndDataAudit`, include both reproducibility/provenance AND clinical/policy applicability (population representativeness, HIC vs LMIC transferability, real-world decision utility).
      4. CLEAN TYPOGRAPHY & NEWLINE STRUCTURE:
         - NEVER wrap plain acronyms/units (IQR, CI, SD, OR, RR, HIC, LMIC, PRISMA) in $ dollar signs. Use Unicode symbols (≤, ≥, ±, ×, κ, I², τ², β).
         - `stage02eTo03cVerifiedListV2`: Put EACH surviving verified issue on its OWN NEWLINE prefixed with `[CRITICAL]`, `[MAJOR]`, or `[MINOR]` and EACH rejected Pass 1 false positive prefixed with `[DISMISSED]`.
         - `stage04aIsItCredibleEssay`: Three newline-separated blocks prefixed exactly with `Executive Assessment:`, `The Bottom Line:`, and `Future Research Agenda:`. Critique strictly the scientific work, never impute intent or misconduct.
         - `stage08aTo08bAlchemistEditorNote`: Start with `Editorial Decision Rationale:` followed by prioritized actions prefixed with `[MUST FIX]`, `[SHOULD FIX]`, or `[RECOMMENDED]`.
         - `fortifyReviewerQuestions`: 4–5 sharp questions numbered `1. `..`5. ` each on its own line, followed by `Recommended Sensitivity & Robustness Checks:` with 2–3 bullets.
         - `stage09aTo09cCopyeditReport`: Two sections prefixed with `Extraction / Typesetting Artifacts:` and `Substantive Prose Improvements:`.

      ORIGINAL MANUSCRIPT TEXT FOR VERBATIM SOURCE VERIFICATION (STAGE 03a):
      $manuscriptText
      ${if (replicationCodeText.isNotBlank()) "\nATTACHED REPLICATION CODE PACKAGE:\n$replicationCodeText" else ""}
    """.trimIndent()
  }

  private fun buildPass2QuantAuditPrompt(
    title: String,
    studyDesignLabel: String,
    studyDesignArchetype: StudyDesignArchetype,
    manuscriptText: String,
    replicationCodeText: String,
    forensicReport: ForensicPreFlightReport,
    redTeamExcerpt: String,
    reviewStrictness: String
  ): String {
    val domainQuantitativeLens = when (studyDesignArchetype) {
      StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS -> """
        DOMAIN QUANTITATIVE LENS (Biostatistics & Evidence Synthesis):
        - Audit pooling estimator choice: Random-Effects (REML / DerSimonian-Laird / Paule-Mandel) with Hartung-Knapp-Sidik-Jonkman (HKSJ) CI adjustment vs. Fixed-Effect vs. unweighted Median/IQR synthesis.
        - Audit between-study heterogeneity quantification (I², τ², Cochran's Q) and whether 95% Prediction Intervals are reported when I² ≥ 50%.
        - Audit scale/variance-stabilizing transformations (Freeman-Tukey double arcsine / logit for proportions; log-transform for skewed rates/ratios such as beds per 100,000 population).
        - Audit small-study & publication bias diagnostics (funnel asymmetry, Egger's/Peters' test — noting low power if k < 10 — trim-and-fill, selection models) and overlapping-cohort double-counting.
        - Audit risk-of-bias weighting, meta-regression degrees of freedom (rule of ≥10 studies per covariate), and inter-rater screening reliability (Cohen's κ / ICC).
      """.trimIndent()

      StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL -> """
        DOMAIN QUANTITATIVE LENS (Biostatistics & Clinical Epidemiology):
        - Audit estimand & regression formulation (Cox PH proportional-hazards testing via Schoenfeld residuals, logistic/Poisson/negative-binomial overdispersion, mixed-effects GLMM random intercepts/slopes).
        - Audit confounding & causal DAGs (collider bias, immortal-time bias, table-2 fallacy, competing risks via Fine-Gray vs Kaplan-Meier censoring).
        - Audit sample size / statistical power / MDE, missing data mechanism (MCAR/MAR/MNAR, multiple imputation MICE vs complete-case bias), and exact CI geometry (log-symmetry for OR/RR/HR).
        - Audit clinical effect magnitude (ARR, NNT, SMD) vs pure p-value significance and FWER/FDR multiplicity across primary/secondary endpoints.
      """.trimIndent()

      StudyDesignArchetype.ECONOMETRICS_CAUSAL -> """
        DOMAIN QUANTITATIVE LENS (Econometrics & Causal Inference):
        - Audit identification assumptions & estimand (ATT vs LATE vs ATE, staggered DiD heterogeneous treatment-effect bias under TWFE -> Callaway-Sant'Anna / Sun-Abraham, weak IV first-stage F / Lee et al. tF adjustment, RDD bandwidth/McCrary density test).
        - Audit standard error covariance structure (heteroskedasticity, autocorrelation HAC/Newey-West, spatial/panel clustering; small cluster count G < 42 requiring wild cluster bootstrap / Bell-McCaffrey).
        - Audit financial/time-series hazards (look-ahead bias in rolling quantiles/z-scores, survivorship bias, multiple testing across trading horizons/signals via Romano-Wolf / Harvey-Liu-Zhu t > 3.0).
      """.trimIndent()

      StudyDesignArchetype.MACHINE_LEARNING_AI -> """
        DOMAIN QUANTITATIVE LENS (Statistical Learning & Empirical Evaluation):
        - Audit objective/loss formulation, regularization hyperparameters, optimization uniqueness/convergence, and dimensional tensor/shape consistency.
        - Audit statistical significance of benchmark deltas (paired McNemar, bootstrap 95% CI, Diebold-Mariano, multi-seed variance vs single-seed point differences).
        - Audit calibration & threshold metrics (ECE, Brier score, PR-AUC under class imbalance vs ROC-AUC) and entity/temporal/hyperparameter leakage across train/val/test splits.
      """.trimIndent()

      StudyDesignArchetype.THEORETICAL_FORMAL -> """
        DOMAIN QUANTITATIVE LENS (Formal Mathematics & Proof Verification):
        - Audit axioms, regularity/convexity/measurability/integrability assumptions, and boundary/edge-case behavior.
        - Re-derive key lemmas/theorems step by step, checking quantifier order (∀ vs ∃), sign flips, convergence modes (a.s., in probability, L²), and index/subscript consistency.
        - Verify non-triviality, tightness of bounds, and counterexample susceptibility when assumptions are relaxed.
      """.trimIndent()

      StudyDesignArchetype.GENERAL_EMPIRICAL -> """
        DOMAIN QUANTITATIVE LENS (Applied Statistics & Quantitative Methodology):
        - Audit estimator assumptions, measurement reliability, distributional skewness, variance estimation, power/MDE, multiplicity control (FWER/FDR), and internal numerical consistency.
      """.trimIndent()
    }

    return """
      You are the SPECIALIST QUANTITATIVE, STATISTICAL & MATHEMATICAL AUDITOR (Stages 01e Error Finder, 01fa Re-Deriver, 01fb Math Proofreader, 01fc Auditor, 01fd Sober Checker, and 02a Exact Numbers Verifier) of Paper Critic.
      Produce an ULTRA-THOROUGH, DEEP, PRECISE, AND SOPHISTICATED biostatistical / econometric / mathematical audit suitable for a senior statistical editor at The Lancet, Nature, NEJM, Econometrica, or JASA.

      MANUSCRIPT:
      - Title: $title
      - Detected Study Design: $studyDesignLabel (${studyDesignArchetype.displayName})
      - Discipline: ${forensicReport.detectedDiscipline}
      - Strictness Calibration: $reviewStrictness

      $domainQuantitativeLens

      PRE-FLIGHT DETERMINISTIC MATHEMATICAL & STATISTICAL FORENSICS (GROUND TRUTH INPUTS):
      ${forensicReport.toStructuredPromptContext()}

      RED TEAM CANDIDATE CONCERNS TO STRESS-TEST QUANTITATIVELY:
      $redTeamExcerpt

      STRICT PRECISION, DEPTH & FORMATTING CONTRACT:
      1. ZERO GENERIC FILLER: Anchor every critique to EXACT numbers, sample sizes, equations, tables, dispersion metrics (IQRs, SDs, CIs, I², t/F/χ²/p), or methodological steps from the manuscript.
      2. CLEAN UNICODE TYPOGRAPHY: NEVER wrap plain abbreviations (IQR, CI, SD, SE, OR, RR, HR, HIC, LMIC, REML, HKSJ, FWER, FDR) in $ dollar signs. Use clean Unicode math symbols (≤, ≥, ≠, ≈, ±, ×, α, β, γ, λ, σ, τ², κ, χ², I², R², Σ). Put standalone equations on their OWN NEWLINE.
      3. PILLAR 6 (`quantAuditMatrixRows`): Output a clean 4-column markdown pipe table with 1 header row + 6 to 8 substantive diagnostic rows covering Estimator Validity, Dispersion/CI Geometry, Numeric/Statcheck Consistency, Heterogeneity/Confounding, Power/Multiplicity, and Formal/Unit Rigor:
         | Audit Dimension | Manuscript Metric / Evidence | Status | Quantitative Impact & Diagnosis |
         |---|---|---|---|
         | ... | ... | PASS / WARN / FAIL | ... |
         Status column MUST be strictly one word: PASS, WARN, or FAIL.
      4. PILLAR 7 (`sensitivityPrescriptions`): Put 3–5 concrete, technically exact quantitative remedies each on its OWN NEWLINE prefixed with `[MUST FIX]`, `[SHOULD FIX]`, or `[RECOMMENDED]`, naming the exact statistical estimator/test/formula and expected diagnostic output.
      5. PILLAR 8 (`soberVerificationSummary`): Filter out trivial/pedantic math nitpicks and end with a new line starting exactly with `Quantitative Bottom Line: `.

      MANUSCRIPT TEXT:
      $manuscriptText
      ${if (replicationCodeText.isNotBlank()) "\nATTACHED REPLICATION CODE:\n$replicationCodeText" else ""}
    """.trimIndent()
  }

  private fun compileUltraDeepQuantitativeAudit(
    forensicReport: ForensicPreFlightReport,
    quantJson: JSONObject?,
    fallbackMathAudit: String,
    fallbackNumbersCheck: String,
    calibratedQuantScore: Int,
    scoreBreakdown: ScoreCalculationBreakdown? = null,
    epistemicOverride: EpistemicSafeguardReport? = null
  ): String = buildString {
    val clean = { s: String -> com.example.util.DossierFormattingEngine.cleanAcademicProse(s) }

    // 1. Deterministic Pre-Flight Quantitative Forensics Table & Design-Aware Audit Trail
    // Always sync with the single authoritative epistemic report (matching extractionConfidence).
    val syncedQuantForensics = if (epistemicOverride != null) {
      forensicReport.quantForensics.copy(epistemicSafeguards = epistemicOverride)
    } else {
      forensicReport.quantForensics
    }
    val deterministicSummary = syncedQuantForensics.toDeterministicAuditSummary()
    if (deterministicSummary.isNotBlank()) {
      appendLine(deterministicSummary)
      appendLine()
    }

    // 1b. Transparent Evidence-Derived Score Calculation Breakdown
    if (scoreBreakdown != null) {
      appendLine(scoreBreakdown.toMarkdownSummary())
      appendLine()
    }

    // 2. Extracted Symbolic Equations Inventory (if equations present)
    if (forensicReport.extractedEquations.isNotEmpty()) {
      appendLine("[PRE-FLIGHT SYMBOLIC INVENTORY — ${forensicReport.extractedEquations.size} Equations Extracted]")
      forensicReport.extractedEquations.take(6).forEachIndexed { i, eq ->
        appendLine("[EQ${i + 1}] ${clean(eq)}")
      }
      appendLine()
    }

    if (quantJson == null) {
      // Graceful fallback to Pass 2B math & numbers summary
      appendLine("[STAGE 01e–01fd — QUANTITATIVE, STATISTICAL & EQUATION AUDIT (Score: $calibratedQuantScore/10)]")
      appendLine(fallbackMathAudit.ifBlank { "Quantitative audit completed; see adjudicated findings." })
      if (fallbackNumbersCheck.isNotBlank()) {
        appendLine()
        appendLine("[STAGE 02a — NUMERICAL & STATISTICAL CONSISTENCY CHECK]")
        appendLine(fallbackNumbersCheck)
      }
      return@buildString
    }

    // 3. Dedicated 8-Pillar Specialist Quantitative Audit
    val matrixRows = clean(quantJson.optString("quantAuditMatrixRows", ""))
    if (matrixRows.isNotBlank()) {
      appendLine("[STAGE 01e–02a — QUANTITATIVE DIAGNOSTIC SCORECARD MATRIX (Quant Rigor: $calibratedQuantScore/10)]")
      appendLine(matrixRows)
      appendLine()
    }

    val estimatorAudit = clean(quantJson.optString("estimatorAndModelAudit", ""))
    if (estimatorAudit.isNotBlank()) {
      appendLine("[PILLAR 1 — ESTIMATOR, DISTRIBUTIONAL & MODEL SPECIFICATION AUDIT]")
      appendLine(estimatorAudit)
      appendLine()
    }

    val numericAudit = clean(quantJson.optString("numericAndStatcheckAudit", ""))
    if (numericAudit.isNotBlank()) {
      appendLine("[PILLAR 2 — TEST STATISTICS, CONFIDENCE INTERVALS & NUMERICAL FORENSICS]")
      appendLine(numericAudit)
      appendLine()
    }

    val powerAudit = clean(quantJson.optString("powerAndMultiplicityAudit", ""))
    if (powerAudit.isNotBlank()) {
      appendLine("[PILLAR 3 — STATISTICAL POWER, PRECISION, DEGREES OF FREEDOM & MULTIPLICITY]")
      appendLine(powerAudit)
      appendLine()
    }

    val biasAudit = clean(quantJson.optString("biasHeterogeneityAudit", ""))
    if (biasAudit.isNotBlank()) {
      appendLine("[PILLAR 4 — BIAS, CONFOUNDING, HETEROGENEITY & IDENTIFICATION DIAGNOSTICS]")
      appendLine(biasAudit)
      appendLine()
    }

    val mathRederivation = clean(quantJson.optString("formalMathRederivation", ""))
    if (mathRederivation.isNotBlank()) {
      appendLine("[PILLAR 5 — FORMAL MATHEMATICAL RE-DERIVATION, UNITS & REGULARITY CONDITIONS]")
      appendLine(mathRederivation)
      appendLine()
    }

    val prescriptions = clean(quantJson.optString("sensitivityPrescriptions", ""))
    if (prescriptions.isNotBlank()) {
      appendLine("[PILLAR 6 — EXECUTABLE SENSITIVITY & ROBUSTNESS PRESCRIPTION SUITE]")
      appendLine(prescriptions)
      appendLine()
    }

    val soberSummary = clean(quantJson.optString("soberVerificationSummary", ""))
    if (soberSummary.isNotBlank()) {
      appendLine("[STAGE 01fd — SOBER QUANTITATIVE VERIFICATION & BOTTOM LINE]")
      appendLine(soberSummary)
    }
  }.trim()

  internal data class PromptPreparedManuscript(
    val text: String,
    val wasTrimmed: Boolean,
    val trimmedNote: String,
    val omittedPages: List<Int> = emptyList(),
    val partiallyRetainedPages: List<Int> = emptyList()
  )

  internal enum class QuoteMatchKind {
    EXACT,
    FUZZY_LOCALIZED,
    NONE
  }

  internal data class QuoteVerificationResult(
    val matchKind: QuoteMatchKind,
    val resolvedPage: Int,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
    val occurrenceCount: Int = 0,
    val pageMismatchWarning: String? = null
  )

  internal data class AdjudicationResult(
    val adjudicatedFindings: List<ReviewFinding>,
    val methodologyScore: Int,
    val significanceScore: Int,
    val clarityScore: Int,
    val originalityScore: Int,
    val mathRigorScore: Int,
    val reproducibilityScore: Int,
    val weightedCredibilityScore: Int,
    val resolvedVerdict: String,
    val scoreBreakdown: ScoreCalculationBreakdown? = null,
    val ledgerCapOmittedCount: Int = 0,
    val ledgerCapCategories: List<String> = emptyList()
  )

  companion object {
    private val PAGE_BLOCK_HEADER_REGEX = Regex("""(?m)^\[PAGE\s+(\d+)\]\s*$""")

    /**
     * Routes rendered PDF page bitmaps to Pass 1 / Pass 2 using page-number metadata so
     * high-value Figures, Tables, and pages flagged by deterministic CI/Statcheck alerts
     * reach Pass 2 adjudication (rather than blindly truncating to `take(2)`).
     */
    internal fun selectVisionPagesForPass(
      allBitmaps: List<Bitmap>,
      visionPageNumbers: List<Int>,
      forensicReport: ForensicPreFlightReport?,
      manuscriptText: String,
      maxBitmaps: Int,
      prioritizeDiscrepancyAndFigures: Boolean
    ): Pair<List<Bitmap>, List<Int>> {
      if (allBitmaps.isEmpty() || maxBitmaps <= 0) return emptyList<Bitmap>() to emptyList()
      val indexed = allBitmaps.mapIndexedNotNull { idx, bmp ->
        if (bmp.isRecycled) null else Triple(idx, visionPageNumbers.getOrNull(idx) ?: (idx + 1), bmp)
      }
      if (indexed.size <= maxBitmaps) {
        return indexed.map { it.third } to indexed.map { it.second }
      }
      if (!prioritizeDiscrepancyAndFigures) {
        val chosen = indexed.take(maxBitmaps)
        return chosen.map { it.third } to chosen.map { it.second }
      }

      val flaggedPages = buildSet {
        forensicReport?.quantForensics?.confidenceIntervalChecks
          ?.filter { it.status == QuantFindingStatus.FAIL }
          ?.forEach { ci ->
            if (ci.pageNumber > 0) add(ci.pageNumber)
            Regex("""Page\s+(\d+)""", RegexOption.IGNORE_CASE).findAll(ci.crossReferenceNote)
              .forEach { m -> m.groupValues[1].toIntOrNull()?.let { add(it) } }
          }
        forensicReport?.quantForensics?.statcheckResults
          ?.filter { it.status == QuantFindingStatus.FAIL && it.pageNumber > 0 }
          ?.forEach { add(it.pageNumber) }
      }
      val figurePageSet = extractPageBlocks(manuscriptText)
        .filter { (_, body) ->
          Regex("""\b(?:figure|fig\.|forest\s+plot|funnel\s+plot|table\s*\d+)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
        }
        .map { it.first }
        .toSet()

      val ranked = indexed.sortedByDescending { (_, pageNum, _) ->
        var score = 0
        if (pageNum in flaggedPages) score += 100
        if (pageNum in figurePageSet) score += 50
        if (pageNum == 1) score += 10
        score
      }.take(maxBitmaps).sortedBy { it.second }

      return ranked.map { it.third } to ranked.map { it.second }
    }

    private fun extractPageBlocks(text: String): List<Pair<Int, String>> {
      val matches = PAGE_BLOCK_HEADER_REGEX.findAll(text).toList()
      if (matches.isEmpty()) return emptyList()
      return matches.mapIndexed { idx, match ->
        val pageNum = match.groupValues[1].toIntOrNull() ?: (idx + 1)
        val bodyStart = (match.range.last + 1).coerceAtMost(text.length)
        val bodyEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else text.length
        pageNum to text.substring(bodyStart, bodyEnd).trim()
      }
    }

    /**
     * Page-aware prompt budget selector:
     * 1. Returns the complete document unchanged when within [maxChars] (`omittedPages = emptyList()`).
     * 2. Trims trailing References/Bibliography sections first when present.
     * 3. If still over budget (or when no References heading exists), preserves high-value pages
     *    across opening/Methods, Results/Figures/Tables, and Discussion/Conclusion while explicitly
     *    disclosing every omitted page number—never falsely claiming full coverage.
     */
    internal fun prepareManuscriptForPrompt(
      raw: String,
      maxChars: Int = 160_000
    ): PromptPreparedManuscript {
      val clean = raw.trim()
      if (clean.length <= maxChars) {
        return PromptPreparedManuscript(clean, wasTrimmed = false, trimmedNote = "", omittedPages = emptyList())
      }

      // 1. Try trimming only the trailing References/Bibliography section first
      val refHeaderRegex = Regex("""(?im)^\s*(?:references|bibliography|works\s+cited)\s*$""")
      val refMatch = refHeaderRegex.findAll(clean).lastOrNull { it.range.first >= (clean.length * 0.35).toInt() }
      if (refMatch != null) {
        val bodyPart = clean.substring(0, refMatch.range.first).trimEnd()
        val refPart = clean.substring(refMatch.range.first)
        if (bodyPart.length <= maxChars - 300) {
          val remainingForRefs = (maxChars - bodyPart.length - 180).coerceAtLeast(0)
          val omittedRefPages = extractPageBlocks(refPart.drop(remainingForRefs)).map { it.first }
          val assembled = buildString {
            append(bodyPart)
            append("\n\n")
            if (remainingForRefs > 400) {
              append(refPart.take(remainingForRefs))
              append("\n[... Trailing bibliography entries omitted (${refPart.length - remainingForRefs} chars); full main body preserved ...]")
            } else {
              append("[... References list omitted (${refPart.length} chars); full main body preserved ...]")
            }
          }
          val note = if (omittedRefPages.isNotEmpty()) {
            "Full main body preserved; trailing bibliography trimmed (omitted reference pages: ${omittedRefPages.joinToString(",") { "P$it" }})."
          } else {
            "Full main body preserved; trailing bibliography trimmed to fit prompt window."
          }
          return PromptPreparedManuscript(
            text = assembled,
            wasTrimmed = true,
            trimmedNote = note,
            omittedPages = emptyList() // Main body pages intact; bibliography-only trim disclosed in trimmedNote
          )
        }
      }

      // 2. Page-aware stratified retention when page markers exist (never silently chop the final page!)
      val pageBlocks = extractPageBlocks(clean)
      if (pageBlocks.size >= 2) {
        val highSignalRegex = Regex(
          """\b(?:results?|methods?|discussion|conclusion|limitations?|supplement(?:ary)?|appendix|table\s*\d+|figure\s*\d+|fig\.\s*\d+|95%\s*ci|meta-?analysis|meta-?regression|p\s*[=<>≤]|i²|i2)\b""",
          RegexOption.IGNORE_CASE
        )
        // Prioritize Page 1, closing page, and high-signal Results/Tables/Figures/Limitations pages
        val scored = pageBlocks.mapIndexed { idx, (pageNum, body) ->
          val signalHits = highSignalRegex.findAll(body).count()
          val priority = when {
            idx == 0 -> 10_000
            idx == pageBlocks.lastIndex -> 5_000 + signalHits * 50
            idx == 1 -> 4_000 + signalHits * 50
            else -> signalHits * 100
          }
          Triple(pageNum, "[PAGE $pageNum]\n$body", priority)
        }

        val budget = (maxChars - 260).coerceAtLeast(320)
        val selectedPageNums = linkedSetOf<Int>()
        var plannedChars = 0
        for ((pageNum, formatted, _) in scored.sortedByDescending { it.third }) {
          val sepCost = if (selectedPageNums.isEmpty()) 0 else 2
          if (plannedChars + sepCost + formatted.length <= budget) {
            selectedPageNums.add(pageNum)
            plannedChars += sepCost + formatted.length
          }
        }

        // Incrementally construct output strictly within [budget] — NEVER call `.take(budget)` post-hoc.
        val bodyBuilder = StringBuilder()
        val fullyRetained = linkedSetOf<Int>()
        val partiallyRetained = mutableListOf<Int>()

        for ((pageNum, body) in pageBlocks) {
          if (pageNum !in selectedPageNums) continue
          val sep = if (bodyBuilder.isEmpty()) "" else "\n\n"
          val block = "[PAGE $pageNum]\n$body"
          if (bodyBuilder.length + sep.length + block.length <= budget) {
            bodyBuilder.append(sep).append(block)
            fullyRetained.add(pageNum)
          }
        }

        // If any high-priority page couldn't fit completely (including when Page 1 itself > budget,
        // or when remaining budget allows a meaningful partial slice of the next highest-priority page),
        // include an explicitly tagged partial slice and record it in partiallyRetainedPages.
        val remainingCandidates = scored
          .filter { it.first !in fullyRetained }
          .sortedByDescending { it.third }
        val topRemaining = remainingCandidates.firstOrNull()
        if (topRemaining != null) {
          val pageNum = topRemaining.first
          val rawBody = pageBlocks.first { it.first == pageNum }.second
          val sep = if (bodyBuilder.isEmpty()) "" else "\n\n"
          val header = "[PAGE $pageNum — PARTIAL]\n"
          val availableForBody = budget - bodyBuilder.length - sep.length - header.length - 64
          if (fullyRetained.isEmpty() || availableForBody >= 180) {
            val sliceLen = availableForBody.coerceAtLeast(120).coerceAtMost(rawBody.length)
            bodyBuilder.append(sep)
              .append(header)
              .append(rawBody.take(sliceLen))
              .append("\n[... Page $pageNum truncated: $sliceLen/${rawBody.length} chars retained ...]")
            partiallyRetained.add(pageNum)
          }
        }

        val omitted = pageBlocks.map { it.first }
          .filter { it !in fullyRetained && it !in partiallyRetained }
          .sorted()
        val partialSorted = partiallyRetained.sorted()
        val parts = buildList {
          if (omitted.isNotEmpty()) add("omitted: ${omitted.joinToString(",") { "P$it" }}")
          if (partialSorted.isNotEmpty()) add("partially retained: ${partialSorted.joinToString(",") { "P$it" }}")
        }
        val disclosureSummary = parts.joinToString("; ")
        val assembled = "${bodyBuilder}\n\n[... Partial prompt coverage ($disclosureSummary) to fit prompt window ...]"
        return PromptPreparedManuscript(
          text = assembled,
          wasTrimmed = true,
          trimmedNote = "Partial prompt coverage ($disclosureSummary; fully retained ${fullyRetained.size}/${pageBlocks.size} pages).",
          omittedPages = omitted,
          partiallyRetainedPages = partialSorted
        )
      }

      // 3. Unpaged fallback: preserve opening + closing rather than dropping Discussion/Conclusion blindly, and disclose exact omitted span
      val headLen = ((maxChars - 240) * 0.72).toInt().coerceAtLeast(200)
      val tailLen = (maxChars - 240 - headLen).coerceAtLeast(100)
      val omittedCharCount = (clean.length - headLen - tailLen).coerceAtLeast(0)
      val assembled = clean.take(headLen) +
        "\n\n[... Unpaged document exceeded prompt budget: $omittedCharCount middle characters omitted between opening and closing sections ...]\n\n" +
        clean.takeLast(tailLen)
      return PromptPreparedManuscript(
        text = assembled,
        wasTrimmed = true,
        trimmedNote = "Partial coverage: %,d unpaged characters omitted to fit %,d-char prompt window.".format(Locale.US, omittedCharCount, maxChars),
        omittedPages = listOf(-1)
      )
    }

    internal fun computeCalibratedExtractionConfidence(
      manuscriptText: String,
      coverageReport: DocumentCoverageReport?,
      omittedPromptPages: List<Int>,
      wasPromptTrimmed: Boolean,
      adjudicatedFindings: List<ReviewFinding>,
      statisticalAudit: StatisticalAuditReport? = null
    ): Int {
      var score = if (coverageReport != null && !coverageReport.isCoverageUnknown) {
        var c = coverageReport.extractionConfidence.coerceIn(1, 5)
        if (coverageReport.omittedPages.isNotEmpty()) c = minOf(c, 3)
        if (coverageReport.warnings.any { it.contains("scanned", ignoreCase = true) || it.contains("fallback", ignoreCase = true) }) {
          c = minOf(c, 2)
        }
        c
      } else {
        // Direct text input without PDF parser provenance: inspect structural quality & integrity
        val clean = manuscriptText.trim()
        if (clean.length < 200) {
          1
        } else {
          val replacementChars = clean.count { it == '\uFFFD' }
          val controlGarbage = clean.count { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }
          val alphaNumCount = clean.count { it.isLetterOrDigit() }
          val alphaRatio = alphaNumCount.toDouble() / clean.length.coerceAtLeast(1).toDouble()
          val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
          val hasPageAnchors = PAGE_BLOCK_HEADER_REGEX.containsMatchIn(clean)
          val sentenceCount = clean.count { it == '.' || it == '?' || it == '!' }

          when {
            replacementChars > 5 || controlGarbage > 10 || alphaRatio < 0.55 || words.size < 40 -> 1
            alphaRatio < 0.68 || sentenceCount < 4 -> 2
            words.size < 250 -> 3
            hasPageAnchors && words.size >= 500 -> 5
            else -> 4 // Unanchored direct text without PDF page provenance caps at 4/5
          }
        }
      }

      if (omittedPromptPages.isNotEmpty()) {
        score = minOf(score, 3)
      } else if (wasPromptTrimmed && score == 5) {
        score = 4
      }

      // Unsupported statistical inputs (missing raw tables/SEs/figure-only numbers) lower extraction confidence, NOT scientific validity
      if (statisticalAudit != null && statisticalAudit.unsupportedDisclosures.isNotEmpty()) {
        val adj = if (statisticalAudit.unsupportedDisclosures.size >= 3) -2 else -1
        score = (score + adj).coerceAtLeast(1)
      }

      // If more than half of model-generated findings failed quote verification, cap extraction confidence at 3
      val quoteChecked = adjudicatedFindings.filter { !it.id.startsWith("Q-") && !it.id.startsWith("S-") }
      if (quoteChecked.size >= 3) {
        val unverifiedOrMismatch = quoteChecked.count {
          it.status == FindingStatus.UNVERIFIED || it.verificationNotes.contains("Page mismatch", ignoreCase = true)
        }
        if (unverifiedOrMismatch * 2 > quoteChecked.size) {
          score = minOf(score, 3)
        }
      }
      return score.coerceIn(1, 5)
    }

    internal fun extractRubricRationales(summary: String): Map<String, String> {
      if (summary.isBlank()) return emptyMap()
      val result = mutableMapOf<String, String>()
      val regex = Regex("""\[RUBRIC:\s*([A-Za-z]+)\]\s*(.+)""", RegexOption.IGNORE_CASE)
      summary.lines().forEach { line ->
        val m = regex.find(line.trim()) ?: return@forEach
        val key = m.groupValues[1].trim().lowercase(Locale.US)
        val value = com.example.util.DossierFormattingEngine.cleanAcademicProse(m.groupValues[2].trim())
        if (value.isNotBlank()) {
          when {
            key.startsWith("method") -> result["methodology"] = value
            key.startsWith("signif") -> result["significance"] = value
            key.startsWith("clar") -> result["clarity"] = value
            key.startsWith("orig") || key.startsWith("novel") -> result["originality"] = value
            key.startsWith("math") || key.startsWith("quant") || key.startsWith("stat") -> result["math"] = value
            key.startsWith("repro") -> result["reproducibility"] = value
          }
        }
      }
      return result
    }

    internal fun parseStructuredFindingsFromJson(arr: JSONArray?): List<ReviewFinding> {
      if (arr == null || arr.length() == 0) return emptyList()
      return buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val id = obj.optString("id", "F-${i + 1}").trim().ifBlank { "F-${i + 1}" }
          val title = com.example.util.DossierFormattingEngine.cleanAcademicProse(
            obj.optString("title", "Appraisal Finding ${i + 1}")
          )
          val allegation = com.example.util.DossierFormattingEngine.cleanAcademicProse(
            obj.optString("allegation", "")
          )
          if (allegation.isBlank()) continue
          val severity = obj.optString("severity", "MAJOR").trim().uppercase(Locale.US).let {
            if (it in setOf("CRITICAL", "MAJOR", "MINOR", "INFO")) it else "MAJOR"
          }
          val status = FindingStatus.fromNameOrDefault(obj.optString("status", "UNVERIFIED"))
          val pageNum = obj.optInt("pageNumber", 0).coerceAtLeast(0)
          val section = obj.optString("section", "").trim().takeIf { it.isNotBlank() }
          val quote = obj.optString("supportingQuote", "").trim()
          val counter = obj.optString("counterEvidenceQuote", "").trim()
          val justification = com.example.util.DossierFormattingEngine.cleanAcademicProse(
            obj.optString("methodologicalJustification", "")
          )
          val remedy = com.example.util.DossierFormattingEngine.cleanAcademicProse(
            obj.optString("recommendedCorrection", "")
          )
          add(
            ReviewFinding(
              id = id,
              title = title,
              allegation = allegation,
              severity = severity,
              status = status,
              supportingEvidence = if (quote.isNotBlank() || pageNum > 0) {
                listOf(EvidenceAnchor(pageNumber = pageNum, section = section, exactQuote = quote))
              } else emptyList(),
              counterEvidence = if (counter.isNotBlank()) {
                listOf(EvidenceAnchor(pageNumber = pageNum, section = section, exactQuote = counter))
              } else emptyList(),
              methodologicalJustification = justification,
              recommendedCorrection = remedy
            )
          )
        }
      }
    }

    private fun normalizeForExactQuoteMatch(input: String): String {
      return input
        .lowercase(Locale.US)
        .replace('–', '-')
        .replace('—', '-')
        .replace('−', '-')
        .replace('“', '"')
        .replace('”', '"')
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace(Regex("""\s+"""), " ")
        .trim()
    }

    /**
     * Strict source-quote grounding verifier:
     * - [QuoteMatchKind.EXACT]: requires a contiguous normalized substring match in [manuscriptText],
     *   records offsets and occurrence count, and checks whether [claimedPage] matches the actual page.
     * - [QuoteMatchKind.FUZZY_LOCALIZED]: allows ≥65% content-token overlap ONLY within a contiguous
     *   500-char window (never scattered across unrelated pages). Strictly maps to PARTIALLY_SUPPORTED,
     *   NEVER VERIFIED.
     * - [QuoteMatchKind.NONE]: maps to UNVERIFIED.
     */
    internal fun verifyQuoteInManuscript(
      quote: String,
      manuscriptText: String,
      claimedPage: Int = 0
    ): QuoteVerificationResult {
      val cleanQuote = quote.trim().trim('"', '“', '”', '\'')
      if (cleanQuote.length < 8 || manuscriptText.isBlank()) {
        return QuoteVerificationResult(QuoteMatchKind.NONE, resolvedPage = 0)
      }

      val normQuote = normalizeForExactQuoteMatch(cleanQuote)
      // Build a character-index mapping from normalized text back to raw text offsets
      val normBuilder = StringBuilder(manuscriptText.length)
      val normToRawIdx = IntArray(manuscriptText.length + 1)
      var prevSpace = true
      for (i in manuscriptText.indices) {
        val rawCh = manuscriptText[i]
        val mapped = when (rawCh) {
          '–', '—', '−' -> '-'
          '“', '”' -> '"'
          '’', '‘' -> '\''
          else -> rawCh.lowercaseChar()
        }
        if (mapped.isWhitespace()) {
          if (!prevSpace) {
            normToRawIdx[normBuilder.length] = i
            normBuilder.append(' ')
            prevSpace = true
          }
        } else {
          normToRawIdx[normBuilder.length] = i
          normBuilder.append(mapped)
          prevSpace = false
        }
      }
      val normDoc = normBuilder.toString().trimEnd()

      // 1. Exact contiguous normalized match (find all occurrences to respect page match & uniqueness)
      val exactMatches = mutableListOf<Triple<Int, Int, Int>>() // (rawStart, rawEnd, page)
      var searchFrom = 0
      while (searchFrom < normDoc.length) {
        val idx = normDoc.indexOf(normQuote, searchFrom)
        if (idx < 0) break
        val rawStart = normToRawIdx[idx]
        val endNormPos = (idx + normQuote.length - 1).coerceAtMost(normDoc.length - 1)
        val rawEnd = (normToRawIdx[endNormPos] + 1).coerceAtMost(manuscriptText.length)
        val page = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(manuscriptText, rawStart)
        exactMatches.add(Triple(rawStart, rawEnd, page))
        searchFrom = idx + 1
      }

      if (exactMatches.isNotEmpty()) {
        val preferred = if (claimedPage > 0) {
          exactMatches.firstOrNull { it.third == claimedPage } ?: exactMatches.first()
        } else {
          exactMatches.first()
        }
        val pageMismatch = if (claimedPage > 0 && preferred.third > 0 && preferred.third != claimedPage) {
          "Page mismatch: finding cited Page $claimedPage, but exact quote appears on Page ${preferred.third}."
        } else {
          null
        }
        return QuoteVerificationResult(
          matchKind = QuoteMatchKind.EXACT,
          resolvedPage = preferred.third,
          startOffset = preferred.first,
          endOffset = preferred.second,
          occurrenceCount = exactMatches.size,
          pageMismatchWarning = pageMismatch
        )
      }

      // 2. Localized sliding-window fuzzy token overlap (strictly PARTIALLY_SUPPORTED, never VERIFIED)
      val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "were", "was",
        "are", "not", "but", "into", "their", "have", "has", "also", "which", "these"
      )
      val quoteTokens = normQuote.split(Regex("""[^a-z0-9.²]+"""))
        .filter { it.length >= 3 && it !in stopWords }
        .distinct()
      if (quoteTokens.size < 3) {
        return QuoteVerificationResult(QuoteMatchKind.NONE, resolvedPage = 0)
      }

      val windowSize = (normQuote.length * 2).coerceIn(220, 520)
      val step = 100
      var bestRatio = 0.0
      var bestNormStart = -1
      var pos = 0
      while (pos < normDoc.length) {
        val winEnd = (pos + windowSize).coerceAtMost(normDoc.length)
        val window = normDoc.substring(pos, winEnd)
        val hits = quoteTokens.count { tok -> window.contains(tok) }
        val ratio = hits.toDouble() / quoteTokens.size.toDouble()
        if (ratio > bestRatio) {
          bestRatio = ratio
          bestNormStart = pos
        }
        if (winEnd == normDoc.length) break
        pos += step
      }

      if (bestRatio >= 0.65 && bestNormStart >= 0) {
        val rawStart = normToRawIdx[bestNormStart]
        val rawEnd = (rawStart + windowSize).coerceAtMost(manuscriptText.length)
        val resolvedPage = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(manuscriptText, rawStart)
        val pageMismatch = if (claimedPage > 0 && resolvedPage > 0 && resolvedPage != claimedPage) {
          "Page mismatch: cited Page $claimedPage, closest partial match near Page $resolvedPage."
        } else {
          null
        }
        return QuoteVerificationResult(
          matchKind = QuoteMatchKind.FUZZY_LOCALIZED,
          resolvedPage = resolvedPage,
          startOffset = rawStart,
          endOffset = rawEnd,
          occurrenceCount = 1,
          pageMismatchWarning = pageMismatch
        )
      }

      return QuoteVerificationResult(QuoteMatchKind.NONE, resolvedPage = 0)
    }

    /**
     * Cross-Pass Adjudicator:
     * 1. Rejects false absence claims with counter-evidence snippets.
     * 2. Enforces strict exact-quote grounding for VERIFIED status; fuzzy localized matches or
     *    page-mismatched quotes are strictly downgraded to PARTIALLY_SUPPORTED with explicit warnings;
     *    ungrounded quotes become UNVERIFIED.
     * 3. Injects deterministic QuantitativeForensicAnalyzer FAIL discrepancies framed carefully as
     *    "Deterministic Discrepancy Requiring Source Reconciliation" (VERIFIED only when exact-grounded
     *    and corroborated by an internal cross-reference discrepancy under high extraction confidence;
     *    otherwise PARTIALLY_SUPPORTED pending table/figure/supplement reconciliation).
     * 4. Reconciles credibilityScore strictly to PaperReviewRubric.computeWeightedScore(...) and
     *    derives the final verdict exclusively from score bands, verified critical errors, and mode rules.
     */
    internal fun adjudicateFindingsAndScores(
      rawFindings: List<ReviewFinding>,
      manuscriptText: String,
      forensicReport: ForensicPreFlightReport?,
      methodologyScore: Int,
      significanceScore: Int,
      clarityScore: Int,
      originalityScore: Int,
      mathRigorScore: Int,
      reproducibilityScore: Int,
      rawVerdict: String,
      reviewMode: ReviewMode,
      extractionConfidence: Int = 5,
      applyStatisticalScoreGate: Boolean = false,
      epistemicReport: EpistemicSafeguardReport? = null,
      modelVersion: String = "gemini-firebase-ai-logic",
      promptCoverage: String = "100%"
    ): AdjudicationResult {
      val absenceCounterChecks = listOf(
        Triple(
          Regex("""(?i)\b(?:no|lack(?:s|ed|ing)?|absent|missing|without|fail(?:ed|s)?\s+to\s+(?:perform|conduct|use|run)|unweighted)\b[^.\n]{0,65}\b(?:random[\s-]?effects?|meta[\s-]?analys|inverse[\s-]?variance|pooled\s+estimate)\b"""),
          Regex("""(?i)\b(?:random[\s-]?effects?(?:\s+meta[\s-]?analys(?:is|es))?|inverse[\s-]?variance|DerSimonian|REML)\b"""),
          "Manuscript explicitly reports random-effects meta-analysis"
        ),
        Triple(
          Regex("""(?i)\b(?:no|lack(?:s|ed|ing)?|absent|missing|omit(?:ted|s|ting)?|without|fail(?:ed|s)?\s+to\s+report)\b[^.\n]{0,65}\b(?:heterogeneity|I[²2]|tau[\s-]?squared|τ²)\b"""),
          Regex("""(?i)(?:I\s*[²2]\s*(?:[-–—]?\s*statistic\s*)?(?:=|≥|>|was|of|above|exceeded)?\s*\d{1,3}(?:\.\d+)?\s*%|\btau[\s-]?squared\b|\bheterogeneity\s+(?:was|exceeded|across))"""),
          "Manuscript explicitly reports I² heterogeneity statistics"
        ),
        Triple(
          Regex("""(?i)\b(?:no|lack(?:s|ed|ing)?|absent|missing|without|fail(?:ed|s)?\s+to\s+perform)\b[^.\n]{0,65}\b(?:meta[\s-]?regression|moderator\s+analys|subgroup\s+analys)\b"""),
          Regex("""(?i)\b(?:meta[\s-]?regression|subgroup\s+analys(?:is|es)|moderator\s+analys)\b"""),
          "Manuscript explicitly performs meta-regression or subgroup analysis"
        ),
        Triple(
          Regex("""(?i)\b(?:single\s+(?:reviewer|extractor)|no\s+independent\s+(?:extraction|screening)|did\s+not\s+(?:independently|double)\s+(?:extract|screen))\b"""),
          Regex("""(?i)\b(?:independently\s+(?:extracted|screened|reviewed|assessed)|(?:two|three|2|3)\s+(?:independent\s+)?(?:authors|reviewers|raters))\b"""),
          "Manuscript explicitly states multi-author independent extraction/screening"
        )
      )

      val verifiedFindings = rawFindings.map { finding ->
        var updatedStatus = finding.status
        val notes = mutableListOf<String>()
        if (finding.verificationNotes.isNotBlank()) notes.add(finding.verificationNotes)
        var updatedSupporting = finding.supportingEvidence
        val updatedCounter = finding.counterEvidence.toMutableList()

        // 1. P0-03: Method-status-aware absence counter-checks (excluding References; negated,
        //    proposed-only, and prior-work citations NEVER dismiss a valid criticism)
        val combinedClaim = "${finding.title}. ${finding.allegation}"
        val refCut = Regex("""(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(references|bibliography|works\s+cited)\s*$""")
          .find(manuscriptText)?.range?.first ?: manuscriptText.length
        val searchableBody = manuscriptText.substring(0, refCut)
        for ((allegationRegex, counterRegex, reasonLabel) in absenceCounterChecks) {
          if (allegationRegex.containsMatchIn(combinedClaim)) {
            val allCounterMatches = counterRegex.findAll(searchableBody).toList()
            val performedMatch = allCounterMatches.firstOrNull { m ->
              val sentStart = searchableBody.lastIndexOfAny(charArrayOf('.', '\n'), m.range.first).let { if (it < 0) 0 else it + 1 }
              val sentEnd = searchableBody.indexOfAny(charArrayOf('.', '\n'), m.range.last).let { if (it < 0) searchableBody.length else it }
              val sentence = searchableBody.substring(sentStart, sentEnd).replace(Regex("""\s+"""), " ").trim()
              val section = BidirectionalAdjudicator.resolveSectionAtOffset(searchableBody, m.range.first)
              BidirectionalAdjudicator.classifyMethodMentionStatus(sentence, m.value, section) ==
                BidirectionalAdjudicator.MethodMentionStatus.PERFORMED
            }
            if (performedMatch != null) {
              val snippetStart = (performedMatch.range.first - 60).coerceAtLeast(0)
              val snippetEnd = (performedMatch.range.last + 110).coerceAtMost(searchableBody.length)
              val snippet = searchableBody.substring(snippetStart, snippetEnd)
                .replace(Regex("""\s+"""), " ")
                .trim()
              val counterPage = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(searchableBody, performedMatch.range.first)
              updatedCounter.add(
                EvidenceAnchor(
                  pageNumber = counterPage,
                  section = if (counterPage > 0) "Page $counterPage" else "Manuscript text",
                  exactQuote = snippet,
                  startOffset = snippetStart,
                  endOffset = snippetEnd
                )
              )
              updatedStatus = FindingStatus.CONTRADICTED
              notes.add("Contradicted by performed method in source text: $reasonLabel.")
              break
            } else if (allCounterMatches.isNotEmpty()) {
              notes.add("Method keyword found in manuscript, but context was NOT_PERFORMED, PROPOSED_ONLY, or CITED_AS_PRIOR_WORK; allegation not dismissed.")
            }
          }
        }

        // 2. P0-04: Separate Source Authenticity (exact quote match) AND Scientific Entailment
        //    (verifying the cited passage logically connects to the alleged criticism).
        if (updatedStatus == FindingStatus.VERIFIED || updatedStatus == FindingStatus.PARTIALLY_SUPPORTED) {
          val primaryAnchor = updatedSupporting.firstOrNull()
          val quote = primaryAnchor?.exactQuote.orEmpty()
          val claimedPage = primaryAnchor?.pageNumber ?: 0
          if (quote.isBlank()) {
            updatedStatus = FindingStatus.UNVERIFIED
            notes.add("Downgraded: no verbatim manuscript quote provided.")
          } else {
            val verification = verifyQuoteInManuscript(
              quote = quote,
              manuscriptText = manuscriptText,
              claimedPage = claimedPage
            )
            when (verification.matchKind) {
              QuoteMatchKind.EXACT -> {
                if (primaryAnchor != null) {
                  val correctedAnchor = primaryAnchor.copy(
                    pageNumber = if (verification.resolvedPage > 0) verification.resolvedPage else primaryAnchor.pageNumber,
                    startOffset = verification.startOffset,
                    endOffset = verification.endOffset
                  )
                  updatedSupporting = listOf(correctedAnchor) + updatedSupporting.drop(1)
                }
                val matchStart = verification.startOffset ?: 0
                val matchEnd = verification.endOffset ?: matchStart
                val windowStart = (matchStart - 160).coerceAtLeast(0)
                val windowEnd = (matchEnd + 160).coerceAtMost(manuscriptText.length)
                val surrounding = if (windowEnd > windowStart) manuscriptText.substring(windowStart, windowEnd) else quote
                val entailment = evaluateQuoteEntailment(finding, quote, surrounding)
                if (entailment == EntailmentStatus.CONTRADICTED_BY_POLARITY) {
                  updatedStatus = FindingStatus.CONTRADICTED
                  updatedCounter.add(
                    EvidenceAnchor(
                      pageNumber = if (verification.resolvedPage > 0) verification.resolvedPage else claimedPage,
                      section = primaryAnchor?.section?.ifBlank { "Manuscript text" } ?: "Manuscript text",
                      exactQuote = quote,
                      startOffset = verification.startOffset,
                      endOffset = verification.endOffset
                    )
                  )
                  notes.add("Contradicted by source polarity: verbatim quote directly negates or reverses the stated claim (CONTRADICTED_BY_POLARITY).")
                } else if (verification.pageMismatchWarning != null) {
                  updatedStatus = FindingStatus.PARTIALLY_SUPPORTED
                  notes.add(verification.pageMismatchWarning)
                } else if (updatedStatus == FindingStatus.VERIFIED && entailment != EntailmentStatus.ENTAILED) {
                  updatedStatus = FindingStatus.PARTIALLY_SUPPORTED
                  notes.add("Source authenticity: EXACT quote verified; Scientific entailment: ${entailment.name} (quote alone does not independently establish the stated criticism; downgraded to PARTIALLY SUPPORTED).")
                } else if (updatedStatus == FindingStatus.VERIFIED) {
                  notes.add("Source authenticity: EXACT quote verified; Scientific entailment: ENTAILED.")
                }
              }
              QuoteMatchKind.FUZZY_LOCALIZED -> {
                updatedStatus = FindingStatus.PARTIALLY_SUPPORTED
                if (primaryAnchor != null) {
                  val correctedAnchor = primaryAnchor.copy(
                    pageNumber = if (verification.resolvedPage > 0) verification.resolvedPage else primaryAnchor.pageNumber,
                    startOffset = verification.startOffset,
                    endOffset = verification.endOffset
                  )
                  updatedSupporting = listOf(correctedAnchor) + updatedSupporting.drop(1)
                }
                val mismatchSuffix = verification.pageMismatchWarning?.let { " $it" }.orEmpty()
                notes.add("Downgraded from VERIFIED to PARTIALLY SUPPORTED: quote is paraphrased/composite rather than verbatim.$mismatchSuffix")
              }
              QuoteMatchKind.NONE -> {
                updatedStatus = FindingStatus.UNVERIFIED
                notes.add("Downgraded to UNVERIFIED: cited quote was not found in extracted source text.")
              }
            }
          }
        }

        finding.copy(
          status = updatedStatus,
          supportingEvidence = updatedSupporting,
          counterEvidence = updatedCounter.distinctBy { it.exactQuote },
          verificationNotes = notes.joinToString(" ").trim()
        )
      }.toMutableList()

      // 3. Auto-inject deterministic QuantitativeForensicAnalyzer FAIL findings with strict
      // source-authenticity vs. semantic-compatibility separation & explicit ledger-cap accounting.
      var ledgerCapOmittedCount = 0
      val ledgerCapCategories = mutableListOf<String>()

      if (forensicReport != null) {
        var nextQuantIdx = 1
        forensicReport.quantForensics.confidenceIntervalChecks
          .filter { it.status == QuantFindingStatus.FAIL }
          .forEach { ci ->
            val alreadyCovered = verifiedFindings.any { existing ->
              existing.allegation.contains(ci.rawClaim.take(18), ignoreCase = true) ||
                (existing.title.contains("confidence interval", ignoreCase = true) &&
                  existing.supportingEvidence.any { it.pageNumber == ci.pageNumber && ci.pageNumber > 0 })
            }
            if (!alreadyCovered) {
              val hasCrossRefCorroboration = ci.crossReferenceNote.isNotBlank() && ci.crossReferenceQuote.isNotBlank()
              val quoteCheck = verifyQuoteInManuscript(ci.rawClaim, manuscriptText, ci.pageNumber)
              val xrefCheck = if (hasCrossRefCorroboration) {
                verifyQuoteInManuscript(ci.crossReferenceQuote, manuscriptText, ci.crossReferencePage)
              } else {
                QuoteVerificationResult(QuoteMatchKind.NONE, resolvedPage = 0)
              }
              val primaryWindow = extractQuoteContextWindow(manuscriptText, quoteCheck, ci.rawClaim)
              val xrefWindow = if (xrefCheck.matchKind == QuoteMatchKind.EXACT) {
                extractQuoteContextWindow(manuscriptText, xrefCheck, ci.crossReferenceQuote)
              } else ""
              val primaryQualifier = hasConflictingReconstructionQualifiers(primaryWindow)
              val xrefQualifier = if (xrefWindow.isNotBlank()) hasConflictingReconstructionQualifiers(xrefWindow) else null
              val dualCompatibility = if (xrefCheck.matchKind == QuoteMatchKind.EXACT) {
                evaluateDualPassageTypedCompatibility(primaryWindow, xrefWindow)
              } else {
                PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "missing independent second passage")
              }
              val isCorroboratedVerified = hasCrossRefCorroboration &&
                quoteCheck.matchKind == QuoteMatchKind.EXACT &&
                xrefCheck.matchKind == QuoteMatchKind.EXACT &&
                extractionConfidence >= 4 &&
                primaryQualifier == null &&
                xrefQualifier == null &&
                dualCompatibility.status == PropositionCompatibility.COMPATIBLE
              val deterministicStatus = if (isCorroboratedVerified) {
                FindingStatus.VERIFIED
              } else {
                FindingStatus.PARTIALLY_SUPPORTED
              }
              val deterministicReportingStatus = if (isCorroboratedVerified) {
                ReportingStatus.REPORTED_BUT_INADEQUATE.name
              } else {
                ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name
              }
              val detail = buildString {
                append(ci.note)
                if (ci.crossReferenceNote.isNotBlank()) {
                  append(" Internal cross-reference discrepancy: ${ci.crossReferenceNote}")
                } else {
                  append(" Deterministic discrepancy requiring source reconciliation against primary tables, figures, units/transformations, and supplementary files.")
                }
              }
              val anchors = buildList {
                add(
                  EvidenceAnchor(
                    pageNumber = if (quoteCheck.resolvedPage > 0) quoteCheck.resolvedPage else ci.pageNumber,
                    section = if (ci.pageNumber > 0) "Page ${ci.pageNumber}" else "Results",
                    exactQuote = ci.rawClaim,
                    startOffset = quoteCheck.startOffset,
                    endOffset = quoteCheck.endOffset
                  )
                )
                if (xrefCheck.matchKind == QuoteMatchKind.EXACT) {
                  add(
                    EvidenceAnchor(
                      pageNumber = if (xrefCheck.resolvedPage > 0) xrefCheck.resolvedPage else ci.crossReferencePage,
                      section = if (ci.crossReferencePage > 0) "Page ${ci.crossReferencePage}" else "Cross-Reference",
                      exactQuote = ci.crossReferenceQuote,
                      startOffset = xrefCheck.startOffset,
                      endOffset = xrefCheck.endOffset
                    )
                  )
                }
              }
              val holdReason = when {
                !hasCrossRefCorroboration || xrefCheck.matchKind != QuoteMatchKind.EXACT ->
                  "uncorroborated by an independently source-matched second passage"
                extractionConfidence < 4 ->
                  "extraction confidence below 4/5 ($extractionConfidence/5)"
                primaryQualifier != null ->
                  "conflicting statistical qualifier in primary context ($primaryQualifier)"
                xrefQualifier != null ->
                  "conflicting statistical qualifier in cross-referenced context ($xrefQualifier)"
                dualCompatibility.status != PropositionCompatibility.COMPATIBLE ->
                  "cross-referenced passages fail typed estimand/context compatibility (${dualCompatibility.reason})"
                else -> "manual reconciliation required"
              }
              verifiedFindings.add(
                0,
                ReviewFinding(
                  id = "Q-0$nextQuantIdx",
                  title = "Deterministic CI Discrepancy Requiring Source Reconciliation (${ci.rawClaim})",
                  allegation = detail,
                  severity = "CRITICAL",
                  status = deterministicStatus,
                  supportingEvidence = anchors,
                  methodologicalJustification = "A point estimate must lie within its confidence interval [lower, upper] on the same scale, population, adjustment model, and confidence level. Potential causes include a transcription typo, unit/scale transformation mismatch, subgroup-vs-overall shift, or PDF extraction/OCR artifact.",
                  recommendedCorrection = "Reconcile the reported narrative estimate and CI directly against primary tables, forest plots/figures, and supplementary spreadsheets; issue a numerical erratum if confirmed in the published source.",
                  verificationNotes = if (isCorroboratedVerified) {
                    "Deterministic CI check confirmed via dual-passage exact source match with compatible typed context and no conflicting scale/method qualifiers."
                  } else {
                    "Deterministic discrepancy held at PARTIALLY SUPPORTED (${ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.displayLabel}): $holdReason."
                  },
                  reportingStatus = deterministicReportingStatus
                )
              )
              nextQuantIdx++
            }
          }

        val statcheckFails = forensicReport.quantForensics.statcheckResults
          .filter { it.status == QuantFindingStatus.FAIL }
        if (statcheckFails.size > 2) {
          val omitted = statcheckFails.size - 2
          ledgerCapOmittedCount += omitted
          ledgerCapCategories.add("statcheck_ledger_cap($omitted omitted)")
        }
        statcheckFails
          .take(2)
          .forEach { st ->
            val alreadyCovered = verifiedFindings.any { it.allegation.contains(st.rawClaim.take(16), ignoreCase = true) }
            if (!alreadyCovered) {
              val quoteCheck = verifyQuoteInManuscript(st.rawClaim, manuscriptText, st.pageNumber)
              verifiedFindings.add(
                0,
                ReviewFinding(
                  id = "Q-0$nextQuantIdx",
                  title = "Deterministic Statcheck Discrepancy Requiring Reconciliation (${st.rawClaim})",
                  allegation = "${st.note} (Recomputed two-tailed p = ${String.format(Locale.US, "%.4f", st.recomputedP)}). Requires verification for one-tailed testing, rounding, corrections, or transcription errors.",
                  severity = "CRITICAL",
                  status = FindingStatus.PARTIALLY_SUPPORTED,
                  supportingEvidence = listOf(
                    EvidenceAnchor(
                      pageNumber = st.pageNumber,
                      section = if (st.pageNumber > 0) "Page ${st.pageNumber}" else "Statistical Results",
                      exactQuote = st.rawClaim,
                      startOffset = quoteCheck.startOffset,
                      endOffset = quoteCheck.endOffset
                    )
                  ),
                  methodologicalJustification = "Exact two-tailed test-statistic recomputation disagrees with the reported p-value threshold; alternative explanations include one-tailed testing, multiple-comparison adjustment, rounding, or transcription/extraction errors.",
                  recommendedCorrection = "Reconcile test statistic, degrees of freedom, tail direction, and exact p-value against primary tables and statistical output.",
                  verificationNotes = "Deterministic discrepancy requiring source reconciliation against tables, supplements, and test specification (${ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.displayLabel}).",
                  reportingStatus = ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name
                )
              )
              nextQuantIdx++
            }
          }
      }

      // 3b. Inject verified Phase 1-5 Statistical Audit Subsystem FAIL findings when score gating is enabled
      val statAudit = forensicReport?.quantForensics?.statisticalAudit
      if (applyStatisticalScoreGate && statAudit != null) {
        var nextStatIdx = 1
        val eligibleReconstructions = statAudit.reconstructions
          .filter { it.status == QuantFindingStatus.FAIL && it.findingType.isConfirmedError && !it.isScreeningSignal }
        if (eligibleReconstructions.size > 3) {
          val omitted = eligibleReconstructions.size - 3
          ledgerCapOmittedCount += omitted
          ledgerCapCategories.add("reconstructions_ledger_cap($omitted omitted)")
        }
        eligibleReconstructions
          .take(3)
          .forEach { rec ->
            val alreadyCovered = verifiedFindings.any {
              it.allegation.contains(rec.sourceEvidence.take(18), ignoreCase = true) ||
                it.title.contains(rec.calculatorName, ignoreCase = true)
            }
            if (!alreadyCovered) {
              val quoteCheck = verifyQuoteInManuscript(rec.sourceEvidence, manuscriptText, rec.pageNumber)
              val recWindow = extractQuoteContextWindow(manuscriptText, quoteCheck, rec.sourceEvidence)
              val conflictingQualifier = hasConflictingReconstructionQualifiers(recWindow)
              val canPromoteVerified = quoteCheck.matchKind == QuoteMatchKind.EXACT &&
                extractionConfidence >= 4 &&
                conflictingQualifier == null
              val verifiedStatus = if (canPromoteVerified) {
                FindingStatus.VERIFIED
              } else {
                FindingStatus.PARTIALLY_SUPPORTED
              }
              val recReportingStatus = if (canPromoteVerified) {
                ReportingStatus.REPORTED_BUT_INADEQUATE.name
              } else {
                ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name
              }
              val recNote = if (canPromoteVerified) {
                "Executable numerical reconstruction (${rec.checkId}) passed exact source match (extConf=$extractionConfidence/5) with no conflicting scale, adjustment, or inference-method qualifiers."
              } else {
                val reason = when {
                  quoteCheck.matchKind != QuoteMatchKind.EXACT -> "quote match=${quoteCheck.matchKind.name}"
                  extractionConfidence < 4 -> "extConf=$extractionConfidence/5 < 4"
                  conflictingQualifier != null -> "conflicting qualifier in context: $conflictingQualifier"
                  else -> "manual reconciliation required"
                }
                "Executable numerical reconstruction (${rec.checkId}) held at PARTIALLY SUPPORTED (${ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.displayLabel}): $reason."
              }
              verifiedFindings.add(
                0,
                ReviewFinding(
                  id = "S-0${nextStatIdx++}",
                  title = "[${rec.findingType.label}] ${rec.calculatorName}",
                  allegation = "${rec.detail} Reported=${rec.reportedValue} vs Recomputed=${rec.recomputedValue} (tol=${rec.tolerance}).",
                  severity = "CRITICAL",
                  status = verifiedStatus,
                  supportingEvidence = listOf(
                    EvidenceAnchor(
                      pageNumber = rec.pageNumber,
                      section = rec.section,
                      exactQuote = rec.sourceEvidence,
                      startOffset = quoteCheck.startOffset,
                      endOffset = quoteCheck.endOffset
                    )
                  ),
                  methodologicalJustification = "${rec.scientificConsequence} Assumptions: ${rec.assumptions.joinToString("; ")}.",
                  recommendedCorrection = "Verify raw input counts/SEs, scale transformations, adjustment terms, and inference method against primary tables and statistical output and correct reported values.",
                  verificationNotes = recNote,
                  reportingStatus = recReportingStatus
                )
              )
            }
          }

        // P0-06 & P1 Strict Dual-Passage Typed Compatibility Gate for cross-section contradictions:
        // Exact quote match proves source presence ONLY; promotion to VERIFIED additionally requires
        // >= 2 independently exact-matched passages, extConf >= 4, no conflicting scale/method/CI-level
        // qualifiers, AND compatible typed propositions (population, outcome, subgroup/overall,
        // adjusted/unadjusted, scale/transformation, inference method, CI level).
        val eligibleCrossLinks = statAudit.crossLinks
          .filter { it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.INTERNAL_CONTRADICTION }
        if (eligibleCrossLinks.size > 2) {
          val omitted = eligibleCrossLinks.size - 2
          ledgerCapOmittedCount += omitted
          ledgerCapCategories.add("cross_links_ledger_cap($omitted omitted)")
        }
        eligibleCrossLinks
          .take(2)
          .forEach { link ->
            val verifiedAnchors = link.sourceQuotes
              .filter { it.isNotBlank() }
              .map { q -> q to verifyQuoteInManuscript(q, manuscriptText, link.pageNumbers.firstOrNull() ?: 0) }
            val allQuotesExact = verifiedAnchors.size >= 2 &&
              verifiedAnchors.all { it.second.matchKind == QuoteMatchKind.EXACT }
            val w1 = verifiedAnchors.getOrNull(0)?.let { (q, chk) ->
              extractQuoteContextWindow(manuscriptText, chk, q, excludeRange = verifiedAnchors.getOrNull(1)?.second?.let { s -> if (s.startOffset != null && s.endOffset != null) s.startOffset until s.endOffset else null })
            }.orEmpty()
            val w2 = verifiedAnchors.getOrNull(1)?.let { (q, chk) ->
              extractQuoteContextWindow(manuscriptText, chk, q, excludeRange = verifiedAnchors.getOrNull(0)?.second?.let { s -> if (s.startOffset != null && s.endOffset != null) s.startOffset until s.endOffset else null })
            }.orEmpty()
            val q1Text = verifiedAnchors.getOrNull(0)?.first.orEmpty()
            val q2Text = verifiedAnchors.getOrNull(1)?.first.orEmpty()
            val qualifier1 = if (w1.isNotBlank()) hasConflictingReconstructionQualifiers(w1) else null
            val qualifier2 = if (w2.isNotBlank()) hasConflictingReconstructionQualifiers(w2) else null
            val dualCompat = if (allQuotesExact) {
              evaluateDualPassageTypedCompatibility(
                passage1Window = w1,
                passage2Window = w2,
                passage1Exact = q1Text,
                passage2Exact = q2Text
              )
            } else {
              PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "fewer than 2 exact source-matched passages")
            }
            val canPromoteVerified = allQuotesExact &&
              extractionConfidence >= 4 &&
              qualifier1 == null &&
              qualifier2 == null &&
              dualCompat.status == PropositionCompatibility.COMPATIBLE
            val resolvedStatus = if (canPromoteVerified) {
              FindingStatus.VERIFIED
            } else {
              FindingStatus.PARTIALLY_SUPPORTED
            }
            val linkReportingStatus = if (canPromoteVerified) {
              ReportingStatus.REPORTED_BUT_INADEQUATE.name
            } else {
              ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name
            }
            val anchors = verifiedAnchors.mapIndexed { idx, (q, check) ->
              EvidenceAnchor(
                pageNumber = if (check.resolvedPage > 0) check.resolvedPage else link.pageNumbers.getOrNull(idx) ?: 0,
                section = link.sectionsInvolved.getOrNull(idx) ?: link.sectionsInvolved.joinToString(" / "),
                exactQuote = q,
                startOffset = check.startOffset,
                endOffset = check.endOffset
              )
            }.ifEmpty {
              listOf(
                EvidenceAnchor(
                  pageNumber = link.pageNumbers.firstOrNull() ?: 0,
                  section = link.sectionsInvolved.joinToString(" / "),
                  exactQuote = link.sourceQuotes.firstOrNull().orEmpty()
                )
              )
            }
            val holdDetail = when {
              !allQuotesExact -> "exactMatches=${verifiedAnchors.count { it.second.matchKind == QuoteMatchKind.EXACT }}/${link.sourceQuotes.size}"
              extractionConfidence < 4 -> "extConf=$extractionConfidence/5 < 4"
              qualifier1 != null -> "conflicting qualifier in passage 1 ($qualifier1)"
              qualifier2 != null -> "conflicting qualifier in passage 2 ($qualifier2)"
              dualCompat.status != PropositionCompatibility.COMPATIBLE -> "typed proposition mismatch (${dualCompat.reason})"
              else -> "manual reconciliation required"
            }
            verifiedFindings.add(
              0,
              ReviewFinding(
                id = "S-0${nextStatIdx++}",
                title = "[${link.findingType.label}] ${link.linkType}",
                allegation = link.description,
                severity = "CRITICAL",
                status = resolvedStatus,
                supportingEvidence = anchors,
                methodologicalJustification = "Sample denominators, subgroup sums, and cross-section estimates must reconcile arithmetically across matching outcomes, populations, adjustment models, scales, and confidence levels.",
                recommendedCorrection = "Reconcile participant flow, exclusions, subgroups, adjustment status, and denominators across Abstract, Methods, Results, and Tables.",
                verificationNotes = if (canPromoteVerified) {
                  "Cross-section evidence link (${link.linkId}) passed dual-passage exact source match AND typed context compatibility."
                } else {
                  "Deterministic cross-section screening signal (${link.linkId}) held at PARTIALLY SUPPORTED (${ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.displayLabel}): $holdDetail."
                },
                reportingStatus = linkReportingStatus
              )
            )
          }

        // P1 Domain Model-Mismatch Verification Gate:
        // Exact quote matching is source authenticity ONLY; promotion to VERIFIED requires
        // high extraction confidence (>= 4) AND independent scientific entailment (ENTAILED)
        // confirming the cited Methods/Results passage actually entails the alleged model mismatch.
        val eligibleDomainFindings = statAudit.domainFindings
          .filter { it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.MODEL_MISMATCH }
        if (eligibleDomainFindings.size > 2) {
          val omitted = eligibleDomainFindings.size - 2
          ledgerCapOmittedCount += omitted
          ledgerCapCategories.add("domain_findings_ledger_cap($omitted omitted)")
        }
        eligibleDomainFindings
          .take(2)
          .forEach { df ->
            val quoteText = df.evidenceQuote.trim()
            val quoteCheck = if (quoteText.isNotBlank()) {
              verifyQuoteInManuscript(quoteText, manuscriptText, df.pageNumber)
            } else {
              QuoteVerificationResult(QuoteMatchKind.NONE, resolvedPage = 0)
            }
            val candidateDomainFinding = ReviewFinding(
              id = "S-0$nextStatIdx",
              title = "[${df.findingType.label}] ${df.criterion}",
              allegation = df.detail,
              severity = "CRITICAL",
              status = FindingStatus.VERIFIED,
              supportingEvidence = listOf(
                EvidenceAnchor(
                  pageNumber = if (quoteCheck.resolvedPage > 0) quoteCheck.resolvedPage else df.pageNumber,
                  section = df.domainModule,
                  exactQuote = quoteText.ifBlank { df.criterion },
                  startOffset = quoteCheck.startOffset,
                  endOffset = quoteCheck.endOffset
                )
              ),
              methodologicalJustification = df.consequence,
              recommendedCorrection = df.recommendedAction
            )
            val domainWindow = extractQuoteContextWindow(manuscriptText, quoteCheck, quoteText)
            val entailment = if (quoteCheck.matchKind == QuoteMatchKind.EXACT) {
              evaluateQuoteEntailment(candidateDomainFinding, quoteText, domainWindow)
            } else {
              EntailmentStatus.NOT_ENTAILED
            }
            val isVerifiedDomain = quoteCheck.matchKind == QuoteMatchKind.EXACT &&
              extractionConfidence >= 4 &&
              entailment == EntailmentStatus.ENTAILED
            val resolvedStatus = if (isVerifiedDomain) FindingStatus.VERIFIED else FindingStatus.PARTIALLY_SUPPORTED
            val domainReportingStatus = if (isVerifiedDomain) {
              ReportingStatus.REPORTED_BUT_INADEQUATE.name
            } else {
              ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name
            }
            verifiedFindings.add(
              0,
              candidateDomainFinding.copy(
                id = "S-0${nextStatIdx++}",
                status = resolvedStatus,
                verificationNotes = if (isVerifiedDomain) {
                  "Domain statistical audit (${df.findingId}, ${statAudit.subDesign.displayName}) passed exact source match AND scientific entailment verification."
                } else {
                  "Domain statistical screening signal (${df.findingId}, ${statAudit.subDesign.displayName}) held at PARTIALLY SUPPORTED (${ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.displayLabel}): quoteMatch=${quoteCheck.matchKind.name}, entailment=${entailment.name}, extConf=$extractionConfidence/5."
                },
                reportingStatus = domainReportingStatus
              )
            )
          }
      }

      // 3c. Inject fatal/major epistemic contradictions & claim-overreach findings with per-finding source & typed compatibility verification
      val effectiveEpistemic = epistemicReport ?: forensicReport?.quantForensics?.epistemicSafeguards
      if (applyStatisticalScoreGate && effectiveEpistemic != null && !effectiveEpistemic.stopAndAsk.shouldStopAndAsk) {
        var nextEpiIdx = 1
        val eligibleEpistemicEdges = effectiveEpistemic.contradictionMap.edges
          .filter { it.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION || it.severityTier == FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE }
        if (eligibleEpistemicEdges.size > 2) {
          val omitted = eligibleEpistemicEdges.size - 2
          ledgerCapOmittedCount += omitted
          ledgerCapCategories.add("epistemic_contradictions_ledger_cap($omitted omitted)")
        }
        eligibleEpistemicEdges
          .take(2)
          .forEach { edge ->
            val alreadyCovered = verifiedFindings.any {
              it.title.contains(edge.contradictionType, ignoreCase = true) ||
                it.allegation.contains(edge.description.take(28), ignoreCase = true)
            }
            if (!alreadyCovered) {
              val isFatal = edge.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION
              val edgeQuotes = edge.quotes.filter { it.isNotBlank() }
              val checkedQuotes = edgeQuotes.map { q -> q to verifyQuoteInManuscript(q, manuscriptText, edge.pages.firstOrNull() ?: 0) }
              val isCrossSectionEdge = edge.fromSection != edge.toSection
              val requiredExactCount = if (isCrossSectionEdge) 2 else 1
              val exactCount = checkedQuotes.count { it.second.matchKind == QuoteMatchKind.EXACT }
              val dualCompat = if (checkedQuotes.size >= 2 && exactCount >= 2) {
                val ew1 = extractQuoteContextWindow(manuscriptText, checkedQuotes[0].second, checkedQuotes[0].first)
                val ew2 = extractQuoteContextWindow(manuscriptText, checkedQuotes[1].second, checkedQuotes[1].first)
                evaluateDualPassageTypedCompatibility(ew1, ew2)
              } else {
                PropositionComparison(PropositionCompatibility.COMPATIBLE, "")
              }
              val isSourceVerified = exactCount >= requiredExactCount &&
                extractionConfidence >= 4 &&
                (!isCrossSectionEdge || (checkedQuotes.size >= 2 && dualCompat.status == PropositionCompatibility.COMPATIBLE))
              val resolvedStatus = if (isSourceVerified) FindingStatus.VERIFIED else FindingStatus.PARTIALLY_SUPPORTED
              val edgeReportingStatus = if (isSourceVerified) {
                ReportingStatus.REPORTED_BUT_INADEQUATE.name
              } else {
                ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.name
              }
              val edgeAnchors = checkedQuotes.mapIndexed { idx, (q, check) ->
                EvidenceAnchor(
                  pageNumber = if (check.resolvedPage > 0) check.resolvedPage else edge.pages.getOrNull(idx) ?: 0,
                  section = if (idx == 0) edge.fromSection else edge.toSection,
                  exactQuote = q,
                  startOffset = check.startOffset,
                  endOffset = check.endOffset
                )
              }.ifEmpty {
                listOf(
                  EvidenceAnchor(
                    pageNumber = edge.pages.firstOrNull() ?: 0,
                    section = "${edge.fromSection} ↔ ${edge.toSection}",
                    exactQuote = edge.quotes.firstOrNull().orEmpty()
                  )
                )
              }
              verifiedFindings.add(
                0,
                ReviewFinding(
                  id = "E-0${nextEpiIdx++}",
                  title = "[${edge.severityTier.displayLabel}] ${edge.contradictionType}",
                  allegation = edge.description,
                  severity = if (isFatal) "CRITICAL" else "MAJOR",
                  status = resolvedStatus,
                  supportingEvidence = edgeAnchors,
                  methodologicalJustification = "Cross-section contradiction or claim-vs-design overreach detected by epistemic safeguard graph.",
                  recommendedCorrection = edge.overturnEvidence,
                  verificationNotes = if (isSourceVerified) {
                    "Epistemic contradiction edge (${edge.edgeId}) passed per-finding exact source match and typed compatibility verification."
                  } else {
                    "Epistemic contradiction screening signal (${edge.edgeId}) held at PARTIALLY SUPPORTED (${ReportingStatus.REQUIRES_MANUAL_RECONCILIATION.displayLabel}): $exactCount/$requiredExactCount exact quotes, extConf=$extractionConfidence/5, compat=${dualCompat.status.name}${if (dualCompat.reason.isNotBlank()) " (${dualCompat.reason})" else ""}."
                  },
                  overturnEvidence = edge.overturnEvidence,
                  reportingStatus = edgeReportingStatus
                )
              )
            }
          }
      }

      // 3d. Bidirectional Defense Adjudication (#13) + Flaw Tier (#10) + 4-State Reporting (#2) + Provenance (#14)
      val finalFindings = if (applyStatisticalScoreGate || epistemicReport != null) {
        BidirectionalAdjudicator.adjudicate(
          findings = verifiedFindings,
          manuscriptText = manuscriptText,
          epistemicReport = effectiveEpistemic,
          modelVersion = modelVersion,
          promptCoverage = promptCoverage
        ).findings
      } else {
        verifiedFindings
      }

      // 4. Authoritative 6-axis weighted rubric computation + Phase 6 Evidence-Derived Score Gate
      val rawM = methodologyScore.coerceIn(1, 10)
      val s = significanceScore.coerceIn(1, 10)
      val c = clarityScore.coerceIn(1, 10)
      val o = originalityScore.coerceIn(1, 10)
      val rawMath = mathRigorScore.coerceIn(1, 10)
      val r = reproducibilityScore.coerceIn(1, 10)

      // P0-07: Only VERIFIED, assessable findings may drive EvidenceDerivedScoreGate penalties
      // or verdict caps. Collect the detector IDs embedded in verified findings' verificationNotes.
      val detectorIdPattern = Regex("""\(([A-Z]+-[A-Za-z0-9_-]+)(?:,|\))""")
      val verifiedDetectorIds = finalFindings
        .filter {
          it.status == FindingStatus.VERIFIED &&
            it.reportingStatus != "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL"
        }
        .flatMap { f ->
          buildList {
            add(f.id)
            detectorIdPattern.findAll(f.verificationNotes).forEach { m -> add(m.groupValues[1]) }
          }
        }
        .toSet()

      val breakdown = statAudit?.let {
        EvidenceDerivedScoreGate.evaluate(
          report = it,
          baseMathScore = rawMath,
          baseMethodologyScore = rawM,
          manuscriptText = manuscriptText,
          verifiedDetectorIds = verifiedDetectorIds
        )
      }
      val m = if (applyStatisticalScoreGate && breakdown != null && effectiveEpistemic?.stopAndAsk?.shouldStopAndAsk != true) {
        breakdown.finalMethodologyScore
      } else {
        rawM
      }
      val math = if (applyStatisticalScoreGate && breakdown != null && effectiveEpistemic?.stopAndAsk?.shouldStopAndAsk != true) {
        breakdown.finalMathScore
      } else {
        rawMath
      }

      val exactComposite = PaperReviewRubric.computeWeightedScore(
        methodology = m,
        significance = s,
        clarity = c,
        originality = o,
        mathematicalRigor = math,
        reproducibility = r
      )

      val verifiedFatalCount = finalFindings.count {
        it.status == FindingStatus.VERIFIED &&
          (it.severity == "CRITICAL" || it.flawTier == "FATAL_TO_PRIMARY_CONCLUSION") &&
          it.reportingStatus != "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL"
      }
      val hasVerifiedCriticalError = verifiedFatalCount > 0 ||
        (applyStatisticalScoreGate && breakdown?.verdictCapReason != null && extractionConfidence >= 3)
      val hasPartiallySupportedCriticalDiscrepancy = finalFindings.any {
        it.status == FindingStatus.PARTIALLY_SUPPORTED &&
          it.severity == "CRITICAL" &&
          it.reportingStatus != "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL"
      } || (applyStatisticalScoreGate && breakdown?.verdictCapReason != null && extractionConfidence < 3)
      val verifiedMajorCount = finalFindings.count {
        it.status == FindingStatus.VERIFIED &&
          it.severity == "MAJOR" &&
          it.reportingStatus != "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL"
      }
      val partiallySupportedMajorCount = finalFindings.count {
        it.status == FindingStatus.PARTIALLY_SUPPORTED &&
          it.severity == "MAJOR" &&
          it.reportingStatus != "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL"
      }

      val classifierAbstained = statAudit?.designClassification?.abstained == true
      val identityUncertainCount = effectiveEpistemic?.studyIdentityCard?.uncertainFields?.size ?: 0

      // Safeguard #15 + Classifier Abstention Policy:
      // 1. Explicit Stop-and-Ask (corrupted/unassessable input) -> INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT
      // 2. Design-independent verified critical arithmetic error still enforces revision/correction gate
      // 3. Otherwise, classifier abstained + >=4 uncertain identity fields -> INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT
      // 4. Otherwise, classifier abstained -> PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN
      val resolvedVerdict = when {
        applyStatisticalScoreGate && effectiveEpistemic?.stopAndAsk?.shouldStopAndAsk == true ->
          "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
        applyStatisticalScoreGate && !hasVerifiedCriticalError && classifierAbstained && identityUncertainCount >= 4 ->
          "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
        applyStatisticalScoreGate && !hasVerifiedCriticalError && classifierAbstained ->
          "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN"
        else -> resolveVerdictForMode(
          rawVerdict = rawVerdict,
          compositeScore = exactComposite,
          reviewMode = reviewMode,
          hasVerifiedCriticalError = hasVerifiedCriticalError,
          hasPartiallySupportedCriticalDiscrepancy = hasPartiallySupportedCriticalDiscrepancy,
          verifiedFatalCount = verifiedFatalCount,
          verifiedMajorCount = verifiedMajorCount,
          partiallySupportedMajorCount = partiallySupportedMajorCount
        )
      }

      return AdjudicationResult(
        adjudicatedFindings = finalFindings,
        methodologyScore = m,
        significanceScore = s,
        clarityScore = c,
        originalityScore = o,
        mathRigorScore = math,
        reproducibilityScore = r,
        weightedCredibilityScore = exactComposite,
        resolvedVerdict = resolvedVerdict,
        scoreBreakdown = breakdown,
        ledgerCapOmittedCount = ledgerCapOmittedCount,
        ledgerCapCategories = ledgerCapCategories.distinct()
      )
    }

    /**
     * Single authoritative confidence & coverage summary builder ensuring Page 1 banner,
     * rubric metadata, coverage disclosure, uncertainty budget, and PDF export NEVER disagree.
     */
    internal fun computeAuthoritativeConfidenceSummary(
      manuscriptText: String,
      coverageReport: DocumentCoverageReport?,
      omittedPromptPages: List<Int> = emptyList(),
      wasPromptTrimmed: Boolean = false,
      adjudicatedFindings: List<ReviewFinding> = emptyList(),
      statisticalAudit: StatisticalAuditReport? = null,
      scientificConfidence: Int = 4
    ): ConfidenceSummary {
      val calibratedExtraction = computeCalibratedExtractionConfidence(
        manuscriptText = manuscriptText,
        coverageReport = coverageReport,
        omittedPromptPages = omittedPromptPages,
        wasPromptTrimmed = wasPromptTrimmed,
        adjudicatedFindings = adjudicatedFindings,
        statisticalAudit = statisticalAudit
      )
      val hasSupplementRef = Regex(
        """\b(?:supplementary|online\s+appendix|eTable|Table\s+S\d)\b""",
        RegexOption.IGNORE_CASE
      ).containsMatchIn(manuscriptText)
      val covSummary = coverageReport?.toCoverageSummary(
        unsupportedInputCount = statisticalAudit?.unsupportedAssessmentCount ?: 0,
        reconstructedClaimCount = statisticalAudit?.reconstructions?.size ?: 0,
        hasSupplementReference = hasSupplementRef
      )
      val reasons = buildList {
        if (coverageReport != null && coverageReport.omittedPages.isNotEmpty()) {
          add("${coverageReport.omittedPages.size} page(s) omitted during extraction")
        }
        if (omittedPromptPages.isNotEmpty()) {
          add("${omittedPromptPages.size} page(s) impacted by context trimming")
        }
        val unsupported = statisticalAudit?.unsupportedAssessmentCount ?: 0
        if (unsupported > 0) {
          add("$unsupported quantitative check(s) lacked full extractable inputs")
        }
        if (coverageReport != null && coverageReport.lowTextPages.isNotEmpty()) {
          add("${coverageReport.lowTextPages.size} low-text / image-dense page(s)")
        }
      }
      val pageMetric = if (coverageReport != null) {
        CoverageMetric(
          label = "Page coverage",
          level = if (coverageReport.omittedPages.isEmpty()) CoverageLevel.COMPLETE else CoverageLevel.PARTIAL,
          detail = "${coverageReport.extractedPages}/${coverageReport.totalPages} pages"
        )
      } else {
        CoverageMetric("Page coverage", CoverageLevel.COMPLETE, "Direct text input")
      }
      val textMetric = CoverageMetric(
        label = "Text extraction",
        level = covSummary?.textExtractionCoverage ?: if (calibratedExtraction >= 4) CoverageLevel.COMPLETE else CoverageLevel.PARTIAL,
        detail = "Extraction Confidence $calibratedExtraction/5"
      )
      val quantMetric = CoverageMetric(
        label = "Quantitative reconstruction",
        level = covSummary?.quantitativeClaimCoverage ?: CoverageLevel.COMPLETE,
        detail = "${statisticalAudit?.reconstructions?.size ?: 0} check(s) reconstructed"
      )
      return ConfidenceSummary(
        scientificConfidence = scientificConfidence.coerceIn(1, 5),
        extractionConfidence = calibratedExtraction,
        pageCoverage = pageMetric,
        textCoverage = textMetric,
        quantitativeCoverage = quantMetric,
        reasons = reasons,
        coverageSummary = covSummary
      )
    }

    // =========================================================================
    // P0-01, P0-02, P0-04, P1-08 & P1-10: SCIENTIFIC TRUTH-PRESERVATION GATE
    // =========================================================================

    enum class EntailmentStatus {
      ENTAILED,
      PARTIAL_OR_AMBIGUOUS,
      UNRESOLVED,
      NOT_ENTAILED,
      CONTRADICTED_BY_POLARITY
    }

    /**
     * P0-10 Structured prose governance:
     * Verified factual claims in editorial prose must anchor to an active VERIFIED finding ID,
     * while unverified or partially-supported narrative items are explicitly quarantined under
     * [openQuestionsForInvestigation] so dismissed/unverified allegations cannot appear as facts.
     */
    data class AnchoredReportStatement(
      val text: String,
      val linkedFindingIds: List<String>,
      val isVerifiedAnchored: Boolean
    )

    data class ReportSection(
      val title: String,
      val verifiedStatements: List<AnchoredReportStatement>,
      val contextStatements: List<String>,
      val openQuestionsForInvestigation: List<String>,
      val strippedDismissedCount: Int
    ) {
      fun renderMarkdown(): String = buildString {
        if (verifiedStatements.isNotEmpty()) {
          verifiedStatements.forEach { stmt ->
            appendLine(stmt.text)
          }
        }
        if (contextStatements.isNotEmpty()) {
          if (isNotEmpty()) appendLine()
          contextStatements.forEach { appendLine(it) }
        }
        if (openQuestionsForInvestigation.isNotEmpty()) {
          if (isNotEmpty()) appendLine()
          appendLine("[QUESTIONS REQUIRING FURTHER INVESTIGATION (UNVERIFIED)]")
          openQuestionsForInvestigation.forEach { q ->
            val cleaned = q.removePrefix("•").removePrefix("-").trim()
            appendLine("• $cleaned")
          }
        }
      }.trim()
    }

    internal enum class ClaimPolarity {
      ABSENT_OR_DEFICIENT,
      PRESENT_OR_ADEQUATE,
      NEUTRAL
    }

    private fun classifyClaimPolarity(text: String): ClaimPolarity {
      // Mask prepositional domain noun phrases such as "for missing outcome data" or "handling missing values"
      // so a positive claim ("Sensitivity analysis for missing outcome data is present in Table 3") is not
      // misclassified as ABSENT_OR_DEFICIENT merely because its topic is missingness.
      val normalizedText = text.replace(
        Regex("""(?i)\b(?:for|of|with|on|regarding|handling|addressing|imputing|accounting\s+for)\s+missing(?:\s+[a-z0-9-]+){0,2}\s+(?:data|outcomes?|values?|observations?|cases?|covariates?|follow[\s-]?up|records?|entries)\b"""),
        " for_missingness_topic "
      )
      val absentRegex = Regex(
        """(?i)\b(?:absent|missing|omitted|not\s+(?:properly\s+|adequately\s+|fully\s+)?(?:performed|conducted|reported|assessed|included|adjusted|blinded|registered|validated|verified)|(?:was|were)\s+(?:not|never)\s+(?:properly\s+|adequately\s+|fully\s+)?(?:performed|conducted|reported|assessed|included|adjusted|blinded|registered|validated|verified)|did\s+not\s+(?:perform|conduct|report|assess|include|adjust|control|blind|preregister|validate|verify)|never\s+(?:performed|conducted|reported|assessed|blinded|adjusted|validated)|no\s+(?:sensitivity\s+analysis|blinding|allocation\s+concealment|adjustment|control|protocol|preregistration|calibration|external\s+validation)|failed\s+to|lacks?|lacked|without\s+(?:sensitivity|blinding|adjustment|control|calibration|correction)|unadjusted|unblinded|open[\s-]?label|unregistered|contradict(?:s|ed|ion)?|discrepancy|miscalculat(?:ed|ion)|invalid(?:ate|ated)?|arithmetic\s+error|leakage|overfit(?:ting)?|violat(?:es|ed|ion)|flaw(?:ed|s)?|bias(?:ed)?)\b"""
      )
      val presentRegex = Regex(
        """(?i)\b(?:(?:was|were)\s+(?:(?:properly|adequately|fully|rigorously|thoroughly|explicitly|appropriately|formally|successfully|clearly)\s+)?(?:performed|conducted|reported|assessed|included|adjusted|blinded|preregistered|registered|verified|confirmed|validated|implemented|executed)|(?:authors|study|investigators|trial|manuscript|paper)\s+(?:(?:properly|adequately|fully|rigorously|explicitly)\s+)?(?:performed|conducted|reported|included|adjusted|controlled|blinded|assessed|validated|verified|confirmed|provides?|includes?|contains?|presents?)|(?:is|are)\s+(?:present|included|reported|provided|documented)\s+in\b|appears?\s+in\s+(?:table|figure|section|methods|results|appendix|supplement)|performed\s+(?:a\s+|an\s+|the\s+)?(?:sensitivity|blinding|adjustment|calibration|external\s+validation|funnel|egger)|conducted\s+(?:a\s+|an\s+|the\s+)?(?:sensitivity|blinding|adjustment|calibration|external\s+validation)|confirmed\s+(?:robustness|consistency|validity|results)|adequately\s+(?:reported|controlled|adjusted|blinded|powered|conducted|performed|validated)|properly\s+(?:blinded|controlled|adjusted|randomized|conducted|performed|validated|assessed|reported)|no\s+(?:discrepancy|contradiction|bias|leakage|error|flaw)|fully\s+(?:consistent|reproducible|transparent|adjusted|validated)|well[\s-]?controlled|robust\s+to\s+sensitivity|clearly\s+(?:stated|described|reported|addressed|controlled))\b"""
      )
      val hasAbsent = absentRegex.containsMatchIn(normalizedText)
      val hasPresent = presentRegex.containsMatchIn(normalizedText)
      return when {
        hasAbsent && !hasPresent -> ClaimPolarity.ABSENT_OR_DEFICIENT
        hasPresent && !hasAbsent -> ClaimPolarity.PRESENT_OR_ADEQUATE
        hasAbsent && hasPresent -> {
          // Compare which cue appears closer to the main verb/clause: explicit negation ("not performed" / "no sensitivity analysis") dominates
          if (Regex("""(?i)\b(?:not\s+(?:properly\s+|adequately\s+|fully\s+)?(?:performed|conducted|reported|assessed|validated)|did\s+not|was\s+(?:not|never)|were\s+(?:not|never)|no\s+(?:sensitivity\s+analysis|blinding|allocation\s+concealment|adjustment|control|protocol|preregistration|calibration|external\s+validation)|absent|omitted|missing|failed\s+to|lacks?)\b""").containsMatchIn(normalizedText)) {
            ClaimPolarity.ABSENT_OR_DEFICIENT
          } else {
            ClaimPolarity.NEUTRAL
          }
        }
        else -> ClaimPolarity.NEUTRAL
      }
    }

    internal data class ScientificProposition(
      val polarity: ClaimPolarity,
      val significance: Boolean?, // true=significant, false=non-significant, null=unspecified
      val direction: Int, // +1=increase/harm/overestimate, -1=decrease/benefit/underestimate, 0=neutral
      val populations: Set<String>,
      val interventions: Set<String> = emptySet(),
      val adjustmentStatus: String?, // "ADJUSTED", "UNADJUSTED", or null
      val comparators: Set<String>,
      val namedComparators: Set<String> = emptySet(),
      val comparatorPairs: Set<Pair<String, String>> = emptySet(),
      val outcomes: Set<String> = emptySet(),
      val subgroupScope: String? = null, // "SUBGROUP", "OVERALL", or null
      val scaleTransform: String? = null, // "LOG_TRANSFORMED", "STANDARDIZED", "RAW", or null
      val ciLevelPct: Double? = null, // e.g., 90.0, 95.0, 99.0
      val inferenceMethod: String? = null, // "BOOTSTRAP", "WALD", "BAYESIAN", "PERMUTATION", "ROBUST_SANDWICH"
      val certaintyLevel: String? = null, // "HIGH", "MODERATE", "LOW", "VERY_LOW"
      val timePointDays: Set<Double>,
      val effectMeasures: Set<String>,
      val percentages: List<Double>,
      val pValues: List<Double>,
      val effectEstimates: List<Double>,
      val intervals: List<Pair<Double, Double>>,
      val sampleSizes: List<Int>
    )

    internal enum class PropositionCompatibility {
      COMPATIBLE,
      MISMATCH_CONTRADICTED,
      UNRESOLVED_INSUFFICIENT_FIELDS
    }

    internal data class PropositionComparison(
      val status: PropositionCompatibility,
      val reason: String = ""
    )

    internal fun extractQuoteContextWindow(
      manuscriptText: String,
      check: QuoteVerificationResult,
      fallbackQuote: String,
      radiusChars: Int = 180,
      excludeRange: IntRange? = null
    ): String {
      val start = check.startOffset
      val end = check.endOffset
      if (start == null || end == null || start < 0 || end <= start || end > manuscriptText.length) {
        return fallbackQuote
      }
      var windowStart = (start - radiusChars).coerceAtLeast(0)
      var windowEnd = (end + radiusChars).coerceAtMost(manuscriptText.length)
      // Never bleed across [PAGE N] section boundaries into another page's opposing framing
      val pageHeaderRegex = Regex("""\[PAGE\s+\d+]""")
      val lastHeaderBefore = pageHeaderRegex.findAll(manuscriptText.substring(windowStart, start))
        .lastOrNull()?.range?.last?.let { windowStart + it + 1 }
      if (lastHeaderBefore != null && lastHeaderBefore > windowStart) {
        windowStart = lastHeaderBefore
      }
      val firstHeaderAfter = pageHeaderRegex.find(manuscriptText.substring(end, windowEnd))
        ?.range?.first?.let { end + it }
      if (firstHeaderAfter != null && firstHeaderAfter < windowEnd) {
        windowEnd = firstHeaderAfter
      }
      // Never swallow a sibling passage being compared against this quote
      if (excludeRange != null && !excludeRange.isEmpty()) {
        if (excludeRange.last < start && excludeRange.last + 1 > windowStart) {
          windowStart = (excludeRange.last + 1).coerceAtMost(start)
        }
        if (excludeRange.first > end && excludeRange.first < windowEnd) {
          windowEnd = excludeRange.first.coerceAtLeast(end)
        }
      }
      return manuscriptText.substring(windowStart, windowEnd).trim()
    }

    /**
     * Detects contextual statistical qualifiers (non-standard CI levels, resampling/robust SEs,
     * scale transformations, imputation/weighting/propensity adjustments, one-tailed/multiplicity
     * adjustments) that prevent single-passage naive formula reconstruction from auto-promoting to VERIFIED.
     */
    internal fun hasConflictingReconstructionQualifiers(window: String): String? {
      if (window.isBlank()) return null
      val rules: List<Pair<String, Regex>> = listOf(
        "bootstrap/resampling inference" to Regex("""(?i)\b(?:bootstrap\w*|jackknife|resampl\w*|permutation\s+(?:test|ci|p[\s-]?value))\b"""),
        "robust/sandwich/cluster SE" to Regex("""(?i)\b(?:robust\s+(?:se|standard\s+errors?)|sandwich\s+(?:estimator|variance|se)|huber[\s-]?white|hc[0-3]\b|cluster[\s-]?robust)\b"""),
        "Bayesian credible interval" to Regex("""(?i)\b(?:bayesian|credible\s+interval|\bcri\b|posterior\s+(?:interval|distribution)|mcmc)\b"""),
        "log/standardized scale transformation" to Regex("""(?i)\b(?:log[\s-]?transformed|log10|log2|natural\s+log|\bln\s*\(|log[\s-]?scale|standardized|standardised|z[\s-]?score|per\s+(?:1\s+)?sd\b)\b"""),
        "non-95% confidence level" to Regex("""(?i)\b(?:80|90|99)(?:\.\d+)?%\s*(?:ci|confidence\s+interval|credible\s+interval|cri)\b"""),
        "weighted/imputed/propensity model" to Regex("""(?i)\b(?:multiple\s+imputation|imputed\s+data|inverse\s+probability\s+weight\w*|ipw\b|propensity[\s-]?(?:score|matched|weighted)|survey[\s-]?weighted|multivariable[\s-]?adjusted)\b"""),
        "one-sided or multiplicity-adjusted p-value" to Regex("""(?i)\b(?:one[\s-]?(?:sided|tailed)|bonferroni|holm[\s-]?bonferroni|sidak|benjamini[\s-]?hochberg|\bfdr\b|false\s+discovery\s+rate|adjusted\s+p[\s-]?value)\b""")
      )
      return rules.firstOrNull { (_, regex) -> regex.containsMatchIn(window) }?.first
    }

    /**
     * Checks whether two independently source-matched passages describe a compatible typed scientific
     * framing (population, intervention, comparator, outcome, adjustment status, subgroup/overall scope,
     * scale transformation, CI level, inference method, time point, effect measure) so cross-passage
     * numerical discrepancies are only promoted to VERIFIED when comparing apples to apples.
     */
    internal fun evaluateDualPassageTypedCompatibility(
      passage1Window: String,
      passage2Window: String,
      passage1Exact: String = passage1Window,
      passage2Exact: String = passage2Window
    ): PropositionComparison {
      if (passage1Exact.isNotBlank() && passage2Exact.isNotBlank()) {
        val exactComp = compareScientificPropositions(
          claimProp = extractScientificProposition(passage1Exact),
          evidenceProp = extractScientificProposition(passage2Exact),
          requireClaimCriticalFieldsInEvidence = false,
          compareNumericMagnitudes = false
        )
        if (exactComp.status != PropositionCompatibility.COMPATIBLE) {
          return exactComp
        }
      }
      val p1 = extractScientificProposition(passage1Window)
      val p2 = extractScientificProposition(passage2Window)
      return compareScientificPropositions(
        claimProp = p1,
        evidenceProp = p2,
        requireClaimCriticalFieldsInEvidence = false,
        compareNumericMagnitudes = false
      )
    }

    private fun normalizeArmLabel(raw: String): String {
      val leadStrip = Regex("""(?i)^(?:the|a|an|of|with|for|by|in|on|at|to|and|or|reduced|increased|improved|lowered|raised|showed|yielded|had|was|were|compared|comparing|receiving|given|assigned|treated)\s+""")
      val trailStrip = Regex("""(?i)\s+(?:in|at|on|for|with|among|by|was|were|had|has|have|reduced|increased|improved|mortality|death|survival|risk|rate|patients?|adults?|children|after|over|during)$""")
      var s = raw.trim().lowercase(Locale.US).replace(Regex("""\s+"""), " ")
      repeat(4) { s = s.replace(leadStrip, "").trim() }
      repeat(4) { s = s.replace(trailStrip, "").trim() }
      return s
    }

    internal fun extractScientificProposition(text: String): ScientificProposition {
      val clean = text.replace(Regex("""\[[A-Z]-\d{1,3}\]"""), " ").trim()
      val polarity = classifyClaimPolarity(clean)

      val hasSig = Regex("""(?i)\bstatistically\s+significant\b""").containsMatchIn(clean) &&
        !Regex("""(?i)\b(?:not|non)[\s-]+(?:statistically\s+)?significant\b""").containsMatchIn(clean)
      val hasNonSig = Regex("""(?i)\b(?:not|non)[\s-]+(?:statistically\s+)?significant\b""").containsMatchIn(clean)
      val significance = when {
        hasSig && !hasNonSig -> true
        hasNonSig && !hasSig -> false
        else -> null
      }

      val upRegex = Regex("""(?i)\b(?:overestimat\w*|inflat\w*|higher|increas\w*|positive\s+effect|harm(?:ful)?|worsen\w*|elevated\s+risk|excess\s+mortality)\b""")
      val downRegex = Regex("""(?i)\b(?:underestimat\w*|deflat\w*|lower|decreas\w*|reduc\w*|negative\s+effect|protective|beneficial|improved\s+survival)\b""")
      val hasUp = upRegex.containsMatchIn(clean)
      val hasDown = downRegex.containsMatchIn(clean)
      val direction = when {
        hasUp && !hasDown -> 1
        hasDown && !hasUp -> -1
        else -> 0
      }

      val populations = buildSet {
        if (Regex("""(?i)\b(?:adults?|elderly|older\s+adults?|geriatric|men\s+and\s+women|adult\s+patients?)\b""").containsMatchIn(clean)) add("ADULTS")
        if (Regex("""(?i)\b(?:children|child|pediatric|paediatric|infants?|neonates?|adolescents?|youth)\b""").containsMatchIn(clean)) add("PEDIATRIC")
        if (Regex("""(?i)\b(?:pregnant|maternal|obstetric|prenatal)\b""").containsMatchIn(clean)) add("PREGNANT")
        if (Regex("""(?i)\b(?:mice|mouse|rats?|murine|rodents?|canine|porcine|in\s+vitro|cell\s+lines?|animal\s+models?)\b""").containsMatchIn(clean)) add("ANIMAL_PRECLINICAL")
        if (Regex("""(?i)\b(?:healthy\s+(?:volunteers?|adults?|subjects?|controls?))\b""").containsMatchIn(clean)) add("HEALTHY_VOLUNTEERS")
      }

      val hasAdjusted = Regex("""(?i)\b(?:adjusted|multivariable|multivariate|propensity[\s-]?(?:score|matched|weighted)|controlling\s+for|aor|ahr)\b""").containsMatchIn(clean) &&
        !Regex("""(?i)\bnot\s+adjusted\b""").containsMatchIn(clean)
      val hasUnadjusted = Regex("""(?i)\b(?:unadjusted|crude|bivariate|univariate|without\s+adjustment|not\s+adjusted)\b""").containsMatchIn(clean)
      val adjustmentStatus = when {
        hasAdjusted && !hasUnadjusted -> "ADJUSTED"
        hasUnadjusted && !hasAdjusted -> "UNADJUSTED"
        else -> null
      }

      val comparators = buildSet {
        if (Regex("""(?i)\b(?:placebo|sham)\b""").containsMatchIn(clean)) add("PLACEBO")
        if (Regex("""(?i)\b(?:standard\s+of\s+care|usual\s+care|routine\s+care|wait[\s-]?list|no\s+treatment|untreated)\b""").containsMatchIn(clean)) add("STANDARD_CARE")
        if (Regex("""(?i)\b(?:active\s+(?:control|comparator|drug|therapy)|standard\s+(?:drug|therapy|chemotherapy)|metformin|statin|aspirin|warfarin|insulin|amoxicillin|prednisone)\b""").containsMatchIn(clean)) add("ACTIVE_CONTROL")
        if (Regex("""(?i)\b(?:surgery|surgical|operative|resection|angioplasty|stenting)\b""").containsMatchIn(clean)) add("SURGICAL")
        if (Regex("""(?i)\b(?:historical\s+control|external\s+control|single[\s-]?arm)\b""").containsMatchIn(clean)) add("HISTORICAL_CONTROL")
      }

      val interventions = mutableSetOf<String>()
      val namedComparators = mutableSetOf<String>()
      val comparatorPairs = mutableSetOf<Pair<String, String>>()

      val vsRegex = Regex(
        """(?i)\b([A-Za-z][A-Za-z0-9-]*(?:\s+[A-Za-z0-9-]+){0,2})\s+(?:vs\.?|versus|compared\s+(?:with|to))\s+([A-Za-z][A-Za-z0-9-]*(?:\s+[A-Za-z0-9-]+){0,2})\b"""
      )
      vsRegex.findAll(clean).forEach { m ->
        val left = normalizeArmLabel(m.groupValues[1])
        val right = normalizeArmLabel(m.groupValues[2])
        if (left.length >= 2 && right.length >= 2 && left != right) {
          comparatorPairs.add(left to right)
          namedComparators.add(right)
          if (left !in setOf("mortality", "risk", "rate", "effect", "outcome", "reduction", "increase")) {
            interventions.add(left)
          }
        }
      }
      val namedAgentRegex = Regex("""(?i)\b(?:drug|agent|compound|vaccine|regimen|therapy|treatment|arm)\s+([A-Z0-9]{1,4})\b""")
      namedAgentRegex.findAll(clean).forEach { m ->
        val full = m.value.lowercase(Locale.US).replace(Regex("""\s+"""), " ").trim()
        if (full !in namedComparators) {
          interventions.add(full)
        }
      }

      val outcomes = buildSet {
        if (Regex("""(?i)\b(?:mortality|deaths?|fatal\w*|survival)\b""").containsMatchIn(clean)) add("MORTALITY")
        if (Regex("""(?i)\b(?:hospitaliz\w*|readmissions?|inpatient\s+admissions?)\b""").containsMatchIn(clean)) add("HOSPITALIZATION")
        if (Regex("""(?i)\b(?:stroke|cerebrovascular)\b""").containsMatchIn(clean)) add("STROKE")
        if (Regex("""(?i)\b(?:myocardial\s+infarction|\bmi\b|heart\s+attack)\b""").containsMatchIn(clean)) add("MYOCARDIAL_INFARCTION")
        if (Regex("""(?i)\b(?:heart\s+failure)\b""").containsMatchIn(clean)) add("HEART_FAILURE")
        if (Regex("""(?i)\b(?:infections?|sepsis|pneumonia|bacteremia)\b""").containsMatchIn(clean)) add("INFECTION")
        if (Regex("""(?i)\b(?:bleeding|hemorrhag\w*|haemorrhag\w*)\b""").containsMatchIn(clean)) add("BLEEDING")
        if (Regex("""(?i)\b(?:relapse|recurrence|disease\s+progression)\b""").containsMatchIn(clean)) add("RELAPSE_RECURRENCE")
        if (Regex("""(?i)\b(?:adverse\s+events?|side\s+effects?|toxicity)\b""").containsMatchIn(clean)) add("ADVERSE_EVENT")
        if (Regex("""(?i)\b(?:length\s+of\s+stay|long[\s-]?stay|bed\s+days?|bed\s+estimate)\b""").containsMatchIn(clean)) add("LENGTH_OF_STAY")
        if (Regex("""(?i)\b(?:quality\s+of\s+life|\bqol\b|symptom\s+score)\b""").containsMatchIn(clean)) add("QUALITY_OF_LIFE")
        if (Regex("""(?i)\b(?:biomarker|hba1c|ldl|cholesterol|blood\s+pressure|viral\s+load)\b""").containsMatchIn(clean)) add("BIOMARKER_SURROGATE")
      }

      val hasSubgroup = Regex("""(?i)\b(?:subgroup|sub[\s-]?analysis|stratif\w*|post[\s-]?hoc|sensitivity\s+subset|per[\s-]?protocol|completers?\s+only)\b""").containsMatchIn(clean)
      val hasOverall = Regex("""(?i)\b(?:overall\s+(?:cohort|population|sample|analysis|effect|estimate|result)|entire\s+(?:cohort|population|sample)|full\s+(?:cohort|population|sample)|intention[\s-]?to[\s-]?treat|\bitt\b|all\s+randomized\s+patients?)\b""").containsMatchIn(clean)
      val subgroupScope = when {
        hasSubgroup && !hasOverall -> "SUBGROUP"
        hasOverall && !hasSubgroup -> "OVERALL"
        else -> null
      }

      val hasLogScale = Regex("""(?i)\b(?:log[\s-]?transformed|log10|log2|natural\s+log|\bln\s*\(|log[\s-]?scale|logarithmic)\b""").containsMatchIn(clean)
      val hasStdScale = Regex("""(?i)\b(?:standardized|standardised|z[\s-]?score|per\s+(?:1\s+)?sd\b|sd\s+unit)\b""").containsMatchIn(clean)
      val hasRawScale = Regex("""(?i)\b(?:untransformed|raw\s+scale|original\s+scale|natural\s+scale|unstandardized|unstandardised)\b""").containsMatchIn(clean)
      val scaleTransform = when {
        hasLogScale && !hasRawScale && !hasStdScale -> "LOG_TRANSFORMED"
        hasStdScale && !hasRawScale && !hasLogScale -> "STANDARDIZED"
        hasRawScale && !hasLogScale && !hasStdScale -> "RAW"
        else -> null
      }

      val ciLevelPct = Regex("""(?i)\b(80|90|95|99)(?:\.\d+)?%\s*(?:ci|confidence\s+interval|credible\s+interval|cri)\b""")
        .find(clean)?.groupValues?.get(1)?.toDoubleOrNull()

      val hasBootstrap = Regex("""(?i)\b(?:bootstrap\w*|resampl\w*|jackknife)\b""").containsMatchIn(clean)
      val hasBayesian = Regex("""(?i)\b(?:bayesian|credible\s+interval|\bcri\b|posterior\s+interval|mcmc)\b""").containsMatchIn(clean)
      val hasWald = Regex("""(?i)\b(?:wald\b|asymptotic\s+normal|normal\s+approximat\w*)\b""").containsMatchIn(clean) ||
        (!hasBayesian && Regex("""(?i)\b(?:\d+(?:\.\d+)?%\s*(?:ci\b|confidence\s+interval)|confidence\s+interval)\b""").containsMatchIn(clean))
      val hasPermutation = Regex("""(?i)\b(?:permutation|exact\s+test|fisher'?s?\s+exact)\b""").containsMatchIn(clean)
      val hasRobustSe = Regex("""(?i)\b(?:robust\s+(?:se|standard\s+errors?)|sandwich|huber[\s-]?white|hc[0-3]\b|cluster[\s-]?robust)\b""").containsMatchIn(clean)
      val activeMethods = buildList {
        if (hasBootstrap) add("BOOTSTRAP")
        if (hasWald) add("WALD")
        if (hasBayesian) add("BAYESIAN")
        if (hasPermutation) add("PERMUTATION")
        if (hasRobustSe) add("ROBUST_SANDWICH")
      }
      val inferenceMethod = if (activeMethods.size == 1) activeMethods.first() else null

      val certaintyLevel = when {
        Regex("""(?i)\bvery\s+low\s+(?:certainty|quality|confidence)\b""").containsMatchIn(clean) -> "VERY_LOW"
        Regex("""(?i)(?<!very\s)\blow\s+(?:certainty|quality|confidence)\b""").containsMatchIn(clean) -> "LOW"
        Regex("""(?i)\bmoderate\s+(?:certainty|quality|confidence)\b""").containsMatchIn(clean) -> "MODERATE"
        Regex("""(?i)\bhigh\s+(?:certainty|quality|confidence)\b""").containsMatchIn(clean) -> "HIGH"
        else -> null
      }

      val timePointDays = buildSet {
        val timeRegex = Regex("""(?i)\b(\d+(?:\.\d+)?)\s*[-]?\s*(hours?|hrs?|days?|weeks?|wks?|months?|mos?|years?|yrs?)\b""")
        timeRegex.findAll(clean).forEach { m ->
          val qty = m.groupValues[1].toDoubleOrNull() ?: return@forEach
          val unit = m.groupValues[2].lowercase(Locale.US)
          val days = when {
            unit.startsWith("h") -> qty / 24.0
            unit.startsWith("d") -> qty
            unit.startsWith("w") -> qty * 7.0
            unit.startsWith("m") -> qty * 30.0
            unit.startsWith("y") -> qty * 365.0
            else -> return@forEach
          }
          add(days)
        }
      }

      val effectMeasures = buildSet {
        if (Regex("""(?i)\b(?:hazard\s+ratio|a?hr)\b""").containsMatchIn(clean)) add("HR")
        if (Regex("""(?i)\b(?:odds\s+ratio|a?or)\b""").containsMatchIn(clean)) add("OR")
        if (Regex("""(?i)\b(?:risk\s+ratio|relative\s+risk|a?rr)\b""").containsMatchIn(clean)) add("RR")
        if (Regex("""(?i)\b(?:mean\s+difference|smd|md)\b""").containsMatchIn(clean)) add("MD")
        if (Regex("""(?i)\b(?:auroc|auc|c[\s-]?statistic)\b""").containsMatchIn(clean)) add("AUC")
      }

      val percentages = Regex("""(?<![\d.])(\d+(?:\.\d+)?)\s*%(?!\s*(?:ci\b|cri\b|confidence\b|credible\b))""", RegexOption.IGNORE_CASE)
        .findAll(clean)
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }
        .toList()

      val pValues = Regex("""(?i)\bp(?:-?value)?\s*[=<>≤≥]\s*(0?\.\d+)""")
        .findAll(clean)
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }
        .toList()

      val intervals = Regex("""(?i)(?:(?:80|90|95|99)(?:\.\d+)?%\s*(?:ci|cri)|ci|confidence\s+interval)\s*[:=]?\s*[\(\[]?\s*([-+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*([-+]?\d+(?:\.\d+)?)\s*[\)\]]?""")
        .findAll(clean)
        .mapNotNull { m ->
          val lo = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
          val hi = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
          lo to hi
        }
        .toList()

      val sampleSizes = Regex("""(?i)\b(?:[nN]\s*=\s*(\d{1,6})|(\d{2,6})\s+(?:patients?|participants?|subjects?|adults?|children|individuals?))\b""")
        .findAll(clean)
        .mapNotNull { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() }
        .toList()

      val effectEstimates = Regex("""(?i)\b(?:a?hr|a?or|a?rr|irr|pr|smd|md|auc|auroc|beta|β|r|rho)\s*(?:=|of|was)?\s*([-+]?\d+(?:\.\d+)?)""")
        .findAll(clean)
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }
        .toList()

      return ScientificProposition(
        polarity = polarity,
        significance = significance,
        direction = direction,
        populations = populations,
        interventions = interventions,
        adjustmentStatus = adjustmentStatus,
        comparators = comparators,
        namedComparators = namedComparators,
        comparatorPairs = comparatorPairs,
        outcomes = outcomes,
        subgroupScope = subgroupScope,
        scaleTransform = scaleTransform,
        ciLevelPct = ciLevelPct,
        inferenceMethod = inferenceMethod,
        certaintyLevel = certaintyLevel,
        timePointDays = timePointDays,
        effectMeasures = effectMeasures,
        percentages = percentages,
        pValues = pValues,
        effectEstimates = effectEstimates,
        intervals = intervals,
        sampleSizes = sampleSizes
      )
    }

    /**
     * Typed Scientific Proposition Comparison Engine (P0):
     * Evaluates compatibility across polarity, significance, direction, population, intervention,
     * comparator/ordered-arm pair, outcome, adjustment status, subgroup/overall scope, scale transform,
     * CI level, inference method, certainty level, follow-up time point, effect measure, and numeric magnitudes.
     */
    internal fun compareScientificPropositions(
      claimProp: ScientificProposition,
      evidenceProp: ScientificProposition,
      requireClaimCriticalFieldsInEvidence: Boolean,
      compareNumericMagnitudes: Boolean = true,
      requireAllClaimNumerics: Boolean = true
    ): PropositionComparison {
      // 1. Polarity / Method-Status opposition
      if ((claimProp.polarity == ClaimPolarity.PRESENT_OR_ADEQUATE && evidenceProp.polarity == ClaimPolarity.ABSENT_OR_DEFICIENT) ||
        (claimProp.polarity == ClaimPolarity.ABSENT_OR_DEFICIENT && evidenceProp.polarity == ClaimPolarity.PRESENT_OR_ADEQUATE)
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "polarity mismatch (${claimProp.polarity} vs ${evidenceProp.polarity})")
      }

      // 2. Significance & directional opposition
      if (claimProp.significance != null && evidenceProp.significance != null && claimProp.significance != evidenceProp.significance) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "statistical significance mismatch")
      }
      if (claimProp.direction != 0 && evidenceProp.direction != 0 && claimProp.direction != evidenceProp.direction) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "effect direction mismatch")
      }

      // 3. Typed Population consistency (adults vs children, pregnant, animal, etc.)
      if (claimProp.populations.isNotEmpty() && evidenceProp.populations.isNotEmpty() &&
        claimProp.populations.intersect(evidenceProp.populations).isEmpty()
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "population mismatch (${claimProp.populations} vs ${evidenceProp.populations})")
      }
      val restrictiveSubpops = setOf("PEDIATRIC", "PREGNANT", "ANIMAL_PRECLINICAL")
      if (claimProp.populations.any { it in restrictiveSubpops } &&
        evidenceProp.populations.intersect(claimProp.populations).isEmpty()
      ) {
        return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "restrictive population ${claimProp.populations} not confirmed in evidence")
      }
      if (requireClaimCriticalFieldsInEvidence && claimProp.populations.isNotEmpty() && evidenceProp.populations.isEmpty()) {
        return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "population ${claimProp.populations} missing from evidence")
      }

      // 4. Intervention & ordered comparator-arm consistency (catches Drug A vs placebo vs Drug A vs Drug B / Placebo vs Drug A)
      if (claimProp.comparatorPairs.isNotEmpty() && evidenceProp.comparatorPairs.isNotEmpty()) {
        val anyExactPair = claimProp.comparatorPairs.any { (cL, cR) ->
          evidenceProp.comparatorPairs.any { (eL, eR) ->
            (cL == eL || cL.contains(eL) || eL.contains(cL)) &&
              (cR == eR || cR.contains(eR) || eR.contains(cR))
          }
        }
        if (!anyExactPair) {
          return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "comparator arm pair mismatch (${claimProp.comparatorPairs} vs ${evidenceProp.comparatorPairs})")
        }
      }
      if (claimProp.interventions.isNotEmpty() && evidenceProp.interventions.isNotEmpty()) {
        val anySharedIntervention = claimProp.interventions.any { ci ->
          evidenceProp.interventions.any { ei -> ci == ei || ci.contains(ei) || ei.contains(ci) }
        }
        if (!anySharedIntervention) {
          return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "intervention mismatch (${claimProp.interventions} vs ${evidenceProp.interventions})")
        }
      }
      if (claimProp.comparators.isNotEmpty() && evidenceProp.comparators.isNotEmpty() &&
        claimProp.comparators.intersect(evidenceProp.comparators).isEmpty()
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "comparator class mismatch (${claimProp.comparators} vs ${evidenceProp.comparators})")
      }
      if (claimProp.namedComparators.isNotEmpty() && evidenceProp.namedComparators.isNotEmpty()) {
        val anySharedNamed = claimProp.namedComparators.any { cc ->
          evidenceProp.namedComparators.any { ec -> cc == ec || cc.contains(ec) || ec.contains(cc) }
        }
        if (!anySharedNamed) {
          return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "named comparator mismatch (${claimProp.namedComparators} vs ${evidenceProp.namedComparators})")
        }
      }
      if (requireClaimCriticalFieldsInEvidence &&
        (claimProp.comparators.isNotEmpty() || claimProp.namedComparators.isNotEmpty()) &&
        evidenceProp.comparators.isEmpty() && evidenceProp.namedComparators.isEmpty()
      ) {
        return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "comparator arm missing from evidence")
      }

      // 5. Outcome consistency
      if (claimProp.outcomes.isNotEmpty() && evidenceProp.outcomes.isNotEmpty() &&
        claimProp.outcomes.intersect(evidenceProp.outcomes).isEmpty()
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "outcome mismatch (${claimProp.outcomes} vs ${evidenceProp.outcomes})")
      }

      // 6. Adjusted vs Unadjusted consistency
      if (claimProp.adjustmentStatus != null && evidenceProp.adjustmentStatus != null &&
        claimProp.adjustmentStatus != evidenceProp.adjustmentStatus
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "adjustment mismatch (${claimProp.adjustmentStatus} vs ${evidenceProp.adjustmentStatus})")
      }
      if (requireClaimCriticalFieldsInEvidence && claimProp.adjustmentStatus != null && evidenceProp.adjustmentStatus == null) {
        return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "adjustment status (${claimProp.adjustmentStatus}) missing from evidence")
      }

      // 7. Subgroup vs Overall scope consistency
      if (claimProp.subgroupScope != null && evidenceProp.subgroupScope != null &&
        claimProp.subgroupScope != evidenceProp.subgroupScope
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "subgroup vs overall scope mismatch (${claimProp.subgroupScope} vs ${evidenceProp.subgroupScope})")
      }

      // 8. Scale / transformation consistency (log-transformed / standardized / raw)
      if (claimProp.scaleTransform != null && evidenceProp.scaleTransform != null &&
        claimProp.scaleTransform != evidenceProp.scaleTransform
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "scale/transformation mismatch (${claimProp.scaleTransform} vs ${evidenceProp.scaleTransform})")
      }

      // 9. Confidence interval level consistency (90% vs 95% vs 99%)
      if (claimProp.ciLevelPct != null && evidenceProp.ciLevelPct != null &&
        kotlin.math.abs(claimProp.ciLevelPct - evidenceProp.ciLevelPct) > 0.1
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "CI level mismatch (${claimProp.ciLevelPct}% vs ${evidenceProp.ciLevelPct}%)")
      }

      // 10. Inference method consistency (bootstrap vs Wald vs Bayesian vs robust SE)
      if (claimProp.inferenceMethod != null && evidenceProp.inferenceMethod != null &&
        claimProp.inferenceMethod != evidenceProp.inferenceMethod
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "inference method mismatch (${claimProp.inferenceMethod} vs ${evidenceProp.inferenceMethod})")
      }

      // 11. GRADE certainty level consistency
      if (claimProp.certaintyLevel != null && evidenceProp.certaintyLevel != null &&
        claimProp.certaintyLevel != evidenceProp.certaintyLevel
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "certainty level mismatch (${claimProp.certaintyLevel} vs ${evidenceProp.certaintyLevel})")
      }

      // 12. Follow-up / time-point consistency (e.g., 6 months vs 12 months)
      if (claimProp.timePointDays.isNotEmpty() && evidenceProp.timePointDays.isNotEmpty()) {
        val matchesTime = { cDays: Double ->
          evidenceProp.timePointDays.any { eDays ->
            val tol = maxOf(2.0, 0.15 * maxOf(cDays, eDays))
            kotlin.math.abs(cDays - eDays) <= tol
          }
        }
        val ok = if (requireAllClaimNumerics) claimProp.timePointDays.all(matchesTime) else claimProp.timePointDays.any(matchesTime)
        if (!ok) {
          return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "time-point mismatch (${claimProp.timePointDays} vs ${evidenceProp.timePointDays} days)")
        }
      } else if (requireClaimCriticalFieldsInEvidence && claimProp.timePointDays.isNotEmpty() && evidenceProp.timePointDays.isEmpty()) {
        return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "time-point missing from evidence")
      }

      // 13. Effect measure consistency (HR vs OR vs RR vs MD vs AUC)
      if (claimProp.effectMeasures.isNotEmpty() && evidenceProp.effectMeasures.isNotEmpty() &&
        claimProp.effectMeasures.intersect(evidenceProp.effectMeasures).isEmpty()
      ) {
        return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "effect measure mismatch (${claimProp.effectMeasures} vs ${evidenceProp.effectMeasures})")
      }

      if (!compareNumericMagnitudes) {
        return PropositionComparison(PropositionCompatibility.COMPATIBLE)
      }

      // 14. Typed numerical magnitude consistency (rejects 5% vs 50%, mismatched OR/HR/CI/p-value/N)
      if (claimProp.percentages.isNotEmpty()) {
        if (evidenceProp.percentages.isEmpty()) {
          if (requireClaimCriticalFieldsInEvidence) {
            return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "percentage magnitude missing from evidence")
          }
        } else {
          val matchPct = { cPct: Double ->
            evidenceProp.percentages.any { ePct ->
              val tol = maxOf(0.25, 0.05 * kotlin.math.abs(ePct))
              kotlin.math.abs(cPct - ePct) <= tol
            }
          }
          val ok = if (requireAllClaimNumerics) claimProp.percentages.all(matchPct) else claimProp.percentages.any(matchPct)
          if (!ok) {
            return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "percentage magnitude mismatch (${claimProp.percentages} vs ${evidenceProp.percentages})")
          }
        }
      }
      if (claimProp.effectEstimates.isNotEmpty()) {
        if (evidenceProp.effectEstimates.isEmpty()) {
          if (requireClaimCriticalFieldsInEvidence) {
            return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "effect estimate missing from evidence")
          }
        } else {
          val matchEst = { cEst: Double ->
            evidenceProp.effectEstimates.any { eEst ->
              val tol = maxOf(0.03, 0.05 * kotlin.math.abs(eEst))
              kotlin.math.abs(cEst - eEst) <= tol
            }
          }
          val ok = if (requireAllClaimNumerics) claimProp.effectEstimates.all(matchEst) else claimProp.effectEstimates.any(matchEst)
          if (!ok) {
            return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "effect estimate mismatch (${claimProp.effectEstimates} vs ${evidenceProp.effectEstimates})")
          }
        }
      }
      if (claimProp.intervals.isNotEmpty()) {
        if (evidenceProp.intervals.isEmpty()) {
          if (requireClaimCriticalFieldsInEvidence) {
            return PropositionComparison(PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS, "confidence interval missing from evidence")
          }
        } else {
          val matchCi = { pair: Pair<Double, Double> ->
            val (cLo, cHi) = pair
            evidenceProp.intervals.any { (eLo, eHi) ->
              val tolLo = maxOf(0.03, 0.05 * kotlin.math.abs(eLo))
              val tolHi = maxOf(0.03, 0.05 * kotlin.math.abs(eHi))
              kotlin.math.abs(cLo - eLo) <= tolLo && kotlin.math.abs(cHi - eHi) <= tolHi
            }
          }
          val ok = if (requireAllClaimNumerics) claimProp.intervals.all(matchCi) else claimProp.intervals.any(matchCi)
          if (!ok) {
            return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "confidence interval mismatch")
          }
        }
      }
      if (claimProp.pValues.isNotEmpty() && evidenceProp.pValues.isNotEmpty()) {
        val matchP = { cP: Double ->
          evidenceProp.pValues.any { eP ->
            val sameSideOfAlpha = (cP < 0.05) == (eP < 0.05)
            val tol = maxOf(0.002, 0.25 * maxOf(cP, eP))
            sameSideOfAlpha && kotlin.math.abs(cP - eP) <= tol
          }
        }
        val ok = if (requireAllClaimNumerics) claimProp.pValues.all(matchP) else claimProp.pValues.any(matchP)
        if (!ok) {
          return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "p-value mismatch")
        }
      }
      if (claimProp.sampleSizes.isNotEmpty() && evidenceProp.sampleSizes.isNotEmpty()) {
        val matchN = { cN: Int ->
          evidenceProp.sampleSizes.any { eN ->
            val tol = maxOf(1.0, 0.02 * eN.toDouble())
            kotlin.math.abs(cN - eN) <= tol
          }
        }
        val ok = if (requireAllClaimNumerics) claimProp.sampleSizes.all(matchN) else claimProp.sampleSizes.any(matchN)
        if (!ok) {
          return PropositionComparison(PropositionCompatibility.MISMATCH_CONTRADICTED, "sample size mismatch (${claimProp.sampleSizes} vs ${evidenceProp.sampleSizes})")
        }
      }

      return PropositionComparison(PropositionCompatibility.COMPATIBLE)
    }

    /**
     * Finding 3 (P0) Typed Scientific Proposition Consistency Gate:
     * Treats finding IDs as pointers, NOT proof. Extracts typed scientific propositions
     * (polarity, direction, significance, population, intervention, comparator, outcome,
     * adjustment status, subgroup/overall scope, scale transform, CI level, inference method,
     * follow-up time point, effect measure, percentages, intervals, p-values, sample sizes)
     * and rejects any mismatch or missing critical field before promoting prose as verified.
     */
    internal fun isProseSemanticallyConsistentWithFinding(
      sentence: String,
      finding: ReviewFinding
    ): Boolean {
      val cleanSentence = sentence.replace(Regex("""\[[A-Z]-\d{1,3}\]"""), " ").trim()
      if (cleanSentence.length < 8) return false
      val findingText = "${finding.title}. ${finding.allegation}"

      val sentProp = extractScientificProposition(cleanSentence)
      val findProp = extractScientificProposition(findingText)
      val comparison = compareScientificPropositions(
        claimProp = sentProp,
        evidenceProp = findProp,
        requireClaimCriticalFieldsInEvidence = true,
        compareNumericMagnitudes = true,
        requireAllClaimNumerics = true
      )
      if (comparison.status != PropositionCompatibility.COMPATIBLE) {
        return false
      }

      // Domain / Subject scope overlap: prevent unrelated prose from borrowing a verified ID
      val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "were", "was", "are", "not", "but",
        "have", "has", "had", "into", "their", "they", "study", "paper", "authors", "reported",
        "analysis", "results", "data", "model", "method", "methods"
      )
      fun contentTokens(s: String): Set<String> = s.lowercase(Locale.US)
        .split(Regex("""[^a-z0-9²]+"""))
        .filter { it.length >= 4 && it !in stopWords }
        .toSet()
      val sentTokens = contentTokens(cleanSentence)
      val findTokens = contentTokens(findingText)
      val numericRegex = Regex("""\b\d+(?:\.\d+)?%?\b""")
      val sentNums = numericRegex.findAll(cleanSentence).map { it.value }.toSet()
      val findNums = numericRegex.findAll(findingText).map { it.value }.toSet()
      val sharedNums = sentNums.intersect(findNums).size
      val sharedTokens = sentTokens.intersect(findTokens).size

      return sharedTokens >= 2 || (sharedTokens >= 1 && sharedNums >= 1)
    }

    /**
     * Finding 4 (P0): Classifies whether an unanchored prose sentence makes a substantive
     * scientific, clinical, methodological, quantitative, or editorial claim (either negative
     * criticism OR affirmative efficacy/safety/causality/adequacy/certainty assertion) that
     * MUST NOT enter unverified editorial [ReportSection.contextStatements].
     */
    internal fun isSubstantiveScientificAssertion(line: String): Boolean {
      val trimmed = line.trim()
      if (trimmed.isEmpty()) return false
      // Explicit counterevidence/dismissal provenance disclosures are allowed as neutral context
      if (trimmed.contains("dismissed", ignoreCase = true) ||
        trimmed.contains("counterevidence", ignoreCase = true) ||
        trimmed.startsWith("Scope:", ignoreCase = true) ||
        trimmed.startsWith("Context:", ignoreCase = true)
      ) {
        return false
      }
      val negativeAllegationCueRegex = Regex(
        """(?i)\b(?:flaw|error|contradict|invalid|fabricat|omitted|missing|failed\s+to|did\s+not|bias(?:ed)?|confound\w*|leakage|overfit\w*|unadjusted|violat\w*|miscalculat\w*|discrepancy|underpowered|unreliable|unproven|inconsistent)\b"""
      )
      val affirmativeScientificClaimRegex = Regex(
        """(?i)\b(?:safe(?:ty)?|effective(?:ness)?|efficac\w+|beneficial|clinical\s+benefit|superior(?:ity)?|inferior(?:ity)?|non[\s-]?inferior\w*|improves?|improved|reduces?|reduced|increases?|increased|prevents?|prevented|cures?|cured|protective|causes?|caused|causal(?:ity)?|associated\s+with|demonstrates?|demonstrated|proves?|proved|proven|confirms?|confirmed|establishes?|established|statistically\s+significant|significant(?:ly)?|well[\s-]?tolerated|high\s+certainty|moderate\s+certainty|robust(?:ness)?|rigorous|well[\s-]?designed|well[\s-]?controlled|methodologically\s+sound|valid(?:ated)?|reliable|reproducible|trustworthy|credible|adequately\s+(?:powered|blinded|controlled|adjusted|reported)|properly\s+(?:randomized|blinded|controlled|adjusted)|free\s+of\s+bias|no\s+bias|recommend(?:s|ed)?\s+(?:accept\w*|adoption|clinical\s+use|implementation)|ready\s+for\s+(?:publication|clinical\s+use|practice)|supports?\s+(?:clinical\s+adoption|publication|approval))\b"""
      )
      val quantitativeClaimRegex = Regex(
        """(?i)(?:\b(?:a?hr|a?or|a?rr|smd|md|auroc|auc)\s*[=:]?\s*\d|\b95%\s*ci\b|\bp(?:-?value)?\s*[=<>≤≥]\s*0?\.\d+|\b\d+(?:\.\d+)?\s*%)"""
      )
      return negativeAllegationCueRegex.containsMatchIn(trimmed) ||
        affirmativeScientificClaimRegex.containsMatchIn(trimmed) ||
        quantitativeClaimRegex.containsMatchIn(trimmed)
    }

    internal fun buildEvidenceGovernedReportSection(
      title: String,
      rawProse: String,
      adjudicatedFindings: List<ReviewFinding>
    ): ReportSection {
      if (rawProse.isBlank()) {
        return ReportSection(title, emptyList(), emptyList(), emptyList(), 0)
      }
      val afterDismissed = sanitizeProseAgainstDismissedFindings(rawProse, adjudicatedFindings)
      val strippedDismissedCount = (rawProse.lines().count { it.isNotBlank() } -
        afterDismissed.lines().count { it.isNotBlank() }).coerceAtLeast(0)

      val verifiedFindings = adjudicatedFindings.filter { it.status == FindingStatus.VERIFIED }
      val unverifiedOrPartialFindings = adjudicatedFindings.filter {
        it.status == FindingStatus.UNVERIFIED || it.status == FindingStatus.PARTIALLY_SUPPORTED
      }
      val verifiedById = verifiedFindings.associateBy { it.id.uppercase(Locale.US) }
      val unverifiedIdSet = unverifiedOrPartialFindings.map { it.id.uppercase(Locale.US) }.toSet()
      val findingIdRegex = Regex("""\[([A-Z]-\d{1,3})\]""")
      val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "were", "was", "are", "not", "but",
        "study", "paper", "authors", "missing", "lacks", "omitted", "reported", "analysis", "results"
      )
      fun tokenize(s: String): Set<String> = s.lowercase(Locale.US)
        .split(Regex("""[^a-z0-9²]+"""))
        .filter { it.length >= 4 && it !in stopWords }
        .toSet()

      val verifiedTokenMap = verifiedFindings.associate { it.id to tokenize("${it.title} ${it.allegation}") }
      val unverifiedTokenMap = unverifiedOrPartialFindings.associate { it.id to tokenize("${it.title} ${it.allegation}") }

      val verifiedStmts = mutableListOf<AnchoredReportStatement>()
      val contextStmts = mutableListOf<String>()
      val openQuestions = mutableListOf<String>()

      afterDismissed.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        if (line.startsWith("[") && line.endsWith("]")) {
          contextStmts.add(line)
          return@forEach
        }
        val explicitIds = findingIdRegex.findAll(line).map { it.groupValues[1].uppercase(Locale.US) }.distinct().toList()
        val explicitVerifiedIds = explicitIds.filter { it in verifiedById }
        val matchedUnverifiedIds = explicitIds.filter { it in unverifiedIdSet }

        // Finding 3: Explicit finding IDs are pointers, NOT proof — verify typed proposition consistency!
        val semanticallyConsistentVerifiedIds = explicitVerifiedIds.filter { id ->
          val target = verifiedById[id]
          target != null && isProseSemanticallyConsistentWithFinding(line, target)
        }.toMutableList()
        val hadContradictedExplicitPointer = explicitVerifiedIds.isNotEmpty() && semanticallyConsistentVerifiedIds.isEmpty()

        var hadContradictedImplicitOverlap = false
        if (explicitVerifiedIds.isEmpty() && matchedUnverifiedIds.isEmpty() && adjudicatedFindings.isNotEmpty()) {
          val lineTokens = tokenize(line)
          verifiedTokenMap.forEach { (id, fTokens) ->
            if (fTokens.size >= 2) {
              val hits = lineTokens.intersect(fTokens).size
              if (hits >= 3 && hits.toDouble() / fTokens.size.toDouble() >= 0.40) {
                val target = verifiedById[id.uppercase(Locale.US)]
                if (target != null && isProseSemanticallyConsistentWithFinding(line, target)) {
                  semanticallyConsistentVerifiedIds.add(id)
                } else {
                  hadContradictedImplicitOverlap = true
                }
              }
            }
          }
          if (semanticallyConsistentVerifiedIds.isEmpty() && !hadContradictedImplicitOverlap) {
            val matchesUnverified = unverifiedTokenMap.values.any { uTokens ->
              if (uTokens.size < 2) false
              else {
                val hits = lineTokens.intersect(uTokens).size
                hits >= 3 && hits.toDouble() / uTokens.size.toDouble() >= 0.40
              }
            }
            if (matchesUnverified) {
              openQuestions.add(line)
              return@forEach
            }
          }
        }

        when {
          semanticallyConsistentVerifiedIds.isNotEmpty() -> {
            val idsSorted = semanticallyConsistentVerifiedIds.distinct()
            val anchoredLine = if (explicitVerifiedIds.any { it in idsSorted }) {
              line
            } else {
              "[${idsSorted.joinToString(", ")}] $line"
            }
            verifiedStmts.add(
              AnchoredReportStatement(
                text = anchoredLine,
                linkedFindingIds = idsSorted,
                isVerifiedAnchored = true
              )
            )
          }
          hadContradictedExplicitPointer || hadContradictedImplicitOverlap -> {
            // Quarantine opposing/misaligned prose as provisional open question and strip false verified ID tag
            val strippedLine = findingIdRegex.replace(line, "").replace(Regex("""\s+"""), " ").trim()
            openQuestions.add("Provisional / Semantic Conflict Requiring Verification: $strippedLine")
          }
          matchedUnverifiedIds.isNotEmpty() -> {
            openQuestions.add(line)
          }
          // Finding 4: Quarantine ALL unanchored substantive scientific assertions (BOTH positive
          // efficacy/safety/causality/adequacy claims AND negative methodological criticisms),
          // even when adjudicatedFindings is empty, so unsupported claims never slip into contextStatements.
          isSubstantiveScientificAssertion(line) -> {
            openQuestions.add("Provisional (Unverified Assertion): $line")
          }
          else -> {
            contextStmts.add(line)
          }
        }
      }

      return ReportSection(
        title = title,
        verifiedStatements = verifiedStmts,
        contextStatements = contextStmts,
        openQuestionsForInvestigation = openQuestions,
        strippedDismissedCount = strippedDismissedCount
      )
    }

    /**
     * P1-13 Final Verdict / Epistemic / GRADE Reconciliation Gate:
     * Ensures the final persisted verdict is strictly consistent with the FINAL post-adjudication
     * extractionConfidence, finalEpistemic stop-and-ask state, and final clinical GRADE assessment.
     */
    internal fun reconcileFinalVerdictWithEpistemicState(
      adjudicatedVerdict: String,
      clinicalGradeAssessment: GradeCertaintyAssessment?,
      finalEpistemic: EpistemicSafeguardReport?,
      finalExtractionConfidence: Int,
      reviewMode: ReviewMode
    ): String {
      if (finalEpistemic?.stopAndAsk?.shouldStopAndAsk == true) {
        return "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      }
      if (finalExtractionConfidence <= 2 &&
        adjudicatedVerdict in setOf("ACCEPT", "HIGH_CREDIBILITY", "HIGH_CERTAINTY", "MODERATE_CERTAINTY")
      ) {
        return "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      }
      if (reviewMode == ReviewMode.CLINICAL_EVIDENCE_APPRAISAL && clinicalGradeAssessment != null) {
        if (!clinicalGradeAssessment.isAssessable) {
          return "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
        }
        if (adjudicatedVerdict != "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT" &&
          adjudicatedVerdict != "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN"
        ) {
          return clinicalGradeAssessment.verdictEnum
        }
      }
      return adjudicatedVerdict
    }

    data class DocumentReadinessResult(
      val isReady: Boolean,
      val blockingReasons: List<String>,
      val requestedActions: List<String>
    )

    enum class GradeDomainConcern(val label: String, val downgradeSteps: Int) {
      NOT_SERIOUS("Not serious (0)", 0),
      SERIOUS("Serious (-1)", 1),
      VERY_SERIOUS("Very serious (-2)", 2),
      NOT_ASSESSABLE("Not assessable", 0)
    }

    data class OutcomeEvidenceProfile(
      val outcomeName: String,
      val effectMeasure: String,
      val pointEstimate: Double?,
      val ciLower: Double?,
      val ciUpper: Double?,
      val isDirectClinicalOutcome: Boolean,
      val sourceSentence: String
    )

    data class GradeCertaintyAssessment(
      val startingLevel: String,
      val startingRank: Int, // 3=HIGH (RCT), 1=LOW (Observational), -1=NOT_ASSESSABLE
      val riskOfBias: GradeDomainConcern,
      val inconsistency: GradeDomainConcern,
      val indirectness: GradeDomainConcern,
      val imprecision: GradeDomainConcern,
      val publicationBias: GradeDomainConcern,
      val verdictEnum: String,
      val isAssessable: Boolean,
      val rationale: String,
      val outcomeProfiles: List<OutcomeEvidenceProfile> = emptyList(),
      val assessmentScopeLabel: String = "Automated Preliminary Clinical Certainty Screen (Non-Substitutive for Formal Multi-Reviewer GRADE)"
    ) {
      fun toSummaryBlock(): String = buildString {
        appendLine("Scope: $assessmentScopeLabel")
        if (outcomeProfiles.isNotEmpty()) {
          val profileSummary = outcomeProfiles.take(3).joinToString("; ") { p ->
            val ciPart = if (p.ciLower != null && p.ciUpper != null) " (95% CI ${p.ciLower}–${p.ciUpper})" else ""
            val estPart = p.pointEstimate?.let { " ${p.effectMeasure}=$it$ciPart" } ?: ""
            "${p.outcomeName}$estPart"
          }
          appendLine("• Extracted Outcome Profile(s): $profileSummary")
        }
        if (!isAssessable) {
          appendLine("Preliminary Certainty Screen: NOT ASSESSABLE ($verdictEnum)")
          appendLine("• Reason: $rationale")
          append("• Governance Note: The 6-axis weighted appraisal score is an internal methodological summary and is NOT substituted for GRADE clinical certainty.")
        } else {
          appendLine("Preliminary Outcome-Specific Certainty Screen: ${verdictEnum.replace('_', ' ')} (Starting design level: $startingLevel)")
          appendLine("• Risk of Bias: ${riskOfBias.label} | Inconsistency: ${inconsistency.label} | Indirectness: ${indirectness.label} | Imprecision: ${imprecision.label} | Publication Bias: ${publicationBias.label}")
          appendLine("• Rationale: $rationale")
          append("• Governance Note: Automated preliminary domain screen derived from clinical evidence domains; formal GRADE certainty requires independent multi-reviewer outcome-by-outcome consensus.")
        }
      }
    }

    /**
     * P1-12 Pre-Inference Document-Readiness Gate:
     * Prevents burning full multi-pass LLM inference on unreadable or partially-rendered scanned
     * PDFs (requiring 100% page coverage or full OCR for any scanned PDF) or empty inputs.
     */
    internal fun evaluateDocumentReadinessGate(
      manuscriptText: String,
      coverageReport: DocumentCoverageReport?,
      attachedBitmapCount: Int,
      useExternalTextOnlyProvider: Boolean
    ): DocumentReadinessResult {
      val trimmed = manuscriptText.trim()
      val wordCount = if (trimmed.isBlank()) 0 else trimmed.split(Regex("""\s+""")).size
      val totalPages = coverageReport?.totalPages ?: 1
      val isScanned = coverageReport?.extractionEngine?.contains("Vision-Only", ignoreCase = true) == true ||
        trimmed.startsWith("PDF MANUSCRIPT (SCANNED / IMAGE-BASED)")
      val blocking = mutableListOf<String>()
      val actions = mutableListOf<String>()

      if (trimmed.isBlank() && attachedBitmapCount == 0) {
        blocking.add("No manuscript text or rendered document pages were supplied.")
        actions.add("Upload a searchable PDF, Word (.docx), or paste the manuscript text.")
      }
      if (isScanned && useExternalTextOnlyProvider) {
        blocking.add("Scanned image-only PDF cannot be inspected by a text-only external API provider.")
        actions.add("Run OCR on the PDF to embed a searchable text layer, or switch to a multimodal vision provider.")
      }
      if (isScanned && totalPages >= 1 && attachedBitmapCount < totalPages) {
        blocking.add(
          "Scanned PDF has $totalPages page(s) with no searchable text layer, but only $attachedBitmapCount/$totalPages page image(s) were attached (incomplete coverage)."
        )
        actions.add("Provide a text-searchable / OCR-processed PDF or full-page vision coverage so Methods, Results, and all tables can be inspected.")
      }
      if (!isScanned && totalPages >= 8 && wordCount < 80 && attachedBitmapCount == 0) {
        blocking.add("Extracted text contains only $wordCount word(s) across $totalPages page(s), indicating a broken font encoding or image-only PDF.")
        actions.add("Export a text-searchable PDF or Word (.docx) copy and upload again.")
      }

      return DocumentReadinessResult(
        isReady = blocking.isEmpty(),
        blockingReasons = blocking,
        requestedActions = actions.ifEmpty { listOf("Supply searchable full text covering Methods, Results, and primary tables.") }
      )
    }

    /**
     * P0-06 Typed Scientific Entailment & Polarity Gate:
     * Keeps source authenticity (exact quote presence) and scientific entailment strictly separate.
     * Parses both [finding] and the matched passage into typed [ScientificProposition] records
     * (population, intervention, comparator, outcome, estimand/effect measure, adjustment status,
     * subgroup/overall scope, scale/transformation, CI level, inference method, time point, and
     * quantitative magnitudes). Rejects any critical-field mismatch, returns [EntailmentStatus.UNRESOLVED]
     * when critical fields specified in the claim cannot be extracted from evidence, and returns
     * [EntailmentStatus.CONTRADICTED_BY_POLARITY] when polarity, negation, significance, or direction oppose.
     */
    internal fun evaluateQuoteEntailment(
      finding: ReviewFinding,
      matchedQuote: String,
      surroundingWindow: String
    ): EntailmentStatus {
      val rawClaimText = "${finding.title}. ${finding.allegation}"
      val claimText = rawClaimText.lowercase(Locale.US)
      val quoteLower = matchedQuote.lowercase(Locale.US)
      val rawEvidenceText = "$matchedQuote $surroundingWindow"
      val evidenceText = rawEvidenceText.lowercase(Locale.US)
      if (matchedQuote.trim().length < 12) return EntailmentStatus.NOT_ENTAILED

      val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "were", "was", "are", "not", "but",
        "into", "their", "have", "has", "also", "which", "these", "study", "paper", "authors",
        "reported", "report", "results", "methods", "analysis", "finding", "findings", "section",
        "table", "figure", "page", "manuscript", "data", "should", "could", "would", "may", "might",
        "between", "within", "across", "using", "used", "based", "without", "lack", "missing",
        "omitted", "failed", "did", "does", "no", "non", "only", "more", "less", "than", "high", "low"
      )
      val claimNumbers = Regex("""\b\d+(?:\.\d+)?%?\b""").findAll(claimText).map { it.value }.toSet()
      val evidenceNumbers = Regex("""\b\d+(?:\.\d+)?%?\b""").findAll(evidenceText).map { it.value }.toSet()
      val matchedNumbers = claimNumbers.intersect(evidenceNumbers)

      val claimTokens = claimText.split(Regex("""[^a-z0-9²]+"""))
        .filter { it.length >= 4 && it !in stopWords }
        .distinct()
      if (claimTokens.isEmpty()) return EntailmentStatus.PARTIAL_OR_AMBIGUOUS

      val matchedTokens = claimTokens.count { tok ->
        val stem = if (tok.length >= 6) tok.take(5) else tok
        evidenceText.contains(stem)
      }
      val overlapRatio = matchedTokens.toDouble() / claimTokens.size.toDouble()

      // 1. Predicate-anchored negation / polarity contradiction check (P0-06)
      val polarityPredicates = listOf(
        "reduc", "decreas", "increas", "improv", "lower", "elevat", "rais", "associat",
        "benefit", "harm", "effect", "differen", "significan", "adjust", "blind", "random",
        "perform", "conduct", "includ", "correlat", "predict", "prevent", "protect", "worsen"
      )
      val negationPrefix = """(?:did\s+not|does\s+not|do\s+not|was\s+not|were\s+not|is\s+not|are\s+not|not\s+significantly|no\s+statistically\s+significant|no\s+significant|no|never|failed\s+to|fail\s+to|without|neither|unable\s+to)"""
      val sharedPredicates = polarityPredicates.filter { stem ->
        claimText.contains(stem) && quoteLower.contains(stem)
      }
      if (sharedPredicates.isNotEmpty() && (matchedTokens >= 2 || matchedNumbers.isNotEmpty())) {
        for (stem in sharedPredicates) {
          val negatedPattern = Regex("""\b$negationPrefix(?:\s+\w+){0,4}\s+[a-z]*$stem[a-z]*\b""")
          val claimNegated = negatedPattern.containsMatchIn(claimText)
          val quoteNegated = negatedPattern.containsMatchIn(quoteLower)
          if (claimNegated != quoteNegated) {
            return EntailmentStatus.CONTRADICTED_BY_POLARITY
          }
        }
      }

      // 2. Direct opposite-direction contradiction check (e.g., "increased mortality" vs "reduced mortality")
      val decreasePattern = Regex("""\b(?:reduc\w*|decreas\w*|lower\w*|declin\w*|fell|drop\w*|protect\w*|benefit\w*)\b""")
      val increasePattern = Regex("""\b(?:increas\w*|elevat\w*|rais\w*|higher|rose|worsen\w*|harm\w*)\b""")
      val anyNegation = Regex("""\b$negationPrefix\b""")
      if (!anyNegation.containsMatchIn(claimText) && !anyNegation.containsMatchIn(quoteLower) &&
        (matchedTokens >= 2 || matchedNumbers.isNotEmpty())
      ) {
        val claimDec = decreasePattern.containsMatchIn(claimText) && !increasePattern.containsMatchIn(claimText)
        val claimInc = increasePattern.containsMatchIn(claimText) && !decreasePattern.containsMatchIn(claimText)
        val quoteDec = decreasePattern.containsMatchIn(quoteLower) && !increasePattern.containsMatchIn(quoteLower)
        val quoteInc = increasePattern.containsMatchIn(quoteLower) && !decreasePattern.containsMatchIn(quoteLower)
        if ((claimDec && quoteInc) || (claimInc && quoteDec)) {
          return EntailmentStatus.CONTRADICTED_BY_POLARITY
        }
      }

      // 3. Typed Scientific Proposition Entailment Gate (P0):
      // Rejects population, intervention, comparator, outcome, adjustment, subgroup/overall,
      // scale, CI-level, inference-method, time-point, and magnitude mismatches even when
      // lexical overlap is high (e.g., "5% in adults at 12 months" vs "50% in children at 6 months").
      val claimProp = extractScientificProposition(rawClaimText)
      val evidenceProp = extractScientificProposition(rawEvidenceText)
      val isDiscrepancyClaim = Regex(
        """(?i)\b(?:discrepancy|contradict\w*|mismatch|inconsisten\w*|recomputed|does\s+not\s+match|differs?\s+from|impossible\s+ci|arithmetic\s+error)\b"""
      ).containsMatchIn(rawClaimText)
      val propositionComparison = compareScientificPropositions(
        claimProp = claimProp,
        evidenceProp = evidenceProp,
        requireClaimCriticalFieldsInEvidence = !isDiscrepancyClaim,
        compareNumericMagnitudes = true,
        requireAllClaimNumerics = !isDiscrepancyClaim
      )
      when (propositionComparison.status) {
        PropositionCompatibility.MISMATCH_CONTRADICTED -> {
          val isPolarityOpposition = propositionComparison.reason.contains("polarity") ||
            propositionComparison.reason.contains("direction") ||
            propositionComparison.reason.contains("significance")
          return if (isPolarityOpposition) {
            EntailmentStatus.CONTRADICTED_BY_POLARITY
          } else {
            EntailmentStatus.NOT_ENTAILED
          }
        }
        PropositionCompatibility.UNRESOLVED_INSUFFICIENT_FIELDS -> {
          return EntailmentStatus.UNRESOLVED
        }
        PropositionCompatibility.COMPATIBLE -> Unit
      }

      // 4. Absence/omission claims ("did not adjust for confounding", "no sensitivity analysis")
      // cannot be entailed solely by quoting an unrelated descriptive sentence!
      val isAbsenceClaim = Regex(
        """\b(?:no|lack(?:s|ed|ing)?|missing|omitted|did\s+not|failed\s+to|without|unadjusted|not\s+reported|absent)\b"""
      ).containsMatchIn(claimText)
      val quoteExplicitlyNotesOmission = Regex(
        """\b(?:not\s+(?:performed|conducted|adjusted|reported|measured|assessed|available|included)|did\s+not|without|unadjusted|limitation|unable\s+to|excluded|missing|omitted)\b"""
      ).containsMatchIn(evidenceText)

      return when {
        isAbsenceClaim && !quoteExplicitlyNotesOmission && overlapRatio < 0.30 && matchedNumbers.isEmpty() ->
          EntailmentStatus.NOT_ENTAILED
        isAbsenceClaim && !quoteExplicitlyNotesOmission && overlapRatio < 0.45 ->
          EntailmentStatus.PARTIAL_OR_AMBIGUOUS
        matchedNumbers.isNotEmpty() && overlapRatio >= 0.15 ->
          EntailmentStatus.ENTAILED
        overlapRatio >= 0.30 || matchedTokens >= 3 ->
          EntailmentStatus.ENTAILED
        overlapRatio >= 0.15 || matchedTokens >= 2 ->
          EntailmentStatus.PARTIAL_OR_AMBIGUOUS
        else ->
          EntailmentStatus.NOT_ENTAILED
      }
    }

    /**
     * P0-01 Single-Source-of-Truth Prose Sanitizer:
     * Strips sentences/bullets from raw AI prose fields if they repeat an allegation that was
     * explicitly DISMISSED (`CONTRADICTED`) in the adjudicated structured findings ledger.
     */
    internal fun sanitizeProseAgainstDismissedFindings(
      prose: String,
      adjudicatedFindings: List<ReviewFinding>
    ): String {
      if (prose.isBlank()) return prose
      val dismissed = adjudicatedFindings.filter { it.status == FindingStatus.CONTRADICTED }
      if (dismissed.isEmpty()) return prose

      val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "were", "was", "are", "not", "but",
        "study", "paper", "authors", "missing", "lacks", "omitted", "reported", "analysis", "results"
      )
      val dismissedSignatures = dismissed.mapNotNull { f ->
        val tokens = "${f.title} ${f.allegation}".lowercase(Locale.US)
          .split(Regex("""[^a-z0-9²]+"""))
          .filter { it.length >= 4 && it !in stopWords }
          .distinct()
        if (tokens.size >= 2) f.id to tokens else null
      }
      if (dismissedSignatures.isEmpty()) return prose

      val filteredLines = prose.lines().filterNot { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || (trimmed.startsWith("[") && trimmed.endsWith("]"))) return@filterNot false
        val lower = trimmed.lowercase(Locale.US)
        // Preserve explicit dismissal/retraction notes
        if (lower.contains("dismissed") || lower.contains("contradicted") || lower.contains("counterevidence")) {
          return@filterNot false
        }
        dismissedSignatures.any { (id, sigTokens) ->
          if (trimmed.contains("[$id]", ignoreCase = true)) return@any true
          val hits = sigTokens.count { tok -> lower.contains(tok) }
          hits >= 3 && hits.toDouble() / sigTokens.size.toDouble() >= 0.45
        }
      }
      return filteredLines.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    /**
     * P0-02 Strict Publication Validation Gate:
     * Assigning `VERIFIED` requires positive proof of claim-inventory verification, NOT merely
     * the absence of detected errors. An empty structured ledger paired with non-empty editorial
     * criticism can NEVER be labeled `VERIFIED`.
     */
    internal fun computeValidationStatus(
      resolvedVerdict: String,
      adjudicatedFindings: List<ReviewFinding>,
      anyJsonRepaired: Boolean,
      omittedOrPartialPromptPagesCount: Int,
      wasPromptTrimmed: Boolean,
      extractionConfidence: Int,
      hasSubstantiveEditorialProse: Boolean = true,
      truncatedStatisticalClaimCount: Int = 0
    ): String {
      if (resolvedVerdict == "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN" ||
        resolvedVerdict == "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      ) {
        return "PROVISIONAL"
      }
      if (anyJsonRepaired || omittedOrPartialPromptPagesCount > 0 || extractionConfidence <= 2 || truncatedStatisticalClaimCount > 0) {
        return "PARTIALLY_VALIDATED"
      }
      val hasPartialOrUnverified = adjudicatedFindings.any {
        it.status == FindingStatus.CONTRADICTED ||
          it.status == FindingStatus.UNVERIFIED ||
          it.status == FindingStatus.PARTIALLY_SUPPORTED
      }
      val activeVerifiedCount = adjudicatedFindings.count { it.status == FindingStatus.VERIFIED }
      val emptyLedgerWithProse = adjudicatedFindings.isEmpty() && hasSubstantiveEditorialProse
      if (wasPromptTrimmed || hasPartialOrUnverified || emptyLedgerWithProse || activeVerifiedCount == 0 || extractionConfidence < 4) {
        return "ADJUDICATED_WITH_CAVEATS"
      }
      return "VERIFIED"
    }

    /**
     * Finding 10 (P1): Automated Preliminary Clinical Certainty Screen.
     * - Builds explicit outcome-specific [OutcomeEvidenceProfile] records linking outcomes to
     *   co-located quantitative effect estimates/CIs rather than disconnected document keywords.
     * - Systematic reviews start at High ONLY when Methods/inclusion criteria explicitly state
     *   randomized controlled trials were pooled/included (never from casual "RCT" mentions).
     * - Checks Imprecision against clinical decision thresholds (ratio null 1.0, appreciable benefit
     *   0.75, appreciable harm 1.25, relative CI width > 2.5) and OIS/event adequacy.
     * - Protocol registration alone (NCT/ISRCTN/PROSPERO) does NOT satisfy Publication Bias as
     *   NOT_SERIOUS; requires explicit dissemination-bias evaluation (funnel/Egger/Begg/trim-and-fill).
     * - Explicitly framed as an Automated Preliminary Clinical Certainty Screen, never a substitute
     *   for formal multi-reviewer outcome-by-outcome GRADE adjudication.
     */
    internal fun evaluateClinicalGradeCertainty(
      manuscriptText: String,
      forensicReport: ForensicPreFlightReport?,
      adjudicatedFindings: List<ReviewFinding>,
      extractionConfidence: Int
    ): GradeCertaintyAssessment {
      val statAudit = forensicReport?.quantForensics?.statisticalAudit
      val subDesign = statAudit?.subDesign
      val refCut = Regex("""(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(references|bibliography|works\s+cited)\s*$""")
        .find(manuscriptText)?.range?.first ?: manuscriptText.length
      val bodyText = manuscriptText.substring(0, refCut)

      // Determine starting level strictly from study design (RCT=High(3), Observational=Low(1))
      val (startingLevel, startingRank) = when (subDesign) {
        AuditSubDesign.RCT -> "High (Randomized Controlled Trial)" to 3
        AuditSubDesign.META_ANALYSIS, AuditSubDesign.SYSTEMATIC_REVIEW -> {
          val poolsRcts = Regex(
            """(?i)\b(?:meta[\s-]?analysis\s+of\s+(?:\d+\s+)?(?:randomized|randomised|rcts?)|included\s+(?:\d+\s+)?(?:randomized|randomised|rcts?)|pooled\s+(?:\d+\s+)?(?:randomized|randomised|rcts?)|(?:randomized|randomised)\s+controlled\s+trials?\s+were\s+(?:included|eligible|pooled))\b"""
          ).containsMatchIn(bodyText)
          if (poolsRcts) "High (Systematic Review of RCTs)" to 3 else "Low (Systematic Review of Observational/Mixed Studies)" to 1
        }
        AuditSubDesign.OBSERVATIONAL_COHORT,
        AuditSubDesign.DIAGNOSTIC_ACCURACY,
        AuditSubDesign.SURVIVAL_ANALYSIS -> "Low (Observational / Non-Randomized Clinical Evidence)" to 1
        else -> "Not Assessable (Non-Clinical / Uncertain Design)" to -1
      }

      // Extract outcome-specific PICO evidence profiles from co-located sentences & parsed claims
      val directClinicalOutcomeRegex = Regex(
        """(?i)\b(all[\s-]?cause\s+mortality|mortality|death|overall\s+survival|progression[\s-]?free\s+survival|heart\s+failure|stroke|myocardial\s+infarction|hospitalization|clinical\s+remission|major\s+bleeding|adverse\s+events?|primary\s+(?:clinical\s+)?(?:outcome|endpoint))\b"""
      )
      val surrogateOutcomeRegex = Regex(
        """(?i)\b(surrogate\s+(?:outcome|endpoint)|biomarker\s+endpoint|hba1c|ldl\s+cholesterol|blood\s+pressure|viral\s+load|tumor\s+marker)\b"""
      )
      val activeClaims = statAudit?.claims?.filter { !it.isFromReferenceOrCitation }.orEmpty()
      val outcomeProfiles = mutableListOf<OutcomeEvidenceProfile>()
      val seenOutcomeKeys = mutableSetOf<String>()

      // 1. Derive profiles from structured statistical claims with co-located outcome context
      activeClaims.filter { it.pointEstimate != null || it.ciLower != null }.forEach { claim ->
        val quote = claim.sourceQuote
        val directMatch = directClinicalOutcomeRegex.find(quote)
        val surrogateMatch = surrogateOutcomeRegex.find(quote)
        val outcomeName = directMatch?.value?.trim()
          ?: surrogateMatch?.value?.trim()
          ?: if (quote.contains("rate", ignoreCase = true) || quote.contains("outcome", ignoreCase = true) || quote.contains("endpoint", ignoreCase = true)) {
            "Reported quantitative outcome (${claim.effectMeasure})"
          } else null
        if (outcomeName != null) {
          val key = "${outcomeName.lowercase(Locale.US)}|${claim.effectMeasure}|${claim.pointEstimate}|${claim.ciLower}|${claim.ciUpper}"
          if (seenOutcomeKeys.add(key)) {
            outcomeProfiles.add(
              OutcomeEvidenceProfile(
                outcomeName = outcomeName,
                effectMeasure = claim.effectMeasure.ifBlank { "Effect" },
                pointEstimate = claim.pointEstimate,
                ciLower = claim.ciLower,
                ciUpper = claim.ciUpper,
                isDirectClinicalOutcome = directMatch != null && surrogateMatch == null,
                sourceSentence = quote.take(220)
              )
            )
          }
        }
      }

      // 2. Fallback sentence-level co-location scan for comparative outcome sentences (e.g. "mortality 24/320 vs 42/320")
      if (outcomeProfiles.isEmpty()) {
        bodyText.split(Regex("""(?<=[.!?])\s+|\n+""")).forEach { sent ->
          val directMatch = directClinicalOutcomeRegex.find(sent)
          val surrogateMatch = surrogateOutcomeRegex.find(sent)
          val outcomeName = directMatch?.value?.trim() ?: surrogateMatch?.value?.trim() ?: return@forEach
          val hasLocalEffect = Regex(
            """(?i)(?:\b(?:a?hr|a?or|a?rr|irr|pr|md|smd|ard|arr|nnt|risk\s+ratio|odds\s+ratio|hazard\s+ratio|mean\s+difference)\b[^.\n]{0,45}\d|\b\d+\s*/\s*\d+\b[^.\n]{0,35}\b(?:vs\.?|versus)\b[^.\n]{0,35}\b\d+\s*/\s*\d+\b)"""
          ).containsMatchIn(sent)
          if (hasLocalEffect) {
            val key = "${outcomeName.lowercase(Locale.US)}|sent|${sent.take(40)}"
            if (seenOutcomeKeys.add(key)) {
              outcomeProfiles.add(
                OutcomeEvidenceProfile(
                  outcomeName = outcomeName,
                  effectMeasure = "Comparative",
                  pointEstimate = null,
                  ciLower = null,
                  ciUpper = null,
                  isDirectClinicalOutcome = directMatch != null && surrogateMatch == null,
                  sourceSentence = sent.trim().take(220)
                )
              )
            }
          }
        }
      }

      val hasPopulation = Regex("""(?i)\b(?:patients?|participants?|subjects?|adults?|children|infants?|cohorts?|individuals?)\b""").containsMatchIn(bodyText)
      val hasComparator = Regex("""(?i)\b(?:placebo|control\s+(?:group|arm)|standard\s+of\s+care|usual\s+care|comparator|versus|vs\.?\s+(?:standard|usual|placebo|control|sham)|randomized\s+to\s+[^.\n]{1,60}\bor\b)\b""").containsMatchIn(bodyText)
      val hasOutcomeEffectEvidence = outcomeProfiles.isNotEmpty()

      if (startingRank < 0 || extractionConfidence <= 2 || statAudit?.designClassification?.abstained == true || !hasOutcomeEffectEvidence || !hasPopulation || !hasComparator) {
        val missingParts = buildList {
          if (startingRank < 0) add("clinical study design")
          if (extractionConfidence <= 2) add("sufficient extraction confidence")
          if (!hasPopulation) add("target clinical population")
          if (!hasComparator) add("explicit comparator/control arm")
          if (!hasOutcomeEffectEvidence) add("co-located outcome-specific quantitative effect profile")
        }
        return GradeCertaintyAssessment(
          startingLevel = startingLevel,
          startingRank = -1,
          riskOfBias = GradeDomainConcern.NOT_ASSESSABLE,
          inconsistency = GradeDomainConcern.NOT_ASSESSABLE,
          indirectness = GradeDomainConcern.NOT_ASSESSABLE,
          imprecision = GradeDomainConcern.NOT_ASSESSABLE,
          publicationBias = GradeDomainConcern.NOT_ASSESSABLE,
          verdictEnum = "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
          isAssessable = false,
          rationale = "Preliminary certainty screen withheld because structured outcome-specific PICO/evidence inputs are missing: ${missingParts.joinToString(", ")}.",
          outcomeProfiles = outcomeProfiles
        )
      }

      val activeFindings = adjudicatedFindings.filter {
        it.status != FindingStatus.CONTRADICTED && it.status != FindingStatus.NOT_APPLICABLE
      }
      val verifiedCriticalCount = activeFindings.count {
        it.status == FindingStatus.VERIFIED && (it.severity == "CRITICAL" || it.flawTier == "FATAL_TO_PRIMARY_CONCLUSION")
      }
      val biasFindingsCount = activeFindings.count { f ->
        val txt = "${f.title} ${f.allegation}".lowercase(Locale.US)
        txt.contains("bias") || txt.contains("confound") || txt.contains("attrition") ||
          txt.contains("blinding") || txt.contains("allocation") || txt.contains("leakage") || txt.contains("contradiction")
      }

      // 1. Risk of Bias — NEVER treat mere mention of "placebo" as adequate RoB!
      val hasNegativeRobCue = Regex(
        """(?i)\b(?:open[\s-]?label|unblinded|no\s+blinding|not\s+blinded|without\s+allocation\s+concealment|allocation\s+concealment\s+was\s+not|high\s+risk\s+of\s+bias|unadjusted\s+for\s+confound|risk\s+of\s+bias\s+(?:was\s+)?not\s+(?:assessed|evaluated))\b"""
      ).containsMatchIn(bodyText)
      val hasRobNotAssessed = Regex(
        """(?i)\b(?:risk\s+of\s+bias|quality)\s+(?:was\s+|were\s+)?not\s+(?:assessed|evaluated|performed)\b"""
      ).containsMatchIn(bodyText)
      val hasAllocationConcealment = Regex(
        """(?i)\b(?:allocation\s+concealment|concealed\s+allocation|central\s+(?:web[\s-]?based\s+)?randomiz(?:ation|ed)|interactive\s+web[\s-]?response|sealed\s+opaque\s+envelopes?)\b"""
      ).containsMatchIn(bodyText) && !Regex("""(?i)\b(?:no|without|lacked)\s+allocation\s+concealment\b""").containsMatchIn(bodyText)
      val hasFormalBlinding = Regex(
        """(?i)\b(?:double[\s-]?blind|triple[\s-]?blind|outcome\s+assessors?\s+(?:were\s+)?(?:blinded|masked)|masked\s+outcome\s+adjudication)\b"""
      ).containsMatchIn(bodyText) && !Regex("""(?i)\b(?:not\s+double[\s-]?blind|unblinded|open[\s-]?label)\b""").containsMatchIn(bodyText)
      val hasFormalLowRobJudgment = Regex(
        """(?i)\b(?:rob[\s-]?2|robins[\s-]?i|newcastle[\s-]?ottawa|quadas[\s-]?2)\b[^.\n]{0,90}\b(?:low\s+risk\s+of\s+bias|low[\s-]to[\s-]moderate\s+risk|high\s+quality)\b"""
      ).containsMatchIn(bodyText)
      val hasObservationalConfoundControl = Regex(
        """(?i)\b(?:propensity\s+score\s+(?:matching|weighting)|inverse\s+probability\s+weighting|multivariable\s+(?:cox|logistic|regression)\s+adjusted\s+for|target\s+trial\s+emulation)\b"""
      ).containsMatchIn(bodyText)

      val adequateStructuredRob = when (subDesign) {
        AuditSubDesign.RCT -> (hasAllocationConcealment && hasFormalBlinding) || hasFormalLowRobJudgment
        AuditSubDesign.META_ANALYSIS, AuditSubDesign.SYSTEMATIC_REVIEW -> hasFormalLowRobJudgment
        else -> hasFormalLowRobJudgment || hasObservationalConfoundControl
      }
      val riskOfBias = when {
        verifiedCriticalCount >= 2 || biasFindingsCount >= 3 -> GradeDomainConcern.VERY_SERIOUS
        verifiedCriticalCount == 1 || biasFindingsCount >= 1 || (hasNegativeRobCue && !hasRobNotAssessed) -> GradeDomainConcern.SERIOUS
        hasRobNotAssessed -> GradeDomainConcern.NOT_ASSESSABLE
        adequateStructuredRob -> GradeDomainConcern.NOT_SERIOUS
        else -> GradeDomainConcern.NOT_ASSESSABLE
      }

      // 2. Inconsistency
      val highHeterogeneity = statAudit?.reconstructions?.any {
        it.detail.contains("I²=", ignoreCase = true) &&
          Regex("""I²\s*=\s*([5-9]\d|100)""").containsMatchIn(it.detail)
      } == true || Regex("""I[²2]\s*[=≥>]\s*(?:[6-9]\d|100)\s*%""").containsMatchIn(bodyText)
      val hasHeterogeneityNotAssessed = Regex("""(?i)\bheterogeneity\s+(?:was\s+)?not\s+(?:assessed|evaluated|quantified)\b""").containsMatchIn(bodyText)
      val hasHeterogeneityEvidence = subDesign == AuditSubDesign.RCT ||
        (Regex("""(?i)(?:I[²2]\s*[=<>≤≥]\s*\d|tau[\s-]?squared|cochran(?:'s)?\s+q|prediction\s+interval)""").containsMatchIn(bodyText) && !hasHeterogeneityNotAssessed)
      val inconsistency = when {
        highHeterogeneity -> GradeDomainConcern.SERIOUS
        hasHeterogeneityEvidence -> GradeDomainConcern.NOT_SERIOUS
        else -> GradeDomainConcern.NOT_ASSESSABLE
      }

      // 3. Indirectness (outcome-specific directness check)
      val indirectnessCues = activeFindings.any { f ->
        val txt = "${f.title} ${f.allegation}".lowercase(Locale.US)
        txt.contains("surrogate") || txt.contains("indirect") || txt.contains("generalizab") || txt.contains("external validity")
      } || Regex("""(?i)\b(?:surrogate\s+(?:outcome|endpoint)|biomarker\s+endpoint\s+only|indirect\s+comparison)\b""").containsMatchIn(bodyText) ||
        (outcomeProfiles.isNotEmpty() && outcomeProfiles.none { it.isDirectClinicalOutcome })
      val hasDirectClinicalOutcome = outcomeProfiles.any { it.isDirectClinicalOutcome }
      val indirectness = when {
        indirectnessCues -> GradeDomainConcern.SERIOUS
        hasDirectClinicalOutcome -> GradeDomainConcern.NOT_SERIOUS
        else -> GradeDomainConcern.NOT_ASSESSABLE
      }

      // 4. Imprecision — Clinical decision threshold (null 1.0, appreciable benefit 0.75, harm 1.25)
      //    + relative width + OIS/sample adequacy. NEVER award NOT_SERIOUS merely from N>=100 + CI!
      val ciClaims = activeClaims.filter { it.ciLower != null && it.ciUpper != null }
      val ratioMeasures = setOf("OR", "RR", "HR", "IRR", "PR", "AOR", "ARR_RATIO", "AHR")
      val anyCiCrossesNull = ciClaims.any { c ->
        val lo = c.ciLower ?: return@any false
        val hi = c.ciUpper ?: return@any false
        if (c.effectMeasure.uppercase(Locale.US) in ratioMeasures) lo <= 1.0 && hi >= 1.0 else lo <= 0.0 && hi >= 0.0
      }
      val anyCiSpansBothClinicalThresholds = ciClaims.any { c ->
        val lo = c.ciLower ?: return@any false
        val hi = c.ciUpper ?: return@any false
        c.effectMeasure.uppercase(Locale.US) in ratioMeasures && lo <= 0.75 && hi >= 1.25
      }
      val anyCiCrossesClinicalDecisionThresholdWithWideSpan = ciClaims.any { c ->
        val lo = c.ciLower ?: return@any false
        val hi = c.ciUpper ?: return@any false
        val isRatio = c.effectMeasure.uppercase(Locale.US) in ratioMeasures
        if (isRatio && lo > 0.0) {
          val widthRatio = hi / lo
          val crossesBenefitThreshold = lo < 0.75 && hi > 0.75
          val crossesHarmThreshold = lo < 1.25 && hi > 1.25
          widthRatio > 2.5 || ((crossesBenefitThreshold || crossesHarmThreshold) && widthRatio > 1.8)
        } else {
          val est = c.pointEstimate
          est != null && kotlin.math.abs(est) > 1e-6 && ((hi - lo) / kotlin.math.abs(est)) > 2.5
        }
      }
      val maxSampleN = Regex("""(?i)\b(?:n\s*=\s*|total\s+of\s+|enrolled\s+|randomized\s+)(\d{2,6})\s*(?:patients?|participants?|subjects?|individuals?)?\b""")
        .findAll(bodyText)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .maxOrNull()
      val explicitUnderpoweredCue = Regex(
        """(?i)\b(?:underpowered|not\s+powered|insufficient\s+power|pilot\s+(?:trial|study|rct)|feasibility\s+(?:trial|study)|small\s+sample\s+size|wide\s+confidence\s+interval|few\s+events)\b"""
      ).containsMatchIn(bodyText)
      val hasPowerOrOisEvidence = Regex(
        """(?i)\b(?:(?:80|85|90|95)\s*%\s*power|powered\s+at\s+(?:80|85|90|95)\s*%|power\s+of\s+(?:0\.[89]\d?|(?:80|85|90|95)\s*%)|optimal\s+information\s+size|sample\s+size\s+calculation\s+indicated)\b"""
      ).containsMatchIn(bodyText)
      val imprecisionFindings = statAudit?.reconstructions?.any {
        it.findingType == StatisticalFindingType.IMPRECISION_OR_HIGH_VARIANCE
      } == true || activeFindings.any { f ->
        "${f.title} ${f.allegation}".lowercase(Locale.US).let { it.contains("imprecis") || it.contains("underpower") || it.contains("wide ci") }
      }
      val smallSample = maxSampleN != null && maxSampleN < 100
      val adequateOisOrLargeSample = hasPowerOrOisEvidence || (maxSampleN != null && maxSampleN >= 300)
      val imprecision = when {
        explicitUnderpoweredCue || imprecisionFindings || anyCiSpansBothClinicalThresholds || (smallSample && anyCiCrossesNull) ->
          GradeDomainConcern.VERY_SERIOUS
        anyCiCrossesNull || anyCiCrossesClinicalDecisionThresholdWithWideSpan || smallSample ->
          GradeDomainConcern.SERIOUS
        ciClaims.isNotEmpty() && adequateOisOrLargeSample ->
          GradeDomainConcern.NOT_SERIOUS
        else -> GradeDomainConcern.NOT_ASSESSABLE
      }

      // 5. Publication Bias — Protocol registration ALONE is NOT sufficient for NOT_SERIOUS!
      //    Requires formal dissemination-bias evaluation (funnel/Egger/Begg/trim-and-fill or explicit negative assessment).
      val pubBiasNotAssessed = Regex(
        """(?i)\b(?:publication\s+bias\s+(?:was\s+|could\s+)?not\s+(?:be\s+)?(?:assessed|evaluated|tested|examined)|did\s+not\s+(?:assess|evaluate|test)\s+publication\s+bias|too\s+few\s+studies\s+to\s+(?:assess|evaluate)\s+publication\s+bias|not\s+(?:pre)?registered|unregistered\s+(?:trial|protocol|review)|no\s+protocol\s+registration)\b"""
      ).containsMatchIn(bodyText)
      val pubBiasDetected = activeFindings.any { f ->
        "${f.title} ${f.allegation}".lowercase(Locale.US).let { it.contains("publication bias") || it.contains("funnel") || it.contains("selective reporting") }
      } || Regex(
        """(?i)(?<!no\s)(?<!without\s)(?<!not\s)(?<!non-)(?<!non\s)\b(?:asymmetric(?:al)?\s+funnel|evidence\s+of\s+publication\s+bias|significant\s+egger(?:'s)?\s+test|selective\s+outcome\s+reporting\s+detected)\b|(?i)\begger(?:'s)?[^.\n]{0,30}p\s*(?:<\s*0\.05|[<≤=]\s*0\.0[0-4]\d*)\b"""
      ).containsMatchIn(bodyText)
      val hasAffirmativeRegistration = Regex(
        """(?i)\b(?:nct\d{6,}|isrctn\d{6,}|prospero\s*(?:crd)?\d{5,}|crd4\d{7,}|prospectively\s+registered\s+at\s+clinicaltrials\.gov)\b"""
      ).containsMatchIn(bodyText) && !Regex("""(?i)\b(?:not\s+registered|unregistered)\b""").containsMatchIn(bodyText)
      val hasFormalDisseminationBiasAssessment = Regex(
        """(?i)\b(?:no\s+evidence\s+of\s+publication\s+bias|symmetrical\s+funnel\s+plot|funnel\s+plot\s+(?:showed\s+)?no\s+asymmetry|egger(?:'s)?[^.\n]{0,35}p\s*[=>]\s*0\.[1-9]\d*|begg(?:'s)?[^.\n]{0,35}p\s*[=>]\s*0\.[1-9]\d*|trim[\s-]?and[\s-]?fill\s+(?:analysis\s+)?(?:confirmed|showed\s+no\s+missing|unchanged))\b"""
      ).containsMatchIn(bodyText)
      val publicationBias = when {
        pubBiasDetected -> GradeDomainConcern.SERIOUS
        pubBiasNotAssessed -> GradeDomainConcern.NOT_ASSESSABLE
        hasFormalDisseminationBiasAssessment -> GradeDomainConcern.NOT_SERIOUS
        // Registration alone reduces selective reporting risk within a single trial, but does NOT
        // establish absence of publication/dissemination bias across the evidence body.
        else -> GradeDomainConcern.NOT_ASSESSABLE
      }

      val unassessedCount = listOf(riskOfBias, inconsistency, indirectness, imprecision, publicationBias)
        .count { it == GradeDomainConcern.NOT_ASSESSABLE }
      if (riskOfBias == GradeDomainConcern.NOT_ASSESSABLE ||
        imprecision == GradeDomainConcern.NOT_ASSESSABLE ||
        unassessedCount >= 2
      ) {
        return GradeCertaintyAssessment(
          startingLevel = startingLevel,
          startingRank = startingRank,
          riskOfBias = riskOfBias,
          inconsistency = inconsistency,
          indirectness = indirectness,
          imprecision = imprecision,
          publicationBias = publicationBias,
          verdictEnum = "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT",
          isAssessable = false,
          rationale = "Preliminary clinical certainty screen withheld: $unassessedCount domain(s) lack structured evidence (RoB=${riskOfBias.label}, Imprecision=${imprecision.label}, Publication Bias=${publicationBias.label}).",
          outcomeProfiles = outcomeProfiles
        )
      }

      val downgradeReasons = buildList {
        if (riskOfBias.downgradeSteps > 0) add("Risk of Bias (-${riskOfBias.downgradeSteps})")
        if (inconsistency.downgradeSteps > 0) add("Inconsistency (-${inconsistency.downgradeSteps})")
        if (indirectness.downgradeSteps > 0) add("Indirectness (-${indirectness.downgradeSteps})")
        if (imprecision.downgradeSteps > 0) add("Imprecision (-${imprecision.downgradeSteps})")
        if (publicationBias.downgradeSteps > 0) add("Publication Bias (-${publicationBias.downgradeSteps})")
      }
      val totalDowngrade = riskOfBias.downgradeSteps + inconsistency.downgradeSteps +
        indirectness.downgradeSteps + imprecision.downgradeSteps + publicationBias.downgradeSteps
      // Never award HIGH_CERTAINTY unless every single domain is affirmatively NOT_SERIOUS
      val cappedRank = if (unassessedCount > 0) {
        minOf(2, (startingRank - totalDowngrade).coerceIn(0, 3))
      } else {
        (startingRank - totalDowngrade).coerceIn(0, 3)
      }
      val verdictEnum = when (cappedRank) {
        3 -> "HIGH_CERTAINTY"
        2 -> "MODERATE_CERTAINTY"
        1 -> "LOW_CERTAINTY"
        else -> "VERY_LOW_CERTAINTY"
      }
      val registrationCaveat = if (publicationBias == GradeDomainConcern.NOT_ASSESSABLE && hasAffirmativeRegistration) {
        " (protocol registration detected, which mitigates selective reporting but does not replace formal publication-bias assessment)"
      } else ""
      val reasonDetail = if (downgradeReasons.isEmpty()) {
        if (unassessedCount > 0) {
          "capped at Moderate due to 1 unassessed domain$registrationCaveat"
        } else {
          "all 5 screened domains affirmatively supported with zero downgrades"
        }
      } else {
        "downgraded for ${downgradeReasons.joinToString(", ")}$registrationCaveat"
      }
      return GradeCertaintyAssessment(
        startingLevel = startingLevel,
        startingRank = startingRank,
        riskOfBias = riskOfBias,
        inconsistency = inconsistency,
        indirectness = indirectness,
        imprecision = imprecision,
        publicationBias = publicationBias,
        verdictEnum = verdictEnum,
        isAssessable = true,
        rationale = "Automated preliminary clinical certainty screen across ${outcomeProfiles.size} outcome profile(s); started at $startingLevel; $reasonDetail.",
        outcomeProfiles = outcomeProfiles
      )
    }

    /**
     * Explicit, deterministic decision-table verdict resolver strictly derived from weighted
     * score bands, verified critical/major errors, partially-supported discrepancies,
     * classifier abstention, and [reviewMode] rules.
     */
    internal fun resolveVerdictForMode(
      rawVerdict: String,
      compositeScore: Int,
      reviewMode: ReviewMode,
      hasVerifiedCriticalError: Boolean = false,
      hasPartiallySupportedCriticalDiscrepancy: Boolean = false,
      verifiedFatalCount: Int = 0,
      verifiedMajorCount: Int = 0,
      partiallySupportedMajorCount: Int = 0,
      classifierAbstained: Boolean = false,
      identityUncertainCount: Int = 0,
      stopAndAskTriggered: Boolean = false,
      gradeAssessment: GradeCertaintyAssessment? = null
    ): String {
      if (stopAndAskTriggered || (classifierAbstained && identityUncertainCount >= 4)) {
        return "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      }
      if (classifierAbstained) {
        return "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN"
      }
      val clamped = compositeScore.coerceIn(0, 100)
      val escalatedMajor = hasVerifiedCriticalError || verifiedFatalCount > 0 || verifiedMajorCount >= 2
      val caveatMajor = hasPartiallySupportedCriticalDiscrepancy || partiallySupportedMajorCount >= 2
      return when (reviewMode) {
        ReviewMode.POST_PUBLICATION_APPRAISAL -> when {
          clamped < 40 -> "LOW_CREDIBILITY"
          escalatedMajor -> "REQUIRES_CORRECTION"
          clamped >= 85 && !caveatMajor -> "HIGH_CREDIBILITY"
          clamped >= 70 -> "SOUND_WITH_CAVEATS"
          clamped >= 55 -> "REQUIRES_CORRECTION"
          else -> "MAJOR_CONCERNS"
        }
        ReviewMode.CLINICAL_EVIDENCE_APPRAISAL -> {
          if (gradeAssessment != null) {
            gradeAssessment.verdictEnum
          } else {
            // Backward-compatible fallback when outcome-specific GRADE domain inputs are not supplied
            val baseGrade = when {
              clamped >= 85 -> 3
              clamped >= 68 -> 2
              clamped >= 48 -> 1
              else -> 0
            }
            val downgrade = when {
              escalatedMajor -> 2
              caveatMajor && baseGrade >= 2 -> 1
              else -> 0
            }
            when ((baseGrade - downgrade).coerceIn(0, 3)) {
              3 -> "HIGH_CERTAINTY"
              2 -> "MODERATE_CERTAINTY"
              1 -> "LOW_CERTAINTY"
              else -> "VERY_LOW_CERTAINTY"
            }
          }
        }
        ReviewMode.JOURNAL_REFEREE -> when {
          clamped < 40 -> "REJECT"
          escalatedMajor -> if (clamped >= 45) "MAJOR_REVISION" else "REJECT_AND_RESUBMIT"
          partiallySupportedMajorCount >= 2 && clamped >= 72 -> "MINOR_REVISION_WITH_CAVEATS"
          clamped >= 85 && !hasPartiallySupportedCriticalDiscrepancy -> "ACCEPT"
          clamped >= 72 -> "MINOR_REVISION"
          clamped >= 55 -> "MAJOR_REVISION"
          else -> "REJECT_AND_RESUBMIT"
        }
      }
    }

    /**
     * P2.3 Severity & Editorial Decision Alignment:
     * Prevents contradictory editorial notes (e.g., "Minor revisions are required" when multiple
     * verified major/fatal findings are present, or journal-style "minor revision" wording during
     * Post-Publication Appraisal where no editorial submission action is being recommended).
     */
    fun alignEditorNoteWithVerdictAndMode(
      rawEditorNote: String,
      findings: List<ReviewFinding>,
      resolvedVerdict: String,
      reviewMode: ReviewMode
    ): String {
      if (rawEditorNote.isBlank()) return rawEditorNote
      val active = findings.filter {
        it.status != FindingStatus.CONTRADICTED &&
          it.status != FindingStatus.NOT_APPLICABLE &&
          it.reportingStatus != "NOT_ASSESSABLE_FROM_SUPPLIED_MATERIAL"
      }
      val verifiedFatalCount = active.count {
        it.status == FindingStatus.VERIFIED &&
          (it.severity == "CRITICAL" || it.flawTier == "FATAL_TO_PRIMARY_CONCLUSION")
      }
      val verifiedMajorCount = active.count { it.status == FindingStatus.VERIFIED && it.severity == "MAJOR" }
      val partiallySupportedMajorCount = active.count { it.status == FindingStatus.PARTIALLY_SUPPORTED && it.severity == "MAJOR" }
      val totalMajorOrCritical = verifiedFatalCount + verifiedMajorCount + partiallySupportedMajorCount

      val minorRevisionPhraseRegex = Regex(
        """\bminor\s+revisions?\s+(?:are|is)\s+required\b|\brecommend(?:s|ed|ing)?\s+(?:a\s+)?minor\s+revisions?\b|\bminor\s+revisions?\b""",
        RegexOption.IGNORE_CASE
      )

      var aligned = rawEditorNote
      val verdictUpper = resolvedVerdict.uppercase(Locale.US)

      if (reviewMode == ReviewMode.POST_PUBLICATION_APPRAISAL) {
        val replacementPhrase = if (totalMajorOrCritical > 0) {
          "Substantive limitations requiring clarification"
        } else {
          "Methodological caveats requiring transparent interpretation"
        }
        aligned = aligned.replace(minorRevisionPhraseRegex, replacementPhrase)
        aligned = aligned.replace(
          Regex("""\bmajor\s+revisions?\s+(?:are|is)\s+required\b""", RegexOption.IGNORE_CASE),
          "Substantive limitations requiring clarification or formal correction"
        )
      } else if (reviewMode == ReviewMode.JOURNAL_REFEREE) {
        if (verifiedFatalCount > 0 || verifiedMajorCount >= 2 || verdictUpper == "MAJOR_REVISION") {
          aligned = aligned.replace(
            Regex("""\bminor\s+revisions?\s+(?:are|is)\s+required\b""", RegexOption.IGNORE_CASE),
            "Major revisions are required"
          )
          aligned = aligned.replace(
            Regex("""\brecommend(?:s|ed|ing)?\s+(?:a\s+)?minor\s+revisions?\b""", RegexOption.IGNORE_CASE),
            "recommend major revision"
          )
        } else if (partiallySupportedMajorCount >= 2 || verdictUpper == "MINOR_REVISION_WITH_CAVEATS") {
          aligned = aligned.replace(
            Regex("""\bminor\s+revisions?\s+(?:are|is)\s+required\b""", RegexOption.IGNORE_CASE),
            "Minor revisions with caveats are required (non-fatal partially supported major findings)"
          )
        }
      }

      val prefixNote = when {
        verdictUpper == "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN" &&
          !aligned.contains("Provisional appraisal", ignoreCase = true) ->
          "Editorial Alignment: Provisional appraisal — design classification uncertain; interpret design-sensitive limitations with caution.\n\n"
        reviewMode == ReviewMode.POST_PUBLICATION_APPRAISAL &&
          totalMajorOrCritical >= 2 &&
          !aligned.contains("Substantive limitations requiring clarification", ignoreCase = true) ->
          "Appraisal Alignment: Substantive limitations requiring clarification ($totalMajorOrCritical active major/critical finding(s); post-publication appraisal rather than journal submission revision).\n\n"
        reviewMode == ReviewMode.JOURNAL_REFEREE &&
          (verifiedFatalCount > 0 || verifiedMajorCount >= 2) &&
          !aligned.contains("Major revision", ignoreCase = true) ->
          "Editorial Alignment: Major revision required ($verifiedFatalCount verified fatal/critical, $verifiedMajorCount verified major finding(s)).\n\n"
        else -> ""
      }
      return (prefixNote + aligned).trim()
    }

    /**
     * Unwraps nested FirebaseAIException / UnknownException cause chains so users and logs
     * always see the concrete underlying failure instead of an opaque "Something unexpected happened.".
     */
    fun formatThrowableDetails(err: Throwable): String {
      val chain = mutableListOf<Throwable>()
      var cursor: Throwable? = err
      var depth = 0
      while (cursor != null && depth < 6 && cursor !in chain) {
        chain.add(cursor)
        cursor = cursor.cause
        depth++
      }

      val meaningful = chain.lastOrNull { t ->
        val msg = t.message?.trim().orEmpty()
        msg.isNotBlank() && !msg.equals("Something unexpected happened.", ignoreCase = true)
      }
      val root = chain.last()
      val rootType = root.javaClass.simpleName
      val rootMsg = root.message?.trim().orEmpty()
      val combinedLower = chain.joinToString(" ") {
        "${it.javaClass.simpleName} ${it.message.orEmpty()}"
      }.lowercase(Locale.US)

      val hint = when {
        combinedLower.contains("unknownhostexception") ||
          combinedLower.contains("unable to resolve host") ||
          combinedLower.contains("connectexception") ||
          combinedLower.contains("network is unreachable") ->
          "Network connection unavailable or DNS blocked."
        combinedLower.contains("sslhandshake") ||
          combinedLower.contains("certpathvalidator") ->
          "Secure TLS/SSL handshake failed. Check device date/time or network proxy."
        combinedLower.contains("sockettimeout") ||
          combinedLower.contains("timeoutcancellation") ||
          combinedLower.contains("httprequesttimeout") ->
          "AI request timed out waiting for server response."
        combinedLower.contains("403") ||
          combinedLower.contains("app check") ||
          combinedLower.contains("appcheck") ->
          "Firebase App Check or API access rejected (HTTP 403)."
        combinedLower.contains("quota") || combinedLower.contains("429") ->
          "Gemini API rate limit or quota reached. Please wait a moment and retry."
        combinedLower.contains("serialization") || combinedLower.contains("json") ->
          "Response format mismatch while decoding AI output."
        else -> null
      }

      val coreMessage = when {
        meaningful != null -> {
          val mType = meaningful.javaClass.simpleName
          val mText = meaningful.message?.trim().orEmpty()
          if (meaningful === err && !mText.equals("Something unexpected happened.", ignoreCase = true)) {
            mText
          } else {
            "$mType: $mText"
          }
        }
        rootMsg.isNotBlank() -> "$rootType: $rootMsg"
        else -> rootType
      }

      return if (hint != null && !coreMessage.contains(hint, ignoreCase = true)) {
        "$hint ($coreMessage)"
      } else {
        coreMessage
      }
    }

    fun renderPlainTextDossier(
      title: String,
      authors: String,
      year: String,
      venueOrCitation: String,
      isEmpirical: Boolean,
      credibilityScore: Long,
      noveltyScore: Int = 7,
      methodologyScore: Int = 7,
      mathRigorScore: Int = 7,
      reproducibilityScore: Int = 7,
      clarityScore: Int = 8,
      verdict: String,
      forensicReport: ForensicPreFlightReport? = null,
      abstractSummary: String,
      researchQuestion: String,
      keyMethodology: String,
      coreContributions: String,
      redTeamCritique: String,
      blueTeamVerification: String,
      mathAuditReport: String,
      codeAuditReport: String,
      editorNote: String,
      copyeditSuggestions: String,
      reviewerQuestions: String
    ): String {
      val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
      val divider = "=".repeat(68)
      val subDivider = "-".repeat(68)
      return buildString {
        appendLine(divider)
        appendLine("                         IS IT CREDIBLE?")
        appendLine("            Automated Adversarial Peer-Review Dossier")
        appendLine("         Engine: isitcredible/reviewer2 + phd-skills (30-Stage)")
        appendLine(divider)
        appendLine()
        appendLine("\"I seem, then, to be wiser than him in this small way, at least:")
        appendLine("that what I do not know, I do not think I know, either.\"")
        appendLine("  — Plato, The Apology of Socrates, 21d")
        appendLine()
        appendLine("\"To err is human. All human knowledge is fallible and therefore")
        appendLine("uncertain. It follows that we must distinguish sharply between")
        appendLine("truth and certainty.\"")
        appendLine("  — Karl Popper, 'Knowledge and the Shaping of Reality'")
        appendLine()
        appendLine(subDivider)
        appendLine("MANUSCRIPT CITATION & 6-AXIS SCORECARD (STAGE 00a–00b)")
        appendLine(subDivider)
        appendLine("Title          : $title")
        appendLine("Authors        : $authors (${year.ifBlank { "Year not verified" }})")
        appendLine("Venue/Ref      : $venueOrCitation")
        appendLine("Track          : ${if (isEmpirical) "Empirical (01a Breaker / 01b Butcher / 01c Shredder)" else "Theoretical / Non-Empirical (01a + 01a_2)"}")
        appendLine("Date           : $dateStr")
        appendLine("Credibility    : $credibilityScore / 100")
        appendLine("6-Axis Scores  : Novelty=$noveltyScore/10 | Methodology=$methodologyScore/10 | Math=$mathRigorScore/10 | Repro=$reproducibilityScore/10 | Clarity=$clarityScore/10")
        appendLine("Decision       : ${verdict.replace('_', ' ')}")
        if (forensicReport != null) {
          appendLine("Domain         : ${forensicReport.detectedDiscipline}")
          appendLine("Pre-Flight Scan: ${forensicReport.wordCount} words, ${forensicReport.extractedEquations.size} eqs, ${forensicReport.proceduralLeakageAlerts.size} forensic flags")
        }
        appendLine()
        appendLine(subDivider)
        appendLine("STAGE 04a THE REVIEWER, 06 LEGAL PASS & 08b EDITOR'S NOTE")
        appendLine(subDivider)
        appendLine(editorNote.trim())
        appendLine()
        appendLine(subDivider)
        appendLine("STAGE 00b–00c EXECUTIVE SUMMARY & CLAIMED CONTRIBUTIONS")
        appendLine(subDivider)
        appendLine("[Abstract Summary]")
        appendLine(abstractSummary.trim())
        appendLine()
        appendLine("[Primary Research Question]")
        appendLine(researchQuestion.trim())
        appendLine()
        appendLine("[Key Methodology]")
        appendLine(keyMethodology.trim())
        appendLine()
        appendLine("[Stage 00c Claimed Contributions]")
        appendLine(coreContributions.trim())
        appendLine()
        appendLine(subDivider)
        appendLine("STAGE 01a–01g RED TEAM ADVERSARIAL CRITIQUE")
        appendLine("(01a Breaker • 01b Butcher • 01c Shredder • 01d Collector • 01g Void)")
        appendLine(subDivider)
        appendLine(redTeamCritique.trim())
        appendLine()
        appendLine(subDivider)
        appendLine("STAGE 02a–03c BLUE TEAM, ASSESSMENT & FACT-CHECKED LIST v2")
        appendLine("(02a Numbers • 02c Blue Team • 02e Assessment • 03a Fact Checker • 03c List v2)")
        appendLine(subDivider)
        appendLine(blueTeamVerification.trim())
        appendLine()
        appendLine(subDivider)
        appendLine("STAGE 01e–01fd MATH RE-DERIVER & SOBER EQUATION AUDIT")
        appendLine("(01e Error Finder • 01fa Re-Deriver • 01fb Proofreader • 01fc Auditor • 01fd Sober)")
        appendLine(subDivider)
        appendLine(mathAuditReport.trim())
        appendLine()
        appendLine(subDivider)
        appendLine("STAGE 01i–01n & 07b DIVERGENCE HUNTER & DATA LEAKAGE X-RAY")
        appendLine(subDivider)
        appendLine(codeAuditReport.trim())
        appendLine()
        appendLine(subDivider)
        appendLine("PHD-SKILLS FORTIFY: ANTICIPATED REVIEWER 2 Q&A & ABLATIONS")
        appendLine(subDivider)
        appendLine(reviewerQuestions.trim())
        appendLine()
        appendLine(subDivider)
        appendLine("STAGE 09a–09c PROOFREADER, PROOFREAD CLEAN & COPYEDIT")
        appendLine(subDivider)
        appendLine(copyeditSuggestions.trim())
        appendLine()
        appendLine(divider)
        appendLine("DISCLAIMER: Generated by the reviewer2 + phd-skills multi-stage")
        appendLine("adversarial peer-review engine. Verify all claims independently.")
        appendLine(divider)
      }
    }
  }
}
