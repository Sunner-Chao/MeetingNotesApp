package com.oa.automation.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal const val BEIJING_TIME_ZONE_ID = "Asia/Shanghai"

internal fun formatBeijingTime(timestampMillis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.SIMPLIFIED_CHINESE).apply {
        timeZone = TimeZone.getTimeZone(BEIJING_TIME_ZONE_ID)
    }.format(Date(timestampMillis))
