package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.PaperReview
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRepositoryRuleTest : FirestoreEmulatorTestBase() {

  private fun sampleReview(title: String = "Yell at It: Automated Peer Review") = PaperReview(
    title = title,
    authors = "S. Garg, R. Kasi",
    publicationYear = "2026",
    venueOrCitation = "isitcredible.com Working Paper",
    isEmpirical = true,
    credibilityScore = 88L,
    verdict = "MINOR_REVISION",
    abstractSummary = "Evaluates a 30-stage adversarial LLM peer-review pipeline.",
    researchQuestion = "Does adversarial red-teaming paired with blue-team verification beat single-shot LLM review?",
    keyMethodology = "Blinded pairwise tournament across 20 benchmark manuscripts.",
    coreContributions = "1. Red-team Breaker/Butcher/Shredder. 2. Verification cascade.",
    redTeamCritique = "Subgroup power is limited at N=20 manuscripts.",
    blueTeamVerification = "Verified exact binomial test and Table 1 win counts (15-4-1).",
    mathAuditReport = "All inline equations verified.",
    codeAuditReport = "Deterministic stage caching verified.",
    editorNote = "Valuable contribution to automated scholarly verification.",
    copyeditSuggestions = "Clarify Figure 2 axis labels.",
    reviewerQuestions = "How does cost scale on 100-page appendices?",
    formattedDossier = "IS IT CREDIBLE? - Full Plain-Text Report",
    bookmarked = false,
    personalNotes = "Initial test dossier."
  )

  @Test
  fun createReview_validPayload_createsDocumentAndReturnsId() = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repository = ReviewRepository(firestore)

    val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.createReview(sampleReview())
    }
    assertTrue(createResult.isSuccess)
    val reviewId = createResult.getOrThrow()
    assertTrue(reviewId.isNotEmpty())
  }

  @Test
  fun getUserReviews_authenticatedOwner_returnsMatchingReviews() = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repository = ReviewRepository(firestore)
    val reviewId = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.createReview(sampleReview("Paper Alpha")).getOrThrow()
    }

    val reviewsResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.getUserReviews()
    }
    assertTrue(reviewsResult.isSuccess)
    assertTrue(reviewsResult.getOrThrow().any { it.id == reviewId })
  }

  @Test
  fun observeReviews_authenticatedOwner_emitsRealtimeUpdatesWithUpdatedNotes() = runBlocking {
    val uid = signInTestUser(ALICE_EMAIL)
    val repository = ReviewRepository(firestore)
    val reviewId = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.createReview(sampleReview("Observable Paper")).getOrThrow()
    }

    val emitted = withTimeout(FLOW_TIMEOUT_MS) {
      repository.observeReviews(uid).first { list -> list.any { it.id == reviewId } }
    }
    val createdReview = emitted.first { it.id == reviewId }
    assertNotNull(createdReview.createdAt)

    val updateResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.updateReview(createdReview.copy(bookmarked = true, personalNotes = "Verified by referee"))
    }
    assertTrue(updateResult.isSuccess)
  }

  @Test
  fun getReviewById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = ReviewRepository(firestore)
    val reviewId = withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRepo.createReview(sampleReview("Alice Confidential Paper")).getOrThrow()
    }

    signInTestUser(BOB_EMAIL)
    val bobRepo = ReviewRepository(firestore)
    // Layer 1 (P1-06 client boundary): ReviewRepository refuses cross-account access before hitting Firestore
    val repoResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      bobRepo.getReviewById(reviewId, ownerUserId = aliceUid)
    }
    assertTrue(repoResult.isFailure)
    assertTrue(
      "Expected SecurityException from repository account guard, got ${repoResult.exceptionOrNull()}",
      repoResult.exceptionOrNull() is SecurityException
    )

    // Layer 2 (zero-trust Firestore server rule): Direct Firestore read by Bob must fail with PERMISSION_DENIED
    val directResult = runCatching {
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("users").document(aliceUid)
          .collection("reviews").document(reviewId)
          .get()
          .await()
      }
    }
    assertTrue(directResult.isFailure)
    val firestoreException = directResult.exceptionOrNull()?.unwrapFirestoreException()
    assertNotNull(firestoreException)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
  }

  @Test
  fun observeReviews_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    auth.signOut()
    val repository = ReviewRepository(firestore)

    var caught: Throwable? = null
    try {
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeReviews(aliceUid).first()
      }
    } catch (e: Throwable) {
      caught = e
    }
    assertNotNull(caught)
    val firestoreException = caught?.unwrapFirestoreException()
    assertNotNull("Expected nested FirebaseFirestoreException, got $caught", firestoreException)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
  }

  private companion object {
    const val ALICE_EMAIL = "alice@papercritic.org"
    const val BOB_EMAIL = "bob@papercritic.org"
    const val DEFAULT_TIMEOUT_MS = 5000L
    const val FLOW_TIMEOUT_MS = 4000L
  }
}
