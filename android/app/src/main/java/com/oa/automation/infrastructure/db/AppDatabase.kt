package com.oa.automation.infrastructure.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MeetingEntity::class,
        TranscriptEntity::class,
        ReportEntity::class,
        MeetingAttachmentEntity::class,
        RecordingMarkerEntity::class,
        ScheduledMeetingEntity::class,
        JourneyEntity::class,
        JourneyStageEntity::class,
        StageDraftVersionEntity::class,
        JourneyEditionEntity::class,
        PublishedPostEntity::class,
        CommunitySyncOutboxEntity::class,
        PublishedPostMediaEntity::class
    ],
    version = 15,
    exportSchema = false
)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meetingDao(): MeetingDao
    abstract fun reportDao(): ReportDao
    abstract fun scheduledMeetingDao(): ScheduledMeetingDao
    abstract fun journeyDao(): JourneyDao
    abstract fun stageDraftDao(): StageDraftDao
    abstract fun journeyEditionDao(): JourneyEditionDao
    abstract fun publishedPostDao(): PublishedPostDao
    abstract fun communitySyncOutboxDao(): CommunitySyncOutboxDao
    abstract fun publishedPostMediaDao(): PublishedPostMediaDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reports ADD COLUMN rawContent TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reports ADD COLUMN templateName TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meeting_attachments (
                        id TEXT NOT NULL,
                        meetingId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_meeting_attachments_meetingId ON meeting_attachments(meetingId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scheduled_meetings (
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        scheduledAt INTEGER NOT NULL,
                        reminderMinutes INTEGER NOT NULL,
                        templateName TEXT,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scheduled_meetings_scheduledAt " +
                        "ON scheduled_meetings(scheduledAt)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN accuracyMeters REAL")
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN locationCapturedAt INTEGER")
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN locationSource TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journeys (
                        id TEXT NOT NULL,
                        meetingId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        status TEXT NOT NULL,
                        currentStageId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        pausedAt INTEGER,
                        completedAt INTEGER,
                        PRIMARY KEY(id),
                        FOREIGN KEY(meetingId) REFERENCES meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_journeys_meetingId " +
                        "ON journeys(meetingId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_journeys_status ON journeys(status)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journey_stages (
                        id TEXT NOT NULL,
                        journeyId TEXT NOT NULL,
                        sequenceNumber INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        savedAt INTEGER,
                        PRIMARY KEY(id),
                        FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_journey_stages_journeyId " +
                        "ON journey_stages(journeyId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_journey_stages_journeyId_sequenceNumber " +
                        "ON journey_stages(journeyId, sequenceNumber)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_journey_stages_status " +
                        "ON journey_stages(status)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN journeyStageId TEXT")
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN journeyStageId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transcripts_journeyStageId " +
                        "ON transcripts(journeyStageId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_meeting_attachments_journeyStageId " +
                        "ON meeting_attachments(journeyStageId)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stage_draft_versions (
                        id TEXT NOT NULL,
                        stageId TEXT NOT NULL,
                        versionNumber INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        status TEXT NOT NULL,
                        evidenceTranscriptCount INTEGER NOT NULL,
                        evidenceAttachmentCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        confirmedAt INTEGER,
                        PRIMARY KEY(id),
                        FOREIGN KEY(stageId) REFERENCES journey_stages(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_stage_draft_versions_stageId " +
                        "ON stage_draft_versions(stageId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_stage_draft_versions_stageId_versionNumber " +
                        "ON stage_draft_versions(stageId, versionNumber)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_stage_draft_versions_stageId_status " +
                        "ON stage_draft_versions(stageId, status)"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journey_editions (
                        id TEXT NOT NULL,
                        journeyId TEXT NOT NULL,
                        versionNumber INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        status TEXT NOT NULL,
                        sourceStageDraftIds TEXT NOT NULL,
                        sourceStageCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        confirmedAt INTEGER,
                        PRIMARY KEY(id),
                        FOREIGN KEY(journeyId) REFERENCES journeys(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_journey_editions_journeyId " +
                        "ON journey_editions(journeyId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_journey_editions_journeyId_versionNumber " +
                        "ON journey_editions(journeyId, versionNumber)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_journey_editions_journeyId_status " +
                        "ON journey_editions(journeyId, status)"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS published_posts (
                        id TEXT NOT NULL,
                        journeyId TEXT NOT NULL,
                        journeyEditionId TEXT NOT NULL,
                        versionNumber INTEGER NOT NULL,
                        sourceEditionVersion INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        status TEXT NOT NULL,
                        visibility TEXT NOT NULL,
                        aiAssisted INTEGER NOT NULL,
                        privacyReviewed INTEGER NOT NULL,
                        rightsConfirmed INTEGER NOT NULL,
                        redactedCoordinateCount INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        readyAt INTEGER,
                        withdrawnAt INTEGER,
                        PRIMARY KEY(id),
                        FOREIGN KEY(journeyId) REFERENCES journeys(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(journeyEditionId) REFERENCES journey_editions(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_published_posts_journeyId " +
                        "ON published_posts(journeyId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_published_posts_journeyEditionId " +
                        "ON published_posts(journeyEditionId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_published_posts_journeyId_versionNumber " +
                        "ON published_posts(journeyId, versionNumber)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_published_posts_journeyId_status " +
                        "ON published_posts(journeyId, status)"
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS community_sync_outbox (
                        postId TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        status TEXT NOT NULL,
                        remotePostId TEXT,
                        attemptCount INTEGER NOT NULL,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(postId),
                        FOREIGN KEY(postId) REFERENCES published_posts(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_community_sync_outbox_postId " +
                        "ON community_sync_outbox(postId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_community_sync_outbox_status " +
                        "ON community_sync_outbox(status)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_community_sync_outbox_updatedAt " +
                        "ON community_sync_outbox(updatedAt)"
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS published_post_media (
                        id TEXT NOT NULL,
                        postId TEXT NOT NULL,
                        sourceAttachmentId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        originalPath TEXT NOT NULL,
                        thumbnailPath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        originalBytes INTEGER NOT NULL,
                        originalSha256 TEXT NOT NULL,
                        thumbnailBytes INTEGER NOT NULL,
                        thumbnailSha256 TEXT NOT NULL,
                        remoteMediaId TEXT,
                        status TEXT NOT NULL,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(postId) REFERENCES published_posts(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_published_post_media_postId " +
                        "ON published_post_media(postId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_published_post_media_postId_sourceAttachmentId " +
                        "ON published_post_media(postId, sourceAttachmentId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_published_post_media_status " +
                        "ON published_post_media(status)"
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE published_posts ADD COLUMN destination TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE published_posts ADD COLUMN travelDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE published_posts ADD COLUMN travelDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE published_posts ADD COLUMN stageTitles TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE published_posts ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE published_posts ADD COLUMN pois TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recording_markers (
                        id TEXT NOT NULL,
                        meetingId TEXT NOT NULL,
                        journeyStageId TEXT,
                        timestampMs INTEGER NOT NULL,
                        transcriptAnchor TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recording_markers_meetingId " +
                        "ON recording_markers(meetingId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recording_markers_journeyStageId " +
                        "ON recording_markers(journeyStageId)"
                )
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN recordingMarkerId TEXT")
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN markerTimestampMs INTEGER")
                db.execSQL("ALTER TABLE meeting_attachments ADD COLUMN markerTranscriptAnchor TEXT")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meetings ADD COLUMN origin TEXT NOT NULL DEFAULT 'QUICK'")
                db.execSQL(
                    """
                    UPDATE meetings
                    SET origin = 'FILE_IMPORT'
                    WHERE title LIKE '%资料导入%'
                       OR title LIKE '%文件导入%'
                       OR title LIKE '%导入会议%'
                       OR LOWER(title) LIKE '%import%'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE meetings
                    SET origin = 'SCHEDULED'
                    WHERE origin = 'QUICK'
                      AND (
                          title LIKE '%预定会议%'
                          OR title LIKE '%预约会议%'
                          OR LOWER(title) LIKE '%scheduled%'
                      )
                    """.trimIndent()
                )
            }
        }
    }
}
