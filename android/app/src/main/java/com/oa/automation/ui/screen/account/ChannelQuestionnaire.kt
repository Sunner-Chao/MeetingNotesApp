package com.oa.automation.ui.screen.account

/** Stable answer keys shared with the existing private-channel application API. */
internal object ChannelQuestionnaire {
    val scenes = listOf("工作例会 / 项目推进", "客户沟通 / 商务洽谈", "培训学习 / 课程讲座", "论坛会议 / 研学考察", "个人复盘 / 灵感记录", "其他场景")
    val frequencies = listOf("几乎每天", "每周几次", "每月几次", "偶尔使用 / 还在体验")
    val methods = listOf("纸笔 / 手动笔记", "录音后回听整理", "其他转写或纪要工具", "通常不整理")
    val painPoints = listOf("来不及记，容易漏重点", "回听整理太费时间", "转写不准，多人发言难区分", "纪要重点不清，需要反复改", "照片、文字和标记难整合", "结论和待办难跟进")
    val paymentPreferences = listOf("先体验效果再决定", "更适合按次购买", "更适合按月套餐", "暂时只考虑免费功能")

    fun answers(
        name: String, city: String, scene: String, frequency: String,
        method: String, pains: List<String>, payment: String, suggestion: String, contact: String
    ): Map<String, String>? {
        if (name.isBlank() || city.isBlank() || scene !in scenes || frequency !in frequencies ||
            pains.isEmpty() || pains.size > 3 || pains.distinct().size != pains.size || pains.any { it !in painPoints }) return null
        return linkedMapOf(
            "survey_version" to "lite-20260914",
            "name" to name.trim().take(50),
            "city" to city.trim().take(50),
            "usage_scene" to scene,
            "usage_frequency" to frequency,
            "current_method" to method.takeIf { it in methods }.orEmpty(),
            "pain_points" to pains.joinToString("；"),
            "payment_preference" to payment.takeIf { it in paymentPreferences }.orEmpty(),
            // Older admin clients still show a meaningful joining purpose.
            "purpose" to "希望用于$scene，主要想解决：${pains.joinToString("；")}",
            "suggestion" to suggestion.trim().take(300),
            "contact" to contact.trim().take(100)
        ).filterValues { it.isNotBlank() }
    }
}
