package com.oa.automation.ui.screen.community

import com.oa.automation.domain.model.CommunityCollection
import com.oa.automation.domain.model.CommunityComment
import com.oa.automation.domain.model.CommunityFacets
import com.oa.automation.domain.model.PublicCommunityMedia
import com.oa.automation.domain.model.PublicCommunityPost

internal data class CommunityPublicReference(
    val id: String,
    val title: String,
    val destination: String,
    val summary: String,
    val sourceLabel: String,
    val sourceUrl: String,
    val referenceLabel: String,
    val relatedPostId: String
)

/**
 * A read-only snapshot of a publicly listed activity. These entries are not community posts
 * and do not imply that 智悟本 or the listed organizer is affiliated with one another.
 */
internal data class CommunityActivityNotice(
    val id: String,
    val title: String,
    val dateLabel: String,
    val locationLabel: String,
    val priceLabel: String,
    val summary: String,
    val sourceLabel: String,
    val sourceUrl: String,
    val verifiedOn: String
)

/**
 * Reviewable domestic study-tour samples shown only while the public community has no real posts.
 * Copy is original; every bundled photo has a recorded reusable license.
 */
internal object MockStudyCommunityData {
    const val ID_PREFIX = "sample-study-"
    const val ACTIVITY_SNAPSHOT_DATE = "2026-08-20"
    private const val ASSET_ROOT = "asset:///community/mock/"
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS
    private val now = System.currentTimeMillis()

    val posts: List<PublicCommunityPost> = listOf(
        PublicCommunityPost(
            id = "${ID_PREFIX}west-lake-heritage",
            title = "杭州西湖怎么逛才不只拍照｜一条边走边学的湖岸线",
            content = """
                # 杭州西湖边走边学

                这次没有把西湖当成一张大合影。我们从湖岸、桥、堤和远山一路走，边看景边追一个问题：一座城市怎样把水面、游览路线和日常生活放在一起？

                ## 第一站：湖面先建立方向

                站在开阔处先拍一张湖面和城市的关系图，再记下视线朝向。讲解员提醒我们，西湖的“好看”不只来自水面，也来自堤岸、桥亭、树影和远处建筑共同形成的层次。📍

                ## 第二站：沿岸走，看路线如何停顿

                一段路为什么让人愿意慢下来？我们观察树下座椅、临水平台、桥头转折和人群停留的位置。风景被看见之前，路线已经在安排观看的节奏。

                ## 第三站：把景色带回一张学习卡

                每个人选一个“景观细节”，写下它解决了什么问题：遮阴、借景、过水，还是让人靠近湖面。不要只写“很美”，要说清楚它如何参与一段游览体验。

                ## 这趟路线带走什么

                - 一张湖岸整体关系图
                - 三个让人停下来的景观节点
                - 一句自己对“城市与水”的新理解
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 7 * HOUR_MS,
            authorLabel = "西湖边的学习小组",
            media = media(
                "west-lake-heritage",
                "west-lake.jpg",
                "west-lake-bridge.jpg",
                "west-lake-lotus.jpg",
                "west-lake-skyline.jpg"
            ),
            destination = "杭州西湖湖岸线",
            travelDate = "2026-08-19",
            travelDays = 1,
            stages = listOf("湖面定向", "湖岸漫步", "景观停点", "学习卡复盘"),
            tags = listOf("景观研学", "杭州旅行", "湖岸路线", "边走边学"),
            pois = listOf("湖岸观景点", "桥亭节点", "临水平台"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "用景观节点串起一条可慢慢走、也能学明白的湖岸路线"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}liangzhu-clue-route",
            title = "良渚博物院半日游｜看玉琮之前先读懂这片土地",
            content = """
                # 良渚博物院半日游

                到良渚，最容易一进展厅就被精美展品吸引。我们给自己设了一个顺序：先看遗址与水网的整体，再看玉器和生活线索，最后把展厅里的信息放回真实地景。

                ## 先看建筑和远景：博物馆不是孤立展柜

                从入口望出去，先记住建筑、草地、道路和远处地形的关系。讲解员把“城、水、稻作、玉礼”串成一条线，展品因此有了它们所在的土地。

                ## 再看三件展品：每件只回答一个问题

                玉琮看形制，陶器看使用，遗址图看聚落。我们不追求把展签抄满，而是把“它是什么”“它说明了怎样的生活”“我还想查什么”分别写在小卡片上。

                ## 最后一段：把展厅知识带到户外

                离馆前重新看一眼周边景观，想一想水网与聚落为什么会一起出现。照片用一张建筑全景、一张展厅局部和一张户外地景收束，刚好形成一组完整的学习线索。✨

                ## 适合带走的三件事

                - 一张“遗址—展品—地景”关系图
                - 一件想回去继续查的器物
                - 一句用自己的话讲给同伴听的知识点
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 18 * HOUR_MS,
            authorLabel = "玉琮线索社",
            media = media("liangzhu-clue-route", "liangzhu-museum.jpg"),
            destination = "杭州良渚博物院",
            travelDate = "2026-08-18",
            travelDays = 1,
            stages = listOf("建筑与地景", "展品线索", "讲解串联", "户外回看"),
            tags = listOf("博物馆研学", "良渚", "历史景点", "展品线索"),
            pois = listOf("博物馆入口", "玉器展厅", "遗址景观"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "把展品、遗址和旅行景观放在同一条学习路线里"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}suzhou-garden-street",
            title = "苏州一日研学路线｜园林、博物馆西馆和老街这样连起来",
            content = """
                # 苏州一日研学路线

                苏州不适合只在景点之间打卡。我们把一天拆成三种观看方式：园林里看空间转折，博物馆里看城市记忆，老街上看传统如何继续被使用。

                ## 上午：园林里找“藏”与“露”

                走过门洞、漏窗和曲廊时，不急着拍正面。先猜下一步会看到什么，再回头看景框是怎样被安排出来的。同行的小朋友负责找“第一次看不见、走近才出现”的景色。

                ## 午后：在苏州博物馆西馆把景色换成线索

                苏州博物馆西馆的建筑、展厅和院落让参观本身也成为一段路线。我们各自选一件展品，用三句话说明材料、用途和它与江南生活的联系。

                ## 傍晚：平江路看真实的城市日常

                老街不只是一张古风背景。沿河的店铺、桥、门槛和居民出行共同构成今天的生活。最后用一张街景全景和两张细节图，比较“被保存的样子”和“正在使用的样子”。

                ## 一日结束的小结

                风景不是知识的装饰。园林教我们看空间，博物馆帮我们理解历史，老街让知识回到人的日常。
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 2 * DAY_MS,
            authorLabel = "江南慢游研究所",
            media = media("suzhou-garden-street", "suzhou-museum.jpg"),
            destination = "苏州园林与平江路",
            travelDate = "2026-08-17",
            travelDays = 1,
            stages = listOf("园林借景", "展馆线索", "老街观察", "一日回看"),
            tags = listOf("苏州研学", "园林旅行", "博物馆路线", "城市漫步"),
            pois = listOf("园林入口", "苏州博物馆西馆", "平江路河岸"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "同一天里切换景观、展馆和生活街巷三种观察镜头"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}zhangjiajie-landform",
            title = "张家界地貌研学｜把“像仙境”换成看得懂的山形",
            content = """
                # 张家界地貌研学

                山景太好看时，最容易只剩下一句“像仙境”。这次我们沿观景路线走，每到一个开阔点就做一件小事：先找峰、谷、崖的关系，再听讲解，最后用自己的话复述山形是怎样被看见的。

                ## 第一眼：先找整体轮廓

                站在观景台不要急着放大拍峰顶。先拍一张能看见山体群落的全景，再在纸上画出高低、远近和遮挡关系。照片负责留下风景，速写负责留下观看方法。

                ## 走近一点：看一块岩壁的细节

                岩柱、裂隙、植被和云雾让同一座山在不同时间呈现不同表情。讲解员说到地貌成因时，我们把关键词写在照片旁边，回到住处再用三句话讲给同行者听。

                ## 下山前：留一张“我学会了什么”

                不把专业术语堆在结尾，只回答：我现在能看出哪种山形？我还分不清什么？下一次旅行想怎样验证？这样风景才会从相册走进记忆。

                ## 拍摄小提示

                一张远景建立尺度，两张不同方向的山体关系图，最后补一张步道或观景台的环境照，七张滚轮图也不会变成重复风景。
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 3 * DAY_MS,
            authorLabel = "山里的一节课",
            media = media("zhangjiajie-landform", "zhangjiajie.jpg"),
            destination = "张家界国家森林公园",
            travelDate = "2026-08-16",
            travelDays = 1,
            stages = listOf("观景定向", "山形速写", "岩壁细看", "下山复述"),
            tags = listOf("自然景观", "张家界", "地貌研学", "旅行观察"),
            pois = listOf("山体观景台", "峰林步道", "岩壁观察点"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "用远景、细节和复述把壮阔景观变成可理解的学习过程"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}huangshan-mountain",
            title = "黄山一日学习卡｜迎客松之外，还能看懂什么",
            content = """
                # 黄山一日学习卡

                黄山最有名的景观很容易成为终点，但研学旅行不妨多走一步：看山路怎样进入山体，看松树怎样与岩石相处，看云雾怎样改变远近层次。

                ## 入口：先记录一条山路

                选一段有台阶、转弯和高差变化的路线，拍下“人从哪里来、准备往哪里去”。山路不是风景之外的设施，它决定了我们以什么速度遇见山。

                ## 半山：看一棵松树的生长位置

                讲解员没有只说名字，而是让我们观察松树、岩缝、风向和光线。我们拍一张整体，再补一张根部与岩面的关系图，理解“迎客”背后的自然条件。

                ## 云海出现时：留下变化，不抢着下结论

                同一处观景点在云开、云合时完全不同。记录时间和视线范围就够了，不把一次看到的景象写成永远如此。下山时把三张照片按“远—近—变化”排列，整趟行程就有了节奏。

                ## 这张学习卡的背面

                - 山路如何组织游览节奏
                - 植物与岩石怎样形成关系
                - 天气变化如何改变景观阅读
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 4 * DAY_MS,
            authorLabel = "山景慢读会",
            media = media("huangshan-mountain", "huangshan.jpg"),
            destination = "黄山风景区",
            travelDate = "2026-08-15",
            travelDays = 1,
            stages = listOf("山路记录", "松石观察", "云海变化", "学习卡复盘"),
            tags = listOf("黄山旅行", "山水研学", "景观阅读", "自然笔记"),
            pois = listOf("登山步道", "松石观景点", "云海观景台"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "让知名景点从‘拍到了’继续走向‘看懂了’"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}guilin-river-study",
            title = "桂林山水怎么做成研学路线｜漓江边的三次观察",
            content = """
                # 漓江边的三次观察

                桂林山水的照片很容易拍成一整排相似的山峰。我们换了一个方式：把游船或岸线上的观看拆成远景、近岸和人的使用，让一段风景有起点、有变化，也有学习问题。

                ## 远景：山水为什么有层次

                第一张照片只拍山、水和天空的整体关系，记录视线方向和天气。讲解员提到峰形、河道和水面反光时，我们把听到的知识点放到照片下方，不写成空泛的赞美。

                ## 近岸：看水怎样进入生活

                靠岸后观察渡口、村落、竹筏和岸边植被。景点不只是自然风光，也是一条被人使用的生活路径。我们把“自然景观”和“日常活动”放在同一张拼图里。

                ## 回程：用一张图讲完整

                三个人各选一张最能说明变化的照片：远景建立尺度，近景交代细节，人物或船只提供参照。回程互相讲两分钟，看看谁能把山、水、路线和知识点连起来。

                ## 收藏价值

                下次来不必复制同一套照片，可以换一个天气、一个岸线节点或一个小问题，继续观察同一片山水。
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 5 * DAY_MS,
            authorLabel = "漓江边的观察员",
            media = media("guilin-river-study", "guilin-river.jpg"),
            destination = "桂林漓江山水线",
            travelDate = "2026-08-14",
            travelDays = 1,
            stages = listOf("山水远景", "近岸生活", "讲解记录", "三图复述"),
            tags = listOf("桂林旅行", "漓江", "山水观察", "研学游记"),
            pois = listOf("漓江观景点", "近岸村落", "渡口节点"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "把一片熟悉山水拆成风景、生活与学习三层"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}sanxingdui-museum",
            title = "三星堆博物馆怎么逛｜从一张面具追到古蜀想象力",
            content = """
                # 三星堆博物馆怎么逛

                这次的入口不是“看了好多文物”，而是一张问题卡：古蜀人为什么留下这样夸张的形象？我们从展厅空间、器物细节和出土背景三步走，边逛边把问题拆小。

                ## 第一眼：先看展厅里的尺度

                大型展陈让人一进门就感到震撼。先退后一步拍整体，再靠近看眼、耳、纹饰这些局部，照片的远近变化也对应了理解的远近。

                ## 第二眼：选一件器物讲给别人听

                不求记住所有年代和编号，每个人选一件器物，用“我看到了什么—讲解员说了什么—我还想知道什么”完成一分钟分享。知识从展签变成了自己的语言。

                ## 第三眼：回到展馆外的景观

                离开展厅后，把博物馆建筑、湖面和步行路线拍进同一组照片。一次好的文化旅行，不只是在室内看文物，也会记住知识发生的空间。

                ## 一组图怎么排

                封面用展馆或外部景观，第二张交代展厅尺度，中间放两到三张器物细节，最后放一张同行者的学习卡，读起来会比连续摆展品更有故事。
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 6 * DAY_MS,
            authorLabel = "古蜀文化散步队",
            media = media("sanxingdui-museum", "sanxingdui-museum.jpg"),
            destination = "广汉三星堆博物馆",
            travelDate = "2026-08-13",
            travelDays = 1,
            stages = listOf("展厅定向", "器物细看", "一分钟分享", "馆外回望"),
            tags = listOf("三星堆", "博物馆旅行", "文化研学", "展品故事"),
            pois = listOf("博物馆入口", "青铜器展厅", "馆外景观"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "让器物细节、讲解内容和旅行空间共同完成一篇游记"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}xian-history-route",
            title = "西安历史研学一日线｜兵马俑之后再问一个问题",
            content = """
                # 西安历史研学一日线

                兵马俑的震撼常常让人拍完就走。这次我们给参观加了一条“为什么”的线：从坑位与阵列看组织，从人物细节看工艺，再把展馆中的历史想象带回城市路线。

                ## 先看整体：阵列怎样形成秩序

                站在高处拍一张整体，不马上放大某一尊俑。同行者分别记录队列方向、空间尺度和人物分布，回看时先讨论整体，再讨论细节。

                ## 再看局部：每个细节都在说话

                服饰、发式、手势和面部表情让“军阵”里出现了具体的人。讲解员提到制作与修复时，我们把“看见的细节”和“听到的解释”分开记，避免把猜测当成结论。

                ## 回到城市：历史景点不止一个室内展厅

                旅行的下一站可以留给古城墙或街区。把厚重的历史展陈与今天的城市生活并排看，才会发现历史并不是一条封闭的时间线，而是仍在影响城市的观看方式。

                ## 复盘问题

                一组照片最终只保留一个主问题：我们今天看到的“秩序”，是怎样通过空间、工艺和人的细节被表现出来的？
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 7 * DAY_MS,
            authorLabel = "关中历史行走课",
            media = media("xian-history-route", "terracotta-army.jpg"),
            destination = "西安秦始皇帝陵博物院",
            travelDate = "2026-08-12",
            travelDays = 1,
            stages = listOf("阵列全景", "人物细节", "讲解记录", "城市回望"),
            tags = listOf("西安旅行", "兵马俑", "历史研学", "文化路线"),
            pois = listOf("一号坑展厅", "文物陈列区", "历史城市路线"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "从壮观的阵列进入具体的工艺与历史问题"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}wuyuan-village",
            title = "婺源古村怎么拍得更有内容｜一条村路的四个停点",
            content = """
                # 婺源古村一条村路

                古村的白墙、黛瓦和溪水很上镜，但如果每张照片都只拍风景，回家后很快就分不清。我们沿着一条村路停四次，把景色、建筑、手艺和日常生活放进同一篇游记。

                ## 村口：先拍路与山的关系

                从村外拍一张整体，留下山、水、田和房屋的层次。它是整组图片的“地图”，后面所有细节都要能回到这张图里找到位置。

                ## 溪边：看水如何经过生活

                桥、洗涤处、台阶和临水房屋都说明村庄怎样使用水。我们不把村落写成静态布景，而是记录真实的通行和停留。

                ## 老屋：把门窗和檐口放回建筑里

                局部图不单独炫技。拍门、窗、木构和屋檐时，先补一张所在立面的照片，再写它与采光、避雨或进出有什么关系。

                ## 回程：问一个关于“留下来”的问题

                旅行结束时，大家各自写下一个观察：哪些空间还在被使用，哪些已经改变用途？古村的学习不在于复述“很有历史”，而在于看到它今天怎样继续生活。
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 8 * DAY_MS,
            authorLabel = "村路观察笔记",
            media = media(
                "wuyuan-village",
                "wuyuan-village.jpg",
                "wuyuan-village-wide.jpg",
                "wuyuan-village-interior.jpg"
            ),
            destination = "婺源篁岭与古村落",
            travelDate = "2026-08-11",
            travelDays = 1,
            stages = listOf("村口定向", "溪边观察", "老屋细看", "回程提问"),
            tags = listOf("婺源旅行", "古村研学", "乡土观察", "拍照路线"),
            pois = listOf("村口观景点", "溪边小桥", "传统民居"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "让古村照片拥有地图、细节和生活三种层次"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}qinghai-landscape",
            title = "青海湖景观观察｜从一片蓝看高原的风、光和路",
            content = """
                # 青海湖景观观察

                面对青海湖，大家第一反应都是“太蓝了”。我们把这句感受留在开头，然后继续看：湖面为什么显得开阔，岸线怎样与公路和草地相遇，风和光如何改变同一个景点。

                ## 第一段：一张全景做尺度

                把湖面、岸线和远处山体一起拍下，人物只作为尺度参照。讲解员带我们看水面颜色、天空亮度和岸线形状，景色开始从“漂亮”变成可描述的对象。

                ## 第二段：沿岸走十分钟

                每隔一段时间回头看一次，记录颜色、风向和人流是否变化。我们不猜测看不见的生态结论，只保留直接观察到的现象和下一步想查的知识。

                ## 第三段：给旅行留一个慢镜头

                选一处安全的观景位置安静停几分钟，拍一张不追求信息量的照片。旅行不需要每一秒都安排任务，但回来后要说得出这段停留让自己看见了什么。

                ## 适合放在文末的路线提示

                先确定观景点，再拍全景；沿岸观察时不离开安全区域；公开分享人物和车辆信息前先检查画面。简单的边界，能让风景分享更舒服。
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 9 * DAY_MS,
            authorLabel = "高原风景课",
            media = media("qinghai-landscape", "qinghai-lake.jpg"),
            destination = "青海湖环湖景观线",
            travelDate = "2026-08-10",
            travelDays = 1,
            stages = listOf("全景定尺度", "岸线慢走", "风光记录", "旅行回望"),
            tags = listOf("青海湖", "自然旅行", "景观观察", "高原研学"),
            pois = listOf("湖岸观景点", "草地边界", "环湖路线"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "从颜色感受进入尺度、岸线和观看节奏"
        ),
        PublicCommunityPost(
            id = "${ID_PREFIX}multi-day-landscape-camp",
            title = "四天四地研学游记｜杭州—苏州—婺源—黄山的景点学习线",
            content = """
                # 四天四地研学游记

                多日旅行最怕每天都拍很多、最后什么也想不起。这次只保留一条主线：第一天看城市与水，第二天看园林与展馆，第三天读古村与建筑，第四天走进山景，沿途把风景变成四张学习卡。

                ## Day 1｜杭州：水边看城市

                从西湖全景开始，沿湖岸寻找桥、堤、树影和停留点。带走的问题是：景观怎样让城市生活靠近水？

                ## Day 2｜苏州：在园林和博物馆之间换镜头

                园林看空间转折，博物馆看历史线索，老街看今天的使用。三种场景不混成一段感想，每站只保留一组最能说明差异的照片。

                ## Day 3｜婺源：从村口整体走到老屋细节

                山、水、田、房屋先建立地图，再看溪边生活和建筑构造。每张局部图都回到整体位置，照片自然形成了滚轮式的阅读顺序。

                ## Day 4｜黄山：让山路成为结尾

                登山路线、松石关系和云雾变化把四天收束起来。回程不做宏大总结，只写一句：这次旅行让我用什么新的方法看风景？

                ## 行李箱里最后留下

                四张学习卡、七张关键照片、三条还想继续查的路线问题。研学游记的重点不是把景点写满，而是让下一次出发有新的看法。
            """.trimIndent(),
            aiAssisted = true,
            publishedAt = now - 10 * DAY_MS,
            authorLabel = "智悟研学营",
            media = media(
                "multi-day-landscape-camp",
                "west-lake.jpg",
                "west-lake-bridge.jpg",
                "west-lake-skyline.jpg",
                "suzhou-museum.jpg",
                "wuyuan-village.jpg",
                "wuyuan-village-wide.jpg",
                "huangshan.jpg"
            ),
            destination = "杭州—苏州—婺源—黄山",
            travelDate = "2026-08-07",
            travelDays = 4,
            stages = listOf("D1 水岸城市", "D2 园林展馆", "D3 古村建筑", "D4 山景路线", "总复盘"),
            tags = listOf("多日研学", "国内旅行", "景点路线", "图文游记"),
            pois = listOf("杭州西湖", "苏州园林", "婺源古村", "黄山步道"),
            likeCount = 0,
            commentCount = 0,
            curationNote = "用每日一条学习主线，把景点、移动和图文故事整理成完整游记"
        )
    )

    val publicReferences: List<CommunityPublicReference> = listOf(
        CommunityPublicReference(
            id = "west-lake-cultural-landscape",
            title = "杭州西湖文化景观",
            destination = "杭州",
            summary = "核对遗产范围与文化景观价值，再把湖岸、堤桥和城市生活转成现场观察问题。",
            sourceLabel = "中国国家地理",
            sourceUrl = "https://www.dili360.com/",
            referenceLabel = "国内资料参考",
            relatedPostId = "${ID_PREFIX}west-lake-heritage"
        ),
        CommunityPublicReference(
            id = "liangzhu-archaeological-ruins",
            title = "良渚古城遗址",
            destination = "杭州",
            summary = "从城址、水利系统和稻作社会三条公开线索出发，准备展馆参访与地景复盘。",
            sourceLabel = "中国国家地理",
            sourceUrl = "https://www.dili360.com/",
            referenceLabel = "国内资料参考",
            relatedPostId = "${ID_PREFIX}liangzhu-clue-route"
        ),
        CommunityPublicReference(
            id = "classical-gardens-suzhou",
            title = "苏州古典园林",
            destination = "苏州",
            summary = "结合公开遗产资料，用空间转折、借景和园居关系设计一张可执行的园林观察卡。",
            sourceLabel = "中国国家地理",
            sourceUrl = "https://www.dili360.com/",
            referenceLabel = "国内资料参考",
            relatedPostId = "${ID_PREFIX}suzhou-garden-street"
        ),
        CommunityPublicReference(
            id = "wulingyuan-scenic-area",
            title = "武陵源风景名胜区",
            destination = "张家界",
            summary = "先读峰林地貌与遗产保护信息，再安排远景、岩壁和步道三个记录尺度。",
            sourceLabel = "中国国家地理",
            sourceUrl = "https://www.dili360.com/",
            referenceLabel = "国内资料参考",
            relatedPostId = "${ID_PREFIX}zhangjiajie-landform"
        ),
        CommunityPublicReference(
            id = "mount-huangshan",
            title = "黄山",
            destination = "黄山",
            summary = "把自然与文化遗产资料转成山路、松石和天气变化三组现场观察提示。",
            sourceLabel = "中国国家地理",
            sourceUrl = "https://www.dili360.com/",
            referenceLabel = "国内资料参考",
            relatedPostId = "${ID_PREFIX}huangshan-mountain"
        ),
        CommunityPublicReference(
            id = "mausoleum-first-qin-emperor",
            title = "秦始皇陵",
            destination = "西安",
            summary = "用公开考古遗产资料核对阵列、制作工艺与保护语境，避免把推测写成结论。",
            sourceLabel = "中国国家地理",
            sourceUrl = "https://www.dili360.com/",
            referenceLabel = "国内资料参考",
            relatedPostId = "${ID_PREFIX}xian-history-route"
        )
    )

    /**
     * Public activity notices captured from real Hudongba detail pages on 2026-08-20 (UTC+8).
     * The linked source page remains authoritative for availability, schedule, fees, capacity,
     * and registration details.
     */
    val activityNotices: List<CommunityActivityNotice> = listOf(
        CommunityActivityNotice(
            id = "zhejiang-nanxi-river-route",
            title = "国庆5天｜浙南秘境｜楠溪江·丽水·雁荡山·云和梯田",
            dateLabel = "2026年10月1日 09:00",
            locationLabel = "浙江 · 杭州集合",
            priceLabel = "￥980起",
            summary = "浙南多日自然与人文路线，包含楠溪江、丽水、雁荡山等地，行程与报名以互动吧详情页为准。",
            sourceLabel = "互动吧活动详情",
            sourceUrl = "https://party.hudongba.com/party/5t157.html",
            verifiedOn = ACTIVITY_SNAPSHOT_DATE
        ),
        CommunityActivityNotice(
            id = "zhejiang-ningbo-reading-salon",
            title = "【悦阁茗读院】第316期",
            dateLabel = "2026年8月22日 18:45",
            locationLabel = "浙江 · 宁波鄞州区",
            priceLabel = "￥0起",
            summary = "宁波本地阅读交流活动，适合记录分享主题、现场观点与个人行动计划。",
            sourceLabel = "互动吧活动详情",
            sourceUrl = "https://party.hudongba.com/party/2fi57.html",
            verifiedOn = ACTIVITY_SNAPSHOT_DATE
        ),
        CommunityActivityNotice(
            id = "zhejiang-wenzhou-creative-drawing",
            title = "三江党群正和夏令营·创意绘画公益课",
            dateLabel = "2026年8月21日 09:30",
            locationLabel = "浙江 · 温州永嘉县",
            priceLabel = "免费",
            summary = "面向亲子与青少年的公益绘画活动，现场记录以互动吧页面公布的报名与场次为准。",
            sourceLabel = "互动吧活动详情",
            sourceUrl = "https://party.hudongba.com/party/o9157.html",
            verifiedOn = ACTIVITY_SNAPSHOT_DATE
        ),
        CommunityActivityNotice(
            id = "zhejiang-zhoushan-reading-healing",
            title = "阅读疗愈活动｜夏日诗光派对",
            dateLabel = "2026年8月23日 14:30",
            locationLabel = "浙江 · 舟山定海区",
            priceLabel = "免费",
            summary = "把西瓜、蝉鸣写成一首小诗的阅读活动，适合练习声音、文字与现场感受的结合记录。",
            sourceLabel = "互动吧活动详情",
            sourceUrl = "https://party.hudongba.com/party/24i57.html",
            verifiedOn = ACTIVITY_SNAPSHOT_DATE
        ),
        CommunityActivityNotice(
            id = "zhejiang-jiaxing-tcm-reading",
            title = "福源中医｜秋燥夹湿如何调养？",
            dateLabel = "2026年8月29日 09:00",
            locationLabel = "浙江 · 嘉兴平湖市",
            priceLabel = "免费",
            summary = "围绕节气与日常健康的线下分享，详情、场次和报名要求以互动吧活动页为准。",
            sourceLabel = "互动吧活动详情",
            sourceUrl = "https://party.hudongba.com/party/s4y57.html",
            verifiedOn = ACTIVITY_SNAPSHOT_DATE
        )
    )

    private val collectionPostIds: Map<String, List<String>> = mapOf(
        "${ID_PREFIX}collection-landscape" to listOf(
            "${ID_PREFIX}west-lake-heritage",
            "${ID_PREFIX}zhangjiajie-landform",
            "${ID_PREFIX}huangshan-mountain",
            "${ID_PREFIX}guilin-river-study"
        ),
        "${ID_PREFIX}collection-museum" to listOf(
            "${ID_PREFIX}liangzhu-clue-route",
            "${ID_PREFIX}sanxingdui-museum",
            "${ID_PREFIX}xian-history-route"
        ),
        "${ID_PREFIX}collection-village" to listOf(
            "${ID_PREFIX}suzhou-garden-street",
            "${ID_PREFIX}wuyuan-village",
            "${ID_PREFIX}qinghai-landscape"
        ),
        "${ID_PREFIX}collection-multi-day" to listOf(
            "${ID_PREFIX}multi-day-landscape-camp",
            "${ID_PREFIX}west-lake-heritage",
            "${ID_PREFIX}suzhou-garden-street"
        )
    )

    val collections: List<CommunityCollection> = listOf(
        collection(
            id = "${ID_PREFIX}collection-landscape",
            title = "山水景观怎么学",
            description = "沿湖岸、山路和江面走，把风景看成一堂户外课",
            destination = "国内山水路线",
            theme = "景观观察",
            displayOrder = 1,
            bookmarkCount = 0,
            coverPostId = "${ID_PREFIX}zhangjiajie-landform",
            coverFileName = "zhangjiajie.jpg"
        ),
        collection(
            id = "${ID_PREFIX}collection-museum",
            title = "博物馆里的中国",
            description = "从展品、讲解和馆外景观进入历史现场",
            destination = "国内文化场馆",
            theme = "文化线索",
            displayOrder = 2,
            bookmarkCount = 0,
            coverPostId = "${ID_PREFIX}liangzhu-clue-route",
            coverFileName = "liangzhu-museum.jpg"
        ),
        collection(
            id = "${ID_PREFIX}collection-village",
            title = "古村与城市慢走",
            description = "景点之外，再看看建筑、街巷和正在发生的生活",
            destination = "古镇古村路线",
            theme = "旅行观察",
            displayOrder = 3,
            bookmarkCount = 0,
            coverPostId = "${ID_PREFIX}wuyuan-village",
            coverFileName = "wuyuan-village.jpg"
        ),
        collection(
            id = "${ID_PREFIX}collection-multi-day",
            title = "多日研学旅行",
            description = "按天保留一条主线，收拢景点、移动和图文故事",
            destination = "跨城旅行路线",
            theme = "路线游记",
            displayOrder = 4,
            bookmarkCount = 0,
            coverPostId = "${ID_PREFIX}multi-day-landscape-camp",
            coverFileName = "west-lake.jpg"
        )
    )

    val facets = CommunityFacets(
        destinations = posts.map(PublicCommunityPost::destination).distinct(),
        tags = posts.flatMap(PublicCommunityPost::tags).distinct(),
        pois = posts.flatMap(PublicCommunityPost::pois).distinct()
    )

    fun isSampleId(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun post(id: String): PublicCommunityPost? = posts.firstOrNull { it.id == id }

    fun referenceForPost(postId: String): CommunityPublicReference? =
        publicReferences.firstOrNull { it.relatedPostId == postId }

    fun collection(id: String): CommunityCollection? = collections.firstOrNull { it.id == id }

    fun postsForCollection(id: String): List<PublicCommunityPost> =
        collectionPostIds[id].orEmpty().mapNotNull(::post)

    /** Bundled samples intentionally do not fabricate comments or other social interactions. */
    fun comments(@Suppress("UNUSED_PARAMETER") postId: String): List<CommunityComment> = emptyList()

    fun filteredPosts(
        query: String,
        destination: String,
        tag: String,
        poi: String,
        minDays: Int,
        maxDays: Int,
        hasMedia: Boolean
    ): List<PublicCommunityPost> = posts.filter { post ->
        val normalizedQuery = query.trim()
        (normalizedQuery.isBlank() || listOf(
            post.title,
            post.content,
            post.destination,
            post.tags.joinToString(),
            post.pois.joinToString()
        ).any { it.contains(normalizedQuery, ignoreCase = true) }) &&
            (destination.isBlank() || post.destination == destination) &&
            (tag.isBlank() || tag in post.tags) &&
            (poi.isBlank() || poi in post.pois) &&
            (minDays <= 0 || post.travelDays >= minDays) &&
            (maxDays <= 0 || post.travelDays <= maxDays) &&
            (!hasMedia || post.media.isNotEmpty())
    }

    private fun collection(
        id: String,
        title: String,
        description: String,
        destination: String,
        theme: String,
        displayOrder: Int,
        bookmarkCount: Int,
        coverPostId: String,
        coverFileName: String
    ): CommunityCollection {
        val postCount = collectionPostIds[id].orEmpty().size
        return CommunityCollection(
            id = id,
            title = title,
            description = description,
            destination = destination,
            theme = theme,
            displayOrder = displayOrder,
            assignedPostCount = postCount,
            visiblePostCount = postCount,
            postCount = postCount,
            bookmarkCount = bookmarkCount,
            coverPostId = coverPostId,
            coverThumbnailUrl = "$ASSET_ROOT$coverFileName",
            publishedAt = now - 11 * DAY_MS
        )
    }

    private fun media(owner: String, vararg fileNames: String): List<PublicCommunityMedia> =
        fileNames.mapIndexed { index, fileName ->
            val source = "$ASSET_ROOT$fileName"
            PublicCommunityMedia(
                id = "$ID_PREFIX$owner-media-${index + 1}",
                thumbnailUrl = source,
                contentUrl = source,
                mimeType = "image/jpeg"
            )
        }
}
