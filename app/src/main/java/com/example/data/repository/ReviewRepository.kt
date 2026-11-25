package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.util.OperationType
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

private const val REPO_TAG = "ReviewRepository"
private const val FINDINGS_CHUNKS_COLLECTION = "findings_chunks"

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

  private suspend fun hydrateChunkedFindings(review: PaperReview, ownerUid: String): PaperReview {
    if (review.findingsChunkCount <= 0 || review.id.isBlank()) {
      return review
    }
    val chunksSnap = db.collection("users")
      .document(ownerUid)
      .collection("reviews")
      .document(review.id)
      .collection(FINDINGS_CHUNKS_COLLECTION)
      .orderBy("chunkIndex", Query.Direction.ASCENDING)
      .get()
      .await()

    val orderedPayloads = chunksSnap.documents.mapNotNull { doc ->
      doc.getString("payload")
    }
    if (orderedPayloads.size < review.findingsChunkCount) {
      throw IllegalStateException(
        "Incomplete findings ledger chunks for ${review.id}: found ${orderedPayloads.size}/${review.findingsChunkCount}."
      )
    }
    val reassembled = ReviewFindingCodec.reassembleAndVerify(
      chunks = orderedPayloads,
      expectedChecksumSha256 = review.findingsChecksum
    ).getOrThrow()
    return review.copy(structuredFindingsJson = reassembled)
  }

  private suspend fun writeDocumentWithLosslessChunks(
    docRef: DocumentReference,
    review: PaperReview,
    uid: String,
    createdAtValue: Any,
    updatedAtValue: Any,
    cleanupPreviousChunks: Boolean
  ) {
    val packaged = ReviewFindingCodec.packageForCloud(review.structuredFindingsJson)
    if (packaged.chunks.isNotEmpty()) {
      val verifiedJson = ReviewFindingCodec.reassembleAndVerify(
        chunks = packaged.chunks,
        expectedChecksumSha256 = packaged.checksumSha256
      ).getOrThrow()
      val originalDecoded = ReviewFindingCodec.decode(review.structuredFindingsJson)
      val verifiedDecoded = ReviewFindingCodec.decode(verifiedJson)
      if (originalDecoded.size != verifiedDecoded.size ||
        originalDecoded.map { it.id } != verifiedDecoded.map { it.id }
      ) {
        throw IllegalStateException(
          "Pre-commit lossless chunk verification failed: finding ledger count/IDs diverged (${originalDecoded.size} vs ${verifiedDecoded.size})."
        )
      }
    }
    val payload = buildFirestorePayload(
      review = review,
      uid = uid,
      createdAtValue = createdAtValue,
      updatedAtValue = updatedAtValue,
      packagedLedger = packaged
    )

    val existingChunkRefs = if (cleanupPreviousChunks) {
      runCatching {
        docRef.collection(FINDINGS_CHUNKS_COLLECTION).get().await().documents.map { it.reference }
      }.getOrDefault(emptyList())
    } else {
      emptyList()
    }

    val batch = db.batch()
    batch.set(docRef, payload)

    // Remove stale excess chunks if new chunk count is smaller
    existingChunkRefs.forEach { oldRef ->
      val idx = oldRef.id.removePrefix("chunk_").toIntOrNull()
      if (idx == null || idx >= packaged.chunks.size) {
        batch.delete(oldRef)
      }
    }

    packaged.chunks.forEachIndexed { index, chunkPayload ->
      val chunkRef = docRef.collection(FINDINGS_CHUNKS_COLLECTION).document("chunk_$index")
      batch.set(
        chunkRef,
        mapOf(
          "userId" to uid,
          "chunkIndex" to index,
          "totalChunks" to packaged.chunks.size,
          "payload" to chunkPayload,
          "checksum" to ReviewFindingCodec.sha256Hex(chunkPayload)
        )
      )
    }

    batch.commit().await()
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
          val rawReviews = snapshot.toObjects(
            PaperReview::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
          ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
          rawReviews.map { review ->
            if (review.findingsChunkCount > 0) {
              try {
                hydrateChunkedFindings(review, userId)
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Log.w(REPO_TAG, "Failed to hydrate chunked findings for ${review.id}", e)
                review
              }
            } else {
              review
            }
          }
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

  suspend fun getUserReviews(): Result<List<PaperReview>> {
    val uid = try {
      requireUserId()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    val path = "users/$uid/reviews"
    return try {
      val snapshot = db.collection("users")
        .document(uid)
        .collection("reviews")
        .whereEqualTo("userId", uid)
        .get()
        .await()
      val reviews = snapshot.toObjects(
        PaperReview::class.java,
        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
      ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
        .map { review ->
          if (review.findingsChunkCount > 0) hydrateChunkedFindings(review, uid) else review
        }
      Result.success(reviews)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, path)
      Result.failure(e)
    }
  }

  suspend fun getReviewById(reviewId: String, ownerUserId: String? = null): Result<PaperReview> {
    val resolvedOwner = try {
      ownerUserId?.takeIf { it.isNotBlank() } ?: requireUserId()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    val docRef = db.collection("users").document(resolvedOwner).collection("reviews").document(reviewId)
    return try {
      val snapshot = docRef.get().await()
      val review = snapshot.toObject(
        PaperReview::class.java,
        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
      ) ?: throw IllegalStateException("Review document $reviewId not found")
      val hydrated = if (review.findingsChunkCount > 0) {
        hydrateChunkedFindings(review, resolvedOwner)
      } else {
        review
      }
      Result.success(hydrated)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun createReview(review: PaperReview): Result<String> {
    val uid = try {
      requireUserId()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    val docRef = db.collection("users").document(uid).collection("reviews").document()
    return try {
      writeDocumentWithLosslessChunks(
        docRef = docRef,
        review = review,
        uid = uid,
        createdAtValue = FieldValue.serverTimestamp(),
        updatedAtValue = FieldValue.serverTimestamp(),
        cleanupPreviousChunks = false
      )
      Result.success(docRef.id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun updateReview(review: PaperReview): Result<Unit> {
    val uid = try {
      requireUserId()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    if (review.id.isBlank()) {
      return Result.failure(IllegalArgumentException("Review ID must not be blank for update"))
    }
    val existingCreatedAt = review.createdAt
      ?: return Result.failure(IllegalArgumentException("Review createdAt must not be null for update"))
    val docRef = db.collection("users").document(uid).collection("reviews").document(review.id)
    return try {
      writeDocumentWithLosslessChunks(
        docRef = docRef,
        review = review,
        uid = uid,
        createdAtValue = existingCreatedAt,
        updatedAtValue = FieldValue.serverTimestamp(),
        cleanupPreviousChunks = true
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  /**
   * Field-level partial update for bookmark state so concurrent edits to personalNotes or
   * other fields never clobber one another.
   */
  suspend fun updateBookmark(reviewId: String, bookmarked: Boolean): Result<Unit> {
    val uid = try {
      requireUserId()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    if (reviewId.isBlank()) {
      return Result.failure(IllegalArgumentException("Review ID must not be blank for bookmark update"))
    }
    val docRef = db.collection("users").document(uid).collection("reviews").document(reviewId)
    return try {
      docRef.update(buildBookmarkPatchPayload(bookmarked)).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  /**
   * Field-level partial update for personalNotes so concurrent edits to bookmark or other fields
   * never overwrite each other.
   */
  suspend fun updatePersonalNotes(reviewId: String, notes: String): Result<Unit> {
    val uid = try {
      requireUserId()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    if (reviewId.isBlank()) {
      return Result.failure(IllegalArgumentException("Review ID must not be blank for notes update"))
    }
    val docRef = db.collection("users").document(uid).collection("reviews").document(reviewId)
    return try {
      docRef.update(buildPersonalNotesPatchPayload(notes)).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun deleteReview(reviewId: String): Result<Unit> {
    val uid = try {
      requireUserId()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    if (reviewId.isBlank()) {
      return Result.failure(IllegalArgumentException("Review ID must not be blank for delete"))
    }
    val docRef = db.collection("users").document(uid).collection("reviews").document(reviewId)
    return try {
      val chunkDocs = runCatching {
        docRef.collection(FINDINGS_CHUNKS_COLLECTION).get().await().documents
      }.getOrDefault(emptyList())
      val batch = db.batch()
      chunkDocs.forEach { batch.delete(it.reference) }
      batch.delete(docRef)
      batch.commit().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docRef.path)
      Result.failure(e)
    }
  }

  companion object {
    private val POST_PUB_VERDICTS = setOf(
      "HIGH_CREDIBILITY", "SOUND_WITH_CAVEATS", "REQUIRES_CORRECTION", "MAJOR_CONCERNS", "LOW_CREDIBILITY",
      "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN"
    )
    private val JOURNAL_VERDICTS = setOf(
      "ACCEPT", "MINOR_REVISION", "MINOR_REVISION_WITH_CAVEATS", "MAJOR_REVISION", "REJECT_AND_RESUBMIT", "REJECT",
      "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN"
    )
    private val CLINICAL_VERDICTS = setOf(
      "HIGH_CERTAINTY", "MODERATE_CERTAINTY", "LOW_CERTAINTY", "VERY_LOW_CERTAINTY",
      "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT", "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN"
    )

    private val ALLOWED_VALIDATION_STATUSES = setOf(
      "VERIFIED", "ADJUDICATED_WITH_CAVEATS", "PARTIALLY_VALIDATED", "PROVISIONAL",
      "UNVERIFIED", "LEGACY_UNVALIDATED", "REQUIRES_REANALYSIS"
    )

    internal fun buildBookmarkPatchPayload(
      bookmarked: Boolean,
      updatedAtValue: Any = FieldValue.serverTimestamp()
    ): Map<String, Any> = mapOf(
      "bookmarked" to bookmarked,
      "updatedAt" to updatedAtValue
    )

    internal fun buildPersonalNotesPatchPayload(
      notes: String,
      updatedAtValue: Any = FieldValue.serverTimestamp()
    ): Map<String, Any> = mapOf(
      "personalNotes" to notes.trim().take(5000),
      "updatedAt" to updatedAtValue
    )

    internal fun allowedVerdictsForMode(mode: ReviewMode): Set<String> = when (mode) {
      ReviewMode.POST_PUBLICATION_APPRAISAL -> POST_PUB_VERDICTS
      ReviewMode.JOURNAL_REFEREE -> JOURNAL_VERDICTS
      ReviewMode.CLINICAL_EVIDENCE_APPRAISAL -> CLINICAL_VERDICTS
    }

    internal fun normalizeVerdict(raw: String, mode: ReviewMode = ReviewMode.POST_PUBLICATION_APPRAISAL): String {
      val upper = raw.trim().uppercase(Locale.US).replace(' ', '_').replace('-', '_')
      if (upper in allowedVerdictsForMode(mode)) return upper
      if (upper.contains("PROVISIONAL") && upper.contains("UNCERTAIN")) {
        return "PROVISIONAL_APPRAISAL_DESIGN_UNCERTAIN"
      }
      if (upper.contains("INSUFFICIENT_EVIDENCE")) {
        return "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      }
      return when (mode) {
        ReviewMode.POST_PUBLICATION_APPRAISAL -> when {
          upper.contains("VERY_LOW") || upper == "REJECT" || upper.contains("LOW_CRED") -> "LOW_CREDIBILITY"
          upper.contains("LOW_CERT") || upper.contains("RESUBMIT") || upper.contains("MAJOR_CONCERN") -> "MAJOR_CONCERNS"
          upper.contains("CORRECTION") || upper.contains("ERRAT") || upper.contains("MAJOR") -> "REQUIRES_CORRECTION"
          upper.contains("HIGH") || upper == "ACCEPT" -> "HIGH_CREDIBILITY"
          else -> "SOUND_WITH_CAVEATS"
        }
        ReviewMode.CLINICAL_EVIDENCE_APPRAISAL -> when {
          upper.contains("VERY_LOW") || upper == "REJECT" || upper.contains("LOW_CRED") -> "VERY_LOW_CERTAINTY"
          upper.contains("LOW") || upper.contains("RESUBMIT") || upper.contains("MAJOR") || upper.contains("CORRECTION") -> "LOW_CERTAINTY"
          upper.contains("HIGH") || upper == "ACCEPT" -> "HIGH_CERTAINTY"
          else -> "MODERATE_CERTAINTY"
        }
        ReviewMode.JOURNAL_REFEREE -> when {
          upper.contains("VERY_LOW") || upper.contains("LOW_CRED") || upper == "REJECT" -> "REJECT"
          upper.contains("RESUBMIT") || upper.contains("MAJOR_CONCERN") -> "REJECT_AND_RESUBMIT"
          upper.contains("CORRECTION") || upper.contains("LOW_CERT") || upper.contains("MAJOR") -> "MAJOR_REVISION"
          upper.contains("MINOR") && upper.contains("CAVEAT") -> "MINOR_REVISION_WITH_CAVEATS"
          upper.contains("HIGH") || upper == "ACCEPT" -> "ACCEPT"
          else -> "MINOR_REVISION"
        }
      }
    }

    internal fun normalizeValidationStatus(raw: String): String {
      val upper = raw.trim().uppercase(Locale.US).replace(' ', '_')
      return if (upper in ALLOWED_VALIDATION_STATUSES) upper else "LEGACY_UNVALIDATED"
    }

    /**
     * Builds the complete, strictly self-consistent Firestore document payload:
     * - Derives all 6 rubric dimensions (`1..10`) AND `credibilityScore` from a single reconciled
     *   [PaperReviewRubric] instance so individual axes and the weighted composite can NEVER disagree.
     * - Never truncates `structuredFindingsJson`: oversized ledgers are chunked losslessly via
     *   [ReviewFindingCodec.packageForCloud] and stored in `findings_chunks`.
     * - Never fabricates a default publication year ("2026") when year is absent/unverified.
     */
    internal fun buildFirestorePayload(
      review: PaperReview,
      uid: String,
      createdAtValue: Any,
      updatedAtValue: Any,
      packagedLedger: ReviewFindingCodec.LosslessLedgerPackage = ReviewFindingCodec.packageForCloud(review.structuredFindingsJson)
    ): Map<String, Any> {
      val resolvedMode = review.resolvedReviewMode()
      val rubric = review.toRubric()
      val authoritativeComposite = rubric.overallCredibilityScore.toLong().coerceIn(0L, 100L)
      return mapOf(
        "userId" to uid,
        "title" to review.title.trim().take(500).ifEmpty { "Untitled Manuscript" },
        "authors" to review.authors.trim().take(500).ifEmpty { "Authors not verified" },
        "publicationYear" to review.publicationYear.trim().take(40),
        "venueOrCitation" to review.venueOrCitation.trim().take(500),
        "isEmpirical" to review.isEmpirical,
        "credibilityScore" to authoritativeComposite,
        "verdict" to normalizeVerdict(review.verdict, resolvedMode),
        // Structured 6-axis rubric (strictly 1..10, guaranteed consistent with credibilityScore)
        "methodologyScore" to rubric.methodology.coerceIn(1, 10),
        "significanceScore" to rubric.significance.coerceIn(1, 10),
        "clarityScore" to rubric.clarity.coerceIn(1, 10),
        "originalityScore" to rubric.originality.coerceIn(1, 10),
        "mathRigorScore" to rubric.mathematicalRigor.coerceIn(1, 10),
        "reproducibilityScore" to rubric.reproducibility.coerceIn(1, 10),
        "methodologyJustification" to review.methodologyJustification.trim().take(4000),
        "significanceJustification" to review.significanceJustification.trim().take(4000),
        "clarityJustification" to review.clarityJustification.trim().take(4000),
        "originalityJustification" to review.originalityJustification.trim().take(4000),
        "mathRigorJustification" to review.mathRigorJustification.trim().take(4000),
        "reproducibilityJustification" to review.reproducibilityJustification.trim().take(4000),
        // Separated confidence, mode, provenance & lossless chunked evidence ledger metadata
        "refereeConfidence" to review.refereeConfidence.coerceIn(1, 5),
        "extractionConfidence" to review.extractionConfidence.coerceIn(1, 5),
        "reviewMode" to resolvedMode.name,
        "coverageDisclosure" to review.coverageDisclosure.trim().take(2000),
        "validationStatus" to normalizeValidationStatus(review.validationStatus),
        "structuredFindingsJson" to packagedLedger.inlineJson,
        "schemaVersion" to review.schemaVersion.coerceAtLeast(5),
        "findingsChunkCount" to packagedLedger.chunks.size,
        "findingsChecksum" to packagedLedger.checksumSha256,
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
