package com.example.data.repository

import android.util.Log
import com.example.data.model.LedgerLoadState
import com.example.data.model.NarrativeSectionCodec
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFindingCodec
import com.example.data.model.ReviewMode
import com.example.util.OperationType
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.snapshots
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await

private const val REPO_TAG = "ReviewRepository"
private const val FINDINGS_CHUNKS_COLLECTION = "findings_chunks"
private const val NARRATIVE_CHUNKS_COLLECTION = "narrative_chunks"
private const val MAX_HYDRATION_CONCURRENCY = 4

class ReviewRepository(
  private val db: FirebaseFirestore = Firebase.firestore,
  private val auth: FirebaseAuth = Firebase.auth
) {

  private fun requireUserId(expectedUserId: String? = null): String {
    val activeUid = auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in to access Cloud Paper Reviews.")
    if (!expectedUserId.isNullOrBlank() && activeUid != expectedUserId) {
      throw SecurityException(
        "Active authenticated account ($activeUid) no longer matches operation owner ($expectedUserId); aborting cloud mutation."
      )
    }
    return activeUid
  }

  private suspend fun hydrateReviewChunks(review: PaperReview, ownerUid: String): PaperReview {
    if (review.id.isBlank()) return inspectInlineLedgerIntegrity(review)
    val docRef = db.collection("users").document(ownerUid).collection("reviews").document(review.id)
    var current = review

    // 1. Hydrate chunked findings ledger if present
    if (current.findingsChunkCount > 0) {
      val chunksSnap = docRef
        .collection(FINDINGS_CHUNKS_COLLECTION)
        .orderBy("chunkIndex", Query.Direction.ASCENDING)
        .get()
        .await()

      val orderedPayloads = chunksSnap.documents.mapNotNull { doc -> doc.getString("payload") }
      if (orderedPayloads.size < current.findingsChunkCount) {
        throw IncompleteLedgerException(
          "Incomplete findings ledger chunks for ${current.id}: found ${orderedPayloads.size}/${current.findingsChunkCount}."
        )
      }
      val reassembled = ReviewFindingCodec.reassembleAndVerify(
        chunks = orderedPayloads,
        expectedChecksumSha256 = current.findingsChecksum
      ).getOrElse { err ->
        throw CorruptLedgerException("Corrupt findings ledger for ${current.id}: ${err.message}", err)
      }
      current = current.copy(structuredFindingsJson = reassembled)
    } else {
      current = inspectInlineLedgerIntegrity(current)
    }

    // 2. Hydrate lossless narrative section chunks if present (P1-05)
    if (current.narrativeChunkCount > 0) {
      val narrativeSnap = docRef
        .collection(NARRATIVE_CHUNKS_COLLECTION)
        .orderBy("chunkIndex", Query.Direction.ASCENDING)
        .get()
        .await()

      val orderedNarratives = narrativeSnap.documents.mapNotNull { doc -> doc.getString("payload") }
      if (orderedNarratives.size < current.narrativeChunkCount) {
        throw IncompleteLedgerException(
          "Incomplete narrative chunks for ${current.id}: found ${orderedNarratives.size}/${current.narrativeChunkCount}."
        )
      }
      current = NarrativeSectionCodec.reassembleAndApply(
        review = current,
        chunks = orderedNarratives,
        expectedChecksumSha256 = current.narrativeChecksum
      ).getOrElse { err ->
        throw CorruptLedgerException("Corrupt narrative chunks for ${current.id}: ${err.message}", err)
      }
    }

    return current
  }

  private suspend fun writeDocumentWithLosslessChunks(
    docRef: DocumentReference,
    review: PaperReview,
    uid: String,
    createdAtValue: Any,
    updatedAtValue: Any,
    cleanupPreviousChunks: Boolean
  ) {
    // Validate & package structured findings (both inline AND chunked validated inside packageForCloud)
    val packagedFindings = ReviewFindingCodec.packageForCloud(review.structuredFindingsJson)
    if (packagedFindings.chunks.isNotEmpty()) {
      val verifiedJson = ReviewFindingCodec.reassembleAndVerify(
        chunks = packagedFindings.chunks,
        expectedChecksumSha256 = packagedFindings.checksumSha256
      ).getOrThrow()
      val originalDecoded = ReviewFindingCodec.decodeResult(review.structuredFindingsJson).getOrThrow()
      val verifiedDecoded = ReviewFindingCodec.decodeResult(verifiedJson).getOrThrow()
      if (originalDecoded.size != verifiedDecoded.size ||
        originalDecoded.map { it.id } != verifiedDecoded.map { it.id }
      ) {
        throw IllegalStateException(
          "Pre-commit lossless findings chunk verification failed: count/IDs diverged (${originalDecoded.size} vs ${verifiedDecoded.size})."
        )
      }
    }

    // Package overflowed long narrative sections losslessly into narrative_chunks (P1-05)
    val packagedNarratives = NarrativeSectionCodec.packageOverflowNarratives(review)
    if (packagedNarratives.chunks.isNotEmpty()) {
      NarrativeSectionCodec.reassembleAndApply(
        review = review,
        chunks = packagedNarratives.chunks,
        expectedChecksumSha256 = packagedNarratives.checksumSha256
      ).getOrThrow()
    }

    val payload = buildFirestorePayload(
      review = review,
      uid = uid,
      createdAtValue = createdAtValue,
      updatedAtValue = updatedAtValue,
      packagedLedger = packagedFindings,
      packagedNarratives = packagedNarratives
    )

    // P1-02: NEVER swallow enumeration errors during stale-chunk cleanup!
    val existingFindingChunkRefs = if (cleanupPreviousChunks) {
      docRef.collection(FINDINGS_CHUNKS_COLLECTION).get().await().documents.map { it.reference }
    } else {
      emptyList()
    }
    val existingNarrativeChunkRefs = if (cleanupPreviousChunks) {
      docRef.collection(NARRATIVE_CHUNKS_COLLECTION).get().await().documents.map { it.reference }
    } else {
      emptyList()
    }

    val batch = db.batch()
    batch.set(docRef, payload)

    existingFindingChunkRefs.forEach { oldRef ->
      val idx = oldRef.id.removePrefix("chunk_").toIntOrNull()
      if (idx == null || idx >= packagedFindings.chunks.size) {
        batch.delete(oldRef)
      }
    }
    existingNarrativeChunkRefs.forEach { oldRef ->
      val idx = oldRef.id.removePrefix("chunk_").toIntOrNull()
      if (idx == null || idx >= packagedNarratives.chunks.size) {
        batch.delete(oldRef)
      }
    }

    packagedFindings.chunks.forEachIndexed { index, chunkPayload ->
      val chunkRef = docRef.collection(FINDINGS_CHUNKS_COLLECTION).document("chunk_$index")
      batch.set(
        chunkRef,
        mapOf(
          "userId" to uid,
          "chunkIndex" to index,
          "totalChunks" to packagedFindings.chunks.size,
          "payload" to chunkPayload,
          "checksum" to ReviewFindingCodec.sha256Hex(chunkPayload)
        )
      )
    }

    packagedNarratives.chunks.forEachIndexed { index, chunkPayload ->
      val chunkRef = docRef.collection(NARRATIVE_CHUNKS_COLLECTION).document("chunk_$index")
      batch.set(
        chunkRef,
        mapOf(
          "userId" to uid,
          "chunkIndex" to index,
          "totalChunks" to packagedNarratives.chunks.size,
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
          // Bounded-concurrency hydration so large archives never stall sequentially
          val semaphore = Semaphore(MAX_HYDRATION_CONCURRENCY)
          coroutineScope {
            rawReviews.map { review ->
              async {
                if (review.findingsChunkCount > 0 || review.narrativeChunkCount > 0) {
                  semaphore.withPermit {
                    try {
                      hydrateReviewChunks(review, userId)
                    } catch (e: CancellationException) {
                      throw e
                    } catch (e: Throwable) {
                      Log.w(REPO_TAG, "Failed to hydrate chunks for ${review.id}", e)
                      applyHydrationFailureState(review, e)
                    }
                  }
                } else {
                  inspectInlineLedgerIntegrity(review)
                }
              }
            }.awaitAll()
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

  /**
   * Explicitly retries chunk hydration on demand for a review whose evidence ledger was
   * temporarily unavailable during archive observation.
   */
  suspend fun retryHydrateReview(review: PaperReview, expectedUserId: String): Result<PaperReview> {
    val uid = try {
      requireUserId(expectedUserId)
    } catch (e: Exception) {
      return Result.failure(e)
    }
    return try {
      Result.success(hydrateReviewChunks(review, uid))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getUserReviews(expectedUserId: String? = null): Result<List<PaperReview>> {
    val uid = try {
      requireUserId(expectedUserId)
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
      val rawReviews = snapshot.toObjects(
        PaperReview::class.java,
        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
      ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
      val semaphore = Semaphore(MAX_HYDRATION_CONCURRENCY)
      val reviews = coroutineScope {
        rawReviews.map { review ->
          async {
            if (review.findingsChunkCount > 0 || review.narrativeChunkCount > 0) {
              semaphore.withPermit {
                try {
                  hydrateReviewChunks(review, uid)
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Throwable) {
                  applyHydrationFailureState(review, e)
                }
              }
            } else {
              inspectInlineLedgerIntegrity(review)
            }
          }
        }.awaitAll()
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
      requireUserId(ownerUserId)
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
      val hydrated = if (review.findingsChunkCount > 0 || review.narrativeChunkCount > 0) {
        hydrateReviewChunks(review, resolvedOwner)
      } else {
        inspectInlineLedgerIntegrity(review)
      }
      Result.success(hydrated)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, docRef.path)
      Result.failure(e)
    }
  }

  suspend fun createReview(review: PaperReview, expectedUserId: String? = null): Result<String> {
    val uid = try {
      requireUserId(expectedUserId)
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

  suspend fun updateReview(review: PaperReview, expectedUserId: String? = null): Result<Unit> {
    val uid = try {
      requireUserId(expectedUserId)
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
   * Field-level partial update for bookmark state with explicit account-ownership enforcement
   * so concurrent edits never clobber notes and account switches never target another account.
   */
  suspend fun updateBookmark(
    reviewId: String,
    bookmarked: Boolean,
    expectedUserId: String? = null
  ): Result<Unit> {
    val uid = try {
      requireUserId(expectedUserId)
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
   * Field-level partial update for personalNotes with explicit account-ownership enforcement.
   */
  suspend fun updatePersonalNotes(
    reviewId: String,
    notes: String,
    expectedUserId: String? = null
  ): Result<Unit> {
    val uid = try {
      requireUserId(expectedUserId)
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

  /**
   * Fail-Visible Recursive Deletion:
   * - Enumerates ALL child chunks (`findings_chunks` and `narrative_chunks`) WITHOUT swallowing
   *   errors. If enumeration fails, nothing is deleted.
   * - For all supported records (<= 499 child docs; current rules cap chunks at 64 + 64 = 128),
   *   deletes ALL children + parent in ONE truly atomic Firestore WriteBatch.
   * - For extraordinary legacy records exceeding 499 children, marks the parent with a resumable
   *   `pendingDeletion` tombstone before multi-batch cleanup so partial failures stay resumable.
   */
  suspend fun deleteReview(reviewId: String, expectedUserId: String? = null): Result<Unit> {
    val uid = try {
      requireUserId(expectedUserId)
    } catch (e: Exception) {
      return Result.failure(e)
    }
    if (reviewId.isBlank()) {
      return Result.failure(IllegalArgumentException("Review ID must not be blank for delete"))
    }
    val docRef = db.collection("users").document(uid).collection("reviews").document(reviewId)
    return deleteReviewAtomically(
      enumerateFindingsChunks = {
        docRef.collection(FINDINGS_CHUNKS_COLLECTION).get().await().documents.map { it.reference }
      },
      enumerateNarrativeChunks = {
        docRef.collection(NARRATIVE_CHUNKS_COLLECTION).get().await().documents.map { it.reference }
      },
      markPendingDeletion = {
        // Resumable tombstone marker ONLY invoked for legacy records > 499 child chunks
        docRef.update(
          mapOf(
            "validationStatus" to "REQUIRES_REANALYSIS",
            "updatedAt" to FieldValue.serverTimestamp()
          )
        ).await()
      },
      commitDeleteRefs = { childRefs ->
        if (childRefs.size <= MAX_SINGLE_BATCH_CHILD_DELETES) {
          // True single-batch atomic deletion of all children + parent (all supported records <= 128 chunks)
          val batch = db.batch()
          childRefs.forEach { batch.delete(it) }
          batch.delete(docRef)
          batch.commit().await()
        } else {
          // Resumable multi-batch cleanup for oversized legacy records (> 499 children)
          val chunkedChildren = childRefs.chunked(450)
          chunkedChildren.forEachIndexed { idx, group ->
            val batch = db.batch()
            group.forEach { batch.delete(it) }
            if (idx == chunkedChildren.lastIndex) {
              batch.delete(docRef)
            }
            batch.commit().await()
          }
        }
      },
      onError = { e -> handleFirestoreError(e, OperationType.DELETE, docRef.path) }
    )
  }

  class IncompleteLedgerException(message: String) : IllegalStateException(message)
  class CorruptLedgerException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

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

    internal fun verifyExpectedAccountOrThrow(activeUid: String?, expectedUid: String?): String {
      val cleanActive = activeUid?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("User must be signed in to access Cloud Paper Reviews.")
      if (!expectedUid.isNullOrBlank() && cleanActive != expectedUid) {
        throw SecurityException(
          "Account mismatch: active account ($cleanActive) != expected account ($expectedUid)."
        )
      }
      return cleanActive
    }

    /**
     * Inspects inline `structuredFindingsJson` on non-chunked reviews so corrupt JSON is marked
     * [LedgerLoadState.CORRUPT] and NEVER presented as `VERIFIED`.
     */
    internal fun inspectInlineLedgerIntegrity(review: PaperReview): PaperReview {
      if (review.structuredFindingsJson.isBlank()) {
        return review.copy(ledgerLoadState = LedgerLoadState.COMPLETE)
      }
      val decoded = ReviewFindingCodec.decodeResult(review.structuredFindingsJson)
      return if (decoded.isSuccess) {
        review.copy(ledgerLoadState = LedgerLoadState.COMPLETE)
      } else {
        review.copy(
          validationStatus = if (review.validationStatus == "VERIFIED") "REQUIRES_REANALYSIS" else review.validationStatus,
          ledgerLoadState = LedgerLoadState.CORRUPT
        )
      }
    }

    /**
     * P1-01: Explicitly transitions an unhydrated or corrupt review to a non-verified degraded
     * [LedgerLoadState] so the UI/PDF NEVER presents a review with missing evidence as `VERIFIED`.
     */
    internal fun applyHydrationFailureState(review: PaperReview, error: Throwable): PaperReview {
      val state = when (error) {
        is IncompleteLedgerException -> LedgerLoadState.INCOMPLETE
        is CorruptLedgerException -> LedgerLoadState.CORRUPT
        else -> LedgerLoadState.TEMPORARILY_UNAVAILABLE
      }
      val safeStatus = when {
        state == LedgerLoadState.CORRUPT -> "REQUIRES_REANALYSIS"
        review.validationStatus.equals("VERIFIED", ignoreCase = true) -> "PROVISIONAL"
        else -> review.validationStatus
      }
      return review.copy(
        validationStatus = safeStatus,
        ledgerLoadState = state
      )
    }

    internal const val MAX_SINGLE_BATCH_CHILD_DELETES = 499

    internal suspend fun <T> deleteReviewAtomically(
      enumerateFindingsChunks: suspend () -> List<T>,
      enumerateNarrativeChunks: suspend () -> List<T>,
      commitDeleteRefs: suspend (List<T>) -> Unit,
      markPendingDeletion: suspend () -> Unit = {},
      onError: (Exception) -> Unit = {}
    ): Result<Unit> = try {
      val findingRefs = enumerateFindingsChunks()
      val narrativeRefs = enumerateNarrativeChunks()
      val allChildren = findingRefs + narrativeRefs
      if (allChildren.size > MAX_SINGLE_BATCH_CHILD_DELETES) {
        markPendingDeletion()
      }
      commitDeleteRefs(allChildren)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      onError(e)
      Result.failure(e)
    }

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
     * - Supports unassessed `INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT` (0 scores) without inventing 50/100.
     * - Never truncates `structuredFindingsJson` or long narrative sections: overflows are stored
     *   losslessly in `findings_chunks` and `narrative_chunks` with SHA-256 verification.
     * - Never fabricates a default publication year ("2026") when year is absent/unverified.
     */
    internal fun buildFirestorePayload(
      review: PaperReview,
      uid: String,
      createdAtValue: Any,
      updatedAtValue: Any,
      packagedLedger: ReviewFindingCodec.LosslessLedgerPackage = ReviewFindingCodec.packageForCloud(review.structuredFindingsJson),
      packagedNarratives: NarrativeSectionCodec.LosslessNarrativePackage = NarrativeSectionCodec.packageOverflowNarratives(review)
    ): Map<String, Any> {
      val resolvedMode = review.resolvedReviewMode()
      val rubric = review.toRubric()
      val isUnassessed = !rubric.isAssessed
      val normalizedVerdict = if (isUnassessed) {
        "INSUFFICIENT_EVIDENCE_FOR_ASSESSMENT"
      } else {
        normalizeVerdict(review.verdict, resolvedMode)
      }
      val authoritativeComposite = if (isUnassessed) 0L else rubric.overallCredibilityScore.toLong().coerceIn(0L, 100L)
      val basePayload = mutableMapOf<String, Any>(
        "userId" to uid,
        "title" to review.title.trim().take(500).ifEmpty { "Untitled Manuscript" },
        "authors" to review.authors.trim().take(500).ifEmpty { "Authors not verified" },
        "publicationYear" to review.publicationYear.trim().take(40),
        "venueOrCitation" to review.venueOrCitation.trim().take(500),
        "isEmpirical" to review.isEmpirical,
        "credibilityScore" to authoritativeComposite,
        "verdict" to normalizedVerdict,
        // Structured 6-axis rubric (0 if unassessed readiness block, otherwise strictly 1..10)
        "methodologyScore" to if (isUnassessed) 0 else rubric.methodology.coerceIn(1, 10),
        "significanceScore" to if (isUnassessed) 0 else rubric.significance.coerceIn(1, 10),
        "clarityScore" to if (isUnassessed) 0 else rubric.clarity.coerceIn(1, 10),
        "originalityScore" to if (isUnassessed) 0 else rubric.originality.coerceIn(1, 10),
        "mathRigorScore" to if (isUnassessed) 0 else rubric.mathematicalRigor.coerceIn(1, 10),
        "reproducibilityScore" to if (isUnassessed) 0 else rubric.reproducibility.coerceIn(1, 10),
        "methodologyJustification" to review.methodologyJustification.trim().take(4000),
        "significanceJustification" to review.significanceJustification.trim().take(4000),
        "clarityJustification" to review.clarityJustification.trim().take(4000),
        "originalityJustification" to review.originalityJustification.trim().take(4000),
        "mathRigorJustification" to review.mathRigorJustification.trim().take(4000),
        "reproducibilityJustification" to review.reproducibilityJustification.trim().take(4000),
        // Separated confidence, mode, provenance & lossless chunked ledger/narrative metadata
        "refereeConfidence" to review.refereeConfidence.coerceIn(1, 5),
        "extractionConfidence" to review.extractionConfidence.coerceIn(1, 5),
        "reviewMode" to resolvedMode.name,
        "coverageDisclosure" to review.coverageDisclosure.trim().take(2000),
        "validationStatus" to normalizeValidationStatus(review.validationStatus),
        "structuredFindingsJson" to packagedLedger.inlineJson,
        "schemaVersion" to review.schemaVersion.coerceAtLeast(5),
        "findingsChunkCount" to packagedLedger.chunks.size,
        "findingsChecksum" to packagedLedger.checksumSha256,
        "narrativeChunkCount" to packagedNarratives.chunks.size,
        "narrativeChecksum" to packagedNarratives.checksumSha256,
        // Narrative dossier sections (full un-truncated copies preserved in narrative_chunks when exceeding inline limits)
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
      if (review.refereeDecision.isNotBlank()) {
        basePayload["refereeDecision"] = review.refereeDecision.trim().take(64)
      }
      if (review.analysisManifestJson.isNotBlank()) {
        basePayload["analysisManifestJson"] = review.analysisManifestJson.trim().take(8000)
      }
      if (review.abstentionSummaryJson.isNotBlank()) {
        basePayload["abstentionSummaryJson"] = review.abstentionSummaryJson.trim().take(8000)
      }
      if (review.readinessSummaryJson.isNotBlank()) {
        basePayload["readinessSummaryJson"] = review.readinessSummaryJson.trim().take(6000)
      }
      if (review.auditLogJson.isNotBlank()) {
        basePayload["auditLogJson"] = review.auditLogJson.trim().take(24000)
      }
      if (review.refereeCoverageJson.isNotBlank()) {
        basePayload["refereeCoverageJson"] = review.refereeCoverageJson.trim().take(4000)
      }
      return basePayload
    }
  }
}
