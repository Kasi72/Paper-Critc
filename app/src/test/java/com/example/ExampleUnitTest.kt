package com.example

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.ai.StudyDesignArchetype
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.PaperReviewRubric
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.util.CalloutTone
import com.example.util.DossierFormattingEngine
import com.example.util.ExtractedPage
import com.example.util.ManuscriptExtractor
import com.example.util.ReportBlock
import com.example.util.SeverityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM unit tests verifying Paper Critic's formatting engine, domain-aware forensic scanner,
 * exact deterministic quantitative/statistical audit math, cross-pass adjudication, and rubric scoring.
 */
class ExampleUnitTest {

  @Test
  fun normalizeTitle_formatsFilenameSlugsAndPreservesAcronyms() {
    val slug = "need estimates of psychiatric beds a systematic review and meta analysis"
    val normalized = DossierFormattingEngine.normalizeTitle(slug)
    assertEquals(
      "Need Estimates of Psychiatric Beds a Systematic Review and Meta-Analysis",
      normalized
    )

    val withAcronyms = DossierFormattingEngine.normalizeTitle("prisma guided iqr synthesis in lmic hospitals")
    assertTrue(withAcronyms.contains("PRISMA"))
    assertTrue(withAcronyms.contains("IQR"))
    assertTrue(withAcronyms.contains("LMIC"))
  }

  @Test
  fun cleanAcademicProse_stripsRawLatexAndInternalScorecardTags() {
    val raw = """
      [6-AXIS CALIBRATED SCORECARD: Methodology=7/10 | Clarity=8/10 | Significance=8/10 | Novelty=7/10 | Math=7/10 | Reproducibility=6/10]
      [CONFIDENCE:4/5]
      The median estimate with (${'$'}IQR${'$'}) across ${'$'}N = 65${'$'} studies was 60 per 100,000 (${'$'}p \leq 0.05${'$'}). [C1] First claim. [C2] Second claim.
    """.trimIndent()

    val cleaned = DossierFormattingEngine.cleanAcademicProse(raw, stripInternalTags = true)
    assertFalse(cleaned.contains("[6-AXIS CALIBRATED SCORECARD"))
    assertFalse(cleaned.contains("[CONFIDENCE:"))
    assertFalse(cleaned.contains("${'$'}IQR${'$'}"))
    assertTrue(cleaned.contains("(IQR)"))
    assertTrue(cleaned.contains("p ≤ 0.05"))
    assertTrue(cleaned.contains("\n[C2]"))
  }

  @Test
  fun parseStructuredBlocks_extractsSeverityCardsCalloutsBulletsAndAuditTables() {
    val sample = """
      [STAGE 02e — ADJUDICATED FINDINGS MATRIX]
      [CRITICAL] Selection bias in non-English exclusion.
      [MAJOR] Missing heterogeneity breakdown across HIC and LMIC cohorts.
      The Bottom Line: Solid policy synthesis requiring PRISMA sensitivity tables.
      [C1] Aggregates 65 global bed-need estimates.
      | Audit Dimension | Manuscript Evidence | Status | Quantitative Impact |
      |---|---|---|---|
      | Pooling Estimator | Unweighted Median (IQR) | WARN | Equal weight to small studies |
      | Statcheck t(28) | t(28) = 1.75, p = 0.03 | FAIL | True two-tailed p = 0.091 |
    """.trimIndent()

    val blocks = DossierFormattingEngine.parseStructuredBlocks(sample)
    assertTrue(blocks.any { it is ReportBlock.SubHeader && it.tag == "STAGE 02e" })
    assertTrue(blocks.any { it is ReportBlock.SeverityCard && it.level == SeverityLevel.CRITICAL })
    assertTrue(blocks.any { it is ReportBlock.SeverityCard && it.level == SeverityLevel.MAJOR })
    assertTrue(blocks.any { it is ReportBlock.Callout && it.tone == CalloutTone.BOTTOM_LINE })
    assertTrue(blocks.any { it is ReportBlock.Bullet && it.marker == "[C1]" })

    val table = blocks.filterIsInstance<ReportBlock.AuditTable>().firstOrNull()
    assertTrue("Should parse AuditTable block", table != null)
    assertEquals(4, table!!.headers.size)
    assertEquals(2, table.rows.size)
    assertEquals("FAIL", table.rows[1][2])
  }

  @Test
  fun forensicEngine_detectsSystematicReviewWithoutMlRandomSeedFlag() {
    val manuscript = """
      ABSTRACT: We conducted a systematic review and meta-analysis following PRISMA guidelines across PubMed,
      Embase, and PsycINFO to synthesize expert consensus estimates of psychiatric bed needs per 100,000 population
      in high-income and low- and middle-income countries. Median and interquartile ranges (IQR) were calculated
      across N = 65 studies for short-stay, long-stay, forensic, and general psychiatric inpatient units.
    """.trimIndent()

    val report = ManuscriptForensicEngine.analyzeManuscript(
      text = "Need Estimates of Psychiatric Beds: A Systematic Review and Meta-Analysis\n$manuscript",
      isEmpiricalTrack = true
    )

    assertEquals(StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS, report.studyDesign)
    assertFalse(
      "Systematic reviews must not receive ML random-seed flags",
      report.proceduralLeakageAlerts.any { it.contains("random seed", ignoreCase = true) }
    )
    assertTrue(
      "Should flag unweighted median/IQR synthesis when no random-effects pooling is present",
      report.quantForensics.heterogeneitySignals.any { it.contains("UNWEIGHTED SYNTHESIS", ignoreCase = true) }
    )
  }

  @Test
  fun forensicEngine_psychiatricBedBenchmark_zeroFalseAbsenceAlarmsAndCatchesPage8ImpossibleCi() {
    val benchmarkText = """
      [PAGE 1]
      Need estimates of psychiatric beds: a systematic review and meta-analysis
      Abstract: We searched PubMed, Embase, and PsycINFO up to 13 September 2022 in accordance with PRISMA guidelines.
      We included 65 publications providing 98 estimates of needed psychiatric beds per 100,000 population across
      high-income countries (HIC) and low- and middle-income countries (LMIC).

      [PAGE 3]
      Methods: Three authors independently extracted data from all eligible studies using a structured extraction form
      and evaluated methodological rigor with a custom quality-scoring checklist. We conducted random-effects
      meta-analyses to pool bed-need estimates per 100,000 population and explored between-study variance using
      univariable meta-regression models.

      [PAGE 7]
      Results: Between-study heterogeneity was extreme across all pooled estimates (I² > 99%). In meta-regression,
      year of publication was positively associated with estimated need (β = 0.53, p = 0.004). Median estimates
      and interquartile ranges (IQR) were also summarized descriptively alongside pooled random-effects estimates.

      [PAGE 8]
      Subgroup analysis for chronic inpatient facilities yielded a pooled long-stay bed estimate of 9, 95% CI 1.26 to 2.35
      per 100,000 population across contributing jurisdictions.

      [PAGE 9]
      Figure 2. Forest plot of long-stay psychiatric bed estimates showing pooled estimate of 9 (95% CI 3–15)
      per 100,000 population with I² > 99%.
    """.trimIndent()

    val report = ManuscriptForensicEngine.analyzeManuscript(
      text = benchmarkText,
      isEmpiricalTrack = true
    )

    assertEquals(StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS, report.studyDesign)
    assertTrue(report.studyDesign.reportingGuideline.contains("PRISMA 2020"))
    assertTrue(report.studyDesign.riskOfBiasInstrument.contains("AMSTAR", ignoreCase = true))
    assertTrue(report.studyDesign.certaintyFramework.contains("GRADE", ignoreCase = true))

    // Precision gates: ZERO false absence alarms on this benchmark
    assertFalse(
      "Must not emit false UNWEIGHTED SYNTHESIS when random-effects meta-analyses are explicitly reported",
      report.quantForensics.heterogeneitySignals.any { it.contains("UNWEIGHTED SYNTHESIS", ignoreCase = true) }
    )
    assertFalse(
      "Must not emit false HETEROGENEITY OMISSION when I² > 99% is reported",
      report.quantForensics.heterogeneitySignals.any { it.contains("HETEROGENEITY OMISSION", ignoreCase = true) }
    )
    assertFalse(
      "Must not emit false PUBLICATION BIAS warning for descriptive/normative bed-need estimates",
      report.quantForensics.heterogeneitySignals.any { it.contains("PUBLICATION BIAS WARN", ignoreCase = true) }
    )

    // Must catch the Page 8 impossible CI and cross-reference it with Figure 2 (95% CI 3–15) on Page 9
    val page8CiFail = report.quantForensics.confidenceIntervalChecks.firstOrNull {
      it.status == QuantFindingStatus.FAIL
    }
    assertNotNull("Must flag impossible narrative CI '9, 95% CI 1.26 to 2.35'", page8CiFail)
    assertEquals(8, page8CiFail!!.pageNumber)
    assertEquals(9.0, page8CiFail.pointEstimate, 0.001)
    assertEquals(1.26, page8CiFail.lower, 0.001)
    assertEquals(2.35, page8CiFail.upper, 0.001)
    assertTrue(
      "Cross-reference note must link to Figure 2 / 3–15 on Page 9",
      page8CiFail.crossReferenceNote.contains("3") &&
        page8CiFail.crossReferenceNote.contains("15") &&
        page8CiFail.crossReferenceNote.contains("Page 9")
    )

    // Methodological nuance: quality scoring recognized (not falsely flagged as absent), independent extraction recognized
    assertFalse(
      "Must not falsely claim multi-author independent extraction was absent",
      report.proceduralLeakageAlerts.any { it.contains("independent extraction", ignoreCase = true) && it.contains("no ", ignoreCase = true) }
    )
    assertTrue(
      "Clinical applicability lens must generate structured real-world notes",
      report.clinicalApplicabilityNotes.isNotEmpty()
    )
  }

  @Test
  fun rubricWeightedScore_matchesExactlyAcrossComputeAndModel() {
    // Weights: 25% Methodology + 20% Significance + 15% Clarity + 15% Originality + 15% Math + 10% Repro
    // (6*25 + 8*20 + 7*15 + 7*15 + 5*15 + 4*10) / 10.0 = 635 / 10.0 = 63.5 -> 64
    val exact = PaperReviewRubric.computeWeightedScore(
      methodology = 6,
      significance = 8,
      clarity = 7,
      originality = 7,
      mathematicalRigor = 5,
      reproducibility = 4
    )
    assertEquals(64, exact)

    val review = PaperReview(
      id = "rubric-test",
      title = "Rubric Reconciliation Check",
      methodologyScore = 6,
      significanceScore = 8,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 5,
      reproducibilityScore = 4,
      credibilityScore = 99 // stale/mismatched legacy value must be overridden by authoritative rubric
    )
    assertEquals(64, review.toRubric().overallCredibilityScore)
  }

  @Test
  fun adjudicator_downgradesUngroundedQuotesContradictsFalseAbsencesAndInjectsImpossibleCi() {
    val benchmarkText = """
      [PAGE 3]
      Methods: Three authors independently extracted data and performed random-effects meta-analyses.
      [PAGE 7]
      Results: Heterogeneity was very high (I² > 99%).
      [PAGE 8]
      Subgroup analysis yielded a pooled long-stay bed estimate of 9, 95% CI 1.26 to 2.35 per 100,000.
      [PAGE 9]
      Figure 2 shows pooled estimate of 9 (95% CI 3–15).
    """.trimIndent()

    val forensic = ManuscriptForensicEngine.analyzeManuscript(benchmarkText, isEmpiricalTrack = true)

    val rawFindings = listOf(
      ReviewFinding(
        id = "F-1",
        title = "Hallucinated Verbatim Quote",
        allegation = "Authors omitted ethics approval in Table 4.",
        severity = "MAJOR",
        status = FindingStatus.VERIFIED,
        supportingEvidence = listOf(
          EvidenceAnchor(
            pageNumber = 4,
            section = "Table 4",
            exactQuote = "Table 4 explicitly omits all institutional review board protocol identifiers across 99 cohorts"
          )
        )
      ),
      ReviewFinding(
        id = "F-2",
        title = "False Absence of Meta-Analysis",
        allegation = "The authors did not perform any random-effects meta-analysis and omitted I² heterogeneity statistics.",
        severity = "CRITICAL",
        status = FindingStatus.VERIFIED,
        supportingEvidence = listOf(
          EvidenceAnchor(pageNumber = 3, section = "Methods", exactQuote = "Three authors independently extracted data")
        )
      )
    )

    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = rawFindings,
      manuscriptText = benchmarkText,
      forensicReport = forensic,
      methodologyScore = 6,
      significanceScore = 8,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 5,
      reproducibilityScore = 4,
      rawVerdict = "HIGH_CREDIBILITY",
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL
    )

    assertEquals(64, adjudicated.weightedCredibilityScore)
    // Verified CRITICAL CI error must prevent HIGH_CREDIBILITY and downgrade to REQUIRES_CORRECTION
    assertEquals("REQUIRES_CORRECTION", adjudicated.resolvedVerdict)

    val f1 = adjudicated.adjudicatedFindings.first { it.id == "F-1" }
    assertEquals(
      "Ungrounded quote must be downgraded from VERIFIED to UNVERIFIED",
      FindingStatus.UNVERIFIED,
      f1.status
    )

    val f2 = adjudicated.adjudicatedFindings.first { it.id == "F-2" }
    assertEquals(
      "False absence claim must be marked CONTRADICTED",
      FindingStatus.CONTRADICTED,
      f2.status
    )
    assertTrue("Contradicted finding must include counter-evidence snippet", f2.counterEvidence.isNotEmpty())

    val injectedCi = adjudicated.adjudicatedFindings.firstOrNull { it.id == "Q-01" }
    assertNotNull("Deterministic impossible CI must be injected as Q-01", injectedCi)
    assertEquals(FindingStatus.VERIFIED, injectedCi!!.status)
    assertEquals("CRITICAL", injectedCi.severity)
    assertEquals(8, injectedCi.supportingEvidence.first().pageNumber)
  }

  @Test
  fun prepareManuscriptForPrompt_preservesFullBodyAndTrimsReferencesTailFirst() {
    val body = "[PAGE 1]\nIntroduction\n" + "Core methods and results sentence. ".repeat(110) +
      "\n[PAGE 8]\nCRITICAL_MID_BODY_RESULT: estimate of 9, 95% CI 1.26 to 2.35\n"
    val refs = "\nReferences\n" + (1..60).joinToString("\n") { "[$it] Author $it et al. Journal of Psychiatry 2022; 10: 1-12." }
    val fullDoc = body + refs

    val prepared = Reviewer2PipelineService.prepareManuscriptForPrompt(fullDoc, maxChars = 4800)
    assertTrue("Should flag that bibliography was trimmed", prepared.wasTrimmed)
    assertTrue(
      "Must preserve the entire middle body including Page 8 critical result without middle truncation",
      prepared.text.contains("CRITICAL_MID_BODY_RESULT: estimate of 9, 95% CI 1.26 to 2.35")
    )
    assertTrue("Should note bibliography trimming", prepared.trimmedNote.contains("bibliography", ignoreCase = true))
  }

  @Test
  fun visionPageSelector_prioritizesTitleFiguresTablesAndLowDensityPages() {
    val pages = (1..14).map { pageNum ->
      val text = when (pageNum) {
        1 -> "Title and Abstract"
        5 -> "Table 1 characteristics of included studies"
        9 -> "Figure 2 forest plot of long-stay estimates"
        11 -> "Chart" // low text density
        else -> "Narrative methods and discussion text ".repeat(25)
      }
      ExtractedPage(
        pageNumber = pageNum,
        text = text,
        charCount = text.length,
        wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() },
        hasLowTextDensity = text.length < 40,
        mentionsFiguresOrTables = text.contains("Figure", ignoreCase = true) || text.contains("Table", ignoreCase = true)
      )
    }
    val selected = ManuscriptExtractor.selectHighValueVisionPages(
      totalPages = 14,
      extractedPages = pages,
      maxPages = 6
    )
    assertTrue("Always includes Page 1 (index 0)", 0 in selected)
    assertTrue("Prioritizes Figure 2 on Page 9 (index 8)", 8 in selected)
    assertTrue("Prioritizes Table 1 on Page 5 (index 4)", 4 in selected)
    assertTrue("Prioritizes low-text-density Page 11 (index 10)", 10 in selected)
    assertEquals(6, selected.size)
  }

  @Test
  fun quantitativeDistributions_matchTextbookReferencePValues() {
    // Standard Normal: z = 1.96 -> p ≈ 0.0500
    assertEquals(0.0500, QuantitativeForensicAnalyzer.normalTwoTailedP(1.95996), 0.001)
    // Student's t: t(28) = 2.0484 -> p ≈ 0.0500; t(28) = 1.75 -> p ≈ 0.0911
    assertEquals(0.0500, QuantitativeForensicAnalyzer.studentTTwoTailedP(2.0484, 28.0), 0.001)
    assertEquals(0.0911, QuantitativeForensicAnalyzer.studentTTwoTailedP(1.75, 28.0), 0.002)
    // Chi-Square: chi²(1) = 3.8415 -> p ≈ 0.0500
    assertEquals(0.0500, QuantitativeForensicAnalyzer.chiSquareUpperTailP(3.8415, 1.0), 0.001)
    // Snedecor's F: F(2, 27) = 3.3541 -> p ≈ 0.0500
    assertEquals(0.0500, QuantitativeForensicAnalyzer.fDistUpperTailP(3.3541, 2.0, 27.0), 0.002)
  }

  @Test
  fun quantitativeForensics_catchesGrossStatcheckErrorImpossibleCiAndFixedEffectMismatch() {
    val text = """
      Across k = 18 included clinical trials of an antidepressant intervention, a fixed-effect meta-analysis yielded I² = 84%.
      Primary group comparison reported t(28) = 1.75, p = 0.03 and a consistent check z = 2.58, p = 0.01.
      Adjusted odds ratio was OR = 2.40 (95% CI 0.90–1.80) alongside median of 45 (IQR 40–95).
    """.trimIndent()

    val report = QuantitativeForensicAnalyzer.analyze(
      text = text,
      studyDesign = StudyDesignArchetype.SYSTEMATIC_REVIEW_META_ANALYSIS,
      sampleSizes = listOf(18, 30)
    )

    val tCheck = report.statcheckResults.firstOrNull { it.testType.startsWith("t(") }
    assertTrue("Should detect gross decision discrepancy on t(28)=1.75, p=0.03", tCheck?.status == QuantFindingStatus.FAIL)

    val zCheck = report.statcheckResults.firstOrNull { it.testType == "z" }
    assertTrue("Should pass consistent z=2.58, p=0.01", zCheck?.status == QuantFindingStatus.PASS)

    val ciFail = report.confidenceIntervalChecks.firstOrNull { it.status == QuantFindingStatus.FAIL }
    assertTrue("Should flag point estimate 2.40 outside [0.90, 1.80]", ciFail != null)

    val skewedIqr = report.medianIqrChecks.firstOrNull { it.status == QuantFindingStatus.WARN }
    assertTrue("Should flag heavily right-skewed IQR (45 in [40, 95])", skewedIqr != null)

    assertTrue(
      "Should flag Fixed-Effect mismatch under I² = 84%",
      report.heterogeneitySignals.any { it.contains("MODEL MISMATCH FAIL", ignoreCase = true) }
    )
    assertTrue(
      "Should flag missing Egger/funnel bias check for comparative effect-size trials with k = 18 >= 10",
      report.heterogeneitySignals.any { it.contains("PUBLICATION BIAS WARN", ignoreCase = true) }
    )
  }

  @Test
  fun formatCleanCitation_handlesBlankFieldsWithoutStrayPunctuation() {
    assertEquals(
      "Uploaded Academic Manuscript",
      DossierFormattingEngine.formatCleanCitation("", "", "")
    )
    assertEquals(
      "Mundt et al. (2022) • World Psychiatry",
      DossierFormattingEngine.formatCleanCitation("Mundt et al.", "2022", "World Psychiatry")
    )
    assertEquals(
      "World Psychiatry",
      DossierFormattingEngine.formatCleanCitation("   ", "", "World Psychiatry")
    )
    assertEquals(
      "Mundt et al.",
      DossierFormattingEngine.formatCleanCitation("Mundt et al.", " ", "")
    )
  }

  @Test
  fun searchDossier_findsMatchesAcrossSectionsAndRanksByHitCount() {
    val review = PaperReview(
      id = "r1",
      title = "Psychiatric Beds Review",
      abstractSummary = "Synthesizes median and IQR bed estimates.",
      mathAuditReport = "IQR skewness check flagged IQR asymmetry and IQR weighting bias.",
      redTeamCritique = "Unweighted IQR pooling masks between-country variance."
    )

    val hits = DossierFormattingEngine.searchDossier(review, "IQR")
    assertEquals(3, hits.size)
    // Quantitative Audit has 3 hits and should rank first at Tab 2, Sub-segment 0
    assertEquals("Quantitative & Statistical Audit", hits.first().sectionTitle)
    assertEquals(3, hits.first().matchCount)
    assertEquals(2, hits.first().sectionTabIndex)
    assertEquals(0, hits.first().subSegmentIndex)
  }

  // --- Audit P0 / P1 / P2 Regression & Integrity Test Suite ---

  @Test
  fun reviewRepositoryPayload_preservesAll18ProvenanceFieldsAndModeSpecificVerdicts() {
    val findingsJson = """[{"id":"Q-01","status":"VERIFIED"}]"""
    val review = PaperReview(
      id = "local-doc-99",
      userId = "uid-alice",
      title = "Need Estimates of Psychiatric Beds",
      authors = "Mundt et al.",
      publicationYear = "2022",
      venueOrCitation = "World Psychiatry",
      isEmpirical = true,
      credibilityScore = 99L, // Stale value must be reconciled to authoritative 64
      verdict = "REQUIRES_CORRECTION",
      methodologyScore = 6,
      significanceScore = 8,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 5,
      reproducibilityScore = 4,
      methodologyJustification = "Random-effects meta-analysis performed across 65 studies.",
      significanceJustification = "High policy significance globally.",
      clarityJustification = "Clear PRISMA flow diagram.",
      originalityJustification = "First global normative bed benchmark.",
      mathRigorJustification = "Page 8 CI conflicts with Figure 2 on Page 9.",
      reproducibilityJustification = "Protocol not pre-registered.",
      refereeConfidence = 4,
      extractionConfidence = 5,
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL.name,
      coverageDisclosure = "12/12 pages extracted • PDFBox",
      validationStatus = "VERIFIED",
      structuredFindingsJson = findingsJson,
      abstractSummary = "Summary text."
    )

    val payload = com.example.data.repository.ReviewRepository.buildFirestorePayload(
      review = review,
      uid = "uid-alice",
      createdAtValue = "TS_CREATE",
      updatedAtValue = "TS_UPDATE"
    )

    // @DocumentId "id" must never be included in raw Map payload
    assertFalse("Must not include id in raw Firestore payload", payload.containsKey("id"))
    assertEquals(64L, payload["credibilityScore"])
    assertEquals("REQUIRES_CORRECTION", payload["verdict"])
    assertEquals("POST_PUBLICATION_APPRAISAL", payload["reviewMode"])
    assertEquals("VERIFIED", payload["validationStatus"])
    assertEquals(4, payload["refereeConfidence"])
    assertEquals(5, payload["extractionConfidence"])
    assertEquals("12/12 pages extracted • PDFBox", payload["coverageDisclosure"])
    assertEquals(findingsJson, payload["structuredFindingsJson"])
    assertEquals(6, payload["methodologyScore"])
    assertEquals(5, payload["mathRigorScore"])
    assertEquals("Page 8 CI conflicts with Figure 2 on Page 9.", payload["mathRigorJustification"])

    // Clinical & Journal verdicts preserved without coercing to MINOR_REVISION
    val clinicalPayload = com.example.data.repository.ReviewRepository.buildFirestorePayload(
      review = review.copy(
        reviewMode = ReviewMode.CLINICAL_EVIDENCE_APPRAISAL.name,
        verdict = "HIGH_CERTAINTY"
      ),
      uid = "uid-alice",
      createdAtValue = "TS_CREATE",
      updatedAtValue = "TS_UPDATE"
    )
    assertEquals("HIGH_CERTAINTY", clinicalPayload["verdict"])
  }

  @Test
  fun autoSyncPrivacyGate_blocksAutomaticUploadWhenAutoSaveDisabledOrConfidentialModeEnabled() {
    // Confidential local-only blocks BOTH automatic and manual sync
    assertFalse(
      com.example.ui.viewmodel.PaperCriticViewModel.shouldAllowPortfolioSync(
        confidentialLocalOnly = true,
        autoSaveToCloud = true,
        isAutomatic = true
      )
    )
    assertFalse(
      com.example.ui.viewmodel.PaperCriticViewModel.shouldAllowPortfolioSync(
        confidentialLocalOnly = true,
        autoSaveToCloud = true,
        isAutomatic = false
      )
    )
    // Auto-save disabled MUST block automatic sign-in upload
    assertFalse(
      com.example.ui.viewmodel.PaperCriticViewModel.shouldAllowPortfolioSync(
        confidentialLocalOnly = false,
        autoSaveToCloud = false,
        isAutomatic = true
      )
    )
    // Explicit manual "Sync Now" succeeds when confidential mode is off even if auto-save is off
    assertTrue(
      com.example.ui.viewmodel.PaperCriticViewModel.shouldAllowPortfolioSync(
        confidentialLocalOnly = false,
        autoSaveToCloud = false,
        isAutomatic = false
      )
    )
    // Automatic sync succeeds only when confidential is off AND auto-save is on
    assertTrue(
      com.example.ui.viewmodel.PaperCriticViewModel.shouldAllowPortfolioSync(
        confidentialLocalOnly = false,
        autoSaveToCloud = true,
        isAutomatic = true
      )
    )
  }

  @Test
  fun externalApiEndpointValidator_enforcesHttpsBlocksPrivateHostsAndKeepsGeminiKeyOutOfUrl() {
    val blockedUrls = listOf(
      "http://api.openai.com/v1/chat/completions",
      "https://localhost:8080/v1/chat",
      "https://127.0.0.1/v1/chat",
      "https://169.254.169.254/latest/meta-data/",
      "https://10.0.0.4/v1/chat",
      "https://192.168.1.10/v1/chat",
      "https://[::1]/v1/chat",
      "https://user:pass@api.openai.com/v1/chat"
    )
    blockedUrls.forEach { bad ->
      val failed = runCatching {
        com.example.data.ai.ExternalApiGatewayClient.validateEndpointUrl(bad, resolveDns = false)
      }.isFailure
      assertTrue("Must reject unsafe endpoint: $bad", failed)
    }

    val valid = com.example.data.ai.ExternalApiGatewayClient.validateEndpointUrl(
      "https://openrouter.ai/api/v1/chat/completions",
      resolveDns = false
    )
    assertEquals("https", valid.protocol)
    assertEquals("openrouter.ai", valid.host)

    // Gemini direct REST URL must NEVER carry ?key= query parameter
    val geminiUrl = com.example.data.ai.ExternalApiGatewayClient.buildGeminiDirectRestUrl(
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      modelId = "gemini-2.5-flash"
    )
    assertFalse("Gemini URL must not embed API key in query string", geminiUrl.toString().contains("key="))
    assertTrue(geminiUrl.toString().endsWith("/models/gemini-2.5-flash:generateContent"))
  }

  @Test
  fun prepareManuscriptForPrompt_withoutReferencesHeadingUsesPageAwareSelectionAndDisclosesOmittedPages() {
    val pages = (1..8).joinToString("\n\n") { pageNum ->
      val body = when (pageNum) {
        1 -> "Title and Abstract: Systematic appraisal of inpatient bed thresholds. " + "Context text. ".repeat(20)
        5 -> "Results Table 2 Figure 2 pooled estimate 95% CI 3-15 p = 0.004 I² = 99%. " + "Quantitative detail. ".repeat(20)
        8 -> "Discussion and Conclusion: Policy implications for LMIC and HIC facilities. " + "Closing synthesis. ".repeat(20)
        else -> "Extended supplementary narrative appendix text for page $pageNum. ".repeat(30)
      }
      "[PAGE $pageNum]\n$body"
    }

    val prepared = Reviewer2PipelineService.prepareManuscriptForPrompt(pages, maxChars = 2200)
    assertTrue("Must flag partial coverage", prepared.wasTrimmed)
    assertTrue("Must record exact omitted page numbers", prepared.omittedPages.isNotEmpty())
    assertFalse("Must never falsely claim no middle sections omitted", prepared.trimmedNote.contains("no middle sections omitted", ignoreCase = true))
    assertTrue("Must retain Page 1 opening", prepared.text.contains("[PAGE 1]"))
    assertTrue("Must retain high-signal Results Page 5", prepared.text.contains("[PAGE 5]"))
    assertTrue("Must retain closing Discussion Page 8", prepared.text.contains("[PAGE 8]"))
    assertTrue("Disclosure note must state partial coverage", prepared.trimmedNote.contains("Partial", ignoreCase = true))
  }

  @Test
  fun quoteVerifier_requiresExactContiguousMatchAndMatchingPageForVerified() {
    val manuscript = """
      [PAGE 2]
      Methods: Three authors independently extracted study characteristics from 65 eligible publications.

      [PAGE 8]
      Results: Long-stay psychiatric beds had a pooled estimate of 9, 95% CI 1.26 to 2.35 per 100,000 population.
    """.trimIndent()

    // 1. Exact contiguous quote + matching page -> EXACT without page mismatch
    val exactOk = Reviewer2PipelineService.verifyQuoteInManuscript(
      quote = "Three authors independently extracted study characteristics from 65 eligible publications",
      manuscriptText = manuscript,
      claimedPage = 2
    )
    assertEquals(Reviewer2PipelineService.QuoteMatchKind.EXACT, exactOk.matchKind)
    assertEquals(2, exactOk.resolvedPage)
    assertEquals(null, exactOk.pageMismatchWarning)
    assertNotNull(exactOk.startOffset)
    assertNotNull(exactOk.endOffset)
    assertTrue(exactOk.startOffset!! >= 0 && exactOk.endOffset!! > exactOk.startOffset!!)
    assertEquals(1, exactOk.occurrenceCount)

    // 2. Exact quote but WRONG claimed page -> pageMismatchWarning != null -> downgraded in adjudication
    val wrongPage = Reviewer2PipelineService.verifyQuoteInManuscript(
      quote = "Three authors independently extracted study characteristics from 65 eligible publications",
      manuscriptText = manuscript,
      claimedPage = 9
    )
    assertEquals(Reviewer2PipelineService.QuoteMatchKind.EXACT, wrongPage.matchKind)
    assertEquals(2, wrongPage.resolvedPage)
    assertNotNull("Must flag wrong page citation", wrongPage.pageMismatchWarning)

    // 3. Paraphrased / fuzzy quote -> FUZZY_LOCALIZED (must NEVER become VERIFIED in adjudication)
    val fuzzy = Reviewer2PipelineService.verifyQuoteInManuscript(
      quote = "Three authors independently extracted study details across 65 included publications",
      manuscriptText = manuscript,
      claimedPage = 2
    )
    assertEquals(Reviewer2PipelineService.QuoteMatchKind.FUZZY_LOCALIZED, fuzzy.matchKind)

    val forensic = ManuscriptForensicEngine.analyzeManuscript(manuscript, isEmpiricalTrack = true)
    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = listOf(
        ReviewFinding(
          id = "F-WRONG-PAGE",
          title = "Wrong Page Citation",
          allegation = "Extraction protocol had limitations.",
          severity = "MINOR",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(pageNumber = 9, section = "Methods", exactQuote = "Three authors independently extracted study characteristics from 65 eligible publications")
          )
        ),
        ReviewFinding(
          id = "F-FUZZY",
          title = "Paraphrased Quote",
          allegation = "Extraction protocol paraphrased.",
          severity = "MINOR",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(
            EvidenceAnchor(pageNumber = 2, section = "Methods", exactQuote = "Three authors independently extracted study details across 65 included publications")
          )
        )
      ),
      manuscriptText = manuscript,
      forensicReport = forensic,
      methodologyScore = 7,
      significanceScore = 7,
      clarityScore = 7,
      originalityScore = 7,
      mathRigorScore = 7,
      reproducibilityScore = 7,
      rawVerdict = "HIGH_CREDIBILITY",
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL
    )
    val wrongPageOut = adjudicated.adjudicatedFindings.first { it.id == "F-WRONG-PAGE" }
    assertEquals(FindingStatus.PARTIALLY_SUPPORTED, wrongPageOut.status)
    assertTrue(wrongPageOut.verificationNotes.contains("Page mismatch", ignoreCase = true))

    val fuzzyOut = adjudicated.adjudicatedFindings.first { it.id == "F-FUZZY" }
    assertEquals(FindingStatus.PARTIALLY_SUPPORTED, fuzzyOut.status)
  }

  @Test
  fun deterministicFindings_uncorroboratedCiRemainsPartiallySupportedAndVerdictsStrictlyFollowScoreBands() {
    // No Figure 2 cross-reference -> deterministic CI alert must be framed cautiously as PARTIALLY_SUPPORTED
    val uncorroboratedDoc = """
      [PAGE 4]
      Results: Subgroup analysis yielded a pooled estimate of 9, 95% CI 1.26 to 2.35 in the primary cohort.
    """.trimIndent()
    val forensic = ManuscriptForensicEngine.analyzeManuscript(uncorroboratedDoc, isEmpiricalTrack = true)
    val adjudicated = Reviewer2PipelineService.adjudicateFindingsAndScores(
      rawFindings = emptyList(),
      manuscriptText = uncorroboratedDoc,
      forensicReport = forensic,
      methodologyScore = 4,
      significanceScore = 5,
      clarityScore = 5,
      originalityScore = 5,
      mathRigorScore = 4,
      reproducibilityScore = 4,
      rawVerdict = "HIGH_CREDIBILITY", // Contradicts low weighted score of 45
      reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL,
      extractionConfidence = 5
    )

    val q01 = adjudicated.adjudicatedFindings.firstOrNull { it.id == "Q-01" }
    assertNotNull("Must still surface deterministic discrepancy", q01)
    assertTrue(q01!!.title.contains("Requiring Source Reconciliation", ignoreCase = true))
    assertEquals(
      "Uncorroborated deterministic regex alert must not be overstated as VERIFIED",
      FindingStatus.PARTIALLY_SUPPORTED,
      q01.status
    )

    // Strictly score-derived verdicts across all 3 modes (low score 45 cannot stay HIGH_CREDIBILITY / ACCEPT / HIGH_CERTAINTY)
    assertEquals(45, adjudicated.weightedCredibilityScore)
    assertEquals("MAJOR_CONCERNS", adjudicated.resolvedVerdict)

    assertEquals(
      "REJECT_AND_RESUBMIT",
      Reviewer2PipelineService.resolveVerdictForMode(
        rawVerdict = "ACCEPT",
        compositeScore = 45,
        reviewMode = ReviewMode.JOURNAL_REFEREE,
        hasVerifiedCriticalError = false
      )
    )
    assertEquals(
      "VERY_LOW_CERTAINTY",
      Reviewer2PipelineService.resolveVerdictForMode(
        rawVerdict = "HIGH_CERTAINTY",
        compositeScore = 45,
        reviewMode = ReviewMode.CLINICAL_EVIDENCE_APPRAISAL,
        hasVerifiedCriticalError = false
      )
    )
    // High score + verified critical finding must downgrade ACCEPT -> MAJOR_REVISION
    assertEquals(
      "MAJOR_REVISION",
      Reviewer2PipelineService.resolveVerdictForMode(
        rawVerdict = "ACCEPT",
        compositeScore = 88,
        reviewMode = ReviewMode.JOURNAL_REFEREE,
        hasVerifiedCriticalError = true
      )
    )
  }

  @Test
  fun extractionConfidenceCalibration_penalizesGarbledTextOmittedPagesAndUnanchoredInput() {
    val garbled = "\uFFFD".repeat(30) + "###@@@%%% ".repeat(400)
    assertEquals(
      1,
      Reviewer2PipelineService.computeCalibratedExtractionConfidence(
        manuscriptText = garbled,
        coverageReport = null,
        omittedPromptPages = emptyList(),
        wasPromptTrimmed = false,
        adjudicatedFindings = emptyList()
      )
    )

    val cleanUnanchored = "This is a well-formed academic sentence discussing clinical trial methodology and results. ".repeat(40)
    assertEquals(
      "Direct pasted text without PDF page provenance must cap at 4/5",
      4,
      Reviewer2PipelineService.computeCalibratedExtractionConfidence(
        manuscriptText = cleanUnanchored,
        coverageReport = null,
        omittedPromptPages = emptyList(),
        wasPromptTrimmed = false,
        adjudicatedFindings = emptyList()
      )
    )

    val cleanAnchored = "[PAGE 1]\n" + cleanUnanchored + "\n[PAGE 2]\n" + cleanUnanchored
    assertEquals(
      "Omitted prompt pages must cap extraction confidence at 3/5",
      3,
      Reviewer2PipelineService.computeCalibratedExtractionConfidence(
        manuscriptText = cleanAnchored,
        coverageReport = null,
        omittedPromptPages = listOf(3, 4),
        wasPromptTrimmed = true,
        adjudicatedFindings = emptyList()
      )
    )
  }

  @Test
  fun pdfLayoutGuards_triggerCardFallbackForTallTableRowsAndPreventHeaderCollision() {
    assertTrue(
      "Rows taller than available body height minus safety margin must switch to paginated card layout",
      com.example.util.PdfReportExporter.shouldFallbackTableRowToPaginatedCard(
        measuredRowHeight = 760f,
        maxPageBodyHeight = 740f
      )
    )
    assertFalse(
      "Normal compact table rows should remain in table layout",
      com.example.util.PdfReportExporter.shouldFallbackTableRowToPaginatedCard(
        measuredRowHeight = 120f,
        maxPageBodyHeight = 740f
      )
    )

    val contentWidth = 515f
    val metrics = com.example.util.PdfReportExporter.computeSectionHeaderMetrics(
      contentWidth = contentWidth,
      stageTagWidth = 220f // Extra-long stage tag must be clamped so it never collides with title
    )
    assertTrue(metrics.tagMaxWidth <= contentWidth * 0.28f + 0.1f)
    assertTrue(
      "Reserved left badge + title + gap + stage tag + right padding must fit inside contentWidth",
      34f + metrics.titleMaxWidth + 10f + metrics.tagMaxWidth + 8f <= contentWidth + 0.5f
    )
  }
}
