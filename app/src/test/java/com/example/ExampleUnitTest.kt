package com.example

import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.QuantFindingStatus
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.StudyDesignArchetype
import com.example.util.CalloutTone
import com.example.util.DossierFormattingEngine
import com.example.util.ReportBlock
import com.example.util.SeverityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM unit tests verifying Paper Critic's formatting engine, domain-aware forensic scanner,
 * and exact deterministic quantitative/statistical audit math.
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
      "Should flag unweighted median/IQR synthesis in quantitative forensics",
      report.quantForensics.heterogeneitySignals.any { it.contains("UNWEIGHTED SYNTHESIS", ignoreCase = true) }
    )
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
      Across k = 18 included studies, a fixed-effect meta-analysis yielded I² = 84%.
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
      "Should flag missing Egger/funnel bias check for k = 18 >= 10",
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
    val review = com.example.data.model.PaperReview(
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
}
