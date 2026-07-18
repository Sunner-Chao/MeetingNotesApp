package com.oa.automation.infrastructure.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MeetingEntity::class, TranscriptEntity::class, ReportEntity::class, MeetingAttachmentEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meetingDao(): MeetingDao
    abstract fun reportDao(): ReportDao

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
    }
}
