package com.example.referee

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PaperCriticSqlDatabase
import com.example.data.model.FindingStatus
import com.example.data.model.PaperReview
import com.example.data.model.ReviewFinding
import com.example.data.model.ReviewFindingCodec
import com.example.data.repository.LocalReviewRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 11 Room Migration (v9 -> v10), Concurrent Dirty-Revision Race, and Lossless Chunk Recovery Tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RoomMigrationAndPersistenceTest {

  private lateinit var context: Context
  private var roomDb: PaperCriticSqlDatabase? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  @After
  fun tearDown() {
    roomDb?.close()
    context.deleteDatabase("migration_v9_v10_test.db")
  }

  @Test
  fun migration9To10_preservesAllExistingRowsRevisionsAndSupportsNewRefereeColumns() = runBlocking {
    val dbName = "migration_v9_v10_test.db"
    context.deleteDatabase(dbName)

    // Create authentic v9 SQLite tables directly via SQLiteOpenHelper and seed pre-migration rows
    val v9Helper = object : SQLiteOpenHelper(context, dbName, null, 9) {
      override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE sql_review_cache (
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
            narrativeChunkCount INTEGER NOT NULL,
            narrativeChecksum TEXT NOT NULL,
            ledgerLoadState TEXT NOT NULL,
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
            bookmarkDirty INTEGER NOT NULL,
            personalNotesDirty INTEGER NOT NULL,
            bookmarkRevision INTEGER NOT NULL,
            personalNotesRevision INTEGER NOT NULL,
            localAnnotationUpdatedAtMs INTEGER NOT NULL,
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
          CREATE TABLE sql_chat_messages (
            messageId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            reviewId TEXT NOT NULL,
            ownerScope TEXT NOT NULL,
            role TEXT NOT NULL,
            personaId TEXT NOT NULL,
            modelTierId TEXT NOT NULL,
            content TEXT NOT NULL,
            searchGroundedQueries TEXT NOT NULL,
            searchGroundedSources TEXT NOT NULL,
            timestampEpochMs INTEGER NOT NULL
          )
          """.trimIndent()
        )
        db.execSQL(
          """
          CREATE TABLE sql_api_providers (
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
          INSERT INTO sql_review_cache VALUES (
            'rev-1', 'uid-alice', 'uid-alice', 'Clinical Trial Alpha', 'Smith J', '2025', 'Lancet',
            1, 82, 'SOUND_WITH_CAVEATS', 8, 8, 8, 8, 8, 8,
            'm', 'c', 's', 'o', 'r', 'p',
            4, 5, 'POST_PUBLICATION_APPRAISAL', '2/2 pages', 'ADJUDICATED_WITH_CAVEATS', '[]',
            5, 0, '', 0, '', 'COMPLETE',
            'abs', 'rq', 'km', 'cc', 'rt', 'bt', 'ma', 'ca', 'ed', 'cp', 'rq2', 'dossier',
            1, 'Important follow-up note', 1, 1, 3, 7, 1700000000000, 'ground',
            1700000, 0, 1700000000000
          )
          """.trimIndent()
        )
      }

      override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    v9Helper.writableDatabase.close()
    v9Helper.close()

    // Open Room v11 with MIGRATION_9_10 + MIGRATION_10_11 and verify existing v9 row + revisions + new v10/v11 columns
    roomDb = Room.databaseBuilder(context, PaperCriticSqlDatabase::class.java, dbName)
      .addMigrations(
        PaperCriticSqlDatabase.MIGRATION_3_4,
        PaperCriticSqlDatabase.MIGRATION_4_5,
        PaperCriticSqlDatabase.MIGRATION_5_6,
        PaperCriticSqlDatabase.MIGRATION_6_7,
        PaperCriticSqlDatabase.MIGRATION_7_8,
        PaperCriticSqlDatabase.MIGRATION_8_9,
        PaperCriticSqlDatabase.MIGRATION_9_10,
        PaperCriticSqlDatabase.MIGRATION_10_11
      )
      .allowMainThreadQueries()
      .build()

    val dao = roomDb!!.sqlDao()
    val migrated = dao.getReviewByIdForOwner("rev-1", "uid-alice")
    assertNotNull(migrated)
    assertEquals("Clinical Trial Alpha", migrated!!.title)
    assertEquals("Important follow-up note", migrated.personalNotes)
    assertEquals(3L, migrated.bookmarkRevision)
    assertEquals(7L, migrated.personalNotesRevision)
    assertEquals("", migrated.refereeDecision)
    assertEquals("", migrated.analysisManifestJson)
    assertEquals("", migrated.auditCommitment)

    // Round-trip new v10/v11 referee metadata + auditCommitment fields via LocalReviewRepository
    val repo = LocalReviewRepository(dao)
    val enriched = migrated.toPaperReview().copy(
      refereeDecision = "VERIFIED_WITH_MINOR_CONCERNS",
      analysisManifestJson = "{\"analysisId\":\"a1\"}",
      abstentionSummaryJson = "{\"shouldAbstain\":false}",
      readinessSummaryJson = "{\"readiness\":\"READY\"}",
      auditLogJson = "[{\"seq\":0}]",
      auditCommitment = "c".repeat(64),
      refereeCoverageJson = "{\"pagesExtracted\":2}"
    )
    repo.cacheReviewOffline(enriched, ownerScope = "uid-alice")
    val reloaded = dao.getReviewByIdForOwner("rev-1", "uid-alice")!!.toPaperReview()
    assertEquals("VERIFIED_WITH_MINOR_CONCERNS", reloaded.refereeDecision)
    assertEquals("{\"analysisId\":\"a1\"}", reloaded.analysisManifestJson)
    assertEquals("c".repeat(64), reloaded.auditCommitment)
    assertEquals("{\"pagesExtracted\":2}", reloaded.refereeCoverageJson)

    // Verify concurrent dirty-revision ack race: rev 7 ack does NOT clear rev 8 note edit
    val rev8 = repo.updatePersonalNotes(reloaded, "Note B after rev 7", ownerScope = "uid-alice")
    assertEquals(8L, rev8)
    assertFalse(repo.clearPersonalNotesDirtyIfRevisionMatches("rev-1", "uid-alice", acknowledgedRevision = 7L))
    assertTrue(dao.getReviewByIdForOwner("rev-1", "uid-alice")!!.personalNotesDirty)
    assertTrue(repo.clearPersonalNotesDirtyIfRevisionMatches("rev-1", "uid-alice", acknowledgedRevision = rev8))
    assertFalse(dao.getReviewByIdForOwner("rev-1", "uid-alice")!!.personalNotesDirty)
  }

  @Test
  fun chunkSplittingAndCorruptionDetection_rejectsTamperedChecksumOrMissingChunk() {
    val findings = (1..40).map { i ->
      ReviewFinding(
        id = "F-$i",
        title = "Finding $i",
        allegation = "Detailed methodological note number $i with quantitative reference.",
        severity = "MINOR",
        status = FindingStatus.VERIFIED
      )
    }
    val json = ReviewFindingCodec.encode(findings)
    val chunks = ReviewFindingCodec.splitIntoLosslessChunks(json, maxChunkChars = 1024)
    assertTrue(chunks.size >= 2)
    val expectedChecksum = chunks.first().checksum

    val restored = ReviewFindingCodec.reassembleAndVerifyChunks(
      chunks = chunks.reversed(),
      expectedChunkCount = chunks.size,
      expectedChecksum = expectedChecksum
    )
    assertEquals(json, restored)

    // Missing chunk -> rejected
    assertNull(
      ReviewFindingCodec.reassembleAndVerifyChunks(
        chunks = chunks.dropLast(1),
        expectedChunkCount = chunks.size,
        expectedChecksum = expectedChecksum
      )
    )

    // Corrupted payload -> SHA-256 mismatch -> rejected
    val corrupted = chunks.toMutableList()
    corrupted[0] = corrupted[0].copy(payload = corrupted[0].payload.replaceFirst("Finding", "Xinding"))
    assertNull(
      ReviewFindingCodec.reassembleAndVerifyChunks(
        chunks = corrupted,
        expectedChunkCount = chunks.size,
        expectedChecksum = expectedChecksum
      )
    )
  }
}
