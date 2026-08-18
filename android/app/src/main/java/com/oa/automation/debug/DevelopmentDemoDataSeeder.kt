package com.oa.automation.debug

import android.content.Context
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.Meeting
import com.oa.automation.domain.model.MeetingOrigin
import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.Task
import com.oa.automation.domain.model.Transcript
import com.oa.automation.domain.repository.MeetingRepository
import com.oa.automation.domain.repository.JourneyRepository
import com.oa.automation.domain.repository.ReportRepository

/** Explicit, deterministic development data for emulator/UI testing. */
class DevelopmentDemoDataSeeder(
    private val context: Context,
    private val meetingRepository: MeetingRepository,
    private val reportRepository: ReportRepository,
    private val journeyRepository: JourneyRepository
) {
    suspend fun seed(): Result<Int> = runCatching {
        check(BuildConfig.DEBUG) { "演示数据仅允许在 debug 构建中使用" }
        var created = 0
        demoMeetings().forEach { demo ->
            if (meetingRepository.findById(demo.meeting.id).getOrThrow() == null) created++
            meetingRepository.save(demo.meeting).getOrThrow()
            demo.transcripts.forEach { meetingRepository.saveTranscript(it).getOrThrow() }
            demo.journey?.let { journey ->
                journeyRepository.save(journey).getOrThrow()
                demo.journeyStages.forEach { journeyRepository.saveStage(it).getOrThrow() }
            }
            if (demo.meeting.id == DevelopmentStudyTourFixture.MEETING_ID) {
                DevelopmentStudyTourMediaFactory(context)
                    .ensure(demo.meeting.createdAt, demo.journeyStages)
                    .forEach { meetingRepository.saveAttachment(it).getOrThrow() }
            }
            demo.report?.let { reportRepository.save(it).getOrThrow() }
        }
        created
    }

    suspend fun clear(): Result<Int> = runCatching {
        check(BuildConfig.DEBUG) { "演示数据仅允许在 debug 构建中使用" }
        var removed = 0
        DEMO_MEETING_IDS.forEach { id ->
            if (meetingRepository.findById(id).getOrThrow() != null) {
                meetingRepository.delete(id).getOrThrow()
                removed++
            }
        }
        removed
    }

    internal fun demoMeetings(): List<DemoMeeting> {
        val baseTime = 1_786_420_800_000L // 2026-08-10T00:00:00Z, stable across runs.
        val longTranscript = buildString {
            repeat(18) { index ->
                append("第${index + 1}段：项目负责人同步了本周进展、现场风险、资源协调和下一步交付节点。")
                append("参会同事确认责任边界，并约定在下次例会前更新验证结果。\n")
            }
        }
        return listOf(
            DemoMeeting(
                meeting = Meeting(
                    id = DEMO_COMPLETED_ID,
                    title = "演示｜产品周会（已完成）",
                    createdAt = baseTime + 300_000,
                    durationMs = 2_145_000
                ),
                transcripts = listOf(
                    Transcript(
                        id = "$DEMO_COMPLETED_ID-transcript",
                        meetingId = DEMO_COMPLETED_ID,
                        content = "本周完成首页体验升级和录音稳定性回归。团队决定先灰度验证，再安排正式发布。产品负责人周五前整理验收清单。",
                        endTimeMs = 2_145_000,
                        createdAt = baseTime + 300_000
                    )
                ),
                report = Report(
                    id = "$DEMO_COMPLETED_ID-report",
                    meetingId = DEMO_COMPLETED_ID,
                    summary = "首页体验升级已完成，录音稳定性进入灰度验收。",
                    keyPoints = listOf("先进行 AVD 与真机回归", "发布前复核升级链路"),
                    decisions = listOf("通过灰度验证后再正式发布"),
                    tasks = listOf(Task("整理验收清单", "产品负责人", "本周五")),
                    rawContent = "# 产品周会\n\n## 概述\n首页体验升级已完成，录音稳定性进入灰度验收。",
                    templateName = "项目管理",
                    generatedAt = baseTime + 360_000
                )
            ),
            DemoMeeting(
                meeting = Meeting(
                    id = DEMO_TRANSCRIPT_ONLY_ID,
                    title = "演示｜客户访谈（待生成纪要）",
                    createdAt = baseTime + 200_000,
                    durationMs = 1_080_000
                ),
                transcripts = listOf(
                    Transcript(
                        id = "$DEMO_TRANSCRIPT_ONLY_ID-transcript",
                        meetingId = DEMO_TRANSCRIPT_ONLY_ID,
                        content = "客户希望会后可以快速定位决策、待办和风险，并保留可追溯的原始转写。",
                        endTimeMs = 1_080_000,
                        createdAt = baseTime + 200_000
                    )
                )
            ),
            DemoMeeting(
                meeting = Meeting(
                    id = DEMO_IMPORT_ID,
                    title = "演示｜顷刻成稿：季度复盘音频",
                    createdAt = baseTime + 100_000,
                    durationMs = 3_420_000,
                    origin = MeetingOrigin.FILE_IMPORT
                ),
                transcripts = listOf(
                    Transcript(
                        id = "$DEMO_IMPORT_ID-transcript",
                        meetingId = DEMO_IMPORT_ID,
                        content = "导入的季度复盘材料梳理了目标完成度、主要偏差和下季度资源安排。",
                        endTimeMs = 3_420_000,
                        createdAt = baseTime + 100_000
                    )
                ),
                report = Report(
                    id = "$DEMO_IMPORT_ID-report",
                    meetingId = DEMO_IMPORT_ID,
                    summary = "季度目标整体达成，下一阶段重点补齐交付自动化。",
                    rawContent = "# 季度复盘\n\n## 关键结论\n下一阶段重点补齐交付自动化。",
                    templateName = "项目管理",
                    generatedAt = baseTime + 160_000
                )
            ),
            DemoMeeting(
                meeting = Meeting(
                    id = DEMO_LONG_TRANSCRIPT_ID,
                    title = "演示｜长时实时转写展示",
                    createdAt = baseTime,
                    durationMs = 7_260_000
                ),
                transcripts = listOf(
                    Transcript(
                        id = "$DEMO_LONG_TRANSCRIPT_ID-transcript",
                        meetingId = DEMO_LONG_TRANSCRIPT_ID,
                        content = longTranscript,
                        endTimeMs = 7_260_000,
                        createdAt = baseTime
                    )
                )
            ),
            DemoMeeting(
                meeting = Meeting(
                    id = DevelopmentStudyTourFixture.MEETING_ID,
                    title = DevelopmentStudyTourFixture.TITLE,
                    createdAt = baseTime + 400_000,
                    durationMs = DevelopmentStudyTourFixture.DURATION_MS,
                    origin = MeetingOrigin.FILE_IMPORT
                ),
                transcripts = DevelopmentStudyTourFixture.transcripts(baseTime + 400_000),
                journey = DevelopmentStudyTourFixture.journey(baseTime + 400_000),
                journeyStages = DevelopmentStudyTourFixture.stages(baseTime + 400_000),
                report = DevelopmentStudyTourFixture.report(baseTime + 400_000)
            )
        )
    }

    internal data class DemoMeeting(
        val meeting: Meeting,
        val transcripts: List<Transcript>,
        val report: Report? = null,
        val journey: Journey? = null,
        val journeyStages: List<JourneyStage> = emptyList()
    )

    companion object {
        const val DEMO_COMPLETED_ID = "debug-demo-completed-v1"
        const val DEMO_TRANSCRIPT_ONLY_ID = "debug-demo-transcript-only-v1"
        const val DEMO_IMPORT_ID = "debug-demo-file-import-v1"
        const val DEMO_LONG_TRANSCRIPT_ID = "debug-demo-long-transcript-v1"
        val DEMO_MEETING_IDS = listOf(
            DEMO_COMPLETED_ID,
            DEMO_TRANSCRIPT_ONLY_ID,
            DEMO_IMPORT_ID,
            DEMO_LONG_TRANSCRIPT_ID,
            DevelopmentStudyTourFixture.MEETING_ID
        )
    }
}
