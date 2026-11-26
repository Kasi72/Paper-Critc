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
   * Field-level Room update for [bookmarked] with durable dirty-flag tracking so concurrent
   * personalNotes edits or subsequent stale cloud snapshots NEVER clobber newer local bookmarks.
   */
  suspend fun updateBookmark(
    review: PaperReview,
    bookmarked: Boolean,
    ownerScope: String = normalizeOwnerScope(review.userId),
    markDirty: Boolean = true,
    updatedAtEpochMs: Long = System.currentTimeMillis()
  ) {
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
      sqlDao.upsertReview(
        SqlReviewCacheEntity.fromPaperReview(
          review = review.copy(bookmarked = bookmarked),
          ownerScope = scope,
          searchGroundingReport = existing?.searchGroundingReport.orEmpty(),
          bookmarkDirty = markDirty,
          personalNotesDirty = existing?.personalNotesDirty ?: false,
          localAnnotationUpdatedAtMs = updatedAtEpochMs
        )
      )
    }
  }

  /**
   * Field-level Room update for [personalNotes] with durable dirty-flag tracking so concurrent
   * bookmark toggles or subsequent stale cloud snapshots NEVER clobber newer local notes.
   */
  suspend fun updatePersonalNotes(
    review: PaperReview,
    personalNotes: String,
    ownerScope: String = normalizeOwnerScope(review.userId),
    markDirty: Boolean = true,
    updatedAtEpochMs: Long = System.currentTimeMillis()
  ) {
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
      sqlDao.upsertReview(
        SqlReviewCacheEntity.fromPaperReview(
          review = review.copy(personalNotes = personalNotes),
          ownerScope = scope,
          searchGroundingReport = existing?.searchGroundingReport.orEmpty(),
          bookmarkDirty = existing?.bookmarkDirty ?: false,
          personalNotesDirty = markDirty,
          localAnnotationUpdatedAtMs = updatedAtEpochMs
        )
      )
    }
  }

  suspend fun clearBookmarkDirty(reviewId: String, ownerScope: String) {
    sqlDao.clearBookmarkDirtyForOwner(reviewId, normalizeOwnerScope(ownerScope))
  }

  suspend fun clearPersonalNotesDirty(reviewId: String, ownerScope: String) {
    sqlDao.clearPersonalNotesDirtyForOwner(reviewId, normalizeOwnerScope(ownerScope))
  }

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

    // 1. Protect intact local structured findings if incoming cloud snapshot failed chunk hydration
    var safeReview = if (
      existing != null &&
      existing.structuredFindingsJson.isNotBlank() &&
      ((incoming.findingsChunkCount > 0 && incoming.structuredFindingsJson.isBlank()) ||
        incoming.ledgerLoadState != LedgerLoadState.COMPLETE) &&
      (incoming.findingsChecksum.isBlank() ||
        existing.findingsChecksum.isBlank() ||
        incoming.findingsChecksum == existing.findingsChecksum)
    ) {
      incoming.copy(
        structuredFindingsJson = existing.structuredFindingsJson,
        ledgerLoadState = LedgerLoadState.COMPLETE
      )
    } else {
      incoming
    }

    // 2. Protect intact local narrative sections if incoming cloud snapshot has shorter truncated fields
    if (existing != null && incoming.narrativeChunkCount > 0 && incoming.ledgerLoadState != LedgerLoadState.COMPLETE) {
      safeReview = safeReview.copy(
        redTeamCritique = if (existing.redTeamCritique.length > safeReview.redTeamCritique.length) existing.redTeamCritique else safeReview.redTeamCritique,
        blueTeamVerification = if (existing.blueTeamVerification.length > safeReview.blueTeamVerification.length) existing.blueTeamVerification else safeReview.blueTeamVerification,
        mathAuditReport = if (existing.mathAuditReport.length > safeReview.mathAuditReport.length) existing.mathAuditReport else safeReview.mathAuditReport,
        codeAuditReport = if (existing.codeAuditReport.length > safeReview.codeAuditReport.length) existing.codeAuditReport else safeReview.codeAuditReport,
        editorNote = if (existing.editorNote.length > safeReview.editorNote.length) existing.editorNote else safeReview.editorNote,
        formattedDossier = if (existing.formattedDossier.length > safeReview.formattedDossier.length) existing.formattedDossier else safeReview.formattedDossier
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

    if (keepLocalBookmark && existing != null) {
      safeReview = safeReview.copy(bookmarked = existing.bookmarked)
    }
    if (keepLocalNotes && existing != null) {
      safeReview = safeReview.copy(personalNotes = existing.personalNotes)
    }

    return SqlReviewCacheEntity.fromPaperReview(
      review = safeReview,
      ownerScope = ownerScope,
      searchGroundingReport = preservedGrounding,
      bookmarkDirty = if (keepLocalBookmark) existing?.bookmarkDirty ?: false else false,
      personalNotesDirty = if (keepLocalNotes) existing?.personalNotesDirty ?: false else false,
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
