package com.oa.automation.infrastructure.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MeetingEntity::class, TranscriptEntity::class, ReportEntity::class],
    version = 2,
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
    }
}
