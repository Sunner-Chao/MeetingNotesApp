package com.oa.automation.infrastructure.textimport

import android.content.Context
import android.content.Intent

data class ExternalTextSource(
    val id: String,
    val label: String,
    val packageNames: List<String>
)

class ExternalTextSourceLauncher(private val context: Context) {
    private val appContext = context.applicationContext

    fun availableSources(): List<ExternalTextSource> = SOURCES.filter { source ->
        source.packageNames.any { packageName ->
            appContext.packageManager.getLaunchIntentForPackage(packageName) != null
        }
    }

    fun open(source: ExternalTextSource): Result<Unit> = runCatching {
        val intent = source.packageNames.firstNotNullOfOrNull { packageName ->
            appContext.packageManager.getLaunchIntentForPackage(packageName)
        } ?: error("未检测到${source.label}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    companion object {
        private val SOURCES = listOf(
            ExternalTextSource("feishu", "飞书", listOf("com.ss.android.lark", "com.larksuite.suite")),
            ExternalTextSource("wechat", "微信", listOf("com.tencent.mm")),
            ExternalTextSource("qq", "QQ", listOf("com.tencent.mobileqq"))
        )
    }
}
