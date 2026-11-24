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
import com.example.data.model.PaperReview
import com.example.data.model.ReviewMode
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sql_review_cache")
data class SqlReviewCacheEntity(
  @PrimaryKey val id: String,
  val userId: String,
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
      updatedAt = safeUpdatedAt
    )
  }

  companion object {
    fun fromPaperReview(review: PaperReview, searchGroundingReport: String = ""): SqlReviewCacheEntity {
      val rubric = review.toRubric()
      val stableId = review.id.ifBlank { "sql_${review.title.hashCode()}" }
      return SqlReviewCacheEntity(
        id = stableId,
        userId = review.userId,
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
  val role: String, // "user" or "model"
  val personaId: String,
  val modelTierId: String,
  val content: String,
  val searchGroundedQueries: String = "",
  val searchGroundedSources: String = "",
  val timestampEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "sql_api_providers")
data class SqlApiProviderEntity(
  @PrimaryKey val id: String,
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
    fun fromDomain(entry: com.example.data.model.AiApiProviderEntry): SqlApiProviderEntity =
      SqlApiProviderEntity(
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
  @Query("SELECT * FROM sql_review_cache ORDER BY updatedAtEpochMs DESC")
  fun observeAllSqlReviews(): Flow<List<SqlReviewCacheEntity>>

  @Query("SELECT * FROM sql_review_cache WHERE id = :reviewId LIMIT 1")
  suspend fun getReviewById(reviewId: String): SqlReviewCacheEntity?

  @Query("SELECT COUNT(*) FROM sql_review_cache")
  fun observeSqlReviewCount(): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertReview(entity: SqlReviewCacheEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertReviews(entities: List<SqlReviewCacheEntity>)

  @Query("DELETE FROM sql_review_cache WHERE id = :reviewId")
  suspend fun deleteReviewById(reviewId: String)

  @Query("SELECT * FROM sql_chat_messages WHERE reviewId = :reviewId ORDER BY timestampEpochMs ASC")
  fun observeChatMessages(reviewId: String): Flow<List<SqlChatMessageEntity>>

  @Query("SELECT COUNT(*) FROM sql_chat_messages")
  fun observeTotalChatMessageCount(): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChatMessage(message: SqlChatMessageEntity): Long

  @Query("UPDATE sql_chat_messages SET reviewId = :newReviewId WHERE reviewId = :oldReviewId")
  suspend fun migrateChatHistory(oldReviewId: String, newReviewId: String)

  @Query("DELETE FROM sql_chat_messages WHERE reviewId = :reviewId")
  suspend fun clearChatHistory(reviewId: String)

  @Query("SELECT * FROM sql_api_providers ORDER BY isFreeTierPreset DESC, serviceName ASC")
  fun observeApiProviders(): Flow<List<SqlApiProviderEntity>>

  @Query("SELECT * FROM sql_api_providers")
  suspend fun getAllApiProvidersOnce(): List<SqlApiProviderEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertApiProvider(provider: SqlApiProviderEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertApiProviders(providers: List<SqlApiProviderEntity>)

  @Query("DELETE FROM sql_api_providers WHERE id = :providerId")
  suspend fun deleteApiProvider(providerId: String)
}

@Database(
  entities = [
    SqlReviewCacheEntity::class,
    SqlChatMessageEntity::class,
    SqlApiProviderEntity::class
  ],
  version = 5,
  exportSchema = false
)
abstract class PaperCriticSqlDatabase : RoomDatabase() {
  abstract fun sqlDao(): PaperCriticSqlDao

  companion object {
    @Volatile
    private var INSTANCE: PaperCriticSqlDatabase? = null

    /**
     * Explicit non-destructive migration preserving all existing user reviews, chat messages,
     * and provider settings when upgrading from Room schema v3 to v4.
     * Legacy reviews without a structured evidence ledger MUST NOT be marked VERIFIED.
     */
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

    /**
     * Non-destructive v4 -> v5 data hygiene migration: relabels any legacy migrated rows that
     * were assigned 'VERIFIED' despite having no structured findings ledger to 'LEGACY_UNVALIDATED'.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "UPDATE sql_review_cache SET validationStatus = 'LEGACY_UNVALIDATED' " +
            "WHERE validationStatus = 'VERIFIED' AND (structuredFindingsJson IS NULL OR TRIM(structuredFindingsJson) = '' OR TRIM(structuredFindingsJson) = '[]')"
        )
      }
    }

    fun getDatabase(context: Context): PaperCriticSqlDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          PaperCriticSqlDatabase::class.java,
          "paper_critic_sql_database"
        )
          .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
