package com.oa.automation.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BaseText = TextStyle(
    fontFamily = FontFamily.SansSerif,
    letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = BaseText.copy(fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp),
    displayMedium = BaseText.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp),
    displaySmall = BaseText.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineLarge = BaseText.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    headlineMedium = BaseText.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 30.sp),
    headlineSmall = BaseText.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = BaseText.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = BaseText.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = BaseText.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = BaseText.copy(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = BaseText.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = BaseText.copy(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = BaseText.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = BaseText.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = BaseText.copy(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp)
)
