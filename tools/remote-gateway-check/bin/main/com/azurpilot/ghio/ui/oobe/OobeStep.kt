package com.azurpilot.ghio.ui.oobe

import androidx.annotation.StringRes
import com.azurpilot.ghio.R

/**
 * 开箱即用体验（OOBE）的各个步骤定义
 *
 * 按顺序引导用户完成欢迎、语言、使用须知、外观、权限、功能导览与就绪。
 *
 * Steps definition for the Out-of-Box Experience (OOBE).
 *
 * Guides users through welcome, language, guidelines, appearance, permissions, tour, and readiness.
 */
enum class OobeStep(@param:StringRes val titleRes: Int) {
    /** 品牌欢迎。 / Brand welcome. */
    WELCOME(R.string.oobe_step_welcome),

    /** 选择显示语言。 / Select the display language. */
    LANGUAGE(R.string.oobe_pref_lang_title),

    /** 社区规范的独立确认门。 / Separate acknowledgement gate for community guidelines. */
    AGREEMENT(R.string.oobe_welcome_guidelines_title),

    /** 主题与外观偏好。 / Theme and appearance preferences. */
    PREFERENCES(R.string.oobe_step_preferences),

    /** 特权模式与权限配置 / Privileged mode and permission setup. */
    PRIVILEGED(R.string.oobe_step_privileged),

    /** 核心工作流与新手教程 / Core workflow and beginner tutorial. */
    TUTORIAL(R.string.oobe_step_tutorial),

    /** 准备就绪与配置摘要 / Ready to go and configuration summary. */
    READY(R.string.oobe_step_ready),
}
