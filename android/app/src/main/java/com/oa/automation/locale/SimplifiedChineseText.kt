package com.oa.automation.locale

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi

object SimplifiedChineseText {
    private val transliterator: Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance("Traditional-Simplified") }.getOrNull()
        } else {
            null
        }
    }

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        val converted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            transliterateApi29(text)
        } else {
            fallback(text)
        }
        return converted
            .replace(Regex("<[^>\\r\\n]{0,120}>"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun transliterateApi29(text: String): String =
        transliterator?.transliterate(text) ?: fallback(text)

    // API 26-28 do not expose ICU Transliterator. This covers common STT variants;
    // the server OpenCC conversion remains the authoritative full conversion.
    internal fun fallback(text: String): String = buildString(text.length) {
        text.forEach { character -> append(COMMON_TRADITIONAL_TO_SIMPLIFIED[character] ?: character) }
    }

    private val COMMON_TRADITIONAL_TO_SIMPLIFIED = mapOf(
        '會' to '会', '議' to '议', '記' to '记', '錄' to '录', '聽' to '听',
        '說' to '说', '話' to '话', '價' to '价', '格' to '格', '塊' to '块',
        '與' to '与', '為' to '为', '後' to '后', '這' to '这', '個' to '个',
        '們' to '们', '來' to '来', '時' to '时', '間' to '间', '開' to '开',
        '關' to '关', '閉' to '闭', '發' to '发', '現' to '现', '實' to '实',
        '際' to '际', '應' to '应', '該' to '该', '還' to '还', '沒' to '没',
        '點' to '点', '項' to '项', '題' to '题', '報' to '报', '總' to '总',
        '結' to '结', '確' to '确', '認' to '认', '進' to '进', '行' to '行',
        '負' to '负', '責' to '责', '聯' to '联', '繫' to '系', '資' to '资',
        '訊' to '讯', '圖' to '图', '檔' to '档', '務' to '务', '業' to '业',
        '專' to '专', '員' to '员', '場' to '场', '機' to '机', '構' to '构',
        '劃' to '划', '預' to '预', '計' to '计', '達' to '达', '標' to '标',
        '準' to '准', '備' to '备', '據' to '据', '統' to '统', '過' to '过',
        '程' to '程', '質' to '质', '量' to '量', '長' to '长', '線' to '线',
        '對' to '对', '於' to '于', '從' to '从', '請' to '请', '儘' to '尽'
    )
}
