package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.data.model.PaperReview
import com.example.data.model.PipelineOptions
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
  private val pass2VerificationAndSynthesisSchema = Schema.obj(
    properties = mapOf(
      "credibilityScore" to Schema.integer(
        description = "Calibrated overall credibility score (0 to 100) consistent with the weighted 6-axis scores and surviving verified issues"
      ),
      "refereeConfidence" to Schema.integer(
        description = "Referee confidence on standard 1 to 5 academic peer-review scale"
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
        values = listOf("ACCEPT", "MINOR_REVISION", "MAJOR_REVISION", "REJECT_AND_RESUBMIT", "REJECT"),
        description = "Final editorial decision strictly aligned with credibilityScore and surviving [CRITICAL]/[MAJOR] issues"
      ),
      "rubricRationaleSummary" to Schema.string(
        description = "Six concise newline-separated 1-line axis rationales prefixed exactly with [RUBRIC:Methodology], [RUBRIC:Significance], [RUBRIC:Clarity], [RUBRIC:Originality], [RUBRIC:Math], [RUBRIC:Reproducibility]"
      ),
      "stage01eTo01fdMathAudit" to Schema.string(
        description = "Stage 01e–01fd Quantitative & Mathematical Audit: Concise newline-separated bullets auditing statistical estimators, pooling/derivation logic, bounds, assumptions, and sober verification. Never wrap plain acronyms like IQR or CI in $ signs."
      ),
      "stage01iTo01nCodeAndDataAudit" to Schema.string(
        description = "Stage 01i–01n & 07b Reproducibility, Provenance & Methodology Audit: Domain-appropriate bullets auditing protocol registration, data extraction/code transparency, split hygiene (for ML only), and bias risks."
      ),
      "stage02aNumbersCheck" to Schema.string(
        description = "Stage 02a Numbers Verifier: Newline-separated bullets verifying reported sample/study counts, medians/IQRs, ratios, percentages, and internal numerical consistency."
      ),
      "stage02cBlueTeamDefense" to Schema.string(
        description = "Stage 02c BLUE TEAM: Newline-separated bullets providing the strongest scholarly counterarguments defending the manuscript against each Red Team finding."
      ),
      "stage02eTo03cVerifiedListV2" to Schema.string(
        description = "Stage 02e–03c Adjudicated Verified List v2: Each surviving issue on its OWN NEWLINE prefixed with [CRITICAL], [MAJOR], or [MINOR] (with Title, Evidence Anchor, and Required Remedy), followed by dismissed Red Team false positives each on its OWN NEWLINE prefixed with [DISMISSED]."
      ),
      "stage04aIsItCredibleEssay" to Schema.string(
        description = "Stage 04a THE REVIEWER (Stage 06 Legal-Checked): Three distinct newline-separated blocks prefixed exactly with 'Executive Assessment:', 'The Bottom Line:', and 'Future Research Agenda:'."
      ),
      "stage08aTo08bAlchemistEditorNote" to Schema.string(
        description = "Stage 08a–08b Editor Decision Memo & Prioritized Revision Roadmap: Brief 'Editorial Decision Rationale:' paragraph followed by newline-separated action items prefixed with [MUST FIX], [SHOULD FIX], or [RECOMMENDED]."
      ),
      "stage09aTo09cCopyeditReport" to Schema.string(
        description = "Stage 09a–09c Proofreader & Copyedit: Two newline-separated sections prefixed with 'Extraction / Typesetting Artifacts:' and 'Substantive Prose Improvements:' with concrete bullet points."
      ),
      "fortifyReviewerQuestions" to Schema.string(
        description = "PhD-Skills Fortify: 4–5 sharp Reviewer 2 questions each on its OWN NEWLINE numbered '1. ', '2. ', '3. ', '4. ', followed by 'Recommended Sensitivity & Robustness Checks:' with 2–3 concise bullets."
      )
    ),
    optionalProperties = listOf("refereeConfidence", "rubricRationaleSummary")
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

  private fun condenseTextForPrompt(raw: String, maxChars: Int = 16_000): String {
    val clean = raw.trim()
    if (clean.length <= maxChars) return clean
    val headLen = (maxChars * 0.62).toInt()
    val tailLen = maxChars - headLen - 120
    return buildString {
      append(clean.take(headLen))
      append("\n\n[... ${clean.length - headLen - tailLen} middle characters condensed to keep stage latency fast ...]\n\n")
      append(clean.takeLast(tailLen.coerceAtLeast(1000)))
    }
  }

  private fun parseJsonObjectSafely(rawResponse: String): JSONObject {
    val extracted = ExternalApiGatewayClient.extractCleanJsonObject(rawResponse)
    runCatching { return JSONObject(extracted) }

    // Attempt balanced repair if stream was clipped at maxOutputTokens
    val firstBrace = extracted.indexOf('{')
    if (firstBrace != -1) {
      val slice = extracted.substring(firstBrace).trimEnd()
      val repaired = StringBuilder(slice)
      var inString = false
      var escaped = false
      var openBraces = 0
      for (ch in slice) {
        when {
          escaped -> escaped = false
          ch == '\\' && inString -> escaped = true
          ch == '"' -> inString = !inString
          !inString && ch == '{' -> openBraces++
          !inString && ch == '}' -> openBraces = (openBraces - 1).coerceAtLeast(0)
        }
      }
      if (inString) {
        if (escaped) repaired.append('\\')
        repaired.append('"')
      } else {
        // Drop trailing incomplete tokens such as `, "nextKey":` before closing braces
        val trimmed = repaired.toString().trimEnd()
        if (trimmed.endsWith(",")) {
          repaired.setLength(trimmed.length - 1)
        }
      }
      repeat(openBraces) { repaired.append('}') }
      runCatching { return JSONObject(repaired.toString()) }

      // Fallback recovery: truncate back to the last complete comma-separated property
      val lastComma = slice.lastIndexOf(',')
      if (lastComma > 0) {
        val candidate = StringBuilder(slice.substring(0, lastComma).trimEnd())
        var cOpenBraces = 0
        var cInStr = false
        var cEsc = false
        for (ch in candidate) {
          when {
            cEsc -> cEsc = false
            ch == '\\' && cInStr -> cEsc = true
            ch == '"' -> cInStr = !cInStr
            !cInStr && ch == '{' -> cOpenBraces++
            !cInStr && ch == '}' -> cOpenBraces = (cOpenBraces - 1).coerceAtLeast(0)
          }
        }
        if (!cInStr && cOpenBraces > 0) {
          repeat(cOpenBraces) { candidate.append('}') }
          runCatching { return JSONObject(candidate.toString()) }
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
          val maxDim = 768
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

  /**
   * Executes a structured JSON stage pass off the Main thread using unary generateContent()
   * (avoiding Ktor SSE stream decoding issues on complex responseSchema payloads) with a
   * live UI progress heartbeat and an automatic 3-tier fallback chain:
   *   Tier 1: gemini-3.8-flash (responseSchema + ThinkingLevel.LOW + optional vision bitmaps)
   *   Tier 2: gemini-3.5-flash-lite (responseSchema, text-only condensed prompt)
   *   Tier 3: gemini-3.5-flash-lite (unconstrained plain JSON prompt, zero schema/thinking config)
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
  ): JSONObject = withContext(Dispatchers.IO) {
    suspend fun invokeUnaryTier(
      model: com.google.firebase.ai.GenerativeModel,
      promptText: String,
      bitmaps: List<Bitmap>,
      tierHint: String,
      timeoutMs: Long
    ): JSONObject = coroutineScope {
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
        val raw = withTimeout(90_000L) {
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
          throw IllegalStateException("Stage $stageCode timed out after 90s on ${externalProvider.serviceName}.")
        }
        Log.w(TAG, "External provider timed out on $stageCode; falling back to Firebase.ai", e)
      } catch (e: Exception) {
        if (!autoFallbackToKeylessFirebase) throw e
        Log.w(TAG, "External provider failed on $stageCode; falling back to Firebase.ai", e)
      }
    }

    val safeBitmaps = sanitizeBitmapsForInference(attachedBitmaps, maxCount = 2)
    var tier1Err: Throwable? = null
    var tier2Err: Throwable? = null

    // TIER 1: Primary gemini-3.8-flash (unary JSON + responseSchema + ThinkingLevel.LOW)
    try {
      return@withContext invokeUnaryTier(
        model = primaryModel,
        promptText = primaryPrompt,
        bitmaps = safeBitmaps,
        tierHint = " [3.8 Flash]",
        timeoutMs = 55_000L
      )
    } catch (e: CancellationException) {
      if (e !is TimeoutCancellationException) throw e
      tier1Err = e
      Log.w(TAG, "Tier 1 (3.8 Flash) timed out on $stageCode", e)
    } catch (e: Exception) {
      tier1Err = e
      Log.w(TAG, "Tier 1 (3.8 Flash) failed on $stageCode: ${formatThrowableDetails(e)}", e)
    }

    // TIER 2: Fast gemini-3.5-flash-lite (unary JSON + responseSchema, text-only, no thinkingConfig)
    onStageUpdate(
      stageCode,
      "$stageTitle • Switching to fast engine (gemini-3.5-flash-lite)...",
      startProgress + (endProgress - startProgress) * 0.30f
    )
    try {
      return@withContext invokeUnaryTier(
        model = liteSchemaModel,
        promptText = compactPrompt,
        bitmaps = emptyList(),
        tierHint = " [3.5 Lite]",
        timeoutMs = 50_000L
      )
    } catch (e: CancellationException) {
      if (e !is TimeoutCancellationException) throw e
      tier2Err = e
      Log.w(TAG, "Tier 2 (3.5 Lite schema) timed out on $stageCode", e)
    } catch (e: Exception) {
      tier2Err = e
      Log.w(TAG, "Tier 2 (3.5 Lite schema) failed on $stageCode: ${formatThrowableDetails(e)}", e)
    }

    // TIER 3: Unconstrained plain JSON fallback on gemini-3.5-flash-lite (no schema/mime constraints)
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
        timeoutMs = 45_000L
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
    reviewStrictness: String = "AGGRESSIVE_REVIEWER2",
    options: PipelineOptions,
    externalProvider: com.example.data.model.AiApiProviderEntry? = null,
    autoFallbackToKeylessFirebase: Boolean = true,
    onStageUpdate: (stageCode: String, stageLabel: String, progress: Float) -> Unit
  ): PaperReview {
    val useExternalProvider = externalProvider != null &&
      externalProvider.protocolType != com.example.data.model.ApiProtocolType.FIREBASE_AI_LOGIC_KEYLESS &&
      externalProvider.apiKey.isNotBlank()

    // ==========================================================================
    // PASS 0: Deterministic Forensic Pre-Flight Engine (Stage 00a / 01c / 01e / 02a)
    // ==========================================================================
    onStageUpdate(
      "00a-00c",
      "Stage 00a–00c: Running Deterministic Forensic & Equation Pre-Flight Scanner...",
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

    // ==========================================================================
    // PASS 1: Stage 00 Metadata + Stage 01 Red Team Adversarial Attack
    //         (01a The Breaker, 01b The Butcher, 01c The Shredder,
    //          01d The Collector, 01g The Void)
    // ==========================================================================
    val providerLabel = if (useExternalProvider) {
      " [${externalProvider.serviceName} • ${externalProvider.modelId}]"
    } else {
      ""
    }
    val condensedManuscript = condenseTextForPrompt(manuscriptText, maxChars = 14_000)
    val compactManuscript = condenseTextForPrompt(manuscriptText, maxChars = 8_500)
    val pass1Bitmaps = pdfPageBitmaps.take(2)
    val pass2Bitmaps = if (manuscriptText.trim().length < 200) pdfPageBitmaps.take(1) else emptyList()

    onStageUpdate(
      "01a-01g",
      "Stage 01a–01g: Executing Red Team (Breaker, Butcher, Shredder, Void)$providerLabel...",
      0.24f
    )

    val pass1Prompt = buildPass1RedTeamPrompt(
      title = title,
      authors = authors,
      year = year,
      venueOrCitation = venueOrCitation,
      manuscriptText = condensedManuscript,
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
      manuscriptText = compactManuscript,
      forensicContext = forensicContext.take(2_000),
      reviewStrictness = reviewStrictness,
      attachedPageCount = 0,
      options = options
    )
    val pass1KeysSpec = "Return ONLY a valid JSON object with exact string keys: extractedTitle, extractedAuthors, extractedYear, studyDesignType, abstractSummary, researchQuestion, keyMethodology, coreContributions, stage01aBreaker, stage01bButcher, stage01cShredder, stage01dCollector, stage01gTheVoid."

    val pass1Json = executeResilientJsonPass(
      primaryModel = pass1RedTeamModel,
      liteSchemaModel = pass1FastFallbackModel,
      plainJsonModel = pass1PlainJsonFallbackModel,
      primaryPrompt = pass1Prompt,
      compactPrompt = pass1CompactPrompt,
      plainJsonDirective = pass1KeysSpec,
      attachedBitmaps = pass1Bitmaps,
      stageCode = "01a-01g",
      stageTitle = "Stage 01a–01g Red Team$providerLabel",
      startProgress = 0.24f,
      endProgress = 0.56f,
      externalProvider = externalProvider,
      useExternalProvider = useExternalProvider,
      externalSystemPrompt = pass1KeysSpec,
      autoFallbackToKeylessFirebase = autoFallbackToKeylessFirebase,
      onStageUpdate = onStageUpdate
    )

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
    //   • Pass 2A: Dedicated 8-Pillar Ultra-Deep Quantitative & Statistical Audit
    //              (Stage 01e–01fd + Stage 02a Exact Numbers Forensics)
    //   • Pass 2B: Blue Team Defense, Adjudication & Senior Editorial Synthesis
    //              (Stage 01i–01n, 02c–03c, 04a–09c)
    // ==========================================================================
    onStageUpdate(
      "01e-03c",
      if (options.enableMathAudit) {
        "Stage 01e–03c: Running Deep Quantitative/Statistical Audit & Blue Team Adjudication$providerLabel..."
      } else {
        "Stage 02a–03c: Running Blue Team Defense & Calibrated Adjudication$providerLabel..."
      },
      0.58f
    )

    val pass2Prompt = buildPass2VerificationPrompt(
      title = resolvedTitle,
      authors = resolvedAuthors,
      year = resolvedYear,
      venueOrCitation = resolvedVenue,
      studyDesignLabel = extractedDesign,
      manuscriptText = condensedManuscript,
      replicationCodeText = replicationCodeText.take(5_000),
      forensicContext = forensicContext,
      coreContributions = coreContributions,
      compiledRedTeamReport = compiledRedTeamReport.take(5_000),
      reviewStrictness = reviewStrictness,
      options = options
    )
    val pass2CompactPrompt = buildPass2VerificationPrompt(
      title = resolvedTitle,
      authors = resolvedAuthors,
      year = resolvedYear,
      venueOrCitation = resolvedVenue,
      studyDesignLabel = extractedDesign,
      manuscriptText = compactManuscript,
      replicationCodeText = replicationCodeText.take(2_500),
      forensicContext = forensicContext.take(2_200),
      coreContributions = coreContributions.take(1_200),
      compiledRedTeamReport = compiledRedTeamReport.take(3_200),
      reviewStrictness = reviewStrictness,
      options = options
    )
    val pass2KeysSpec = "Return ONLY a valid JSON object with exact keys: credibilityScore (int 0-100), refereeConfidence (int 1-5), noveltyScore (int 1-10), methodologyScore (int 1-10), mathRigorScore (int 1-10), reproducibilityScore (int 1-10), clarityScore (int 1-10), significanceScore (int 1-10), verdict (ACCEPT|MINOR_REVISION|MAJOR_REVISION|REJECT_AND_RESUBMIT|REJECT), rubricRationaleSummary, stage01eTo01fdMathAudit, stage01iTo01nCodeAndDataAudit, stage02aNumbersCheck, stage02cBlueTeamDefense, stage02eTo03cVerifiedListV2, stage04aIsItCredibleEssay, stage08aTo08bAlchemistEditorNote, stage09aTo09cCopyeditReport, fortifyReviewerQuestions."

    val quantKeysSpec = "Return ONLY a valid JSON object with exact keys: quantitativeRigorScore (int 1-10), estimatorAndModelAudit, numericAndStatcheckAudit, powerAndMultiplicityAudit, biasHeterogeneityAudit, formalMathRederivation, quantAuditMatrixRows, sensitivityPrescriptions, soberVerificationSummary."

    val (pass2Json, quantJsonOrNull) = coroutineScope {
      val quantDeferred = if (options.enableMathAudit) {
        async {
          runCatching {
            val quantPrompt = buildPass2QuantAuditPrompt(
              title = resolvedTitle,
              studyDesignLabel = extractedDesign,
              studyDesignArchetype = forensicReport.studyDesign,
              manuscriptText = condensedManuscript,
              replicationCodeText = replicationCodeText.take(4_000),
              forensicReport = forensicReport,
              redTeamExcerpt = compiledRedTeamReport.take(2_800),
              reviewStrictness = reviewStrictness
            )
            val quantCompactPrompt = buildPass2QuantAuditPrompt(
              title = resolvedTitle,
              studyDesignLabel = extractedDesign,
              studyDesignArchetype = forensicReport.studyDesign,
              manuscriptText = compactManuscript,
              replicationCodeText = replicationCodeText.take(1_800),
              forensicReport = forensicReport,
              redTeamExcerpt = compiledRedTeamReport.take(1_600),
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
              startProgress = 0.58f,
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
          startProgress = 0.58f,
          endProgress = 0.91f,
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

    onStageUpdate(
      "04a-09c",
      "Stage 04a–09c: 'Is It Credible?' Reviewer Essay, Stage 06 Legal Pass & Alchemist...",
      0.93f
    )

    val noveltyScore = pass2Json.optInt("noveltyScore", 7).coerceIn(1, 10)
    val methodologyScore = pass2Json.optInt("methodologyScore", 7).coerceIn(1, 10)
    val pass2MathScore = pass2Json.optInt("mathRigorScore", 7).coerceIn(1, 10)
    val specialistQuantScore = quantJsonOrNull?.optInt("quantitativeRigorScore", -1)?.takeIf { it in 1..10 }
    val mathRigorScore = if (specialistQuantScore != null) {
      ((pass2MathScore + specialistQuantScore * 2) / 3.0).toInt().coerceIn(1, 10)
    } else {
      pass2MathScore
    }
    val reproducibilityScore = pass2Json.optInt("reproducibilityScore", 6).coerceIn(1, 10)
    val clarityScore = pass2Json.optInt("clarityScore", 8).coerceIn(1, 10)
    val significanceScore = pass2Json.optInt("significanceScore", 7).coerceIn(1, 10)
    val refereeConfidence = pass2Json.optInt("refereeConfidence", 4).coerceIn(1, 5)

    // Calibrate overall credibilityScore so it stays mathematically coherent with the 6-axis weighted rubric
    val weightedRubricScore = (
      methodologyScore * 2.5 +
        significanceScore * 2.0 +
        clarityScore * 1.5 +
        noveltyScore * 1.5 +
        mathRigorScore * 1.5 +
        reproducibilityScore * 1.0
      ).toInt().coerceIn(0, 100)
    val rawCredibility = pass2Json.optInt("credibilityScore", weightedRubricScore).coerceIn(0, 100)
    val credibilityScore = if (kotlin.math.abs(rawCredibility - weightedRubricScore) > 10) {
      ((rawCredibility + weightedRubricScore) / 2).toLong().coerceIn(0L, 100L)
    } else {
      rawCredibility.toLong()
    }

    val rawVerdict = pass2Json.optString("verdict", "MAJOR_REVISION").trim().uppercase(Locale.US)
    val verdict = if (rawVerdict in setOf("ACCEPT", "MINOR_REVISION", "MAJOR_REVISION", "REJECT_AND_RESUBMIT", "REJECT")) {
      rawVerdict
    } else {
      when {
        credibilityScore >= 85 -> "ACCEPT"
        credibilityScore >= 72 -> "MINOR_REVISION"
        credibilityScore >= 55 -> "MAJOR_REVISION"
        credibilityScore >= 40 -> "REJECT_AND_RESUBMIT"
        else -> "REJECT"
      }
    }

    val rubricRationaleSummary = pass2Json.optString("rubricRationaleSummary", "").trim()
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

    val compiledBlueTeamCascade = buildString {
      appendLine("[STAGE 02e–03c — ADJUDICATED FINDINGS MATRIX & VERIFIED LIST v2]")
      appendLine(stage02eTo03cVerifiedListV2.trim())
      appendLine()
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
        calibratedQuantScore = mathRigorScore
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
        appendLine("[STAGE 01i–01n & 07b — REPRODUCIBILITY, PROVENANCE & METHODOLOGY X-RAY]")
        appendLine(stage01iTo01nCodeAndDataAudit.trim())
      }.trim()
    } else {
      "Reproducibility & Data Provenance Audit (Stages 01i–01n, 07b) skipped by user configuration."
    }

    val compiledEditorAndReviewerNote = buildString {
      appendLine("[6-AXIS CALIBRATED SCORECARD: Credibility=$credibilityScore/100 | Methodology=$methodologyScore/10 | Clarity=$clarityScore/10 | Significance=$significanceScore/10 | Novelty=$noveltyScore/10 | Math=$mathRigorScore/10 | Reproducibility=$reproducibilityScore/10]")
      appendLine("[CONFIDENCE:$refereeConfidence/5]")
      if (rubricRationaleSummary.isNotBlank()) {
        rubricRationaleSummary.lines()
          .map { it.trim() }
          .filter { it.startsWith("[RUBRIC:", ignoreCase = true) }
          .forEach { appendLine(it) }
      }
      appendLine()
      appendLine("[STAGE 04a — 'IS IT CREDIBLE?' ASSESSMENT & BOTTOM LINE (Stage 06 Legal-Checked)]")
      appendLine(stage04aIsItCredibleEssay.trim())
      if (options.enableEditorNote) {
        appendLine()
        appendLine("[STAGE 08a–08b — SENIOR EDITOR DECISION MEMO & PRIORITIZED REVISION ROADMAP]")
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

    onStageUpdate("10_render", "Stage 07 & 10: Formatting 'IS IT CREDIBLE?' Plain-Text Dossier...", 0.96f)

    val formattedDossier = renderPlainTextDossier(
      title = resolvedTitle,
      authors = resolvedAuthors,
      year = resolvedYear,
      venueOrCitation = resolvedVenue,
      isEmpirical = options.isEmpirical,
      credibilityScore = credibilityScore,
      noveltyScore = noveltyScore,
      methodologyScore = methodologyScore,
      mathRigorScore = mathRigorScore,
      reproducibilityScore = reproducibilityScore,
      clarityScore = clarityScore,
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

    onStageUpdate("DONE", "30-Stage Dossier Complete", 1.0f)

    return PaperReview(
      title = resolvedTitle,
      authors = resolvedAuthors,
      publicationYear = resolvedYear,
      venueOrCitation = resolvedVenue,
      isEmpirical = options.isEmpirical,
      credibilityScore = credibilityScore,
      verdict = verdict,
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
      "EMPIRICAL MANUSCRIPT — Execute full Stage 01a (The Breaker), Stage 01b (The Butcher), Stage 01c (The Shredder), Stage 01d (The Collector), and Stage 01g (The Void)."
    } else {
      "THEORETICAL / FORMAL MANUSCRIPT — Execute Stage 01a (The Breaker), Stage 01a_2 (Non-Empirical Formal Deep-Dive in place of 01b), Stage 01c (The Shredder on proof/definition consistency), Stage 01d (The Collector), and Stage 01g (The Void)."
    }

    val strictnessDirective = when (reviewStrictness) {
      "CONSTRUCTIVE_MENTOR" ->
        "Maintain high analytical rigor while framing each Red Team critique clearly so the author can diagnose and repair it."
      "BALANCED_AREA_CHAIR" ->
        "Operate at Senior Area Chair rigor: uncompromising on identification, leakage, and math errors, while proportionate on minor stylistic items."
      else ->
        "Adopt the authentic 'Yell at It / Reviewer 2' hostile adversarial Red Team posture: assume zero benefit of the doubt, interrogate every assumption, table, equation, and split, and surface every candidate flaw for downstream verification."
    }

    return """
      You are PASS 1 (Stage 00 Ingestion + Stage 01 Adversarial Red Team) of the upgraded 'isitcredible/reviewer2' & 'phd-skills' peer-review engine.
      $strictnessDirective
      ${if (attachedPageCount > 0) "IMPORTANT: $attachedPageCount rendered PDF page images are attached. Visually inspect titles, authors, equations, tables, figures, and footnotes directly alongside the extracted text." else ""}

      MANUSCRIPT METADATA HINTS (VERIFY & OVERRIDE FROM PAPER IF BLANK OR RAW FILENAME):
      - Title Hint: ${title.ifBlank { "Extract exact title from manuscript" }}
      - Authors Hint: ${authors.ifBlank { "Extract exact author names from manuscript" }}
      - Year Hint: ${year.ifBlank { "Extract publication year from manuscript" }}
      - Target Venue: ${venueOrCitation.ifBlank { options.targetVenue }}
      - Track: $trackDescription

      $forensicContext

      STRICT EDITORIAL STRUCTURE & TYPOGRAPHY CONTRACT:
      - DOMAIN PRECISION: Strictly apply the Detected Study Design audit standards above. NEVER inject irrelevant critiques (e.g., do NOT ask a bibliographic systematic review/meta-analysis for ML random seeds or neural network train/test splits).
      - CLEAN TYPOGRAPHY: NEVER wrap plain abbreviations or units (IQR, IQRs, CI, SD, OR, RR, HIC, LMIC, PRISMA, PROSPERO) in LaTeX $ dollar signs. Use crisp Unicode symbols (≤, ≥, ±, ×, κ, I², τ², β) in prose.
      - STRUCTURED NEWLINE BULLETS: Put EVERY contribution ([C1], [C2], [C3]) and EVERY Red Team finding (• [RT-1] ..., • [RT-2] ...) on its OWN NEWLINE (\n). Never run multiple numbered points together into one paragraph.

      EXECUTE STAGES 00a–01g WITH SURGICAL PRECISION:
      1. STAGE 00a–00b (Canonical Metadata, Executive Summary, Research Question & Methodology):
         - Extract `extractedTitle` (proper Title Case), `extractedAuthors` (real authors from paper), `extractedYear`, and `studyDesignType`.
         - Write a concise, exact executive summary, primary research question, and bulleted methodology.
      2. STAGE 00c (Claimed Contributions):
         - Enumerate concrete claimed contributions on separate lines: [C1] ..., [C2] ..., [C3] ...
      3. STAGE 01a — THE BREAKER (Theoretical & Conceptual Foundations):
         - 2–3 newline-separated bullets (`• [RT-1] ...`) attacking conceptual definitions, core assumptions, and framing validity.
      4. STAGE 01b — THE BUTCHER (Design, Identification / Synthesis & Construct Validity):
         - 2–3 newline-separated bullets (`• [RT-2] ...`) dissecting construct-to-measure mapping, pooling/identification validity, heterogeneity, confounders, or baseline fairness.
      5. STAGE 01c — THE SHREDDER (Procedural, Sample & Protocol Rigor):
         - 2–3 newline-separated bullets (`• [RT-3] ...`) auditing study/sample counts, screening/split rigor, attrition, risk-of-bias/protocol registration, and internal numeric alignment.
      6. STAGE 01d — THE COLLECTOR (Tables, Footnotes & Missingness Disclosures):
         - 2–3 newline-separated bullets (`• [RT-4] ...`) inspecting table structure, weighting disclosures, missing data ('ND'), and cross-reference accuracy.
      7. STAGE 01g — THE VOID (Omissions, Confounds & External Validity):
         - 2–3 newline-separated bullets (`• [RT-5] ...`) exposing omitted outcomes, unmeasured confounds, geographic/demographic skew, and generalizability boundaries.

      MANUSCRIPT TEXT:
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
    return """
      You are PASS 2 (Verification Cascade, Adjudication & Senior Editorial Synthesis) of the upgraded 'isitcredible/reviewer2' & 'phd-skills' peer-review engine.

      MANUSCRIPT:
      - Title: ${title.ifBlank { "Untitled Academic Manuscript" }}
      - Authors: ${authors.ifBlank { "Authors Not Disclosed" }} ($year)
      - Study Design: $studyDesignLabel
      - Venue: ${venueOrCitation.ifBlank { options.targetVenue }}
      - Strictness Calibration: $reviewStrictness

      $forensicContext

      STAGE 00c CLAIMED CONTRIBUTIONS:
      $coreContributions

      STAGE 01 RED TEAM CANDIDATE FINDINGS (FROM PASS 1):
      $compiledRedTeamReport

      MANDATORY COHERENCE, SCORING & FORMATTING CONTRACT:
      1. STRICT SCORE-VERDICT-SEVERITY COHERENCE:
         - Weighted Composite = 2.5×Methodology + 2.0×Significance + 1.5×Clarity + 1.5×Originality + 1.5×MathRigor + 1.0×Reproducibility (0–100 scale).
         - Set `credibilityScore` within ±5 points of the Weighted Composite.
         - Align `verdict` coherently: ACCEPT (≥85, 0 critical/major flaws), MINOR_REVISION (72–84, 0 critical, ≤2 fixable major reporting flaws), MAJOR_REVISION (55–71, ≥2 substantive methodological/major flaws), REJECT_AND_RESUBMIT (40–54, severe flaw requiring redesign), REJECT (<40, fatal unfixable flaws).
         - In `rubricRationaleSummary`, provide 6 newline-separated 1-line rationales prefixed strictly with:
           [RUBRIC:Methodology] ...
           [RUBRIC:Significance] ...
           [RUBRIC:Clarity] ...
           [RUBRIC:Originality] ...
           [RUBRIC:Math] ...
           [RUBRIC:Reproducibility] ...
      2. CLEAN TYPOGRAPHY: NEVER wrap plain acronyms/units (IQR, IQRs, CI, SD, OR, RR, HIC, LMIC, PRISMA) in $ LaTeX dollar signs. Use Unicode symbols (≤, ≥, ±, ×, κ, I², τ², β) cleanly.
      3. DOMAIN-EXACT AUDITING: Strictly apply $studyDesignLabel methodological criteria. Do NOT mention ML random seeds, neural hyperparameters, or train/test splits unless this manuscript actually uses machine learning.
      4. STRUCTURED NEWLINE OUTPUT FOR EVERY FIELD:
         - `stage02eTo03cVerifiedListV2`: Put EACH surviving verified issue on its OWN NEWLINE prefixed with `[CRITICAL]`, `[MAJOR]`, or `[MINOR]` using the format `[SEVERITY] [F1] Short Title — Exact finding, manuscript evidence anchor, and required remedy.` Then put EACH rejected Red Team false positive on its OWN NEWLINE prefixed with `[DISMISSED]`.
         - `stage04aIsItCredibleEssay`: Structure into three crisp newline-separated blocks prefixed exactly with `Executive Assessment:`, `The Bottom Line:`, and `Future Research Agenda:`. Enforce all 4 Stage 06 Legal Red Lines (critique only the work, never impute intent or misconduct).
         - `stage08aTo08bAlchemistEditorNote`: Start with `Editorial Decision Rationale:` (2–3 sentences explicitly reconciling the verdict and 6-axis scores), followed by newline-separated prioritized actions prefixed with `[MUST FIX]`, `[SHOULD FIX]`, or `[RECOMMENDED]`.
         - `fortifyReviewerQuestions`: Put 4–5 sharp, specific questions each on its OWN NEWLINE (`1. ...`, `2. ...`, `3. ...`, `4. ...`), followed by `Recommended Sensitivity & Robustness Checks:` with 2–3 newline bullets.
         - `stage09aTo09cCopyeditReport`: Two clearly labeled sections: `Extraction / Typesetting Artifacts:` and `Substantive Prose Improvements:` with concise newline bullets.
         - `stage01eTo01fdMathAudit`, `stage01iTo01nCodeAndDataAudit`, `stage02aNumbersCheck`, `stage02cBlueTeamDefense`: Use clean newline bullets (`• ...`) with concrete evidence anchors.

      ORIGINAL MANUSCRIPT TEXT FOR FACT-CHECKING (STAGE 03a):
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
    calibratedQuantScore: Int
  ): String = buildString {
    val clean = { s: String -> com.example.util.DossierFormattingEngine.cleanAcademicProse(s) }

    // 1. Deterministic Pre-Flight Quantitative Forensics Table (Exact Kotlin Math Engine)
    val deterministicSummary = forensicReport.quantForensics.toDeterministicAuditSummary()
    if (deterministicSummary.isNotBlank()) {
      appendLine(deterministicSummary)
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

  companion object {
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
