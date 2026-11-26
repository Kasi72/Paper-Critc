package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.LedgerLoadState
import com.example.data.model.PaperReview
import com.example.data.model.ReviewMode
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow

const val ANONYMOUS_OWNER_SCOPE: String = "__local_anonymous__"

fun normalizeOwnerScope(userId: String?): String =
  userId?.trim()?.takeIf { it.isNotBlank() } ?: ANONYMOUS_OWNER_SCOPE

@Entity(
  tableName = "sql_review_cache",
  primaryKeys = ["ownerScope", "id"]
)
data class SqlReviewCacheEntity(
  val id: String,
  val userId: String,
  val ownerScope: String = ANONYMOUS_OWNER_SCOPE,
  val title: String,
  val authors: String,
  val publicationYear: String,
  val venueOrCitation: String,
  val isEmpirical: Boolean,
  val credibilityScore: Long,
  val verdict: String,
  val methodologyScore: Int,
  val clarityScore: Int,
  val significanceScore: Int,
  val originalityScore: Int,
  val mathRigorScore: Int,
  val reproducibilityScore: Int,
  val methodologyJustification: String = "",
  val clarityJustification: String = "",
  val significanceJustification: String = "",
  val originalityJustification: String = "",
  val mathRigorJustification: String = "",
  val reproducibilityJustification: String = "",
  val refereeConfidence: Int = 4,
  val extractionConfidence: Int = 4,
  val reviewMode: String = ReviewMode.POST_PUBLICATION_APPRAISAL.name,
  val coverageDisclosure: String = "",
  val validationStatus: String = "LEGACY_UNVALIDATED",
  val structuredFindingsJson: String = "",
  val schemaVersion: Int = 1,
  val findingsChunkCount: Int = 0,
  val findingsChecksum: String = "",
  val narrativeChunkCount: Int = 0,
  val narrativeChecksum: String = "",
  val ledgerLoadState: String = LedgerLoadState.COMPLETE.name,
  val abstractSummary: String,
  val researchQuestion: String,
  val keyMethodology: String,
  val coreContributions: String,
  val redTeamCritique: String,
  val blueTeamVerification: String,
  val mathAuditReport: String,
  val codeAuditReport: String,
  val editorNote: String,
  val copyeditSuggestions: String,
  val reviewerQuestions: String,
  val formattedDossier: String,
  val bookmarked: Boolean,
  val personalNotes: String,
  val bookmarkDirty: Boolean = false,
  val personalNotesDirty: Boolean = false,
  val localAnnotationUpdatedAtMs: Long = 0L,
  val searchGroundingReport: String = "",
  val createdAtSeconds: Long = System.currentTimeMillis() / 1000L,
  val createdAtNanos: Int = 0,
  val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
  fun toPaperReview(): PaperReview {
    val safeCreatedAt = if (createdAtSeconds > 0L) {
      Timestamp(createdAtSeconds, createdAtNanos)
    } else {
      null
    }
    val safeUpdatedAt = Timestamp(updatedAtEpochMs / 1000L, ((updatedAtEpochMs % 1000L) * 1_000_000L).toInt())
    return PaperReview(
      id = id,
      userId = userId,
      title = title,
      authors = authors,
      publicationYear = publicationYear,
      venueOrCitation = venueOrCitation,
      isEmpirical = isEmpirical,
      credibilityScore = credibilityScore,
      verdict = verdict,
      methodologyScore = methodologyScore,
      significanceScore = significanceScore,
      clarityScore = clarityScore,
      originalityScore = originalityScore,
      mathRigorScore = mathRigorScore,
      reproducibilityScore = reproducibilityScore,
      methodologyJustification = methodologyJustification,
      significanceJustification = significanceJustification,
      clarityJustification = clarityJustification,
      originalityJustification = originalityJustification,
      mathRigorJustification = mathRigorJustification,
      reproducibilityJustification = reproducibilityJustification,
      refereeConfidence = refereeConfidence,
      extractionConfidence = extractionConfidence,
      reviewMode = reviewMode,
      coverageDisclosure = coverageDisclosure,
      validationStatus = validationStatus,
      structuredFindingsJson = structuredFindingsJson,
      schemaVersion = schemaVersion,
      findingsChunkCount = findingsChunkCount,
      findingsChecksum = findingsChecksum,
      narrativeChunkCount = narrativeChunkCount,
      narrativeChecksum = narrativeChecksum,
      abstractSummary = abstractSummary,
      researchQuestion = researchQuestion,
      keyMethodology = keyMethodology,
      coreContributions = coreContributions,
      redTeamCritique = redTeamCritique,
      blueTeamVerification = blueTeamVerification,
      mathAuditReport = mathAuditReport,
      codeAuditReport = codeAuditReport,
      editorNote = editorNote,
      copyeditSuggestions = copyeditSuggestions,
      reviewerQuestions = reviewerQuestions,
      formattedDossier = formattedDossier,
      bookmarked = bookmarked,
      personalNotes = personalNotes,
      createdAt = safeCreatedAt,
      updatedAt = safeUpdatedAt,
      ledgerLoadState = LedgerLoadState.fromNameOrDefault(ledgerLoadState)
    )
  }

  companion object {
    fun fromPaperReview(
      review: PaperReview,
      ownerScope: String = normalizeOwnerScope(review.userId),
      searchGroundingReport: String = "",
      bookmarkDirty: Boolean = false,
      personalNotesDirty: Boolean = false,
      localAnnotationUpdatedAtMs: Long = 0L
    ): SqlReviewCacheEntity {
      val rubric = review.toRubric()
      val stableId = review.id.ifBlank { "sql_${java.util.UUID.randomUUID()}" }
      val normalizedScope = normalizeOwnerScope(ownerScope)
      return SqlReviewCacheEntity(
        id = stableId,
        userId = review.userId,
        ownerScope = normalizedScope,
        title = review.title,
        authors = review.authors,
        publicationYear = review.publicationYear,
        venueOrCitation = review.venueOrCitation,
        isEmpirical = review.isEmpirical,
        credibilityScore = rubric.overallCredibilityScore.toLong(),
        verdict = review.verdict,
        methodologyScore = rubric.methodology,
        clarityScore = rubric.clarity,
        significanceScore = rubric.significance,
        originalityScore = rubric.originality,
        mathRigorScore = rubric.mathematicalRigor,
        reproducibilityScore = rubric.reproducibility,
        methodologyJustification = rubric.methodologyJustification,
        clarityJustification = rubric.clarityJustification,
        significanceJustification = rubric.significanceJustification,
        originalityJustification = rubric.originalityJustification,
        mathRigorJustification = rubric.mathematicalRigorJustification,
        reproducibilityJustification = rubric.reproducibilityJustification,
        refereeConfidence = rubric.refereeConfidence,
        extractionConfidence = rubric.extractionConfidence,
        reviewMode = review.reviewMode.ifBlank { ReviewMode.POST_PUBLICATION_APPRAISAL.name },
        coverageDisclosure = review.coverageDisclosure,
        validationStatus = review.validationStatus.ifBlank { "LEGACY_UNVALIDATED" },
        structuredFindingsJson = review.structuredFindingsJson,
        schemaVersion = review.schemaVersion,
        findingsChunkCount = review.findingsChunkCount,
        findingsChecksum = review.findingsChecksum,
        narrativeChunkCount = review.narrativeChunkCount,
        narrativeChecksum = review.narrativeChecksum,
        ledgerLoadState = review.ledgerLoadState.name,
        abstractSummary = review.abstractSummary,
        researchQuestion = review.researchQuestion,
        keyMethodology = review.keyMethodology,
        coreContributions = review.coreContributions,
        redTeamCritique = review.redTeamCritique,
        blueTeamVerification = review.blueTeamVerification,
        mathAuditReport = review.mathAuditReport,
        codeAuditReport = review.codeAuditReport,
        editorNote = review.editorNote,
        copyeditSuggestions = review.copyeditSuggestions,
        reviewerQuestions = review.reviewerQuestions,
        formattedDossier = review.formattedDossier,
        bookmarked = review.bookmarked,
        personalNotes = review.personalNotes,
        bookmarkDirty = bookmarkDirty,
        personalNotesDirty = personalNotesDirty,
        localAnnotationUpdatedAtMs = localAnnotationUpdatedAtMs,
        searchGroundingReport = searchGroundingReport,
        createdAtSeconds = review.createdAt?.seconds ?: 0L,
        createdAtNanos = review.createdAt?.nanoseconds ?: 0,
        updatedAtEpochMs = review.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
      )
    }
  }
}

@Entity(tableName = "sql_chat_messages")
data class SqlChatMessageEntity(
  @PrimaryKey(autoGenerate = true) val messageId: Long = 0,
  val reviewId: String,
  val ownerScope: String = ANONYMOUS_OWNER_SCOPE,
  val role: String, // "user" or "model"
  val personaId: String,
  val modelTierId: String,
  val content: String,
  val searchGroundedQueries: String = "",
  val searchGroundedSources: String = "",
  val timestampEpochMs: Long = System.currentTimeMillis()
)

@Entity(
  tableName = "sql_api_providers",
  primaryKeys = ["ownerScope", "id"]
)
data class SqlApiProviderEntity(
  val ownerScope: String = ANONYMOUS_OWNER_SCOPE,
  val id: String,
  val serviceName: String,
  val protocolTypeName: String,
  val baseUrl: String,
  val modelId: String,
  val apiKey: String, // Encrypted via ApiKeyVault (Android Keystore AES-256-GCM)
  val isFreeTierPreset: Boolean,
  val enabled: Boolean,
  val notes: String,
  val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
  fun toDomain(): com.example.data.model.AiApiProviderEntry {
    val protocol = com.example.data.model.ApiProtocolType.entries.find {
      it.name == protocolTypeName
    } ?: com.example.data.model.ApiProtocolType.OPENAI_COMPATIBLE_REST
    return com.example.data.model.AiApiProviderEntry(
      id = id,
      serviceName = serviceName,
      protocolType = protocol,
      baseUrl = baseUrl,
      modelId = modelId,
      apiKey = ApiKeyVault.decrypt(apiKey),
      isFreeTierPreset = isFreeTierPreset,
      enabled = enabled,
      notes = notes
    )
  }

  companion object {
    fun fromDomain(
      entry: com.example.data.model.AiApiProviderEntry,
      ownerScope: String = ANONYMOUS_OWNER_SCOPE
    ): SqlApiProviderEntity =
      SqlApiProviderEntity(
        ownerScope = normalizeOwnerScope(ownerScope),
        id = entry.id,
        serviceName = entry.serviceName,
        protocolTypeName = entry.protocolType.name,
        baseUrl = entry.baseUrl,
        modelId = entry.modelId,
        apiKey = ApiKeyVault.encrypt(entry.apiKey),
        isFreeTierPreset = entry.isFreeTierPreset,
        enabled = entry.enabled,
        notes = entry.notes
      )
  }
}

@Dao
interface PaperCriticSqlDao {
  @Query("SELECT * FROM sql_review_cache WHERE ownerScope = :ownerScope ORDER BY updatedAtEpochMs DESC")
  fun observeSqlReviewsForOwner(ownerScope: String): Flow<List<SqlReviewCacheEntity>>

  @Query("SELECT * FROM sql_review_cache WHERE ownerScope = :ownerScope ORDER BY updatedAtEpochMs DESC")
  suspend fun getSqlReviewsOnceForOwner(ownerScope: String): List<SqlReviewCacheEntity>

  @Query("SELECT * FROM sql_review_cache WHERE id = :reviewId AND ownerScope = :ownerScope LIMIT 1")
  suspend fun getReviewByIdForOwner(reviewId: String, ownerScope: String): SqlReviewCacheEntity?

  @Query("SELECT COUNT(*) FROM sql_review_cache WHERE ownerScope = :ownerScope")
  fun observeSqlReviewCountForOwner(ownerScope: String): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertReview(entity: SqlReviewCacheEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertReviews(entities: List<SqlReviewCacheEntity>)

  @Query(
    "UPDATE sql_review_cache SET bookmarked = :bookmarked, bookmarkDirty = :dirty, " +
      "localAnnotationUpdatedAtMs = :updatedAtEpochMs, updatedAtEpochMs = :updatedAtEpochMs " +
      "WHERE id = :reviewId AND ownerScope = :ownerScope"
  )
  suspend fun updateBookmarkForOwner(
    reviewId: String,
    ownerScope: String,
    bookmarked: Boolean,
    dirty: Boolean = true,
    updatedAtEpochMs: Long = System.currentTimeMillis()
  ): Int

  @Query(
    "UPDATE sql_review_cache SET personalNotes = :personalNotes, personalNotesDirty = :dirty, " +
      "localAnnotationUpdatedAtMs = :updatedAtEpochMs, updatedAtEpochMs = :updatedAtEpochMs " +
      "WHERE id = :reviewId AND ownerScope = :ownerScope"
  )
  suspend fun updatePersonalNotesForOwner(
    reviewId: String,
    ownerScope: String,
    personalNotes: String,
    dirty: Boolean = true,
    updatedAtEpochMs: Long = System.currentTimeMillis()
  ): Int

  @Query(
    "UPDATE sql_review_cache SET bookmarkDirty = 0 WHERE id = :reviewId AND ownerScope = :ownerScope"
  )
  suspend fun clearBookmarkDirtyForOwner(reviewId: String, ownerScope: String)

  @Query(
    "UPDATE sql_review_cache SET personalNotesDirty = 0 WHERE id = :reviewId AND ownerScope = :ownerScope"
  )
  suspend fun clearPersonalNotesDirtyForOwner(reviewId: String, ownerScope: String)

  @Query(
    "SELECT * FROM sql_review_cache WHERE ownerScope = :ownerScope AND (bookmarkDirty = 1 OR personalNotesDirty = 1)"
  )
  suspend fun getDirtyAnnotationReviewsForOwner(ownerScope: String): List<SqlReviewCacheEntity>

  @Query(
    "UPDATE sql_review_cache SET searchGroundingReport = :report WHERE id = :reviewId AND ownerScope = :ownerScope"
  )
  suspend fun updateSearchGroundingReportForOwner(reviewId: String, ownerScope: String, report: String): Int

  @Query("DELETE FROM sql_review_cache WHERE id = :reviewId AND ownerScope = :ownerScope")
  suspend fun deleteReviewByIdForOwner(reviewId: String, ownerScope: String)

  @Query(
    "UPDATE sql_review_cache SET ownerScope = :targetOwnerScope, userId = :targetOwnerScope " +
      "WHERE id = :reviewId AND ownerScope = '$ANONYMOUS_OWNER_SCOPE'"
  )
  suspend fun adoptAnonymousReviewToOwner(reviewId: String, targetOwnerScope: String): Int

  @Query(
    "UPDATE sql_chat_messages SET ownerScope = :targetOwnerScope " +
      "WHERE reviewId = :reviewId AND ownerScope = '$ANONYMOUS_OWNER_SCOPE'"
  )
  suspend fun adoptAnonymousChatHistoryToOwner(reviewId: String, targetOwnerScope: String): Int

  @Query(
    "SELECT * FROM sql_chat_messages WHERE reviewId = :reviewId AND ownerScope = :ownerScope " +
      "ORDER BY timestampEpochMs ASC"
  )
  fun observeChatMessagesForOwner(reviewId: String, ownerScope: String): Flow<List<SqlChatMessageEntity>>

  @Query("SELECT COUNT(*) FROM sql_chat_messages WHERE ownerScope = :ownerScope")
  fun observeTotalChatMessageCountForOwner(ownerScope: String): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChatMessage(message: SqlChatMessageEntity): Long

  @Query(
    "UPDATE sql_chat_messages SET reviewId = :newReviewId " +
      "WHERE reviewId = :oldReviewId AND ownerScope = :ownerScope"
  )
  suspend fun migrateChatHistoryForOwner(oldReviewId: String, newReviewId: String, ownerScope: String)

  @Query("DELETE FROM sql_chat_messages WHERE reviewId = :reviewId AND ownerScope = :ownerScope")
  suspend fun clearChatHistoryForOwner(reviewId: String, ownerScope: String)

  @Query(
    "SELECT * FROM sql_api_providers WHERE ownerScope = :ownerScope " +
      "ORDER BY isFreeTierPreset DESC, serviceName ASC"
  )
  fun observeApiProvidersForOwner(ownerScope: String): Flow<List<SqlApiProviderEntity>>

  @Query("SELECT * FROM sql_api_providers WHERE ownerScope = :ownerScope")
  suspend fun getApiProvidersOnceForOwner(ownerScope: String): List<SqlApiProviderEntity>

  @Query("SELECT * FROM sql_api_providers")
  suspend fun getAllApiProvidersOnce(): List<SqlApiProviderEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertApiProvider(provider: SqlApiProviderEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertApiProviders(providers: List<SqlApiProviderEntity>)

  @Query("DELETE FROM sql_api_providers WHERE id = :providerId AND ownerScope = :ownerScope")
  suspend fun deleteApiProviderForOwner(providerId: String, ownerScope: String)

  @Query(
    "SELECT COUNT(*) FROM sql_api_providers " +
      "WHERE ownerScope = '$ANONYMOUS_OWNER_SCOPE' AND protocolTypeName != 'FIREBASE_AI_LOGIC_KEYLESS' AND TRIM(apiKey) != ''"
  )
  fun observeAnonymousCustomProviderCount(): Flow<Int>

  @Query(
    "SELECT * FROM sql_api_providers " +
      "WHERE ownerScope = '$ANONYMOUS_OWNER_SCOPE' AND protocolTypeName != 'FIREBASE_AI_LOGIC_KEYLESS' AND TRIM(apiKey) != ''"
  )
  suspend fun getAnonymousCustomProvidersOnce(): List<SqlApiProviderEntity>
}

@Database(
  entities = [
    SqlReviewCacheEntity::class,
    SqlChatMessageEntity::class,
    SqlApiProviderEntity::class
  ],
  version = 8,
  exportSchema = false
)
abstract class PaperCriticSqlDatabase : RoomDatabase() {
  abstract fun sqlDao(): PaperCriticSqlDao

  companion object {
    @Volatile
    private var INSTANCE: PaperCriticSqlDatabase? = null

    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN methodologyJustification TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN clarityJustification TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN significanceJustification TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN originalityJustification TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN mathRigorJustification TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN reproducibilityJustification TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN refereeConfidence INTEGER NOT NULL DEFAULT 4")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN extractionConfidence INTEGER NOT NULL DEFAULT 4")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN reviewMode TEXT NOT NULL DEFAULT 'POST_PUBLICATION_APPRAISAL'")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN coverageDisclosure TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN validationStatus TEXT NOT NULL DEFAULT 'LEGACY_UNVALIDATED'")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN structuredFindingsJson TEXT NOT NULL DEFAULT ''")
      }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "UPDATE sql_review_cache SET validationStatus = 'LEGACY_UNVALIDATED' " +
            "WHERE validationStatus = 'VERIFIED' AND (structuredFindingsJson IS NULL OR TRIM(structuredFindingsJson) = '' OR TRIM(structuredFindingsJson) = '[]')"
        )
      }
    }

    /**
     * Non-destructive v5 -> v6 multi-account isolation & lossless ledger metadata migration:
     * - Scopes reviews, chat messages, and API provider credentials strictly by [ownerScope].
     * - Existing reviews with a non-empty userId remain bound to that exact UID; blank userId rows
     *   remain quarantined in [ANONYMOUS_OWNER_SCOPE] until explicitly adopted by a signed-in user.
     * - Rebuilds sql_api_providers with composite primary key (ownerScope, id) so accounts never
     *   share or overwrite each other's credentials.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN ownerScope TEXT NOT NULL DEFAULT '$ANONYMOUS_OWNER_SCOPE'")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 4")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN findingsChunkCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN findingsChecksum TEXT NOT NULL DEFAULT ''")
        db.execSQL(
          "UPDATE sql_review_cache SET ownerScope = TRIM(userId) " +
            "WHERE userId IS NOT NULL AND TRIM(userId) != ''"
        )

        db.execSQL("ALTER TABLE sql_chat_messages ADD COLUMN ownerScope TEXT NOT NULL DEFAULT '$ANONYMOUS_OWNER_SCOPE'")
        db.execSQL(
          "UPDATE sql_chat_messages SET ownerScope = COALESCE(" +
            "(SELECT r.ownerScope FROM sql_review_cache r WHERE r.id = sql_chat_messages.reviewId LIMIT 1), " +
            "'$ANONYMOUS_OWNER_SCOPE')"
        )

        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS sql_api_providers_v6 (
            ownerScope TEXT NOT NULL,
            id TEXT NOT NULL,
            serviceName TEXT NOT NULL,
            protocolTypeName TEXT NOT NULL,
            baseUrl TEXT NOT NULL,
            modelId TEXT NOT NULL,
            apiKey TEXT NOT NULL,
            isFreeTierPreset INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            notes TEXT NOT NULL,
            updatedAtEpochMs INTEGER NOT NULL,
            PRIMARY KEY(ownerScope, id)
          )
          """.trimIndent()
        )
        db.execSQL(
          """
          INSERT INTO sql_api_providers_v6 (
            ownerScope, id, serviceName, protocolTypeName, baseUrl, modelId,
            apiKey, isFreeTierPreset, enabled, notes, updatedAtEpochMs
          )
          SELECT
            '$ANONYMOUS_OWNER_SCOPE', id, serviceName, protocolTypeName, baseUrl, modelId,
            apiKey, isFreeTierPreset, enabled, notes, updatedAtEpochMs
          FROM sql_api_providers
          """.trimIndent()
        )
        db.execSQL("DROP TABLE sql_api_providers")
        db.execSQL("ALTER TABLE sql_api_providers_v6 RENAME TO sql_api_providers")
      }
    }

    /**
     * Non-destructive v6 -> v7 hardening: rebuilds sql_review_cache with composite
     * PRIMARY KEY(ownerScope, id) so identical review IDs across different accounts on a shared
     * device can never collide or overwrite each other via OnConflictStrategy.REPLACE.
     */
    val MIGRATION_6_7 = object : Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS sql_review_cache_v7 (
            id TEXT NOT NULL,
            userId TEXT NOT NULL,
            ownerScope TEXT NOT NULL,
            title TEXT NOT NULL,
            authors TEXT NOT NULL,
            publicationYear TEXT NOT NULL,
            venueOrCitation TEXT NOT NULL,
            isEmpirical INTEGER NOT NULL,
            credibilityScore INTEGER NOT NULL,
            verdict TEXT NOT NULL,
            methodologyScore INTEGER NOT NULL,
            clarityScore INTEGER NOT NULL,
            significanceScore INTEGER NOT NULL,
            originalityScore INTEGER NOT NULL,
            mathRigorScore INTEGER NOT NULL,
            reproducibilityScore INTEGER NOT NULL,
            methodologyJustification TEXT NOT NULL,
            clarityJustification TEXT NOT NULL,
            significanceJustification TEXT NOT NULL,
            originalityJustification TEXT NOT NULL,
            mathRigorJustification TEXT NOT NULL,
            reproducibilityJustification TEXT NOT NULL,
            refereeConfidence INTEGER NOT NULL,
            extractionConfidence INTEGER NOT NULL,
            reviewMode TEXT NOT NULL,
            coverageDisclosure TEXT NOT NULL,
            validationStatus TEXT NOT NULL,
            structuredFindingsJson TEXT NOT NULL,
            schemaVersion INTEGER NOT NULL,
            findingsChunkCount INTEGER NOT NULL,
            findingsChecksum TEXT NOT NULL,
            abstractSummary TEXT NOT NULL,
            researchQuestion TEXT NOT NULL,
            keyMethodology TEXT NOT NULL,
            coreContributions TEXT NOT NULL,
            redTeamCritique TEXT NOT NULL,
            blueTeamVerification TEXT NOT NULL,
            mathAuditReport TEXT NOT NULL,
            codeAuditReport TEXT NOT NULL,
            editorNote TEXT NOT NULL,
            copyeditSuggestions TEXT NOT NULL,
            reviewerQuestions TEXT NOT NULL,
            formattedDossier TEXT NOT NULL,
            bookmarked INTEGER NOT NULL,
            personalNotes TEXT NOT NULL,
            searchGroundingReport TEXT NOT NULL,
            createdAtSeconds INTEGER NOT NULL,
            createdAtNanos INTEGER NOT NULL,
            updatedAtEpochMs INTEGER NOT NULL,
            PRIMARY KEY(ownerScope, id)
          )
          """.trimIndent()
        )
        db.execSQL(
          """
          INSERT OR REPLACE INTO sql_review_cache_v7 (
            id, userId, ownerScope, title, authors, publicationYear, venueOrCitation,
            isEmpirical, credibilityScore, verdict,
            methodologyScore, clarityScore, significanceScore, originalityScore, mathRigorScore, reproducibilityScore,
            methodologyJustification, clarityJustification, significanceJustification,
            originalityJustification, mathRigorJustification, reproducibilityJustification,
            refereeConfidence, extractionConfidence, reviewMode, coverageDisclosure, validationStatus,
            structuredFindingsJson, schemaVersion, findingsChunkCount, findingsChecksum,
            abstractSummary, researchQuestion, keyMethodology, coreContributions,
            redTeamCritique, blueTeamVerification, mathAuditReport, codeAuditReport,
            editorNote, copyeditSuggestions, reviewerQuestions, formattedDossier,
            bookmarked, personalNotes, searchGroundingReport,
            createdAtSeconds, createdAtNanos, updatedAtEpochMs
          )
          SELECT
            id, userId, ownerScope, title, authors, publicationYear, venueOrCitation,
            isEmpirical, credibilityScore, verdict,
            methodologyScore, clarityScore, significanceScore, originalityScore, mathRigorScore, reproducibilityScore,
            methodologyJustification, clarityJustification, significanceJustification,
            originalityJustification, mathRigorJustification, reproducibilityJustification,
            refereeConfidence, extractionConfidence, reviewMode, coverageDisclosure, validationStatus,
            structuredFindingsJson, schemaVersion, findingsChunkCount, findingsChecksum,
            abstractSummary, researchQuestion, keyMethodology, coreContributions,
            redTeamCritique, blueTeamVerification, mathAuditReport, codeAuditReport,
            editorNote, copyeditSuggestions, reviewerQuestions, formattedDossier,
            bookmarked, personalNotes, searchGroundingReport,
            createdAtSeconds, createdAtNanos, updatedAtEpochMs
          FROM sql_review_cache
          """.trimIndent()
        )
        db.execSQL("DROP TABLE sql_review_cache")
        db.execSQL("ALTER TABLE sql_review_cache_v7 RENAME TO sql_review_cache")
      }
    }

    /**
     * Non-destructive v7 -> v8 upgrade:
     * - Adds narrative chunking metadata + explicit [ledgerLoadState] column.
     * - Adds durable local dirty-field outbox columns ([bookmarkDirty], [personalNotesDirty],
     *   [localAnnotationUpdatedAtMs]) so cloud refreshes never overwrite newer offline edits.
     */
    val MIGRATION_7_8 = object : Migration(7, 8) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN narrativeChunkCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN narrativeChecksum TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN ledgerLoadState TEXT NOT NULL DEFAULT 'COMPLETE'")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN bookmarkDirty INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN personalNotesDirty INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sql_review_cache ADD COLUMN localAnnotationUpdatedAtMs INTEGER NOT NULL DEFAULT 0")
      }
    }

    fun getDatabase(context: Context): PaperCriticSqlDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          PaperCriticSqlDatabase::class.java,
          "paper_critic_sql_database"
        )
          .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
