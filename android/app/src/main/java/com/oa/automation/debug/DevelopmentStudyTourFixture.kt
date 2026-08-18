package com.oa.automation.debug

import com.oa.automation.domain.model.Journey
import com.oa.automation.domain.model.JourneyStage
import com.oa.automation.domain.model.JourneyStageStatus
import com.oa.automation.domain.model.JourneyStatus
import com.oa.automation.domain.model.Report
import com.oa.automation.domain.model.Transcript

/** Synthetic long-form study-tour transcript for repeatable AVD report generation. */
internal object DevelopmentStudyTourFixture {
    const val MEETING_ID = "debug-demo-study-tour-long-v1"
    const val JOURNEY_ID = "debug-demo-study-tour-journey-v1"
    const val TITLE = "演示｜两日非遗工坊与乡村共创研学"
    const val DURATION_MS = 21_600_000L
    const val ELAPSED_MS = 117_000_000L

    private val stageOffsetsMs = listOf(
        0L,
        7_200_000L,
        72_000_000L,
        79_200_000L,
        90_000_000L,
        108_000_000L
    )

    private val stageTitles = listOf(
        "古法造纸工坊",
        "竹编非遗馆",
        "云岭生态茶园",
        "清溪古村水系",
        "乡创共富工坊",
        "返程分享会"
    )

    fun transcripts(baseTime: Long): List<Transcript> = segmentContents.mapIndexed { index, content ->
        val segmentDuration = DURATION_MS / segmentContents.size
        Transcript(
            id = "$MEETING_ID-transcript-${index + 1}",
            meetingId = MEETING_ID,
            content = content.trimIndent(),
            startTimeMs = segmentDuration * index,
            endTimeMs = segmentDuration * (index + 1),
            createdAt = baseTime + stageOffsetsMs[index]
        )
    }

    fun journey(baseTime: Long): Journey = Journey(
        id = JOURNEY_ID,
        meetingId = MEETING_ID,
        title = TITLE,
        status = JourneyStatus.COMPLETED,
        currentStageId = null,
        createdAt = baseTime,
        updatedAt = baseTime + ELAPSED_MS,
        completedAt = baseTime + ELAPSED_MS
    )

    fun stages(baseTime: Long): List<JourneyStage> = stageTitles.mapIndexed { index, title ->
        val startedAt = baseTime + stageOffsetsMs[index]
        val savedAt = if (index == stageTitles.lastIndex) {
            baseTime + ELAPSED_MS
        } else {
            baseTime + stageOffsetsMs[index + 1] - 900_000L
        }
        JourneyStage(
            id = "$JOURNEY_ID-stage-${index + 1}",
            journeyId = JOURNEY_ID,
            sequenceNumber = index + 1,
            title = title,
            status = JourneyStageStatus.SAVED,
            startedAt = startedAt,
            updatedAt = savedAt,
            savedAt = savedAt
        )
    }

    fun report(baseTime: Long): Report = Report(
        id = "$MEETING_ID-report",
        meetingId = MEETING_ID,
        summary = "两天六站，从手艺、土地和真实经营中重新理解乡村共创。",
        rawContent = """
            # 山里的一张纸，如何连接一座村庄

            > 这趟两日研学没有从宏大的乡村振兴口号开始，而是从一池纸浆、一根竹篾和一杯刚炒好的春茶开始。手艺如何留下来，村庄怎样被真实使用，答案都藏在具体的人和日常里。

            **路线**：古法造纸工坊 → 竹编非遗馆 → 云岭生态茶园 → 清溪古村水系 → 乡创共富工坊 → 返程分享会

            **同行与讲解**：研学小组、非遗传承人、茶园负责人、村史讲解员与返乡创业团队

            ## 第一站｜古法造纸工坊 · 一张纸要经过时间

            推开木门，最先听到的是水声和竹帘入池的轻响。师傅把已经蒸煮、漂洗和捶打过的树皮纤维倒进纸槽，纸浆看起来很轻，真正抄起来却需要稳定的手腕。

            [照片：图 1｜师傅用竹帘从纸槽中抄起均匀纸浆]

            “薄厚不是靠称出来的，是手在水里慢慢记住的。”这句话让原本抽象的经验传承有了画面。我们轮流体验后才发现，帘子只要稍微倾斜，纤维就会聚到一侧。

            [照片：图 2｜院落中逐张晾晒的手工纸形成柔和光影]

            晾纸墙没有被设计成表演区，它仍在完成每天真实的生产。参观者能靠近观察，但不能打乱揭纸、检查和平码的节奏。

            ## 第二站｜竹编非遗馆 · 从经纬里看见秩序

            展柜里最吸引人的并不是复杂摆件，而是一组从破竹、刮青到起底的工具。传承人先让大家摸不同宽度的竹篾，再解释同样的图案为什么要用不同韧性的材料。

            [照片：图 3｜竹篾在木桌上交错形成器物的起底纹理]

            一位同学编到第三圈时出现明显空隙，老师没有立刻拆掉，而是让大家观察前两圈张力怎样一步步累积成最后的偏差。手艺的精确并不冷冰冰，它来自持续感知和及时调整。

            ### 问题｜为什么前两圈的张力会影响后面的形状？

            ### 现场回答

            传承人解释，竹篾会持续把前一圈的受力带到下一圈；起底时的松紧不均，越往外编越容易被放大。

            ### 观察印证

            同学把出现空隙的半成品与旁边完成度较高的样品并排，能直接看出偏差从起底位置向外延伸。

            ### 继续探索

            如何在不拆掉整件作品的情况下调整局部张力，是大家离开前继续追问的问题。

            ## 第三站｜云岭生态茶园 · 好味道从土壤开始

            第二天清晨，茶垄沿山势铺开。负责人没有先谈茶叶等级，而是带我们看覆盖在土面的落叶、诱虫板和坡地排水沟。茶园减少除草剂后，维护工作反而更细。

            [照片：图 4｜顺着山势延伸的茶垄与保留的地表植被]

            采下的一芽两叶要尽快送到小型加工间。杀青时的香气很直接，但师傅提醒，香气不能替代对温度、含水率和叶片状态的判断。体验环节因此不只是拍照，也包括记录每一步变化。

            ## 第四站｜清溪古村水系 · 村庄不是静止的布景

            古村的水从山脚进入，经过洗涤、灌溉和景观节点后流向稻田。讲解员没有回避生活排水和旺季拥堵等问题，反而用几次改造说明水系如何边使用边调整。

            [照片：图 5｜石桥、巷道与穿村水渠共同形成日常路径]

            老宅修缮保留了原有尺度，新加入的民宿和工作室必须服从消防、排水与邻里关系。最打动人的不是“古色古香”，而是放学的孩子、门口择菜的老人和游客共享同一条巷子。

            ## 第五站｜乡创共富工坊 · 文创要回到真实订单

            返乡团队把手工纸、竹编和茶园副产品重新组合成礼盒，但负责人首先展示的不是包装，而是订单、工时和分配表。哪些由村民在家完成，哪些必须集中质检，账目都写得很清楚。

            [照片：图 6｜工坊桌面上的手工纸、竹编包装与产品样品]

            团队曾做过一批很受欢迎却难以稳定生产的复杂包装，最后主动下架。能持续交付、让参与者获得合理收入，比一次传播数据更重要。

            ## 第六站｜返程分享会 · 把看见的带回去

            返程前，大家围坐把照片按阶段铺开。有人记住纸浆在水中的重量，有人记住竹编张力的累积，也有人反复提到古村水渠和工坊账本。

            [照片：图 7｜分享会上按路线展开的照片、笔记与采集样本]

            六个阶段最终汇成一个朴素判断：好的研学不只是“看过”，而是能说清一个地方怎样工作、谁在维护、遇到什么问题，以及人们如何继续修正。

            ## 旅程回望

            手艺、农业、村落和经营原本像四个分开的主题，真正走过以后才发现，它们都依赖长期关系。材料来自土地，技艺依赖人，产品要进入市场，村庄还要继续生活。

            这趟旅程最珍贵的不是获得一个标准答案，而是学会从生产现场、使用过程和真实限制中观察变化。

            ## 实用小贴士

            工坊体验前先确认哪些步骤可以动手，避免打乱真实生产流程。

            茶园和古村适合穿防滑鞋，雨后石板路与坡地较湿。

            拍摄传承人和村民前先征得同意，产品配方、订单和个人信息不要随意公开。

            ## 封面标题建议

            山里的一张纸，如何连接一座村庄

            ## 话题标签

            #研学日记 #非遗寻访 #乡村观察 #手作体验
        """.trimIndent(),
        templateName = "研学考察",
        generatedAt = baseTime + ELAPSED_MS + 60_000L
    )

    private val segmentContents = listOf(
        """
            【第一天上午，古法造纸工坊】
            师傅介绍树皮纤维要经过蒸煮、漂洗、捶打和抄纸。竹帘入水的角度会影响纸浆分布，薄厚判断主要依赖长期手感。学员体验时发现，动作看起来简单，真正保持均匀却很难。院落晾纸墙仍承担日常生产，参观必须服从揭纸和检查节奏。
        """,
        """
            【第一天下午，竹编非遗馆】
            传承人从材料开始讲解，竹龄、取材位置、宽度和含水状态都会影响韧性。体验者在第三圈出现空隙，老师通过前两圈张力解释偏差怎样累积。大家开始理解，经纬纹样背后是材料判断、手部反馈和持续修正。
        """,
        """
            【第二天清晨，云岭生态茶园】
            茶园负责人带队观察地表覆盖、坡地排水和病虫管理。减少除草剂不等于减少管理，而是把工作转向更细致的人工观察。采茶和杀青体验中，师傅不断根据叶片、温度和含水状态调整动作。
        """,
        """
            【第二天上午，清溪古村水系】
            村史讲解员沿水渠介绍生活、灌溉和公共空间的关系，也说明旺季拥堵、排水和消防等真实问题。修缮没有把村庄变成静止布景，新业态必须与居民生活共享有限的巷道和基础设施。
        """,
        """
            【第二天下午，乡创共富工坊】
            返乡团队展示手工纸、竹编和茶产品的组合方式，同时公开工时、订单和分配逻辑。团队主动下架过难以稳定生产的复杂包装，认为持续交付和合理收入比一次传播数据更重要。
        """,
        """
            【返程前，分享会】
            学员把照片和笔记按路线铺开，分别讲述最改变原有判断的细节。讨论最终形成共识：研学游记应当说明地方怎样工作、谁在维护、真实限制是什么，以及人们如何持续调整，而不是只罗列到访地点。
        """
    )
}
