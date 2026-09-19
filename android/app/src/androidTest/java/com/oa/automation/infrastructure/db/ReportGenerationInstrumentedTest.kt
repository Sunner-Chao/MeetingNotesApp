package com.oa.automation.infrastructure.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oa.automation.domain.model.Report
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportGenerationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private val owner = "generation-regression-owner"
    private val meetingId = "generation-regression-meeting"
    private val templateA = "宣贯·落实会"
    private val templateB = "博弈·洽谈会"
    private val dao get() = database.reportDao()

    @Before
    fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        database.meetingDao().upsertMeeting(meeting())
        dao.upsertReport(report("original", templateA, 1L))
    }

    @After
    fun close() = database.close()

    private fun meeting() = MeetingEntity(
        id = meetingId, title = "模板并发验收", createdAt = 1L, durationMs = 1L,
        audioFilePath = null, origin = "IMPORTED", selectedTemplateName = templateA, ownerId = owner
    )

    private fun report(id: String, template: String, time: Long = 2L) = Report(
        id = id, meetingId = meetingId, templateName = template,
        rawContent = "# $template\n当前请求的测试正文", generatedAt = time, ownerId = owner
    ).toEntity()

    @Test
    fun switchingTemplateInvalidatesOldBodyUntilNewResultCommits() = runBlocking {
        val task = dao.beginGeneration(meetingId, owner, "request-b", templateB)
        assertNull(dao.findByMeetingId(meetingId, owner))
        assertNull(dao.findCurrentReport(meetingId, owner))
        assertEquals(templateB, database.meetingDao().findMeetingById(meetingId, owner)?.selectedTemplateName)
        val fresh = report(task.reportId, templateB)
        assertTrue(dao.completeGeneration(fresh, task.requestId))
        assertEquals(fresh, dao.findCurrentReport(meetingId, owner))
    }

    @Test
    fun lateResultsCannotOverwriteEvenAfterSwitchingBackToSameTemplate() = runBlocking {
        val first = dao.beginGeneration(meetingId, owner, "a-first", templateA)
        val second = dao.beginGeneration(meetingId, owner, "b-second", templateB)
        val latest = dao.beginGeneration(meetingId, owner, "a-latest", templateA)
        assertFalse(dao.completeGeneration(report(first.reportId, templateA), first.requestId))
        assertFalse(dao.completeGeneration(report(second.reportId, templateB), second.requestId))
        assertNull(dao.findCurrentReport(meetingId, owner))
        val current = report(latest.reportId, templateA, 3L)
        assertTrue(dao.completeGeneration(current, latest.requestId))
        assertFalse(dao.completeGeneration(report(first.reportId, templateA, 4L), first.requestId))
        assertEquals(current, dao.findCurrentReport(meetingId, owner))
    }

    @Test
    fun wrongTemplateOrReportIdCannotCompleteCurrentRequest() = runBlocking {
        val task = dao.beginGeneration(meetingId, owner, "current", templateB)
        assertFalse(dao.completeGeneration(report(task.reportId, templateA), task.requestId))
        assertFalse(dao.completeGeneration(report("wrong-id", templateB), task.requestId))
        assertNull(dao.findCurrentReport(meetingId, owner))
    }

    @Test
    fun cancellationAndDeletionRejectLateResults() = runBlocking {
        val cancelled = dao.beginGeneration(meetingId, owner, "cancelled", templateB)
        dao.cancelGeneration(meetingId, owner)
        assertFalse(dao.completeGeneration(report(cancelled.reportId, templateB), cancelled.requestId))
        val deleted = dao.beginGeneration(meetingId, owner, "deleted", templateB)
        dao.deleteCurrentReport(meetingId, owner)
        assertFalse(dao.completeGeneration(report(deleted.reportId, templateB), deleted.requestId))
        assertNull(dao.findCurrentReport(meetingId, owner))
    }

    @Test
    fun staleLayoutSaveCannotRestoreOldReport() = runBlocking {
        val old = checkNotNull(dao.findCurrentReport(meetingId, owner))
        val task = dao.beginGeneration(meetingId, owner, "new", templateA)
        assertTrue(runCatching { dao.saveCurrentReport(old) }.isFailure)
        val fresh = report(task.reportId, templateA, 3L)
        assertTrue(dao.completeGeneration(fresh, task.requestId))
        assertTrue(runCatching { dao.saveCurrentReport(old) }.isFailure)
        assertEquals(fresh, dao.findCurrentReport(meetingId, owner))
    }

    @Test
    fun completedRequestIsIdempotentAndCannotBeReplacedByRetry() = runBlocking {
        val task = dao.beginGeneration(meetingId, owner, "current", templateA)
        val fresh = report(task.reportId, templateA)
        assertTrue(dao.completeGeneration(fresh, task.requestId))
        assertTrue(dao.completeGeneration(fresh, task.requestId))
        assertFalse(dao.completeGeneration(fresh.copy(rawContent = "迟到的重试结果"), task.requestId))
        assertEquals(fresh, dao.findCurrentReport(meetingId, owner))
    }

    @Test
    fun ownerAndMeetingTemplateMustMatch() = runBlocking {
        val task = dao.beginGeneration(meetingId, owner, "current", templateA)
        val fresh = report(task.reportId, templateA)
        assertFalse(dao.completeGeneration(fresh.copy(ownerId = "another-owner"), task.requestId))
        assertNull(dao.findCurrentReport(meetingId, "another-owner"))
        assertTrue(dao.completeGeneration(fresh, task.requestId))
        dao.selectMeetingTemplate(meetingId, owner, templateB)
        assertNull(dao.findCurrentReport(meetingId, owner))
        assertTrue(runCatching { dao.saveCurrentReport(fresh) }.isFailure)
    }

    @Test
    fun migrationPreservesExistingReportAndEnablesGenerationLock() = runBlocking {
        val name = "report-generation-migration-test.db"
        context.deleteDatabase(name)
        try {
            val source = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                source.meetingDao().upsertMeeting(meeting())
                source.reportDao().upsertReport(report("legacy", templateA))
            } finally {
                source.close()
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TABLE report_generations")
                it.version = 25
            }
            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_25_26).build()
            try {
                assertEquals("legacy", migrated.reportDao().findCurrentReport(meetingId, owner)?.id)
                migrated.reportDao().beginGeneration(meetingId, owner, "after-upgrade", templateB)
                assertNull(migrated.reportDao().findCurrentReport(meetingId, owner))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
