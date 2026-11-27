package com.example.data.repository

import com.example.data.local.ANONYMOUS_OWNER_SCOPE
import com.example.data.local.PaperCriticSqlDao
import com.example.data.local.SqlApiProviderEntity
import com.example.data.local.SqlChatMessageEntity
import com.example.data.local.SqlReviewCacheEntity
import com.example.data.local.normalizeOwnerScope
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.LedgerLoadState
import com.example.data.model.PaperReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed offline repository strictly partitioned by [ownerScope] (authenticated Google UID
 * or [ANONYMOUS_OWNER_SCOPE]) so reviews, chat histories, and encrypted API keys never leak across
 * accounts on a shared device.
 *
 * Includes conflict-safe local annotation protection ([bookmarkDirty], [personalNotesDirty],
 * [localAnnotationUpdatedAtMs]) so stale cloud refreshes or background search-grounding callbacks
 * NEVER overwrite newer offline notes or bookmarks.
 */
class LocalReviewRepository(private val sqlDao: PaperCriticSqlDao) {

  fun observeCachedReviews(ownerScope: String): Flow<List<PaperReview>> {
    val scope = normalizeOwnerScope(ownerScope)
    return sqlDao
      .observeSqlReviewsForOwner(scope)
      .map { entities -> entities.map { it.toPaperReview() } }
  }

  fun observeAnonymousReviews(): Flow<List<PaperReview>> =
    sqlDao
      .observeSqlReviewsForOwner(ANONYMOUS_OWNER_SCOPE)
      .map { entities -> entities.map { it.toPaperReview() } }

  suspend fun getCachedReviewsOnce(ownerScope: String): List<PaperReview> =
    sqlDao.getSqlReviewsOnceForOwner(normalizeOwnerScope(ownerScope)).map { it.toPaperReview() }

  fun observeCachedReviewCount(ownerScope: String): Flow<Int> =
    sqlDao.observeSqlReviewCountForOwner(normalizeOwnerScope(ownerScope))

  fun observeTotalChatMessageCount(ownerScope: String): Flow<Int> =
    sqlDao.observeTotalChatMessageCountForOwner(normalizeOwnerScope(ownerScope))

  fun observeApiProviders(ownerScope: String): Flow<List<SqlApiProviderEntity>> =
    sqlDao.observeApiProvidersForOwner(normalizeOwnerScope(ownerScope))

  fun observeAnonymousCustomProviderCount(): Flow<Int> =
    sqlDao.observeAnonymousCustomProviderCount()

  suspend fun cacheReviewOffline(
    review: PaperReview,
    ownerScope: String = normalizeOwnerScope(review.userId),
    searchGroundingReport: String = ""
  ) {
    val scope = normalizeOwnerScope(ownerScope)
    val existing = sqlDao.getReviewByIdForOwner(review.id, scope)
    val merged = mergeIncomingWithLocalState(
      incoming = review,
      existing = existing,
      ownerScope = scope,
      searchGroundingReport = searchGroundingReport
    )
    sqlDao.upsertReview(merged)
  }

  suspend fun cacheReviewsOffline(
    reviews: List<PaperReview>,
    ownerScope: String
  ) {
    if (reviews.isEmpty()) return
    val scope = normalizeOwnerScope(ownerScope)
    val entities = reviews.map { review ->
      val existing = sqlDao.getReviewByIdForOwner(review.id, scope)
      mergeIncomingWithLocalState(
        incoming = review,
        existing = existing,
        ownerScope = scope,
        searchGroundingReport = ""
      )
    }
    sqlDao.upsertReviews(entities)
  }

  /**
   * Field-level Room update for [bookmarked] with monotonic revision tracking so concurrent
   * personalNotes edits, stale cloud snapshots, or older in-flight cloud acknowledgments NEVER
   * erase a newer local bookmark edit.
   *
   * @return the new monotonic [SqlReviewCacheEntity.bookmarkRevision] persisted in Room.
   */
  suspend fun updateBookmark(
    review: PaperReview,
    bookmarked: Boolean,
    ownerScope: String = normalizeOwnerScope(review.userId),
    markDirty: Boolean = true,
    updatedAtEpochMs: Long = System.currentTimeMillis()
  ): Long {
    val scope = normalizeOwnerScope(ownerScope)
    val updatedRows = sqlDao.updateBookmarkForOwner(
      reviewId = review.id,
      ownerScope = scope,
      bookmarked = bookmarked,
      dirty = markDirty,
      updatedAtEpochMs = updatedAtEpochMs
    )
    if (updatedRows == 0) {
      val existing = sqlDao.getReviewByIdForOwner(review.id, scope)
      val nextRevision = (existing?.bookmarkRevision ?: 0L) + 1L
      sqlDao.upsertReview(
        SqlReviewCacheEntity.fromPaperReview(
          review = review.copy(bookmarked = bookmarked),
          ownerScope = scope,
          searchGroundingReport = existing?.searchGroundingReport.orEmpty(),
          bookmarkDirty = markDirty,
          personalNotesDirty = existing?.personalNotesDirty ?: false,
          bookmarkRevision = nextRevision,
          personalNotesRevision = existing?.personalNotesRevision ?: 0L,
          localAnnotationUpdatedAtMs = updatedAtEpochMs
        )
      )
      return nextRevision
    }
    return sqlDao.getReviewByIdForOwner(review.id, scope)?.bookmarkRevision ?: 1L
  }

  /**
   * Field-level Room update for [personalNotes] with monotonic revision tracking so concurrent
   * bookmark toggles, stale cloud snapshots, or older in-flight cloud acknowledgments NEVER
   * erase a newer local note edit.
   *
   * @return the new monotonic [SqlReviewCacheEntity.personalNotesRevision] persisted in Room.
   */
  suspend fun updatePersonalNotes(
    review: PaperReview,
    personalNotes: String,
    ownerScope: String = normalizeOwnerScope(review.userId),
    markDirty: Boolean = true,
    updatedAtEpochMs: Long = System.currentTimeMillis()
  ): Long {
    val scope = normalizeOwnerScope(ownerScope)
    val updatedRows = sqlDao.updatePersonalNotesForOwner(
      reviewId = review.id,
      ownerScope = scope,
      personalNotes = personalNotes,
      dirty = markDirty,
      updatedAtEpochMs = updatedAtEpochMs
    )
    if (updatedRows == 0) {
      val existing = sqlDao.getReviewByIdForOwner(review.id, scope)
      val nextRevision = (existing?.personalNotesRevision ?: 0L) + 1L
      sqlDao.upsertReview(
        SqlReviewCacheEntity.fromPaperReview(
          review = review.copy(personalNotes = personalNotes),
          ownerScope = scope,
          searchGroundingReport = existing?.searchGroundingReport.orEmpty(),
          bookmarkDirty = existing?.bookmarkDirty ?: false,
          personalNotesDirty = markDirty,
          bookmarkRevision = existing?.bookmarkRevision ?: 0L,
          personalNotesRevision = nextRevision,
          localAnnotationUpdatedAtMs = updatedAtEpochMs
        )
      )
      return nextRevision
    }
    return sqlDao.getReviewByIdForOwner(review.id, scope)?.personalNotesRevision ?: 1L
  }

  /**
   * Clears [SqlReviewCacheEntity.bookmarkDirty] ONLY if the current local `bookmarkRevision`
   * still equals [acknowledgedRevision]. If the user edited the bookmark again while the cloud
   * write was in flight, the dirty flag remains `true` so the newer edit is still synchronized.
   */
  suspend fun clearBookmarkDirtyIfRevisionMatches(
    reviewId: String,
    ownerScope: String,
    acknowledgedRevision: Long
  ): Boolean = sqlDao.clearBookmarkDirtyIfRevisionMatches(
    reviewId = reviewId,
    ownerScope = normalizeOwnerScope(ownerScope),
    acknowledgedRevision = acknowledgedRevision
  ) > 0

  /**
   * Clears [SqlReviewCacheEntity.personalNotesDirty] ONLY if the current local
   * `personalNotesRevision` still equals [acknowledgedRevision].
   */
  suspend fun clearPersonalNotesDirtyIfRevisionMatches(
    reviewId: String,
    ownerScope: String,
    acknowledgedRevision: Long
  ): Boolean = sqlDao.clearPersonalNotesDirtyIfRevisionMatches(
    reviewId = reviewId,
    ownerScope = normalizeOwnerScope(ownerScope),
    acknowledgedRevision = acknowledgedRevision
  ) > 0

  suspend fun getDirtyAnnotationReviews(ownerScope: String): List<SqlReviewCacheEntity> =
    sqlDao.getDirtyAnnotationReviewsForOwner(normalizeOwnerScope(ownerScope))

  /**
   * Field-level update for Stage 03b search grounding report so a long-running web grounding
   * callback NEVER replaces a newer local bookmark, note, or hydrated ledger row.
   */
  suspend fun updateSearchGroundingReport(
    review: PaperReview,
    report: String,
    ownerScope: String = normalizeOwnerScope(review.userId)
  ) {
    val scope = normalizeOwnerScope(ownerScope)
    val updated = sqlDao.updateSearchGroundingReportForOwner(review.id, scope, report)
    if (updated == 0) {
      cacheReviewOffline(review, ownerScope = scope, searchGroundingReport = report)
    }
  }

  private fun mergeIncomingWithLocalState(
    incoming: PaperReview,
    existing: SqlReviewCacheEntity?,
    ownerScope: String,
    searchGroundingReport: String
  ): SqlReviewCacheEntity {
    val preservedGrounding = searchGroundingReport.ifBlank { existing?.searchGroundingReport.orEmpty() }
    val incomingUpdatedMs = incoming.updatedAt?.toDate()?.time ?: 0L

    var safeReview = incoming
    val existingIsComplete = existing != null && existing.ledgerLoadState == LedgerLoadState.COMPLETE.name
    val isTransientDegraded = incoming.ledgerLoadState == LedgerLoadState.TEMPORARILY_UNAVAILABLE ||
      (incoming.findingsChunkCount > 0 && incoming.structuredFindingsJson.isBlank() && incoming.ledgerLoadState != LedgerLoadState.CORRUPT)

    // 1. Checksum-verified lossless recovery of structured findings when cloud chunk hydration failed transiently
    //    (NEVER mask a cloud-verified CORRUPT checksum failure — keep CORRUPT visible!)
    val findingsNeedRestore = incoming.findingsChunkCount > 0 &&
      (incoming.structuredFindingsJson.isBlank() || incoming.ledgerLoadState == LedgerLoadState.TEMPORARILY_UNAVAILABLE)
    val findingsChecksumCompatible = existing != null &&
      existing.structuredFindingsJson.isNotBlank() &&
      (incoming.findingsChecksum.isBlank() ||
        existing.findingsChecksum.isBlank() ||
        incoming.findingsChecksum == existing.findingsChecksum)
    val findingsRestored = existingIsComplete && isTransientDegraded && findingsNeedRestore && findingsChecksumCompatible
    if (findingsRestored && existing != null) {
      safeReview = safeReview.copy(
        structuredFindingsJson = existing.structuredFindingsJson
      )
    }

    // 2. Checksum-verified lossless recovery of ALL 12 narrative fields (never length heuristics!)
    //    when narrative chunk hydration failed transiently for the same narrative checksum/version.
    val narrativeNeedsRestore = incoming.narrativeChunkCount > 0 &&
      incoming.ledgerLoadState == LedgerLoadState.TEMPORARILY_UNAVAILABLE
    val narrativeChecksumCompatible = existing != null &&
      (incoming.narrativeChecksum.isBlank() ||
        existing.narrativeChecksum.isBlank() ||
        incoming.narrativeChecksum == existing.narrativeChecksum)
    val narrativesRestored = existingIsComplete && narrativeNeedsRestore && narrativeChecksumCompatible
    if (narrativesRestored && existing != null) {
      safeReview = safeReview.copy(
        abstractSummary = existing.abstractSummary,
        researchQuestion = existing.researchQuestion,
        keyMethodology = existing.keyMethodology,
        coreContributions = existing.coreContributions,
        redTeamCritique = existing.redTeamCritique,
        blueTeamVerification = existing.blueTeamVerification,
        mathAuditReport = existing.mathAuditReport,
        codeAuditReport = existing.codeAuditReport,
        editorNote = existing.editorNote,
        copyeditSuggestions = existing.copyeditSuggestions,
        reviewerQuestions = existing.reviewerQuestions,
        formattedDossier = existing.formattedDossier
      )
    }

    // Restore COMPLETE load state & verified validationStatus ONLY if every degraded component was losslessly recovered
    val findingsSatisfied = !findingsNeedRestore || findingsRestored
    val narrativesSatisfied = !narrativeNeedsRestore || narrativesRestored
    if (existing != null &&
      existingIsComplete &&
      isTransientDegraded &&
      (findingsRestored || narrativesRestored) &&
      findingsSatisfied &&
      narrativesSatisfied
    ) {
      safeReview = safeReview.copy(
        validationStatus = existing.validationStatus,
        ledgerLoadState = LedgerLoadState.COMPLETE
      )
    }

    // 3. Protect locally modified (dirty) or newer offline bookmark & personalNotes from stale cloud snapshots
    val keepLocalBookmark = existing != null && (
      existing.bookmarkDirty ||
        (existing.localAnnotationUpdatedAtMs > 0L && incomingUpdatedMs in 1L until existing.localAnnotationUpdatedAtMs)
      )
    val keepLocalNotes = existing != null && (
      existing.personalNotesDirty ||
        (existing.localAnnotationUpdatedAtMs > 0L && incomingUpdatedMs in 1L until existing.localAnnotationUpdatedAtMs)
      )

    if (keepLocalBookmark) {
      safeReview = safeReview.copy(bookmarked = existing!!.bookmarked)
    }
    if (keepLocalNotes) {
      safeReview = safeReview.copy(personalNotes = existing!!.personalNotes)
    }

    return SqlReviewCacheEntity.fromPaperReview(
      review = safeReview,
      ownerScope = ownerScope,
      searchGroundingReport = preservedGrounding,
      bookmarkDirty = if (keepLocalBookmark) existing?.bookmarkDirty ?: false else false,
      personalNotesDirty = if (keepLocalNotes) existing?.personalNotesDirty ?: false else false,
      bookmarkRevision = existing?.bookmarkRevision ?: 0L,
      personalNotesRevision = existing?.personalNotesRevision ?: 0L,
      localAnnotationUpdatedAtMs = if (keepLocalBookmark || keepLocalNotes) {
        existing?.localAnnotationUpdatedAtMs ?: 0L
      } else {
        0L
      }
    )
  }

  suspend fun removeCachedReview(reviewId: String, ownerScope: String) {
    val scope = normalizeOwnerScope(ownerScope)
    sqlDao.deleteReviewByIdForOwner(reviewId, scope)
    sqlDao.clearChatHistoryForOwner(reviewId, scope)
  }

  /**
   * Explicitly adopts user-selected local anonymous reports into [targetUserId]'s local scope.
   * Anonymous drafts are NEVER silently adopted or uploaded without explicit user consent.
   */
  suspend fun adoptAnonymousReviewsToAccount(
    reviewIds: Collection<String>,
    targetUserId: String
  ): Int {
    val cleanTarget = targetUserId.trim()
    if (cleanTarget.isBlank() || cleanTarget == ANONYMOUS_OWNER_SCOPE) return 0
    var adopted = 0
    for (id in reviewIds) {
      if (id.isBlank()) continue
      val updated = sqlDao.adoptAnonymousReviewToOwner(id, cleanTarget)
      if (updated > 0) {
        sqlDao.adoptAnonymousChatHistoryToOwner(id, cleanTarget)
        adopted += updated
      }
    }
    return adopted
  }

  /**
   * P1-07: One-time explicit credential recovery flow allowing a signed-in user to move legacy
   * anonymous/pre-migration API provider entries into their authenticated account scope after
   * explicit confirmation.
   */
  suspend fun adoptAnonymousApiProvidersToAccount(targetUserId: String): Int {
    val cleanTarget = targetUserId.trim()
    if (cleanTarget.isBlank() || cleanTarget == ANONYMOUS_OWNER_SCOPE) return 0
    val legacyProviders = sqlDao.getAnonymousCustomProvidersOnce()
    if (legacyProviders.isEmpty()) return 0
    var moved = 0
    for (provider in legacyProviders) {
      sqlDao.upsertApiProvider(
        provider.copy(
          ownerScope = cleanTarget,
          updatedAtEpochMs = System.currentTimeMillis()
        )
      )
      sqlDao.deleteApiProviderForOwner(provider.id, ANONYMOUS_OWNER_SCOPE)
      moved++
    }
    return moved
  }

  fun observeChatMessages(reviewKey: String, ownerScope: String): Flow<List<SqlChatMessageEntity>> =
    sqlDao.observeChatMessagesForOwner(reviewKey, normalizeOwnerScope(ownerScope))

  suspend fun insertChatMessage(message: SqlChatMessageEntity): Long =
    sqlDao.insertChatMessage(message.copy(ownerScope = normalizeOwnerScope(message.ownerScope)))

  suspend fun migrateChatHistory(oldReviewId: String, newReviewId: String, ownerScope: String) {
    if (oldReviewId.isNotBlank() && newReviewId.isNotBlank() && oldReviewId != newReviewId) {
      sqlDao.migrateChatHistoryForOwner(oldReviewId, newReviewId, normalizeOwnerScope(ownerScope))
    }
  }

  suspend fun clearChatHistory(reviewKey: String, ownerScope: String) {
    sqlDao.clearChatHistoryForOwner(reviewKey, normalizeOwnerScope(ownerScope))
  }

  suspend fun upsertApiProvider(entry: AiApiProviderEntry, ownerScope: String) {
    sqlDao.upsertApiProvider(SqlApiProviderEntity.fromDomain(entry, ownerScope))
  }

  /**
   * Immediately migrates any legacy unencrypted API key rows in Room SQLite to AES-256-GCM
   * ciphertext when Android Keystore is available. Fails closed if Keystore is unavailable
   * (returns migratedCount = -1 so UI can alert the user).
   */
  suspend fun migratePlaintextApiKeysIfNeeded(): Int {
    val rows = sqlDao.getAllApiProvidersOnce()
    val legacyRows = rows.filter { it.apiKey.isNotBlank() && !com.example.data.local.ApiKeyVault.isEncrypted(it.apiKey) }
    if (legacyRows.isEmpty()) return 0
    if (!com.example.data.local.ApiKeyVault.isHardwareVaultAvailable()) {
      return -1
    }
    var migrated = 0
    for (row in legacyRows) {
      val encrypted = com.example.data.local.ApiKeyVault.encrypt(row.apiKey)
      sqlDao.upsertApiProvider(row.copy(apiKey = encrypted, updatedAtEpochMs = System.currentTimeMillis()))
      migrated++
    }
    return migrated
  }

  suspend fun deleteApiProvider(providerId: String, ownerScope: String) {
    sqlDao.deleteApiProviderForOwner(providerId, normalizeOwnerScope(ownerScope))
  }
}
