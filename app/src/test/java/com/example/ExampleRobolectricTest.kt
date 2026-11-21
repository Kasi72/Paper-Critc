package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.ManuscriptForensicEngine
import com.example.data.ai.Reviewer2PipelineService
import com.example.data.local.ApiKeyVault
import com.example.data.local.CredentialStorageUnavailableException
import com.example.data.local.KeyDecryptOutcome
import com.example.data.local.PaperCriticSqlDatabase
import com.example.data.local.SqlApiProviderEntity
import com.example.data.local.SqlReviewCacheEntity
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.data.repository.LocalReviewRepository
import com.example.util.ManuscriptExtractor
import com.example.util.PdfExportScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private fun generateTestAesKey(): SecretKey {
    return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
  }

  private fun buildRealPdfBytes(pageTexts: List<String>): ByteArray {
    val context = ApplicationProvider.getApplicationContext<Context>()
    PDFBoxResourceLoader.init(context)
    val out = ByteArrayOutputStream()
    PDDocument().use { doc ->
      pageTexts.forEach { text ->
        val page = PDPage()
        doc.addPage(page)
        if (text.isNotBlank()) {
          PDPageContentStream(doc, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 11f)
            stream.newLineAtOffset(50f, 700f)
            // Keep ASCII-safe lines for standard Helvetica font encoding
            text.lines().forEachIndexed { idx, line ->
              if (idx > 0) stream.newLineAtOffset(0f, -15f)
              stream.showText(line.replace(Regex("[^\\x20-\\x7E]"), " "))
            }
            stream.endText()
          }
        }
      }
      doc.save(out)
    }
    return out.toByteArray()
  }

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
  fun `api key vault fails closed without keystore and migrates legacy plaintext rows immediately`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, PaperCriticSqlDatabase::class.java)
      .allowMainThreadQueries()
      .build()

    try {
      // 1. Fail-closed verification when Android Keystore is unavailable
      ApiKeyVault.keyProviderOverride = { null }
      assertFalse(ApiKeyVault.isHardwareVaultAvailable())
      try {
        ApiKeyVault.encrypt("sk-secret-must-not-fall-back")
        fail("Expected CredentialStorageUnavailableException when keystore is unavailable")
      } catch (_: CredentialStorageUnavailableException) {
        // Expected fail-closed behavior
      }
      // Legacy plaintext must NOT silently decrypt when vault is unavailable
      assertEquals("", ApiKeyVault.decrypt("legacy-plaintext-sk-1234"))
      val legacyOutcome = ApiKeyVault.decryptOutcome("legacy-plaintext-sk-1234")
      assertTrue(legacyOutcome is KeyDecryptOutcome.Decrypted && legacyOutcome.wasLegacyPlaintext)

      // 2. When AES-256 hardware key is available: encrypts with enc_v1 prefix, round-trips, and migrates SQLite rows
      val testKey = generateTestAesKey()
      ApiKeyVault.keyProviderOverride = { testKey }
      assertTrue(ApiKeyVault.isHardwareVaultAvailable())

      val encrypted = ApiKeyVault.encrypt("sk-test-secret-key-9876")
      assertTrue(encrypted.startsWith("enc_v1:"))
      assertNotEquals("sk-test-secret-key-9876", encrypted)
      assertEquals("sk-test-secret-key-9876", ApiKeyVault.decrypt(encrypted))

      // Seed a legacy plaintext provider directly into Room SQLite and run immediate migration
      db.sqlDao().upsertApiProvider(
        SqlApiProviderEntity(
          id = "legacy-openai",
          serviceName = "Legacy OpenAI",
          protocolTypeName = "OPENAI_COMPATIBLE_REST",
          baseUrl = "https://api.openai.com/v1/chat/completions",
          modelId = "gpt-4o",
          apiKey = "sk-legacy-unencrypted-key",
          isFreeTierPreset = false,
          enabled = true,
          notes = "Legacy test row",
          updatedAtEpochMs = 1000L
        )
      )
      val repo = LocalReviewRepository(db.sqlDao())
      val migratedCount = repo.migratePlaintextApiKeysIfNeeded()
      assertEquals(1, migratedCount)

      val storedRow = db.sqlDao().getAllApiProvidersOnce().first { it.id == "legacy-openai" }
      assertTrue("Migrated row must be encrypted on disk", ApiKeyVault.isEncrypted(storedRow.apiKey))
      assertEquals("sk-legacy-unencrypted-key", ApiKeyVault.decrypt(storedRow.apiKey))
    } finally {
      ApiKeyVault.keyProviderOverride = null
      db.close()
    }
  }

  @Test
  fun `real pdfbox multi-page fixture extracts page anchors tracks omitted pages and detects scanned pdfs`() {
    val multiPagePdf = buildRealPdfBytes(
      listOf(
        "Page One Title: Need Estimates of Psychiatric Beds Systematic Review.\nAbstract across 65 included studies.",
        "Page Two Methods: Three authors independently extracted data and conducted random-effects meta-analyses.",
        "Page Three Results: Subgroup long-stay estimate 9, 95% CI 1.26 to 2.35. Figure 2 shows 95% CI 3 to 15."
      )
    )

    val full = ManuscriptExtractor.extractPdfPagesFromBytes(
      rawBytes = multiPagePdf,
      fileName = "psychiatric_beds_fixture.pdf",
      maxChars = 50_000
    )
    assertEquals(3, full.totalPageCount)
    assertTrue(full.omittedPages.isEmpty())
    assertTrue(full.extractedText.contains("[PAGE 1]"))
    assertTrue(full.extractedText.contains("[PAGE 2]"))
    assertTrue(full.extractedText.contains("[PAGE 3]"))
    assertTrue(full.extractedText.contains("9, 95% CI 1.26 to 2.35"))

    // Under a tight character cap, omitted pages must be explicitly recorded (no silent truncation)
    val capped = ManuscriptExtractor.extractPdfPagesFromBytes(
      rawBytes = multiPagePdf,
      fileName = "psychiatric_beds_fixture.pdf",
      maxChars = 220
    )
    assertTrue("Must explicitly list omitted page numbers when cap is reached", capped.omittedPages.isNotEmpty())
    assertTrue(capped.warnings.any { it.contains("omitted", ignoreCase = true) })

    // Zero-text scanned PDF fixture must be flagged as low text density on all pages and scanned vision-only
    val scannedPdf = buildRealPdfBytes(listOf("", ""))
    val scanned = ManuscriptExtractor.extractPdfPagesFromBytes(
      rawBytes = scannedPdf,
      fileName = "scanned_manuscript.pdf",
      maxChars = 50_000
    )
    assertEquals(2, scanned.totalPageCount)
    assertTrue(scanned.isScannedVisionOnly)
    assertTrue(scanned.extractedPages.all { it.hasLowTextDensity && it.text.isBlank() })
    assertTrue(scanned.extractedText.contains("SCANNED / IMAGE-BASED", ignoreCase = true))
    assertTrue(scanned.warnings.any { it.contains("scanned", ignoreCase = true) })
  }

  @Test
  fun `vision page routing sends up to 6 pages to pass 1 and prioritizes discrepancy and figure pages for pass 2`() {
    val bitmaps = (1..6).map { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888) }
    val pageNumbers = listOf(1, 3, 5, 8, 9, 12)
    val manuscript = """
      [PAGE 1]
      Title and Abstract
      [PAGE 3]
      Methods narrative
      [PAGE 5]
      Table 1 baseline cohort metrics
      [PAGE 8]
      Subgroup pooled long-stay bed estimate of 9, 95% CI 1.26 to 2.35 per 100,000.
      [PAGE 9]
      Figure 2 forest plot showing pooled estimate 9 (95% CI 3–15).
      [PAGE 12]
      References list
    """.trimIndent()
    val forensic = ManuscriptForensicEngine.analyzeManuscript(manuscript, isEmpiricalTrack = true)

    try {
      val (pass1Bmps, pass1Pages) = Reviewer2PipelineService.selectVisionPagesForPass(
        allBitmaps = bitmaps,
        visionPageNumbers = pageNumbers,
        forensicReport = forensic,
        manuscriptText = manuscript,
        maxBitmaps = 6,
        prioritizeDiscrepancyAndFigures = false
      )
      assertEquals(6, pass1Bmps.size)
      assertEquals(pageNumbers, pass1Pages)

      val (pass2Bmps, pass2Pages) = Reviewer2PipelineService.selectVisionPagesForPass(
        allBitmaps = bitmaps,
        visionPageNumbers = pageNumbers,
        forensicReport = forensic,
        manuscriptText = manuscript,
        maxBitmaps = 4,
        prioritizeDiscrepancyAndFigures = true
      )
      assertEquals(4, pass2Bmps.size)
      assertTrue("Pass 2 must prioritize Page 8 with deterministic CI discrepancy", 8 in pass2Pages)
      assertTrue("Pass 2 must prioritize Page 9 with Figure 2 cross-reference", 9 in pass2Pages)
      assertTrue("Pass 2 must prioritize Page 5 with Table 1", 5 in pass2Pages)
    } finally {
      bitmaps.forEach { it.recycle() }
    }
  }
}
