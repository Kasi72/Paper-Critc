package com.example.data.ai.stats

import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.semantic.ClaimCompatibilityStatus
import com.example.data.ai.semantic.SemanticValidityEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Phase 1 & Phase 2: Structured Statistical Claim Parser & Cross-Section Evidence Linker.
 *
 * Extracts every quantitative claim into a structured [QuantitativeClaim] ledger with
 * page/section/table anchors, supports alternative & scientific statistical notations,
 * parses pipe/tab-delimited tables, isolates cited/reference p-values from primary testing
 * families, reconstructs sample-size flows, and cross-links Abstract/Methods/Results/Tables.
 */
object StatisticalClaimParser {

  data class ParsedManuscriptLedger(
    val claims: List<QuantitativeClaim>,
    val twoByTwoTables: List<TwoByTwoTableClaim>,
    val metaStudyEffects: List<MetaStudyEffectClaim>,
    val sampleFlowRecords: List<SampleFlowRecord>,
    val crossLinks: List<EvidenceCrossLink>,
    val unsupportedDisclosures: List<String>,
    val globalTailSpec: TailSpecification,
    val globalAlpha: Double,
    val sanitizedResult: SemanticValidityEngine.SanitizedTextResult? = null,
    val totalDiscoveredClaims: Int = claims.size,
    val truncatedClaimCount: Int = 0,
    val truncatedPages: List<Int> = emptyList(),
    val skippedDiscoveryCategories: List<String> = emptyList()
  )

  private const val MAX_PER_FAMILY_DISCOVERY = 500

  private val sectionHeaderRegex = Regex(
    """(?im)^\s*(?:\d+(?:\.\d+)*\.?\s+)?(abstract|introduction|background|methods?|materials?\s+and\s+methods?|statistical\s+analysis|results?|findings?|discussion|conclusions?|limitations?|references|bibliography|works\s+cited|supplementary(?:\s+\w+)?|appendix|protocol)\b.*$"""
  )
  private val tableFigureAnchorRegex = Regex(
    """\b((?:Supplementary\s+)?(?:Table|Figure|Fig\.?|Appendix\s+Table|eTable|eFigure)\s*[A-Za-z]?\d+[a-z]?)\b""",
    RegexOption.IGNORE_CASE
  )
  private val referenceSectionStartRegex = Regex(
    """(?im)^\s*(?:\d+\.?\s+)?(references|bibliography|works\s+cited)\s*$"""
  )
  private val inlineCitationContextRegex = Regex(
    """(?:et\s+al\.?\s*\(?\d{4}\)?|\(\s*[A-Z][a-z]+(?:\s+(?:&|and)\s+[A-Z][a-z]+)?,?\s*\d{4}\s*\)|previously\s+(?:reported|shown|found)|prior\s+stud(?:y|ies)|earlier\s+(?:trial|study|work)|consistent\s+with\s+[A-Z][a-z]+|similar\s+to\s+[A-Z][a-z]+)""",
    RegexOption.IGNORE_CASE
  )

  // Scientific notation or decimal p-value token: e.g. 0.004, .004, 1.2e-4, 1.2 x 10^-4, 3.4 × 10⁻⁵
  private const val NUM_SCI = """(?:0?\.\d+|\d+(?:\.\d+)?)(?:\s*(?:[eE]\s*[-+]?\d+|[x×]\s*10\s*\^?\s*[-−+]?\d+))?"""

  // Alternative & standard test statistics:
  // t(28) = 1.75, p = .045 | t28 = 1.75; P=.004 | t_28 = -2.14, p = 1.2e-4
  private val tStatFlexibleRegex = Regex(
    """\bt\s*(?:\(\s*(\d+(?:\.\d+)?)\s*\)|[_]?\s*(\d+))\s*=\s*([-−+]?\d+(?:\.\d+)?)\s*[,;:]\s*p(?:-?value)?\s*([=<>≤≥])\s*($NUM_SCI)""",
    RegexOption.IGNORE_CASE
  )
  // z = 2.41, p = 0.016 | Z = -3.12; P = 1.8e-3
  private val zStatFlexibleRegex = Regex(
    """\bz\s*=\s*([-−+]?\d+(?:\.\d+)?)\s*[,;:]\s*p(?:-?value)?\s*([=<>≤≥])\s*($NUM_SCI)""",
    RegexOption.IGNORE_CASE
  )
  // χ²(3) = 12.2; P=.004 | chi2(1) = 4.10, p = 0.043 | χ²3 = 9.1, p = 0.028
  private val chiSqFlexibleRegex = Regex(
    """(?:chi\^?2|χ2|χ²)\s*(?:\(\s*(\d+(?:\.\d+)?)\s*(?:,\s*N\s*=\s*\d+)?\s*\)|[_]?\s*(\d+))\s*=\s*(\d+(?:\.\d+)?)\s*[,;:]\s*p(?:-?value)?\s*([=<>≤≥])\s*($NUM_SCI)""",
    RegexOption.IGNORE_CASE
  )
  // F(2, 45) = 5.12, p = 0.009 | F2,45 = 5.12; P = .009
  private val fStatFlexibleRegex = Regex(
    """\bF\s*(?:\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\)|[_]?(\d+)\s*,\s*(\d+))\s*=\s*(\d+(?:\.\d+)?)\s*[,;:]\s*p(?:-?value)?\s*([=<>≤≥])\s*($NUM_SCI)""",
    RegexOption.IGNORE_CASE
  )
  // r(48) = 0.42, p = 0.002 | r = 0.42 (n = 50, p = 0.002)
  private val rStatFlexibleRegex = Regex(
    """\br\s*\(\s*(\d+)\s*\)\s*=\s*([-−+]?0?\.\d+)\s*[,;:]\s*p(?:-?value)?\s*([=<>≤≥])\s*($NUM_SCI)""",
    RegexOption.IGNORE_CASE
  )

  // Regression coefficients with SE & optional CI/t/p:
  // β = 0.42 (SE = 0.11, 95% CI 0.20 to 0.64, p = 0.0001) or b = 0.42, SE = 0.11, p = 0.0001
  private val regressionBetaSeRegex = Regex(
    """(?:^|[\s(;,])(β|beta|coef(?:ficient)?|slope|b)\s*[=:]\s*([-−+]?\d+(?:\.\d+)?)\s*[,;(]\s*(?:robust\s+|cluster-?robust\s+)?SE\s*[=:]\s*(\d+(?:\.\d+)?)(?:[^\n;]{0,120})?""",
    RegexOption.IGNORE_CASE
  )

  // Effect sizes with CIs (with or without explicit "95%" label), capturing exact interval type (CI/CrI/UI/PI):
  // OR 2.10 (1.30–3.40), aHR = 0.74 (95% CI 0.58-0.94), OR = 1.40 (95% CrI 0.95–2.10)
  private val effectCiFlexibleRegex = Regex(
    """\b(aOR|OR|aRR|RR|aHR|HR|IRR|PR|SMD|MD|WMD|RD|ARR|AUC|AUROC|PR-AUC|AUPRC|C-statistic|beta|β|mean|median|rate|prevalence|pooled(?:\s+\w+)?)\s*(?:of\s*|was\s*|[=:]\s*)?\s*([-−+]?\d+(?:\.\d+)?)\s*\(\s*(?:(?:(\d{2})%\s*)?(CI|CrI|UI|PI|confidence\s+interval|credible\s+interval|uncertainty\s+interval|prediction\s+interval)\s*[:=,]?\s*)?([-−+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*([-−+]?\d+(?:\.\d+)?)(?:\s*[,;]\s*p(?:-?value)?\s*[=<>≤≥]\s*$NUM_SCI)?\s*\)""",
    RegexOption.IGNORE_CASE
  )

  // Narrative comma-separated CIs: "PR = 2.21, 95% CI 0.00 to 4.50", "OR = 1.80, 95% CrI 1.10–2.90"
  private val effectCiNarrativeRegex = Regex(
    """\b(aOR|OR|aRR|RR|aHR|HR|IRR|PR|SMD|MD|WMD|RD|ARR|AUC|AUROC|beta|β|mean|median|rate|prevalence|estimate|beds?|pooled(?:\s+\w+)?)\s*(?:of\s*|was\s*|[=:]\s*)\s*([-−+]?\d+(?:\.\d+)?)\s*[,;]\s*(?:with\s+(?:a\s+)?)?(?:(\d{2})%\s*)?(CI|CrI|UI|PI|confidence\s+interval|credible\s+interval|uncertainty\s+interval|prediction\s+interval)\s*(?:of\s*)?[:=]?\s*([-−+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-)\s*([-−+]?\d+(?:\.\d+)?)""",
    RegexOption.IGNORE_CASE
  )

  private fun parseIntervalType(rawToken: String, fullMatch: String): IntervalType {
    val norm = rawToken.trim().lowercase(Locale.US)
    return when {
      norm == "cri" || norm.contains("credible") -> IntervalType.CREDIBLE_INTERVAL
      norm == "ui" || norm.contains("uncertainty") -> IntervalType.UNCERTAINTY_INTERVAL
      norm == "pi" || norm.contains("prediction") -> IntervalType.PREDICTION_INTERVAL
      norm == "ci" || norm.contains("confidence") -> IntervalType.CONFIDENCE_INTERVAL
      Regex("""\bCrI\b|credible\s+interval""", RegexOption.IGNORE_CASE).containsMatchIn(fullMatch) -> IntervalType.CREDIBLE_INTERVAL
      Regex("""\bUI\b|uncertainty\s+interval""", RegexOption.IGNORE_CASE).containsMatchIn(fullMatch) -> IntervalType.UNCERTAINTY_INTERVAL
      Regex("""\bPI\b|prediction\s+interval""", RegexOption.IGNORE_CASE).containsMatchIn(fullMatch) -> IntervalType.PREDICTION_INTERVAL
      Regex("""\bCI\b|confidence\s+interval""", RegexOption.IGNORE_CASE).containsMatchIn(fullMatch) -> IntervalType.CONFIDENCE_INTERVAL
      else -> IntervalType.UNSPECIFIED
    }
  }

  // Proportions: 18/120 (15.0%) or 18 of 120 (15%)
  private val proportionRegex = Regex(
    """\b(\d{1,5})\s*(?:/|of)\s*(\d{1,6})\s*\(\s*(\d+(?:\.\d+)?)\s*%?\s*\)""",
    RegexOption.IGNORE_CASE
  )

  // 2x2 comparisons in narrative: "18/100 vs 35/100" or "treatment 18/100 vs control 35/100"
  private val twoByTwoPairRegex = Regex(
    """(\d{1,5})\s*/\s*(\d{1,6})\s*(?:\([^)]{0,30}\)\s*)?(?:vs\.?|versus|compared\s+(?:with|to))\s*(?:[A-Za-z0-9\s-]{0,24}?)?(\d{1,5})\s*/\s*(\d{1,6})""",
    RegexOption.IGNORE_CASE
  )
  // Explicit 2x2 cell syntax: a = 18, b = 82, c = 35, d = 65
  private val explicit2x2CellsRegex = Regex(
    """\ba\s*=\s*(\d+)\s*[,;]\s*b\s*=\s*(\d+)\s*[,;]\s*c\s*=\s*(\d+)\s*[,;]\s*d\s*=\s*(\d+)\b""",
    RegexOption.IGNORE_CASE
  )

  // Mean ± SD or Mean (SD)
  private val meanSdRegex = Regex(
    """\b(?:mean|average)\s*(?:[=:]|of|was)?\s*([-−+]?\d+(?:\.\d+)?)\s*(?:±|\+/-|\(\s*SD\s*[=:]?\s*)(\d+(?:\.\d+)?)\)?""",
    RegexOption.IGNORE_CASE
  )

  // Meta-analysis individual study lines: "Study 1: OR = 1.40 (1.05–1.86)" or "Smith 2021: d = 0.45, SE = 0.12"
  private val metaStudyCiRegex = Regex(
    """(?m)^\s*\|?\s*([A-Z][A-Za-z0-9\s.'-]{2,28}(?:\(\d{4}\)|\d{4}|\d+))\s*[:|]\s*(OR|RR|HR|SMD|MD|d|g|effect|logOR|theta)\s*[=:|]?\s*([-−+]?\d+(?:\.\d+)?)\s*(?:\(\s*(?:95%\s*CI\s*[:=]?\s*)?([-−+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*([-−+]?\d+(?:\.\d+)?)\s*\)|[,;|]\s*SE\s*[=:|]?\s*(\d+(?:\.\d+)?))""",
    RegexOption.IGNORE_CASE
  )

  // Standalone p-values for multiple testing family detection
  private val standalonePRegex = Regex(
    """\bp(?:-?value)?\s*([=<>≤≥])\s*($NUM_SCI)""",
    RegexOption.IGNORE_CASE
  )

  // Custom alpha threshold detection
  private val customAlphaRegex = Regex(
    """(?:α|alpha|significance\s+(?:level|threshold)|two-sided\s+alpha)\s*(?:of|was|set\s+at|[=:<])\s*(0?\.\d+)""",
    RegexOption.IGNORE_CASE
  )

  // Sample flow patterns
  private val flowScreenedRegex = Regex("""\b(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?|records?|articles?|studies)\s+(?:were\s+)?(?:screened|assessed\s+for\s+eligibility|identified)\b""", RegexOption.IGNORE_CASE)
  private val flowEnrolledRegex = Regex("""\b(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?|individuals?)\s+(?:were\s+)?(?:enrolled|randomized|randomised|included|recruited)\b""", RegexOption.IGNORE_CASE)
  private val flowAnalyzedRegex = Regex("""\b(\d{1,3}(?:,\d{3})*|\d{2,6})\s+(?:patients?|participants?|subjects?|individuals?)\s+(?:were\s+)?(?:analyzed|analysed|included\s+in\s+(?:the\s+)?(?:final|primary)\s+analysis|completed\s+follow-?up)\b""", RegexOption.IGNORE_CASE)
  private val flowLostRegex = Regex("""\b(\d{1,3}(?:,\d{3})*|\d{1,6})\s+(?:patients?|participants?|subjects?)?\s*(?:were\s+)?(?:lost\s+to\s+follow-?up|dropped\s+out|excluded\s+due\s+to\s+missing|withdrew|had\s+missing\s+outcome)\b""", RegexOption.IGNORE_CASE)
  private val totalNRegex = Regex("""\b(?:total\s+)?[Nn]\s*=\s*(\d{1,3}(?:,\d{3})*|\d{2,6})\b""")
  private val subgroupNRegex = Regex(
    """\b(?:subgroup|stratum|stratified|age\s*[<>≤≥]\s*\d+|male|female|men|women|stage\s+[I1-4V]+|high-risk|low-risk|arm\s+[A-C1-3]|treatment\s+(?:group|arm)|control\s+(?:group|arm)|intervention\s+(?:group|arm))\b[^.\n]{0,35}?\(\s*n\s*=\s*(\d{1,3}(?:,\d{3})*|\d{1,6})\s*\)""",
    RegexOption.IGNORE_CASE
  )

  // Table row parser (pipe-delimited markdown/extracted tables OR tab-delimited rows)
  private val pipeTableRowRegex = Regex("""(?m)^\s*\|(.+)\|\s*$""")
  private val tabTableRowRegex = Regex("""(?m)^[^\t\n]+\t[^\t\n]+(?:\t[^\t\n]+)+$""")

  fun parse(text: String): ParsedManuscriptLedger {
    val sanitized = SemanticValidityEngine.sanitizePdfExtractionNoise(text.replace("\r\n", "\n"))
    val clean = sanitized.text
    val lower = clean.lowercase(Locale.US)
    val refStartOffset = findReferenceSectionOffset(clean)

    val globalTailSpec = when {
      Regex("""\b(?:one-?sided|one-?tailed|1-?tailed)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> TailSpecification.ONE_SIDED
      Regex("""\b(?:two-?sided|two-?tailed|2-?tailed)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> TailSpecification.TWO_SIDED
      else -> TailSpecification.UNSPECIFIED
    }
    val globalAlpha = customAlphaRegex.find(clean)
      ?.groupValues?.getOrNull(1)
      ?.toDoubleOrNull()
      ?.takeIf { it in 1e-6..0.20 }
      ?: 0.05

    val sectionSpans = buildSectionSpans(clean)
    val claims = mutableListOf<QuantitativeClaim>()
    val twoByTwoTables = mutableListOf<TwoByTwoTableClaim>()
    val metaStudies = mutableListOf<MetaStudyEffectClaim>()
    val sampleFlow = mutableListOf<SampleFlowRecord>()
    val unsupportedDisclosures = mutableListOf<String>()

    var claimCounter = 1
    fun nextClaimId(): String = "C-%03d".format(Locale.US, claimCounter++)

    fun contextAt(offset: Int): ContextInfo {
      val page = QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(clean, offset)
      val section = resolveSection(sectionSpans, offset)
      val windowStart = (offset - 180).coerceAtLeast(0)
      val windowEnd = (offset + 180).coerceAtMost(clean.length)
      val window = clean.substring(windowStart, windowEnd)
      val lineStart = clean.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
      val lineEnd = clean.indexOf('\n', offset).let { if (it < 0) clean.length else it }
      val localLine = clean.substring(
        max(lineStart, (offset - 160).coerceAtLeast(0)),
        kotlin.math.min(lineEnd, (offset + 120).coerceAtMost(clean.length))
      )
      val precedingText = clean.substring((offset - 120).coerceAtLeast(0), offset)
      val tableFig = tableFigureAnchorRegex.findAll(precedingText).lastOrNull()?.groupValues?.getOrNull(1)
        ?: tableFigureAnchorRegex.find(clean.substring(offset, (offset + 60).coerceAtMost(clean.length)))?.groupValues?.getOrNull(1)
        ?: ""
      val inRefs = (refStartOffset >= 0 && offset >= refStartOffset) || section.equals("References", ignoreCase = true)
      val isCitedPrior = inRefs || isPriorStudyCitationWindow(clean, offset)
      val hierarchy = inferHierarchy(window)
      val adj = inferAdjustment(localLine)
      val tail = inferLocalTail(window, globalTailSpec)
      val alpha = customAlphaRegex.find(window)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it in 1e-6..0.20 } ?: globalAlpha
      val family = inferTestingFamily(section, tableFig, hierarchy, window)
      return ContextInfo(page, section, tableFig, isCitedPrior, hierarchy, adj, tail, alpha, family, window, localLine)
    }

    var totalDiscoveredCount = 0
    var truncatedCount = 0
    val truncatedPages = linkedSetOf<Int>()
    val skippedDiscoveryCategories = linkedSetOf<String>()

    fun prioritizeAndCap(
      matches: Sequence<MatchResult>,
      limit: Int,
      categoryLabel: String = "quantitative_claims"
    ): List<MatchResult> {
      val all = matches.toList()
      totalDiscoveredCount += all.size
      if (all.size <= limit) return all
      val ranked = all.sortedBy { m ->
        val ctx = contextAt(m.range.first)
        when {
          ctx.isReferenceOrCitation -> 5
          ctx.hierarchy == AnalysisHierarchy.PRIMARY -> 0
          ctx.section.equals("Results", ignoreCase = true) || ctx.tableFig.isNotBlank() -> 1
          ctx.section.equals("Abstract", ignoreCase = true) -> 2
          ctx.section.equals("Methods", ignoreCase = true) -> 3
          else -> 4
        }
      }
      val omitted = ranked.drop(limit)
      truncatedCount += omitted.size
      skippedDiscoveryCategories.add("$categoryLabel(-${omitted.size})")
      omitted.forEach { m ->
        val pg = contextAt(m.range.first).page
        if (pg > 0) truncatedPages.add(pg)
      }
      return ranked.take(limit)
    }

    // 1. Flexible Test-Statistic Claims (t, z, chi2, F, r)
    prioritizeAndCap(tStatFlexibleRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val dfStr = m.groupValues[1].ifBlank { m.groupValues[2] }
      val df = dfStr.toDoubleOrNull()
      val statVal = parseSignedDouble(m.groupValues[3])
      val op = m.groupValues[4]
      val pVal = parseScientificDouble(m.groupValues[5])
      val ctx = contextAt(m.range.first)
      val status = if (df != null && statVal != null && pVal != null && pVal in 0.0..1.0) ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          testStatisticName = "t",
          testStatisticValue = statVal,
          degreesOfFreedom = df,
          pValueOperator = op,
          pValue = pVal,
          tailSpec = ctx.tail,
          alphaThreshold = ctx.alpha,
          analysisType = "t-test",
          adjustedOrUnadjusted = ctx.adjustment,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim(),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = status,
          parseNote = if (isAdjustedPWindow(ctx.window)) "Multiplicity-adjusted p-value context detected." else "",
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "t", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    prioritizeAndCap(zStatFlexibleRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val statVal = parseSignedDouble(m.groupValues[1])
      val op = m.groupValues[2]
      val pVal = parseScientificDouble(m.groupValues[3])
      val ctx = contextAt(m.range.first)
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          testStatisticName = "z",
          testStatisticValue = statVal,
          pValueOperator = op,
          pValue = pVal,
          tailSpec = ctx.tail,
          alphaThreshold = ctx.alpha,
          analysisType = "z-test",
          adjustedOrUnadjusted = ctx.adjustment,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim(),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = if (statVal != null && pVal != null) ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL,
          parseNote = if (isAdjustedPWindow(ctx.window)) "Multiplicity-adjusted p-value context detected." else "",
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "z", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    prioritizeAndCap(chiSqFlexibleRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val df = m.groupValues[1].ifBlank { m.groupValues[2] }.toDoubleOrNull()
      val statVal = parseSignedDouble(m.groupValues[3])
      val op = m.groupValues[4]
      val pVal = parseScientificDouble(m.groupValues[5])
      val ctx = contextAt(m.range.first)
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          testStatisticName = "chi2",
          testStatisticValue = statVal,
          degreesOfFreedom = df,
          pValueOperator = op,
          pValue = pVal,
          tailSpec = TailSpecification.TWO_SIDED,
          alphaThreshold = ctx.alpha,
          analysisType = "chi-square",
          adjustedOrUnadjusted = ctx.adjustment,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim(),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = if (df != null && statVal != null && pVal != null) ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL,
          parseNote = if (isAdjustedPWindow(ctx.window)) "Multiplicity-adjusted p-value context detected." else "",
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "chi2", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    prioritizeAndCap(fStatFlexibleRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val df1 = m.groupValues[1].ifBlank { m.groupValues[3] }.toDoubleOrNull()
      val df2 = m.groupValues[2].ifBlank { m.groupValues[4] }.toDoubleOrNull()
      val statVal = parseSignedDouble(m.groupValues[5])
      val op = m.groupValues[6]
      val pVal = parseScientificDouble(m.groupValues[7])
      val ctx = contextAt(m.range.first)
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          testStatisticName = "F",
          testStatisticValue = statVal,
          degreesOfFreedom = df1,
          degreesOfFreedom2 = df2,
          pValueOperator = op,
          pValue = pVal,
          tailSpec = TailSpecification.TWO_SIDED,
          alphaThreshold = ctx.alpha,
          analysisType = "ANOVA/F-test",
          adjustedOrUnadjusted = ctx.adjustment,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim(),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = if (df1 != null && df2 != null && statVal != null && pVal != null) ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL,
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "F", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    prioritizeAndCap(rStatFlexibleRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val df = m.groupValues[1].toDoubleOrNull()
      val rVal = parseSignedDouble(m.groupValues[2])
      val op = m.groupValues[3]
      val pVal = parseScientificDouble(m.groupValues[4])
      val ctx = contextAt(m.range.first)
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          effectMeasure = "r",
          pointEstimate = rVal,
          testStatisticName = "r",
          testStatisticValue = rVal,
          degreesOfFreedom = df,
          pValueOperator = op,
          pValue = pVal,
          tailSpec = ctx.tail,
          alphaThreshold = ctx.alpha,
          analysisType = "Correlation",
          adjustedOrUnadjusted = ctx.adjustment,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim(),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = if (df != null && rVal != null && pVal != null) ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL,
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "r", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    // 2. Regression β + SE (+ optional CI / p)
    prioritizeAndCap(regressionBetaSeRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val beta = parseSignedDouble(m.groupValues[2])
      val se = parseSignedDouble(m.groupValues[3])
      val ctx = contextAt(m.range.first)
      val seEndInMatch = Regex("""SE\s*[=:]\s*\d+(?:\.\d+)?""", RegexOption.IGNORE_CASE).find(m.value)?.range?.last ?: 0
      val afterSe = if (seEndInMatch + 1 < m.value.length) m.value.substring(seEndInMatch + 1) else ""
      val ciMatch = Regex("""(?:(?:95%\s*)?CI\s*[:=]?\s*|\[\s*)([-−+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*([-−+]?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(afterSe)
      val pMatch = standalonePRegex.find(afterSe)
      val dfMatch = Regex("""\bdf\s*=\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(afterSe)
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          effectMeasure = "beta",
          pointEstimate = beta,
          standardError = se,
          ciLower = ciMatch?.groupValues?.getOrNull(1)?.let { parseSignedDouble(it) },
          ciUpper = ciMatch?.groupValues?.getOrNull(2)?.let { parseSignedDouble(it) },
          degreesOfFreedom = dfMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
          pValueOperator = pMatch?.groupValues?.getOrNull(1).orEmpty(),
          pValue = pMatch?.groupValues?.getOrNull(2)?.let { parseScientificDouble(it) },
          tailSpec = ctx.tail,
          alphaThreshold = ctx.alpha,
          analysisType = "Regression",
          adjustedOrUnadjusted = ctx.adjustment,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim().take(120),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = if (beta != null && se != null && se > 0.0) ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL,
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "beta", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    // 3. Effect sizes with CIs (parenthesized + narrative comma-separated, e.g. PR = 2.21, 95% CI 0.00 to 4.50)
    val effectCiRanges = mutableListOf<IntRange>()
    fun recordEffectCiMatch(m: MatchResult, requireExplicitCiLabel: Boolean) {
      if (effectCiRanges.any { existing -> m.range.first <= existing.last && m.range.last >= existing.first }) return
      effectCiRanges.add(m.range)
      val rawLabel = m.groupValues[1].trim()
      val label = rawLabel.replace(Regex("""(?i)^pooled\s+"""), "").trim().ifBlank { rawLabel }
      val point = parseSignedDouble(m.groupValues[2])
      val levelPct = m.groupValues[3].toDoubleOrNull()
      val intervalToken = m.groupValues[4]
      val lowerBound = parseSignedDouble(m.groupValues[5])
      val upperBound = parseSignedDouble(m.groupValues[6])
      val intervalType = parseIntervalType(intervalToken, m.value)
      val ctx = contextAt(m.range.first)
      val afterStart = m.range.last + 1
      val afterEnd = (afterStart + 45).coerceAtMost(clean.length)
      val afterText = clean.substring(afterStart, afterEnd)
      val pMatchInside = standalonePRegex.find(m.value)
      val pMatchAfter = if (pMatchInside == null) standalonePRegex.find(afterText) else null
      val pMatch = pMatchInside ?: pMatchAfter
      val hasExplicitIntervalLabel = intervalType != IntervalType.UNSPECIFIED
      val isAmbiguousInterval = !requireExplicitCiLabel && levelPct == null && !hasExplicitIntervalLabel &&
        (label.equals("mean", ignoreCase = true) || label.equals("median", ignoreCase = true))
      val parseStatus = when {
        point == null || lowerBound == null || upperBound == null -> ClaimParseStatus.UNSUPPORTED
        isAmbiguousInterval -> ClaimParseStatus.AMBIGUOUS
        !requireExplicitCiLabel && levelPct == null && !hasExplicitIntervalLabel -> ClaimParseStatus.PARTIAL
        else -> ClaimParseStatus.EXACT
      }
      val adj = when {
        label.startsWith("a", ignoreCase = true) && label.length in 3..4 -> AdjustmentStatus.ADJUSTED
        else -> ctx.adjustment
      }
      val quoteEnd = if (pMatchAfter != null) {
        (afterStart + pMatchAfter.range.last + 1).coerceAtMost(clean.length)
      } else {
        m.range.last + 1
      }
      val fullQuote = clean.substring(m.range.first, quoteEnd).trim()
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          effectMeasure = label,
          pointEstimate = point,
          ciLower = lowerBound,
          ciUpper = upperBound,
          ciLevel = (levelPct ?: 95.0) / 100.0,
          intervalType = intervalType,
          pValueOperator = pMatch?.groupValues?.getOrNull(1).orEmpty(),
          pValue = pMatch?.groupValues?.getOrNull(2)?.let { parseScientificDouble(it) },
          tailSpec = ctx.tail,
          alphaThreshold = ctx.alpha,
          analysisType = inferAnalysisTypeFromMeasure(label),
          adjustedOrUnadjusted = adj,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = fullQuote,
          startOffset = m.range.first,
          endOffset = quoteEnd,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = parseStatus,
          parseNote = if (isAmbiguousInterval) "Parenthesized range lacks explicit CI/IQR/SD label; classified AMBIGUOUS." else "",
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, label, adj, ctx.hierarchy)
        )
      )
    }

    prioritizeAndCap(effectCiFlexibleRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { recordEffectCiMatch(it, requireExplicitCiLabel = false) }
    prioritizeAndCap(effectCiNarrativeRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { recordEffectCiMatch(it, requireExplicitCiLabel = true) }

    // 4. Proportions (x / n (pct%))
    prioritizeAndCap(proportionRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val num = m.groupValues[1].toDoubleOrNull()
      val den = m.groupValues[2].toDoubleOrNull()
      val pct = m.groupValues[3].toDoubleOrNull()
      if (num != null && den != null && den > 0.0 && num <= den * 1.2) {
        val ctx = contextAt(m.range.first)
        claims.add(
          QuantitativeClaim(
            claimId = nextClaimId(),
            page = ctx.page,
            section = ctx.section,
            tableFigure = ctx.tableFig,
            numerator = num,
            denominator = den,
            effectMeasure = "Proportion (%)",
            pointEstimate = pct,
            analysisType = "Proportion",
            adjustedOrUnadjusted = AdjustmentStatus.UNADJUSTED,
            hierarchy = ctx.hierarchy,
            testingFamily = ctx.family,
            sourceQuote = m.value.trim(),
            startOffset = m.range.first,
            endOffset = m.range.last + 1,
            isFromReferenceOrCitation = ctx.isReferenceOrCitation,
            parseStatus = ClaimParseStatus.EXACT,
            compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "proportion", AdjustmentStatus.UNADJUSTED, ctx.hierarchy)
          )
        )
      }
    }

    // 5. Mean ± SD claims
    prioritizeAndCap(meanSdRegex.findAll(clean), MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      val mean = parseSignedDouble(m.groupValues[1])
      val sd = parseSignedDouble(m.groupValues[2])
      val ctx = contextAt(m.range.first)
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          effectMeasure = "Mean ± SD",
          pointEstimate = mean,
          standardDeviation = sd,
          analysisType = "Continuous summary",
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim(),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = if (mean != null && sd != null) ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL,
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "mean", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    // 6. Table-only rows (pipe-delimited & tab-delimited) — tracked through prioritizeAndCap
    val cappedTableRows = prioritizeAndCap(
      pipeTableRowRegex.findAll(clean) + tabTableRowRegex.findAll(clean),
      limit = 80,
      categoryLabel = "structured_table_rows"
    )
    parseStructuredTableRows(clean, cappedTableRows, ::contextAt, ::nextClaimId, claims, twoByTwoTables, metaStudies)

    // 7. Narrative 2x2 contingency pairs & explicit a,b,c,d cells (tracked through prioritizeAndCap)
    var tblIdx = twoByTwoTables.size + 1
    prioritizeAndCap(twoByTwoPairRegex.findAll(clean), limit = 120, categoryLabel = "two_by_two_pairs").forEach { m ->
      val e1 = m.groupValues[1].toIntOrNull() ?: return@forEach
      val n1 = m.groupValues[2].toIntOrNull() ?: return@forEach
      val e2 = m.groupValues[3].toIntOrNull() ?: return@forEach
      val n2 = m.groupValues[4].toIntOrNull() ?: return@forEach
      if (n1 in 2..10_000_000 && n2 in 2..10_000_000 && e1 in 0..n1 && e2 in 0..n2) {
        val ctx = contextAt(m.range.first)
        if (!ctx.isReferenceOrCitation) {
          val near = clean.substring(
            (m.range.first - 60).coerceAtLeast(0),
            (m.range.last + 160).coerceAtMost(clean.length)
          )
          val alreadyExists = twoByTwoTables.any { it.a == e1 && it.b == (n1 - e1) && it.c == e2 && it.d == (n2 - e2) }
          if (!alreadyExists) {
            twoByTwoTables.add(extract2x2Metadata("2x2-${tblIdx++}", ctx, e1, n1 - e1, e2, n2 - e2, near, m.value.trim()))
          }
        }
      }
    }
    prioritizeAndCap(explicit2x2CellsRegex.findAll(clean), limit = 60, categoryLabel = "explicit_2x2_cells").forEach { m ->
      val a = m.groupValues[1].toIntOrNull() ?: return@forEach
      val b = m.groupValues[2].toIntOrNull() ?: return@forEach
      val c = m.groupValues[3].toIntOrNull() ?: return@forEach
      val d = m.groupValues[4].toIntOrNull() ?: return@forEach
      val ctx = contextAt(m.range.first)
      if (!ctx.isReferenceOrCitation) {
        val near = clean.substring((m.range.first - 60).coerceAtLeast(0), (m.range.last + 160).coerceAtMost(clean.length))
        twoByTwoTables.add(extract2x2Metadata("2x2-${tblIdx++}", ctx, a, b, c, d, near, m.value.trim()))
      }
    }

    // 8. Individual study effects for Meta-Analysis pooling & LOO diagnostics (tracked through prioritizeAndCap)
    prioritizeAndCap(metaStudyCiRegex.findAll(clean), limit = 250, categoryLabel = "meta_study_effects").forEach { m ->
      val studyLabel = m.groupValues[1].trim()
      val measure = m.groupValues[2].trim().uppercase(Locale.US)
      val est = parseSignedDouble(m.groupValues[3]) ?: return@forEach
      val lo = m.groupValues[4].takeIf { it.isNotBlank() }?.let { parseSignedDouble(it) }
      val hi = m.groupValues[5].takeIf { it.isNotBlank() }?.let { parseSignedDouble(it) }
      val explicitSe = m.groupValues[6].takeIf { it.isNotBlank() }?.let { parseSignedDouble(it) }
      val isRatio = measure in setOf("OR", "RR", "HR", "IRR")
      val (effectOnScale, seOnScale) = when {
        explicitSe != null && explicitSe > 0.0 -> {
          if (isRatio && est > 0.0) ln(est) to explicitSe else est to explicitSe
        }
        lo != null && hi != null && hi > lo -> {
          if (isRatio && est > 0.0 && lo > 0.0 && hi > 0.0) {
            ln(est) to ((ln(hi) - ln(lo)) / (2.0 * 1.959964))
          } else {
            est to ((hi - lo) / (2.0 * 1.959964))
          }
        }
        else -> return@forEach
      }
      val ctx = contextAt(m.range.first)
      if (!ctx.isReferenceOrCitation && seOnScale > 1e-6 && metaStudies.none { it.studyLabel.equals(studyLabel, ignoreCase = true) }) {
        metaStudies.add(
          MetaStudyEffectClaim(
            studyLabel = studyLabel,
            effect = effectOnScale,
            standardError = seOnScale,
            isLogScale = isRatio,
            page = ctx.page,
            sourceQuote = m.value.trim()
          )
        )
      }
    }

    // 9. Standalone p-values not already captured by test-statistic claims (for family-scoped FDR/FWER)
    val capturedOffsets = claims.map { it.startOffset..it.endOffset }
    val uncapturedStandaloneP = standalonePRegex.findAll(clean).filter { m -> capturedOffsets.none { m.range.first in it } }
    prioritizeAndCap(uncapturedStandaloneP, MAX_PER_FAMILY_DISCOVERY).forEach { m ->
      if (capturedOffsets.any { m.range.first in it }) return@forEach
      val op = m.groupValues[1]
      val pVal = parseScientificDouble(m.groupValues[2]) ?: return@forEach
      val ctx = contextAt(m.range.first)
      claims.add(
        QuantitativeClaim(
          claimId = nextClaimId(),
          page = ctx.page,
          section = ctx.section,
          tableFigure = ctx.tableFig,
          pValueOperator = op,
          pValue = pVal,
          tailSpec = ctx.tail,
          alphaThreshold = ctx.alpha,
          analysisType = "Reported p-value",
          adjustedOrUnadjusted = ctx.adjustment,
          hierarchy = ctx.hierarchy,
          testingFamily = ctx.family,
          sourceQuote = m.value.trim(),
          startOffset = m.range.first,
          endOffset = m.range.last + 1,
          isFromReferenceOrCitation = ctx.isReferenceOrCitation,
          parseStatus = if (op == "=") ClaimParseStatus.EXACT else ClaimParseStatus.PARTIAL,
          parseNote = if (op != "=") "Inequality p-value ($op); exact test statistic not adjacent." else "",
          compoundKey = SemanticValidityEngine.extractCompoundKey(ctx.localLine, ctx.section, "p_value", ctx.adjustment, ctx.hierarchy)
        )
      )
    }

    // 10. Sample-size & denominator flow extraction (tracked through prioritizeAndCap)
    extractSampleFlow(clean, ::contextAt, sampleFlow) { seq, lim, cat -> prioritizeAndCap(seq, lim, cat) }

    // 11. Cross-section linkage & contradiction checks
    val crossLinks = buildCrossSectionLinks(clean, claims, sampleFlow, lower)

    // 12. Unsupported input disclosures (transparently flag missing raw tables/SEs/denominators & any cap truncation)
    detectUnsupportedInputs(clean, lower, claims, twoByTwoTables, metaStudies, unsupportedDisclosures)
    if (truncatedCount > 0) {
      val pageLabel = if (truncatedPages.isNotEmpty()) {
        " on page(s) ${truncatedPages.sorted().joinToString(", ")}"
      } else {
        ""
      }
      val catLabel = if (skippedDiscoveryCategories.isNotEmpty()) {
        " [${skippedDiscoveryCategories.joinToString(", ")}]"
      } else {
        ""
      }
      unsupportedDisclosures.add(
        "Quantitative discovery cap reached: $truncatedCount additional item(s)$pageLabel were omitted$catLabel (total discovered: $totalDiscoveredCount); quantitative coverage is incomplete."
      )
    }

    return ParsedManuscriptLedger(
      claims = claims,
      twoByTwoTables = twoByTwoTables,
      metaStudyEffects = metaStudies,
      sampleFlowRecords = sampleFlow,
      crossLinks = crossLinks,
      unsupportedDisclosures = unsupportedDisclosures.distinct(),
      globalTailSpec = globalTailSpec,
      globalAlpha = globalAlpha,
      sanitizedResult = sanitized,
      totalDiscoveredClaims = maxOf(totalDiscoveredCount, claims.size),
      truncatedClaimCount = truncatedCount,
      truncatedPages = truncatedPages.sorted(),
      skippedDiscoveryCategories = skippedDiscoveryCategories.toList()
    )
  }

  private fun parseStructuredTableRows(
    clean: String,
    allRowMatches: List<MatchResult>,
    contextAt: (Int) -> ContextInfo,
    nextClaimId: () -> String,
    claims: MutableList<QuantitativeClaim>,
    twoByTwoTables: MutableList<TwoByTwoTableClaim>,
    metaStudies: MutableList<MetaStudyEffectClaim>
  ) {
    var tblIdx = twoByTwoTables.size + 1

    for (m in allRowMatches) {
      val rowText = m.value.trim()
      if (rowText.contains("---")) continue
      val cells = if (rowText.contains("|")) {
        rowText.trim('|').split("|").map { it.trim() }.filter { it.isNotEmpty() }
      } else {
        rowText.split("\t").map { it.trim() }.filter { it.isNotEmpty() }
      }
      if (cells.size < 3) continue
      // Skip obvious header rows
      val firstLower = cells[0].lowercase(Locale.US)
      if (firstLower in setOf("variable", "outcome", "characteristic", "study", "model", "metric", "parameter", "group", "predictor")) continue

      val ctx = contextAt(m.range.first)
      if (ctx.isReferenceOrCitation) continue
      val rowLabel = cells[0].take(48)

      // Pattern A: Table row with two event/N cells -> 2x2 table!
      val fracCells = cells.mapNotNull { cell ->
        Regex("""(\d{1,5})\s*/\s*(\d{1,6})""").find(cell)?.let { fm ->
          val e = fm.groupValues[1].toIntOrNull()
          val n = fm.groupValues[2].toIntOrNull()
          if (e != null && n != null && n >= 2 && e in 0..n) e to n else null
        }
      }
      if (fracCells.size >= 2) {
        val (e1, n1) = fracCells[0]
        val (e2, n2) = fracCells[1]
        val alreadyExists = twoByTwoTables.any { it.a == e1 && it.b == (n1 - e1) && it.c == e2 && it.d == (n2 - e2) }
        if (!alreadyExists) {
          twoByTwoTables.add(
            extract2x2Metadata(
              id = "T2x2-${tblIdx++}",
              ctx = ctx.copy(tableFig = ctx.tableFig.ifBlank { "Table Row ($rowLabel)" }),
              a = e1,
              b = n1 - e1,
              c = e2,
              d = n2 - e2,
              nearText = rowText,
              quote = rowText.take(140)
            ).copy(label = rowLabel)
          )
        }
      }

      // Pattern B: Table row with numeric estimate + SE + optional p (regression / meta row)
      val rowHasCi = Regex("""([-−+]?\d+(?:\.\d+)?)\s*\(\s*([-−+]?\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*([-−+]?\d+(?:\.\d+)?)\s*\)""").find(rowText)
      val rowP = standalonePRegex.find(rowText) ?: cells.lastOrNull()?.let { last ->
        Regex("""^(0?\.\d+|<\s*0?\.\d+|\d+(?:\.\d+)?[eE][-+]?\d+)$""").find(last)
      }
      if (rowHasCi != null) {
        val est = parseSignedDouble(rowHasCi.groupValues[1])
        val lo = parseSignedDouble(rowHasCi.groupValues[2])
        val hi = parseSignedDouble(rowHasCi.groupValues[3])
        val pVal = rowP?.let { pm ->
          val raw = if (pm.groupValues.size >= 3) pm.groupValues[2] else pm.groupValues[1].removePrefix("<").trim()
          parseScientificDouble(raw)
        }
        val op = rowP?.let { pm ->
          if (pm.groupValues.size >= 3) pm.groupValues[1] else if (pm.value.contains("<")) "<" else "="
        }.orEmpty()
        val measure = inferMeasureFromRow(rowText)
        val alreadyCaptured = claims.any { abs(it.startOffset - m.range.first) < 40 && it.pointEstimate == est }
        if (!alreadyCaptured && est != null && lo != null && hi != null) {
          claims.add(
            QuantitativeClaim(
              claimId = nextClaimId(),
              page = ctx.page,
              section = if (ctx.section == "Body") "Tables" else ctx.section,
              tableFigure = ctx.tableFig.ifBlank { "Table Row" },
              outcome = rowLabel,
              effectMeasure = measure,
              pointEstimate = est,
              ciLower = lo,
              ciUpper = hi,
              pValueOperator = op,
              pValue = pVal,
              tailSpec = ctx.tail,
              alphaThreshold = ctx.alpha,
              analysisType = "Table-only estimate",
              adjustedOrUnadjusted = ctx.adjustment,
              hierarchy = ctx.hierarchy,
              testingFamily = ctx.family,
              sourceQuote = rowText.take(140),
              startOffset = m.range.first,
              endOffset = m.range.last + 1,
              isFromReferenceOrCitation = false,
              parseStatus = if (measure == "Estimate") ClaimParseStatus.PARTIAL else ClaimParseStatus.EXACT,
              compoundKey = SemanticValidityEngine.extractCompoundKey("$rowLabel $rowText ${ctx.window}", if (ctx.section == "Body") "Tables" else ctx.section, measure, ctx.adjustment, ctx.hierarchy)
            )
          )
        }
        // Also register meta-analysis study row if first cell looks like Study + Year
        if (Regex("""\b(19|20)\d{2}\b""").containsMatchIn(rowLabel) && est != null && lo != null && hi != null && hi > lo) {
          val isRatio = measure in setOf("OR", "RR", "HR", "IRR")
          if (!isRatio || (est > 0.0 && lo > 0.0 && hi > 0.0)) {
            val eff = if (isRatio) ln(est) else est
            val se = if (isRatio) (ln(hi) - ln(lo)) / (2.0 * 1.959964) else (hi - lo) / (2.0 * 1.959964)
            if (se > 1e-6 && metaStudies.none { it.studyLabel.equals(rowLabel, ignoreCase = true) }) {
              metaStudies.add(MetaStudyEffectClaim(rowLabel, eff, se, isRatio, null, ctx.page, rowText.take(120)))
            }
          }
        }
      } else if (cells.size >= 4) {
        // Pure numeric table row: Label | Beta | SE | p  (or Label | Mean | SD | p)
        val c1 = parseSignedDouble(cells[1])
        val c2 = parseSignedDouble(cells[2])
        val c3p = parseScientificDouble(cells[3].removePrefix("<").removePrefix("p=").removePrefix("P=").trim())
        if (c1 != null && c2 != null && c2 > 0.0 && c3p != null && c3p in 0.0..1.0) {
          val looksLikeRegression = rowText.contains("SE", ignoreCase = true) ||
            clean.substring((m.range.first - 220).coerceAtLeast(0), m.range.first)
              .contains(Regex("""\b(SE|Std\.?\s*Err|Beta|Coef|Estimate)\b""", RegexOption.IGNORE_CASE))
          if (looksLikeRegression) {
            claims.add(
              QuantitativeClaim(
                claimId = nextClaimId(),
                page = ctx.page,
                section = if (ctx.section == "Body") "Tables" else ctx.section,
                tableFigure = ctx.tableFig.ifBlank { "Table Row" },
                outcome = rowLabel,
                effectMeasure = "beta",
                pointEstimate = c1,
                standardError = c2,
                pValueOperator = if (cells[3].contains("<")) "<" else "=",
                pValue = c3p,
                tailSpec = ctx.tail,
                alphaThreshold = ctx.alpha,
                analysisType = "Table-only regression",
                adjustedOrUnadjusted = ctx.adjustment,
                hierarchy = ctx.hierarchy,
                testingFamily = ctx.family,
                sourceQuote = rowText.take(140),
                startOffset = m.range.first,
                endOffset = m.range.last + 1,
                isFromReferenceOrCitation = false,
                parseStatus = ClaimParseStatus.EXACT,
                compoundKey = SemanticValidityEngine.extractCompoundKey("$rowLabel $rowText ${ctx.window}", if (ctx.section == "Body") "Tables" else ctx.section, "beta", ctx.adjustment, ctx.hierarchy)
              )
            )
          }
        }
      }
    }
  }

  private fun extract2x2Metadata(
    id: String,
    ctx: ContextInfo,
    a: Int,
    b: Int,
    c: Int,
    d: Int,
    nearText: String,
    quote: String
  ): TwoByTwoTableClaim {
    val orMatch = Regex("""\b(?:a?OR|odds\s+ratio)\s*[=:]?\s*(\d+(?:\.\d+)?)(?:\s*\(\s*(?:95%\s*CI\s*[:=]?\s*)?(\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*(\d+(?:\.\d+)?)\s*\))?""", RegexOption.IGNORE_CASE).find(nearText)
    val rrMatch = Regex("""\b(?:a?RR|risk\s+ratio|relative\s+risk)\s*[=:]?\s*(\d+(?:\.\d+)?)(?:\s*\(\s*(?:95%\s*CI\s*[:=]?\s*)?(\d+(?:\.\d+)?)\s*(?:to|–|—|-|,)\s*(\d+(?:\.\d+)?)\s*\))?""", RegexOption.IGNORE_CASE).find(nearText)
    val arrMatch = Regex("""\b(?:ARR|absolute\s+risk\s+reduction)\s*[=:]?\s*([-−+]?\d+(?:\.\d+)?)\s*(%)?""", RegexOption.IGNORE_CASE).find(nearText)
    val nntMatch = Regex("""\bNNT\s*[=:]?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(nearText)
    val pMatch = standalonePRegex.find(nearText)
    val chiMatch = Regex("""(?:chi\^?2|χ2|χ²)\s*(?:\(\s*1\s*\))?\s*=\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(nearText)
    val rawArr = arrMatch?.groupValues?.getOrNull(1)?.let { parseSignedDouble(it) }
    val normalizedArr = if (rawArr != null && (arrMatch?.groupValues?.getOrNull(2) == "%" || abs(rawArr) > 1.0)) rawArr / 100.0 else rawArr
    return TwoByTwoTableClaim(
      tableId = id,
      page = ctx.page,
      section = ctx.section,
      tableFigure = ctx.tableFig,
      a = a,
      b = b,
      c = c,
      d = d,
      reportedOr = orMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
      reportedOrLower = orMatch?.groupValues?.getOrNull(2)?.toDoubleOrNull(),
      reportedOrUpper = orMatch?.groupValues?.getOrNull(3)?.toDoubleOrNull(),
      reportedRr = rrMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
      reportedRrLower = rrMatch?.groupValues?.getOrNull(2)?.toDoubleOrNull(),
      reportedRrUpper = rrMatch?.groupValues?.getOrNull(3)?.toDoubleOrNull(),
      reportedArr = normalizedArr,
      reportedNnt = nntMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
      reportedP = pMatch?.groupValues?.getOrNull(2)?.let { parseScientificDouble(it) },
      reportedChiSq = chiMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull(),
      sourceQuote = quote
    )
  }

  private fun extractSampleFlow(
    clean: String,
    contextAt: (Int) -> ContextInfo,
    out: MutableList<SampleFlowRecord>,
    capper: (Sequence<MatchResult>, Int, String) -> List<MatchResult>
  ) {
    fun addMatches(regex: Regex, stage: String) {
      capper(regex.findAll(clean), 15, "sample_flow_${stage.lowercase(Locale.US)}").forEach { m ->
        val n = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@forEach
        val ctx = contextAt(m.range.first)
        if (!ctx.isReferenceOrCitation && n > 0) {
          out.add(SampleFlowRecord(stage, m.value.trim().take(60), n, ctx.page, ctx.section, ctx.tableFig, m.value.trim()))
        }
      }
    }
    addMatches(flowScreenedRegex, "SCREENED")
    addMatches(flowEnrolledRegex, "ENROLLED")
    addMatches(flowAnalyzedRegex, "ANALYZED")
    addMatches(flowLostRegex, "MISSING_OR_LOST")
    capper(totalNRegex.findAll(clean), 20, "sample_flow_total_n").forEach { m ->
      val n = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@forEach
      val ctx = contextAt(m.range.first)
      if (!ctx.isReferenceOrCitation && n >= 10) {
        out.add(SampleFlowRecord("TOTAL_N", "N = $n", n, ctx.page, ctx.section, ctx.tableFig, m.value.trim()))
      }
    }
    capper(subgroupNRegex.findAll(clean), 20, "sample_flow_subgroup_n").forEach { m ->
      val n = m.groupValues[1].replace(",", "").toIntOrNull() ?: return@forEach
      val ctx = contextAt(m.range.first)
      if (!ctx.isReferenceOrCitation && n >= 1) {
        out.add(SampleFlowRecord("SUBGROUP", m.value.trim().take(60), n, ctx.page, ctx.section, ctx.tableFig, m.value.trim()))
      }
    }
  }

  private fun buildCrossSectionLinks(
    clean: String,
    claims: List<QuantitativeClaim>,
    flow: List<SampleFlowRecord>,
    lower: String
  ): List<EvidenceCrossLink> {
    val links = mutableListOf<EvidenceCrossLink>()
    var linkIdx = 1
    fun nextLinkId() = "XL-%02d".format(Locale.US, linkIdx++)

    // 1. Denominator drift across sections / tables (e.g., Abstract/Table 1 N=420 vs Table 2/Results N=385 unexplained)
    val totalRecords = flow.filter { it.stage in setOf("TOTAL_N", "ENROLLED", "ANALYZED") }
      .distinctBy { "${it.section}|${it.tableFigure}|${it.n}" }
    val enrolledN = flow.filter { it.stage == "ENROLLED" || it.stage == "TOTAL_N" }.maxOfOrNull { it.n }
    val analyzedRecords = flow.filter { it.stage == "ANALYZED" }
    val missingLostMax = flow.filter { it.stage == "MISSING_OR_LOST" }.maxOfOrNull { it.n }

    if (totalRecords.size >= 2) {
      val byTableOrSection = totalRecords.groupBy { it.tableFigure.ifBlank { it.section } }
      val distinctNs = byTableOrSection.mapValues { (_, list) -> list.maxOf { it.n } }
      val maxN = distinctNs.values.maxOrNull() ?: 0
      val minN = distinctNs.values.minOrNull() ?: 0
      if (maxN > 0 && minN > 0 && maxN != minN && (maxN - minN).toDouble() / maxN.toDouble() >= 0.03) {
        val hasAttritionExplanation = lower.contains("missing") || lower.contains("lost to follow") ||
          lower.contains("excluded") || lower.contains("complete-case") || lower.contains("per-protocol") ||
          lower.contains("attrition") || missingLostMax != null
        val diff = maxN - minN
        val accountedByLost = missingLostMax != null && abs(diff - missingLostMax) <= max(2, (maxN * 0.01).toInt())
        if (!accountedByLost) {
          val summary = distinctNs.entries.take(4).joinToString(", ") { "${it.key}: N=${it.value}" }
          links.add(
            EvidenceCrossLink(
              linkId = nextLinkId(),
              linkType = "DENOMINATOR_DRIFT",
              findingType = if (hasAttritionExplanation) StatisticalFindingType.REPORTING_OMISSION else StatisticalFindingType.INTERNAL_CONTRADICTION,
              status = if (hasAttritionExplanation) QuantFindingStatus.WARN else QuantFindingStatus.FAIL,
              pageNumbers = totalRecords.map { it.page }.filter { it > 0 }.distinct(),
              sectionsInvolved = distinctNs.keys.toList(),
              description = "Denominator changes across sections/tables ($summary; Δ = $diff participants)" +
                if (hasAttritionExplanation) " without exact numeric reconciliation to reported exclusions/attrition."
                else " with no missing-data, exclusion, or attrition accounting detected in text.",
              sourceQuotes = totalRecords.take(3).map { it.sourceQuote },
              isScreeningSignal = hasAttritionExplanation
            )
          )
        }
      }
    }

    // 2. Missing-data flow arithmetic: Enrolled - Lost != Analyzed
    if (enrolledN != null && missingLostMax != null && analyzedRecords.isNotEmpty()) {
      val analyzedN = analyzedRecords.first().n
      val expectedAnalyzed = enrolledN - missingLostMax
      if (analyzedN > enrolledN) {
        links.add(
          EvidenceCrossLink(
            linkId = nextLinkId(),
            linkType = "MISSING_DATA_FLOW_MISMATCH",
            findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
            status = QuantFindingStatus.FAIL,
            pageNumbers = flow.map { it.page }.filter { it > 0 }.distinct(),
            sectionsInvolved = flow.map { it.section }.distinct(),
            description = "Analyzed sample (n = $analyzedN) exceeds enrolled/randomized cohort (N = $enrolledN).",
            sourceQuotes = (flow.filter { it.stage in setOf("ENROLLED", "ANALYZED") }).take(3).map { it.sourceQuote }
          )
        )
      } else if (expectedAnalyzed > 0 && abs(analyzedN - expectedAnalyzed) > max(2, (enrolledN * 0.02).toInt())) {
        links.add(
          EvidenceCrossLink(
            linkId = nextLinkId(),
            linkType = "MISSING_DATA_FLOW_MISMATCH",
            findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
            status = QuantFindingStatus.FAIL,
            pageNumbers = flow.map { it.page }.filter { it > 0 }.distinct(),
            sectionsInvolved = flow.map { it.section }.distinct(),
            description = "Sample-flow arithmetic mismatch: enrolled N = $enrolledN minus reported exclusions/loss ($missingLostMax) implies n = $expectedAnalyzed, but analyzed n = $analyzedN (unexplained gap = ${abs(analyzedN - expectedAnalyzed)}).",
            sourceQuotes = flow.filter { it.stage in setOf("ENROLLED", "MISSING_OR_LOST", "ANALYZED") }.take(3).map { it.sourceQuote }
          )
        )
      }
    }

    // 3. Subgroup totals exceeding full N (mutually exclusive pairs like male+female or arm A+arm B, or any single subgroup > full N)
    val subgroupRecords = flow.filter { it.stage == "SUBGROUP" }
    val primaryN = enrolledN ?: flow.filter { it.stage == "TOTAL_N" }.maxOfOrNull { it.n }
    if (primaryN != null && subgroupRecords.isNotEmpty()) {
      val singleExceeding = subgroupRecords.firstOrNull { it.n > primaryN }
      if (singleExceeding != null) {
        links.add(
          EvidenceCrossLink(
            linkId = nextLinkId(),
            linkType = "SUBGROUP_EXCEEDS_TOTAL",
            findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
            status = QuantFindingStatus.FAIL,
            pageNumbers = listOf(singleExceeding.page).filter { it > 0 },
            sectionsInvolved = listOf(singleExceeding.section),
            description = "Subgroup count (${singleExceeding.label}, n = ${singleExceeding.n}) exceeds total study N = $primaryN.",
            sourceQuotes = listOf(singleExceeding.sourceQuote)
          )
        )
      } else {
        // Check complementary subgroup pairs (male+female, treatment+control, age< vs age>=)
        val pairs = listOf(
          "Sex strata" to (subgroupRecords.filter { it.label.contains(Regex("""\b(male|men)\b""", RegexOption.IGNORE_CASE)) && !it.label.contains("female", true) } to
            subgroupRecords.filter { it.label.contains(Regex("""\b(female|women)\b""", RegexOption.IGNORE_CASE)) }),
          "Treatment/Control arms" to (subgroupRecords.filter { it.label.contains(Regex("""\b(treatment|intervention|arm\s+a|arm\s+1)\b""", RegexOption.IGNORE_CASE)) } to
            subgroupRecords.filter { it.label.contains(Regex("""\b(control|placebo|arm\s+b|arm\s+2)\b""", RegexOption.IGNORE_CASE)) }),
          "Risk strata" to (subgroupRecords.filter { it.label.contains("high-risk", true) } to
            subgroupRecords.filter { it.label.contains("low-risk", true) })
        )
        for ((pairName, pairLists) in pairs) {
          val n1 = pairLists.first.firstOrNull()?.n
          val n2 = pairLists.second.firstOrNull()?.n
          if (n1 != null && n2 != null && (n1 + n2) > primaryN) {
            links.add(
              EvidenceCrossLink(
                linkId = nextLinkId(),
                linkType = "SUBGROUP_EXCEEDS_TOTAL",
                findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
                status = QuantFindingStatus.FAIL,
                pageNumbers = listOfNotNull(pairLists.first.firstOrNull()?.page, pairLists.second.firstOrNull()?.page).filter { it > 0 }.distinct(),
                sectionsInvolved = listOfNotNull(pairLists.first.firstOrNull()?.section, pairLists.second.firstOrNull()?.section).distinct(),
                description = "$pairName subgroup sum (n₁=$n1 + n₂=$n2 = ${n1 + n2}) exceeds total study denominator N = $primaryN.",
                sourceQuotes = listOfNotNull(pairLists.first.firstOrNull()?.sourceQuote, pairLists.second.firstOrNull()?.sourceQuote)
              )
            )
          }
        }
      }
    }

    // 4. Duplicate cohort / overlapping trial population detection (in meta-analyses & pooled cohorts)
    val cohortIdMatches = Regex("""\b(NCT\d{6,10}|UK\s+Biobank|NHANES\s+\d{4}(?:[-–]\d{4})?|Framingham\s+(?:Heart\s+)?Study|Nurses'?\s+Health\s+Study|WHI|SPRINT|ACCORD|ALLHAT|MIMIC-(?:III|IV))\b""", RegexOption.IGNORE_CASE)
      .findAll(clean)
      .map { it.value.trim().uppercase(Locale.US) to it.range.first }
      .toList()
    val duplicateRegistry = cohortIdMatches.groupBy { it.first }
      .filter { (_, hits) ->
        hits.size >= 2 && (lower.contains("meta-analysis") || lower.contains("pooled") || lower.contains("included studies") || lower.contains("two cohorts") || lower.contains("separate studies"))
      }
    if (duplicateRegistry.isNotEmpty() && !lower.contains("deduplicat") && !lower.contains("excluded overlapping") && !lower.contains("removed duplicate cohort")) {
      val cohorts = duplicateRegistry.keys.take(3).joinToString(", ")
      val explicitlyDoubleCounted = lower.contains("same cohort") || lower.contains("overlapping sample") ||
        lower.contains("both reports from") || duplicateRegistry.values.any { hits ->
          hits.count { (_, off) ->
            val w = clean.substring((off - 80).coerceAtLeast(0), (off + 80).coerceAtMost(clean.length))
            w.contains("|") || w.contains("Study", true) || w.contains("20")
          } >= 2
        }
      links.add(
        EvidenceCrossLink(
          linkId = nextLinkId(),
          linkType = "DUPLICATE_COHORT_OR_ANALYSIS",
          findingType = if (explicitlyDoubleCounted) StatisticalFindingType.MODEL_MISMATCH else StatisticalFindingType.POSSIBLE_BIAS,
          status = if (explicitlyDoubleCounted) QuantFindingStatus.FAIL else QuantFindingStatus.WARN,
          pageNumbers = duplicateRegistry.values.flatten().map { QuantitativeForensicAnalyzer.resolvePageNumberAtOffset(clean, it.second) }.filter { it > 0 }.distinct(),
          sectionsInvolved = listOf("Results", "Tables"),
          description = "Potential duplicate/overlapping cohort or trial identifier ($cohorts) appears across multiple pooled entries without documented deduplication or multilevel nesting (risks unit-of-analysis variance understatement).",
          sourceQuotes = listOf("Repeated cohort identifier(s): $cohorts"),
          isScreeningSignal = !explicitlyDoubleCounted
        )
      )
    }

    // 5. Adjusted vs. Unadjusted estimate divergence / reversal check
    val activeEffects = claims.filter { !it.isFromReferenceOrCitation && it.pointEstimate != null && it.effectMeasure.isNotBlank() }
    val unadjusted = activeEffects.filter { it.adjustedOrUnadjusted == AdjustmentStatus.UNADJUSTED }
    val adjusted = activeEffects.filter { it.adjustedOrUnadjusted == AdjustmentStatus.ADJUSTED }
    if (unadjusted.isNotEmpty() && adjusted.isNotEmpty()) {
      for (u in unadjusted.take(5)) {
        val matchAdj = adjusted.firstOrNull { a ->
          a.effectMeasure.removePrefix("a").equals(u.effectMeasure.removePrefix("a"), ignoreCase = true)
        } ?: continue
        val uVal = u.pointEstimate ?: continue
        val aVal = matchAdj.pointEstimate ?: continue
        val isRatio = u.effectMeasure.uppercase(Locale.US) in setOf("OR", "RR", "HR", "IRR")
        val directionFlipped = if (isRatio) (uVal > 1.0 && aVal < 1.0) || (uVal < 1.0 && aVal > 1.0) else (uVal > 0.0 && aVal < 0.0) || (uVal < 0.0 && aVal > 0.0)
        val relShift = if (isRatio && uVal > 0 && aVal > 0) abs(ln(aVal) - ln(uVal)) else abs(aVal - uVal) / max(abs(uVal), 0.1)
        if (directionFlipped || relShift >= 0.35) {
          links.add(
            EvidenceCrossLink(
              linkId = nextLinkId(),
              linkType = "ADJUSTED_VS_UNADJUSTED_SHIFT",
              findingType = StatisticalFindingType.POSSIBLE_BIAS,
              status = QuantFindingStatus.WARN,
              pageNumbers = listOf(u.page, matchAdj.page).filter { it > 0 }.distinct(),
              sectionsInvolved = listOf(u.section, matchAdj.section).distinct(),
              description = "Substantial shift between unadjusted (${u.sourceQuote}) and adjusted (${matchAdj.sourceQuote}) estimates" +
                if (directionFlipped) " including sign/direction reversal across null — audit covariate selection for confounding vs collider/mediator over-adjustment."
                else " — verify covariate adjustment set and residual confounding sensitivity.",
              sourceQuotes = listOf(u.sourceQuote, matchAdj.sourceQuote),
              isScreeningSignal = true
            )
          )
          break
        }
      }
    }

    // 6. Abstract vs. Results/Tables point-estimate contradiction for the same effect measure
    // Revision B strict semantic matching: two claims are compared for FAIL contradiction ONLY when
    // CompoundClaimKey.evaluateCompatibility(...) == COMPATIBLE (explicitly observed equal fields;
    // `unspecified == unspecified` returns AMBIGUOUS and NEVER supports a hard contradiction!).
    val abstractEffects = activeEffects.filter { it.section.equals("Abstract", true) && it.ciLower != null && it.ciUpper != null }
    val resultsEffects = activeEffects.filter { it.section in setOf("Results", "Tables") && it.ciLower != null && it.ciUpper != null }
    for (absClaim in abstractEffects.take(4)) {
      val matchingRes = resultsEffects.firstOrNull { r ->
        r.effectMeasure.equals(absClaim.effectMeasure, ignoreCase = true) &&
          absClaim.pointEstimate != null && r.pointEstimate != null &&
          absClaim.ciLower != null && r.ciLower != null &&
          abs(absClaim.ciLower - r.ciLower) < 0.02 && abs((absClaim.ciUpper ?: 0.0) - (r.ciUpper ?: 0.0)) < 0.02 &&
          abs(absClaim.pointEstimate - r.pointEstimate) > 0.05 &&
          (absClaim.compoundKey != null && r.compoundKey != null &&
            absClaim.compoundKey.evaluateCompatibility(r.compoundKey) != ClaimCompatibilityStatus.INCOMPATIBLE)
      }
      val absKey = absClaim.compoundKey
      val resKey = matchingRes?.compoundKey
      if (matchingRes != null && absKey != null && resKey != null) {
        val compat = absKey.evaluateCompatibility(resKey)
        if (compat == ClaimCompatibilityStatus.COMPATIBLE) {
          links.add(
            EvidenceCrossLink(
              linkId = nextLinkId(),
              linkType = "CROSS_SECTION_CONTRADICTION",
              findingType = StatisticalFindingType.INTERNAL_CONTRADICTION,
              status = QuantFindingStatus.FAIL,
              pageNumbers = listOf(absClaim.page, matchingRes.page).filter { it > 0 }.distinct(),
              sectionsInvolved = listOf(absClaim.section, matchingRes.section),
              description = "Abstract (${absClaim.sourceQuote}) and ${matchingRes.section} (${matchingRes.sourceQuote}) share identical CI bounds and strictly compatible compound claim key (${absKey.toKeyLabel()}) but report conflicting point estimates.",
              sourceQuotes = listOf(absClaim.sourceQuote, matchingRes.sourceQuote)
            )
          )
        } else if (compat == ClaimCompatibilityStatus.AMBIGUOUS) {
          val missingFields = absKey.unspecifiedComparisonFields(resKey)
          links.add(
            EvidenceCrossLink(
              linkId = nextLinkId(),
              linkType = "AMBIGUOUS_CROSS_SECTION_SHIFT",
              findingType = StatisticalFindingType.UNSUPPORTED_TO_ASSESS,
              status = QuantFindingStatus.WARN,
              pageNumbers = listOf(absClaim.page, matchingRes.page).filter { it > 0 }.distinct(),
              sectionsInvolved = listOf(absClaim.section, matchingRes.section),
              description = "Abstract (${absClaim.sourceQuote}) and ${matchingRes.section} (${matchingRes.sourceQuote}) differ in point estimate with similar CI bounds, but comparison is AMBIGUOUS because critical compound-key field(s) are unspecified (${missingFields.joinToString(", ")}); not escalated to contradiction.",
              sourceQuotes = listOf(absClaim.sourceQuote, matchingRes.sourceQuote),
              isScreeningSignal = true
            )
          )
        }
      }
    }

    return links
  }

  private fun detectUnsupportedInputs(
    clean: String,
    lower: String,
    claims: List<QuantitativeClaim>,
    twoByTwoTables: List<TwoByTwoTableClaim>,
    metaStudies: List<MetaStudyEffectClaim>,
    disclosures: MutableList<String>
  ) {
    val activeClaims = claims.filter { !it.isFromReferenceOrCitation }
    val hasPOnly = activeClaims.count { it.pValue != null && it.testStatisticValue == null && it.pointEstimate == null }
    if (hasPOnly >= 3 && activeClaims.none { it.testStatisticValue != null }) {
      disclosures.add(
        "$hasPOnly p-value(s) were reported without accompanying test statistics, degrees of freedom, or standard errors; exact statcheck p-value recomputation is UNSUPPORTED without primary tables or supplementary output."
      )
    }
    if ((lower.contains("odds ratio") || lower.contains("risk ratio")) && twoByTwoTables.isEmpty() &&
      activeClaims.any { it.effectMeasure.uppercase(Locale.US) in setOf("OR", "AOR", "RR", "ARR") }
    ) {
      disclosures.add(
        "Reported OR/RR estimates lack extracted 2x2 cell counts (a,b,c,d or events/N per arm); exact 2x2 and Fisher reconstruction is UNSUPPORTED without contingency tables."
      )
    }
    if ((lower.contains("meta-analysis") || lower.contains("pooled estimate")) && metaStudies.size < 2) {
      disclosures.add(
        "Study-level effect sizes and standard errors were not available in structured text (likely embedded in forest plot graphics or supplementary tables); independent τ²/I²/prediction-interval pooling is UNSUPPORTED from text alone."
      )
    }
    if (lower.contains("regression") && activeClaims.none { it.standardError != null }) {
      disclosures.add(
        "Regression analysis referenced without machine-readable β and SE pairs in extracted text; coefficient Wald/CI reconstruction is UNSUPPORTED without regression tables."
      )
    }
    val ambiguousCount = activeClaims.count { it.parseStatus == ClaimParseStatus.AMBIGUOUS }
    if (ambiguousCount > 0) {
      disclosures.add(
        "$ambiguousCount parenthesized numerical interval(s) lacked explicit CI/IQR/SD labels and were classified AMBIGUOUS rather than treated as confirmed errors."
      )
    }
    if (clean.contains(Regex("""\b(?:supplementary\s+table|eTable|appendix\s+table|forest\s+plot)\b""", RegexOption.IGNORE_CASE)) &&
      activeClaims.isEmpty()
    ) {
      disclosures.add(
        "Key statistical results appear to reside in figures or external supplementary tables not present in the extracted text."
      )
    }
  }

  private data class ContextInfo(
    val page: Int,
    val section: String,
    val tableFig: String,
    val isReferenceOrCitation: Boolean,
    val hierarchy: AnalysisHierarchy,
    val adjustment: AdjustmentStatus,
    val tail: TailSpecification,
    val alpha: Double,
    val family: String,
    val window: String,
    val localLine: String = window
  )

  private fun findReferenceSectionOffset(text: String): Int {
    val match = referenceSectionStartRegex.findAll(text).lastOrNull() ?: return -1
    // Only treat as References section if it starts after the first 35% of the document
    return if (match.range.first >= (text.length * 0.35).toInt()) match.range.first else -1
  }

  private fun isPriorStudyCitationWindow(text: String, offset: Int): Boolean {
    // Stay strictly within the same paragraph/sentence and never bleed across newlines or [PAGE] boundaries
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val start = max(lineStart, (offset - 110).coerceAtLeast(0))
    val end = kotlin.math.min(lineEnd, (offset + 50).coerceAtMost(text.length))
    if (start >= end) return false
    val snippet = text.substring(start, end)
    return inlineCitationContextRegex.containsMatchIn(snippet)
  }

  private fun buildSectionSpans(text: String): List<Pair<Int, String>> {
    val spans = mutableListOf<Pair<Int, String>>()
    sectionHeaderRegex.findAll(text).forEach { m ->
      val raw = m.groupValues[1].trim().lowercase(Locale.US)
      val normalized = when {
        raw.startsWith("abstract") -> "Abstract"
        raw.startsWith("intro") || raw.startsWith("background") -> "Introduction"
        raw.startsWith("method") || raw.startsWith("material") || raw.startsWith("statistical") -> "Methods"
        raw.startsWith("result") || raw.startsWith("finding") -> "Results"
        raw.startsWith("discuss") || raw.startsWith("conclu") || raw.startsWith("limit") -> "Discussion"
        raw.startsWith("ref") || raw.startsWith("biblio") || raw.startsWith("works") -> "References"
        raw.startsWith("supp") || raw.startsWith("appendix") -> "Supplement"
        raw.startsWith("protocol") -> "Protocol"
        else -> "Body"
      }
      spans.add(m.range.first to normalized)
    }
    return spans
  }

  private fun resolveSection(spans: List<Pair<Int, String>>, offset: Int): String {
    var current = "Body"
    for ((pos, name) in spans) {
      if (pos <= offset) current = name else break
    }
    return current
  }

  private fun inferHierarchy(window: String): AnalysisHierarchy {
    val w = window.lowercase(Locale.US)
    return when {
      w.contains("primary outcome") || w.contains("primary endpoint") || w.contains("primary analysis") -> AnalysisHierarchy.PRIMARY
      w.contains("secondary outcome") || w.contains("secondary endpoint") || w.contains("secondary analysis") -> AnalysisHierarchy.SECONDARY
      w.contains("sensitivity") || w.contains("robustness") -> AnalysisHierarchy.SENSITIVITY
      w.contains("subgroup") || w.contains("stratum") || w.contains("stratified") -> AnalysisHierarchy.SUBGROUP
      w.contains("exploratory") || w.contains("post-hoc") || w.contains("post hoc") -> AnalysisHierarchy.EXPLORATORY
      else -> AnalysisHierarchy.UNSPECIFIED
    }
  }

  private fun inferAdjustment(window: String): AdjustmentStatus {
    val w = window.lowercase(Locale.US)
    return when {
      w.contains("unadjusted") || w.contains("crude") || w.contains("bivariate") -> AdjustmentStatus.UNADJUSTED
      w.contains("propensity") || w.contains("matched") -> AdjustmentStatus.MATCHED
      w.contains("stratified") -> AdjustmentStatus.STRATIFIED
      w.contains("adjusted") || w.contains("multivariable") || w.contains("multivariate") || w.contains("controlling for") -> AdjustmentStatus.ADJUSTED
      else -> AdjustmentStatus.UNSPECIFIED
    }
  }

  private fun isAdjustedPWindow(window: String): Boolean {
    val w = window.lowercase(Locale.US)
    return w.contains("adjusted p") || w.contains("p_adj") || w.contains("padj") ||
      w.contains("bonferroni-adjusted") || w.contains("holm-adjusted") ||
      w.contains("fdr-adjusted") || w.contains("benjamini-hochberg") ||
      w.contains("corrected p") || w.contains("q-value")
  }

  private fun inferLocalTail(window: String, fallback: TailSpecification): TailSpecification {
    val w = window.lowercase(Locale.US)
    return when {
      w.contains("one-sided") || w.contains("one-tailed") || w.contains("1-tailed") || w.contains("one sided") -> TailSpecification.ONE_SIDED
      w.contains("two-sided") || w.contains("two-tailed") || w.contains("2-tailed") || w.contains("two sided") -> TailSpecification.TWO_SIDED
      else -> fallback
    }
  }

  private fun inferTestingFamily(section: String, tableFig: String, hierarchy: AnalysisHierarchy, window: String): String {
    val w = window.lowercase(Locale.US)
    return when {
      w.contains("baseline") || tableFig.equals("Table 1", true) -> "Baseline"
      hierarchy == AnalysisHierarchy.PRIMARY -> "Primary"
      hierarchy == AnalysisHierarchy.SECONDARY -> "Secondary"
      hierarchy == AnalysisHierarchy.SUBGROUP -> "Subgroup"
      hierarchy == AnalysisHierarchy.EXPLORATORY -> "Exploratory"
      hierarchy == AnalysisHierarchy.SENSITIVITY -> "Sensitivity"
      tableFig.isNotBlank() -> tableFig
      else -> section
    }
  }

  private fun inferAnalysisTypeFromMeasure(label: String): String = when (label.uppercase(Locale.US)) {
    "OR", "AOR" -> "Odds Ratio / Logistic"
    "RR", "ARR" -> "Risk Ratio / Cohort-RCT"
    "HR", "AHR" -> "Survival / Cox PH"
    "IRR" -> "Incidence Rate / Poisson-NB"
    "SMD", "MD", "WMD" -> "Meta-Analysis / Mean Difference"
    "AUC", "AUROC", "PR-AUC", "AUPRC", "C-STATISTIC" -> "Discrimination / ML-Diagnostic"
    "BETA", "Β" -> "Regression"
    else -> "Effect Estimate"
  }

  private fun inferMeasureFromRow(rowText: String): String {
    val upper = rowText.uppercase(Locale.US)
    return when {
      Regex("""\bAOR\b""").containsMatchIn(upper) -> "aOR"
      Regex("""\bOR\b""").containsMatchIn(upper) -> "OR"
      Regex("""\bAHR\b""").containsMatchIn(upper) -> "aHR"
      Regex("""\bHR\b""").containsMatchIn(upper) -> "HR"
      Regex("""\bARR\b""").containsMatchIn(upper) -> "aRR"
      Regex("""\bRR\b""").containsMatchIn(upper) -> "RR"
      Regex("""\bIRR\b""").containsMatchIn(upper) -> "IRR"
      Regex("""\bSMD\b""").containsMatchIn(upper) -> "SMD"
      Regex("""\bAUC|AUROC\b""").containsMatchIn(upper) -> "AUROC"
      else -> "Estimate"
    }
  }

  internal fun parseSignedDouble(raw: String): Double? {
    val normalized = raw.trim().replace('−', '-').replace('–', '-').replace(",", "")
    return normalized.toDoubleOrNull()
  }

  /**
   * Parses decimal and scientific notation strings including `1.2e-4`, `1.2 × 10^-4`, `.004`.
   */
  internal fun parseScientificDouble(raw: String): Double? {
    val cleaned = raw.trim()
      .replace('−', '-')
      .replace('–', '-')
      .replace(" ", "")
    // Check a × 10^b or a x 10^-b
    val sciTimes = Regex("""^([-+]?(?:\d*\.?\d+))[x×]10\^?([-+]?\d+)$""", RegexOption.IGNORE_CASE).find(cleaned)
    if (sciTimes != null) {
      val mantissa = sciTimes.groupValues[1].toDoubleOrNull() ?: return null
      val expVal = sciTimes.groupValues[2].toIntOrNull() ?: return null
      return mantissa * 10.0.pow(expVal.toDouble())
    }
    return cleaned.toDoubleOrNull()
  }
}
