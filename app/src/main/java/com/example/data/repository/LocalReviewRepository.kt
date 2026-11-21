package com.example.data.repository

import com.example.data.local.PaperCriticSqlDao
import com.example.data.local.SqlApiProviderEntity
import com.example.data.local.SqlChatMessageEntity
import com.example.data.local.SqlReviewCacheEntity
import com.example.data.model.AiApiProviderEntry
import com.example.data.model.PaperReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed offline repository abstracting local SQLite caching for paper reviews,
 * multi-turn Gemini chat threads, and user API provider configurations.
 */
class LocalReviewRepository(private val sqlDao: PaperCriticSqlDao) {

  val cachedReviews: Flow<List<PaperReview>> = sqlDao
    .observeAllSqlReviews()
    .map { entities -> entities.map { it.toPaperReview() } }

  val cachedReviewCount: Flow<Int> = sqlDao.observeSqlReviewCount()

  val totalChatMessageCount: Flow<Int> = sqlDao.observeTotalChatMessageCount()

  val apiProviders: Flow<List<SqlApiProviderEntity>> = sqlDao.observeApiProviders()

  suspend fun cacheReviewOffline(review: PaperReview, searchGroundingReport: String = "") {
    val existing = sqlDao.getReviewById(review.id)
    val preservedGrounding = searchGroundingReport.ifBlank { existing?.searchGroundingReport.orEmpty() }
    sqlDao.upsertReview(SqlReviewCacheEntity.fromPaperReview(review, preservedGrounding))
  }

  suspend fun cacheReviewsOffline(reviews: List<PaperReview>) {
    if (reviews.isEmpty()) return
    val entities = reviews.map { review ->
      val existing = sqlDao.getReviewById(review.id)
      SqlReviewCacheEntity.fromPaperReview(
        review = review,
        searchGroundingReport = existing?.searchGroundingReport.orEmpty()
      )
    }
    sqlDao.upsertReviews(entities)
  }

  suspend fun removeCachedReview(reviewId: String) {
    sqlDao.deleteReviewById(reviewId)
    sqlDao.clearChatHistory(reviewId)
  }

  fun observeChatMessages(reviewKey: String): Flow<List<SqlChatMessageEntity>> =
    sqlDao.observeChatMessages(reviewKey)

  suspend fun insertChatMessage(message: SqlChatMessageEntity): Long =
    sqlDao.insertChatMessage(message)

  suspend fun migrateChatHistory(oldReviewId: String, newReviewId: String) {
    if (oldReviewId.isNotBlank() && newReviewId.isNotBlank() && oldReviewId != newReviewId) {
      sqlDao.migrateChatHistory(oldReviewId, newReviewId)
    }
  }

  suspend fun clearChatHistory(reviewKey: String) {
    sqlDao.clearChatHistory(reviewKey)
  }

  suspend fun upsertApiProvider(entry: AiApiProviderEntry) {
    sqlDao.upsertApiProvider(SqlApiProviderEntity.fromDomain(entry))
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

  suspend fun deleteApiProvider(providerId: String) {
    sqlDao.deleteApiProvider(providerId)
  }
}
