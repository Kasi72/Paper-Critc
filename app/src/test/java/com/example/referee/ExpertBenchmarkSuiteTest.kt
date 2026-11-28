package com.example.referee

import com.example.data.ai.referee.RefereeBenchmarkHarness
import com.example.data.ai.referee.ReleaseReadinessEvaluator
import com.example.data.ai.referee.ReleaseValidationState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 11 Expert Benchmark Fixture Suite & Phase 12 Release Readiness Verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExpertBenchmarkSuiteTest {

  @Test
  fun benchmarkHarness_computesCalibrationAgreementReproducibilityAndReportsNotExternallyValidated() {
    val report = RefereeBenchmarkHarness.evaluate()

    assertFalse("Must explicitly report externallyValidated=false until external unseen testing completes", report.externallyValidated)
    assertTrue(report.validationDisclaimer.contains("externallyValidated=false", ignoreCase = true))
    assertEquals(4, report.totalFixtures)
    assertTrue(report.precision in 0.0..1.0)
    assertTrue(report.recall in 0.0..1.0)
    assertTrue(report.falsePositiveRate in 0.0..1.0)
    assertTrue(report.falseNegativeRate in 0.0..1.0)
    assertTrue(report.abstentionRate > 0.0)
    assertTrue(report.brierScore in 0.0..1.0)
    assertTrue(report.expectedCalibrationError in 0.0..1.0)
    assertTrue(report.interPassAgreementRate in 0.0..1.0)
    assertEquals(1.0, report.reportReproducibilityRate, 0.0001)
    assertEquals(1.0, report.precision, 0.0001)
    assertEquals(1.0, report.recall, 0.0001)
  }

  @Test
  fun releaseReadinessEvaluator_passesAll12GatesAndVerifiesArchitectureDoc() {
    val docFile = listOf(
      File("docs/AUTONOMOUS_REFEREE_ARCHITECTURE.md"),
      File("../docs/AUTONOMOUS_REFEREE_ARCHITECTURE.md")
    ).firstOrNull { it.exists() && it.length() > 500L }

    val report = ReleaseReadinessEvaluator.evaluate(architectureDocPresent = docFile != null)
    assertFalse(report.externallyValidated)
    assertEquals(ReleaseValidationState.EXTERNAL_VALIDATION_PENDING, report.validationState)
    assertTrue(report.statusHeadline.contains("EXTERNAL VALIDATION PENDING", ignoreCase = true))
    assertEquals(12, report.totalGates)
    assertTrue(
      "Expected all 12 release gates to pass, failed: ${report.gates.filterNot { it.passed }}",
      report.allGatesPassed
    )
    assertEquals(12, report.passedCount)
  }
}
