package com.example.data.repository

import com.example.data.local.ANONYMOUS_OWNER_SCOPE
import com.example.data.local.PaperCriticSqlDao
import com.example.data.local.SqlApiProviderEntity
import com.example.data.local.SqlChatMessageEntity
import com.example.data.local.SqlReviewCacheEntity
import com.example.data.local.normalizeOwnerScope
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.PaperReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed offline repository strictly partitioned by [ownerScope] (authenticated Google UID
 * or [ANONYMOUS_OWNER_SCOPE]) so reviews, chat histories, and encrypted API keys never leak across
 * accounts on a shared device.
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

  suspend fun cacheReviewOffline(
    review: PaperReview,
    ownerScope: String = normalizeOwnerScope(review.userId),
    searchGroundingReport: String = ""
  ) {
    val scope = normalizeOwnerScope(ownerScope)
    val existing = sqlDao.getReviewByIdForOwner(review.id, scope)
    val preservedGrounding = searchGroundingReport.ifBlank { existing?.searchGroundingReport.orEmpty() }
    val safeReview = preserveIntactLocalChunkedFindings(review, existing)
    sqlDao.upsertReview(
      SqlReviewCacheEntity.fromPaperReview(
        review = safeReview,
        ownerScope = scope,
        searchGroundingReport = preservedGrounding
      )
    )
  }

  suspend fun cacheReviewsOffline(
    reviews: List<PaperReview>,
    ownerScope: String
  ) {
    if (reviews.isEmpty()) return
    val scope = normalizeOwnerScope(ownerScope)
    val entities = reviews.map { review ->
      val existing = sqlDao.getReviewByIdForOwner(review.id, scope)
      val safeReview = preserveIntactLocalChunkedFindings(review, existing)
      SqlReviewCacheEntity.fromPaperReview(
        review = safeReview,
        ownerScope = scope,
        searchGroundingReport = existing?.searchGroundingReport.orEmpty()
      )
    }
    sqlDao.upsertReviews(entities)
  }

  /**
   * Field-level Room update for [bookmarked] so concurrent local personalNotes edits
   * can never be clobbered by a full-row REPLACE from a stale snapshot.
   */
  suspend fun updateBookmark(
    review: PaperReview,
    bookmarked: Boolean,
    ownerScope: String = normalizeOwnerScope(review.userId)
  ) {
    val scope = normalizeOwnerScope(ownerScope)
    val updatedRows = sqlDao.updateBookmarkForOwner(
      reviewId = review.id,
      ownerScope = scope,
      bookmarked = bookmarked,
      updatedAtEpochMs = System.currentTimeMillis()
    )
    if (updatedRows == 0) {
      cacheReviewOffline(review.copy(bookmarked = bookmarked), ownerScope = scope)
    }
  }

  /**
   * Field-level Room update for [personalNotes] so concurrent local bookmark toggles
   * can never be clobbered by a full-row REPLACE from a stale snapshot.
   */
  suspend fun updatePersonalNotes(
    review: PaperReview,
    personalNotes: String,
    ownerScope: String = normalizeOwnerScope(review.userId)
  ) {
    val scope = normalizeOwnerScope(ownerScope)
    val updatedRows = sqlDao.updatePersonalNotesForOwner(
      reviewId = review.id,
      ownerScope = scope,
      personalNotes = personalNotes,
      updatedAtEpochMs = System.currentTimeMillis()
    )
    if (updatedRows == 0) {
      cacheReviewOffline(review.copy(personalNotes = personalNotes), ownerScope = scope)
    }
  }

  private fun preserveIntactLocalChunkedFindings(
    incoming: PaperReview,
    existing: SqlReviewCacheEntity?
  ): PaperReview {
    if (incoming.findingsChunkCount > 0 &&
      incoming.structuredFindingsJson.isBlank() &&
      existing != null &&
      existing.structuredFindingsJson.isNotBlank() &&
      (incoming.findingsChecksum.isBlank() ||
        existing.findingsChecksum.isBlank() ||
        incoming.findingsChecksum == existing.findingsChecksum)
    ) {
      return incoming.copy(structuredFindingsJson = existing.structuredFindingsJson)
    }
    return incoming
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
