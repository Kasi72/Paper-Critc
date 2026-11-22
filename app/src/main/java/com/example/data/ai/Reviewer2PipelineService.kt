package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ai.epistemic.BidirectionalAdjudicator
import com.example.data.ai.epistemic.EpistemicSafeguardEngine
import com.example.data.ai.epistemic.EpistemicSafeguardReport
import com.example.data.ai.epistemic.FlawSeverityTier
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
import com.example.util.DocumentCoverageReport
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
    val wasRepaired: Boolean
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
    stageCode: String,
    stageTitle: String,
    startProgress: Float,
    endProgress: Float,
    externalProvider: com.example.data.model.AiApiProviderEntry?,
    useExternalProvider: Boolean,
    externalSystemPrompt: String,
    autoFallbackToKeylessFirebase: Boolean,
    onStageUpdate: (stageCode: String, stageLabel: String, progress: Float) -> Unit
  ): ParsedJsonResult = withContext(Dispatchers.IO) {
    suspend fun invokeUnaryTier(
      model: com.google.firebase.ai.GenerativeModel,
      promptText: String,
      bitmaps: List<Bitmap>,
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
        parseJsonObjectSafely(rawText)
      } finally {
        heartbeatJob.cancel()
      }
    }

    // Optional external BYOK provider path
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
        return@withContext parseJsonObjectSafely(raw)
      } catch (e: CancellationException) {
        if (e !is TimeoutCancellationException) throw e
        if (!autoFallbackToKeylessFirebase) {
          throw IllegalStateException(
            "Stage $stageCode timed out after 95s on ${externalProvider.serviceName}. " +
              "Provider fallback is disabled to protect manuscript routing privacy."
          )
        }
        Log.w(TAG, "External provider timed out on $stageCode; falling back to Firebase.ai (user-authorized)", e)
      } catch (e: Exception) {
        if (!autoFallbackToKeylessFirebase) throw e
        Log.w(TAG, "External provider failed on $stageCode; falling back to Firebase.ai (user-authorized)", e)
      }
    }

    val safeBitmaps = sanitizeBitmapsForInference(attachedBitmaps, maxCount = 4)
    var tier1Err: Throwable? = null
    var tier2Err: Throwable? = null

    // TIER 1: Primary gemini-3.8-flash (unary JSON + responseSchema + ThinkingLevel.LOW)
    try {
      return@withContext invokeUnaryTier(
        model = primaryModel,
        promptText = primaryPrompt,
        bitmaps = safeBitmaps,
        tierHint = " [3.8 Flash]",
        timeoutMs = 70_000L
      )
    } catch (e: CancellationException) {
      if (e !is TimeoutCancellationException) throw e
      tier1Err = e
      Log.w(TAG, "Tier 1 (3.8 Flash) timed out on $stageCode", e)
    } catch (e: Exception) {
      tier1Err = e
      Log.w(TAG, "Tier 1 (3.8 Flash) failed on $stageCode: ${formatThrowableDetails(e)}", e)
    }

    // TIER 2: Fast gemini-3.5-flash-lite — preserve visual bitmaps unless Tier 1 failed on a vision/payload error
    val tier2Bitmaps = if (isLikelyVisionPayloadError(tier1Err)) emptyList() else safeBitmaps.take(2)
    onStageUpdate(
      stageCode,
      "$stageTitle • Switching to fast engine (gemini-3.5-flash-lite)...",
      startProgress + (endProgress - startProgress) * 0.30f
    )
    try {
      return@withContext invokeUnaryTier(
        model = liteSchemaModel,
        promptText = compactPrompt,
        bitmaps = tier2Bitmaps,
        tierHint = " [3.5 Lite]",
        timeoutMs = 55_000L
      )
    } catch (e: CancellationException) {
      if (e !is TimeoutCancellationException) throw e
      tier2Err = e
      Log.w(TAG, "Tier 2 (3.5 Lite schema) timed out on $stageCode", e)
    } catch (e: Exception) {
      tier2Err = e
      Log.w(TAG, "Tier 2 (3.5 Lite schema) failed on $stageCode: ${formatThrowableDetails(e)}", e)
    }

    // TIER 3: Unconstrained plain JSON fallback on gemini-3.5-flash-lite
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
        promptText = plainPrompt,
        bitmaps = emptyList(),
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
    onStageUpdate: (stageCode: String, stageLabel: String, progress: Float) -> Unit
  ): PaperReview {
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
    val forensicReport = withContext(Dispatchers.Default) {
      ManuscriptForensicEngine.analyzeManuscript(
        text = manuscriptText,
        isEmpiricalTrack = options.isEmpirical,
        replicationCode = replicationCodeText
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
      attachedPageCount = pass1Bitmaps.count { !it.isRecycled },
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
      attachedPageCount = pass1Bitmaps.take(2).count { !it.isRecycled },
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
      stageCode = "01a-01g",
      stageTitle = "Stage 01a–01g Appraisal$providerLabel",
      startProgress = 0.24f,
      endProgress = 0.54f,
      externalProvider = externalProvider,
      useExternalProvider = useExternalProvider,
      externalSystemPrompt = pass1KeysSpec,
      autoFallbackToKeylessFirebase = autoFallbackToKeylessFirebase,
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
      else -> "Authors Not Disclosed"
    }
    val resolvedYear = when {
      year.isNotBlank() -> year.trim()
      extractedYear.matches(Regex("""(19|20)\d{2}""")) -> extractedYear
      else -> "2026"
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
              stageCode = "01e-01fd",
              stageTitle = "Stage 01e–01fd Deep Quantitative Audit",
              startProgress = 0.56f,
              endProgress = 0.88f,
              externalProvider = externalProvider,
              useExternalProvider = useExternalProvider,
              externalSystemPrompt = quantKeysSpec,
              autoFallbackToKeylessFirebase = autoFallbackToKeylessFirebase,
              onStageUpdate = { _, _, _ -> /* Progress driven smoothly by primary synthesis pass */ }
            )
          }.onFailure { err ->
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
      modelVersion = if (useExternalProvider) externalProvider.modelId.ifBlank { externalProvider.serviceName } else "gemini-firebase-ai-logic",
      promptCoverage = promptCoverageLabel
    )

    val credibilityScore = adjudicated.weightedCredibilityScore.toLong()
    val verdict = adjudicated.resolvedVerdict
    val structuredFindingsJson = ReviewFindingCodec.encode(adjudicated.adjudicatedFindings)

    // Calibrated extraction confidence incorporating parser status, omissions, partial cuts, text quality, unsupported inputs & quote verifiability
    val extractionConfidence = computeCalibratedExtractionConfidence(
      manuscriptText = manuscriptText,
      coverageReport = coverageReport,
      omittedPromptPages = allImpactedPromptPages,
      wasPromptTrimmed = primaryPrepared.wasTrimmed,
      adjudicatedFindings = adjudicated.adjudicatedFindings,
      statisticalAudit = forensicReport.quantForensics.statisticalAudit
    )
    val anyJsonRepaired = pass1Result.wasRepaired || pass2Result.wasRepaired || (quantResultOrNull?.wasRepaired == true)
    val hasPartialOrUnverified = adjudicated.adjudicatedFindings.any {
      it.status == FindingStatus.CONTRADICTED ||
        it.status == FindingStatus.UNVERIFIED ||
        it.status == FindingStatus.PARTIALLY_SUPPORTED
    }
    val validationStatus = when {
      anyJsonRepaired || allImpactedPromptPages.isNotEmpty() || extractionConfidence <= 2 -> "PARTIALLY_VALIDATED"
      primaryPrepared.wasTrimmed || hasPartialOrUnverified -> "ADJUDICATED_WITH_CAVEATS"
      else -> "VERIFIED"
    }
    val coverageDisclosure = buildString {
      if (coverageReport != null) {
        append(coverageReport.toDisclosureLine())
      } else {
        val wordCnt = if (manuscriptText.isBlank()) 0 else manuscriptText.trim().split(Regex("""\s+""")).size
        append("Direct text input • %,d words • %,d chars • Extraction Confidence: %d/5".format(Locale.US, wordCnt, manuscriptText.length, extractionConfidence))
      }
      if (pass1VisionSelection.second.isNotEmpty() || pass2VisionSelection.second.isNotEmpty()) {
        val p1Tags = pass1VisionSelection.second.joinToString(",") { "P$it" }
        val p2Tags = pass2VisionSelection.second.joinToString(",") { "P$it" }
        append(" • Vision routed: Pass1[$p1Tags], Pass2[$p2Tags]")
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

    val stage01eTo01fdMathAudit = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage01eTo01fdMathAudit", "")
    )
    val stage01iTo01nCodeAndDataAudit = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage01iTo01nCodeAndDataAudit", "")
    )
    val stage02aNumbersCheck = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage02aNumbersCheck", "")
    )
    val stage02cBlueTeamDefense = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage02cBlueTeamDefense", "")
    )
    val stage02eTo03cVerifiedListV2 = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage02eTo03cVerifiedListV2", "")
    )
    val stage04aIsItCredibleEssay = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage04aIsItCredibleEssay", "")
    )
    val stage08aTo08bAlchemistEditorNote = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage08aTo08bAlchemistEditorNote", "")
    )
    val stage09aTo09cCopyeditReport = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("stage09aTo09cCopyeditReport", "")
    )
    val fortifyReviewerQuestions = com.example.util.DossierFormattingEngine.cleanAcademicProse(
      pass2Json.optString("fortifyReviewerQuestions", "")
    )

    val methodologyJustification = parsedRationales["methodology"]
      ?: "Evaluated design rigor, bias controls, and synthesis alignment (${forensicReport.studyDesign.riskOfBiasInstrument})."
    val significanceJustification = parsedRationales["significance"]
      ?: "Evaluated scientific contribution, generalizability, and clinical/policy utility."
    val clarityJustification = parsedRationales["clarity"]
      ?: "Evaluated reporting completeness (${forensicReport.studyDesign.reportingGuideline}), structure, and table/text precision."
    val originalityJustification = parsedRationales["originality"]
      ?: "Evaluated originality of the research question, dataset scope, and analytical contribution."
    val mathRigorJustification = parsedRationales["math"]
      ?: "Evaluated statistical estimation, CI geometry, heterogeneity/variance reporting, and numerical consistency."
    val reproducibilityJustification = parsedRationales["reproducibility"]
      ?: "Evaluated protocol transparency, search/code provenance, and replication readiness."

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
            appendLine("• Classification: Tier=${f.flawTier.ifBlank { "UNSPECIFIED" }} | Reporting=${f.reportingStatus.ifBlank { "UNSPECIFIED" }}")
          }
          if (f.overturnEvidence.isNotBlank() && f.status != FindingStatus.CONTRADICTED) {
            appendLine("• Minimum Evidence to Overturn: ${f.overturnEvidence}")
          }
          if (f.provenanceSummary.isNotBlank()) {
            appendLine("• Provenance: ${f.provenanceSummary}")
          }
          if (f.recommendedCorrection.isNotBlank() && f.status != FindingStatus.CONTRADICTED) {
            appendLine("• Recommended Remedy: ${f.recommendedCorrection}")
          }
        }
        appendLine()
      }
      if (stage02eTo03cVerifiedListV2.isNotBlank()) {
        appendLine(stage02eTo03cVerifiedListV2.trim())
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
        scoreBreakdown = adjudicated.scoreBreakdown
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
        appendLine(stage08aTo08bAlchemistEditorNote.trim())
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
      validationStatus = validationStatus,
      structuredFindingsJson = structuredFindingsJson,
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
      formattedDossier = formattedDossier
    )
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
      Authors: ${review.authors} (${review.publicationYear})
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
      - Authors: ${authors.ifBlank { "Anonymous" }} ($year)
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

    val useExternalProvider = externalProvider != null &&
      externalProvider.protocolType != com.example.data.model.ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS &&
      externalProvider.apiKey.isNotBlank() &&
      !enableSearchGrounding

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
      - Authors: ${review.authors} (${review.publicationYear}) • Venue: ${review.venueOrCitation}
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
    scoreBreakdown: ScoreCalculationBreakdown? = null
  ): String = buildString {
    val clean = { s: String -> com.example.util.DossierFormattingEngine.cleanAcademicProse(s) }

    // 1. Deterministic Pre-Flight Quantitative Forensics Table & Design-Aware Audit Trail
    val deterministicSummary = forensicReport.quantForensics.toDeterministicAuditSummary()
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
    val scoreBreakdown: ScoreCalculationBreakdown? = null
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
      var score = if (coverageReport != null) {
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

        // 1. Check false absence allegations against full manuscript text
        val combinedClaim = "${finding.title}. ${finding.allegation}"
        for ((allegationRegex, counterRegex, reasonLabel) in absenceCounterChecks) {
          if (allegationRegex.containsMatchIn(combinedClaim)) {
            val counterMatch = counterRegex.find(manuscriptText)
            if (counterMatch != null) {
              val snippetStart = (counterMatch.range.first - 60).coerceAtLeast(0)
              val snippetEnd = (counterMatch.range.last + 110).coerceAtMost(manuscriptText.length)
              val snippet = manuscriptText.substring(snippetStart, snippetEnd)
                .replace(Regex("""\s+"""), " ")
                .trim()
              val counterPage = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(manuscriptText, counterMatch.range.first)
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
              notes.add("Contradicted by source text: $reasonLabel.")
              break
            }
          }
        }

        // 2. Strict exact vs. fuzzy quote grounding for any finding claiming VERIFIED or PARTIALLY_SUPPORTED
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
                if (verification.pageMismatchWarning != null) {
                  updatedStatus = FindingStatus.PARTIALLY_SUPPORTED
                  notes.add(verification.pageMismatchWarning)
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

      // 3. Auto-inject deterministic QuantitativeForensicAnalyzer FAIL findings with careful epistemic status
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
              val hasCrossRefCorroboration = ci.crossReferenceNote.isNotBlank()
              val quoteCheck = verifyQuoteInManuscript(ci.rawClaim, manuscriptText, ci.pageNumber)
              val isCorroboratedVerified = hasCrossRefCorroboration &&
                quoteCheck.matchKind == QuoteMatchKind.EXACT &&
                extractionConfidence >= 4
              val deterministicStatus = if (isCorroboratedVerified) {
                FindingStatus.VERIFIED
              } else {
                FindingStatus.PARTIALLY_SUPPORTED
              }
              val detail = buildString {
                append(ci.note)
                if (hasCrossRefCorroboration) {
                  append(" Internal cross-reference discrepancy: ${ci.crossReferenceNote}")
                } else {
                  append(" Deterministic discrepancy requiring source reconciliation against primary tables, figures, units/transformations, and supplementary files.")
                }
              }
              verifiedFindings.add(
                0,
                ReviewFinding(
                  id = "Q-0$nextQuantIdx",
                  title = "Deterministic CI Discrepancy Requiring Source Reconciliation (${ci.rawClaim})",
                  allegation = detail,
                  severity = "CRITICAL",
                  status = deterministicStatus,
                  supportingEvidence = listOf(
                    EvidenceAnchor(
                      pageNumber = ci.pageNumber,
                      section = if (ci.pageNumber > 0) "Page ${ci.pageNumber}" else "Results",
                      exactQuote = ci.rawClaim,
                      startOffset = quoteCheck.startOffset,
                      endOffset = quoteCheck.endOffset
                    )
                  ),
                  methodologicalJustification = "A point estimate must lie within its 95% confidence interval [lower, upper]. Potential causes include a narrative transcription typo, unit/scale transformation mismatch, duplicate outcome mapping, or PDF extraction/OCR artifact.",
                  recommendedCorrection = "Reconcile the reported narrative estimate and CI directly against primary tables, forest plots/figures, and supplementary spreadsheets; issue a numerical erratum if confirmed in the published source.",
                  verificationNotes = if (isCorroboratedVerified) {
                    "Deterministic CI check confirmed via exact text match and corroborated by conflicting Figure/Table bounds."
                  } else {
                    "Deterministic discrepancy requiring manual reconciliation against original tables, figures, and supplements (uncorroborated by cross-reference or degraded extraction confidence)."
                  }
                )
              )
              nextQuantIdx++
            }
          }

        forensicReport.quantForensics.statcheckResults
          .filter { it.status == QuantFindingStatus.FAIL }
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
                  verificationNotes = "Deterministic discrepancy requiring source reconciliation against tables, supplements, and test specification."
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
        statAudit.reconstructions
          .filter { it.status == QuantFindingStatus.FAIL && it.findingType.isConfirmedError && !it.isScreeningSignal }
          .take(3)
          .forEach { rec ->
            val alreadyCovered = verifiedFindings.any {
              it.allegation.contains(rec.sourceEvidence.take(18), ignoreCase = true) ||
                it.title.contains(rec.calculatorName, ignoreCase = true)
            }
            if (!alreadyCovered) {
              val quoteCheck = verifyQuoteInManuscript(rec.sourceEvidence, manuscriptText, rec.pageNumber)
              val verifiedStatus = if (quoteCheck.matchKind == QuoteMatchKind.EXACT && extractionConfidence >= 3) {
                FindingStatus.VERIFIED
              } else {
                FindingStatus.PARTIALLY_SUPPORTED
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
                  recommendedCorrection = "Verify raw input counts/SEs against primary tables and statistical output and correct reported values.",
                  verificationNotes = "Executable numerical reconstruction (${rec.checkId}) flagged ${rec.findingType.name}."
                )
              )
            }
          }

        statAudit.crossLinks
          .filter { it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.INTERNAL_CONTRADICTION }
          .take(2)
          .forEach { link ->
            verifiedFindings.add(
              0,
              ReviewFinding(
                id = "S-0${nextStatIdx++}",
                title = "[${link.findingType.label}] ${link.linkType}",
                allegation = link.description,
                severity = "CRITICAL",
                status = if (extractionConfidence >= 3) FindingStatus.VERIFIED else FindingStatus.PARTIALLY_SUPPORTED,
                supportingEvidence = listOf(
                  EvidenceAnchor(
                    pageNumber = link.pageNumbers.firstOrNull() ?: 0,
                    section = link.sectionsInvolved.joinToString(" / "),
                    exactQuote = link.sourceQuotes.firstOrNull().orEmpty()
                  )
                ),
                methodologicalJustification = "Sample denominators, subgroup sums, and cross-section estimates must reconcile arithmetically.",
                recommendedCorrection = "Reconcile participant flow, exclusions, and denominators across Abstract, Methods, Results, and Tables.",
                verificationNotes = "Cross-section evidence link (${link.linkId}) detected internal contradiction."
              )
            )
          }

        statAudit.domainFindings
          .filter { it.status == QuantFindingStatus.FAIL && it.findingType == StatisticalFindingType.MODEL_MISMATCH }
          .take(2)
          .forEach { df ->
            verifiedFindings.add(
              0,
              ReviewFinding(
                id = "S-0${nextStatIdx++}",
                title = "[${df.findingType.label}] ${df.criterion}",
                allegation = df.detail,
                severity = "CRITICAL",
                status = if (extractionConfidence >= 3) FindingStatus.VERIFIED else FindingStatus.PARTIALLY_SUPPORTED,
                supportingEvidence = listOf(
                  EvidenceAnchor(
                    pageNumber = df.pageNumber,
                    section = df.domainModule,
                    exactQuote = df.evidenceQuote.ifBlank { df.criterion }
                  )
                ),
                methodologicalJustification = df.consequence,
                recommendedCorrection = df.recommendedAction,
                verificationNotes = "Domain statistical audit (${df.findingId}, ${statAudit.subDesign.displayName}) confirmed model/design mismatch."
              )
            )
          }
      }

      // 3c. Inject fatal/major epistemic contradictions & claim-overreach findings when score gate is active
      val effectiveEpistemic = epistemicReport ?: forensicReport?.quantForensics?.epistemicSafeguards
      if (applyStatisticalScoreGate && effectiveEpistemic != null && !effectiveEpistemic.stopAndAsk.shouldStopAndAsk) {
        var nextEpiIdx = 1
        effectiveEpistemic.contradictionMap.edges
          .filter { it.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION || it.severityTier == FlawSeverityTier.MATERIALLY_WEAKENS_CONFIDENCE }
          .take(2)
          .forEach { edge ->
            val alreadyCovered = verifiedFindings.any {
              it.title.contains(edge.contradictionType, ignoreCase = true) ||
                it.allegation.contains(edge.description.take(28), ignoreCase = true)
            }
            if (!alreadyCovered) {
              val isFatal = edge.severityTier == FlawSeverityTier.FATAL_TO_PRIMARY_CONCLUSION
              verifiedFindings.add(
                0,
                ReviewFinding(
                  id = "E-0${nextEpiIdx++}",
                  title = "[${edge.severityTier.displayLabel}] ${edge.contradictionType}",
                  allegation = edge.description,
                  severity = if (isFatal) "CRITICAL" else "MAJOR",
                  status = if (extractionConfidence >= 3) FindingStatus.VERIFIED else FindingStatus.PARTIALLY_SUPPORTED,
                  supportingEvidence = listOf(
                    EvidenceAnchor(
                      pageNumber = edge.pages.firstOrNull() ?: 0,
                      section = "${edge.fromSection} ↔ ${edge.toSection}",
                      exactQuote = edge.quotes.firstOrNull().orEmpty()
                    )
                  ),
                  methodologicalJustification = "Cross-section contradiction or claim-vs-design overreach detected by epistemic safeguard graph.",
                  recommendedCorrection = edge.overturnEvidence,
                  verificationNotes = "Epistemic contradiction edge (${edge.edgeId}).",
                  overturnEvidence = edge.overturnEvidence
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

      val breakdown = statAudit?.let {
        EvidenceDerivedScoreGate.evaluate(
          report = it,
          baseMathScore = rawMath,
          baseMethodologyScore = rawM,
          manuscriptText = manuscriptText
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

      val hasVerifiedCriticalError = finalFindings.any {
        it.status == FindingStatus.VERIFIED && it.severity == "CRITICAL"
      } || (applyStatisticalScoreGate && breakdown?.verdictCapReason != null && extractionConfidence >= 3)
      val hasPartiallySupportedCriticalDiscrepancy = finalFindings.any {
        it.status == FindingStatus.PARTIALLY_SUPPORTED && it.severity == "CRITICAL"
      } || (applyStatisticalScoreGate && breakdown?.verdictCapReason != null && extractionConfidence < 3)

      // Safeguard #15: Stop-and-Ask gate suspends strong verdicts when supplied material is incomplete/ambiguous
      val resolvedVerdict = if (applyStatisticalScoreGate && effectiveEpistemic?.stopAndAsk?.shouldStopAndAsk == true) {
        "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      } else {
        resolveVerdictForMode(
          rawVerdict = rawVerdict,
          compositeScore = exactComposite,
          reviewMode = reviewMode,
          hasVerifiedCriticalError = hasVerifiedCriticalError,
          hasPartiallySupportedCriticalDiscrepancy = hasPartiallySupportedCriticalDiscrepancy
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
        scoreBreakdown = breakdown
      )
    }

    /**
     * Explicit, deterministic decision-table verdict resolver strictly derived from weighted
     * score bands, verified critical errors, partially-supported critical discrepancies, and
     * [reviewMode] rules (any model-emitted [rawVerdict] is advisory only and ignored):
     *
     * - **POST_PUBLICATION_APPRAISAL**:
     *   - Verified CRITICAL error -> `REQUIRES_CORRECTION` (if score >= 40) else `LOW_CREDIBILITY`
     *   - Partially-supported CRITICAL discrepancy -> caps top tier (`>= 85`) at `SOUND_WITH_CAVEATS`
     *   - Clean bands: `>=85 HIGH_CREDIBILITY`, `70..84 SOUND_WITH_CAVEATS`, `55..69 REQUIRES_CORRECTION`, `40..54 MAJOR_CONCERNS`, `<40 LOW_CREDIBILITY`
     * - **CLINICAL_EVIDENCE_APPRAISAL (GRADE-aligned)**:
     *   - Base tier: `>=85 HIGH_CERTAINTY`, `68..84 MODERATE_CERTAINTY`, `48..67 LOW_CERTAINTY`, `<48 VERY_LOW_CERTAINTY`
     *   - Verified CRITICAL error applies a -2 GRADE downgrade (`HIGH -> LOW`, `MODERATE -> VERY_LOW`, `LOW -> VERY_LOW`)
     *   - Partially-supported CRITICAL discrepancy applies a -1 GRADE caveat downgrade (`HIGH -> MODERATE`, `MODERATE -> LOW`)
     * - **JOURNAL_REFEREE**:
     *   - Verified CRITICAL error -> `MAJOR_REVISION` (if score >= 45), `REJECT_AND_RESUBMIT` (40..44), `REJECT` (<40)
     *   - Partially-supported CRITICAL discrepancy -> caps `ACCEPT` (`>=85`) at `MINOR_REVISION`
     *   - Clean bands: `>=85 ACCEPT`, `72..84 MINOR_REVISION`, `55..71 MAJOR_REVISION`, `40..54 REJECT_AND_RESUBMIT`, `<40 REJECT`
     */
    internal fun resolveVerdictForMode(
      rawVerdict: String,
      compositeScore: Int,
      reviewMode: ReviewMode,
      hasVerifiedCriticalError: Boolean = false,
      hasPartiallySupportedCriticalDiscrepancy: Boolean = false
    ): String {
      val clamped = compositeScore.coerceIn(0, 100)
      return when (reviewMode) {
        ReviewMode.POST_PUBLICATION_APPRAISAL -> when {
          clamped < 40 -> "LOW_CREDIBILITY"
          hasVerifiedCriticalError -> "REQUIRES_CORRECTION"
          clamped >= 85 && !hasPartiallySupportedCriticalDiscrepancy -> "HIGH_CREDIBILITY"
          clamped >= 70 -> "SOUND_WITH_CAVEATS"
          clamped >= 55 -> "REQUIRES_CORRECTION"
          else -> "MAJOR_CONCERNS"
        }
        ReviewMode.CLINICAL_EVIDENCE_APPRAISAL -> {
          // GRADE levels: 3 = HIGH, 2 = MODERATE, 1 = LOW, 0 = VERY_LOW
          val baseGrade = when {
            clamped >= 85 -> 3
            clamped >= 68 -> 2
            clamped >= 48 -> 1
            else -> 0
          }
          val downgrade = when {
            hasVerifiedCriticalError -> 2
            hasPartiallySupportedCriticalDiscrepancy && baseGrade >= 2 -> 1
            else -> 0
          }
          when ((baseGrade - downgrade).coerceIn(0, 3)) {
            3 -> "HIGH_CERTAINTY"
            2 -> "MODERATE_CERTAINTY"
            1 -> "LOW_CERTAINTY"
            else -> "VERY_LOW_CERTAINTY"
          }
        }
        ReviewMode.JOURNAL_REFEREE -> when {
          clamped < 40 -> "REJECT"
          hasVerifiedCriticalError -> if (clamped >= 45) "MAJOR_REVISION" else "REJECT_AND_RESUBMIT"
          clamped >= 85 && !hasPartiallySupportedCriticalDiscrepancy -> "ACCEPT"
          clamped >= 72 -> "MINOR_REVISION"
          clamped >= 55 -> "MAJOR_REVISION"
          else -> "REJECT_AND_RESUBMIT"
        }
      }
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
        appendLine("Authors        : $authors ($year)")
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
