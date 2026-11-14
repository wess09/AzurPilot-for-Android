@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.azurpilot.ghio.ui.oobe

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.azurpilot.ghio.R

/** 从连接手机取得 MiSans 可变字体，保留原始字形与字重轴。 / Original MiSans variable fonts from the connected phone. */
internal val OobeMiuixFont = FontFamily(listOf(300 to 250, 400 to 330, 500 to 380, 600 to 520, 700 to 630).map { (weight, axis) ->
    // MiSans 的命名字重轴值与 CSS/Compose 字重不同，Regular 实际为 330。
    Font(R.font.oobe_misans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(axis)))
})

/** 品牌字标使用原始 MiSans Latin。 / Uses original MiSans Latin for the wordmark. */
internal val OobeBrandFont = FontFamily(Font(R.font.oobe_misans_latin, FontWeight.Medium,
    variationSettings = FontVariation.Settings(FontVariation.weight(380))))

/** 仅为 Miuix 激活页提供系统一致的字体。 / Applies consistent typography only to Miuix activation pages. */
internal fun Typography.oobeMiuix(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = OobeMiuixFont),
    displayMedium = displayMedium.copy(fontFamily = OobeMiuixFont),
    displaySmall = displaySmall.copy(fontFamily = OobeMiuixFont),
    headlineLarge = headlineLarge.copy(fontFamily = OobeMiuixFont),
    headlineMedium = headlineMedium.copy(fontFamily = OobeMiuixFont),
    headlineSmall = headlineSmall.copy(fontFamily = OobeMiuixFont),
    titleLarge = titleLarge.copy(fontFamily = OobeMiuixFont),
    titleMedium = titleMedium.copy(fontFamily = OobeMiuixFont),
    titleSmall = titleSmall.copy(fontFamily = OobeMiuixFont),
    bodyLarge = bodyLarge.copy(fontFamily = OobeMiuixFont),
    bodyMedium = bodyMedium.copy(fontFamily = OobeMiuixFont),
    bodySmall = bodySmall.copy(fontFamily = OobeMiuixFont),
    labelLarge = labelLarge.copy(fontFamily = OobeMiuixFont),
    labelMedium = labelMedium.copy(fontFamily = OobeMiuixFont),
    labelSmall = labelSmall.copy(fontFamily = OobeMiuixFont),
)
