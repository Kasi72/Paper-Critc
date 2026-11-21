package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.PaperReview
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

  private fun normalizeVerdict(raw: String): String {
    val upper = raw.trim().uppercase().replace(' ', '_').replace('-', '_')
    return when {
      upper in setOf("ACCEPT", "MINOR_REVISION", "MAJOR_REVISION", "REJECT_AND_RESUBMIT", "REJECT") -> upper
      upper.contains("RESUBMIT") -> "REJECT_AND_RESUBMIT"
      upper.contains("REJECT") -> "REJECT"
      upper.contains("MAJOR") -> "MAJOR_REVISION"
      upper.contains("MINOR") -> "MINOR_REVISION"
      upper.contains("ACCEPT") -> "ACCEPT"
      else -> "MINOR_REVISION"
    }
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
    val payload = mapOf(
      "userId" to uid,
      "title" to review.title.trim().take(500).ifEmpty { "Untitled Manuscript" },
      "authors" to review.authors.trim().take(500).ifEmpty { "Unknown Authors" },
      "publicationYear" to review.publicationYear.trim().take(20).ifEmpty { "2026" },
      "venueOrCitation" to review.venueOrCitation.trim().take(500),
      "isEmpirical" to review.isEmpirical,
      "credibilityScore" to review.credibilityScore.coerceIn(0L, 100L),
      "verdict" to normalizeVerdict(review.verdict),
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
      "formattedDossier" to review.formattedDossier.trim().take(40000).ifEmpty { "IS IT CREDIBLE? Dossier" },
      "bookmarked" to review.bookmarked,
      "personalNotes" to review.personalNotes.trim().take(5000),
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp()
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
    val payload = mapOf(
      "userId" to uid,
      "title" to review.title.trim().take(500).ifEmpty { "Untitled Manuscript" },
      "authors" to review.authors.trim().take(500).ifEmpty { "Unknown Authors" },
      "publicationYear" to review.publicationYear.trim().take(20).ifEmpty { "2026" },
      "venueOrCitation" to review.venueOrCitation.trim().take(500),
      "isEmpirical" to review.isEmpirical,
      "credibilityScore" to review.credibilityScore.coerceIn(0L, 100L),
      "verdict" to normalizeVerdict(review.verdict),
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
      "formattedDossier" to review.formattedDossier.trim().take(40000).ifEmpty { "IS IT CREDIBLE? Dossier" },
      "bookmarked" to review.bookmarked,
      "personalNotes" to review.personalNotes.trim().take(5000),
      "createdAt" to existingCreatedAt,
      "updatedAt" to FieldValue.serverTimestamp()
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
}
