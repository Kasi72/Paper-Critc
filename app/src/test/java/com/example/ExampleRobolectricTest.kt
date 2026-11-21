package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ApiKeyVault
import com.example.data.local.PaperCriticSqlDatabase
import com.example.data.local.SqlReviewCacheEntity
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.util.PdfExportScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Paper Critic", appName)
  }

  @Test
  fun `room v4 database persists untruncated rubric justifications structured findings and provenance`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val longRationale = "Random-effects meta-analyses and meta-regression were appropriately executed across 65 publications, " +
        "yet the narrative long-stay subgroup confidence interval on Page 8 (9, 95% CI 1.26 to 2.35) conflicts directly with " +
        "Figure 2 on Page 9 (95% CI 3–15) and warrants a published numerical erratum prior to policy adoption."
      val findings = listOf(
        ReviewFinding(
          id = "Q-01",
          title = "Mathematically Impossible Confidence Interval (9, 95% CI 1.26 to 2.35)",
          allegation = "Narrative text reports estimate 9 outside [1.26, 2.35]; Figure 2 on Page 9 shows 9 (95% CI 3–15).",
          severity = "CRITICAL",
          status = FindingStatus.VERIFIED,
          supportingEvidence = listOf(EvidenceAnchor(pageNumber = 8, section = "Results", exactQuote = "9, 95% CI 1.26 to 2.35")),
          counterEvidence = listOf(EvidenceAnchor(pageNumber = 9, section = "Figure 2", exactQuote = "9 (95% CI 3–15)")),
          methodologicalJustification = "A point estimate must lie strictly within its confidence bounds.",
          recommendedCorrection = "Verify pooled bounds and publish an erratum."
        )
      )
      val review = PaperReview(
        id = "room-v4-test-1",
        userId = "local-user",
        title = "Need Estimates of Psychiatric Beds: A Systematic Review and Meta-Analysis",
        authors = "Mundt et al.",
        venueOrCitation = "World Psychiatry",
        publicationYear = "2022",
        reviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL.name,
        verdict = "REQUIRES_CORRECTION",
        methodologyScore = 6,
        significanceScore = 8,
        clarityScore = 7,
        originalityScore = 7,
        mathRigorScore = 5,
        reproducibilityScore = 4,
        credibilityScore = 64L,
        refereeConfidence = 4,
        extractionConfidence = 5,
        validationStatus = "VERIFIED",
        coverageDisclosure = "12/12 page(s) extracted • Engine: PDFBox-Android",
        methodologyJustification = longRationale,
        mathRigorJustification = longRationale,
        structuredFindingsJson = ReviewFindingCodec.encode(findings),
        abstractSummary = "Global synthesis of 65 publications providing 98 estimates of psychiatric bed needs."
      )

      db.sqlDao().upsertReview(SqlReviewCacheEntity.fromPaperReview(review))
      val loadedEntity = db.sqlDao().getReviewById("room-v4-test-1")
      assertNotNull(loadedEntity)

      val loaded = loadedEntity!!.toPaperReview()
      assertEquals(64, loaded.toRubric().overallCredibilityScore)
      assertEquals(longRationale, loaded.methodologyJustification)
      assertEquals(longRationale, loaded.mathRigorJustification)
      assertEquals(4, loaded.refereeConfidence)
      assertEquals(5, loaded.extractionConfidence)
      assertEquals(ReviewMode.POST_PUBLICATION_APPRAISAL, loaded.resolvedReviewMode())
      assertEquals("12/12 page(s) extracted • Engine: PDFBox-Android", loaded.coverageDisclosure)
      val parsedFindings = loaded.parsedFindings()
      assertEquals(1, parsedFindings.size)
      assertEquals("Q-01", parsedFindings.first().id)
      assertEquals(8, parsedFindings.first().supportingEvidence.first().pageNumber)
      assertEquals(9, parsedFindings.first().counterEvidence.first().pageNumber)
    } finally {
      db.close()
    }
  }

  @Test
  fun `pdf export scopes expose clean scientific report and technical appendix options`() {
    assertEquals("appraisal_report", PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT.fileSuffix)
    assertEquals("full_technical_dossier", PdfExportScope.FULL_TECHNICAL_APPENDIX.fileSuffix)
    assertTrue(PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT.displayName.contains("Scientific", ignoreCase = true))
    assertTrue(PdfExportScope.FULL_TECHNICAL_APPENDIX.displayName.contains("Appendix", ignoreCase = true))
  }

  @Test
  fun `api key vault preserves plaintext compatibility and round trips keys`() {
    assertEquals("legacy-sk-1234", ApiKeyVault.decrypt("legacy-sk-1234"))
    assertEquals("", ApiKeyVault.encrypt(""))
    val encrypted = ApiKeyVault.encrypt("sk-test-secret-key-9876")
    val decrypted = ApiKeyVault.decrypt(encrypted)
    assertEquals("sk-test-secret-key-9876", decrypted)
  }
}

