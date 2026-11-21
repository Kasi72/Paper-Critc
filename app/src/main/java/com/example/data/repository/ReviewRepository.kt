package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.PaperReview
import com.example.data.model.ReviewMode
import com.example.util.OperationType
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ReviewRepository(private val db: FirebaseFirestore) {

  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private val auth = Firebase.auth

  private fun requireUserId(): String {
    return auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
  }

  fun observeReviews(userId: String = requireUserId()): Flow<List<PaperReview>> = flow {
    val path = "users/$userId/reviews"
    emitAll(
      db.collection("users")
        .document(userId)
        .collection("reviews")
        .whereEqualTo("userId", userId)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            PaperReview::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
          ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        }
        .catch { error ->
          val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == userId) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun getUserReviews(): Result<List<PaperReview>> = runCatching {
    val uid = requireUserId()
    val path = "users/$uid/reviews"
    try {
      val snapshot = db.collection("users")
        .document(uid)
        .collection("reviews")
        .whereEqualTo("userId", uid)
        .get()
        .await()
      snapshot.toObjects(
        PaperReview::class.java,
        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
      ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, path)
      throw e
    }
  }

  suspend fun getReviewById(reviewId: String, ownerUserId: String = requireUserId()): Result<PaperReview> = runCatching {
    val docRef = db.collection("users").document(ownerUserId).collection("reviews").document(reviewId)
    try {
      val snapshot = docRef.get().await()
      snapshot.toObject(
        PaperReview::class.java,
        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
      ) ?: throw IllegalStateException("Review document $reviewId not found")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      throw e
    }
  }

  suspend fun createReview(review: PaperReview): Result<String> = runCatching {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid).collection("reviews").document()
    // Never include "id" in raw Map payload when @DocumentId is on PaperReview
    val payload = buildFirestorePayload(
      review = review,
      uid = uid,
      createdAtValue = FieldValue.serverTimestamp(),
      updatedAtValue = FieldValue.serverTimestamp()
    )
    try {
      docRef.set(payload).await()
      docRef.id
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      throw e
    }
  }

  suspend fun updateReview(review: PaperReview): Result<Unit> = runCatching {
    val uid = requireUserId()
    require(review.id.isNotBlank()) { "Review ID must not be blank for update" }
    val existingCreatedAt = requireNotNull(review.createdAt) { "Review createdAt must not be null for update" }
    val docRef = db.collection("users").document(uid).collection("reviews").document(review.id)
    val payload = buildFirestorePayload(
      review = review,
      uid = uid,
      createdAtValue = existingCreatedAt,
      updatedAtValue = FieldValue.serverTimestamp()
    )
    try {
      docRef.set(payload).await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      throw e
    }
  }

  suspend fun deleteReview(reviewId: String): Result<Unit> = runCatching {
    val uid = requireUserId()
    require(reviewId.isNotBlank()) { "Review ID must not be blank for delete" }
    val docRef = db.collection("users").document(uid).collection("reviews").document(reviewId)
    try {
      docRef.delete().await()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      throw e
    }
  }

  companion object {
    private val ALLOWED_VERDICTS = setOf(
      "ACCEPT", "MINOR_REVISION", "MAJOR_REVISION", "REJECT_AND_RESUBMIT", "REJECT",
      "HIGH_CREDIBILITY", "SOUND_WITH_CAVEATS", "REQUIRES_CORRECTION", "MAJOR_CONCERNS", "LOW_CREDIBILITY",
      "HIGH_CERTAINTY", "MODERATE_CERTAINTY", "LOW_CERTAINTY", "VERY_LOW_CERTAINTY"
    )

    private val ALLOWED_VALIDATION_STATUSES = setOf(
      "VERIFIED", "ADJUDICATED_WITH_CAVEATS", "PARTIALLY_VALIDATED", "UNVERIFIED"
    )

    internal fun normalizeVerdict(raw: String, mode: ReviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL): String {
      val upper = raw.trim().uppercase(Locale.US).replace(' ', '_').replace('-', '_')
      if (upper in ALLOWED_VERDICTS) return upper
      return when (mode) {
        ReviewMode.POST_PUBLICATION_APPRAISAL -> when {
          upper.contains("LOW_CRED") -> "LOW_CREDIBILITY"
          upper.contains("MAJOR_CONCERN") -> "MAJOR_CONCERNS"
          upper.contains("CORRECTION") || upper.contains("ERRAT") -> "REQUIRES_CORRECTION"
          upper.contains("HIGH_CRED") -> "HIGH_CREDIBILITY"
          else -> "SOUND_WITH_CAVEATS"
        }
        ReviewMode.CLINICAL_EVIDENCE_APPRAISAL -> when {
          upper.contains("VERY_LOW") -> "VERY_LOW_CERTAINTY"
          upper.contains("LOW") -> "LOW_CERTAINTY"
          upper.contains("HIGH") -> "HIGH_CERTAINTY"
          else -> "MODERATE_CERTAINTY"
        }
        ReviewMode.JOURNAL_REFEREE -> when {
          upper.contains("RESUBMIT") -> "REJECT_AND_RESUBMIT"
          upper.contains("REJECT") -> "REJECT"
          upper.contains("MAJOR") -> "MAJOR_REVISION"
          upper.contains("ACCEPT") -> "ACCEPT"
          else -> "MINOR_REVISION"
        }
      }
    }

    internal fun normalizeValidationStatus(raw: String): String {
      val upper = raw.trim().uppercase(Locale.US).replace(' ', '_')
      return if (upper in ALLOWED_VALIDATION_STATUSES) upper else "PARTIALLY_VALIDATED"
    }

    /**
     * Builds the complete, rule-aligned Firestore document payload preserving ALL 6-axis rubric
     * scores/justifications, separated confidence metrics, appraisal mode, coverage provenance,
     * validation status, and structured evidence ledger JSON (without `@DocumentId` "id").
     */
    internal fun buildFirestorePayload(
      review: PaperReview,
      uid: String,
      createdAtValue: Any,
      updatedAtValue: Any
    ): Map<String, Any> {
      val resolvedMode = review.resolvedReviewMode()
      val authoritativeComposite = review.toRubric().overallCredibilityScore.toLong().coerceIn(0L, 100L)
      return mapOf(
        "userId" to uid,
        "title" to review.title.trim().take(500).ifEmpty { "Untitled Manuscript" },
        "authors" to review.authors.trim().take(500).ifEmpty { "Unknown Authors" },
        "publicationYear" to review.publicationYear.trim().take(20).ifEmpty { "2026" },
        "venueOrCitation" to review.venueOrCitation.trim().take(500),
        "isEmpirical" to review.isEmpirical,
        "credibilityScore" to authoritativeComposite,
        "verdict" to normalizeVerdict(review.verdict, resolvedMode),
        // Structured 6-axis rubric & full justifications
        "methodologyScore" to review.methodologyScore.coerceIn(0, 10),
        "significanceScore" to review.significanceScore.coerceIn(0, 10),
        "clarityScore" to review.clarityScore.coerceIn(0, 10),
        "originalityScore" to review.originalityScore.coerceIn(0, 10),
        "mathRigorScore" to review.mathRigorScore.coerceIn(0, 10),
        "reproducibilityScore" to review.reproducibilityScore.coerceIn(0, 10),
        "methodologyJustification" to review.methodologyJustification.trim().take(4000),
        "significanceJustification" to review.significanceJustification.trim().take(4000),
        "clarityJustification" to review.clarityJustification.trim().take(4000),
        "originalityJustification" to review.originalityJustification.trim().take(4000),
        "mathRigorJustification" to review.mathRigorJustification.trim().take(4000),
        "reproducibilityJustification" to review.reproducibilityJustification.trim().take(4000),
        // Separated confidence, mode, provenance & structured evidence ledger
        "refereeConfidence" to review.refereeConfidence.coerceIn(1, 5),
        "extractionConfidence" to review.extractionConfidence.coerceIn(1, 5),
        "reviewMode" to resolvedMode.name,
        "coverageDisclosure" to review.coverageDisclosure.trim().take(2000),
        "validationStatus" to normalizeValidationStatus(review.validationStatus),
        "structuredFindingsJson" to review.structuredFindingsJson.trim().take(60000),
        // Narrative dossier sections
        "abstractSummary" to review.abstractSummary.trim().take(5000).ifEmpty { "No abstract summary provided." },
        "researchQuestion" to review.researchQuestion.trim().take(3000).ifEmpty { "Not specified." },
        "keyMethodology" to review.keyMethodology.trim().take(4000).ifEmpty { "Not specified." },
        "coreContributions" to review.coreContributions.trim().take(6000).ifEmpty { "Not specified." },
        "redTeamCritique" to review.redTeamCritique.trim().take(12000).ifEmpty { "No red team critique recorded." },
        "blueTeamVerification" to review.blueTeamVerification.trim().take(10000).ifEmpty { "No verification notes recorded." },
        "mathAuditReport" to review.mathAuditReport.trim().take(8000),
        "codeAuditReport" to review.codeAuditReport.trim().take(8000),
        "editorNote" to review.editorNote.trim().take(8000).ifEmpty { "No editor note recorded." },
        "copyeditSuggestions" to review.copyeditSuggestions.trim().take(8000),
        "reviewerQuestions" to review.reviewerQuestions.trim().take(8000),
        "formattedDossier" to review.formattedDossier.trim().take(40000).ifEmpty { "Paper Critic Appraisal Dossier" },
        "bookmarked" to review.bookmarked,
        "personalNotes" to review.personalNotes.trim().take(5000),
        "createdAt" to createdAtValue,
        "updatedAt" to updatedAtValue
      )
    }
  }
}
