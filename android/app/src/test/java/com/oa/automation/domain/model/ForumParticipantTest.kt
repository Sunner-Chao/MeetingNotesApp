package com.oa.automation.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumParticipantTest {
    @Test
    fun extractsStructuredRosterWithRoleAndOrganization() {
        val content = """
            # 论坛纪要

            ## 参会人员名录

            | 姓名/称谓 | 单位 | 角色 |
            | --- | --- | --- |
            | 周岚 | 智悟本 | 主持人 |
            | 林老师 | 城市实验室 | 嘉宾 |
        """.trimIndent()

        val participants = extractForumParticipants(content)

        assertEquals(listOf("周岚", "林老师"), participants.map { it.name })
        assertEquals("主持人", participants[0].role)
        assertEquals("智悟本", participants[0].organization)
        assertEquals("嘉宾", participants[1].role)
    }

    @Test
    fun supportsLegacyKeyValueRosterAndSpeakerMetadataWithoutInventingPhotos() {
        val content = """
            | 项目 | 内容 |
            | --- | --- |
            | 主持人 | 第一场主持：记录者；第二场主持：周老师 |
            | 嘉宾/主讲人 | 林老师、陈工 |
        """.trimIndent()

        val participants = extractForumParticipants(content, listOf("顾老师", "林老师"))

        assertTrue(participants.map { it.name }.containsAll(listOf("记录者", "周老师", "林老师", "陈工", "顾老师")))
        assertTrue(participants.all { it.avatarDataUrl == null && !it.photoAuthorized })
        assertTrue("论坛会议".isForumMeetingTemplate())
        assertTrue("论坛·共识会".isForumMeetingTemplate())
        assertTrue("聚智·论道会".isForumMeetingTemplate())
        assertTrue(!"项目管理".isForumMeetingTemplate())
    }

    @Test
    fun removesMarkdownAndIgnoresGenericSpeakerLabels() {
        val content = """
            | 姓名/称谓 | 单位 | 角色 |
            | --- | --- | --- |
            | **周岚** | 智悟本 | 主持人 |
        """.trimIndent()

        val participants = extractForumParticipants(
            content,
            listOf("Speaker 1", "说话人2", "林老师")
        )

        assertEquals(listOf("周岚", "林老师"), participants.map { it.name })
    }
}
