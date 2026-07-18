package com.oa.automation.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

private val simplifiedChinese = Locale.forLanguageTag("zh-CN")

fun Context.withSimplifiedChineseLocale(): Context {
    Locale.setDefault(simplifiedChinese)
    val localized = Configuration(resources.configuration).apply {
        setLocale(simplifiedChinese)
        setLocales(LocaleList(simplifiedChinese))
        setLayoutDirection(simplifiedChinese)
    }
    return createConfigurationContext(localized)
}
