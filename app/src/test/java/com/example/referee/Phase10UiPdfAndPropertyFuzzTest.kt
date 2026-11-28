package com.example.referee

import android.graphics.Bitmap
import com.example.data.ai.QuantitativeForensicAnalyzer
import com.example.data.ai.StudyDesignArchetype
import com.example.data.ai.referee.AbstentionGate
import com.example.data.ai.referee.AbstentionReason
import com.example.data.ai.referee.AbstentionResult
import com.example.data.ai.referee.AbstentionTrigger
import com.example.data.ai.referee.AnalysisManifest
import com.example.data.ai.referee.AutonomousRefereeOrchestrator
import com.example.data.ai.referee.DocumentReadiness
import com.example.data.ai.referee.DocumentReadinessEvaluator
import com.example.data.ai.referee.PipelineStageId
import com.example.data.ai.referee.QuantitativeAdjudicationContracts
import com.example.data.ai.referee.QuantitativeFindingStatus
import com.example.data.ai.referee.RefereeCoverageMetrics
import com.example.data.ai.referee.RefereeDecision
import com.example.data.ai.referee.TamperEvidentAuditLog
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.ApiProtocolType
import com.example.data.model.EvidenceAnchor
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.ui.viewmodel.AiProviderRoute
import com.example.ui.viewmodel.AppSettingsState
import com.example.ui.viewmodel.PaperCriticViewModel
import com.example.ui.viewmodel.WorkbenchFormState
import com.example.util.DocumentCoverageReport
import com.example.util.PdfExportScope
import com.example.util.PdfReportExporter
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 10 UI/PDF Export Verification + Phase 11 Deterministic-Seed Randomized Property Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase10UiPdfAndPropertyFuzzTest {

  @Test
  fun phase10_pdfExportRendersCoverageAbstentionProvenanceAndNeverOverclaimsCompleteAudit() {
    val abstention = AbstentionResult(
      shouldAbstainFromVerifiedVerdict = true,
      hardBlockAllVerdicts = false,
      triggeredReasons = listOf(
        AbstentionReason(
          trigger = AbstentionTrigger.CAPPED_MANUSCRIPT_COVERAGE,
          whatIsMissing = "2 omitted pages and 1 skipped claim.",
          whyItMatters = "Partial coverage cannot rule out limitations on omitted pages.",
          blockedConclusion = "Complete verified audit blocked.",
          nextStepsForUser = listOf("Inspect omitted pages manually.")
        )
      ),
      primaryWhatIsMissing = "2 omitted pages and 1 skipped claim.",
      primaryWhyItMatters = "Partial coverage cannot rule out limitations on omitted pages.",
      primaryBlockedConclusion = "Complete verified audit blocked.",
      combinedNextSteps = listOf("Inspect omitted pages manually.")
    )
    val coverage = RefereeCoverageMetrics(
      pagesExtracted = 6,
      totalPages = 8,
      pagesAnalyzed = 6,
      pagesOmitted = 2,
      claimsDiscovered = 10,
      claimsRetained = 9,
      claimsReconstructed = 8,
      claimsSkipped = 1,
      unsupportedClaims = 1,
      unresolvedClaims = 1,
      sourceAnchorsVerified = 1,
      interPassDisagreements = 0,
      extractionCapApplied = true,
      claimCapApplied = true,
      standardizedLabel = RefereeCoverageMetrics.LABEL_PARTIAL
    )
    val manifest = AnalysisManifest(
      analysisId = "ana-pdf-1",
      manuscriptSha256 = "a".repeat(64),
      replicationCodeSha256 = "b".repeat(64),
      pdfVisionDigest = "c".repeat(64),
      providerRoute = "Firebase AI Logic (Tier 1)",
      modelId = "gemini-3.8-flash",
      fallbackHistory = emptyList(),
      promptVersion = "v30.1",
      rulesetVersion = "referee-2026.11",
      appVersion = "1.0.0",
      startedAtMs = 1_700_000_000_000L,
      completedAtMs = 1_700_000_005_000L,
      consentDigestAtApproval = "d".repeat(64),
      consentVerified = true
    )
    val auditLog = TamperEvidentAuditLog().apply {
      PipelineStageId.entries.forEachIndexed { idx, stage ->
        append(stage.name, "STAGE_COMPLETED", "stage-$idx", 1000L + idx * 100L)
      }
    }
    val commitment = TamperEvidentAuditLog.computeCommitmentHash(
      terminalHash = auditLog.entries().last().entryHash,
      analysisId = manifest.analysisId,
      manuscriptSha256 = manifest.manuscriptSha256,
      stageCount = auditLog.entries().size
    )
    val readiness = DocumentReadinessEvaluator.assess(
      manuscriptText = "Methods\nRandomized trial.\nResults\nHR 0.80 (95% CI 0.65 to 0.98).\nReferences\n1. A.",
      coverageReport = DocumentCoverageReport.forCompleteText("Methods Results"),
      attachedBitmapCount = 0
    )

    val review = PaperReview(
      id = "rev-pdf-10",
      title = "Bounded Referee Trial Appraisal",
      authors = "Doe J",
      publicationYear = "2026",
      venueOrCitation = "Journal of Reproducible Methods",
      isEmpirical = true,
      credibilityScore = 72L,
      verdict = "PROVISIONAL_APPRAISAL",
      methodologyScore = 7,
      significanceScore = 7,
      clarityScore = 8,
      originalityScore = 7,
      mathRigorScore = 7,
      reproducibilityScore = 7,
      refereeConfidence = 4,
      extractionConfidence = 4,
      coverageDisclosure = "6/8 pages extracted • Quantitative Coverage Partial: discoverySkipped=1",
      validationStatus = "ADJUDICATED_WITH_CAVEATS",
      structuredFindingsJson = ReviewFindingCodec.encode(
        listOf(
          ReviewFinding(
            id = "F-1",
            title = "Primary outcome verified",
            allegation = "Primary outcome HR 0.80 reported clearly.",
            severity = "MINOR",
            status = FindingStatus.VERIFIED,
            supportingEvidence = listOf(
              EvidenceAnchor(
                documentId = "doc-1",
                pageNumber = 2,
                section = "Results",
                exactQuote = "HR 0.80 (95% CI 0.65 to 0.98)",
                startOffset = 10,
                endOffset = 38,
                extractionMethod = "PDFBox-Android",
                sourceChecksum = "chk-1"
              )
            )
          )
        )
      ),
      abstractSummary = "Bounded appraisal summary.",
      researchQuestion = "Does intervention improve survival?",
      keyMethodology = "Randomized trial.",
      coreContributions = "Mortality comparison.",
      blueTeamVerification = "Primary outcome quote verified.",
      editorNote = "Proceed with caution due to partial page coverage.",
      refereeDecision = RefereeDecision.PROVISIONAL_APPRAISAL.name,
      analysisManifestJson = manifest.toJson(),
      abstentionSummaryJson = abstention.toJson(),
      readinessSummaryJson = readiness.toJson(),
      auditLogJson = auditLog.toJson(),
      auditCommitment = commitment,
      refereeCoverageJson = coverage.toJson()
    )

    val rendered = PdfReportExporter.renderAndInspectPdf(
      review = review,
      scope = PdfExportScope.FULL_TECHNICAL_APPENDIX,
      requireByteExtraction = false
    )

    assertTrue("Expected valid PDF header", rendered.pdfBytes.isNotEmpty())
    assertTrue("Expected geometry & diagnostics clean: ${rendered.diagnostics}", rendered.diagnostics.isClean)
    assertTrue(rendered.renderedText.contains("Partial quantitative coverage", ignoreCase = true))
    assertTrue(rendered.renderedText.contains("SAFE ABSTENTION GATE", ignoreCase = true))
    assertTrue(rendered.renderedText.contains("PROVENANCE & TAMPER-EVIDENT AUDIT MANIFEST", ignoreCase = true))
    assertTrue(rendered.renderedText.contains("SHA-256 Chain Verified", ignoreCase = true))
    assertFalse("Must never claim complete audit when caps > 0", rendered.renderedText.contains("complete audit", ignoreCase = true))

    // Empty auditLogJson MUST NOT claim SHA-256 Chain Verified — must disclose Audit Log Not Available
    val missingAuditRendered = PdfReportExporter.renderAndInspectPdf(
      review = review.copy(auditLogJson = "", auditCommitment = ""),
      scope = PdfExportScope.SCIENTIFIC_APPRAISAL_REPORT,
      requireByteExtraction = false
    )
    assertTrue(missingAuditRendered.renderedText.contains("Audit Log Not Available", ignoreCase = true))
    assertFalse(missingAuditRendered.renderedText.contains("SHA-256 Chain Verified", ignoreCase = true))
  }

  @Test
  fun phase11_propertyFuzz_chunkRoundTripAndTamperRejection() {
    val rng = Random(20261128L)
    repeat(60) { iter ->
      val count = rng.nextInt(5, 35)
      val findings = (1..count).map { idx ->
        ReviewFinding(
          id = "FZ-$iter-$idx",
          title = "Finding $idx",
          allegation = "Generated fuzz payload #${rng.nextInt(100_000)} with variance=${rng.nextDouble()}",
          severity = listOf("CRITICAL", "MAJOR", "MINOR").random(rng),
          status = FindingStatus.entries.random(rng)
        )
      }
      val encoded = ReviewFindingCodec.encode(findings)
      val chunks = ReviewFindingCodec.splitIntoLosslessChunks(encoded, maxChunkChars = rng.nextInt(256, 1500))
      val checksum = chunks.first().checksum
      val restored = ReviewFindingCodec.reassembleAndVerifyChunks(
        chunks = chunks.shuffled(rng),
        expectedChunkCount = chunks.size,
        expectedChecksum = checksum
      )
      assertEquals(encoded, restored)

      // Tampering a random chunk payload MUST fail verification
      val badIdx = rng.nextInt(chunks.size)
      val tampered = chunks.mapIndexed { i, c ->
        if (i == badIdx) c.copy(payload = c.payload + "x") else c
      }
      assertNull(
        ReviewFindingCodec.reassembleAndVerifyChunks(
          chunks = tampered,
          expectedChunkCount = chunks.size,
          expectedChecksum = checksum
        )
      )
    }
  }

  @Test
  fun phase11_propertyFuzz_tamperEvidentAuditChainRejectsAnyMutation() {
    val rng = Random(424242L)
    repeat(60) { iter ->
      val log = TamperEvidentAuditLog()
      val steps = rng.nextInt(3, 12)
      for (s in 0 until steps) {
        log.append("S${s + 1}", "EVENT_${rng.nextInt(999)}", "payload-$iter-$s-${rng.nextLong()}")
      }
      assertTrue(log.verifyChain())
      val entries = log.entries()
      val victim = rng.nextInt(steps)
      val mutated = entries.toMutableList()
      mutated[victim] = mutated[victim].copy(payloadSummary = mutated[victim].payloadSummary + "_tampered")
      assertFalse("Mutation at index $victim MUST invalidate SHA-256 chain", TamperEvidentAuditLog.verifyChain(mutated))
    }
  }

  @Test
  fun phase11_propertyFuzz_malformedStatisticalInputsNeverCrashOrFalseConfirm() {
    val rng = Random(998877L)
    val malformedSnippets = listOf(
      "HR NaN (95% CI -Infinity to Infinity, p = 1.45, N = -12)",
      "OR 1.85 (95% CI 2.40 to 0.90, p = -0.05) in n = 0 patients",
      "mean = 0.00, SD = NaN, t(0) = Infinity, p = 9.99",
      "95% CI [10.0, 2.0] with estimate 5.5 and p = 0.000",
      "effect size = Infinity (95% CI NaN to 0.00)"
    )
    repeat(60) {
      val snippet = malformedSnippets[rng.nextInt(malformedSnippets.size)] + " random=${rng.nextInt(1000)}"
      val fullText = "Methods\nCohort study.\nResults\n$snippet\nReferences\n1. Ref."
      val report = QuantitativeForensicAnalyzer.analyze(
        text = fullText,
        studyDesign = StudyDesignArchetype.CLINICAL_EPIDEMIOLOGICAL,
        sampleSizes = listOf(120),
        coverageReport = DocumentCoverageReport.forCompleteText(fullText),
        extractionConfidence = 5
      )
      // Must never crash and any statistical status without full reconciliation must not auto-promote silently
      val mapped = report.statisticalAudit?.reconstructions.orEmpty().map { rec ->
        QuantitativeAdjudicationContracts.mapStatus(
          result = rec,
          sourceAnchorsVerified = false,
          estimandCompatible = false
        )
      }
      assertTrue(mapped.none { it == QuantitativeFindingStatus.CONFIRMED_ERROR })
    }
  }

  @Test
  fun phase11_propertyFuzz_consentInvalidationAndFailClosedProviderRouting() {
    val rng = Random(771122L)
    repeat(50) { i ->
      val bmp1 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        setPixel(0, 0, android.graphics.Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256)))
      }
      val bmp2 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        setPixel(0, 0, android.graphics.Color.rgb((i + 1) % 256, 12, 34))
        setPixel(1, 1, android.graphics.Color.RED)
      }
      val form1 = WorkbenchFormState(
        manuscriptText = "Manuscript version A #$i",
        replicationCodeText = "fit <- lm(y ~ x)",
        replicationCodeBoundDraftId = "draft-$i",
        draftId = "draft-$i",
        pdfPageBitmaps = listOf(bmp1)
      )
      val provider = AiApiProviderEntry(
        id = "p-$i",
        serviceName = "Custom Endpoint",
        protocolType = ApiProtocolType.OPENAI_COMPATIBLE_REST,
        baseUrl = "https://api.example.com/v1",
        modelId = "custom-model-$i",
        apiKey = "secret-key-$i",
        enabled = true
      )
      val settingsCustom = AppSettingsState(useCustomApiProvider = true, activeProviderId = provider.id)
      val route1 = PaperCriticViewModel.resolveAiProviderRoute(settingsCustom, listOf(provider))
      assertTrue(route1 is AiProviderRoute.CustomProvider)

      val keyBase = PaperCriticViewModel.computeRemoteConsentKey(
        form = form1,
        providerRoute = route1,
        ownerScope = "user-1",
        sessionGen = 1L,
        enablePdfVisionPages = true
      )
      val keyTextChanged = PaperCriticViewModel.computeRemoteConsentKey(
        form = form1.copy(manuscriptText = "Manuscript version B #$i"),
        providerRoute = route1,
        ownerScope = "user-1",
        sessionGen = 1L,
        enablePdfVisionPages = true
      )
      val keyVisionChanged = PaperCriticViewModel.computeRemoteConsentKey(
        form = form1.copy(pdfPageBitmaps = listOf(bmp2)),
        providerRoute = route1,
        ownerScope = "user-1",
        sessionGen = 1L,
        enablePdfVisionPages = true
      )
      assertNotEquals(keyBase, keyTextChanged)
      assertNotEquals(keyBase, keyVisionChanged)

      // Fail-closed: missing or blank API key MUST return UnavailableCustomProvider, never BuiltInKeyless
      val brokenProvider = provider.copy(apiKey = "")
      val brokenRoute = PaperCriticViewModel.resolveAiProviderRoute(settingsCustom, listOf(brokenProvider))
      assertTrue("Must fail closed instead of falling back to BuiltInKeyless", brokenRoute is AiProviderRoute.UnavailableCustomProvider)
      bmp1.recycle()
      bmp2.recycle()
    }
  }
}
